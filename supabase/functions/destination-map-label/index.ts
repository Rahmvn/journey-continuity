import { createClient } from "npm:@supabase/supabase-js@2.116.0";
import { handleDestinationMapLabel, type MapLabelContext } from "./core.ts";
import type { BoundaryOutcome } from "../destination-search/core.ts";

Deno.serve(async (request) => {
  try {
    const url = requiredEnvironment("SUPABASE_URL");
    const anonKey = requiredEnvironment("SUPABASE_ANON_KEY");
    const serviceKey = requiredEnvironment("SUPABASE_SERVICE_ROLE_KEY");
    // Missing provider configuration must not prevent independent verification.
    const apiKey = Deno.env.get("GEOAPIFY_API_KEY") ?? "";
    const caller = createClient(url, anonKey, {
      global: {
        headers: { Authorization: request.headers.get("authorization") ?? "" },
      },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const service = createClient(url, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    return await handleDestinationMapLabel(request, {
      apiKey,
      fetchProvider: fetch,
      authenticate: async () => {
        const { data, error } = await caller.auth.getUser();
        if (
          error || !data.user || data.user.is_anonymous !== false ||
          !data.user.email_confirmed_at || !data.user.email?.trim()
        ) return false;
        const { data: profile, error: profileError } = await caller.rpc(
          "get_my_traveller_identity_v1",
        );
        if (profileError) throw new Error("Traveller identity unavailable");
        return profile !== null;
      },
      catalogue: async (
        regionId,
        localUnitId,
      ): Promise<MapLabelContext | null> => {
        // Catalogue reads retain the existing server-only grant. No client grants change.
        const { data: snapshot, error: snapshotError } = await service
          .from("admin_catalogue_versions").select("version")
          .eq("dataset_id", "NG_ADMIN")
          .order("snapshot_date", { ascending: false }).order("version", {
            ascending: false,
          })
          .limit(1).abortSignal(request.signal).single();
        if (snapshotError || !snapshot) {
          throw new Error("Catalogue unavailable");
        }
        const [region, unit] = await Promise.all([
          service.from("admin_regions").select("id,canonical_name")
            .eq("dataset_id", "NG_ADMIN").eq("version", snapshot.version)
            .eq("id", regionId).abortSignal(request.signal).maybeSingle(),
          service.from("admin_local_units").select(
            "id,parent_region_id,canonical_name",
          )
            .eq("dataset_id", "NG_ADMIN").eq("version", snapshot.version)
            .eq("id", localUnitId).abortSignal(request.signal).maybeSingle(),
        ]);
        if (region.error || unit.error) {
          throw new Error("Catalogue unavailable");
        }
        if (
          !region.data || !unit.data ||
          unit.data.parent_region_id !== region.data.id
        ) return null;
        return {
          regionId: region.data.id,
          regionName: region.data.canonical_name,
          localUnitId: unit.data.id,
          localUnitName: unit.data.canonical_name,
        };
      },
      verifyBoundary: async (input) => {
        // Reuse the unchanged authenticated RPC, with the caller's JWT. Its
        // own verified Traveller check and current boundary snapshot are authoritative.
        const { data, error } = await caller.rpc(
          "verify_destination_coordinate_v1",
          {
            p_region_id: input.regionId,
            p_local_unit_id: input.localUnitId,
            p_latitude: input.latitude,
            p_longitude: input.longitude,
          },
        ).abortSignal(request.signal);
        if (
          error || !data || typeof data !== "object" ||
          ![
            "INSIDE",
            "OUTSIDE_SELECTED_UNIT",
            "BOUNDARY_AMBIGUOUS",
            "COVERAGE_UNRESOLVED",
            "INVALID_CONTEXT",
            "UNAVAILABLE",
          ].includes(data.outcome)
        ) throw new Error("Verification unavailable");
        const outcome = data.outcome as BoundaryOutcome;
        const version = data.boundary_dataset_version;
        if (outcome === "UNAVAILABLE" || outcome === "INVALID_CONTEXT") {
          if (version !== null || data.boundary_dataset_id !== null) {
            throw new Error("Invalid verification");
          }
        } else if (
          data.boundary_dataset_id !== "NG_OPERATIONAL_BOUNDARIES" ||
          typeof version !== "string" || !version.trim()
        ) throw new Error("Invalid verification");
        return { outcome, boundaryDatasetVersion: version };
      },
    });
  } catch {
    // Do not log exceptions: URLs, tokens and coordinates can be embedded upstream.
    return new Response(
      JSON.stringify({
        error: {
          code: request.signal.aborted
            ? "REQUEST_CANCELLED"
            : "LABEL_UNAVAILABLE",
        },
      }),
      {
        status: request.signal.aborted ? 499 : 503,
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
  if (!value) throw new Error("Missing server configuration");
  return value;
}
