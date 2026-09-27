import assert from "node:assert/strict";
import test from "node:test";
import {
  buildSmsMessage,
  classifyProviderFailure,
  createHandler,
  maskPhoneNumber,
  SMS_CLIENT_OPTIONS,
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
        : rpc === "authorize_sms_dispatch" ? true
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
  assert.deepEqual(providerRequest.Context, { notification_id: "one", provider_attempt_id: "lease-one" });
  assert.equal(requests.findIndex((r) => r.rpc === "authorize_sms_dispatch"), 2);
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

test("all approved templates contain only factual wording and the public viewer input", () => {
  const expected = {
    VERIFICATION_STARTED: "Journey Continuity: Device contact is being verified; current whereabouts are unknown. Details: ",
    DEVICE_CONTACT_RESTORED: "Journey Continuity: Fresh device contact was restored. Journey details: ",
    JOURNEY_COMPLETED: "Journey Continuity: The Journey was completed. Journey details: ",
  };
  for (const [kind, prefix] of Object.entries(expected)) {
    const message = buildSmsMessage(kind, configuration.viewerUrl);
    assert.equal(message, prefix + configuration.viewerUrl);
    assert.doesNotMatch(message, /safe|danger|latitude|longitude|JC1\.|sb_secret_|eyJ|journey-id|case-id|lease-/i);
  }
  assert.throws(() => buildSmsMessage("UNSUPPORTED", configuration.viewerUrl));
});

test("overlapping handlers and a later run submit a normally acknowledged row only once", async () => {
  let state = "PENDING";
  let sends = 0;
  const fetchImpl = async (url) => {
    const rpc = url.split("/").at(-1);
    let payload;
    if (rpc === "evaluate_due_journeys") payload = [{ evaluated_at: "2026-09-26T00:00:00Z" }];
    if (rpc === "claim_due_sms_notifications") {
      payload = state === "PENDING" ? [claim("one")] : [];
      if (payload.length) state = "SENDING";
    }
    if (rpc === "record_sms_send_success") {
      state = "PROVIDER_ACCEPTED";
      payload = true;
    }
    if (rpc === "authorize_sms_dispatch") {
      payload = state === "SENDING";
      if (payload) state = "DISPATCHING";
    }
    return { ok: true, json: async () => payload };
  };
  const handler = createHandler({
    getConfiguration: async () => configuration,
    fetchImpl,
    sendTextMessage: async () => { sends += 1; return { MessageId: "fake-accepted" }; },
    log: () => {},
  });
  await Promise.all([handler(), handler()]);
  await handler();
  assert.equal(sends, 1);
  assert.equal(state, "PROVIDER_ACCEPTED");
});

test("lost database acknowledgement preserves uncertainty and cannot submit again", async () => {
  let state = "PENDING";
  let sends = 0;
  let acknowledgements = 0;
  const fetchImpl = async (url) => {
    const rpc = url.split("/").at(-1);
    if (rpc === "record_sms_send_success") { acknowledgements += 1; return { ok: false, status: 503 }; }
    let payload;
    if (rpc === "evaluate_due_journeys") payload = [{ evaluated_at: "2026-09-26T00:00:00Z" }];
    if (rpc === "claim_due_sms_notifications") {
      payload = state === "PENDING" ? [claim("uncertain")] : [];
      if (payload.length) state = "SENDING";
    }
    if (rpc === "authorize_sms_dispatch") { payload = state === "SENDING"; if (payload) state = "DISPATCHING"; }
    if (rpc === "record_sms_outcome_unknown") { state = "PROVIDER_OUTCOME_UNKNOWN"; payload = true; }
    return { ok: true, json: async () => payload };
  };
  const handler = createHandler({
    getConfiguration: async () => configuration,
    fetchImpl,
    sendTextMessage: async () => { sends += 1; return { MessageId: `fake-${sends}` }; },
    acknowledgementDelay: async () => {},
    log: () => {},
  });
  await handler();
  await handler();
  assert.equal(sends, 1);
  assert.equal(acknowledgements, 3, "bounded acknowledgement retries do not repeat AWS");
  assert.equal(state, "PROVIDER_OUTCOME_UNKNOWN");
  await handler();
  assert.equal(sends, 1, "unknown outcome is never claimable");
});

test("SDK sends once and unconfirmed errors remain unknown", () => {
  assert.equal(SMS_CLIENT_OPTIONS.maxAttempts, 1);
  for (const name of ["TimeoutError", "AbortError", "InternalServerException", "ServiceUnavailableException", "InvalidProviderResponse", "Error"]) {
    assert.equal(classifyProviderFailure({ name }).classification, "UNKNOWN");
  }
});

test("actual AWS SDK middleware performs one HTTP invocation on server and transport errors", async () => {
  const { PinpointSMSVoiceV2Client, SendTextMessageCommand } = await import("@aws-sdk/client-pinpoint-sms-voice-v2");
  for (const transportError of [false, true]) {
    let invocations = 0;
    const client = new PinpointSMSVoiceV2Client({
      ...SMS_CLIENT_OPTIONS, region: "us-east-1",
      credentials: { accessKeyId: "synthetic-test-id", secretAccessKey: "synthetic-test-secret" },
      requestHandler: { handle: async () => {
        invocations += 1;
        if (transportError) throw Object.assign(new Error("synthetic timeout"), { name: "TimeoutError" });
        return { response: { statusCode: 500, headers: { "content-type": "application/json" },
          body: new TextEncoder().encode(JSON.stringify({ __type: "InternalServerException", message: "synthetic" })) } };
      } },
    });
    assert.equal(await client.config.maxAttempts(), 1);
    await assert.rejects(client.send(new SendTextMessageCommand({ DestinationPhoneNumber: "+12025550123", MessageBody: "Synthetic test" })));
    assert.equal(invocations, 1);
    client.destroy();
  }
});

test("final dispatch denial or lost authorization response never invokes AWS", async () => {
  for (const lostResponse of [false, true]) {
    const base = rpcFetch({ claims: [claim("one")] });
    let sends = 0;
    const result = await createHandler({
      getConfiguration: async () => configuration,
      fetchImpl: async (url, options) => url.endsWith("authorize_sms_dispatch")
        ? { ok: !lostResponse, status: 503, json: async () => false } : base.fetchImpl(url, options),
      sendTextMessage: async () => { sends += 1; }, log: () => {},
    })();
    assert.equal(sends, 0);
    assert.equal(result.sms.dispatchDenied, 1);
  }
});

test("transient acknowledgement errors retry only the database", async () => {
  const base = rpcFetch({ claims: [claim("one")] });
  let sends = 0;
  let acknowledgements = 0;
  const result = await createHandler({
    getConfiguration: async () => configuration,
    fetchImpl: async (url, options) => {
      if (url.endsWith("record_sms_send_success") && ++acknowledgements < 3) return { ok: false, status: 503 };
      return base.fetchImpl(url, options);
    },
    sendTextMessage: async () => { sends += 1; return { MessageId: "fake-accepted" }; },
    acknowledgementDelay: async () => {}, log: () => {},
  })();
  assert.equal(sends, 1);
  assert.equal(acknowledgements, 3);
  assert.equal(result.sms.providerAccepted, 1);
});

test("timeout and missing provider id record unknown, never confirmed failure", async () => {
  for (const timeout of [true, false]) {
    const base = rpcFetch({ claims: [claim("one")] });
    const result = await createHandler({
      getConfiguration: async () => configuration, fetchImpl: base.fetchImpl,
      sendTextMessage: async () => { if (timeout) throw Object.assign(new Error(), { name: "TimeoutError" }); return {}; },
      log: () => {},
    })();
    assert.equal(result.sms.outcomeUnknown, 1);
    assert.ok(base.requests.some((r) => r.rpc === "record_sms_outcome_unknown"));
    assert.ok(!base.requests.some((r) => r.rpc === "record_sms_send_failure"));
  }
});
