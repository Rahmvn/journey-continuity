import assert from "node:assert/strict";
import test from "node:test";
import { createHandler } from "./index.mjs";

test("invokes only the deterministic watchdog RPC and reports safe counts", async () => {
  let request;
  const logs = [];
  const handler = createHandler({
    getConfiguration: async () => ({
      url: "https://project.supabase.co",
      secretKey: "sb_secret_not-a-real-secret",
    }),
    fetchImpl: async (url, options) => {
      request = { url, options };
      return {
        ok: true,
        json: async () => [{
          evaluated_at: "2026-09-16T12:00:00Z",
          verifying_started: 2,
          monitoring_closed: 1,
        }],
      };
    },
    log: (message) => logs.push(message),
  });

  const result = await handler();

  assert.equal(request.url, "https://project.supabase.co/rest/v1/rpc/evaluate_due_journeys");
  assert.equal(request.options.headers.apikey, "sb_secret_not-a-real-secret");
  assert.equal("authorization" in request.options.headers, false);
  assert.deepEqual(result, {
    event: "watchdog_evaluated",
    evaluatedAt: "2026-09-16T12:00:00Z",
    verifyingStarted: 2,
    monitoringClosed: 1,
  });
  assert.equal(logs.length, 1);
  assert.equal(logs[0].includes("sb_secret"), false);
});

test("fails visibly without logging a Supabase response body", async () => {
  const handler = createHandler({
    getConfiguration: async () => ({
      url: "https://project.supabase.co",
      secretKey: "sb_secret_not-a-real-secret",
    }),
    fetchImpl: async () => ({ ok: false, status: 503 }),
    log: () => assert.fail("failure must not emit a success log"),
  });

  await assert.rejects(handler(), /HTTP 503/);
});
