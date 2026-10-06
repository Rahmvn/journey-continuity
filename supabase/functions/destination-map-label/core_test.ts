import {
  assertEquals,
  assertStringIncludes,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import type { BoundaryOutcome } from "../destination-search/core.ts";
import {
  handleDestinationMapLabel,
  type MapLabelDependencies,
  type MapLabelRequest,
} from "./core.ts";

const POINT: MapLabelRequest = {
  regionId: "ng-fct",
  localUnitId: "ng-fct-abuja-municipal",
  latitude: 9.039929,
  longitude: 7.4619731,
};
const VERSION = "alabarin-2026-10-03-v1";
const OSM = {
  sourcename: "openstreetmap",
  attribution: "© OpenStreetMap contributors",
  license: "Open Database License",
  url: "https://www.openstreetmap.org/copyright",
};
const HOSPITAL = {
  result_type: "amenity",
  name: "National Hospital Abuja",
  address_line1: "National Hospital Abuja",
  address_line2: "Constitution Avenue, Abuja, Nigeria",
  country_code: "ng",
  state: "Federal Capital Territory",
  city: "Abuja",
  county: "Municipal Area Council",
  lat: 9.04,
  lon: 7.46,
  place_id: "hospital-place-id",
  distance: 12.5,
  datasource: OSM,
  private_provider_field: "must not escape",
  rank: { confidence: 1 },
};
function request(
  body: unknown = POINT,
  options: {
    auth?: string;
    method?: string;
    contentType?: string;
    raw?: string;
    signal?: AbortSignal;
  } = {},
) {
  const method = options.method ?? "POST";
  return new Request("http://localhost/functions/v1/destination-map-label", {
    method,
    headers: {
      authorization: options.auth ?? "Bearer valid",
      "content-type": options.contentType ?? "application/json",
    },
    body: method === "POST" ? options.raw ?? JSON.stringify(body) : undefined,
    signal: options.signal,
  });
}
function setup(options: {
  authenticated?: boolean;
  authThrows?: boolean;
  contextMissing?: boolean;
  catalogueThrows?: boolean;
  outcome?: BoundaryOutcome;
  version?: string | null;
  verificationThrows?: boolean;
  results?: unknown[];
  payload?: unknown;
  providerStatus?: number;
  providerThrows?: boolean;
  providerBody?: string;
  apiKey?: string;
} = {}) {
  const seen = {
    auth: 0,
    catalogue: 0,
    verification: [] as MapLabelRequest[],
    urls: [] as URL[],
    signals: [] as AbortSignal[],
    calls: [] as string[],
  };
  const dependencies: MapLabelDependencies = {
    apiKey: options.apiKey ?? "server-only-test-key",
    authenticate: () => {
      seen.auth++;
      seen.calls.push("auth");
      if (options.authThrows) throw new Error("private authentication detail");
      return Promise.resolve(options.authenticated ?? true);
    },
    catalogue: (regionId, localUnitId) => {
      seen.catalogue++;
      seen.calls.push("catalogue");
      if (options.catalogueThrows) throw new Error("private catalogue detail");
      return Promise.resolve(
        options.contextMissing ? null : {
          regionId,
          localUnitId,
          regionName: regionId === "ng-kwara"
            ? "Kwara"
            : regionId === "ng-lagos"
            ? "Lagos"
            : "Federal Capital Territory",
          localUnitName: localUnitId === "ng-kwara-offa"
            ? "Offa"
            : localUnitId === "ng-lagos-eti-osa"
            ? "Eti-Osa"
            : "Abuja Municipal Area Council",
        },
      );
    },
    verifyBoundary: (input) => {
      seen.verification.push(input);
      seen.calls.push("verify");
      if (options.verificationThrows) {
        throw new Error("private verification detail");
      }
      return Promise.resolve({
        outcome: options.outcome ?? "INSIDE",
        boundaryDatasetVersion: options.version === undefined
          ? options.outcome === "UNAVAILABLE" ||
              options.outcome === "INVALID_CONTEXT"
            ? null
            : VERSION
          : options.version,
      });
    },
    fetchProvider: (input, init) => {
      seen.urls.push(new URL(String(input)));
      seen.calls.push("provider");
      seen.signals.push(init!.signal!);
      assertEquals(init!.method, "GET");
      assertEquals(init!.redirect, "error");
      if (options.providerThrows) {
        throw new DOMException(
          "credential-bearing provider error",
          "TimeoutError",
        );
      }
      return Promise.resolve(
        new Response(
          options.providerBody ??
            JSON.stringify(
              options.payload ?? { results: options.results ?? [HOSPITAL] },
            ),
          { status: options.providerStatus ?? 200 },
        ),
      );
    },
  };
  return { seen, dependencies };
}
async function execute(
  options: Parameters<typeof setup>[0] = {},
  input: MapLabelRequest = POINT,
) {
  const fixture = setup(options);
  const response = await handleDestinationMapLabel(
    request(input),
    fixture.dependencies,
  );
  return { ...fixture, response, result: await response.json() };
}

Deno.test("authentication precedes body, catalogue, verification and provider", async () => {
  const missing = setup();
  assertEquals(
    (await handleDestinationMapLabel(
      request(POINT, { auth: "" }),
      missing.dependencies,
    )).status,
    401,
  );
  assertEquals(missing.seen.calls, []);
  const denied = setup({ authenticated: false });
  assertEquals(
    (await handleDestinationMapLabel(
      request(POINT, { raw: "malformed" }),
      denied.dependencies,
    )).status,
    401,
  );
  assertEquals(denied.seen.calls, ["auth"]);
  const failed = await execute({ authThrows: true });
  assertEquals(failed.response.status, 503);
  assertEquals(failed.result, { error: { code: "LABEL_UNAVAILABLE" } });
  assertEquals(failed.seen.urls, []);
});
Deno.test("POST and JSON content type required", async () => {
  for (const options of [{ method: "GET" }, { contentType: "text/plain" }]) {
    const fixture = setup();
    const response = await handleDestinationMapLabel(
      request(POINT, options),
      fixture.dependencies,
    );
    assertEquals(response.status, options.method ? 405 : 400);
    assertEquals(fixture.seen.urls, []);
  }
});
Deno.test("exact keys, object shape and canonical ID syntax", async () => {
  for (
    const body of [
      null,
      [],
      {},
      { ...POINT, customName: "private name" },
      { ...POINT, inside: true },
      { ...POINT, latitude: undefined },
      { ...POINT, regionId: "NG-fct" },
      { ...POINT, localUnitId: "ng-fct/amac" },
      { ...POINT, regionId: "ng-fct " },
      { ...POINT, localUnitId: "ng-" + "a".repeat(121) },
    ]
  ) {
    const fixture = setup();
    assertEquals(
      (await handleDestinationMapLabel(request(body), fixture.dependencies))
        .status,
      400,
    );
    assertEquals(fixture.seen.calls, ["auth"]);
  }
});
Deno.test("coordinates must be finite numbers in geographic range", async () => {
  for (
    const body of [
      { ...POINT, latitude: 91 },
      { ...POINT, latitude: -91 },
      { ...POINT, longitude: 181 },
      { ...POINT, longitude: -181 },
      { ...POINT, latitude: "9" },
      { ...POINT, longitude: null },
      { ...POINT, latitude: NaN },
      { ...POINT, longitude: Infinity },
    ]
  ) {
    const fixture = setup();
    assertEquals(
      (await handleDestinationMapLabel(request(body), fixture.dependencies))
        .status,
      400,
    );
    assertEquals(fixture.seen.verification, []);
    assertEquals(fixture.seen.urls, []);
  }
  const fixture = setup();
  assertEquals(
    (await handleDestinationMapLabel(
      request(POINT, {
        raw:
          '{"regionId":"ng-fct","localUnitId":"ng-fct-abuja-municipal","latitude":1e999,"longitude":7}',
      }),
      fixture.dependencies,
    )).status,
    400,
  );
});
Deno.test("bounded request bytes and valid JSON required", async () => {
  for (
    const raw of [
      "{",
      " ".repeat(2049),
      JSON.stringify({ ...POINT, regionId: "ng-" + "a".repeat(2048) }),
    ]
  ) {
    const fixture = setup();
    assertEquals(
      (await handleDestinationMapLabel(
        request(POINT, { raw }),
        fixture.dependencies,
      )).status,
      400,
    );
    assertEquals(fixture.seen.catalogue, 0);
  }
});
Deno.test("canonical context required before verification/provider", async () => {
  const missing = await execute({ contextMissing: true });
  assertEquals(missing.result, {
    status: "VERIFICATION_FAILED",
    boundaryOutcome: "INVALID_CONTEXT",
    boundaryDatasetVersion: null,
  });
  assertEquals(missing.seen.verification, []);
  assertEquals(missing.seen.urls, []);
  const unavailable = await execute({ catalogueThrows: true });
  assertEquals(unavailable.result.boundaryOutcome, "UNAVAILABLE");
  assertEquals(unavailable.seen.urls, []);
});
for (
  const outcome of [
    "OUTSIDE_SELECTED_UNIT",
    "BOUNDARY_AMBIGUOUS",
    "COVERAGE_UNRESOLVED",
    "UNAVAILABLE",
    "INVALID_CONTEXT",
  ] as const
) {
  Deno.test(`${outcome} never calls provider`, async () => {
    const fixture = outcome === "OUTSIDE_SELECTED_UNIT"
      ? {
        regionId: "ng-kwara",
        localUnitId: "ng-kwara-offa",
        latitude: 8.1998492,
        longitude: 4.6316354,
      }
      : outcome === "BOUNDARY_AMBIGUOUS"
      ? {
        regionId: "ng-kwara",
        localUnitId: "ng-kwara-offa",
        latitude: 8.14435408227513,
        longitude: 4.65057447773,
      }
      : outcome === "COVERAGE_UNRESOLVED"
      ? {
        regionId: "ng-lagos",
        localUnitId: "ng-lagos-eti-osa",
        latitude: 6.463625,
        longitude: 3.396625,
      }
      : POINT;
    const result = await execute({ outcome }, fixture);
    assertEquals(result.result, {
      status: "VERIFICATION_FAILED",
      boundaryOutcome: outcome,
      boundaryDatasetVersion:
        outcome === "UNAVAILABLE" || outcome === "INVALID_CONTEXT"
          ? null
          : VERSION,
    });
    assertEquals(result.seen.verification, [fixture]);
    assertEquals(result.seen.urls, []);
  });
}
Deno.test("verification exceptions and missing/invalid version fail closed", async () => {
  for (
    const options of [{ verificationThrows: true }, { version: null }, {
      version: "",
    }, { version: "bad\nversion" }]
  ) {
    const result = await execute(options);
    assertEquals(result.result.boundaryOutcome, "UNAVAILABLE");
    assertEquals(result.seen.urls, []);
  }
});
Deno.test("INSIDE calls reverse once with exact selected coordinate and server key", async () => {
  for (
    const point of [POINT, {
      regionId: "ng-kwara",
      localUnitId: "ng-kwara-offa",
      latitude: 8.1560008,
      longitude: 4.7227435,
    }]
  ) {
    const result = await execute({}, point);
    assertEquals(result.seen.calls, [
      "auth",
      "catalogue",
      "verify",
      "provider",
    ]);
    assertEquals(result.seen.verification, [point]);
    assertEquals(result.seen.urls.length, 1);
    const url = result.seen.urls[0];
    assertEquals(
      url.origin + url.pathname,
      "https://api.geoapify.com/v1/geocode/reverse",
    );
    assertEquals(url.searchParams.get("lat"), String(point.latitude));
    assertEquals(url.searchParams.get("lon"), String(point.longitude));
    assertEquals(url.searchParams.get("apiKey"), "server-only-test-key");
    assertEquals(url.searchParams.get("limit"), "1");
    assertEquals([...url.searchParams.keys()].sort(), [
      "apiKey",
      "format",
      "lang",
      "lat",
      "limit",
      "lon",
    ]);
    assertEquals(result.response.headers.get("cache-control"), "no-store");
  }
});
Deno.test("specific amenity retains bounded provenance and descriptive distance only", async () => {
  const result = await execute();
  assertEquals(result.result, {
    status: "SPECIFIC_LABEL",
    boundaryOutcome: "INSIDE",
    boundaryDatasetVersion: VERSION,
    canonicalLocalUnitName: "Abuja Municipal Area Council",
    label: "National Hospital Abuja",
    secondaryLabel: "Constitution Avenue, Abuja, Nigeria",
    resultType: "PLACE",
    provider: "GEOAPIFY",
    providerPlaceId: "hospital-place-id",
    distanceMeters: 12.5,
    attribution: {
      sourceName: OSM.sourcename,
      attribution: OSM.attribution,
      license: OSM.license,
      sourceUrl: OSM.url,
    },
  });
  const text = JSON.stringify(result.result);
  for (
    const field of [
      "private_provider_field",
      "rank",
      "datasource",
      "latitude",
      "longitude",
      '"lat"',
      '"lon"',
      "server-only-test-key",
    ]
  ) assertEquals(text.includes(field), false);
});
Deno.test("named building, supported premise and named road are specific", async () => {
  for (
    const raw of [
      { ...HOSPITAL, result_type: "building", name: "Main entrance" },
      {
        ...HOSPITAL,
        result_type: "building",
        name: null,
        address_line1: "12 Constitution Avenue",
        street: "Constitution Avenue",
        housenumber: "12",
      },
      {
        ...HOSPITAL,
        result_type: "street",
        name: null,
        street: "Constitution Avenue",
      },
    ]
  ) {
    const result = await execute({ results: [raw] });
    assertEquals(result.result.status, "SPECIFIC_LABEL");
    assertEquals(
      result.result.resultType,
      raw.result_type === "street" ? "ROAD" : "PLACE",
    );
  }
});
Deno.test("normalized generic placeholders never become specific labels", async () => {
  for (
    const name of [
      "Unnamed",
      "Unnamed building",
      "Unnamed road",
      "Unknown",
      "Unknown place",
      "No name",
      "Unnamed place",
      "  UNNAMED  ",
      "  Unnamed   Building  ",
    ]
  ) {
    for (const result_type of ["amenity", "building", "street"]) {
      const result = await execute({
        results: [{
          ...HOSPITAL,
          result_type,
          name,
          address_line1: name,
          street: name,
        }],
      });
      assertEquals(result.result.status, "NO_MEANINGFUL_LABEL");
      assertEquals(result.result.label, null);
      assertEquals(result.result.boundaryOutcome, "INSIDE");
    }
  }
});
Deno.test("placeholder matching preserves genuinely named buildings and amenities", async () => {
  for (
    const [result_type, name] of [
      ["building", "Unknown Soldiers Memorial"],
      ["amenity", "Unnamed Artists Gallery"],
      ["amenity", "National Hospital Abuja"],
    ]
  ) {
    const result = await execute({
      results: [{ ...HOSPITAL, result_type, name }],
    });
    assertEquals(result.result.status, "SPECIFIC_LABEL");
    assertEquals(result.result.label, name);
  }
});
Deno.test("placeholder names preserve useful premise and broad description fallbacks", async () => {
  const premise = await execute({
    results: [{
      ...HOSPITAL,
      result_type: "building",
      name: "Unnamed building",
      address_line1: "12 Constitution Avenue",
      street: "Constitution Avenue",
      housenumber: "12",
    }],
  });
  assertEquals(premise.result.status, "SPECIFIC_LABEL");
  assertEquals(premise.result.label, "12 Constitution Avenue");
  const broad = await execute({
    results: [{
      ...HOSPITAL,
      result_type: "city",
      name: "Unnamed",
      address_line1: "Abuja",
    }],
  });
  assertEquals(broad.result.status, "BROAD_ONLY");
  assertEquals(broad.result.label, null);
});
for (
  const type of [
    "suburb",
    "district",
    "postcode",
    "city",
    "county",
    "state",
    "country",
  ]
) {
  Deno.test(`${type} text remains BROAD_ONLY`, async () => {
    const result = await execute({
      results: [{ ...HOSPITAL, result_type: type, name: "Abuja" }],
    });
    assertEquals(result.result.status, "BROAD_ONLY");
    assertEquals(result.result.label, null);
    assertEquals(result.result.provider, null);
    assertEquals(result.result.attribution, null);
    assertEquals(result.result.boundaryOutcome, "INSIDE");
  });
}
Deno.test("unknown vocabulary, absent name, generic and administrative descriptions are conservative", async () => {
  for (
    const raw of [
      { ...HOSPITAL, result_type: "unknown" },
      { ...HOSPITAL, result_type: "junction" },
      { ...HOSPITAL, name: null, address_line1: null },
      {
        ...HOSPITAL,
        name: "Abuja Municipal Area Council",
        address_line1: "Abuja Municipal Area Council",
      },
      { ...HOSPITAL, name: "Abuja, Nigeria" },
      { ...HOSPITAL, name: "building" },
      {
        ...HOSPITAL,
        result_type: "building",
        name: null,
        address_line1: "Abuja",
        street: null,
      },
      { ...HOSPITAL, country_code: "gb" },
      { ...HOSPITAL, name: "Bad\nName", address_line1: null },
      { ...HOSPITAL, name: "a".repeat(161), address_line1: null },
    ]
  ) {
    assertEquals(
      (await execute({ results: [raw] })).result.status,
      "NO_MEANINGFUL_LABEL",
    );
  }
});
Deno.test("empty provider response yields no meaningful label without invalidating INSIDE", async () => {
  const result = await execute({ results: [] });
  assertEquals(result.result.status, "NO_MEANINGFUL_LABEL");
  assertEquals(result.result.label, null);
  assertEquals(result.result.boundaryOutcome, "INSIDE");
  assertEquals(result.result.boundaryDatasetVersion, VERSION);
});
Deno.test("attribution must be complete bounded HTTPS provenance", async () => {
  for (
    const datasource of [
      null,
      {},
      { ...OSM, url: "http://example.com" },
      { ...OSM, url: "https://user:secret@example.com" },
      { ...OSM, license: "" },
      { ...OSM, attribution: "bad\ncredit" },
      { ...OSM, sourcename: "a".repeat(81) },
    ]
  ) {
    const result = await execute({ results: [{ ...HOSPITAL, datasource }] });
    assertEquals(result.result.status, "NO_MEANINGFUL_LABEL");
    assertEquals(result.result.label, null);
  }
});
Deno.test("optional provider ID/distance are not manufactured", async () => {
  for (const distance of [-1, null, "12", Infinity]) {
    const result = await execute({
      results: [{ ...HOSPITAL, place_id: null, distance }],
    });
    assertEquals(result.result.status, "SPECIFIC_LABEL");
    assertEquals(result.result.providerPlaceId, null);
    assertEquals(result.result.distanceMeters, null);
  }
});
Deno.test("finite distance metadata retains normal values and the physical upper bound", async () => {
  for (const distance of [0, 12.5, 20_099_999, 20_100_000]) {
    const result = await execute({ results: [{ ...HOSPITAL, distance }] });
    assertEquals(result.result.distanceMeters, distance);
    assertEquals(result.result.status, "SPECIFIC_LABEL");
    assertEquals(result.result.boundaryOutcome, "INSIDE");
    assertEquals(result.result.boundaryDatasetVersion, VERSION);
  }
});
Deno.test("out-of-range or malformed distance metadata is null without changing label or geography", async () => {
  for (
    const distance of [
      20_100_001,
      1e300,
      -1,
      NaN,
      Infinity,
      -Infinity,
      null,
      undefined,
      "12",
    ]
  ) {
    // JSON serializes non-finite numbers as null; absent fields remain absent.
    const result = await execute({ results: [{ ...HOSPITAL, distance }] });
    assertEquals(result.result.distanceMeters, null);
    assertEquals(result.result.status, "SPECIFIC_LABEL");
    assertEquals(result.result.label, HOSPITAL.name);
    assertEquals(result.result.boundaryOutcome, "INSIDE");
    assertEquals(result.result.boundaryDatasetVersion, VERSION);
  }
  const result = await execute({
    providerBody: JSON.stringify({ results: [HOSPITAL] }).replace(
      '"distance":12.5',
      '"distance":1e999',
    ),
  });
  assertEquals(result.result.distanceMeters, null);
  assertEquals(result.result.status, "SPECIFIC_LABEL");
  assertEquals(result.result.boundaryOutcome, "INSIDE");
});
for (const status of [429, 500, 503, 401, 302]) {
  Deno.test(`provider HTTP ${status} preserves INSIDE with PROVIDER_UNAVAILABLE`, async () => {
    const result = await execute({ providerStatus: status });
    assertEquals(result.response.status, 200);
    assertEquals(result.result.status, "PROVIDER_UNAVAILABLE");
    assertEquals(result.result.boundaryOutcome, "INSIDE");
    assertEquals(result.result.boundaryDatasetVersion, VERSION);
    assertEquals(result.seen.urls.length, 1);
  });
}
Deno.test("provider timeout, missing key, malformed and oversized payload preserve INSIDE", async () => {
  for (
    const options of [
      { providerThrows: true },
      { apiKey: "" },
      { payload: { features: [] } },
      { providerBody: "{" },
      { providerBody: "x".repeat(65_537) },
    ]
  ) {
    const result = await execute(options);
    assertEquals(result.result.status, "PROVIDER_UNAVAILABLE");
    assertEquals(result.result.boundaryOutcome, "INSIDE");
    assertEquals(result.result.boundaryDatasetVersion, VERSION);
    assertEquals(
      JSON.stringify(result.result).includes("credential-bearing"),
      false,
    );
  }
});
Deno.test("request cancellation does not become label failure or provider retry", async () => {
  const controller = new AbortController();
  controller.abort();
  const fixture = setup();
  const response = await handleDestinationMapLabel(
    request(POINT, { signal: controller.signal }),
    fixture.dependencies,
  );
  assertEquals(response.status, 499);
  assertEquals(fixture.seen.urls, []);
  assertStringIncludes(await response.text(), "REQUEST_CANCELLED");
});

for (const stage of ["auth", "catalogue", "verify", "provider"]) {
  Deno.test(`cancellation during ${stage} remains cancellation`, async () => {
    const controller = new AbortController();
    const fixture = setup();
    const cancel = () => {
      controller.abort();
      throw new DOMException("Aborted", "AbortError");
    };
    if (stage === "auth") fixture.dependencies.authenticate = cancel;
    if (stage === "catalogue") fixture.dependencies.catalogue = cancel;
    if (stage === "verify") fixture.dependencies.verifyBoundary = cancel;
    if (stage === "provider") fixture.dependencies.fetchProvider = cancel;
    const response = await handleDestinationMapLabel(
      request(POINT, { signal: controller.signal }),
      fixture.dependencies,
    );
    assertEquals(response.status, 499);
    assertStringIncludes(await response.text(), "REQUEST_CANCELLED");
    assertEquals(fixture.seen.urls, []);
  });
}
