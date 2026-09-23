# Fallback key provisioning contract

The authenticated `provision-fallback` Edge Function implements this contract. It is intentionally
not deployed by this repository change. Until its migrations, function, and deployment secrets are
installed in an environment, Android reports fallback capability as unavailable and never substitutes
local, build-time, or Supabase API keys.

Provisioning runs over authenticated TLS and must:

1. Authenticate the current traveller and registered installation using server-side authorization.
2. Verify that the traveller owns the active Journey being bound.
3. Return a rotatable unsigned 32-bit `key_id`, an opaque random 12-byte Journey handle, and a
   cryptographically random 32-byte installation fallback master key.
4. Scope that master key only to JC1 fallback-envelope protection. It must not be a Supabase JWT,
   publishable key, service-role key, or credential for ordinary APIs.
5. Ensure the opaque Journey handle, rather than the Journey UUID, is used in the JC1 header.

The client must first wrap the master key with the non-exportable Android Keystore key. It may then
persist the non-secret Journey binding in Room. A crash between those operations may leave an orphaned
wrapped key, but must never leave a binding considered available without matching usable key material.
Raw key bytes must be cleared after use and must never enter Room, logs, UI/state models, resources,
`BuildConfig`, or plaintext preferences.

For JC1 V1, the Journey encryption key is derived using HKDF-SHA-256 with:

- input key material: the 32-byte installation fallback master key;
- salt: UTF-8 `JourneyContinuity/JC1/HKDF-SHA-256/v1`;
- info: UTF-8 `journey-envelope-key`, followed by the 12-byte opaque Journey handle;
- output length: 32 bytes.

## Endpoint

`POST /functions/v1/provision-fallback`

Request body:

```json
{"installation_id":"uuid","journey_id":"uuid"}
```

Successful response:

```json
{
  "key_id": 1234,
  "installation_master_key": "base64url-32-bytes",
  "journey_handle": "base64url-12-bytes",
  "binding_status": "ACTIVE",
  "binding_version": 1
}
```

The caller must provide a valid Supabase bearer token. The function validates that token with Auth,
then uses a service-role-only RPC to verify owner, installation, and active-Journey eligibility. The
private tables and RPCs are not callable by `anon` or `authenticated` roles.

## Server encryption and deployment secrets

The database stores only AES-256-GCM ciphertext, a random 96-bit IV, and an encryption version. The
AAD binds the ciphertext to its owner, installation identifier, `key_id`, and version. Configure these
Edge Function secrets outside the database and repository:

- `FALLBACK_KEY_KEKS_JSON`: JSON object mapping positive integer versions to Base64URL-encoded random
  32-byte KEKs. Retain a version while any stored key record references it.
- `FALLBACK_ACTIVE_KEK_VERSION`: the version used for new encryption.

Supabase supplies `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY`. Never log any
KEK, plaintext installation key, authorization header, or complete successful provisioning response.

## Rotation and revocation

The service-role-only `rotate_fallback_installation_key_backend` operation retires the current ACTIVE
installation key and creates a new ACTIVE key for future Journey bindings. It does not rewrite an
existing active Journey binding. `revoke_fallback_installation_key_backend` marks a key REVOKED without
deleting it or its bindings. A future ingestion service must reject REVOKED keys; RETIRED keys remain
available for existing bindings and historical verification.

Future inbound lookup is supported by the unique `key_id` and 12-byte opaque Journey handle. Sender
phone number is not an authentication input.
