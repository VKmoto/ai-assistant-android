# Таск-менеджер в ai-assistant-android — спека 2.0

Версия: 2.0 (2026-11-26). Статус: на ревью владельцу.
Основа: набросок «модуль таск-менеджмента для аудиочата» (внешний агент), переработан под наш стек.
Связанные документы: `BB-VOICE-SPEC.md` (голосовой шлюз), `BB-MESSENGER-SPEC.md` (роутер),
`INTEGRATION-METAFEED.md` (пайплайн сбора).

---

## 1. Аудит наброска

Набросок содержательный: модель данных продумана, undo/аудит заложены, этапы разумные.
Но он писался без знания нашего стека и предполагает компоненты, которых у нас нет.
Ниже — что принято, что исправлено и почему.

### 1.1. Не соответствует нашему стеку

| В наброске | У нас | Решение 2.0 |
|---|---|---|
| **FCM push** как основной канал | Firebase-проекта нет; приложение не имеет push-слоя | **Решено: без Google.** Основной канал — **локальные уведомления Android** (AlarmManager/WorkManager), плюс MAX/Telegram через роутер и email |
| **Redis** для ZSET/таймеров/SSE | Redis-контейнер есть, но это не наш контракт; состояние проекта живёт в Postgres | Напоминания и очереди — **Postgres** (`SKIP LOCKED`, LISTEN/NOTIFY). Redis не вводим: лишняя зависимость без выгоды на одном пользователе |
| **«bb-агент-секретарь» + function calling** | Агенты — треды BB (pi), у них нет OpenAI tool-calling; они умеют запускать команды | Секретарь (Масяня) вызывает модуль через **CLI `tasks`** (как `bbx`) + навык. HTTP API остаётся, но function-calling-схема не нужна |
| **«роутер дёргает секретаря» по webhook** | Роутер не вызывает агентов; он доставляет сообщения | Входящие события идут **в модуль**, а модуль/агент решает. Агент узнаёт о событиях через свой обычный чат/automation, без обратного вызова из роутера |
| **«пайплайн → webhook в роутер»** | Пайплайн — metafeed; контракт с ним через `bbx` CLI и БД, не webhook | Входящие — `POST /inbox` с service-токеном; источник (metafeed/почта/сообщение) указывает агент или `bbx` |
| **9 экранов → 4 активити** | В приложении сейчас один экран чата + настройки | Для v1 — **экран «Задачи» со списком И канбаном** (владелец: «канбан нужен сразу»), карточка и оверлей Pomodoro. Матрица/календарь/дашборд — v2 |
| **Почта/email-канал** | Своего SMTP нет; есть `bbx mail` (через metafeed) | **Решено: включён.** Отправка через `bbx mail` (очередь metafeed, их cron). Задержка — минуты, не мгновенно |
| **Вложения в мессенджерах** | Файлы — `/var/spool/bbx/media`, ссылки | Храним **ссылку + transport ref** (`channel`, `message_id`), не копируем байты |

### 1.2. Риски наброска, которые надо снять

1. **Undo без soft-delete.** `reverse_payload` не восстановит удалённую задачу, если строка физически удалена.
   → `deleted_at` + undo в течение 24 ч, физическая чистка позже.
2. **Повторяющиеся задачи как RRULE.** Полный RRULE — недели работы и крайние случаи.
   → Пресеты: `daily`, `weekdays`, `weekly`, `monthly`, `yearly` + интервал. Без произвольных правил.
3. **Иерархия × представления.** Не сказано, показывать ли подзадачи в канбане/матрице отдельно.
   → Правило: в канбане/матрице/календаре — только **корневые** задачи; подзадачи — в карточке.
4. **Pomodoro серверное состояние.** Серверный таймер без push требует, чтобы приложение было живо.
   → Таймер считает **клиент**, сервер хранит сессии и «остаток на момент старта»; при открытии — досчёт.
5. **Геозоны.** Фоновые geofence — разрешения, энергопотребление, ограничения Android 12+.
   → v2, после напоминаний по времени.
6. **SSE-поток данных.** Второй SSE-канал в приложении — отдельный сетевой слой и реконнекты.
   → v1: pull-to-refresh и обновление после действия; SSE — если понадобится.
7. **«Один пользователь» vs гости.** Модуль — только владелец (owner). Гость не должен видеть задачи.
8. **Rate-limit «N за цикл»** без числа. → 60 изменений/мин и 500/час на агента, с логом.

### 1.3. Что принято без изменений

Иерархия проектов и задач, теги, приоритеты, статусы, канбан с фиксированными колонками,
матрица Эйзенхауэра (ручная + подсказка), undo-журнал, аудит, тихие часы, часовой пояс
Москвы, один пользователь, без офлайна и внешних календарей.

---

## 2. Архитектура 2.0

```
телефон (ai-assistant-android)
   │  HTTPS, Bearer device-token
   ▼
bbx-tasks  (Node 22, systemd, :8122, только docker-сеть)
   ├─ REST API (задачи/проекты/напоминания/pomodoro/дашборд)
   ├─ планировщик напоминаний (Postgres, SKIP LOCKED)
   ├─ журнал действий агента (undo) + аудит
   └─ исходящие уведомления: bbx.outbound_msg (MAX/Telegram), локальные (клиент)
        ▲
        │ HTTP/CLI, service-token
        │
   Масяня (BB-агент, тред) — «секретарь»: create/update/remind/digest
        ▲
        │ обычный чат
   владелец (MAX/Telegram/голос)
```

Компоненты:
- **`bbx-tasks`** — новый сервис на VPS, как `bbx-voice`: Node, systemd, отдельный токен,
  Postgres (схема `todo`), никаких прямых вызовов мессенджеров.
- **Агент Масяня** — уже есть; получает навык `tasks` (CLI) и права owner.
- **Роутер** — доставка MAX/Telegram и голос; модуль пишет только `bbx.outbound_msg`.
- **Приложение** — новый экран «Задачи» + карточка + локальные уведомления.

Схема БД: **`todo`** (отдельная от `bbx`, чтобы не смешивать роутер и задачи).
Чужие схемы (`mine.*`, `metafeed.*`) не читаются.

---

## 3. Границы

В скоупе v1: задачи, проекты (2 уровня для UI, дерево в данных), подзадачи, теги,
приоритет, срок, напоминание по времени, повторения-пресеты, список/сегодня/предстоящее,
**канбан (Надо/В работе/Ждёт/Готово)**, карточка, Pomodoro (базовый), журнал агента + undo,
аудит, локальные уведомления, доставка в MAX/Telegram и **email (`bbx mail`)**, дайджест
(утро/вечер) через агента.

Вне скоупа v1: матрица Эйзенхауэра, календарь, дашборд (v2), геозоны (v2), SSE, FCM,
мультитенантность, офлайн, внешние календари, вложения-файлы.

---

## 4. Модель данных (схема `todo`)

Отличия от наброска: `users` не нужен (один владелец — `owner`), добавлен soft-delete,
`agent_actions` с ограничением окна undo, `reminders` без Redis, `attachments` = ссылка.

```sql
create schema if not exists todo;

create table todo.projects (
  id            text primary key,              -- p_<uuid8>
  name          text not null,
  parent_id     text references todo.projects(id),
  color         text,
  icon          text,
  position      numeric not null default 0,
  archived      boolean not null default false,
  created_at    timestamptz not null default now()
);

create table todo.tasks (
  id            text primary key,              -- t_<uuid8>
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
  eisenhower_hint jsonb,                       -- {quadrant, confidence, at}
  recurrence    jsonb,                         -- {preset:'daily|weekdays|weekly|monthly|yearly', interval:n}
  recurrence_parent_id text references todo.tasks(id),
  source        text not null default 'manual'
                  check (source in ('manual','voice','max','telegram','pipeline','agent','email')),
  source_ref    jsonb,                         -- {channel, message_id, url}
  created_by    text not null default 'user' check (created_by in ('user','agent','pipeline')),
  completed_at  timestamptz,
  deleted_at    timestamptz,                   -- soft delete для undo
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index tasks_parent on todo.tasks(parent_id) where deleted_at is null;
create index tasks_due on todo.tasks(due_at) where deleted_at is null and status not in ('done','cancelled');
create index tasks_project_status on todo.tasks(project_id, status);

create table todo.tags (
  id text primary key, name text not null unique, color text
);
create table todo.task_tags (
  task_id text references todo.tasks(id) on delete cascade,
  tag_id  text references todo.tags(id) on delete cascade,
  primary key (task_id, tag_id)
);

create table todo.attachments (
  id text primary key,
  task_id text references todo.tasks(id) on delete cascade,
  kind text not null check (kind in ('link','messenger_ref','voice_transcript')),
  url text, transport text, message_id text, transcript text,
  created_at timestamptz not null default now()
);

create table todo.reminders (
  id text primary key,
  task_id text references todo.tasks(id) on delete cascade,
  kind text not null default 'time' check (kind in ('time','geo')),
  fire_at timestamptz,
  channels text[] not null default '{local,max}',
  status text not null default 'pending'
    check (status in ('pending','sent','snoozed','cancelled')),
  snooze_until timestamptz,
  sent_at timestamptz,
  attempts int not null default 0,
  last_error text,
  created_at timestamptz not null default now()
);
create index reminders_due on todo.reminders(fire_at)
  where status = 'pending';

create table todo.pomodoro_sessions (
  id text primary key,
  task_id text references todo.tasks(id) on delete set null,
  kind text not null check (kind in ('work','short_break','long_break')),
  status text not null check (status in ('running','paused','completed','cancelled')),
  started_at timestamptz not null,
  ended_at timestamptz,
  duration_sec int not null,
  remaining_sec int,                           -- на момент паузы/старта
  channels text[] not null default '{local}'
);

create table todo.digests (
  id text primary key,
  name text not null,
  cron text not null,
  template text not null,
  channels text[] not null default '{max}',
  enabled boolean not null default true
);

create table todo.agent_actions (
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
create index agent_actions_recent on todo.agent_actions(created_at desc);

create table todo.audit (
  id bigserial primary key,
  actor text not null check (actor in ('user','agent','pipeline')),
  actor_id text,
  action text not null,
  entity text not null,
  entity_id text,
  before jsonb, after jsonb,
  created_at timestamptz not null default now()
);
```

Правила данных:
- удаление — `deleted_at`, физическая чистка через 30 дней;
- undo доступен 24 ч, только для действий агента (пользователь и так видит кнопку отмены);
- глубина подзадач — 7 (проверка при создании);
- в представлениях канбан/матрица/календарь — только корневые задачи.

---

## 5. API (v1, минимум для приложения и агента)

Аутентификация: `Authorization: Bearer <token>`; два токена — `device` (приложение) и
`service` (агент/CLI). Токены в `/opt/bbx-tasks/etc/tasks.env`, только docker-сеть.

Пользователь/приложение:
```
GET  /api/v1/tasks?view=inbox|today|upcoming|project:<id>&status=&q=
POST /api/v1/tasks                      {title, project_id?, due_at?, priority?, tags?}
PATCH /api/v1/tasks/:id
POST /api/v1/tasks/:id/complete
POST /api/v1/tasks/:id/subtasks
POST /api/v1/tasks/:id/reminders        {fire_at, channels}
POST /api/v1/reminders/:id/snooze       {minutes}
GET  /api/v1/projects
POST /api/v1/projects
GET  /api/v1/pomodoro/state
POST /api/v1/pomodoro/start|pause|resume|stop
GET  /api/v1/agent-actions?limit=20
POST /api/v1/agent-actions/:id/undo
```

CLI для агента (`tasks`, как `bbx`): `tasks add`, `tasks list`, `tasks done`, `tasks move`,
`tasks remind`, `tasks today`, `tasks digest`, `tasks undo`, `tasks actions` — с `--json`.

Приём входящих:
```
POST /api/v1/inbox     {source, title, url?, text?, due_at?, filter:{is_task,confidence}}
```
Правила «что считать задачей» — конфиг + решение агента; модуль только сохраняет.

---

## 6. Напоминания и уведомления (без FCM)

- Планировщик `bbx-tasks`: каждые 15 с `SELECT ... WHERE status='pending' AND fire_at <= now() FOR UPDATE SKIP LOCKED`.
- Каналы:
  - **local** — WorkManager раз в 15 минут забирает `GET /api/v1/reminders/due` и показывает
    системное уведомление (канал «Напоминания задач»). Сервер отмечает `local_sent_at`
    отдельно от `remote_sent_at`, поэтому воркер мессенджеров не «съедает» напоминание
    до телефона. Точность — до 15 минут; AlarmManager добавим при необходимости.
  - **max / telegram** — модуль пишет `bbx.outbound_msg` (channel/chat_id владельца), доставляет роутер.
  - **email** — включён по умолчанию. Сервис (bbx) кладёт письмо в `todo.email_outbox`,
  root-таймер `bbx-tasks-email.timer` отправляет через `bbx mail` (очередь metafeed).
  «Отправлено» = принято в очередь metafeed; это не мгновенный канал.
- Тихие часы: 23:00–08:00 Europe/Moscow; «срочное» (priority=1 и due в пределах 2 ч)
  доставляется. **Email — исключение из тихих часов** (решение владельца 2026-11-26):
  письма уходят и ночью, чтобы к утру быть в ящике.
- Snooze сдвигает `fire_at`, attempts++ и backoff при ошибках.
- Дедупликация: `reminder_id + channel` в `sent_at` (повторно не отправляем).

---

## 7. Приложение (v1 → v2)

**v1 (экран «Задачи» + оверлей):**
- «Задачи»: табы **Входящие / Сегодня / Предстоящее** и **канбан** (Надо/В работе/Ждёт/
  Готово) — переключатель представления; строка задачи — заголовок, проект, срок,
  приоритет; свайп — выполнено; на канбане — drag-and-drop между колонками; pull-to-refresh.
- Карточка задачи: заголовок, описание, проект, теги, приоритет, срок, напоминание, повтор,
  подзадачи, история (агент), «Помодоро».
- Оверлей Pomodoro: мини-плеер + пауза/стоп; полный экран — длительности и статистика.
- Локальные уведомления; при открытии — синхронизация.

**v2:** матрица Эйзенхауэра, календарь, дашборд, геозоны-напоминания.

Клиентский слой: Retrofit/OkHttp уже есть; данные — в памяти + кэш в Room (не офлайн-режим,
а чтобы список открывался мгновенно). Токен — в Keystore.

---

## 8. Агент-секретарь (Масяня)

Инструменты — CLI `tasks` + навык `tasks` (аналог `bbx`), без function calling.

Сценарии v1:
1. «Добавь задачу…» — create с due/priority/project, ответ в чат.
2. «Что на сегодня?» — `tasks today --json`, краткий ответ.
3. «Перенеси на завтра» — reschedule + запись в agent_actions.
4. «Напомни…» — reminder time.
5. Утренний дайджест 08:30, вечерний 21:30 — через automation/таймер агента.
6. Автоперенос просрочек — не чаще раза в час, лимит 20 задач за цикл.
7. «Отмени последнее» — undo последнего действия.

Правила: каждое изменение — `agent_actions` с `reverse_payload`; rate-limit 60/мин, 500/час;
тихие часы; ничего не удаляет физически.

---

## 9. Безопасность

- Два Bearer-токена, только docker-сеть (порт не публикуется), NPM не проксирует.
- Скоупы: device — CRUD своих задач; service — плюс undo/audit у агента.
- Гости (viewer) к модулю не допускаются: проверка владельца на входе.
- Аудит всех изменений; в логах нет текстов задач по умолчанию (`TASKS_LOG_TEXT=false`).
- Лимиты: 60 изменений/мин на агента; 20 автопереносов за цикл; глубина 7.

---

## 10. Этапы и критерии готовности

| Этап | Что | DoD (живой замер) |
|---|---|---|
| 1 | Схема `todo`, миграции, CRUD, токены, CLI `tasks add/list/done` | `tasks add` создаёт задачу; `tasks list --json` её видит |
| 2 | Экран «Задачи» (список + канбан) + карточка | задача, созданная голосом, видна в списке и на канбане; перетаскивание меняет статус; свайп завершает |
| 3 | Напоминания + локальные уведомления + MAX/Telegram | напоминание приходит на телефон в тихое и не тихое время по правилам |
| 4 | Агент-сценарии (создать/перенести/дайджест) + undo | «Масяня, напомни…» → задача и напоминание; «отмени» возвращает |
| 5 | Pomodoro (клиент + сессии) | таймер переживает сворачивание; сессия в статистике |
| 6 | Матрица/календарь/дашборд | переключение представлений, распределение по квадрантам |
| 7 | Inbox (пайплайн/почта) + правила | письмо-задача появляется во «Входящих» с source_ref |
| 8 | Полировка: аудит, лимиты, бэкап | `check.sh`-аналог модуля зелёный, бэкап проверен |

---

## 11. Открытые вопросы к владельцу

Решено владельцем 2026-11-26: без Google (локальные уведомления), email включён (через
`bbx mail`) и отправляется в том числе в тихие часы, геозоны — v2, повторения — пресеты,
канбан — в v1, колонки зафиксированы: **Надо / В работе / Ждёт / Готово** (включая «Ждёт»).

Остаётся открытым: **почта/пайплайн → задачи** — правила «что считать задачей»
согласуем на этапе 7 (inbox).
