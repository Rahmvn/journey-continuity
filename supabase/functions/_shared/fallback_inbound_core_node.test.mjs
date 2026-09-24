import test from "node:test";
import assert from "node:assert/strict";
import {
  base64UrlDecode,
  base64UrlEncode,
  encryptFallbackMasterKey,
  randomBytes,
} from "./fallback_crypto.ts";
import {
  authenticateAndDecryptJc1V1,
  bytesToHex,
  JC1_TEXT_LENGTH,
  parseJc1V1,
} from "./jc1_v1.ts";
import { ingestVerifiedJc1Transport } from "./fallback_inbound_core.ts";

const ownerId = "10000000-0000-4000-8000-000000000001";
const installationId = "11111111-1111-4111-8111-111111111111";
const journeyId = "20000000-0000-4000-8000-000000000001";
const bindingId = "30000000-0000-4000-8000-000000000001";
const keyId = 4_000_000_001;
const handle = Uint8Array.from({ length: 12 }, (_, index) => index + 1);
const masterKey = Uint8Array.from({ length: 32 }, (_, index) => 0xa0 + index);
const kek = Uint8Array.from({ length: 32 }, (_, index) => 0x20 + index);
const ring = { activeVersion: 1, keys: new Map([[1, kek]]) };

test("strict parser accepts the canonical 73-byte JC1 V1 frame", async () => {
  const sms = await makeJc1();
  assert.equal(sms.length, JC1_TEXT_LENGTH);
  const frame = parseJc1V1(sms);
  assert.equal(frame.header.keyId, keyId);
  assert.equal(frame.header.envelopeSequence, 7);
  assert.deepEqual(frame.header.journeyHandle, handle);
});

test("parser rejects malformed prefix, Base64URL, size, version, type, and header", async () => {
  const sms = await makeJc1();
  assert.throws(() => parseJc1V1(`JC2.${sms.slice(4)}`));
  assert.throws(() => parseJc1V1(`${sms.slice(0, -1)}=`));
  assert.throws(() => parseJc1V1(`JC1.${base64UrlEncode(new Uint8Array(72))}`));
  assert.throws(() =>
    parseJc1V1(mutateFrame(sms, (bytes) => {
      bytes[0] = 0x21;
    }))
  );
  assert.throws(() =>
    parseJc1V1(mutateFrame(sms, (bytes) => {
      bytes[0] = 0x1f;
    }))
  );
  assert.throws(() =>
    parseJc1V1(mutateFrame(sms, (bytes) => {
      bytes[17] =
        bytes[18] =
        bytes[19] =
        bytes[20] =
          0;
    }))
  );
});

test("parser rejects a noncanonical Base64URL spelling", async () => {
  const sms = await makeJc1();
  const alphabet =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  const payload = sms.slice(4);
  const last = alphabet.indexOf(payload.at(-1));
  const noncanonical = `${payload.slice(0, -1)}${
    alphabet[(last & 0x30) | ((last + 1) & 0x0f)]
  }`;
  assert.deepEqual(base64UrlDecode(noncanonical), base64UrlDecode(payload));
  assert.throws(() => parseJc1V1(`JC1.${noncanonical}`));
});

test("valid JC1 authenticates and decodes Android-compatible fields", async () => {
  const frame = parseJc1V1(await makeJc1());
  const body = await authenticateAndDecryptJc1V1(frame, masterKey);
  assert.deepEqual(body, {
    telemetrySequence: 42n,
    observationEventTimeUnixSeconds: 1_700_000_000,
    latitudeE7: 65_432_100,
    longitudeE7: 32_109_800,
    accuracyDecimeters: 125,
    batteryPercent: 67,
    charging: true,
    connectivity: "CELLULAR",
  });
});

test("altered authenticated header and ciphertext both fail authentication", async () => {
  const sms = await makeJc1();
  const alteredHeader = parseJc1V1(mutateFrame(sms, (bytes) => {
    bytes[5] ^= 1;
  }));
  const alteredCiphertext = parseJc1V1(mutateFrame(sms, (bytes) => {
    bytes[40] ^= 1;
  }));
  await assert.rejects(authenticateAndDecryptJc1V1(alteredHeader, masterKey));
  await assert.rejects(
    authenticateAndDecryptJc1V1(alteredCiphertext, masterKey),
  );
});

test("wrong key id and wrong Journey handle cannot resolve a binding", async () => {
  const repository = await FakeRepository.create();
  const wrongKey = mutateFrame(await makeJc1(), (bytes) => {
    bytes[4] ^= 1;
  });
  const wrongHandle = mutateFrame(await makeJc1(), (bytes) => {
    bytes[5] ^= 1;
  });
  assert.equal(
    (await ingest(wrongKey, "wrong-key", repository)).classification,
    "KEY_OR_BINDING_NOT_FOUND",
  );
  assert.equal(
    (await ingest(wrongHandle, "wrong-handle", repository)).classification,
    "KEY_OR_BINDING_NOT_FOUND",
  );
});

test("revoked key and revoked binding are rejected before decryption", async () => {
  const revokedKey = await FakeRepository.create({
    keyLifecycleStatus: "REVOKED",
  });
  const revokedBinding = await FakeRepository.create({
    bindingStatus: "REVOKED",
  });
  const sms = await makeJc1();
  assert.equal(
    (await ingest(sms, "revoked-key", revokedKey)).classification,
    "KEY_REVOKED",
  );
  assert.equal(
    (await ingest(sms, "revoked-binding", revokedBinding)).classification,
    "BINDING_REVOKED",
  );
});

test("retired key remains valid for its existing active binding", async () => {
  const repository = await FakeRepository.create({
    keyLifecycleStatus: "RETIRED",
  });
  assert.equal(
    (await ingest(await makeJc1(), "retired", repository)).classification,
    "AUTHENTICATED_NEW",
  );
});

test("same provider event is idempotent and does not authenticate twice", async () => {
  const repository = await FakeRepository.create();
  const sms = await makeJc1();
  const first = await ingest(sms, "same-event", repository);
  const second = await ingest(sms, "same-event", repository);
  assert.equal(first.classification, "AUTHENTICATED_NEW");
  assert.equal(second.classification, "AUTHENTICATED_NEW");
  assert.equal(second.duplicateProviderEvent, true);
  assert.equal(repository.recordCalls, 1);
});

test("same authenticated envelope via distinct provider events is a duplicate envelope", async () => {
  const repository = await FakeRepository.create();
  const sms = await makeJc1();
  const first = await ingest(sms, "event-a", repository);
  const second = await ingest(sms, "event-b", repository);
  assert.equal(first.duplicateEnvelope, false);
  assert.equal(second.classification, "AUTHENTICATED_DUPLICATE");
  assert.equal(second.duplicateEnvelope, true);
  assert.equal(repository.receipts.size, 2);
});

test("malformed and authentication failures persist classifications without raw bodies", async () => {
  const repository = await FakeRepository.create();
  const malformed = await ingest("X".repeat(102), "malformed", repository);
  const tampered = mutateFrame(await makeJc1(), (bytes) => {
    bytes[50] ^= 1;
  });
  const failed = await ingest(tampered, "tampered", repository);
  assert.equal(malformed.classification, "MALFORMED");
  assert.equal(failed.classification, "AUTHENTICATION_FAILED");
  assert.equal("rawSmsBody" in repository.recorded[0], false);
  assert.equal("rawSmsBody" in repository.recorded[1], false);
});

test("transport bounds reject control identifiers and multi-byte oversized bodies before persistence", async () => {
  const repository = await FakeRepository.create();
  await assert.rejects(ingestVerifiedJc1Transport(
    {
      provider: "verified-test-adapter",
      providerEventId: "bad\nevent",
      rawSmsBody: await makeJc1(),
      serverReceivedAt: "2026-09-24T12:00:02.000Z",
    },
    repository,
    ring,
  ));
  await assert.rejects(ingestVerifiedJc1Transport(
    {
      provider: "verified-test-adapter",
      providerEventId: "oversized-body",
      rawSmsBody: "é".repeat(100),
      serverReceivedAt: "2026-09-24T12:00:02.000Z",
    },
    repository,
    ring,
  ));
  await assert.rejects(ingestVerifiedJc1Transport(
    {
      provider: "verified-test-adapter",
      providerEventId: "é".repeat(101),
      rawSmsBody: await makeJc1(),
      serverReceivedAt: "2026-09-24T12:00:02.000Z",
    },
    repository,
    ring,
  ));
  assert.equal(repository.recordCalls, 0);
});

async function ingest(rawSmsBody, providerEventId, repository) {
  return ingestVerifiedJc1Transport(
    {
      provider: "verified-test-adapter",
      providerEventId,
      rawSmsBody,
      providerArrivedAt: "2026-09-24T12:00:01.000Z",
      serverReceivedAt: "2026-09-24T12:00:02.000Z",
    },
    repository,
    ring,
  );
}

class FakeRepository {
  static async create(overrides = {}) {
    const encrypted = await encryptFallbackMasterKey(
      masterKey,
      kek,
      ownerId,
      installationId,
      keyId,
      1,
    );
    return new FakeRepository({
      bindingId,
      journeyId,
      ownerId,
      installationId,
      keyId,
      journeyHandleHex: bytesToHex(handle),
      bindingStatus: "ACTIVE",
      bindingVersion: 1,
      keyLifecycleStatus: "ACTIVE",
      encryptedMasterKeyHex: bytesToHex(encrypted.ciphertext),
      encryptionIvHex: bytesToHex(encrypted.iv),
      encryptionVersion: 1,
      ...overrides,
    });
  }

  constructor(resolution) {
    this.resolution = resolution;
    this.receipts = new Map();
    this.envelopes = new Map();
    this.recorded = [];
    this.recordCalls = 0;
  }

  async findByProviderEvent(provider, providerEventId) {
    return this.receipts.get(`${provider}:${providerEventId}`) ?? null;
  }

  async resolveFallbackKey(requestedKeyId, requestedHandleHex) {
    return requestedKeyId === this.resolution.keyId &&
        requestedHandleHex === this.resolution.journeyHandleHex
      ? this.resolution
      : null;
  }

  async recordResult(result) {
    this.recordCalls += 1;
    this.recorded.push(result);
    const providerKey =
      `${result.transport.provider}:${result.transport.providerEventId}`;
    let classification = result.classification;
    let duplicateEnvelope = false;
    let reconciliation = null;
    if (classification === "AUTHENTICATED") {
      const envelopeKey =
        `${result.header.keyId}:${result.header.journeyHandleHex}:${result.header.envelopeSequence}`;
      duplicateEnvelope = this.envelopes.has(envelopeKey);
      classification = duplicateEnvelope
        ? "AUTHENTICATED_DUPLICATE"
        : "AUTHENTICATED_NEW";
      reconciliation = duplicateEnvelope
        ? "DUPLICATE_ENVELOPE"
        : "SMS_CREATED_CANONICAL";
      this.envelopes.set(envelopeKey, result.jc1DigestHex);
    }
    const response = {
      receiptId: `receipt-${this.receipts.size + 1}`,
      classification,
      duplicateProviderEvent: false,
      duplicateEnvelope,
      reconciliation,
      evidenceAdvanced: classification === "AUTHENTICATED_NEW",
    };
    this.receipts.set(providerKey, response);
    return response;
  }
}

async function makeJc1({ eventType = 1, envelopeSequence = 7 } = {}) {
  const header = new Uint8Array(33);
  const headerView = new DataView(header.buffer);
  headerView.setUint8(0, 0x10 | eventType);
  headerView.setUint32(1, keyId, false);
  header.set(handle, 5);
  headerView.setUint32(17, envelopeSequence, false);
  header.set(Uint8Array.from({ length: 12 }, (_, index) => 0x70 + index), 21);

  const plaintext = new Uint8Array(24);
  const body = new DataView(plaintext.buffer);
  body.setBigInt64(0, 42n, false);
  body.setUint32(8, 1_700_000_000, false);
  body.setInt32(12, 65_432_100, false);
  body.setInt32(16, 32_109_800, false);
  body.setUint16(20, 125, false);
  body.setUint8(22, 67);
  body.setUint8(23, 0x11);

  const key = await deriveJourneyKey(masterKey, handle);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    key,
    { name: "AES-GCM" },
    false,
    ["encrypt"],
  );
  const encrypted = new Uint8Array(
    await crypto.subtle.encrypt(
      {
        name: "AES-GCM",
        iv: header.slice(21, 33),
        additionalData: header,
        tagLength: 128,
      },
      cryptoKey,
      plaintext,
    ),
  );
  const frame = new Uint8Array(73);
  frame.set(header);
  frame.set(encrypted, 33);
  key.fill(0);
  plaintext.fill(0);
  return `JC1.${base64UrlEncode(frame)}`;
}

async function deriveJourneyKey(keyMaterial, journeyHandle) {
  const salt = new TextEncoder().encode(
    "JourneyContinuity/JC1/HKDF-SHA-256/v1",
  );
  const info = new Uint8Array(
    new TextEncoder().encode("journey-envelope-key").length + 13,
  );
  info.set(new TextEncoder().encode("journey-envelope-key"));
  info.set(journeyHandle, info.length - 13);
  info[info.length - 1] = 1;
  const saltKey = await crypto.subtle.importKey(
    "raw",
    salt,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const extracted = new Uint8Array(
    await crypto.subtle.sign("HMAC", saltKey, keyMaterial),
  );
  const extractedKey = await crypto.subtle.importKey(
    "raw",
    extracted,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const output = new Uint8Array(
    await crypto.subtle.sign("HMAC", extractedKey, info),
  ).slice(0, 32);
  extracted.fill(0);
  return output;
}

function mutateFrame(sms, mutate) {
  const bytes = base64UrlDecode(sms.slice(4));
  mutate(bytes);
  return `JC1.${base64UrlEncode(bytes)}`;
}
