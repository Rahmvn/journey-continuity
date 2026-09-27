// Isolated native PostgreSQL acceptance: never reads hosted credentials/config.
// Install pg + embedded-postgres in an ignored tools directory, then pass its
// node_modules directory. Every run gets a new cluster and random local password.
import fs from "node:fs/promises";
import path from "node:path";
import net from "node:net";
import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import { runDispatchCases } from "./notification-dispatch-cases.mjs";

const modules = path.resolve(process.argv[2]);
const { default: EmbeddedPostgres } = await import(pathToFileURL(path.join(modules, "embedded-postgres/dist/index.js")));
const { default: pg } = await import(pathToFileURL(path.join(modules, "pg/lib/index.js")));
const listener = net.createServer();
await new Promise((resolve) => listener.listen(0, "127.0.0.1", resolve));
const port = listener.address().port;
await new Promise((resolve) => listener.close(resolve));
const artifactRoot = path.resolve("aws/watchdog/.aws-sam");
const databaseDir = await fs.mkdtemp(path.join(artifactRoot, "notification-postgres-"));
const password = randomUUID();
const cluster = new EmbeddedPostgres({ databaseDir, port, user: "postgres", password,
  persistent: true, postgresFlags: ["-h", "127.0.0.1"], onLog: () => {}, onError: () => {} });
const connections = new Set();
const connect = async () => {
  const client = new pg.Client({ host: "127.0.0.1", port, user: "postgres", password, database: "postgres" });
  await client.connect();
  await client.query("set search_path=public,extensions; set statement_timeout='15s'; set lock_timeout='5s'");
  connections.add(client);
  return client;
};
const closeConnections = async () => { for (const client of connections) await client.end(); connections.clear(); };
const restart = async () => { await closeConnections(); await cluster.stop(); await cluster.start(); return connect(); };

try {
  await cluster.initialise();
  await cluster.start();
  let client = await connect();
  await client.query(`
    create role anon; create role authenticated; create role service_role bypassrls;
    create schema auth; create schema extensions;
    create table auth.users(id uuid primary key, instance_id uuid, aud text, role text,
      email text, email_confirmed_at timestamptz, created_at timestamptz, updated_at timestamptz);
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth,extensions to anon,authenticated,service_role;
  `);
  const pgtapResponse = await fetch("https://raw.githubusercontent.com/theory/pgtap/v1.3.3/sql/pgtap.sql.in");
  if (!pgtapResponse.ok) throw new Error("pgTAP dependency download failed");
  const pgtap = (await pgtapResponse.text()).replaceAll("__OS__", process.platform).replaceAll("__VERSION__", "1.3");
  await client.query(pgtap);
  const baseMigrations = (await fs.readdir("supabase/migrations")).filter((name) => name.endsWith(".sql") && name < "20260927000100").sort();
  for (const name of baseMigrations) await client.query(await fs.readFile(path.join("supabase/migrations", name), "utf8"));
  const migration = await fs.readFile("supabase/migrations/20260927000100_sms_dispatch_boundary.sql", "utf8");
  // The integration suite creates representative legacy rows before migration.
  await runDispatchCases({ client, connect, restart, migration, phase: "migration" });
  const results = await client.query(await fs.readFile("supabase/tests/milestone_6_trusted_contact_sms_test.sql", "utf8"));
  let assertions = 0;
  let failures = 0;
  for (const result of results) for (const row of result.rows) for (const value of Object.values(row)) {
    if (typeof value !== "string") continue;
    for (const line of value.split("\n")) {
      if (/^(ok|not ok) \d+/.test(line)) { assertions += 1; if (line.startsWith("not ok")) { failures += 1; console.log(line); } }
    }
  }
  console.log(JSON.stringify({ suite: "notification-pgtap", assertions, failures }));
  if (!assertions || failures) throw new Error("pgTAP assertions failed");
  await runDispatchCases({ client, connect, restart, phase: "runtime" });
} catch (error) {
  // Local synthetic tests only; never emit raw SQL, destinations or password.
  console.error(JSON.stringify({ failed: true, code: error.code, message: error.message, context: error.where?.split("\n").at(-1) }));
  process.exitCode = 1;
} finally {
  const exitCode = process.exitCode;
  await closeConnections();
  await cluster.stop();
  // Only the freshly created, resolved task directory is removed.
  if (path.dirname(databaseDir) !== artifactRoot || !path.basename(databaseDir).startsWith("notification-postgres-")) throw new Error("Unsafe fixture cleanup path");
  await fs.rm(databaseDir, { recursive: true, force: true });
  process.exitCode = exitCode;
}
// embedded-postgres registers a beforeExit hook with status 0. Cleanup is
// already awaited above; explicitly preserve a failed assertion's exit status.
process.exit(process.exitCode ?? 0);
