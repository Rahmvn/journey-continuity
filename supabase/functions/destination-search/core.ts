/** Provider-neutral HTTP contract and fail-closed Geoapify adapter. */
export type SearchMode = "autocomplete" | "search";
/** Search suitability only. No result is boundary-verified for Journey creation. */
export type SearchSuitability =
  | "POINT_CANDIDATE"
  | "NEEDS_MAP_PIN"
  | "NOT_USABLE";
export type AdminMatch = "MATCH" | "UNKNOWN" | "CONTRADICTION";
export type ResultType = "PLACE" | "ROAD" | "LOCALITY" | "UNKNOWN";

export type ProviderAttribution = {
  sourceName: string;
  attribution: string;
  license: string;
  sourceUrl: string;
};

export type PlaceSearchResult = {
  provider: "GEOAPIFY";
  providerPlaceId: string | null;
  primaryLabel: string;
  secondaryLabel: string | null;
  latitude: number | null;
  longitude: number | null;
  resultType: ResultType;
  providerRegionName: string | null;
  providerLocalityName: string | null;
  /** Provider text consistency, never geographic containment proof. */
  regionMatch: AdminMatch;
  localUnitMatch: AdminMatch;
  suitability: SearchSuitability;
  attribution: ProviderAttribution | null;
};

export type CatalogueContext = {
  regionId: string;
  regionName: string;
  regionType: "STATE" | "FCT";
  localUnitId: string;
  localUnitName: string;
  otherRegionNames: string[];
  siblingLocalUnitNames: string[];
};

export type DestinationSearchDependencies = {
  authenticate: (authorization: string) => Promise<boolean>;
  catalogue: (
    regionId: string,
    localUnitId: string,
  ) => Promise<CatalogueContext | null>;
  apiKey: string;
  fetchProvider: typeof fetch;
};

const MAX_BODY_BYTES = 2_048;
const MAX_QUERY_CHARS = 120;
const MAX_RESULTS = 5;
const UPSTREAM_TIMEOUT_MS = 7_000;
const ID = /^ng-[a-z0-9]+(?:-[a-z0-9]+)*$/u;
// deno-lint-ignore no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/u;
const RESULT_TYPES = new Set([
  "amenity",
  "building",
  "street",
  "suburb",
  "district",
  "postcode",
  "city",
  "county",
  "state",
  "country",
  "unknown",
]);

export async function handleDestinationSearch(
  request: Request,
  dependencies: DestinationSearchDependencies,
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
    return error(503, "SEARCH_UNAVAILABLE");
  }
  if (
    request.headers.get("content-type")?.split(";", 1)[0].trim()
      .toLowerCase() !==
      "application/json"
  ) return error(400, "INVALID_REQUEST");

  let body: unknown;
  try {
    const bytes = await readBoundedBody(request);
    if (!bytes) return error(400, "INVALID_REQUEST");
    body = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch {
    return error(400, "INVALID_REQUEST");
  }
  const input = parseRequest(body);
  if (!input) return error(400, "INVALID_REQUEST");

  let context: CatalogueContext | null;
  try {
    context = await dependencies.catalogue(input.regionId, input.localUnitId);
  } catch {
    return error(503, "SEARCH_UNAVAILABLE");
  }
  if (!context) return error(400, "INVALID_REQUEST");
  if (!dependencies.apiKey) return error(503, "SEARCH_UNAVAILABLE");

  const url = new URL(`https://api.geoapify.com/v1/geocode/${input.mode}`);
  url.searchParams.set("text", contextualQuery(input.query, context));
  url.searchParams.set("filter", "countrycode:ng");
  url.searchParams.set("format", "json");
  url.searchParams.set("limit", String(MAX_RESULTS));
  url.searchParams.set("lang", "en");
  url.searchParams.set("apiKey", dependencies.apiKey);

  let upstream: Response;
  try {
    upstream = await dependencies.fetchProvider(url, {
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch {
    return error(503, "PROVIDER_UNAVAILABLE");
  }
  if (upstream.status === 429) return error(429, "RATE_LIMITED");
  if (upstream.status >= 500) return error(503, "PROVIDER_UNAVAILABLE");
  if (!upstream.ok) return error(502, "INVALID_PROVIDER_RESPONSE");

  let payload: unknown;
  try {
    payload = await upstream.json();
  } catch {
    return error(502, "INVALID_PROVIDER_RESPONSE");
  }
  if (!isObject(payload) || !Array.isArray(payload.results)) {
    return error(502, "INVALID_PROVIDER_RESPONSE");
  }
  if (payload.results.length === 0) {
    return json(200, { outcome: "NO_RESULTS", results: [] });
  }
  const results = payload.results.slice(0, MAX_RESULTS)
    .map((item: unknown) => parseResult(item, context, input.query))
    .filter((item: PlaceSearchResult | null): item is PlaceSearchResult =>
      item !== null
    );
  if (results.length === 0) return error(502, "INVALID_PROVIDER_RESPONSE");
  return json(200, { outcome: "OK", results });
}

async function readBoundedBody(request: Request): Promise<Uint8Array | null> {
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > MAX_BODY_BYTES) {
        await reader.cancel();
        return null;
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
  return bytes;
}

function parseRequest(value: unknown): {
  query: string;
  regionId: string;
  localUnitId: string;
  mode: SearchMode;
} | null {
  if (
    !isObject(value) ||
    Object.keys(value).some((key) =>
      !["query", "regionId", "localUnitId", "mode"].includes(key)
    )
  ) return null;
  const { query, regionId, localUnitId, mode } = value;
  const trimmedQuery = typeof query === "string" ? query.trim() : "";
  if (
    typeof query !== "string" ||
    CONTROL.test(query) ||
    [...trimmedQuery].length < 3 ||
    [...trimmedQuery].length > MAX_QUERY_CHARS ||
    typeof regionId !== "string" || !ID.test(regionId) ||
    typeof localUnitId !== "string" || !ID.test(localUnitId) ||
    (mode !== "autocomplete" && mode !== "search")
  ) return null;
  return { query: trimmedQuery, regionId, localUnitId, mode };
}

/** Context changes provider ranking only; it never changes the Traveller's visible query. */
export function contextualQuery(
  query: string,
  context: CatalogueContext,
): string {
  const lower = query.toLocaleLowerCase("en");
  if (
    context.regionType === "FCT" &&
    /\b(abuja|fct|federal capital territory)\b/iu.test(lower)
  ) {
    return query;
  }
  const parts = [query];
  if (!lower.includes(context.localUnitName.toLocaleLowerCase("en"))) {
    parts.push(context.localUnitName);
  }
  if (!lower.includes(context.regionName.toLocaleLowerCase("en"))) {
    parts.push(context.regionName);
  }
  return parts.join(", ");
}

function parseResult(
  raw: unknown,
  context: CatalogueContext,
  query: string,
): PlaceSearchResult | null {
  if (!isObject(raw)) return null;
  const name = safeText(raw.name, 160);
  const line1 = safeText(raw.address_line1, 160);
  const primaryLabel = name ?? line1;
  if (!primaryLabel) return null;
  const type = safeText(raw.result_type, 30);
  const resultType: ResultType = type === "amenity" || type === "building"
    ? "PLACE"
    : type === "street"
    ? "ROAD"
    : type && RESULT_TYPES.has(type) && type !== "unknown"
    ? "LOCALITY"
    : "UNKNOWN";
  const region = safeText(raw.state, 120);
  const locality = safeText(raw.county, 120);
  const regionMatch = matchRegion(region, context);
  const localUnitMatch = matchLocalUnit(locality, context);
  const latitude = validCoordinate(raw.lat, -90, 90);
  const longitude = validCoordinate(raw.lon, -180, 180);
  const placeId = safeText(raw.place_id, 512);
  const attribution = parseAttribution(raw.datasource);
  const country = safeText(raw.country_code, 8)?.toLowerCase();
  const specificName = name !== null && relevantName(name, query, context);
  const invalid = country !== "ng" || regionMatch === "CONTRADICTION" ||
    localUnitMatch === "CONTRADICTION" || latitude === null ||
    longitude === null ||
    !placeId || !attribution;
  const suitability: SearchSuitability = invalid || resultType === "UNKNOWN"
    ? "NOT_USABLE"
    : resultType !== "PLACE"
    ? "NEEDS_MAP_PIN"
    : specificName
    ? "POINT_CANDIDATE"
    : "NOT_USABLE";
  const secondary = [safeText(raw.city, 120), locality, region]
    .filter((part): part is string =>
      part !== null && normalized(part) !== normalized(primaryLabel)
    );
  return {
    provider: "GEOAPIFY",
    providerPlaceId: placeId,
    primaryLabel,
    secondaryLabel: [...new Set(secondary)].slice(0, 2).join(", ") || null,
    latitude,
    longitude,
    resultType,
    providerRegionName: region,
    providerLocalityName: locality,
    regionMatch,
    localUnitMatch,
    suitability,
    attribution,
  };
}

function matchRegion(
  provider: string | null,
  context: CatalogueContext,
): AdminMatch {
  if (!provider) return "UNKNOWN";
  const value = normalizedRegion(provider);
  if (
    value === normalizedRegion(context.regionName) ||
    (context.regionType === "FCT" && ["fct", "abuja"].includes(value))
  ) return "MATCH";
  return context.otherRegionNames.some((name) =>
      normalizedRegion(name) === value ||
      (normalizedRegion(name) === "federal capital territory" &&
        ["fct", "abuja"].includes(value))
    )
    ? "CONTRADICTION"
    : "UNKNOWN";
}

function matchLocalUnit(
  provider: string | null,
  context: CatalogueContext,
): AdminMatch {
  if (!provider) return "UNKNOWN";
  const value = normalized(provider);
  if (
    value === normalized(context.localUnitName) ||
    (context.localUnitId === "ng-fct-abuja-municipal" &&
      value === "municipal area council")
  ) return "MATCH";
  return context.siblingLocalUnitNames.some((name) =>
      normalized(name) === value
    )
    ? "CONTRADICTION"
    : "UNKNOWN";
}

function relevantName(
  name: string,
  query: string,
  context: CatalogueContext,
): boolean {
  const contextTokens = new Set(
    tokens(
      `${context.regionName} ${context.localUnitName} nigeria ${
        context.regionType === "FCT" ? "Abuja FCT" : ""
      }`,
    ),
  );
  const wanted = tokens(query).filter((token) => !contextTokens.has(token));
  const named = new Set(tokens(name));
  return wanted.length > 0 && wanted.every((token) => named.has(token));
}

function tokens(value: string): string[] {
  return value.toLocaleLowerCase("en").match(/[\p{L}\p{N}]+/gu) ?? [];
}

function normalized(value: string): string {
  return value.trim().toLocaleLowerCase("en").replace(/\s+/gu, " ");
}

function normalizedRegion(value: string): string {
  return normalized(value).replace(/ state$/u, "");
}

function parseAttribution(value: unknown): ProviderAttribution | null {
  if (!isObject(value)) return null;
  const sourceName = safeText(value.sourcename, 80);
  const attribution = safeText(value.attribution, 160);
  const license = safeText(value.license, 80);
  const sourceUrl = safeText(value.url, 300);
  if (!sourceName || !attribution || !license || !sourceUrl) return null;
  try {
    if (new URL(sourceUrl).protocol !== "https:") return null;
  } catch {
    return null;
  }
  return { sourceName, attribution, license, sourceUrl };
}

function validCoordinate(
  value: unknown,
  minimum: number,
  maximum: number,
): number | null {
  return typeof value === "number" && Number.isFinite(value) &&
      value >= minimum && value <= maximum
    ? value
    : null;
}

function safeText(value: unknown, maximum: number): string | null {
  return typeof value === "string" && value.trim().length > 0 &&
      value.length <= maximum && !CONTROL.test(value)
    ? value.trim()
    : null;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function error(status: number, code: string): Response {
  return json(status, { error: { code } });
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
    },
  });
}
