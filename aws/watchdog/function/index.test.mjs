import assert from "node:assert/strict";
import test from "node:test";
import {
  buildSmsMessage,
  classifyProviderFailure,
  createHandler,
  maskPhoneNumber,
} from "./index.mjs";

const configuration = {
  url: "https://project.supabase.co",
  secretKey: "sb_secret_not-a-real-secret",
  viewerUrl: "https://viewer.example.test",
  originationIdentity: "sender-identity",
  configurationSetName: "journey-events",
  batchSize: 2,
};

function rpcFetch({ claims = [], failureStates = {}, successValue = true } = {}) {
  const requests = [];
  const fetchImpl = async (url, options) => {
    const rpc = url.split("/").at(-1);
    const body = JSON.parse(options.body);
    requests.push({ rpc, body, options });
    const payload = rpc === "evaluate_due_journeys"
      ? [{ evaluated_at: "2026-09-18T12:00:00Z", verifying_started: 1, monitoring_closed: 0 }]
      : rpc === "claim_due_sms_notifications"
        ? claims
        : rpc === "record_sms_send_success"
          ? successValue
          : failureStates[body.p_notification_id] ?? "FAILED";
    return { ok: true, json: async () => payload };
  };
  return { fetchImpl, requests };
}

function claim(id, kind = "VERIFICATION_STARTED", phone = "+2348012345678") {
  return {
    notification_id: id,
    lease_token: `lease-${id}`,
    notification_kind: kind,
    destination_phone_e164: phone,
    journey_id: "journey-id",
    verification_case_id: "case-id",
    attempt_count: 1,
  };
}

test("uses fixed factual templates without precise evidence", () => {
  for (const kind of ["VERIFICATION_STARTED", "DEVICE_CONTACT_RESTORED", "JOURNEY_COMPLETED"]) {
    const message = buildSmsMessage(kind, configuration.viewerUrl);
    assert.match(message, /Journey Continuity/);
    assert.match(message, /https:\/\/viewer\.example\.test/);
    assert.doesNotMatch(message, /latitude|longitude|battery|@|\+234/i);
  }
  assert.match(buildSmsMessage("VERIFICATION_STARTED", configuration.viewerUrl), /whereabouts are unknown/i);
  assert.ok(
    buildSmsMessage(
      "VERIFICATION_STARTED",
      "https://journey-continuity-trusted-viewer.vercel.app",
    ).length <= 160,
    "deployed-viewer started alert stays within one basic SMS segment by character count",
  );
});

test("masks phone numbers in logs", () => {
  assert.equal(maskPhoneNumber("+2348012345678"), "+234***78");
});

test("does not send SMS when the claim set is empty", async () => {
  const { fetchImpl, requests } = rpcFetch();
  let sends = 0;
  const result = await createHandler({
    getConfiguration: async () => configuration,
    fetchImpl,
    sendTextMessage: async () => { sends += 1; },
    log: () => {},
  })();
  assert.equal(sends, 0);
  assert.equal(result.sms.claimed, 0);
  assert.deepEqual(requests.map((request) => request.rpc), [
    "evaluate_due_journeys",
    "claim_due_sms_notifications",
  ]);
  assert.deepEqual(requests[1].body, { p_limit: 2 });
});

test("constructs a TRANSACTIONAL E.164 provider request and stores its provider id", async () => {
  const { fetchImpl, requests } = rpcFetch({ claims: [claim("one")] });
  let providerRequest;
  const result = await createHandler({
    getConfiguration: async () => configuration,
    fetchImpl,
    sendTextMessage: async (request) => {
      providerRequest = request;
      return { MessageId: "provider-message-1" };
    },
    log: () => {},
  })();
  assert.equal(providerRequest.DestinationPhoneNumber, "+2348012345678");
  assert.equal(providerRequest.MessageType, "TRANSACTIONAL");
  assert.equal(providerRequest.OriginationIdentity, "sender-identity");
  assert.equal(providerRequest.ConfigurationSetName, "journey-events");
  assert.equal(result.sms.providerAccepted, 1);
  const success = requests.find((request) => request.rpc === "record_sms_send_success");
  assert.equal(success.body.p_provider_message_id, "provider-message-1");
});

test("classifies transient and permanent AWS failures", () => {
  assert.deepEqual(classifyProviderFailure({ name: "ThrottlingException" }), {
    classification: "TRANSIENT",
    code: "THROTTLINGEXCEPTION",
  });
  assert.deepEqual(classifyProviderFailure({ name: "ValidationException" }), {
    classification: "PERMANENT",
    code: "VALIDATIONEXCEPTION",
  });
});

test("records transient retry and continues after one bad destination", async () => {
  const claims = [claim("bad"), claim("good", "JOURNEY_COMPLETED", "+2348099999999")];
  const { fetchImpl, requests } = rpcFetch({ claims, failureStates: { bad: "RETRY_PENDING" } });
  const logs = [];
  const result = await createHandler({
    getConfiguration: async () => configuration,
    fetchImpl,
    sendTextMessage: async (request) => {
      if (request.DestinationPhoneNumber === "+2348012345678") {
        throw Object.assign(new Error("secret provider detail"), { name: "ThrottlingException" });
      }
      return { MessageId: "provider-message-good" };
    },
    log: (entry) => logs.push(entry),
  })();
  assert.equal(result.sms.claimed, 2);
  assert.equal(result.sms.retryPending, 1);
  assert.equal(result.sms.providerAccepted, 1);
  assert.ok(requests.some((request) => request.rpc === "record_sms_send_failure"));
  const combinedLogs = logs.join("\n");
  assert.doesNotMatch(combinedLogs, /\+2348012345678|secret provider detail|current whereabouts/i);
  assert.match(combinedLogs, /\+234\*\*\*78/);
});

test("records a permanent provider failure as terminal without aborting the batch", async () => {
  const { fetchImpl } = rpcFetch({ claims: [claim("invalid")], failureStates: { invalid: "FAILED" } });
  const result = await createHandler({
    getConfiguration: async () => configuration,
    fetchImpl,
    sendTextMessage: async () => {
      throw Object.assign(new Error("bad destination"), { name: "ValidationException" });
    },
    log: () => {},
  })();
  assert.equal(result.sms.failed, 1);
});

test("keeps the watchdog failure visible and does not claim SMS", async () => {
  let requestCount = 0;
  const handler = createHandler({
    getConfiguration: async () => configuration,
    fetchImpl: async () => {
      requestCount += 1;
      return { ok: false, status: 503 };
    },
    log: () => assert.fail("failure must not emit a success log"),
  });
  await assert.rejects(handler(), /evaluate_due_journeys RPC failed with HTTP 503/);
  assert.equal(requestCount, 1);
});
