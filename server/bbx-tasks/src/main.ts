/**
 * bbx-tasks — REST API таск-менеджера для приложения и агента-секретаря.
 *
 * Стек намеренно тот же, что у bbx-voice/bbx-connector: Node + Postgres, systemd,
 * порт только в docker-сети. Мессенджеры напрямую не вызываются (§11.12 роутера):
 * уведомления кладутся в bbx.outbound_msg, доставляет bbx-connector.
 */
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { Pool } from "pg";

import { loadConfig, type TasksConfig } from "./config.js";
import { Store } from "./db.js";

const cfg: TasksConfig = loadConfig();
const pool = new Pool({ connectionString: cfg.dsn, max: 5 });
const store = new Store(pool, cfg.timezone, cfg.maxDepth);
const startedAt = Date.now();

function log(fields: Record<string, unknown>): void {
  console.log(JSON.stringify({ ts: new Date().toISOString(), svc: "bbx-tasks", ...fields }));
}

/** Постоянное время сравнения: длина утекает, содержимое — нет. */
function tokenMatches(header: string | undefined, want: string): boolean {
  if (!header || !header.startsWith("Bearer ")) return false;
  const given = Buffer.from(header.slice(7).trim());
  const expected = Buffer.from(want);
  if (given.length !== expected.length) return false;
  return timingSafeEqual(given, expected);
}

function sendJson(res: ServerResponse, code: number, body: unknown): void {
  const text = JSON.stringify(body);
  res.writeHead(code, { "content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(text), "cache-control": "no-store" });
  res.end(text);
}

function fail(res: ServerResponse, code: number, message: string): void {
  sendJson(res, code, { error: { message, code } });
}

function readBody(req: IncomingMessage, limit = 256 * 1024): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error("body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function newId(prefix: string): string {
  return `${prefix}_${randomUUID().slice(0, 8)}`;
}

/** Кому доставлять уведомления: последний чат владельца в MAX/Telegram. */
/**
 * Чат владельца ДЛЯ КОНКРЕТНОГО канала.
 *
 * Раньше брался просто «последний чат владельца» без канала. Если последним был MAX,
 * напоминание с каналами {max,telegram} уходило дважды в MAX (по разу за каждый канал),
 * а в Telegram не уходило вовсе (жалоба владельца 2026-11-27). Теперь канал — часть
 * ключа: нет чата в этом канале — этот канал просто пропускается.
 */
async function ownerChat(channel: "max" | "telegram"): Promise<{ chatId: string; chatType: string } | null> {
  const r = await pool.query<{ chat_id: string; chat_type: string }>(
    `SELECT i.chat_id, i.chat_type
       FROM bbx.inbound_msg i JOIN bbx.person p ON p.id = i.person_id
      WHERE p.role='owner' AND i.channel = $1
      ORDER BY i.received_at DESC LIMIT 1`,
    [channel],
  );
  const row = r.rows[0];
  return row ? { chatId: row.chat_id, chatType: row.chat_type } : null;
}

/** Тихие часы владельца (МСК): "23:00-08:00". */
function inQuietHours(): boolean {
  const [from, to] = cfg.quietHours.split("-");
  if (!from || !to) return false;
  const now = new Date();
  const mskMinutes = (now.getUTCHours() * 60 + now.getUTCMinutes() + 180) % 1440;
  const parts = (s: string) => {
    const [h, m] = s.split(":").map(Number);
    return (h ?? 0) * 60 + (m ?? 0);
  };
  const a = parts(from);
  const b = parts(to);
  return a <= b ? mskMinutes >= a && mskMinutes < b : mskMinutes >= a || mskMinutes < b;
}

const server = createServer((req, res) => {
  void (async () => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const path = url.pathname;
    const method = req.method ?? "GET";
    const remote = (req.socket.remoteAddress ?? "").replace(/^::ffff:/, "");

    if (path === "/healthz") {
      // С хоста подключение к 172.19.0.1 приходит с публичного адреса (измерено на
      // bbx-voice); порт при этом закрыт ufw снаружи.
      const local =
        remote === "127.0.0.1" ||
        remote === "::1" ||
        remote === cfg.bind ||
        remote === "172.19.0.1" ||
        remote === "82.148.241.98";
      if (!local) return fail(res, 404, "not found");
      const db = await store.health();
      return sendJson(res, 200, { ok: db.ok, detail: db.detail, uptime_s: Math.floor((Date.now() - startedAt) / 1000) });
    }

    const isDevice = tokenMatches(req.headers.authorization, cfg.deviceToken);
    const isService = tokenMatches(req.headers.authorization, cfg.serviceToken);
    if (!isDevice && !isService) return fail(res, 401, "unauthorized");
    const actor = isService ? "agent" : "user";

    // --------------------------------------------------------------- задачи
    if (path === "/api/v1/tasks" && method === "GET") {
      const view = url.searchParams.get("view") ?? "inbox";
      const q = url.searchParams.get("q") ?? undefined;
      return sendJson(res, 200, { tasks: await store.listTasks(view, q) });
    }

    if (path === "/api/v1/tasks" && method === "POST") {
      const body = JSON.parse(await readBody(req)) as Record<string, unknown>;
      if (typeof body["title"] !== "string" || body["title"].trim() === "") return fail(res, 400, "title required");
      const task = await store.createTask({
        id: newId("t"),
        title: String(body["title"]).trim(),
        description: (body["description"] as string | null) ?? null,
        projectId: (body["project_id"] as string | null) ?? null,
        parentId: (body["parent_id"] as string | null) ?? null,
        priority: typeof body["priority"] === "number" ? (body["priority"] as number) : undefined,
        dueAt: (body["due_at"] as string | null) ?? null,
        dueDateOnly: Boolean(body["due_date_only"]),
        source: (body["source"] as string) ?? (isService ? "agent" : "manual"),
        sourceRef: body["source_ref"] ?? null,
        createdBy: isService ? "agent" : "user",
        recurrence: body["recurrence"] ?? null,
      });
      if (isService) {
        // Каждое действие агента пишется в журнал с обратным действием: undo для
        // создания — это soft-delete (восстановление снимет deleted_at).
        await store.recordAgentAction({
          id: newId("a"), agent: "secretary", action: "create", entity: "task", entityId: task.id,
          payload: task, reversePayload: { deleted: true }, reason: (body["reason"] as string) ?? null,
        });
      }
      return sendJson(res, 201, task);
    }

    const taskMatch = /^\/api\/v1\/tasks\/([^/]+)$/.exec(path);
    if (taskMatch) {
      const id = taskMatch[1]!;
      if (method === "PATCH") {
        const body = JSON.parse(await readBody(req)) as Record<string, unknown>;
        const before = await store.getTask(id);
        const updated = await store.patchTask(id, body, actor);
        if (updated && isService) {
          await store.recordAgentAction({
            id: newId("a"), agent: "secretary", action: "update", entity: "task", entityId: id,
            payload: body,
            reversePayload: before
              ? { status: before.status, priority: before.priority, due_at: before.due_at, title: before.title, project_id: before.project_id }
              : null,
          });
        }
        return updated ? sendJson(res, 200, updated) : fail(res, 404, "task not found");
      }
      if (method === "DELETE") {
        const ok = await store.deleteTask(id, actor);
        return ok ? sendJson(res, 200, { ok: true }) : fail(res, 404, "task not found");
      }
    }

    const completeMatch = /^\/api\/v1\/tasks\/([^/]+)\/complete$/.exec(path);
    if (completeMatch && method === "POST") {
      const before = await store.getTask(completeMatch[1]!);
      const updated = await store.completeTask(completeMatch[1]!, actor);
      if (updated && isService && before) {
        await store.recordAgentAction({
          id: newId("a"), agent: "secretary", action: "complete", entity: "task", entityId: before.id,
          payload: { status: "done" }, reversePayload: { status: before.status },
        });
      }
      return updated ? sendJson(res, 200, updated) : fail(res, 404, "task not found");
    }

    const subMatch = /^\/api\/v1\/tasks\/([^/]+)\/subtasks$/.exec(path);
    if (subMatch) {
      const parentId = subMatch[1]!;
      if (method === "GET") return sendJson(res, 200, { tasks: await store.listSubtasks(parentId) });
      if (method === "POST") {
        const body = JSON.parse(await readBody(req)) as Record<string, unknown>;
        if (typeof body["title"] !== "string" || body["title"].trim() === "") return fail(res, 400, "title required");
        const task = await store.createTask({
          id: newId("t"),
          title: String(body["title"]).trim(),
          parentId,
          projectId: (body["project_id"] as string | null) ?? null,
          createdBy: isService ? "agent" : "user",
          source: isService ? "agent" : "manual",
        });
        return sendJson(res, 201, task);
      }
    }

    // ------------------------------------------------------------- проекты
    if (path === "/api/v1/projects" && method === "GET") return sendJson(res, 200, { projects: await store.listProjects() });
    if (path === "/api/v1/projects" && method === "POST") {
      const body = JSON.parse(await readBody(req)) as Record<string, unknown>;
      if (typeof body["name"] !== "string" || body["name"].trim() === "") return fail(res, 400, "name required");
      const project = await store.createProject({
        id: newId("p"),
        name: String(body["name"]).trim(),
        parentId: (body["parent_id"] as string | null) ?? null,
        color: (body["color"] as string | null) ?? null,
      });
      return sendJson(res, 201, project);
    }

    // ----------------------------------------------------------- напоминания
    const remindMatch = /^\/api\/v1\/tasks\/([^/]+)\/reminders$/.exec(path);
    if (remindMatch && method === "POST") {
      const body = JSON.parse(await readBody(req)) as Record<string, unknown>;
      const fireAt = body["fire_at"];
      if (typeof fireAt !== "string") return fail(res, 400, "fire_at required");
      const reminder = await store.createReminder({
        id: newId("r"),
        taskId: remindMatch[1]!,
        fireAt,
        channels: Array.isArray(body["channels"]) ? (body["channels"] as string[]) : undefined,
      });
      return sendJson(res, 201, reminder);
    }

    if (path === "/api/v1/reminders/due" && method === "GET") {
      return sendJson(res, 200, { reminders: await store.dueRemindersForDevice() });
    }
    if (path === "/api/v1/reminders/upcoming" && method === "GET") {
      const hours = Number(url.searchParams.get("hours") ?? 24);
      return sendJson(res, 200, { reminders: await store.upcomingRemindersForDevice(Number.isFinite(hours) ? hours : 24) });
    }

    const snoozeMatch = /^\/api\/v1\/reminders\/([^/]+)\/snooze$/.exec(path);
    if (snoozeMatch && method === "POST") {
      const body = JSON.parse(await readBody(req)) as Record<string, unknown>;
      const minutes = typeof body["minutes"] === "number" ? (body["minutes"] as number) : 10;
      await store.snoozeReminder(snoozeMatch[1]!, minutes);
      return sendJson(res, 200, { ok: true });
    }

    // -------------------------------------------------------- журнал и undo
    if (path === "/api/v1/agent-actions" && method === "GET") {
      const limit = Number(url.searchParams.get("limit") ?? 20);
      return sendJson(res, 200, { actions: await store.listAgentActions(Number.isFinite(limit) ? limit : 20) });
    }
    const undoMatch = /^\/api\/v1\/agent-actions\/([^/]+)\/undo$/.exec(path);
    if (undoMatch && method === "POST") {
      if (!isService) return fail(res, 403, "service token required");
      const ok = await store.undoAction(undoMatch[1]!);
      return ok ? sendJson(res, 200, { ok: true }) : fail(res, 404, "action not found or already undone");
    }

    // --------------------------------------------------------------- pomodoro
    if (path === "/api/v1/pomodoro/start" && method === "POST") {
      const body = JSON.parse(await readBody(req)) as Record<string, unknown>;
      const duration = typeof body["duration_sec"] === "number" ? (body["duration_sec"] as number) : 25 * 60;
      const session = await store.startPomodoro({
        id: newId("pm"),
        taskId: (body["task_id"] as string | null) ?? null,
        kind: typeof body["kind"] === "string" ? (body["kind"] as string) : "work",
        durationSec: Math.max(60, Math.min(3 * 3600, duration)),
        channels: Array.isArray(body["channels"]) ? (body["channels"] as string[]) : ["local"],
      });
      return sendJson(res, 201, session);
    }
    const pmDone = /^\/api\/v1\/pomodoro\/([^/]+)\/(finish|cancel)$/.exec(path);
    if (pmDone && method === "POST") {
      const session = await store.finishPomodoro(pmDone[1]!, pmDone[2] === "finish" ? "completed" : "cancelled");
      return session ? sendJson(res, 200, session) : fail(res, 404, "session not found");
    }
    if (path === "/api/v1/pomodoro/state" && method === "GET") {
      return sendJson(res, 200, { session: await store.activePomodoro() });
    }
    if (path === "/api/v1/pomodoro/stats" && method === "GET") {
      return sendJson(res, 200, await store.pomodoroStats(7));
    }

    // ---------------------------------------------------------------- inbox
    if (path === "/api/v1/inbox" && method === "POST") {
      if (!isService) return fail(res, 403, "service token required");
      const body = JSON.parse(await readBody(req)) as Record<string, unknown>;
      const title = typeof body["title"] === "string" ? body["title"].trim() : "";
      if (title === "") return fail(res, 400, "title required");
      const source = typeof body["source"] === "string" ? (body["source"] as string) : "pipeline";
      const task = await store.inboxTask({
        id: newId("t"),
        title,
        source: ["email", "max", "telegram", "pipeline", "voice"].includes(source) ? source : "pipeline",
        sourceRef: body["source_ref"] ?? null,
        dueAt: (body["due_at"] as string | null) ?? null,
      });
      return sendJson(res, 201, task);
    }

    return fail(res, 404, "not found");
  })().catch((error) => {
    const message = error instanceof Error ? error.message : String(error);
    log({ event: "error", error: message });
    if (!res.headersSent) {
      // Глубина подзадач и прочие ограничения — это 400, а не 500.
      return sendJson(res, 400, { error: { message } });
    }
    res.end();
  });
});

/**
 * Воркер напоминаний.
 *
 * Каждые 15 с забирает due-строки и доставляет: MAX/Telegram — через bbx.outbound_msg
 * (роутер), email — отдельным шагом через `bbx mail` (этап 3, пока журналируется).
 * Тихие часы: мессенджеры молчат, email — исключение (решение владельца).
 */
let workerStopped = false;
let lastDigestMinute = "";

/** Дайджесты: раз в минуту проверяем, не пора ли отправить утренний/вечерний. */
async function digestTick(): Promise<void> {
  if (workerStopped) return;
  try {
    const now = new Date();
    const msk = new Date(now.getTime() + 3 * 3600_000).toISOString();
    const hhmm = msk.slice(11, 16);
    if (hhmm === lastDigestMinute) return;
    lastDigestMinute = hhmm;
    const today = msk.slice(0, 10);
    const digest = await store.dueDigest(hhmm, today);
    if (!digest) return;
    const text = await store.digestText(digest.template);
    for (const channel of ["max", "telegram"] as const) {
      if (!digest.channels.includes(channel)) continue;
      const chat = await ownerChat(channel);
      if (!chat) continue;
      await pool.query(
        `INSERT INTO bbx.outbound_msg (channel, chat_id, chat_type, agent_alias, text, kind)
         VALUES ($1,$2,$3,NULL,$4,'message')`,
        [channel, chat.chatId, chat.chatType, text],
      );
    }
    if (digest.channels.includes("email")) {
      await store.enqueueEmail(digest.template === "evening" ? "Итоги дня" : "Утренний дайджест", text);
    }
    await store.markDigestSent(digest.id);
    log({ event: "digest-sent", id: digest.id, template: digest.template });
  } catch (error) {
    log({ event: "digest-error", error: error instanceof Error ? error.message : String(error) });
  }
}

async function reminderTick(): Promise<void> {
  if (workerStopped) return;
  try {
    const due = await store.claimDueReminders(20);
    if (due.length === 0) return;
    const quiet = inQuietHours();
    for (const reminder of due) {
      const text = `⏰ Напоминание: ${reminder.title}`;
      for (const channel of reminder.channels) {
        if ((channel === "max" || channel === "telegram") && !quiet) {
          const chat = await ownerChat(channel);
          if (chat) {
            await pool.query(
              `INSERT INTO bbx.outbound_msg (channel, chat_id, chat_type, agent_alias, text, kind)
               VALUES ($1,$2,$3,NULL,$4,'message')`,
              [channel, chat.chatId, chat.chatType, text],
            );
          }
        }
        if (channel === "email") {
          // Email — исключение из тихих часов (решение владельца), поэтому quiet здесь
          // не проверяем. Саму отправку делает root-таймер (см. scripts/email-dispatch.mjs):
          // у bbx нет доступа к metafeed CLI.
          await store.enqueueEmail(`Напоминание: ${reminder.title}`, text);
        }
      }
      log({ event: "reminder-sent", id: reminder.id, quiet, channels: reminder.channels });
    }
  } catch (error) {
    log({ event: "reminder-error", error: error instanceof Error ? error.message : String(error) });
  }
}

store
  .migrate()
  .then(() => {
    server.listen(cfg.port, cfg.bind, () => {
      log({ event: "listening", bind: cfg.bind, port: cfg.port, tz: cfg.timezone });
    });
    setInterval(() => void reminderTick(), 15_000).unref();
    setInterval(() => void digestTick(), 20_000).unref();
  })
  .catch((error) => {
    log({ event: "migrate-failed", error: error instanceof Error ? error.message : String(error) });
    process.exit(1);
  });

for (const sig of ["SIGTERM", "SIGINT"] as const) {
  process.on(sig, () => {
    workerStopped = true;
    log({ event: "shutdown", signal: sig });
    server.close(() => void store.close().finally(() => process.exit(0)));
  });
}
