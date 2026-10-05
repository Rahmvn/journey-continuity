import { createClient } from "npm:@supabase/supabase-js@2.116.0";
import {
  type BoundaryVerification,
  type CatalogueContext,
  handleDestinationSearch,
} from "./core.ts";

type RegionRow = {
  id: string;
  canonical_name: string;
  region_type: "STATE" | "FCT";
};
type UnitRow = { id: string; parent_region_id: string; canonical_name: string };
type Snapshot = { version: string; regions: RegionRow[]; units: UnitRow[] };

let cachedSnapshot: Promise<Snapshot> | undefined;
let cacheExpiresAt = 0;

Deno.serve(async (request) => {
  try {
    const url = requiredEnvironment("SUPABASE_URL");
    const anonKey = requiredEnvironment("SUPABASE_ANON_KEY");
    const serviceKey = requiredEnvironment("SUPABASE_SERVICE_ROLE_KEY");
    const geoapifyKey = requiredEnvironment("GEOAPIFY_API_KEY");
    const service = createClient(url, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    return await handleDestinationSearch(request, {
      apiKey: geoapifyKey,
      fetchProvider: fetch,
      authenticate: async (authorization) => {
        const caller = createClient(url, anonKey, {
          global: { headers: { Authorization: authorization } },
          auth: { persistSession: false, autoRefreshToken: false },
        });
        const { data: auth, error: authError } = await caller.auth.getUser();
        if (
          authError || !auth.user || auth.user.is_anonymous !== false ||
          !auth.user.email_confirmed_at
        ) return false;
        // A valid Auth user without an established Traveller profile is not a Traveller.
        const { data: profile, error: profileError } = await caller.rpc(
          "get_my_traveller_identity_v1",
        );
        if (profileError) {
          throw new Error("Traveller identity lookup unavailable");
        }
        return profile !== null;
      },
      catalogue: async (
        regionId,
        localUnitId,
      ): Promise<CatalogueContext | null> => {
        const snapshot = await currentSnapshot(async () => {
          const { data: version, error: versionError } = await service
            .from("admin_catalogue_versions")
            .select("version,region_count,local_unit_count")
            .eq("dataset_id", "NG_ADMIN")
            .order("snapshot_date", { ascending: false })
            .order("version", { ascending: false })
            .limit(1).single();
          if (versionError || !version) {
            throw new Error("Catalogue version unavailable");
          }
          const [regionResult, unitResult] = await Promise.all([
            service.from("admin_regions")
              .select("id,canonical_name,region_type")
              .eq("dataset_id", "NG_ADMIN").eq("version", version.version),
            service.from("admin_local_units")
              .select("id,parent_region_id,canonical_name")
              .eq("dataset_id", "NG_ADMIN").eq("version", version.version),
          ]);
          if (
            regionResult.error || unitResult.error || !regionResult.data ||
            !unitResult.data ||
            regionResult.data.length !== version.region_count ||
            unitResult.data.length !== version.local_unit_count
          ) {
            throw new Error("Catalogue rows unavailable");
          }
          return {
            version: version.version,
            regions: regionResult.data as RegionRow[],
            units: unitResult.data as UnitRow[],
          };
        });
        const region = snapshot.regions.find((row) => row.id === regionId);
        const unit = snapshot.units.find((row) => row.id === localUnitId);
        if (!region || !unit || unit.parent_region_id !== region.id) {
          return null;
        }
        return {
          regionId: region.id,
          regionName: region.canonical_name,
          regionType: region.region_type,
          localUnitId: unit.id,
          localUnitName: unit.canonical_name,
          otherRegionNames: snapshot.regions.filter((row) =>
            row.id !== region.id
          )
            .map((row) => row.canonical_name),
          siblingLocalUnitNames: snapshot.units.filter((row) =>
            row.parent_region_id === region.id && row.id !== unit.id
          ).map((row) => row.canonical_name),
        };
      },
      verifyBoundary: async (
        regionId,
        localUnitId,
        latitude,
        longitude,
      ): Promise<BoundaryVerification> => {
        const { data, error } = await service.rpc(
          "classify_destination_coordinate_service_v1",
          {
            p_region_id: regionId,
            p_local_unit_id: localUnitId,
            p_latitude: latitude,
            p_longitude: longitude,
          },
        );
        if (error || !data || typeof data !== "object") {
          throw new Error("Boundary verification unavailable");
        }
        const outcome = data.outcome;
        if (
          ![
            "INSIDE",
            "OUTSIDE_SELECTED_UNIT",
            "BOUNDARY_AMBIGUOUS",
            "COVERAGE_UNRESOLVED",
            "INVALID_CONTEXT",
            "UNAVAILABLE",
          ].includes(outcome)
        ) throw new Error("Invalid boundary response");
        const version = data.boundary_dataset_version;
        if (version !== null && typeof version !== "string") {
          throw new Error("Invalid boundary version");
        }
        if (
          outcome !== "UNAVAILABLE" && outcome !== "INVALID_CONTEXT" &&
          (data.boundary_dataset_id !== "NG_OPERATIONAL_BOUNDARIES" ||
            !version)
        ) throw new Error("Invalid boundary dataset");
        return {
          outcome,
          boundaryDatasetVersion: version,
        } as BoundaryVerification;
      },
    });
  } catch {
    return new Response(
      JSON.stringify({ error: { code: "SEARCH_UNAVAILABLE" } }),
      {
        status: 503,
        headers: {
          "content-type": "application/json",
          "cache-control": "no-store",
        },
      },
    );
  }
});

function requiredEnvironment(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing server configuration: ${name}`);
  return value;
}

async function currentSnapshot(
  load: () => Promise<Snapshot>,
): Promise<Snapshot> {
  if (!cachedSnapshot || Date.now() >= cacheExpiresAt) {
    cachedSnapshot = load();
    cacheExpiresAt = Date.now() + 300_000;
  }
  try {
    return await cachedSnapshot;
  } catch (error) {
    cachedSnapshot = undefined;
    cacheExpiresAt = 0;
    throw error;
  }
}
