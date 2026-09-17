let cachedConfiguration;

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

function validateConfiguration(value) {
  const url = new URL(value.SUPABASE_URL);
  if (url.protocol !== "https:") throw new Error("SUPABASE_URL must use HTTPS");
  if (!String(value.SUPABASE_SECRET_KEY ?? "").startsWith("sb_secret_")) {
    throw new Error("SUPABASE_SECRET_KEY must use the current sb_secret key format");
  }
  return {
    url: url.toString().replace(/\/$/, ""),
    secretKey: value.SUPABASE_SECRET_KEY,
  };
}

export function createHandler({
  getConfiguration = async () => {
    cachedConfiguration ??= validateConfiguration(await loadConfigurationFromSecretsManager());
    return cachedConfiguration;
  },
  fetchImpl = fetch,
  log = console.log,
} = {}) {
  return async function watchdogHandler() {
    const configuration = await getConfiguration();
    const response = await fetchImpl(
      `${configuration.url}/rest/v1/rpc/evaluate_due_journeys`,
      {
        method: "POST",
        headers: {
          apikey: configuration.secretKey,
          "content-type": "application/json",
        },
        body: "{}",
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (!response.ok) {
      throw new Error(`Supabase watchdog RPC failed with HTTP ${response.status}`);
    }
    const payload = await response.json();
    const result = Array.isArray(payload) ? payload[0] : payload;
    if (!result) throw new Error("Supabase watchdog RPC returned no result");
    const safeResult = {
      event: "watchdog_evaluated",
      evaluatedAt: result.evaluated_at,
      verifyingStarted: Number(result.verifying_started ?? 0),
      monitoringClosed: Number(result.monitoring_closed ?? 0),
    };
    log(JSON.stringify(safeResult));
    return safeResult;
  };
}

export const handler = createHandler();

