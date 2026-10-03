import {
  assertEquals,
  assertStringIncludes,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  type CatalogueContext,
  type DestinationSearchDependencies,
  handleDestinationSearch,
} from "./core.ts";

const KWARA: CatalogueContext = {
  regionId: "ng-kwara",
  regionName: "Kwara",
  regionType: "STATE",
  localUnitId: "ng-kwara-offa",
  localUnitName: "Offa",
  otherRegionNames: ["Lagos", "Kano", "Federal Capital Territory"],
  siblingLocalUnitNames: ["Ilorin West", "Ilorin South", "Ilorin East"],
};
const FCT: CatalogueContext = {
  regionId: "ng-fct",
  regionName: "Federal Capital Territory",
  regionType: "FCT",
  localUnitId: "ng-fct-abuja-municipal",
  localUnitName: "Abuja Municipal Area Council",
  otherRegionNames: ["Kwara", "Kano", "Lagos"],
  siblingLocalUnitNames: ["Abaji", "Bwari", "Kuje"],
};
const OSM = {
  sourcename: "openstreetmap",
  attribution: "© OpenStreetMap contributors",
  license: "Open Database License",
  url: "https://www.openstreetmap.org/copyright",
};
const WOF = {
  sourcename: "whosonfirst",
  attribution: "Who's On First",
  license: "CC0",
  url: "https://www.whosonfirst.org/docs/licenses/",
};
const GEONAMES = {
  sourcename: "geonames",
  attribution: "GeoNames",
  license: "CC BY 4.0",
  url: "https://www.geonames.org/datasources/",
};
const HOSPITAL = {
  name: "National Hospital Abuja",
  address_line1: "National Hospital Abuja",
  formatted: "National Hospital Abuja, Constitution Avenue, Abuja, Nigeria",
  lat: 9.039929,
  lon: 7.4619731,
  place_id: "hospital-place-id",
  result_type: "amenity",
  country_code: "ng",
  state: "Federal Capital Territory",
  county: "Municipal Area Council",
  city: "Abuja",
  datasource: OSM,
};

function request(
  body: Record<string, unknown> = {},
  authorization = "Bearer valid",
): Request {
  return new Request("http://localhost/functions/v1/destination-search", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization,
    },
    body: JSON.stringify({
      query: "National Hospital Abuja",
      regionId: "ng-fct",
      localUnitId: "ng-fct-abuja-municipal",
      mode: "autocomplete",
      ...body,
    }),
  });
}

function setup(options: {
  context?: CatalogueContext | null;
  providerResults?: unknown[];
  providerStatus?: number;
  authenticated?: boolean;
  throwFetch?: boolean;
} = {}) {
  const seen = { urls: [] as URL[], authenticated: 0, catalogue: 0 };
  const dependencies: DestinationSearchDependencies = {
    apiKey: "server-only-test-key",
    authenticate: () => {
      seen.authenticated++;
      return Promise.resolve(options.authenticated ?? true);
    },
    catalogue: (regionId, localUnitId) => {
      seen.catalogue++;
      const context = options.context === undefined ? FCT : options.context;
      return Promise.resolve(
        context?.regionId === regionId && context.localUnitId === localUnitId
          ? context
          : null,
      );
    },
    fetchProvider: (input) => {
      seen.urls.push(new URL(String(input)));
      if (options.throwFetch) {
        return Promise.reject(new DOMException("Timeout", "TimeoutError"));
      }
      return Promise.resolve(
        new Response(
          JSON.stringify({ results: options.providerResults ?? [HOSPITAL] }),
          {
            status: options.providerStatus ?? 200,
          },
        ),
      );
    },
  };
  return { seen, dependencies };
}

async function execute(body: Record<string, unknown> = {}, options = {}) {
  const fixture = setup(options);
  const response = await handleDestinationSearch(
    request(body),
    fixture.dependencies,
  );
  return { response, result: await response.json(), ...fixture };
}

Deno.test("requires authenticated Traveller before reading body or catalogue", async () => {
  const fixture = setup();
  const missing = await handleDestinationSearch(
    request({}, ""),
    fixture.dependencies,
  );
  assertEquals(missing.status, 401);
  assertEquals(fixture.seen.authenticated, 0);
  assertEquals(fixture.seen.catalogue, 0);
  const denied = setup({ authenticated: false });
  assertEquals(
    (await handleDestinationSearch(request(), denied.dependencies)).status,
    401,
  );
  assertEquals(denied.seen.catalogue, 0);
  assertEquals(denied.seen.urls.length, 0);
});

Deno.test("rejects short, oversized, malformed and unsupported requests", async () => {
  for (
    const body of [
      { query: "ab" },
      { query: "x".repeat(121) },
      { query: "Abu\nJa" },
      { mode: "reverse" },
      { limit: 50 },
      { regionId: "ng-missing" },
      { localUnitId: "ng-fct-bwari" },
    ]
  ) {
    const { response, seen } = await execute(body);
    assertEquals(response.status, 400);
    assertEquals(seen.urls.length, 0);
  }
  const wrongParent = await execute({
    regionId: "ng-kwara",
    localUnitId: "ng-fct-abuja-municipal",
  });
  assertEquals(wrongParent.response.status, 400);
  const trimmed = await execute({ query: "  National Hospital Abuja  " });
  assertEquals(trimmed.response.status, 200);
  assertEquals(
    trimmed.seen.urls[0].searchParams.get("text"),
    "National Hospital Abuja",
  );
  const oversizedBody = await execute({ query: "x".repeat(2_100) });
  assertEquals(oversizedBody.response.status, 400);
  assertEquals(oversizedBody.seen.urls.length, 0);
});

Deno.test("enforces Nigeria, server key, endpoint and five-result limit", async () => {
  const { response, result, seen } = await execute({}, {
    providerResults: Array(9).fill(HOSPITAL),
  });
  assertEquals(response.status, 200);
  assertEquals(result.results.length, 5);
  const url = seen.urls[0];
  assertEquals(url.pathname, "/v1/geocode/autocomplete");
  assertEquals(url.searchParams.get("filter"), "countrycode:ng");
  assertEquals(url.searchParams.get("limit"), "5");
  assertEquals(url.searchParams.get("apiKey"), "server-only-test-key");
  assertEquals(
    [...url.searchParams.keys()].sort(),
    ["apiKey", "filter", "format", "lang", "limit", "text"].sort(),
  );
  assertEquals(JSON.stringify(result).includes("server-only-test-key"), false);
  const forward = await execute({ mode: "search" });
  assertEquals(forward.seen.urls[0].pathname, "/v1/geocode/search");
  assertEquals(forward.seen.urls.length, 1);
});

Deno.test("contextual query is deterministic and leaves visible request text untouched", async () => {
  const market = await execute({
    query: "Central Market",
    regionId: "ng-kwara",
    localUnitId: "ng-kwara-offa",
  }, { context: KWARA });
  assertEquals(
    market.seen.urls[0].searchParams.get("text"),
    "Central Market, Offa, Kwara",
  );
  const hospital = await execute();
  assertEquals(
    hospital.seen.urls[0].searchParams.get("text"),
    "National Hospital Abuja",
  );
  assertEquals(
    await hospital.result.results[0].primaryLabel,
    "National Hospital Abuja",
  );
});

Deno.test("specific hospital is only POINT_CANDIDATE despite provider-text MATCH", async () => {
  const { result } = await execute();
  const item = result.results[0];
  assertEquals(item.suitability, "POINT_CANDIDATE");
  assertEquals(item.regionMatch, "MATCH");
  assertEquals(item.localUnitMatch, "MATCH");
  assertEquals(item.resultType, "PLACE");
  assertEquals(item.latitude, 9.039929);
  assertEquals("boundaryVerified" in item, false);
  assertEquals("eligibility" in item, false);
  assertEquals("destination" in result, false);
});

Deno.test("wrong State or known other local unit is NOT_USABLE", async () => {
  const wrongState = await execute({}, {
    providerResults: [{ ...HOSPITAL, state: "Lagos State" }],
  });
  assertEquals(wrongState.result.results[0].regionMatch, "CONTRADICTION");
  assertEquals(wrongState.result.results[0].suitability, "NOT_USABLE");
  const wrongFctAlias = await execute({
    query: "Central Market",
    regionId: "ng-kwara",
    localUnitId: "ng-kwara-offa",
  }, {
    context: KWARA,
    providerResults: [{ ...HOSPITAL, name: "Central Market", state: "Abuja" }],
  });
  assertEquals(wrongFctAlias.result.results[0].regionMatch, "CONTRADICTION");
  assertEquals(wrongFctAlias.result.results[0].suitability, "NOT_USABLE");
  const wrongUnit = await execute({
    query: "Central Market",
    regionId: "ng-kwara",
    localUnitId: "ng-kwara-offa",
  }, {
    context: KWARA,
    providerResults: [{
      ...HOSPITAL,
      name: "Central Market",
      state: "Kwara State",
      county: "Ilorin West",
    }],
  });
  assertEquals(wrongUnit.result.results[0].localUnitMatch, "CONTRADICTION");
  assertEquals(wrongUnit.result.results[0].suitability, "NOT_USABLE");
});

Deno.test("missing provider county is UNKNOWN, not boundary verification", async () => {
  const { result } = await execute({}, {
    providerResults: [{ ...HOSPITAL, county: undefined }],
  });
  assertEquals(result.results[0].localUnitMatch, "UNKNOWN");
  assertEquals(result.results[0].suitability, "POINT_CANDIDATE");
  assertEquals("boundaryVerified" in result.results[0], false);
});

Deno.test("roads and broad localities need a pin", async () => {
  const road = await execute({ query: "Zaria Road" }, {
    providerResults: [
      { ...HOSPITAL, name: "Zaria Road", result_type: "street" },
    ],
  });
  assertEquals(road.result.results[0].suitability, "NEEDS_MAP_PIN");
  assertEquals(road.result.results[0].resultType, "ROAD");
  for (const type of ["city", "county", "state", "district", "suburb"]) {
    const { result } = await execute({}, {
      providerResults: [
        { ...HOSPITAL, result_type: type },
      ],
    });
    assertEquals(result.results[0].suitability, "NEEDS_MAP_PIN");
  }
});

Deno.test("malformed identity, coordinates, names, or attribution never make a point candidate", async () => {
  for (
    const change of [
      { lat: null },
      { lon: 181 },
      { place_id: "" },
      { country_code: "gb" },
      { name: "Unrelated University" },
      { datasource: { ...OSM, attribution: "" } },
      { result_type: "unexpected" },
    ]
  ) {
    const { result } = await execute({}, {
      providerResults: [{ ...HOSPITAL, ...change }],
    });
    assertEquals(result.results[0].suitability, "NOT_USABLE");
  }
});

Deno.test("attribution transports all four fields without raw datasource", async () => {
  for (const source of [OSM, WOF, GEONAMES]) {
    const { result } = await execute({}, {
      providerResults: [{ ...HOSPITAL, datasource: source }],
    });
    assertEquals(result.results[0].attribution, {
      sourceName: source.sourcename,
      attribution: source.attribution,
      license: source.license,
      sourceUrl: source.url,
    });
    assertEquals("datasource" in result.results[0], false);
  }
});

Deno.test("no results, malformed payload, rate limit, timeout and 5xx are distinct", async () => {
  const empty = await execute({}, { providerResults: [] });
  assertEquals(empty.result.outcome, "NO_RESULTS");
  const rate = await execute({}, { providerStatus: 429 });
  assertEquals(rate.response.status, 429);
  assertEquals(rate.result.error.code, "RATE_LIMITED");
  const timeout = await execute({}, { throwFetch: true });
  assertEquals(timeout.result.error.code, "PROVIDER_UNAVAILABLE");
  const server = await execute({}, { providerStatus: 503 });
  assertEquals(server.result.error.code, "PROVIDER_UNAVAILABLE");
  const malformed = setup();
  malformed.dependencies.fetchProvider = () =>
    Promise.resolve(new Response("{", { status: 200 }));
  const response = await handleDestinationSearch(
    request(),
    malformed.dependencies,
  );
  assertEquals(response.status, 502);
  assertEquals((await response.json()).error.code, "INVALID_PROVIDER_RESPONSE");
  const shape = setup();
  shape.dependencies.fetchProvider = () =>
    Promise.resolve(new Response('{"items":[]}', { status: 200 }));
  assertEquals(
    (await handleDestinationSearch(request(), shape.dependencies)).status,
    502,
  );
});

Deno.test("malformed individual result is skipped; no usable parse fails closed", async () => {
  const mixed = await execute({}, { providerResults: [null, HOSPITAL] });
  assertEquals(mixed.result.results.length, 1);
  const bad = await execute({}, { providerResults: [null, { lat: 1 }] });
  assertEquals(bad.result.error.code, "INVALID_PROVIDER_RESPONSE");
});

Deno.test("application response and logging omit sensitive query, coordinates, key and URL", async () => {
  const logs: string[] = [];
  const original = console.log;
  console.log = (...values: unknown[]) => {
    logs.push(values.join(" "));
  };
  try {
    const { result } = await execute({ query: "National Hospital Abuja" }, {
      providerStatus: 503,
    });
    assertEquals(result.error.code, "PROVIDER_UNAVAILABLE");
  } finally {
    console.log = original;
  }
  assertEquals(logs.length, 0);
  assertEquals(JSON.stringify(logs).includes("National Hospital Abuja"), false);
  assertStringIncludes(JSON.stringify(OSM), "OpenStreetMap");
});
