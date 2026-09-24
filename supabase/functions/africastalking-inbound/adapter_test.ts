import {
  assertEquals,
  assertExists,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import type {
  InboundCoreResult,
  VerifiedInboundTransport,
} from "../_shared/fallback_inbound_core.ts";
import {
  type AfricaTalkingInboundDependencies,
  handleAfricaTalkingInbound,
} from "./adapter.ts";

const CALLBACK_SECRET = "s".repeat(32);
const SHORTCODE = "35549";
const NOW = new Date("2026-09-24T12:00:00.000Z");

Deno.test("accepts the documented sandbox callback and preserves exact SMS text", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const smsBody = "JC1.test-envelope";
  const response = await handleAfricaTalkingInbound(
    callbackRequest({ text: smsBody }),
    dependencies(seen),
  );

  assertEquals(response.status, 200);
  assertEquals(seen, [{
    provider: "africastalking",
    providerEventId: "ATXid-123",
    rawSmsBody: smsBody,
    providerArrivedAt: "2026-09-24T11:59:58.000Z",
    serverReceivedAt: NOW.toISOString(),
  }]);
});

Deno.test("rejects malformed form encoding", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const response = await handleAfricaTalkingInbound(
    rawRequest("date=%GG&from=x&id=y&text=JC1.test&to=35549"),
    dependencies(seen),
  );
  assertEquals(response.status, 400);
  assertEquals(seen.length, 0);
});

Deno.test("rejects missing required callback fields", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const fields = validFields();
  fields.delete("id");
  const response = await handleAfricaTalkingInbound(
    rawRequest(fields.toString()),
    dependencies(seen),
  );
  assertEquals(response.status, 400);
  assertEquals(seen.length, 0);
});

Deno.test("rejects callbacks for a different shortcode", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const response = await handleAfricaTalkingInbound(
    callbackRequest({ to: "12345" }),
    dependencies(seen),
  );
  assertEquals(response.status, 403);
  assertEquals(seen.length, 0);
});

Deno.test("rejects non-JC1 messages before the core", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const response = await handleAfricaTalkingInbound(
    callbackRequest({ text: "hello" }),
    dependencies(seen),
  );
  assertEquals(response.status, 422);
  assertEquals(seen.length, 0);
});

Deno.test("rejects an oversized request body", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const response = await handleAfricaTalkingInbound(
    rawRequest(`text=${"A".repeat(2_100)}`),
    dependencies(seen),
  );
  assertEquals(response.status, 413);
  assertEquals(seen.length, 0);
});

Deno.test("returns success for a safely persisted malformed JC1 classification", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const response = await handleAfricaTalkingInbound(
    callbackRequest({ text: "JC1.not-a-valid-frame" }),
    dependencies(seen, { classification: "MALFORMED" }),
  );
  assertEquals(response.status, 200);
  assertEquals(seen.length, 1);
});

Deno.test("passes a repeated provider event ID unchanged for core idempotency", async () => {
  const seen: VerifiedInboundTransport[] = [];
  let calls = 0;
  const deps = dependencies(seen, undefined, () => {
    calls += 1;
    return { duplicateProviderEvent: calls > 1 };
  });

  const first = await handleAfricaTalkingInbound(callbackRequest(), deps);
  const second = await handleAfricaTalkingInbound(callbackRequest(), deps);

  assertEquals(first.status, 200);
  assertEquals(second.status, 200);
  assertEquals(seen.map((event) => event.providerEventId), [
    "ATXid-123",
    "ATXid-123",
  ]);
});

Deno.test("does not use or forward the sender number", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const deps = dependencies(seen);
  const first = await handleAfricaTalkingInbound(
    callbackRequest({ from: "masked-sender-a", id: "event-one" }),
    deps,
  );
  const second = await handleAfricaTalkingInbound(
    callbackRequest({ from: "masked-sender-b", id: "event-two" }),
    deps,
  );

  assertEquals(first.status, 200);
  assertEquals(second.status, 200);
  assertEquals(Object.keys(seen[0]).includes("from"), false);
  assertEquals(Object.keys(seen[1]).includes("from"), false);
});

Deno.test("emits no callback or payload logs", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const messages: unknown[][] = [];
  const originals = {
    log: console.log,
    info: console.info,
    warn: console.warn,
    error: console.error,
  };
  console.log = (...args: unknown[]) => messages.push(args);
  console.info = (...args: unknown[]) => messages.push(args);
  console.warn = (...args: unknown[]) => messages.push(args);
  console.error = (...args: unknown[]) => messages.push(args);
  try {
    const response = await handleAfricaTalkingInbound(
      callbackRequest(),
      dependencies(seen),
    );
    assertEquals(response.status, 200);
  } finally {
    console.log = originals.log;
    console.info = originals.info;
    console.warn = originals.warn;
    console.error = originals.error;
  }
  assertEquals(messages, []);
});

Deno.test("enforces POST, callback secret, and form content type", async () => {
  const seen: VerifiedInboundTransport[] = [];
  const deps = dependencies(seen);
  const get = await handleAfricaTalkingInbound(
    new Request(callbackUrl(), { method: "GET" }),
    deps,
  );
  const unauthorized = await handleAfricaTalkingInbound(
    rawRequest(validFields().toString(), "wrong-secret"),
    deps,
  );
  const json = await handleAfricaTalkingInbound(
    new Request(callbackUrl(), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{}",
    }),
    deps,
  );
  assertEquals(get.status, 405);
  assertEquals(get.headers.get("allow"), "POST");
  assertEquals(unauthorized.status, 401);
  assertEquals(json.status, 415);
  assertEquals(seen.length, 0);
});

function dependencies(
  seen: VerifiedInboundTransport[],
  overrides: Partial<InboundCoreResult> = {},
  dynamic?: () => Partial<InboundCoreResult>,
): AfricaTalkingInboundDependencies {
  return {
    callbackSecret: CALLBACK_SECRET,
    sandboxShortcode: SHORTCODE,
    now: () => NOW,
    ingest: (input) => {
      seen.push(input);
      return Promise.resolve(coreResult({ ...overrides, ...dynamic?.() }));
    },
  };
}

function coreResult(
  overrides: Partial<InboundCoreResult> = {},
): InboundCoreResult {
  return {
    receiptId: "receipt-id",
    classification: "AUTHENTICATED_NEW",
    duplicateProviderEvent: false,
    duplicateEnvelope: false,
    reconciliation: "MATERIALIZED",
    evidenceAdvanced: true,
    ...overrides,
  };
}

function callbackRequest(overrides: Record<string, string> = {}): Request {
  const fields = validFields();
  for (const [name, value] of Object.entries(overrides)) {
    fields.set(name, value);
  }
  return rawRequest(fields.toString());
}

function validFields(): URLSearchParams {
  return new URLSearchParams({
    date: "2026-09-24T11:59:58.000Z",
    from: "masked-sender",
    id: "ATXid-123",
    linkId: "link-123",
    networkCode: "99999",
    text: "JC1.test-envelope",
    to: SHORTCODE,
  });
}

function rawRequest(body: string, secret = CALLBACK_SECRET): Request {
  return new Request(callbackUrl(secret), {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded; charset=utf-8",
    },
    body,
  });
}

function callbackUrl(secret = CALLBACK_SECRET): string {
  assertExists(secret);
  return `https://example.invalid/functions/v1/africastalking-inbound?token=${secret}`;
}
