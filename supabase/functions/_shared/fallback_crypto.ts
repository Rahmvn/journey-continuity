export const MASTER_KEY_BYTES = 32;
export const GCM_IV_BYTES = 12;
export const GCM_TAG_BYTES = 16;

export type EncryptedFallbackKey = {
  ciphertext: Uint8Array;
  iv: Uint8Array;
  encryptionVersion: number;
};

export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export function base64UrlDecode(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/u.test(value)) throw new Error("Invalid Base64URL value");
  const padded = value.replaceAll("-", "+").replaceAll("_", "/") +
    "=".repeat((4 - value.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

export function hexDecode(value: string): Uint8Array {
  const hex = value.startsWith("\\x") ? value.slice(2) : value;
  if (hex.length % 2 !== 0 || !/^[0-9a-f]*$/iu.test(hex)) throw new Error("Invalid bytea value");
  return Uint8Array.from(hex.match(/.{2}/gu) ?? [], (pair) => Number.parseInt(pair, 16));
}

export function hexEncode(value: Uint8Array): string {
  return `\\x${Array.from(value, (byte) => byte.toString(16).padStart(2, "0")).join("")}`;
}

export function randomBytes(length: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(length));
}

export function randomUint32(): number {
  const bytes = randomBytes(4);
  return new DataView(bytes.buffer).getUint32(0, false);
}

function aad(ownerId: string, installationId: string, keyId: number, version: number): Uint8Array {
  return new TextEncoder().encode(
    `journey-continuity-fallback-key|v1|${ownerId}|${installationId}|${keyId}|${version}`,
  );
}

function buffer(bytes: Uint8Array): ArrayBuffer {
  return Uint8Array.from(bytes).buffer;
}

async function importKek(rawKek: Uint8Array, usage: KeyUsage[]): Promise<CryptoKey> {
  if (rawKek.length !== 32) throw new Error("Fallback KEK must be exactly 32 bytes");
  return crypto.subtle.importKey("raw", buffer(rawKek), { name: "AES-GCM" }, false, usage);
}

export async function encryptFallbackMasterKey(
  plaintext: Uint8Array,
  rawKek: Uint8Array,
  ownerId: string,
  installationId: string,
  keyId: number,
  encryptionVersion: number,
): Promise<EncryptedFallbackKey> {
  if (plaintext.length !== MASTER_KEY_BYTES) throw new Error("Fallback master key must be 32 bytes");
  const iv = randomBytes(GCM_IV_BYTES);
  const key = await importKek(rawKek, ["encrypt"]);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv: buffer(iv),
      additionalData: buffer(aad(ownerId, installationId, keyId, encryptionVersion)),
      tagLength: 128,
    },
    key,
    buffer(plaintext),
  ));
  return { ciphertext, iv, encryptionVersion };
}

export async function decryptFallbackMasterKey(
  encrypted: EncryptedFallbackKey,
  rawKek: Uint8Array,
  ownerId: string,
  installationId: string,
  keyId: number,
): Promise<Uint8Array> {
  if (encrypted.iv.length !== GCM_IV_BYTES || encrypted.ciphertext.length !== MASTER_KEY_BYTES + GCM_TAG_BYTES) {
    throw new Error("Invalid encrypted fallback key");
  }
  const key = await importKek(rawKek, ["decrypt"]);
  return new Uint8Array(await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: buffer(encrypted.iv),
      additionalData: buffer(aad(ownerId, installationId, keyId, encrypted.encryptionVersion)),
      tagLength: 128,
    },
    key,
    buffer(encrypted.ciphertext),
  ));
}

export type KekRing = { activeVersion: number; keys: Map<number, Uint8Array> };

export function parseKekRing(serialized: string, activeVersionText: string): KekRing {
  const activeVersion = Number.parseInt(activeVersionText, 10);
  if (!Number.isSafeInteger(activeVersion) || activeVersion <= 0) throw new Error("Invalid active KEK version");
  const parsed = JSON.parse(serialized) as Record<string, unknown>;
  const keys = new Map<number, Uint8Array>();
  for (const [versionText, encoded] of Object.entries(parsed)) {
    const version = Number.parseInt(versionText, 10);
    if (!Number.isSafeInteger(version) || version <= 0 || typeof encoded !== "string") {
      throw new Error("Invalid fallback KEK ring");
    }
    const key = base64UrlDecode(encoded);
    if (key.length !== 32) throw new Error("Fallback KEK must be exactly 32 bytes");
    keys.set(version, key);
  }
  if (!keys.has(activeVersion)) throw new Error("Active fallback KEK is unavailable");
  return { activeVersion, keys };
}
