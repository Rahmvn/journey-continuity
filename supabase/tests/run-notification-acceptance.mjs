// Direct pgTAP runner for rollback-only notification acceptance. The optional
// client-module argument permits an ignored, isolated installation of `pg`.
import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const modulePath = process.argv[2];
const { default: pg } = await import(pathToFileURL(path.resolve(modulePath)).href);
const pooler = new URL((await fs.readFile("supabase/.temp/pooler-url", "utf8")).trim());
if (!process.env.SUPABASE_DB_PASSWORD) throw new Error("Database password environment missing");
const linked = (await fs.readFile("supabase/.temp/project-ref", "utf8")).trim();
if (!decodeURIComponent(pooler.username).endsWith(`.${linked}`)) throw new Error("Linked project mismatch");
// Supabase's public root certificate; obtain it over authenticated HTTPS and
// retain normal certificate/hostname verification for the password connection.
const caResponse = await fetch("https://supabase-downloads.s3-ap-southeast-1.amazonaws.com/prod/ssl/prod-ca-2021.crt");
if (!caResponse.ok) throw new Error("Supabase public CA download failed");
const ca = await caResponse.text();
const client = new pg.Client({
  host: pooler.hostname,
  port: Number(pooler.port || 5432),
  user: decodeURIComponent(pooler.username),
  database: pooler.pathname.slice(1),
  password: process.env.SUPABASE_DB_PASSWORD,
  ssl: { rejectUnauthorized: true, ca },
  connectionTimeoutMillis: 15000,
  application_name: "journey-notification-rollback-acceptance",
});
let connected = false;
try {
  await client.connect();
  connected = true;
  await client.query("set search_path=public,extensions; set statement_timeout='60s'");
  const boundary = await client.query("select to_regprocedure('public.authorize_sms_dispatch(uuid,uuid)') is not null as installed");
  if (!boundary.rows[0].installed) throw new Error("Dispatch boundary migration missing; use the isolated local runner");
  const historyQuery = "select md5(coalesce(jsonb_agg(to_jsonb(n) order by n.id)::text, '[]')) as fingerprint from public.trusted_contact_notification_outbox n";
  const before = await client.query(historyQuery);
  const sql = await fs.readFile("supabase/tests/milestone_6_trusted_contact_sms_test.sql", "utf8");
  if (!/^begin;/i.test(sql.trim()) || !/rollback;$/i.test(sql.trim())) throw new Error("Rollback fixture required");
  const results = await client.query(sql);
  let assertions = 0;
  let failures = 0;
  for (const result of results) for (const row of result.rows) {
    for (const value of Object.values(row)) if (typeof value === "string") {
      for (const line of value.split("\n")) {
        if (/^(ok|not ok) \d+/.test(line)) {
          assertions += 1;
          if (line.startsWith("not ok")) failures += 1;
          console.log(line);
        }
        if (/^# Looks like/.test(line)) console.log(line);
      }
    }
  }
  const after = await client.query(historyQuery);
  const committedHistoryUnchanged = before.rows[0].fingerprint === after.rows[0].fingerprint;
  console.log(JSON.stringify({ assertions, failures, rolledBack: true, committedHistoryUnchanged }));
  if (!committedHistoryUnchanged) process.exitCode = 1;
  if (!assertions || failures) process.exitCode = 1;
} catch (error) {
  // Never expose connection configuration, SQL containing destinations, or
  // driver diagnostics. SQLSTATE is sufficient to investigate the fixture.
  console.error(JSON.stringify({ failed: true, code: error.code || "RUNNER_ERROR", routine: error.routine, position: error.position }));
  process.exitCode = 1;
} finally {
  if (connected) await client.query("rollback").catch(() => {});
  await client.end().catch(() => {});
}
