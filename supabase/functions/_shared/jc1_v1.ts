import { base64UrlDecode, base64UrlEncode } from "./fallback_crypto.ts";

export const JC1_PREFIX = "JC1.";
export const JC1_FRAME_BYTES = 73;
export const JC1_TEXT_LENGTH = 102;
export const JC1_HEADER_BYTES = 33;
export const JC1_BODY_BYTES = 24;
export const JC1_HANDLE_BYTES = 12;
export const JC1_NONCE_BYTES = 12;
export const JC1_TAG_BYTES = 16;
export const JC1_KEY_BYTES = 32;

const VERSION = 1;
const RESERVED_FLAG_MASK = 0xe0;
const UNKNOWN_BATTERY = 0xff;
const HKDF_SALT = new TextEncoder().encode(
  "JourneyContinuity/JC1/HKDF-SHA-256/v1",
);
const HKDF_INFO = new TextEncoder().encode("journey-envelope-key");

export type Jc1EventType = "OBSERVATION" | "JOURNEY_COMPLETED";
export type Jc1Connectivity =
  | "NONE"
  | "CELLULAR"
  | "WIFI"
  | "OTHER"
  | "UNKNOWN";

export type Jc1Header = {
  eventType: Jc1EventType;
  keyId: number;
  journeyHandle: Uint8Array;
  envelopeSequence: number;
  nonce: Uint8Array;
};

export type Jc1ProtectedFrame = {
  header: Jc1Header;
  authenticatedHeader: Uint8Array;
  ciphertext: Uint8Array;
  authenticationTag: Uint8Array;
  frameBytes: Uint8Array;
};

export type Jc1Body = {
  telemetrySequence: bigint;
  observationEventTimeUnixSeconds: number;
  latitudeE7: number;
  longitudeE7: number;
  accuracyDecimeters: number;
  batteryPercent: number | null;
  charging: boolean | null;
  connectivity: Jc1Connectivity;
};

export class Jc1ParseError extends Error {}
export class Jc1AuthenticationError extends Error {}

export function parseJc1V1(rawBody: string): Jc1ProtectedFrame {
  if (rawBody.length !== JC1_TEXT_LENGTH || !rawBody.startsWith(JC1_PREFIX)) {
    throw new Jc1ParseError("Invalid JC1 envelope shape");
  }
  const payload = rawBody.slice(JC1_PREFIX.length);
  if (!/^[A-Za-z0-9_-]+$/u.test(payload) || payload.includes("=")) {
    throw new Jc1ParseError("JC1 payload must be unpadded Base64URL");
  }

  let bytes: Uint8Array;
  try {
    bytes = base64UrlDecode(payload);
  } catch {
    throw new Jc1ParseError("JC1 payload is not valid Base64URL");
  }
  if (bytes.length !== JC1_FRAME_BYTES || base64UrlEncode(bytes) !== payload) {
    throw new Jc1ParseError(
      "JC1 payload is noncanonical or has the wrong size",
    );
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const versionAndType = view.getUint8(0);
  if (versionAndType >>> 4 !== VERSION) {
    throw new Jc1ParseError("Unsupported JC1 version");
  }
  const eventType = decodeEventType(versionAndType & 0x0f);
  const keyId = view.getUint32(1, false);
  const journeyHandle = bytes.slice(5, 17);
  const envelopeSequence = view.getUint32(17, false);
  if (envelopeSequence === 0) {
    throw new Jc1ParseError("JC1 envelope sequence must be positive");
  }
  const nonce = bytes.slice(21, 33);
  const ciphertext = bytes.slice(33, 57);
  const authenticationTag = bytes.slice(57, 73);

  return {
    header: { eventType, keyId, journeyHandle, envelopeSequence, nonce },
    authenticatedHeader: bytes.slice(0, JC1_HEADER_BYTES),
    ciphertext,
    authenticationTag,
    frameBytes: bytes.slice(),
  };
}

export async function authenticateAndDecryptJc1V1(
  frame: Jc1ProtectedFrame,
  installationMasterKey: Uint8Array,
): Promise<Jc1Body> {
  if (installationMasterKey.length !== JC1_KEY_BYTES) {
    throw new Jc1AuthenticationError("Invalid installation fallback key");
  }
  const journeyKey = await deriveJourneyKey(
    installationMasterKey,
    frame.header.journeyHandle,
  );
  try {
    const cryptoKey = await crypto.subtle.importKey(
      "raw",
      arrayBuffer(journeyKey),
      { name: "AES-GCM" },
      false,
      ["decrypt"],
    );
    const protectedBody = concat(frame.ciphertext, frame.authenticationTag);
    const plaintext = new Uint8Array(
      await crypto.subtle.decrypt(
        {
          name: "AES-GCM",
          iv: arrayBuffer(frame.header.nonce),
          additionalData: arrayBuffer(frame.authenticatedHeader),
          tagLength: 128,
        },
        cryptoKey,
        arrayBuffer(protectedBody),
      ),
    );
    try {
      return decodeBody(plaintext);
    } finally {
      plaintext.fill(0);
      protectedBody.fill(0);
    }
  } catch (error) {
    if (error instanceof Jc1ParseError) throw error;
    throw new Jc1AuthenticationError("JC1 authentication failed");
  } finally {
    journeyKey.fill(0);
  }
}

export async function sha256(value: string): Promise<Uint8Array> {
  return new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
  );
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

export async function deriveJourneyKey(
  masterKey: Uint8Array,
  journeyHandle: Uint8Array,
): Promise<Uint8Array> {
  if (journeyHandle.length !== JC1_HANDLE_BYTES) {
    throw new Jc1AuthenticationError("Invalid Journey handle");
  }
  const saltKey = await crypto.subtle.importKey(
    "raw",
    arrayBuffer(HKDF_SALT),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const extracted = new Uint8Array(
    await crypto.subtle.sign("HMAC", saltKey, arrayBuffer(masterKey)),
  );
  try {
    const extractedKey = await crypto.subtle.importKey(
      "raw",
      arrayBuffer(extracted),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const info = concat(HKDF_INFO, journeyHandle, new Uint8Array([1]));
    try {
      return new Uint8Array(
        await crypto.subtle.sign("HMAC", extractedKey, arrayBuffer(info)),
      ).slice(0, 32);
    } finally {
      info.fill(0);
    }
  } finally {
    extracted.fill(0);
  }
}

function decodeBody(bytes: Uint8Array): Jc1Body {
  if (bytes.length !== JC1_BODY_BYTES) {
    throw new Jc1ParseError("Invalid JC1 plaintext body size");
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const telemetrySequence = view.getBigInt64(0, false);
  if (telemetrySequence < 0n) {
    throw new Jc1ParseError("Telemetry sequence must not be negative");
  }
  const observationEventTimeUnixSeconds = view.getUint32(8, false);
  const latitudeE7 = view.getInt32(12, false);
  const longitudeE7 = view.getInt32(16, false);
  const accuracyDecimeters = view.getUint16(20, false);
  if (latitudeE7 < -900_000_000 || latitudeE7 > 900_000_000) {
    throw new Jc1ParseError("Invalid JC1 latitude");
  }
  if (longitudeE7 < -1_800_000_000 || longitudeE7 > 1_800_000_000) {
    throw new Jc1ParseError("Invalid JC1 longitude");
  }
  const batteryWire = view.getUint8(22);
  if (batteryWire !== UNKNOWN_BATTERY && batteryWire > 100) {
    throw new Jc1ParseError("Invalid JC1 battery percentage");
  }
  const flags = view.getUint8(23);
  if ((flags & RESERVED_FLAG_MASK) !== 0) {
    throw new Jc1ParseError("Reserved JC1 flags are nonzero");
  }
  const connectivity = decodeConnectivity(flags & 0x07);
  const chargingBits = (flags >>> 3) & 0x03;
  const charging = chargingBits === 0
    ? null
    : chargingBits === 1
    ? false
    : chargingBits === 2
    ? true
    : undefined;
  if (charging === undefined) {
    throw new Jc1ParseError("Invalid JC1 charging flags");
  }
  return {
    telemetrySequence,
    observationEventTimeUnixSeconds,
    latitudeE7,
    longitudeE7,
    accuracyDecimeters,
    batteryPercent: batteryWire === UNKNOWN_BATTERY ? null : batteryWire,
    charging,
    connectivity,
  };
}

function decodeEventType(value: number): Jc1EventType {
  if (value === 1) return "OBSERVATION";
  if (value === 2) return "JOURNEY_COMPLETED";
  throw new Jc1ParseError("Unsupported JC1 event type");
}

function decodeConnectivity(value: number): Jc1Connectivity {
  if (value === 0) return "NONE";
  if (value === 1) return "CELLULAR";
  if (value === 2) return "WIFI";
  if (value === 3) return "OTHER";
  if (value === 4) return "UNKNOWN";
  throw new Jc1ParseError("Invalid JC1 connectivity flags");
}

function concat(...values: Uint8Array[]): Uint8Array {
  const output = new Uint8Array(
    values.reduce((size, value) => size + value.length, 0),
  );
  let offset = 0;
  for (const value of values) {
    output.set(value, offset);
    offset += value.length;
  }
  return output;
}

function arrayBuffer(value: Uint8Array): ArrayBuffer {
  return Uint8Array.from(value).buffer;
}
