import type {
  FallbackInboundRepository,
  FallbackKeyResolution,
  InboundCoreResult,
  RecordInboundResult,
} from "./fallback_inbound_core.ts";

export interface BackendRpcClient {
  rpc(name: string, parameters: Record<string, unknown>): Promise<{
    data: unknown;
    error: { message?: string } | null;
  }>;
}

export class SupabaseFallbackInboundRepository
  implements FallbackInboundRepository {
  constructor(private readonly client: BackendRpcClient) {}

  async findByProviderEvent(
    provider: string,
    providerEventId: string,
  ): Promise<InboundCoreResult | null> {
    const rows = await this.rpcRows("get_fallback_inbound_receipt_backend", {
      p_provider: provider,
      p_provider_event_id: providerEventId,
    });
    return rows.length === 0 ? null : decodeCoreResult(rows[0]);
  }

  async resolveFallbackKey(
    keyId: number,
    journeyHandleHex: string,
  ): Promise<FallbackKeyResolution | null> {
    const rows = await this.rpcRows(
      "resolve_fallback_ingestion_material_backend",
      {
        p_key_id: keyId,
        p_journey_handle: `\\x${journeyHandleHex}`,
      },
    );
    if (rows.length === 0) return null;
    const row = rows[0];
    return {
      bindingId: string(row.binding_id),
      journeyId: string(row.journey_id),
      ownerId: string(row.owner_id),
      installationId: string(row.installation_id),
      keyId: number(row.key_id),
      journeyHandleHex: byteaHex(row.journey_handle),
      bindingStatus: string(
        row.binding_status,
      ) as FallbackKeyResolution["bindingStatus"],
      bindingVersion: number(row.binding_version),
      keyLifecycleStatus: string(
        row.key_lifecycle_status,
      ) as FallbackKeyResolution["keyLifecycleStatus"],
      encryptedMasterKeyHex: byteaHex(row.encrypted_master_key),
      encryptionIvHex: byteaHex(row.encryption_iv),
      encryptionVersion: number(row.encryption_version),
    };
  }

  async recordResult(result: RecordInboundResult): Promise<InboundCoreResult> {
    const rows = await this.rpcRows("record_fallback_inbound_result_backend", {
      p_provider: result.transport.provider,
      p_provider_event_id: result.transport.providerEventId,
      p_provider_arrived_at: result.transport.providerArrivedAt ?? null,
      p_received_at: result.transport.serverReceivedAt,
      p_raw_body_length: result.rawBodyLength,
      p_jc1_digest: `\\x${result.jc1DigestHex}`,
      p_classification: result.classification,
      p_event_type: result.header?.eventType ?? null,
      p_key_id: result.header?.keyId ?? null,
      p_journey_handle: result.header
        ? `\\x${result.header.journeyHandleHex}`
        : null,
      p_envelope_sequence: result.header?.envelopeSequence ?? null,
      p_telemetry_sequence: result.body?.telemetrySequence.toString() ?? null,
      p_observation_event_time: result.body
        ? new Date(result.body.observationEventTimeUnixSeconds * 1000)
          .toISOString()
        : null,
      p_latitude_e7: result.body?.latitudeE7 ?? null,
      p_longitude_e7: result.body?.longitudeE7 ?? null,
      p_accuracy_decimeters: result.body?.accuracyDecimeters ?? null,
      p_battery_percent: result.body?.batteryPercent ?? null,
      p_charging: result.body?.charging ?? null,
      p_connectivity_state: result.body?.connectivity ?? null,
    });
    if (rows.length !== 1) {
      throw new Error("Inbound fallback result RPC returned no result");
    }
    return decodeCoreResult(rows[0]);
  }

  private async rpcRows(
    name: string,
    parameters: Record<string, unknown>,
  ): Promise<Record<string, unknown>[]> {
    const { data, error } = await this.client.rpc(name, parameters);
    if (error) {
      throw new Error(
        `Fallback inbound backend RPC failed: ${
          error.message ?? "unknown error"
        }`,
      );
    }
    if (!Array.isArray(data)) {
      throw new Error("Fallback inbound backend RPC returned an invalid shape");
    }
    return data as Record<string, unknown>[];
  }
}

function decodeCoreResult(row: Record<string, unknown>): InboundCoreResult {
  return {
    receiptId: string(row.receipt_id),
    classification: string(
      row.classification,
    ) as InboundCoreResult["classification"],
    duplicateProviderEvent: boolean(row.duplicate_provider_event),
    duplicateEnvelope: boolean(row.duplicate_envelope),
    reconciliation: row.reconciliation == null
      ? null
      : string(row.reconciliation),
    evidenceAdvanced: boolean(row.evidence_advanced),
  };
}

function string(value: unknown): string {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error("Invalid inbound backend text value");
  }
  return value;
}

function number(value: unknown): number {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isSafeInteger(parsed)) {
    throw new Error("Invalid inbound backend number value");
  }
  return parsed;
}

function boolean(value: unknown): boolean {
  if (typeof value !== "boolean") {
    throw new Error("Invalid inbound backend boolean value");
  }
  return value;
}

function byteaHex(value: unknown): string {
  const encoded = string(value);
  if (!/^\\x[0-9a-f]+$/iu.test(encoded)) {
    throw new Error("Invalid inbound backend bytea value");
  }
  return encoded.slice(2).toLowerCase();
}
