let cachedConfiguration;
let cachedSmsClient;

const MESSAGE_TEMPLATES = Object.freeze({
  VERIFICATION_STARTED: (viewerUrl) =>
    `Journey Continuity: Device contact is being verified; current whereabouts are unknown. Details: ${viewerUrl}`,
  DEVICE_CONTACT_RESTORED: (viewerUrl) =>
    `Journey Continuity: Fresh device contact was restored. Journey details: ${viewerUrl}`,
  JOURNEY_COMPLETED: (viewerUrl) =>
    `Journey Continuity: The Journey was completed. Journey details: ${viewerUrl}`,
});

async function loadConfigurationFromSecretsManager() {
  const { GetSecretValueCommand, SecretsManagerClient } = await import(
    "@aws-sdk/client-secrets-manager"
  );
  const secretArn = process.env.WATCHDOG_SECRET_ARN;
  if (!secretArn) throw new Error("WATCHDOG_SECRET_ARN is not configured");
  const response = await new SecretsManagerClient({}).send(
    new GetSecretValueCommand({ SecretId: secretArn }),
  );
  if (!response.SecretString) throw new Error("Watchdog secret has no SecretString");
  return JSON.parse(response.SecretString);
}

export function validateConfiguration(value, environment = process.env) {
  const url = new URL(value.SUPABASE_URL);
  if (url.protocol !== "https:") throw new Error("SUPABASE_URL must use HTTPS");
  if (!String(value.SUPABASE_SECRET_KEY ?? "").startsWith("sb_secret_")) {
    throw new Error("SUPABASE_SECRET_KEY must use the current sb_secret key format");
  }
  const viewerUrl = new URL(environment.SMS_VIEWER_URL);
  if (viewerUrl.protocol !== "https:") throw new Error("SMS_VIEWER_URL must use HTTPS");
  const originationIdentity = String(environment.SMS_ORIGINATION_IDENTITY ?? "").trim();
  if (!originationIdentity) throw new Error("SMS_ORIGINATION_IDENTITY is not configured");
  const requestedBatchSize = Number(environment.SMS_DISPATCH_BATCH_SIZE ?? 10);
  if (!Number.isInteger(requestedBatchSize) || requestedBatchSize < 1 || requestedBatchSize > 25) {
    throw new Error("SMS_DISPATCH_BATCH_SIZE must be an integer from 1 to 25");
  }
  return {
    url: url.toString().replace(/\/$/, ""),
    secretKey: value.SUPABASE_SECRET_KEY,
    viewerUrl: viewerUrl.toString().replace(/\/$/, ""),
    originationIdentity,
    configurationSetName: String(environment.SMS_CONFIGURATION_SET_NAME ?? "").trim() || undefined,
    batchSize: requestedBatchSize,
  };
}

export const SMS_CLIENT_OPTIONS = Object.freeze({ maxAttempts: 1 });

async function defaultSendTextMessage(request) {
  const { PinpointSMSVoiceV2Client, SendTextMessageCommand } = await import(
    "@aws-sdk/client-pinpoint-sms-voice-v2"
  );
  // SendTextMessage has no idempotency token. Even a hidden SDK retry may send twice.
  cachedSmsClient ??= new PinpointSMSVoiceV2Client(SMS_CLIENT_OPTIONS);
  return cachedSmsClient.send(new SendTextMessageCommand(request));
}

async function callRpc(configuration, fetchImpl, functionName, body) {
  const response = await fetchImpl(
    `${configuration.url}/rest/v1/rpc/${functionName}`,
    {
      method: "POST",
      headers: {
        apikey: configuration.secretKey,
        "content-type": "application/json",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10_000),
    },
  );
  if (!response.ok) {
    throw new Error(`Supabase ${functionName} RPC failed with HTTP ${response.status}`);
  }
  return response.json();
}

export function buildSmsMessage(notificationKind, viewerUrl) {
  const template = MESSAGE_TEMPLATES[notificationKind];
  if (!template) throw new Error(`Unsupported notification kind: ${notificationKind}`);
  return template(viewerUrl);
}

export function maskPhoneNumber(phone) {
  const value = String(phone);
  return value.length < 5 ? "***" : `${value.slice(0, 4)}***${value.slice(-2)}`;
}

export function classifyProviderFailure(error) {
  const errorName = String(error?.name ?? "UnknownProviderError");
  const permanentNames = new Set([
    "ValidationException", "AccessDeniedException", "ResourceNotFoundException",
    "ServiceQuotaExceededException", "ConflictException",
  ]);
  const safeCode = errorName.replace(/[^A-Za-z0-9_.-]/g, "_").toUpperCase().slice(0, 80) || "UNKNOWN_PROVIDER_ERROR";
  return {
    classification: errorName === "ThrottlingException" ? "TRANSIENT"
      : permanentNames.has(errorName) ? "PERMANENT" : "UNKNOWN",
    code: safeCode,
  };
}

function firstRpcRow(payload) {
  return Array.isArray(payload) ? payload[0] : payload;
}

export function createHandler({
  getConfiguration = async () => {
    cachedConfiguration ??= validateConfiguration(await loadConfigurationFromSecretsManager());
    return cachedConfiguration;
  },
  fetchImpl = fetch,
  sendTextMessage = defaultSendTextMessage,
  acknowledgementDelay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
  log = console.log,
} = {}) {
  return async function watchdogHandler() {
    const configuration = await getConfiguration();
    const watchdogPayload = await callRpc(configuration, fetchImpl, "evaluate_due_journeys", {});
    const watchdogResult = firstRpcRow(watchdogPayload);
    if (!watchdogResult) throw new Error("Supabase watchdog RPC returned no result");
    const safeResult = {
      event: "watchdog_evaluated",
      evaluatedAt: watchdogResult.evaluated_at,
      verifyingStarted: Number(watchdogResult.verifying_started ?? 0),
      monitoringClosed: Number(watchdogResult.monitoring_closed ?? 0),
    };
    log(JSON.stringify(safeResult));

    const claimedPayload = await callRpc(
      configuration,
      fetchImpl,
      "claim_due_sms_notifications",
      { p_limit: configuration.batchSize },
    );
    const claimed = Array.isArray(claimedPayload) ? claimedPayload : [];
    let providerAccepted = 0;
    let retryPending = 0;
    let failed = 0;
    let outcomeUnknown = 0;
    let dispatchDenied = 0;

    const markUnknown = async (notification) => {
      try {
        await callRpc(configuration, fetchImpl, "record_sms_outcome_unknown", {
          p_notification_id: notification.notification_id,
          p_lease_token: notification.lease_token,
        });
      } catch {
        // The committed DISPATCHING state is already non-reclaimable. Its
        // expired lease will become UNKNOWN even when this database call fails.
      }
    };

    for (const notification of claimed) {
      const maskedDestination = maskPhoneNumber(notification.destination_phone_e164);
      let providerResponse;
      const request = {
        DestinationPhoneNumber: notification.destination_phone_e164,
        OriginationIdentity: configuration.originationIdentity,
        MessageBody: buildSmsMessage(notification.notification_kind, configuration.viewerUrl),
        MessageType: "TRANSACTIONAL",
        TimeToLive: 900,
        Context: { notification_id: notification.notification_id, provider_attempt_id: notification.lease_token },
      };
      if (configuration.configurationSetName) request.ConfigurationSetName = configuration.configurationSetName;
      // Never retry this RPC on an uncertain response: it may already have
      // committed. Only its first successful false->true transition authorizes
      // this worker to perform the one provider invocation.
      try {
        const authorized = await callRpc(configuration, fetchImpl, "authorize_sms_dispatch", {
          p_notification_id: notification.notification_id,
          p_lease_token: notification.lease_token,
        });
        if (firstRpcRow(authorized) !== true) {
          dispatchDenied += 1;
          continue;
        }
      } catch {
        dispatchDenied += 1;
        continue;
      }
      try {
        providerResponse = await sendTextMessage(request);
        if (!providerResponse?.MessageId) {
          throw Object.assign(new Error("Provider returned no message id"), { name: "InvalidProviderResponse" });
        }
      } catch (error) {
        const failure = classifyProviderFailure(error);
        if (failure.classification === "UNKNOWN") {
          await markUnknown(notification);
          outcomeUnknown += 1;
          continue;
        }
        try {
          const recorded = await callRpc(
            configuration,
            fetchImpl,
            "record_sms_send_failure",
            {
              p_notification_id: notification.notification_id,
              p_lease_token: notification.lease_token,
              p_failure_classification: failure.classification,
              p_failure_code: failure.code,
            },
          );
          const state = firstRpcRow(recorded);
          if (state === "RETRY_PENDING") retryPending += 1;
          else if (state === "FAILED") failed += 1;
          else outcomeUnknown += 1;
        } catch {
          await markUnknown(notification);
          outcomeUnknown += 1;
        }
        log(JSON.stringify({
          event: "sms_dispatch_failed",
          notificationId: notification.notification_id,
          kind: notification.notification_kind,
          destination: maskedDestination,
          classification: failure.classification,
          code: failure.code,
        }));
        continue;
      }

      let acknowledged = false;
      // Retry only the idempotent database acknowledgement, never AWS.
      for (let acknowledgement = 0; acknowledgement < 3; acknowledgement += 1) {
        try {
          const recorded = await callRpc(
            configuration,
            fetchImpl,
            "record_sms_send_success",
            {
              p_notification_id: notification.notification_id,
              p_lease_token: notification.lease_token,
              p_provider_message_id: providerResponse.MessageId,
            },
          );
          if (firstRpcRow(recorded) !== true) break;
          acknowledged = true;
          break;
        } catch {
          if (acknowledgement < 2) await acknowledgementDelay(100 * (2 ** acknowledgement));
        }
      }
      if (acknowledged) {
        providerAccepted += 1;
        log(JSON.stringify({
          event: "sms_provider_accepted",
          notificationId: notification.notification_id,
          kind: notification.notification_kind,
          destination: maskedDestination,
        }));
      } else {
        await markUnknown(notification);
        outcomeUnknown += 1;
        log(JSON.stringify({
          event: "sms_provider_acceptance_ack_failed",
          notificationId: notification.notification_id,
          kind: notification.notification_kind,
          destination: maskedDestination,
        }));
      }
    }

    const dispatchResult = {
      event: "sms_outbox_drained",
      claimed: claimed.length,
      providerAccepted,
      retryPending,
      failed,
      outcomeUnknown,
      dispatchDenied,
    };
    log(JSON.stringify(dispatchResult));
    return { ...safeResult, sms: dispatchResult };
  };
}

export const handler = createHandler();
