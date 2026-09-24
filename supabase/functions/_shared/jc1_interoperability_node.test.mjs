import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  authenticateAndDecryptJc1V1,
  deriveJourneyKey,
  parseJc1V1,
} from "./jc1_v1.ts";
import {
  decryptFallbackMasterKey,
  encryptFallbackMasterKey,
  randomBytes,
} from "./fallback_crypto.ts";

// This is the very same resource loaded by the Kotlin JVM test.
const text = readFileSync(
  new URL(
    "../../../app/src/test/resources/jc1-v1-interoperability.properties",
    import.meta.url,
  ),
  "utf8",
);
const fixture = Object.fromEntries(
  text.split(/\r?\n/u)
    .filter((line) => line && !line.startsWith("#"))
    .map((
      line,
    ) => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]),
);
const bytes = (name) => Buffer.from(fixture[name], "hex");
const number = (name) => Number(fixture[name]);

test("shared vector: production HKDF exactly matches the expected Journey key", async () => {
  const key = await deriveJourneyKey(
    bytes("masterKeyHex"),
    bytes("journeyHandleHex"),
  );
  assert.deepEqual(Buffer.from(key), bytes("derivedKeyHex"));
});

test("shared vector: server parses and decrypts the exact Android-compatible frame", async () => {
  const frame = parseJc1V1(fixture.jc1);
  assert.equal(fixture.jc1.length, 102);
  assert.equal(frame.frameBytes.length, 73);
  assert.deepEqual(Buffer.from(frame.frameBytes), bytes("frameHex"));
  assert.deepEqual(Buffer.from(frame.authenticatedHeader), bytes("headerHex"));
  assert.equal(
    Buffer.from(
      await crypto.subtle.digest("SHA-256", frame.authenticatedHeader),
    ).toString("hex"),
    fixture.headerSha256,
  );
  assert.equal(frame.header.keyId, number("keyId"));
  assert.equal(frame.header.envelopeSequence, number("envelopeSequence"));
  assert.equal(frame.header.eventType, fixture.eventType);
  assert.deepEqual(
    Buffer.from(frame.header.journeyHandle),
    bytes("journeyHandleHex"),
  );
  assert.deepEqual(Buffer.from(frame.header.nonce), bytes("nonceHex"));
  assert.equal(frame.ciphertext.length, 24);
  assert.equal(frame.authenticationTag.length, 16);
  assert.deepEqual(
    await authenticateAndDecryptJc1V1(frame, bytes("masterKeyHex")),
    {
      telemetrySequence: BigInt(fixture.telemetrySequence),
      observationEventTimeUnixSeconds: number("eventTimeUnixSeconds"),
      latitudeE7: number("latitudeE7"),
      longitudeE7: number("longitudeE7"),
      accuracyDecimeters: number("accuracyDecimeters"),
      batteryPercent: number("batteryPercent"),
      charging: fixture.charging === "true",
      connectivity: fixture.connectivity,
    },
  );
});

test("shared vector: independent WebCrypto encoding produces the expected frame bytes", async () => {
  const key = await deriveJourneyKey(
    bytes("masterKeyHex"),
    bytes("journeyHandleHex"),
  );
  const imported = await crypto.subtle.importKey("raw", key, "AES-GCM", false, [
    "encrypt",
  ]);
  const encrypted = await crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv: bytes("nonceHex"),
      additionalData: bytes("headerHex"),
      tagLength: 128,
    },
    imported,
    bytes("plaintextHex"),
  );
  const frame = Buffer.concat([bytes("headerHex"), Buffer.from(encrypted)]);
  assert.deepEqual(frame, bytes("frameHex"));
  assert.equal("JC1." + frame.toString("base64url"), fixture.jc1);
  key.fill(0);
});

test("shared vector: header, nonce, ciphertext and tag tampering fail authentication", async () => {
  for (const offset of [17, 21, 33, 72]) {
    const changed = bytes("frameHex");
    changed[offset] ^= 1;
    await assert.rejects(authenticateAndDecryptJc1V1(
      parseJc1V1("JC1." + changed.toString("base64url")),
      bytes("masterKeyHex"),
    ));
  }
});

test("shared metadata: wrap uses provisioning identifier, never the database row UUID", async () => {
  assert.notEqual(fixture.installationRowId, fixture.installationIdentifier);
  const kek = randomBytes(32);
  try {
    const encrypted = await encryptFallbackMasterKey(
      bytes("masterKeyHex"),
      kek,
      fixture.ownerId,
      fixture.installationIdentifier,
      number("keyId"),
      1,
    );
    const unwrapped = await decryptFallbackMasterKey(
      encrypted,
      kek,
      fixture.ownerId,
      fixture.installationIdentifier,
      number("keyId"),
    );
    assert.deepEqual(Buffer.from(unwrapped), bytes("masterKeyHex"));
    unwrapped.fill(0);
    await assert.rejects(decryptFallbackMasterKey(
      encrypted,
      kek,
      fixture.ownerId,
      fixture.installationRowId,
      number("keyId"),
    ));
  } finally {
    kek.fill(0);
  }
});
