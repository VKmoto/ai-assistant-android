#!/usr/bin/env node
/**
 * `tasks` — CLI таск-менеджера для агента-секретаря (Масяни).
 *
 * Почему CLI, а не function calling: агенты у нас — треды BB, они умеют запускать
 * команды; так инструмент работает одинаково из чата, из automation и из shell, и его
 * легко проверить глазами (`tasks list --json`). Полный HTTP API остаётся для приложения.
 *
 * Токен service читается из /opt/bbx-tasks/etc/tasks.env (файл 600, только root/bbx);
 * в аргументах и логах он не появляется.
 */
import { readFileSync } from "node:fs";

const ENV_FILE = process.env.TASKS_ENV ?? "/opt/bbx-tasks/etc/tasks.env";

function envFromFile(): Record<string, string> {
  const out: Record<string, string> = {};
  try {
    for (const line of readFileSync(ENV_FILE, "utf8").split("\n")) {
      const m = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
      if (m) out[m[1]!] = m[2]!;
    }
  } catch {
    /* нет файла — ниже упадём с понятной ошибкой */
  }
  return out;
}

const env = { ...envFromFile(), ...process.env } as Record<string, string | undefined>;
const token = env.TASKS_SERVICE_TOKEN;
const base = `http://${env.TASKS_BIND ?? "172.19.0.1"}:${env.TASKS_PORT ?? "8122"}`;

if (!token) {
  console.error("tasks: не задан TASKS_SERVICE_TOKEN (ожидается в /opt/bbx-tasks/etc/tasks.env)");
  process.exit(2);
}

type Args = { positional: string[]; flags: Record<string, string | boolean> };
function parse(argv: string[]): Args {
  const positional: string[] = [];
  const flags: Record<string, string | boolean> = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    if (a.startsWith("--")) {
      const eq = a.indexOf("=");
      if (eq > 0) flags[a.slice(0, eq)] = a.slice(eq + 1);
      else {
        const next = argv[i + 1];
        if (next !== undefined && !next.startsWith("--")) {
          flags[a] = next;
          i++;
        } else flags[a] = true;
      }
    } else positional.push(a);
  }
  return { positional, flags };
}

async function api(method: string, path: string, body?: unknown): Promise<unknown> {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text === "" ? null : JSON.parse(text);
  if (!res.ok) {
    const message = (data as { error?: { message?: string } } | null)?.error?.message ?? `HTTP ${res.status}`;
    throw new Error(message);
  }
  return data;
}

function usage(): never {
  console.error(`tasks — таск-менеджер

  tasks add "<заголовок>" [--due "2026-11-27 09:00"] [--project p_x] [--priority 1..4] [--source agent]
  tasks list [--view inbox|today|upcoming|kanban|project:<id>] [--q текст] [--json]
  tasks today [--json]
  tasks done <id>
  tasks move <id> <todo|in_progress|waiting|done|cancelled>
  tasks subtask <parent_id> "<заголовок>"
  tasks remind <id> "<2026-11-27 09:00>" [--channels local,max,email]
  tasks reminders <id> [--json]          # напоминания задачи и их id
  tasks unremind <reminder_id>           # удалить напоминание
  tasks reschedule <id> "<2026-11-30 09:00>"  # перенести срок (напоминания снимаются)
  tasks undo <agent_action_id>
  tasks actions [--limit N] [--json]
  tasks inbox "<заголовок>" [--source email|max|telegram|pipeline] [--ref-url URL]
`);
  process.exit(2);
}

function fmt(task: Record<string, unknown>): string {
  // API отдаёт UTC (правильно для данных), но агенту и человеку нужно МСК: Москва
  // фиксирована в +03:00, поэтому показываем сдвинутое время.
  const due = task["due_at"]
    ? new Date(new Date(String(task["due_at"])).getTime() + 3 * 3600_000)
        .toISOString()
        .slice(0, 16)
        .replace("T", " ")
    : "—";
  return `${task["id"]}  [${task["status"]}] p${task["priority"]}  ${due}  ${task["title"]}`;
}

async function main(): Promise<void> {
  const [cmd, ...rest] = process.argv.slice(2);
  const { positional, flags } = parse(rest);
  const json = Boolean(flags["--json"]);

  switch (cmd) {
    case "add": {
      const title = positional.join(" ").trim();
      if (!title) usage();
      const body: Record<string, unknown> = { title, source: flags["--source"] ?? "agent" };
      if (typeof flags["--due"] === "string") body["due_at"] = flags["--due"];
      if (typeof flags["--project"] === "string") body["project_id"] = flags["--project"];
      if (typeof flags["--priority"] === "string") body["priority"] = Number(flags["--priority"]);
      if (typeof flags["--parent"] === "string") body["parent_id"] = flags["--parent"];
      const task = (await api("POST", "/api/v1/tasks", body)) as Record<string, unknown>;
      console.log(json ? JSON.stringify(task, null, 2) : `создана: ${fmt(task)}`);
      return;
    }
    case "list": {
      const view = typeof flags["--view"] === "string" ? flags["--view"] : "inbox";
      const q = typeof flags["--q"] === "string" ? `&q=${encodeURIComponent(flags["--q"])}` : "";
      const data = (await api("GET", `/api/v1/tasks?view=${encodeURIComponent(view)}${q}`)) as { tasks: Record<string, unknown>[] };
      if (json) return console.log(JSON.stringify(data.tasks, null, 2));
      if (data.tasks.length === 0) return console.log("пусто");
      data.tasks.forEach((t) => console.log(fmt(t)));
      return;
    }
    case "today":
      return mainList("today", json);
    case "done": {
      const id = positional[0];
      if (!id) usage();
      const task = (await api("POST", `/api/v1/tasks/${id}/complete`)) as Record<string, unknown>;
      console.log(json ? JSON.stringify(task, null, 2) : `готово: ${fmt(task)}`);
      return;
    }
    case "move": {
      const [id, status] = positional;
      if (!id || !status) usage();
      const task = (await api("PATCH", `/api/v1/tasks/${id}`, { status })) as Record<string, unknown>;
      console.log(json ? JSON.stringify(task, null, 2) : `статус: ${fmt(task)}`);
      return;
    }
    case "subtask": {
      const [parent, ...titleParts] = positional;
      const title = titleParts.join(" ").trim();
      if (!parent || !title) usage();
      const task = (await api("POST", `/api/v1/tasks/${parent}/subtasks`, { title })) as Record<string, unknown>;
      console.log(json ? JSON.stringify(task, null, 2) : `подзадача: ${fmt(task)}`);
      return;
    }
    case "remind": {
      const [id, when] = positional;
      if (!id || !when) usage();
      const channels = typeof flags["--channels"] === "string" ? flags["--channels"].split(",") : undefined;
      const r = (await api("POST", `/api/v1/tasks/${id}/reminders`, { fire_at: when, channels })) as Record<string, unknown>;
      console.log(json ? JSON.stringify(r, null, 2) : `напоминание ${r["id"]} на ${when}`);
      return;
    }
    case "reminders": {
      const id = positional[0];
      if (!id) usage();
      const data = (await api("GET", `/api/v1/tasks/${id}/reminders`)) as { reminders: Record<string, unknown>[] };
      if (json) return console.log(JSON.stringify(data.reminders, null, 2));
      if (data.reminders.length === 0) return console.log("напоминаний нет");
      data.reminders.forEach((r) => {
        const at = r["fire_at"]
          ? new Date(new Date(String(r["fire_at"])).getTime() + 3 * 3600_000).toISOString().slice(0, 16).replace("T", " ")
          : "—";
        const channels = Array.isArray(r["channels"]) ? (r["channels"] as string[]).join(",") : "—";
        console.log(`${r["id"]}  [${r["status"]}]  ${at} МСК  ${channels}`);
      });
      return;
    }
    case "unremind": {
      const id = positional[0];
      if (!id) usage();
      await api("DELETE", `/api/v1/reminders/${id}`);
      console.log(`напоминание ${id} удалено`);
      return;
    }
    case "reschedule": {
      const [id, when] = positional;
      if (!id || !when) usage();
      // Сервер сам снимает pending-напоминания задачи при смене due_at: напоминание
      // относится ко времени задачи и иначе противоречило бы новому сроку.
      const task = (await api("PATCH", `/api/v1/tasks/${id}`, { due_at: when })) as Record<string, unknown>;
      console.log(json ? JSON.stringify(task, null, 2) : `перенесена: ${fmt(task)} (напоминания сняты, поставьте новое через \`tasks remind\`)`);
      return;
    }
    case "undo": {
      const id = positional[0];
      if (!id) usage();
      await api("POST", `/api/v1/agent-actions/${id}/undo`);
      console.log("отменено");
      return;
    }
    case "actions": {
      const limit = typeof flags["--limit"] === "string" ? flags["--limit"] : "20";
      const data = (await api("GET", `/api/v1/agent-actions?limit=${limit}`)) as { actions: Record<string, unknown>[] };
      if (json) return console.log(JSON.stringify(data.actions, null, 2));
      data.actions.forEach((a) =>
        console.log(`${a["id"]}  ${String(a["created_at"]).slice(0, 16).replace("T", " ")}  ${a["action"]} ${a["entity"]} ${a["entity_id"] ?? ""}${a["undone_at"] ? "  (отменено)" : ""}`),
      );
      return;
    }
    case "inbox": {
      const title = positional.join(" ").trim();
      if (!title) usage();
      const body: Record<string, unknown> = {
        title,
        source: flags["--source"] ?? "pipeline",
        source_ref: typeof flags["--ref-url"] === "string" ? { url: flags["--ref-url"] } : null,
      };
      const task = (await api("POST", "/api/v1/inbox", body)) as Record<string, unknown>;
      console.log(json ? JSON.stringify(task, null, 2) : `во входящих: ${fmt(task)}`);
      return;
    }
    default:
      usage();
  }
}

async function mainList(view: string, json: boolean): Promise<void> {
  const data = (await api("GET", `/api/v1/tasks?view=${view}`)) as { tasks: Record<string, unknown>[] };
  if (json) return console.log(JSON.stringify(data.tasks, null, 2));
  if (data.tasks.length === 0) return console.log("пусто");
  data.tasks.forEach((t) => console.log(fmt(t)));
}

main().catch((error) => {
  console.error(`tasks: ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});
