import { createClient } from "npm:@supabase/supabase-js@2.116.0";
import {
  base64UrlEncode,
  decryptFallbackMasterKey,
  encryptFallbackMasterKey,
  hexDecode,
  hexEncode,
  MASTER_KEY_BYTES,
  parseKekRing,
  randomBytes,
  randomUint32,
} from "../_shared/fallback_crypto.ts";

type ProvisionRequest = { installation_id?: unknown; journey_id?: unknown };
type BackendRow = {
  key_id: number;
  encrypted_master_key: string;
  encryption_iv: string;
  encryption_version: number;
  journey_handle: string;
  binding_status: string;
  binding_version: number;
  key_lifecycle_status: string;
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const JSON_HEADERS = { "content-type": "application/json", "cache-control": "no-store" };

function response(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return response(405, { error: "Method not allowed" });
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ")) return response(401, { error: "Authentication required" });

  let body: ProvisionRequest;
  try {
    body = await request.json();
  } catch {
    return response(400, { error: "Malformed JSON request" });
  }
  if (typeof body.installation_id !== "string" || !UUID.test(body.installation_id) ||
      typeof body.journey_id !== "string" || !UUID.test(body.journey_id)) {
    return response(400, { error: "Valid installation_id and journey_id are required" });
  }

  try {
    const url = Deno.env.get("SUPABASE_URL");
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !anonKey || !serviceKey) throw new Error("Supabase function environment is incomplete");
    const callerClient = createClient(url, anonKey, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: userData, error: userError } = await callerClient.auth.getUser();
    if (userError || !userData.user) return response(401, { error: "Authentication required" });

    const kekRing = parseKekRing(
      Deno.env.get("FALLBACK_KEY_KEKS_JSON") ?? "",
      Deno.env.get("FALLBACK_ACTIVE_KEK_VERSION") ?? "",
    );
    const serviceClient = createClient(url, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    let row: BackendRow | undefined;
    for (let collisionAttempt = 0; collisionAttempt < 5 && !row; collisionAttempt += 1) {
      const keyId = randomUint32();
      const candidateMasterKey = randomBytes(MASTER_KEY_BYTES);
      try {
        const encrypted = await encryptFallbackMasterKey(
          candidateMasterKey,
          kekRing.keys.get(kekRing.activeVersion)!,
          userData.user.id,
          body.installation_id,
          keyId,
          kekRing.activeVersion,
        );
        const { data, error } = await serviceClient.rpc("provision_fallback_material_backend", {
          p_owner_id: userData.user.id,
          p_installation_identifier: body.installation_id,
          p_journey_id: body.journey_id,
          p_candidate_key_id: keyId,
          p_candidate_encrypted_master_key: hexEncode(encrypted.ciphertext),
          p_candidate_encryption_iv: hexEncode(encrypted.iv),
          p_encryption_version: encrypted.encryptionVersion,
          p_candidate_journey_handle: hexEncode(randomBytes(12)),
        });
        if (error) {
          if (error.code === "23505") continue;
          const status = error.code === "42501" ? 403 : error.code === "22023" ? 409 : 500;
          return response(status, { error: status === 500 ? "Provisioning failed" : error.message });
        }
        row = (data as BackendRow[] | null)?.[0];
      } finally {
        candidateMasterKey.fill(0);
      }
    }
    if (!row) return response(503, { error: "Provisioning collision retry exhausted" });
    if (row.key_lifecycle_status === "REVOKED") return response(403, { error: "Fallback key is revoked" });

    const kek = kekRing.keys.get(row.encryption_version);
    if (!kek) throw new Error("Required fallback KEK version is unavailable");
    const plaintext = await decryptFallbackMasterKey(
      {
        ciphertext: hexDecode(row.encrypted_master_key),
        iv: hexDecode(row.encryption_iv),
        encryptionVersion: row.encryption_version,
      },
      kek,
      userData.user.id,
      body.installation_id,
      row.key_id,
    );
    try {
      return response(200, {
        key_id: row.key_id,
        installation_master_key: base64UrlEncode(plaintext),
        journey_handle: base64UrlEncode(hexDecode(row.journey_handle)),
        binding_status: row.binding_status,
        binding_version: row.binding_version,
      });
    } finally {
      plaintext.fill(0);
    }
  } catch {
    return response(500, { error: "Provisioning is unavailable" });
  }
});
