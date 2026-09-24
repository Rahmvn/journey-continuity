import test from "node:test";
import assert from "node:assert/strict";
import { SupabaseFallbackInboundRepository } from "./fallback_inbound_repository.ts";

test("resolver maps both explicit identifiers and never falls back to the row UUID", async () => {
  const row = {
    binding_id: "binding",
    journey_id: "journey",
    owner_id: "owner",
    installation_row_id: "internal-row",
    installation_identifier: "client-identifier",
    key_id: 7,
    journey_handle: "\\xa1",
    binding_status: "ACTIVE",
    binding_version: 1,
    key_lifecycle_status: "ACTIVE",
    encrypted_master_key: "\\xb1",
    encryption_iv: "\\xc1",
    encryption_version: 1,
  };
  const repository = new SupabaseFallbackInboundRepository({
    rpc: async (name) => {
      assert.equal(name, "resolve_fallback_ingestion_material_backend");
      return { data: [row], error: null };
    },
  });
  const result = await repository.resolveFallbackKey(7, "a1");
  assert.equal(result.installationRowId, "internal-row");
  assert.equal(result.installationIdentifier, "client-identifier");
  delete row.installation_identifier;
  await assert.rejects(repository.resolveFallbackKey(7, "a1"));
});
