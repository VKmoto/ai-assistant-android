/**
 * Конфигурация bbx-tasks.
 *
 * Сервис обслуживает ОДНОГО владельца (решение проекта): реестра пользователей нет,
 * есть два токена — устройство (приложение) и агент (CLI/секретарь). Всё, что можно
 * сломать неверным значением, падает на старте: сервис с пустым токеном опаснее
 * сервиса, который не поднялся.
 */

function req(name: string): string {
  const v = process.env[name];
  if (v === undefined || v.trim() === "") throw new Error(`bbx-tasks: обязательная переменная ${name} не задана`);
  return v.trim();
}

function str(name: string, def: string): string {
  const v = process.env[name];
  return v === undefined || v.trim() === "" ? def : v.trim();
}

function int(name: string, def: number, min = 0): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === "") return def;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < min) throw new Error(`bbx-tasks: ${name} должно быть числом >= ${min}`);
  return Math.floor(n);
}

export interface TasksConfig {
  dsn: string;
  /** Токен приложения (чтение/CRUD своих задач). */
  deviceToken: string;
  /** Токен агента/CLI: плюс undo, audit, inbox. */
  serviceToken: string;
  bind: string;
  port: number;
  timezone: string;
  /** Тихие часы, "23:00-08:00"; local/max/telegram молчат, email — нет. */
  quietHours: string;
  /** Предел изменений агента (защита от runaway). */
  agentLimitPerMinute: number;
  agentLimitPerHour: number;
  /** Глубина вложенности подзадач. */
  maxDepth: number;
}

export function loadConfig(): TasksConfig {
  const deviceToken = req("TASKS_DEVICE_TOKEN");
  const serviceToken = req("TASKS_SERVICE_TOKEN");
  if (deviceToken.length < 24 || serviceToken.length < 24) {
    throw new Error("bbx-tasks: токены должны быть не короче 24 символов");
  }
  return {
    dsn: req("BBX_DSN"),
    deviceToken,
    serviceToken,
    bind: str("TASKS_BIND", "172.19.0.1"),
    port: int("TASKS_PORT", 8122, 1),
    timezone: str("TASKS_TZ", "Europe/Moscow"),
    quietHours: str("TASKS_QUIET", "23:00-08:00"),
    agentLimitPerMinute: int("TASKS_AGENT_LIMIT_MIN", 60, 1),
    agentLimitPerHour: int("TASKS_AGENT_LIMIT_HOUR", 500, 1),
    maxDepth: int("TASKS_MAX_DEPTH", 7, 1),
  };
}
