#!/usr/bin/env node
/**
 * Отправка email из todo.email_outbox через `bbx mail` (metafeed).
 *
 * Запускается от root по таймеру: bbx-tasks работает под bbx и metafeed CLI ему
 * недоступен. «Отправлено» ставим только при rc=0 от bbx mail — это очередь metafeed,
 * их cron отправит письмо; статус доставки нам недоступен, поэтому не обещаем больше.
 */
import { readFileSync } from "node:fs";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import pg from "pg";

const run = promisify(execFile);
const env = {};
for (const line of readFileSync("/opt/bbx-tasks/etc/tasks.env", "utf8").split("\n")) {
  const m = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
  if (m) env[m[1]] = m[2];
}
const pool = new pg.Pool({ connectionString: env.BBX_DSN, max: 2 });

const { rows } = await pool.query(
  "SELECT id, subject, body FROM todo.email_outbox WHERE status='pending' ORDER BY id LIMIT 10",
);
for (const row of rows) {
  try {
    await run("bbx", ["mail", "--subject", row.subject, "--text", row.body], { timeout: 60_000 });
    // По контракту metafeed «отправлено» — только после `mf mail-outbox --send` с
    // sent > 0. Раньше письмо ждало их cron до 15 минут; штатная команда отправляет
    // накопленное сразу (владелец: письмо приходило на 15 минут позже).
    const sentRun = await run("mf", ["mail-outbox", "--send"], { timeout: 120_000 });
    const combined = `${sentRun.stdout ?? ""}\n${sentRun.stderr ?? ""}`;
    const sentCount = Number(/(?:sent|ушло)[^0-9]*(\d+)/i.exec(combined)?.[1] ?? "0");
    if (sentCount <= 0) {
      throw new Error(`очередь metafeed не отправила: ${combined.trim().slice(0, 200)}`);
    }
    await pool.query("UPDATE todo.email_outbox SET status='sent', sent_at=now(), error=NULL WHERE id=$1", [row.id]);
    console.log(JSON.stringify({ ts: new Date().toISOString(), event: "email-sent", id: row.id }));
  } catch (error) {
    await pool.query(
      "UPDATE todo.email_outbox SET status=CASE WHEN attempts>=2 THEN 'failed' ELSE 'pending' END, attempts=attempts+1, error=$2 WHERE id=$1",
      [row.id, error instanceof Error ? error.message.slice(0, 300) : String(error)],
    );
    console.log(JSON.stringify({ ts: new Date().toISOString(), event: "email-failed", id: row.id, error: String(error).slice(0, 200) }));
  }
}
await pool.end();
