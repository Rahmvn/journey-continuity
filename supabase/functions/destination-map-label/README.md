# Destination map backend foundation

This function resolves descriptive metadata for a map pin. It does not create a
Journey, persist a destination, establish arrival, or provide map tiles.

## Independent containment verification

Map clients use the existing authenticated
`public.verify_destination_coordinate_v1(p_region_id, p_local_unit_id, p_latitude, p_longitude)`
RPC directly. Its signature, permissions and geographic semantics are unchanged.
Only `INSIDE` with the actual boundary dataset version makes a point usable.

## Camera initialization

`public.get_destination_local_unit_viewport_v1(p_region_id, p_local_unit_id)`
accepts canonical IDs and requires the same verified, non-anonymous Traveller
with a profile as the verifier. Only `authenticated` has EXECUTE.

Available response:

```json
{
  "status": "AVAILABLE",
  "west": 4.0,
  "south": 8.0,
  "east": 4.4,
  "north": 8.3,
  "boundary_dataset_id": "NG_OPERATIONAL_BOUNDARIES",
  "boundary_dataset_version": "alabarin-2026-10-03-v1"
}
```

The example numbers illustrate the shape, not production Offa bounds. Failures
return only `{"status":"INVALID_CONTEXT"}` or `{"status":"UNAVAILABLE"}`.
Missing authentication raises `42501`. Bounds cover all multipart components;
unsupported antimeridian spans, invalid bounds and incomplete current snapshots
fail closed. No older snapshot is silently substituted.

**Camera bounds never select a destination or prove containment.** No raw
geometry or centre coordinate is returned. Raw boundary permissions do not
change.

## Reverse-label request

Authenticated `POST /functions/v1/destination-map-label`, `application/json`:

```json
{
  "regionId": "ng-fct",
  "localUnitId": "ng-fct-abuja-municipal",
  "latitude": 9.039929,
  "longitude": 7.4619731
}
```

Only these four keys are accepted. IDs must be canonical syntax and at most 120
characters. Coordinates must be finite geographic numbers. The UTF-8 body is
limited to 2,048 bytes. Authentication checks the verified non-anonymous Auth
user and its existing Traveller profile before catalogue or provider access.

The adapter resolves current canonical context using existing service-only
catalogue SELECT grants, then invokes the unchanged public verifier with the
caller's JWT. It calls Geoapify only for a fresh `INSIDE` result with a boundary
version. It sends the exact requested coordinate to `/v1/geocode/reverse`, with
one result, a seven-second timeout, no redirects and no retries. Provider bodies
are limited to 65,536 bytes. No custom name or other Traveller data is accepted.

## Responses

Authenticated, valid requests use HTTP 200 for domain outcomes. Every response
uses `Cache-Control: no-store`.

For unsuccessful verification:

```json
{
  "status": "VERIFICATION_FAILED",
  "boundaryOutcome": "OUTSIDE_SELECTED_UNIT",
  "boundaryDatasetVersion": "alabarin-2026-10-03-v1"
}
```

The other outcomes are `BOUNDARY_AMBIGUOUS`, `COVERAGE_UNRESOLVED`,
`UNAVAILABLE` and `INVALID_CONTEXT`; the last two carry a null version. They
never call Geoapify.

For verified points, `boundaryOutcome` remains `INSIDE`, with a nonblank
`boundaryDatasetVersion` and `canonicalLocalUnitName`. The `status` is one of:

- `SPECIFIC_LABEL`: a supported named amenity/building, supplied premise address
  with supporting street/house-number fields, or named road.
- `BROAD_ONLY`: documented administrative/locality/postcode result type with
  descriptive text. No provider label is retained as an exact name.
- `NO_MEANINGFUL_LABEL`: empty, unknown, generic, unsupported, non-Nigerian or
  inadequately attributed description.
- `PROVIDER_UNAVAILABLE`: missing geocoding credential, timeout, non-success
  HTTP, malformed or oversized provider response.

Verified responses always include these metadata keys:

```text
label: string | null
secondaryLabel: string | null
resultType: PLACE | ROAD | LOCALITY | POSTCODE | UNKNOWN
provider: GEOAPIFY | null
providerPlaceId: string | null
distanceMeters: finite nonnegative number | null
attribution: { sourceName, attribution, license, sourceUrl } | null
```

Only `SPECIFIC_LABEL` retains a label and provider provenance. It requires
complete bounded attribution with an HTTPS source URL. Provider identifiers and
distance are optional and never manufactured. Result type and supported
feature/address fields determine specificity; nonblank text or confidence alone
does not. Descriptions equal to administrative context and generic feature names
are discarded. Unknown result vocabulary is conservative. Provider coordinates,
raw objects, rank and arbitrary provider fields are never returned.

A specific description remains provider metadata, not proof that its returned
feature is the selected coordinate. Distance, when supplied, is retained for
later presentation policy. The requested/verified coordinate never moves.

For any verified response without a useful label, later Android may use:
`Pinned location in {canonicalLocalUnitName}`. Provider failure does not
invalidate the independent `INSIDE` fact. No generic address is manufactured by
this function.

Request errors use `{ "error": { "code": "..." } }`: 400 `INVALID_REQUEST`, 401
`UNAUTHORIZED`, 405 `INVALID_REQUEST`, 503 `LABEL_UNAVAILABLE`, or 499
`REQUEST_CANCELLED`. Cancellation does not become a provider result.

## Secrets, provenance and request limits

Use existing server secrets `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
`SUPABASE_SERVICE_ROLE_KEY`, and `GEOAPIFY_API_KEY`. Missing Geoapify
configuration still allows re-verification and the truthful provider-unavailable
fallback. Never place the geocoding key in Android or log requests, coordinates,
tokens, credential-bearing URLs, provider objects or exceptions.

The current destination-search implementation has no reusable per-user limiter.
This slice adds bounded request/provider bodies, one upstream call and a
timeout, but no distributed per-user quota mechanism. Client cancellation may
happen after an upstream request has already been processed.

Attribution here is reverse-geocoding provenance. Map tile attribution and GRID3
source credits are separate responsibilities; no map tiles are rendered here.

Provider vocabulary reference:
https://apidocs.geoapify.com/docs/geocoding/reverse-geocoding/

## Focused deterministic tests

```text
deno test supabase/functions/destination-map-label/core_test.ts
deno check supabase/functions/destination-map-label/index.ts
deno lint supabase/functions/destination-map-label
deno fmt --check supabase/functions/destination-map-label
```

`supabase/tests/destination_map_viewport_test.sql` is a transaction-scoped pgTAP
suite for an isolated PostgreSQL/PostGIS test database with the existing
committed migrations and this slice's migration applied. Its fixture changes
roll back. Do not run database setup or fixtures against shared/hosted databases
implicitly.
