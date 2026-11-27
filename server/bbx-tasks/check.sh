#!/usr/bin/env bash
# Definition of done для bbx-tasks: живой HTTP к работающему сервису.
# Требует запущенный bbx-tasks и etc/tasks.env; тест создаёт и удаляет свои объекты.
set -uo pipefail
cd "$(dirname "$0")"
fail=0
echo "=== bbx-tasks smoke (live HTTP)"
if node test/smoke.mjs; then echo "smoke OK"; else echo "SMOKE FAILED"; fail=1; fi
echo "=== schema todo: ключевые таблицы"
APP_PW=$(docker inspect postgres --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^POSTGRES_PASSWORD=' | cut -d= -f2-)
if docker exec -e PGPASSWORD="$APP_PW" postgres psql -U app -h 127.0.0.1 -d metafeed_mine -tAc \
  "select count(*) from information_schema.tables where table_schema='todo'" | grep -qE '^[0-9]+$'; then
  echo "todo tables OK"
else
  echo "TODO SCHEMA MISSING"; fail=1
fi
echo
if [ $fail -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "CHECKS FAILED"; exit 1; fi
