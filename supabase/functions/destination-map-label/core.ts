import type {
  BoundaryOutcome,
  BoundaryVerification,
  ProviderAttribution,
} from "../destination-search/core.ts";

export type MapLabelRequest = {
  regionId: string;
  localUnitId: string;
  latitude: number;
  longitude: number;
};
export type MapLabelContext = {
  regionId: string;
  regionName: string;
  localUnitId: string;
  localUnitName: string;
};
export type MapLabelResultType =
  | "PLACE"
  | "ROAD"
  | "LOCALITY"
  | "POSTCODE"
  | "UNKNOWN";
export type MapLabelStatus =
  | "SPECIFIC_LABEL"
  | "BROAD_ONLY"
  | "NO_MEANINGFUL_LABEL"
  | "PROVIDER_UNAVAILABLE";
type LabelMetadata = {
  label: string | null;
  secondaryLabel: string | null;
  resultType: MapLabelResultType;
  provider: "GEOAPIFY" | null;
  providerPlaceId: string | null;
  distanceMeters: number | null;
  attribution: ProviderAttribution | null;
};
export type MapLabelResponse =
  | ({
    status: MapLabelStatus;
    boundaryOutcome: "INSIDE";
    boundaryDatasetVersion: string;
    canonicalLocalUnitName: string;
  } & LabelMetadata)
  | {
    status: "VERIFICATION_FAILED";
    boundaryOutcome: Exclude<BoundaryOutcome, "INSIDE">;
    boundaryDatasetVersion: string | null;
  };

export type MapLabelDependencies = {
  authenticate: (authorization: string) => Promise<boolean>;
  catalogue: (
    regionId: string,
    localUnitId: string,
  ) => Promise<MapLabelContext | null>;
  verifyBoundary: (input: MapLabelRequest) => Promise<BoundaryVerification>;
  apiKey: string;
  fetchProvider: typeof fetch;
};

const MAX_BODY_BYTES = 2_048;
const MAX_PROVIDER_BYTES = 65_536;
// Physical sanity bound for metadata only; never a label-quality threshold.
const MAX_PROVIDER_DISTANCE_METERS = 20_100_000;
const UPSTREAM_TIMEOUT_MS = 7_000;
const GENERIC_PROVIDER_LABELS = new Set([
  "building",
  "amenity",
  "street",
  "road",
  "unnamed",
  "unnamed building",
  "unnamed road",
  "unknown",
  "unknown place",
  "no name",
  "unnamed place",
]);
const ID = /^ng-[a-z0-9]+(?:-[a-z0-9]+)*$/u;
// deno-lint-ignore no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/u;
const BOUNDARY_OUTCOMES: readonly BoundaryOutcome[] = [
  "INSIDE",
  "OUTSIDE_SELECTED_UNIT",
  "BOUNDARY_AMBIGUOUS",
  "COVERAGE_UNRESOLVED",
  "INVALID_CONTEXT",
  "UNAVAILABLE",
];
const EMPTY_METADATA: LabelMetadata = {
  label: null,
  secondaryLabel: null,
  resultType: "UNKNOWN",
  provider: null,
  providerPlaceId: null,
  distanceMeters: null,
  attribution: null,
};

/** Label resolution is descriptive metadata; only the server verifier establishes containment. */
export async function handleDestinationMapLabel(
  request: Request,
  dependencies: MapLabelDependencies,
): Promise<Response> {
  if (request.method !== "POST") return error(405, "INVALID_REQUEST");
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ") || authorization.length <= 7) {
    return error(401, "UNAUTHORIZED");
  }
  try {
    if (!(await dependencies.authenticate(authorization))) {
      return error(401, "UNAUTHORIZED");
    }
  } catch {
    if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
    return error(503, "LABEL_UNAVAILABLE");
  }
  if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
  if (
    request.headers.get("content-type")?.split(";", 1)[0].trim()
      .toLowerCase() !== "application/json"
  ) {
    return error(400, "INVALID_REQUEST");
  }
  let input: MapLabelRequest | null;
  try {
    input = parseRequest(await boundedJson(request, MAX_BODY_BYTES));
  } catch {
    if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
    return error(400, "INVALID_REQUEST");
  }
  if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
  if (!input) return error(400, "INVALID_REQUEST");

  let context: MapLabelContext | null;
  try {
    context = await dependencies.catalogue(input.regionId, input.localUnitId);
  } catch {
    if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
    return json(verificationFailed("UNAVAILABLE", null));
  }
  if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
  if (
    !context || context.regionId !== input.regionId ||
    context.localUnitId !== input.localUnitId
  ) {
    return json(verificationFailed("INVALID_CONTEXT", null));
  }
  if (
    !safeText(context.regionName, 160) || !safeText(context.localUnitName, 160)
  ) {
    return json(verificationFailed("INVALID_CONTEXT", null));
  }
  let verification: BoundaryVerification;
  try {
    verification = await dependencies.verifyBoundary(input);
  } catch {
    if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
    return json(verificationFailed("UNAVAILABLE", null));
  }
  if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
  const version = safeText(verification.boundaryDatasetVersion, 120);
  if (
    !BOUNDARY_OUTCOMES.includes(verification.outcome) ||
    (verification.outcome !== "INVALID_CONTEXT" &&
      verification.outcome !== "UNAVAILABLE" && !version)
  ) {
    return json(verificationFailed("UNAVAILABLE", null));
  }
  if (verification.outcome !== "INSIDE") {
    return json(
      verificationFailed(
        verification.outcome,
        verification.outcome === "INVALID_CONTEXT" ||
          verification.outcome === "UNAVAILABLE"
          ? null
          : version,
      ),
    );
  }
  // Geography remains INSIDE even when the secret/provider/description is unavailable.
  const verified = (
    status: MapLabelStatus,
    metadata = EMPTY_METADATA,
  ): Response =>
    json({
      status,
      boundaryOutcome: "INSIDE",
      boundaryDatasetVersion: version!,
      canonicalLocalUnitName: context.localUnitName,
      ...metadata,
    });
  if (!dependencies.apiKey) return verified("PROVIDER_UNAVAILABLE");

  const url = new URL("https://api.geoapify.com/v1/geocode/reverse");
  url.searchParams.set("lat", String(input.latitude));
  url.searchParams.set("lon", String(input.longitude));
  url.searchParams.set("format", "json");
  url.searchParams.set("limit", "1");
  url.searchParams.set("lang", "en");
  url.searchParams.set("apiKey", dependencies.apiKey);
  try {
    const upstream = await dependencies.fetchProvider(url, {
      method: "GET",
      redirect: "error",
      signal: AbortSignal.any([
        request.signal,
        AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
      ]),
    });
    if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
    if (!upstream.ok) {
      await upstream.body?.cancel();
      return verified("PROVIDER_UNAVAILABLE");
    }
    const payload = await boundedJson(upstream, MAX_PROVIDER_BYTES);
    if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
    if (!isObject(payload) || !Array.isArray(payload.results)) {
      return verified("PROVIDER_UNAVAILABLE");
    }
    if (payload.results.length === 0) return verified("NO_MEANINGFUL_LABEL");
    const result = classifyDescription(payload.results[0], context);
    return verified(result.status, result.metadata);
  } catch {
    if (request.signal.aborted) return error(499, "REQUEST_CANCELLED");
    return verified("PROVIDER_UNAVAILABLE");
  }
}

function parseRequest(value: unknown): MapLabelRequest | null {
  if (
    !isObject(value) || Object.keys(value).length !== 4 ||
    Object.keys(value).some((key) =>
      !["regionId", "localUnitId", "latitude", "longitude"].includes(key)
    )
  ) return null;
  const { regionId, localUnitId, latitude, longitude } = value;
  if (
    typeof regionId !== "string" || regionId.length > 120 ||
    !ID.test(regionId) ||
    typeof localUnitId !== "string" || localUnitId.length > 120 ||
    !ID.test(localUnitId) ||
    !coordinate(latitude, -90, 90) || !coordinate(longitude, -180, 180)
  ) return null;
  return { regionId, localUnitId, latitude, longitude };
}

/**
 * Documented Geoapify result_type vocabulary:
 * https://apidocs.geoapify.com/docs/geocoding/reverse-geocoding/
 * Broad/unknown descriptions never become an exact destination name. Provider
 * lat/lon, formatted addresses, rank and administrative text are not containment proof.
 */
function classifyDescription(raw: unknown, context: MapLabelContext): {
  status: MapLabelStatus;
  metadata: LabelMetadata;
} {
  const none = {
    status: "NO_MEANINGFUL_LABEL" as const,
    metadata: EMPTY_METADATA,
  };
  if (!isObject(raw) || raw.country_code !== "ng") return none;
  const type = raw.result_type;
  const name = safeText(raw.name, 160);
  const line1 = safeText(raw.address_line1, 160);
  const administrativeNames = [
    context.localUnitName,
    context.regionName,
    "Nigeria",
    raw.country,
    raw.state,
    raw.county,
    raw.city,
    raw.suburb,
    raw.district,
    raw.postcode,
  ]
    .filter((value): value is string => typeof value === "string").map(
      normalized,
    );
  const specificText = (value: string | null): value is string =>
    value !== null &&
    !GENERIC_PROVIDER_LABELS.has(normalized(value)) &&
    !value.split(/[,·]/u).every((part) =>
      administrativeNames.includes(normalized(part))
    );
  if (
    ["suburb", "district", "postcode", "city", "county", "state", "country"]
      .includes(String(type))
  ) {
    const meaningful = name || line1 || safeText(raw.formatted, 300);
    return meaningful
      ? {
        status: "BROAD_ONLY",
        metadata: {
          ...EMPTY_METADATA,
          resultType: type === "postcode" ? "POSTCODE" : "LOCALITY",
        },
      }
      : none;
  }
  let label: string | null = null;
  let resultType: MapLabelResultType = "UNKNOWN";
  if (type === "amenity") {
    label = specificText(name) ? name : null;
    resultType = "PLACE";
  } else if (type === "building") {
    if (specificText(name)) label = name;
    else {
      const street = safeText(raw.street, 160);
      const house = safeText(raw.housenumber, 40);
      // Use a supplied address line only when the documented premise components support it.
      if (
        specificText(line1) && street && house &&
        normalized(line1).includes(normalized(street)) &&
        normalized(line1).includes(normalized(house))
      ) label = line1;
    }
    resultType = "PLACE";
  } else if (type === "street") {
    const street = safeText(raw.street, 160);
    label = specificText(street) ? street : specificText(name) ? name : null;
    resultType = "ROAD";
  }
  if (!label) return none;
  const attribution = parseAttribution(raw.datasource);
  // Retaining a label without required source evidence would fabricate provenance.
  if (!attribution) return none;
  const secondary = safeText(raw.address_line2, 300);
  return {
    status: "SPECIFIC_LABEL",
    metadata: {
      label,
      secondaryLabel: secondary && normalized(secondary) !== normalized(label)
        ? secondary
        : null,
      resultType,
      provider: "GEOAPIFY",
      providerPlaceId: safeText(raw.place_id, 512),
      distanceMeters:
        typeof raw.distance === "number" && Number.isFinite(raw.distance) &&
          raw.distance >= 0 && raw.distance <= MAX_PROVIDER_DISTANCE_METERS
          ? raw.distance
          : null,
      attribution,
    },
  };
}

function parseAttribution(value: unknown): ProviderAttribution | null {
  if (!isObject(value)) return null;
  const sourceName = safeText(value.sourcename, 80);
  const attribution = safeText(value.attribution, 160);
  const license = safeText(value.license, 80);
  const sourceUrl = safeText(value.url, 300);
  if (!sourceName || !attribution || !license || !sourceUrl) return null;
  try {
    const url = new URL(sourceUrl);
    if (
      url.protocol !== "https:" || !url.hostname || url.username || url.password
    ) return null;
  } catch {
    return null;
  }
  return { sourceName, attribution, license, sourceUrl };
}

function verificationFailed(
  outcome: Exclude<BoundaryOutcome, "INSIDE">,
  version: string | null,
): MapLabelResponse {
  return {
    status: "VERIFICATION_FAILED",
    boundaryOutcome: outcome,
    boundaryDatasetVersion: version,
  };
}
function coordinate(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= min &&
    value <= max;
}
function safeText(value: unknown, maximum: number): string | null {
  return typeof value === "string" && [...value].length <= maximum &&
      !CONTROL.test(value) && value.trim()
    ? value.trim()
    : null;
}
function normalized(value: string): string {
  return value.trim().toLocaleLowerCase("en").replace(/\s+/gu, " ").replace(
    / state$/u,
    "",
  );
}
function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
async function boundedJson(
  message: Request | Response,
  maximum: number,
): Promise<unknown> {
  const reader = message.body?.getReader();
  if (!reader) throw new Error("Missing body");
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > maximum) {
        await reader.cancel();
        throw new Error("Body too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}
function json(body: MapLabelResponse): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
    },
  });
}
function error(status: number, code: string): Response {
  return new Response(JSON.stringify({ error: { code } }), {
    status,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
    },
  });
}
