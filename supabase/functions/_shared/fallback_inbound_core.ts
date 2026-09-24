import { decryptFallbackMasterKey, hexDecode } from "./fallback_crypto.ts";
import type { KekRing } from "./fallback_crypto.ts";
import {
  authenticateAndDecryptJc1V1,
  bytesToHex,
  Jc1AuthenticationError,
  Jc1ParseError,
  parseJc1V1,
  sha256,
} from "./jc1_v1.ts";
import type { Jc1Body, Jc1EventType } from "./jc1_v1.ts";

export type VerifiedInboundTransport = {
  provider: string;
  providerEventId: string;
  rawSmsBody: string;
  providerArrivedAt?: string | null;
  serverReceivedAt: string;
};

export type FallbackKeyResolution = {
  bindingId: string;
  journeyId: string;
  ownerId: string;
  installationRowId: string;
  installationIdentifier: string;
  keyId: number;
  journeyHandleHex: string;
  bindingStatus: "ACTIVE" | "REVOKED";
  bindingVersion: number;
  keyLifecycleStatus: "ACTIVE" | "RETIRED" | "REVOKED";
  encryptedMasterKeyHex: string;
  encryptionIvHex: string;
  encryptionVersion: number;
};

export type InboundClassification =
  | "MALFORMED"
  | "KEY_OR_BINDING_NOT_FOUND"
  | "KEY_REVOKED"
  | "BINDING_REVOKED"
  | "KEK_UNAVAILABLE"
  | "AUTHENTICATION_FAILED"
  | "KEY_UNWRAP_FAILED"
  | "ENVELOPE_AUTHENTICATION_FAILED"
  | "AUTHENTICATED";

export type InboundReceiptClassification =
  | Exclude<InboundClassification, "AUTHENTICATED">
  | "AUTHENTICATED_NEW"
  | "AUTHENTICATED_DUPLICATE"
  | "AUTHENTICATED_ENVELOPE_CONFLICT";

export type RecordInboundResult = {
  transport: Omit<VerifiedInboundTransport, "rawSmsBody">;
  rawBodyLength: number;
  jc1DigestHex: string;
  classification: InboundClassification;
  header?: {
    eventType: Jc1EventType;
    keyId: number;
    journeyHandleHex: string;
    envelopeSequence: number;
  };
  resolution?: FallbackKeyResolution;
  body?: Jc1Body;
};

export type InboundCoreResult = {
  receiptId: string;
  classification: InboundReceiptClassification;
  duplicateProviderEvent: boolean;
  duplicateEnvelope: boolean;
  reconciliation: string | null;
  evidenceAdvanced: boolean;
};

export interface FallbackInboundRepository {
  findByProviderEvent(
    provider: string,
    providerEventId: string,
  ): Promise<InboundCoreResult | null>;
  resolveFallbackKey(
    keyId: number,
    journeyHandleHex: string,
  ): Promise<FallbackKeyResolution | null>;
  recordResult(result: RecordInboundResult): Promise<InboundCoreResult>;
}

export async function ingestVerifiedJc1Transport(
  input: VerifiedInboundTransport,
  repository: FallbackInboundRepository,
  kekRing: KekRing,
): Promise<InboundCoreResult> {
  validateTransport(input);
  const existing = await repository.findByProviderEvent(
    input.provider,
    input.providerEventId,
  );
  if (existing) return { ...existing, duplicateProviderEvent: true };

  const digestHex = bytesToHex(await sha256(input.rawSmsBody));
  const base = {
    transport: {
      provider: input.provider,
      providerEventId: input.providerEventId,
      providerArrivedAt: input.providerArrivedAt ?? null,
      serverReceivedAt: input.serverReceivedAt,
    },
    rawBodyLength: input.rawSmsBody.length,
    jc1DigestHex: digestHex,
  };

  let frame;
  try {
    frame = parseJc1V1(input.rawSmsBody);
  } catch (error) {
    if (!(error instanceof Jc1ParseError)) throw error;
    return repository.recordResult({ ...base, classification: "MALFORMED" });
  }

  const header = {
    eventType: frame.header.eventType,
    keyId: frame.header.keyId,
    journeyHandleHex: bytesToHex(frame.header.journeyHandle),
    envelopeSequence: frame.header.envelopeSequence,
  };
  const resolution = await repository.resolveFallbackKey(
    header.keyId,
    header.journeyHandleHex,
  );
  if (!resolution) {
    return repository.recordResult({
      ...base,
      header,
      classification: "KEY_OR_BINDING_NOT_FOUND",
    });
  }
  if (resolution.bindingStatus !== "ACTIVE") {
    return repository.recordResult({
      ...base,
      header,
      resolution,
      classification: "BINDING_REVOKED",
    });
  }
  if (resolution.keyLifecycleStatus === "REVOKED") {
    return repository.recordResult({
      ...base,
      header,
      resolution,
      classification: "KEY_REVOKED",
    });
  }

  const kek = kekRing.keys.get(resolution.encryptionVersion);
  if (!kek) {
    return repository.recordResult({
      ...base,
      header,
      resolution,
      classification: "KEK_UNAVAILABLE",
    });
  }

  let installationMasterKey: Uint8Array;
  try {
    installationMasterKey = await decryptFallbackMasterKey(
      {
        ciphertext: hexDecode(resolution.encryptedMasterKeyHex),
        iv: hexDecode(resolution.encryptionIvHex),
        encryptionVersion: resolution.encryptionVersion,
      },
      kek,
      resolution.ownerId,
      resolution.installationIdentifier,
      resolution.keyId,
    );
  } catch (error) {
    if (!(error instanceof DOMException)) throw error;
    return repository.recordResult({
      ...base,
      header,
      resolution,
      classification: "KEY_UNWRAP_FAILED",
    });
  }

  let body: Jc1Body;
  try {
    body = await authenticateAndDecryptJc1V1(
      frame,
      installationMasterKey,
    );
  } catch (error) {
    if (
      !(error instanceof Jc1AuthenticationError ||
        error instanceof Jc1ParseError || error instanceof DOMException)
    ) {
      throw error;
    }
    return repository.recordResult({
      ...base,
      header,
      resolution,
      classification: "ENVELOPE_AUTHENTICATION_FAILED",
    });
  } finally {
    installationMasterKey.fill(0);
  }
  return repository.recordResult({
    ...base,
    header,
    resolution,
    body,
    classification: "AUTHENTICATED",
  });
}

function validateTransport(input: VerifiedInboundTransport) {
  if (!/^[A-Za-z0-9_.-]{1,64}$/u.test(input.provider)) {
    throw new Error("Invalid provider identifier");
  }
  if (
    input.providerEventId.length < 1 || input.providerEventId.length > 200 ||
    new TextEncoder().encode(input.providerEventId).length > 200 ||
    /[\u0000-\u001f\u007f]/u.test(input.providerEventId)
  ) {
    throw new Error("Invalid provider event identifier");
  }
  const rawBodyBytes = new TextEncoder().encode(input.rawSmsBody).length;
  if (
    input.rawSmsBody.length < 1 || input.rawSmsBody.length > 160 ||
    rawBodyBytes > 160
  ) {
    throw new Error("Invalid SMS body length");
  }
  const receivedAt = Date.parse(input.serverReceivedAt);
  if (!Number.isFinite(receivedAt)) {
    throw new Error("Invalid server received timestamp");
  }
  if (input.providerArrivedAt != null) {
    const arrivedAt = Date.parse(input.providerArrivedAt);
    if (!Number.isFinite(arrivedAt) || arrivedAt > receivedAt + 5 * 60_000) {
      throw new Error("Invalid provider arrival timestamp");
    }
  }
}
