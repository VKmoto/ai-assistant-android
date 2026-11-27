/**
 * Схема `todo` и работа с ней.
 *
 * Почему отдельная схема, а не `bbx`: `bbx` — контракт роутера (inbound/outbound,
 * чаты, диспетчи). Задачи живут своей жизнью и не должны смешиваться с очередями
 * сообщений; при этом DSN тот же, бэкап общий.
 *
 * Решения из спеки 2.0, зашитые здесь:
 *   • удаление — soft-delete (`deleted_at`), иначе undo не восстановит задачу;
 *   • подзадачи — дерево, глубина ограничена (по умолчанию 7);
 *   • действия агента пишут `reverse_payload` — основа отката;
 *   • напоминания выбираются `FOR UPDATE SKIP LOCKED`, без Redis.
 */
import { Pool } from "pg";

export interface TaskRow {
  id: string;
  project_id: string | null;
  parent_id: string | null;
  title: string;
  description: string | null;
  status: string;
  priority: number;
  due_at: string | null;
  due_date_only: boolean;
  importance: boolean;
  urgency: boolean;
  recurrence: unknown;
  source: string;
  source_ref: unknown;
  created_by: string;
  completed_at: string | null;
  created_at: string;
  updated_at: string;
}

const SCHEMA_SQL = `
-- Схема todo создаётся администратором один раз: роли bbx_messenger CREATE на БД не
-- выдаётся, поэтому CREATE SCHEMA здесь упал бы с permission denied даже при
-- существующей схеме. Всё остальное — обычные CREATE TABLE IF NOT EXISTS.

create table if not exists todo.projects (
  id         text primary key,
  name       text not null,
  parent_id  text references todo.projects(id),
  color      text,
  icon       text,
  position   numeric not null default 0,
  archived   boolean not null default false,
  created_at timestamptz not null default now()
);

create table if not exists todo.tasks (
  id            text primary key,
  project_id    text references todo.projects(id),
  parent_id     text references todo.tasks(id),
  title         text not null,
  description   text,
  status        text not null default 'todo'
                  check (status in ('todo','in_progress','waiting','done','cancelled')),
  priority      smallint not null default 3 check (priority between 1 and 4),
  due_at        timestamptz,
  due_date_only boolean not null default false,
  importance    boolean not null default false,
  urgency       boolean not null default false,
  eisenhower_hint jsonb,
  recurrence    jsonb,
  recurrence_parent_id text references todo.tasks(id),
  source        text not null default 'manual'
                  check (source in ('manual','voice','max','telegram','pipeline','agent','email')),
  source_ref    jsonb,
  created_by    text not null default 'user' check (created_by in ('user','agent','pipeline')),
  completed_at  timestamptz,
  deleted_at    timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index if not exists tasks_parent on todo.tasks(parent_id) where deleted_at is null;
create index if not exists tasks_due on todo.tasks(due_at)
  where deleted_at is null and status not in ('done','cancelled');
create index if not exists tasks_project_status on todo.tasks(project_id, status);

create table if not exists todo.tags (
  id text primary key, name text not null unique, color text
);
create table if not exists todo.task_tags (
  task_id text references todo.tasks(id) on delete cascade,
  tag_id  text references todo.tags(id) on delete cascade,
  primary key (task_id, tag_id)
);

create table if not exists todo.attachments (
  id text primary key,
  task_id text references todo.tasks(id) on delete cascade,
  kind text not null check (kind in ('link','messenger_ref','voice_transcript')),
  url text, transport text, message_id text, transcript text,
  created_at timestamptz not null default now()
);

create table if not exists todo.reminders (
  id text primary key,
  task_id text references todo.tasks(id) on delete cascade,
  kind text not null default 'time' check (kind in ('time','geo')),
  fire_at timestamptz,
  channels text[] not null default '{local,max,email}',
  status text not null default 'pending'
    check (status in ('pending','sent','snoozed','cancelled')),
  snooze_until timestamptz,
  sent_at timestamptz,
  attempts int not null default 0,
  last_error text,
  created_at timestamptz not null default now()
);
create index if not exists reminders_due on todo.reminders(fire_at) where status = 'pending';
-- Каналы доставляются независимо: приложение (local) и воркер (max/telegram/email).
-- Без отдельных отметок воркер помечал строку sent до того, как телефон её заберёт.
alter table todo.reminders add column if not exists local_sent_at timestamptz;
alter table todo.reminders add column if not exists remote_sent_at timestamptz;

create table if not exists todo.pomodoro_sessions (
  id text primary key,
  task_id text references todo.tasks(id) on delete set null,
  kind text not null check (kind in ('work','short_break','long_break')),
  status text not null check (status in ('running','paused','completed','cancelled')),
  started_at timestamptz not null,
  ended_at timestamptz,
  duration_sec int not null,
  remaining_sec int,
  channels text[] not null default '{local}'
);

create table if not exists todo.digests (
  id text primary key,
  name text not null,
  cron text not null,
  template text not null,
  channels text[] not null default '{max,email}',
  enabled boolean not null default true
);

create table if not exists todo.agent_actions (
  id text primary key,
  agent text not null,
  action text not null,
  entity text not null,
  entity_id text,
  payload jsonb,
  reverse_payload jsonb,
  undoable boolean not null default true,
  undone_at timestamptz,
  reason text,
  created_at timestamptz not null default now()
);
create index if not exists agent_actions_recent on todo.agent_actions(created_at desc);

create table if not exists todo.email_outbox (
  id bigserial primary key,
  subject text not null,
  body text not null,
  status text not null default 'pending' check (status in ('pending','sent','failed')),
  attempts int not null default 0,
  error text,
  created_at timestamptz not null default now(),
  sent_at timestamptz
);

create table if not exists todo.audit (
  id bigserial primary key,
  actor text not null check (actor in ('user','agent','pipeline')),
  actor_id text,
  action text not null,
  entity text not null,
  entity_id text,
  before jsonb, after jsonb,
  created_at timestamptz not null default now()
);
`;

export class Store {
  constructor(readonly pool: Pool, readonly timezone: string, readonly maxDepth: number) {}

  /**
   * Время владельца → timestamptz.
   *
   * «2026-11-27 09:00» из чата/CLI — это МСК, а Postgres без зоны понял бы это как UTC
   * и напомнил бы на 3 часа позже. Москва живёт в фиксированном +03:00 (без перехода
   * на летнее время), поэтому подставляем смещение явно, когда зоны во входе нет.
   */
  private normalizeTime(value: string | null): string | null {
    if (value === null || value.trim() === "") return null;
    const v = value.trim();
    if (/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?$/.test(v)) {
      return `${v.replace(" ", "T")}+03:00`;
    }
    if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return `${v}T00:00:00+03:00`;
    return v;
  }

  async migrate(): Promise<void> {
    await this.pool.query(SCHEMA_SQL);
  }

  private async audit(
    actor: string,
    action: string,
    entity: string,
    entityId: string | null,
    before: unknown,
    after: unknown,
  ): Promise<void> {
    await this.pool.query(
      `INSERT INTO todo.audit (actor, action, entity, entity_id, before, after)
       VALUES ($1,$2,$3,$4,$5,$6)`,
      [actor, action, entity, entityId, before === null ? null : JSON.stringify(before), after === null ? null : JSON.stringify(after)],
    );
  }

  // ---------------------------------------------------------------- проекты
  async listProjects(): Promise<unknown[]> {
    const r = await this.pool.query(
      "SELECT * FROM todo.projects WHERE archived = false ORDER BY position, created_at",
    );
    return r.rows;
  }

  async createProject(input: { id: string; name: string; parentId?: string | null; color?: string | null }): Promise<unknown> {
    const r = await this.pool.query(
      `INSERT INTO todo.projects (id, name, parent_id, color)
       VALUES ($1,$2,$3,$4) RETURNING *`,
      [input.id, input.name, input.parentId ?? null, input.color ?? null],
    );
    await this.audit("user", "create", "project", input.id, null, r.rows[0]);
    return r.rows[0];
  }

  // ------------------------------------------------------------------ задачи
  async createTask(input: {
    id: string;
    title: string;
    description?: string | null;
    projectId?: string | null;
    parentId?: string | null;
    priority?: number;
    dueAt?: string | null;
    dueDateOnly?: boolean;
    source?: string;
    sourceRef?: unknown;
    createdBy?: string;
    recurrence?: unknown;
  }): Promise<TaskRow> {
    // Глубина: считаем предков подзадачи; глубже лимита — отказ, иначе дерево превращается
    // в бесконечную вложенность, которую не отрисовать и не пройти рекурсией.
    if (input.parentId) {
      const d = await this.pool.query<{ depth: number }>(
        `WITH RECURSIVE up AS (
            SELECT id, parent_id, 1 AS depth FROM todo.tasks WHERE id = $1
            UNION ALL
            SELECT t.id, t.parent_id, up.depth + 1 FROM todo.tasks t JOIN up ON t.id = up.parent_id
         ) SELECT max(depth)::int AS depth FROM up`,
        [input.parentId],
      );
      const depth = d.rows[0]?.depth ?? 0;
      if (depth >= this.maxDepth) {
        throw new Error(`подзадача глубже ${this.maxDepth} уровней запрещена`);
      }
    }
    const r = await this.pool.query<TaskRow>(
      `INSERT INTO todo.tasks
         (id, project_id, parent_id, title, description, priority, due_at, due_date_only,
          source, source_ref, created_by, recurrence)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
       RETURNING *`,
      [
        input.id,
        input.projectId ?? null,
        input.parentId ?? null,
        input.title,
        input.description ?? null,
        input.priority ?? 3,
        this.normalizeTime(input.dueAt ?? null),
        input.dueDateOnly ?? false,
        input.source ?? "manual",
        input.sourceRef == null ? null : JSON.stringify(input.sourceRef),
        input.createdBy ?? "user",
        input.recurrence == null ? null : JSON.stringify(input.recurrence),
      ],
    );
    const task = r.rows[0]!;
    await this.audit(input.createdBy === "agent" ? "agent" : "user", "create", "task", task.id, null, task);
    return task;
  }

  /**
   * Представления.
   *
   * `today`/`upcoming` считаются в часовом поясе владельца (Москва), а не UTC:
   * иначе задача «на сегодня 23:30» попадала бы в «завтра» по UTC.
   * В канбане возвращаем корневые задачи: подзадачи живут в карточке (спека §1.2).
   */
  async listTasks(view: string, q?: string): Promise<TaskRow[]> {
    const params: unknown[] = [];
    let where = "deleted_at IS NULL";
    const tz = this.timezone;
    if (view === "inbox") {
      where += " AND status = 'todo' AND project_id IS NULL";
    } else if (view === "today") {
      where += ` AND (due_at AT TIME ZONE $1)::date <= (now() AT TIME ZONE $1)::date`;
      params.push(tz);
    } else if (view === "upcoming") {
      where += ` AND (due_at AT TIME ZONE $1)::date > (now() AT TIME ZONE $1)::date`;
      params.push(tz);
    } else if (view === "kanban") {
      where += " AND parent_id IS NULL";
    } else if (view.startsWith("project:")) {
      where += ` AND project_id = $1`;
      params.push(view.slice("project:".length));
    }
    if (q !== undefined && q.trim() !== "") {
      params.push(`%${q.trim()}%`);
      where += ` AND title ILIKE $${params.length}`;
    }
    const r = await this.pool.query<TaskRow>(
      `SELECT * FROM todo.tasks WHERE ${where}
       ORDER BY (status IN ('done','cancelled')), priority, due_at NULLS LAST, created_at
       LIMIT 500`,
      params,
    );
    return r.rows;
  }

  async getTask(id: string): Promise<TaskRow | null> {
    const r = await this.pool.query<TaskRow>("SELECT * FROM todo.tasks WHERE id=$1 AND deleted_at IS NULL", [id]);
    return r.rows[0] ?? null;
  }

  async patchTask(id: string, patch: Record<string, unknown>, actor = "user"): Promise<TaskRow | null> {
    const before = await this.getTask(id);
    if (!before) return null;
    const allowed = ["title", "description", "project_id", "status", "priority", "due_at", "due_date_only", "importance", "urgency"];
    const sets: string[] = [];
    const params: unknown[] = [id];
    for (const key of allowed) {
      if (!(key in patch)) continue;
      params.push(patch[key]);
      sets.push(`${key} = $${params.length}`);
    }
    if (sets.length === 0) return before;
    if (patch["status"] === "done") sets.push("completed_at = now()");
    const r = await this.pool.query<TaskRow>(
      `UPDATE todo.tasks SET ${sets.join(", ")}, updated_at = now() WHERE id=$1 RETURNING *`,
      params,
    );
    const after = r.rows[0] ?? null;
    await this.audit(actor, "update", "task", id, before, after);
    return after;
  }

  async completeTask(id: string, actor = "user"): Promise<TaskRow | null> {
    return this.patchTask(id, { status: "done" }, actor);
  }

  /** Soft-delete: физическое удаление сломало бы undo агента. */
  async deleteTask(id: string, actor = "user"): Promise<boolean> {
    const before = await this.getTask(id);
    if (!before) return false;
    await this.pool.query("UPDATE todo.tasks SET deleted_at = now(), updated_at = now() WHERE id=$1", [id]);
    await this.audit(actor, "delete", "task", id, before, null);
    return true;
  }

  async listSubtasks(parentId: string): Promise<TaskRow[]> {
    const r = await this.pool.query<TaskRow>(
      "SELECT * FROM todo.tasks WHERE parent_id=$1 AND deleted_at IS NULL ORDER BY created_at",
      [parentId],
    );
    return r.rows;
  }

  // -------------------------------------------------------------- напоминания
  async createReminder(input: { id: string; taskId: string; fireAt: string; channels?: string[] }): Promise<unknown> {
    const r = await this.pool.query(
      `INSERT INTO todo.reminders (id, task_id, fire_at, channels)
       VALUES ($1,$2,$3,$4) RETURNING *`,
      [input.id, input.taskId, this.normalizeTime(input.fireAt), input.channels ?? ["local", "max", "email"]],
    );
    return r.rows[0];
  }

  /**
   * Напоминания для телефона.
   *
   * Отметка `local_sent_at` отдельная: воркер (max/telegram/email) и приложение
   * доставляют параллельно, и общий status='sent' означал бы, что телефон уже не
   * получит напоминание, если воркер успел первым (он тикает каждые 15 с, телефон — реже).
   */
  async dueRemindersForDevice(limit = 50): Promise<unknown[]> {
    const r = await this.pool.query<{ id: string }>(
      `UPDATE todo.reminders r SET local_sent_at = now()
        WHERE r.id IN (
          SELECT id FROM todo.reminders
           WHERE status = 'pending' AND fire_at <= now()
             AND 'local' = ANY(channels) AND local_sent_at IS NULL
           ORDER BY fire_at LIMIT $1
           FOR UPDATE SKIP LOCKED)
       RETURNING r.id`,
      [limit],
    );
    const ids = r.rows.map((row) => row.id);
    if (ids.length === 0) return [];
    for (const id of ids) {
      await this.finishReminderIfDone(id);
    }
    const full = await this.pool.query(
      `SELECT r.*, t.title FROM todo.reminders r JOIN todo.tasks t ON t.id = r.task_id WHERE r.id = ANY($1::text[])`,
      [ids],
    );
    return full.rows;
  }

  /** Закрыть reminder, когда все его каналы отработали. */
  async finishReminderIfDone(id: string): Promise<void> {
    await this.pool.query(
      `UPDATE todo.reminders
          SET status = 'sent', sent_at = coalesce(sent_at, now())
        WHERE id = $1
          AND (remote_sent_at IS NOT NULL OR 'local' <> ALL(channels))
          AND (local_sent_at IS NOT NULL OR 'local' <> ALL(channels))`,
      [id],
    );
  }

  /**
   * Забрать due-напоминания для отправки в мессенджеры/почту.
   * SKIP LOCKED вместо Redis: один воркер, но рестарт и параллельный запуск безопасны.
   */
  async claimDueReminders(limit = 20): Promise<Array<{ id: string; task_id: string; channels: string[]; title: string }>> {
    // Только «внешние» каналы: local забирает приложение, и если воркер закроет
    // строку целиком, телефон её уже не увидит.
    const r = await this.pool.query<{ id: string; task_id: string; channels: string[]; title: string }>(
      `WITH due AS (
         SELECT r.id FROM todo.reminders r
          WHERE r.status = 'pending' AND r.fire_at <= now()
            AND r.remote_sent_at IS NULL
            AND r.channels && ARRAY['max','telegram','email']::text[]
          FOR UPDATE SKIP LOCKED LIMIT $1
       )
       UPDATE todo.reminders rem SET remote_sent_at = now(), attempts = attempts + 1
        FROM due, todo.tasks t
       WHERE rem.id = due.id AND t.id = rem.task_id
       RETURNING rem.id, rem.task_id, rem.channels, t.title`,
      [limit],
    );
    for (const row of r.rows) await this.finishReminderIfDone(row.id);
    return r.rows;
  }

  /**
   * Email не отправляется из-под bbx: `bbx mail` использует metafeed, чей venv
   * доступен только root. Поэтому сервис (пользователь bbx) кладёт письмо в очередь,
   * а отдельный root-таймер забирает и отправляет, отмечая результат.
   */
  async enqueueEmail(subject: string, body: string): Promise<void> {
    await this.pool.query("INSERT INTO todo.email_outbox (subject, body) VALUES ($1,$2)", [subject, body]);
  }

  async markReminderSent(id: string): Promise<void> {
    await this.pool.query("UPDATE todo.reminders SET status='sent', sent_at=now() WHERE id=$1", [id]);
  }

  async snoozeReminder(id: string, minutes: number): Promise<void> {
    await this.pool.query(
      `UPDATE todo.reminders
          SET status='pending', fire_at = now() + make_interval(mins => $2), snooze_until = now() + make_interval(mins => $2)
        WHERE id=$1`,
      [id, Math.max(1, Math.round(minutes))],
    );
  }

  // ------------------------------------------------------------- агент/undo
  async recordAgentAction(input: {
    id: string;
    agent: string;
    action: string;
    entity: string;
    entityId: string | null;
    payload: unknown;
    reversePayload: unknown;
    reason?: string | null;
  }): Promise<void> {
    await this.pool.query(
      `INSERT INTO todo.agent_actions (id, agent, action, entity, entity_id, payload, reverse_payload, reason)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
      [
        input.id,
        input.agent,
        input.action,
        input.entity,
        input.entityId,
        input.payload == null ? null : JSON.stringify(input.payload),
        input.reversePayload == null ? null : JSON.stringify(input.reversePayload),
        input.reason ?? null,
      ],
    );
    // Rate-limit считается по этому журналу: отдельный счётчик в Redis не нужен.
    await this.audit("agent", input.action, input.entity, input.entityId, null, input.payload);
  }

  async countAgentActions(interval: string): Promise<number> {
    const r = await this.pool.query<{ n: string }>(
      `SELECT count(*)::text AS n FROM todo.agent_actions WHERE created_at > now() - $1::interval`,
      [interval],
    );
    return Number(r.rows[0]?.n ?? 0);
  }

  async listAgentActions(limit = 20): Promise<unknown[]> {
    const r = await this.pool.query(
      "SELECT * FROM todo.agent_actions ORDER BY created_at DESC LIMIT $1",
      [limit],
    );
    return r.rows;
  }

  async undoAction(id: string): Promise<boolean> {
    const r = await this.pool.query<{ reverse_payload: Record<string, unknown> | null; entity: string; entity_id: string | null }>(
      "SELECT reverse_payload, entity, entity_id FROM todo.agent_actions WHERE id=$1 AND undone_at IS NULL AND undoable",
      [id],
    );
    const row = r.rows[0];
    if (!row) return false;
    const rev = row.reverse_payload;
    if (row.entity === "task" && row.entity_id && rev) {
      if (rev["deleted"] === true) {
        await this.pool.query("UPDATE todo.tasks SET deleted_at = NULL, updated_at = now() WHERE id=$1", [row.entity_id]);
      } else {
        const patch: Record<string, unknown> = {};
        for (const key of ["status", "priority", "due_at", "title", "project_id"]) {
          if (key in rev) patch[key] = rev[key];
        }
        if (Object.keys(patch).length > 0) await this.patchTask(row.entity_id, patch, "agent");
      }
    }
    await this.pool.query("UPDATE todo.agent_actions SET undone_at = now() WHERE id=$1", [id]);
    await this.audit("agent", "undo", row.entity, row.entity_id, null, null);
    return true;
  }

  // ---------------------------------------------------------------- inbox
  async inboxTask(input: {
    id: string;
    title: string;
    source: string;
    sourceRef: unknown;
    dueAt?: string | null;
  }): Promise<TaskRow> {
    return this.createTask({
      id: input.id,
      title: input.title,
      source: input.source,
      sourceRef: input.sourceRef,
      dueAt: input.dueAt ?? null,
      createdBy: "pipeline",
    });
  }

  // --------------------------------------------------------------- pomodoro
  async startPomodoro(input: { id: string; taskId: string | null; kind: string; durationSec: number; channels: string[] }): Promise<unknown> {
    // Одна активная сессия: предыдущую незавершённую закрываем как cancelled, иначе
    // статистика посчитает одно и то же время дважды.
    await this.pool.query(
      "UPDATE todo.pomodoro_sessions SET status='cancelled', ended_at=now() WHERE status IN ('running','paused')",
    );
    const r = await this.pool.query(
      `INSERT INTO todo.pomodoro_sessions (id, task_id, kind, status, started_at, duration_sec, remaining_sec, channels)
       VALUES ($1,$2,$3,'running',now(),$4,$4,$5) RETURNING *`,
      [input.id, input.taskId, input.kind, input.durationSec, input.channels],
    );
    return r.rows[0];
  }

  async finishPomodoro(id: string, status: 'completed' | 'cancelled'): Promise<unknown | null> {
    const r = await this.pool.query(
      `UPDATE todo.pomodoro_sessions
          SET status=$2, ended_at=now(),
              duration_sec = GREATEST(1, EXTRACT(EPOCH FROM (now() - started_at))::int)
        WHERE id=$1 AND status IN ('running','paused')
        RETURNING *`,
      [id, status],
    );
    return r.rows[0] ?? null;
  }

  async activePomodoro(): Promise<unknown | null> {
    const r = await this.pool.query(
      `SELECT *, EXTRACT(EPOCH FROM (now() - started_at))::int AS elapsed_sec
         FROM todo.pomodoro_sessions
        WHERE status IN ('running','paused')
        ORDER BY started_at DESC LIMIT 1`,
    );
    return r.rows[0] ?? null;
  }

  async pomodoroStats(days = 7): Promise<unknown> {
    const r = await this.pool.query(
      `SELECT count(*)::int AS sessions,
              coalesce(sum(duration_sec),0)::int AS seconds
         FROM todo.pomodoro_sessions
        WHERE kind='work' AND status='completed'
          AND started_at > now() - make_interval(days => $1)`,
      [days],
    );
    return r.rows[0] ?? { sessions: 0, seconds: 0 };
  }

  async health(): Promise<{ ok: boolean; detail: string }> {
    try {
      const r = await this.pool.query<{ n: string }>("SELECT count(*)::text AS n FROM todo.tasks WHERE deleted_at IS NULL");
      return { ok: true, detail: `tasks=${r.rows[0]?.n ?? "?"}` };
    } catch (error) {
      return { ok: false, detail: error instanceof Error ? error.message : String(error) };
    }
  }

  async close(): Promise<void> {
    await this.pool.end();
  }
}
