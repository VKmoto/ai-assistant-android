#!/usr/bin/env node
/**
 * Живой smoke-тест bbx-tasks: настоящий HTTP к запущенному сервису.
 *
 * Почему не только unit: контракт здесь — HTTP + SQL-инварианты (глубина подзадач,
 * soft-delete, undo, независимые каналы напоминания). Проверять их на моках значило бы
 * проверять моки; поэтому тест создаёт свои объекты в живой базе и удаляет их за собой.
 */
import { readFileSync } from "node:fs";

const env = {};
for (const line of readFileSync("/opt/bbx-tasks/etc/tasks.env", "utf8").split("\n")) {
  const m = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
  if (m) env[m[1]] = m[2];
}
const BASE = `http://${env.TASKS_BIND ?? "172.19.0.1"}:${env.TASKS_PORT ?? "8122"}`;
const DEVICE = env.TASKS_DEVICE_TOKEN;
const SERVICE = env.TASKS_SERVICE_TOKEN;

let pass = 0;
const fails = [];
async function check(name, fn) {
  try {
    await fn();
    pass++;
    console.log(`  ok  ${name}`);
  } catch (e) {
    fails.push(`${name}: ${e?.message ?? e}`);
    console.log(`  FAIL ${name}: ${e?.message ?? e}`);
  }
}
const api = async (method, path, token = DEVICE, body) => {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, data: text ? JSON.parse(text) : null };
};

const created = [];
let reminderId = null;
let pomodoroId = null;

await check("healthz отвечает ok", async () => {
  const r = await api("GET", "/healthz");
  if (r.status !== 200 || !r.data.ok) throw new Error(JSON.stringify(r));
});

await check("без токена — 401", async () => {
  const res = await fetch(`${BASE}/api/v1/tasks?view=inbox`);
  if (res.status !== 401) throw new Error(`status ${res.status}`);
});

await check("создание задачи: время МСК → UTC", async () => {
  const r = await api("POST", "/api/v1/tasks", DEVICE, {
    title: "smoke: задача",
    due_at: "2026-12-01T09:00:00+03:00",
  });
  if (r.status !== 201) throw new Error(JSON.stringify(r));
  created.push(r.data.id);
  if (!String(r.data.due_at).startsWith("2026-12-01T06:00")) throw new Error(`due ${r.data.due_at}`);
});

await check("список показывает задачу и has_reminder=false", async () => {
  const r = await api("GET", "/api/v1/tasks?view=kanban");
  const t = r.data.tasks.find((x) => x.title === "smoke: задача");
  if (!t) throw new Error("задачи нет в списке");
  if (t.has_reminder !== false) throw new Error("has_reminder должен быть false");
});

await check("напоминание: has_reminder становится true", async () => {
  const r = await api("POST", `/api/v1/tasks/${created[0]}/reminders`, DEVICE, {
    fire_at: "2026-12-01T09:00:00+03:00",
    channels: ["local"],
  });
  if (r.status !== 201) throw new Error(JSON.stringify(r));
  reminderId = r.data.id;
  const list = await api("GET", "/api/v1/tasks?view=kanban");
  const t = list.data.tasks.find((x) => x.id === created[0]);
  if (t?.has_reminder !== true) throw new Error("has_reminder не поднялся");
});

await check("upcoming отдаёт будущее напоминание", async () => {
  const r = await api("GET", "/api/v1/reminders/upcoming?hours=240");
  if (!r.data.reminders.some((x) => x.id === reminderId)) throw new Error("напоминания нет");
});

await check("перенос срока снимает напоминание", async () => {
  const patch = await api("PATCH", `/api/v1/tasks/${created[0]}`, DEVICE, { due_at: "2026-12-02 10:00" });
  if (patch.status !== 200) throw new Error(JSON.stringify(patch));
  if (!String(patch.data.due_at).startsWith("2026-12-02T07:00")) throw new Error(`МСК: ${patch.data.due_at}`);
  const list = await api("GET", `/api/v1/tasks/${created[0]}/reminders`);
  const pending = list.data.reminders.filter((r) => r.status === "pending");
  if (pending.length !== 0) throw new Error(`осталось pending: ${pending.length}`);
});

await check("удаление напоминания вручную", async () => {
  const made = await api("POST", `/api/v1/tasks/${created[0]}/reminders`, DEVICE, {
    fire_at: "2026-12-03 09:00",
    channels: ["local"],
  });
  if (made.status !== 201) throw new Error(JSON.stringify(made));
  const del = await api("DELETE", `/api/v1/reminders/${made.data.id}`, DEVICE);
  if (del.status !== 200) throw new Error(JSON.stringify(del));
  const list = await api("GET", `/api/v1/tasks/${created[0]}/reminders`);
  const row = list.data.reminders.find((r) => r.id === made.data.id);
  if (!row || row.status !== "cancelled") throw new Error(`статус ${row?.status}`);
});

await check("глубина подзадач: 7 уровней, 8-й отклоняется", async () => {
  let parent = created[0];
  for (let i = 0; i < 6; i++) {
    const r = await api("POST", `/api/v1/tasks/${parent}/subtasks`, DEVICE, { title: `smoke: подзадача ${i}` });
    if (r.status !== 201) throw new Error(`уровень ${i + 1}: ${JSON.stringify(r)}`);
    created.push(r.data.id);
    parent = r.data.id;
  }
  const tooDeep = await api("POST", `/api/v1/tasks/${parent}/subtasks`, DEVICE, { title: "smoke: слишком глубоко" });
  if (tooDeep.status !== 400) throw new Error(`ожидали 400, получили ${tooDeep.status}`);
});

await check("завершение задачи", async () => {
  const r = await api("POST", `/api/v1/tasks/${created[0]}/complete`);
  if (r.status !== 200 || r.data.status !== "done") throw new Error(JSON.stringify(r));
});

await check("помодоро: старт → state → finish → stats", async () => {
  const start = await api("POST", "/api/v1/pomodoro/start", DEVICE, { duration_sec: 60 });
  if (start.status !== 201) throw new Error(JSON.stringify(start));
  pomodoroId = start.data.id;
  const state = await api("GET", "/api/v1/pomodoro/state");
  if (state.data.session?.id !== pomodoroId) throw new Error("активной сессии нет");
  const fin = await api("POST", `/api/v1/pomodoro/${pomodoroId}/finish`);
  if (fin.status !== 200) throw new Error(JSON.stringify(fin));
  const stats = await api("GET", "/api/v1/pomodoro/stats");
  if (typeof stats.data.sessions !== "number") throw new Error(JSON.stringify(stats));
});

await check("действия агента и undo", async () => {
  const upd = await api("PATCH", `/api/v1/tasks/${created[0]}`, SERVICE, { priority: 2 });
  if (upd.status !== 200) throw new Error(JSON.stringify(upd));
  const actions = await api("GET", "/api/v1/agent-actions?limit=5", SERVICE);
  const action = actions.data.actions.find((a) => a.entity_id === created[0] && !a.undone_at);
  if (!action) throw new Error("действие агента не записано");
  const undo = await api("POST", `/api/v1/agent-actions/${action.id}/undo`, SERVICE);
  if (undo.status !== 200) throw new Error(JSON.stringify(undo));
});

// cleanup
for (const id of created.reverse()) await api("DELETE", `/api/v1/tasks/${id}`, SERVICE).catch(() => {});
if (reminderId) await api("POST", `/api/v1/reminders/${reminderId}/snooze`, DEVICE, { minutes: 1 }).catch(() => {});

console.log(`\nbbx-tasks smoke: ${pass} checks passed${fails.length ? `, ${fails.length} FAILED` : ""}`);
if (fails.length) { for (const f of fails) console.error(" - " + f); process.exit(1); }
