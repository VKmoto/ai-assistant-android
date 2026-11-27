# bbx-tasks — таск-менеджер приложения и секретаря

Сервис живёт на VPS (`/opt/bbx-tasks`), как `bbx-voice`: Node 22 + Postgres (схема `todo`),
systemd, порт `172.19.0.1:8122` только в docker-сети. Мессенджеры напрямую не вызывает —
напоминания кладёт в `bbx.outbound_msg`, доставляет `bbx-connector`.

Документы: `specs/TASKS-SPEC-2.0.md` (что и почему), `specs/TASKS-PLAN.md` (этапы).

## Установка (VPS)

```bash
cp -r server/bbx-tasks /opt/bbx-tasks
cd /opt/bbx-tasks && npm ci && npm run build
cp etc/tasks.env.example etc/tasks.env   # заполнить DSN и токены, chmod 600
# схема todo создаётся администратором один раз:
#   CREATE SCHEMA todo AUTHORIZATION bbx_messenger;
cp systemd/bbx-tasks.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now bbx-tasks
ln -sf /opt/bbx-tasks/dist/cli.js /usr/local/bin/tasks
```

## Email

Сервис работает под `bbx` и не имеет доступа к metafeed CLI. Поэтому email кладётся
в `todo.email_outbox`, а root-таймер `bbx-tasks-email.timer` (каждые 2 минуты)
отправляет его через `bbx mail`. «Отправлено» = принято в очередь metafeed и `mf mail-outbox --send` подтвердил
отправку (`sent > 0`). Вызываем send сразу, не ждём их cron (раньше письмо
приходило на 15 минут позже). Тихие часы на email не действуют.

## CLI (для агента-секретаря)

```
tasks add "Позвонить в клинику" --due "2026-11-27 09:00" --priority 1
tasks list --view today
tasks done <id>
tasks move <id> in_progress
tasks remind <id> "2026-11-27 09:00"
tasks actions --limit 5
tasks undo <action_id>
```

Время в CLI — **московское** (UTC+3): `--due "2026-11-27 09:00"` означает 09:00 МСК.
API отдаёт/принимает ISO-8601; если зона не указана, значение трактуется как МСК.
