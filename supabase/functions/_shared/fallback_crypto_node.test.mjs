import test from "node:test";
import assert from "node:assert/strict";
import {
  base64UrlDecode,
  base64UrlEncode,
  decryptFallbackMasterKey,
  encryptFallbackMasterKey,
  parseKekRing,
  randomBytes,
} from "./fallback_crypto.ts";

const owner = "10000000-0000-4000-8000-000000000001";
const installation = "11111111-1111-4111-8111-111111111111";

test("server AES-GCM round trip", async () => {
  const kek = randomBytes(32);
  const plaintext = randomBytes(32);
  const encrypted = await encryptFallbackMasterKey(plaintext, kek, owner, installation, 42, 1);
  assert.deepEqual(await decryptFallbackMasterKey(encrypted, kek, owner, installation, 42), plaintext);
});

test("wrong KEK and changed authenticated metadata fail", async () => {
  const kek = randomBytes(32);
  const encrypted = await encryptFallbackMasterKey(randomBytes(32), kek, owner, installation, 42, 1);
  await assert.rejects(decryptFallbackMasterKey(encrypted, randomBytes(32), owner, installation, 42));
  await assert.rejects(decryptFallbackMasterKey(encrypted, kek, owner, installation, 43));
});

test("ciphertext IV and tag tampering fail", async () => {
  const kek = randomBytes(32);
  const encrypted = await encryptFallbackMasterKey(randomBytes(32), kek, owner, installation, 42, 1);
  for (const target of ["ciphertext", "iv", "tag"]) {
    const ciphertext = encrypted.ciphertext.slice();
    const iv = encrypted.iv.slice();
    if (target === "iv") iv[0] ^= 1;
    else if (target === "tag") ciphertext[ciphertext.length - 1] ^= 1;
    else ciphertext[0] ^= 1;
    await assert.rejects(decryptFallbackMasterKey({ ...encrypted, ciphertext, iv }, kek, owner, installation, 42));
  }
});

test("separate encryption uses a different nonce", async () => {
  const kek = randomBytes(32);
  const plaintext = randomBytes(32);
  const first = await encryptFallbackMasterKey(plaintext, kek, owner, installation, 42, 1);
  const second = await encryptFallbackMasterKey(plaintext, kek, owner, installation, 42, 1);
  assert.notEqual(base64UrlEncode(first.iv), base64UrlEncode(second.iv));
});

test("KEK ring is versioned and requires 32-byte keys", () => {
  const encoded = base64UrlEncode(randomBytes(32));
  const ring = parseKekRing(JSON.stringify({ 1: encoded }), "1");
  assert.deepEqual(ring.keys.get(1), base64UrlDecode(encoded));
  assert.throws(() => parseKekRing(JSON.stringify({ 1: encoded }), "2"));
});
