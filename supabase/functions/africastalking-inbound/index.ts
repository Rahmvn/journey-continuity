import { createClient } from "npm:@supabase/supabase-js@2.116.0";
import { parseKekRing } from "../_shared/fallback_crypto.ts";
import { ingestVerifiedJc1Transport } from "../_shared/fallback_inbound_core.ts";
import { SupabaseFallbackInboundRepository } from "../_shared/fallback_inbound_repository.ts";
import { handleAfricaTalkingInbound } from "./adapter.ts";

Deno.serve(async (request) => {
  try {
    const supabaseUrl = requiredEnvironment("SUPABASE_URL");
    const serviceRoleKey = requiredEnvironment("SUPABASE_SERVICE_ROLE_KEY");
    const callbackSecret = requiredEnvironment(
      "AFRICASTALKING_SANDBOX_CALLBACK_SECRET",
    );
    const sandboxShortcode = requiredEnvironment(
      "AFRICASTALKING_SANDBOX_SHORTCODE",
    );
    const kekRing = parseKekRing(
      requiredEnvironment("FALLBACK_KEY_KEKS_JSON"),
      requiredEnvironment("FALLBACK_ACTIVE_KEK_VERSION"),
    );
    const serviceClient = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const repository = new SupabaseFallbackInboundRepository({
      rpc: async (name, parameters) => {
        const { data, error } = await serviceClient.rpc(name, parameters);
        return { data, error };
      },
    });
    return await handleAfricaTalkingInbound(request, {
      callbackSecret,
      sandboxShortcode,
      now: () => new Date(),
      ingest: (input) => ingestVerifiedJc1Transport(input, repository, kekRing),
    });
  } catch {
    return new Response(JSON.stringify({ result: "temporarily_unavailable" }), {
      status: 503,
      headers: {
        "content-type": "application/json",
        "cache-control": "no-store",
      },
    });
  }
});

function requiredEnvironment(name: string): string {
  const value = Deno.env.get(name);
  if (!value) {
    throw new Error(`Missing required function configuration: ${name}`);
  }
  return value;
}
