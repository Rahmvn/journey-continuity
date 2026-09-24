import type {
  InboundCoreResult,
  VerifiedInboundTransport,
} from "../_shared/fallback_inbound_core.ts";

const PROVIDER = "africastalking";
const FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
const MAX_FORM_BYTES = 2_048;
const MAX_SMS_BYTES = 160;
const RESPONSE_HEADERS = {
  "content-type": "application/json",
  "cache-control": "no-store",
};
const PROVIDER_EVENT_ID = /^[A-Za-z0-9_.:-]{1,200}$/u;
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/u;

export type AfricaTalkingInboundDependencies = {
  callbackSecret: string;
  sandboxShortcode: string;
  now: () => Date;
  ingest: (input: VerifiedInboundTransport) => Promise<InboundCoreResult>;
};

export async function handleAfricaTalkingInbound(
  request: Request,
  dependencies: AfricaTalkingInboundDependencies,
): Promise<Response> {
  if (request.method !== "POST") {
    return response(405, "method_not_allowed", { allow: "POST" });
  }
  if (
    dependencies.callbackSecret.length < 32 ||
    !(await validCallbackSecret(request, dependencies.callbackSecret))
  ) {
    return response(401, "unauthorized");
  }

  const mediaType = request.headers.get("content-type")?.split(";", 1)[0]
    .trim().toLowerCase();
  if (mediaType !== FORM_CONTENT_TYPE) {
    return response(415, "unsupported_media_type");
  }
  const declaredLength = parseContentLength(
    request.headers.get("content-length"),
  );
  if (declaredLength === null) return response(400, "invalid_content_length");
  if (declaredLength !== undefined && declaredLength > MAX_FORM_BYTES) {
    return response(413, "request_too_large");
  }

  let rawForm: Uint8Array;
  try {
    rawForm = new Uint8Array(await request.arrayBuffer());
  } catch {
    return response(400, "malformed_form");
  }
  if (rawForm.length > MAX_FORM_BYTES) {
    return response(413, "request_too_large");
  }

  let form: URLSearchParams;
  try {
    const encoded = new TextDecoder("utf-8", { fatal: true }).decode(rawForm);
    if (/%(?![0-9A-Fa-f]{2})/u.test(encoded)) {
      throw new Error("Malformed form escape");
    }
    form = new URLSearchParams(encoded);
  } catch {
    return response(400, "malformed_form");
  }

  const fields = parseFields(form);
  if (!fields) return response(400, "invalid_callback");
  if (fields.to !== dependencies.sandboxShortcode) {
    return response(403, "wrong_shortcode");
  }
  if (!fields.text.startsWith("JC1.")) {
    return response(422, "unsupported_message");
  }
  const smsBytes = new TextEncoder().encode(fields.text).length;
  if (fields.text.length > MAX_SMS_BYTES || smsBytes > MAX_SMS_BYTES) {
    return response(413, "sms_body_too_large");
  }

  const providerArrivedAt = parseProviderDate(fields.date);
  if (!providerArrivedAt) return response(400, "invalid_callback");
  const serverReceivedAt = dependencies.now();
  if (!Number.isFinite(serverReceivedAt.getTime())) {
    return response(503, "temporarily_unavailable");
  }

  try {
    await dependencies.ingest({
      provider: PROVIDER,
      providerEventId: fields.id,
      rawSmsBody: fields.text,
      providerArrivedAt,
      serverReceivedAt: serverReceivedAt.toISOString(),
    });
    return response(200, "accepted");
  } catch {
    return response(503, "temporarily_unavailable");
  }
}

type CallbackFields = {
  date: string;
  from: string;
  id: string;
  text: string;
  to: string;
};

function parseFields(form: URLSearchParams): CallbackFields | null {
  const date = singleRequired(form, "date", 80);
  const from = singleRequired(form, "from", 64);
  const id = singleRequired(form, "id", 200);
  const text = singleRequired(form, "text", MAX_FORM_BYTES);
  const to = singleRequired(form, "to", 32);
  if (!date || !from || !id || !text || !to || !PROVIDER_EVENT_ID.test(id)) {
    return null;
  }
  if (CONTROL_CHARACTER.test(from) || CONTROL_CHARACTER.test(to)) return null;
  if (
    !singleOptional(form, "linkId", 200) ||
    !singleOptional(form, "networkCode", 32)
  ) return null;
  return { date, from, id, text, to };
}

function singleRequired(
  form: URLSearchParams,
  name: string,
  maxLength: number,
): string | null {
  const values = form.getAll(name);
  if (
    values.length !== 1 || values[0].length < 1 || values[0].length > maxLength
  ) return null;
  return values[0];
}

function singleOptional(
  form: URLSearchParams,
  name: string,
  maxLength: number,
): boolean {
  const values = form.getAll(name);
  return values.length <= 1 &&
    (values.length === 0 || values[0].length <= maxLength);
}

function parseProviderDate(value: string): string | null {
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : null;
}

function parseContentLength(value: string | null): number | undefined | null {
  if (value === null) return undefined;
  if (!/^(0|[1-9][0-9]*)$/u.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

async function validCallbackSecret(
  request: Request,
  expected: string,
): Promise<boolean> {
  const suppliedValues = new URL(request.url).searchParams.getAll("token");
  if (suppliedValues.length !== 1) return false;
  if (
    expected.length > 256 || suppliedValues[0].length < 32 ||
    suppliedValues[0].length > 256
  ) return false;
  const encoder = new TextEncoder();
  const [actualDigest, expectedDigest] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(suppliedValues[0])),
    crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  ]);
  const actual = new Uint8Array(actualDigest);
  const wanted = new Uint8Array(expectedDigest);
  let difference = 0;
  for (let index = 0; index < wanted.length; index += 1) {
    difference |= actual[index] ^ wanted[index];
  }
  return difference === 0;
}

function response(
  status: number,
  result: string,
  extraHeaders: HeadersInit = {},
): Response {
  return new Response(JSON.stringify({ result }), {
    status,
    headers: { ...RESPONSE_HEADERS, ...extraHeaders },
  });
}
