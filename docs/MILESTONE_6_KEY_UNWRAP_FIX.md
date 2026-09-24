# Milestone 6 installation-key unwrap correction

Milestone 6 remains **IN PROGRESS**. Migration
`20260924000200_fix_inbound_key_unwrap_identity.sql` and the matching function
code are local only: **NOT HOSTED / NOT DEPLOYED**. This document authorizes no
deployment, key rotation, data rewrite, or sandbox resend.

## Diagnosis and correction

On 2026-09-24, the real attempt-5 JC1 reached the sandbox adapter unchanged
(102 characters, matching the previously verified device digest). Its receipt
was classified `AUTHENTICATION_FAILED`; no authenticated envelope was created.
The active key/binding lookup succeeded. The unwrap AAD used the internal
installation row UUID instead of the client installation identifier used by
provisioning.

The immutable at-rest contract is UTF-8:

```text
journey-continuity-fallback-key|v1|<owner-id>|<installation-identifier>|<key-id>|<encryption-version>
```

The resolver's inputs remain `(p_key_id bigint, p_journey_handle bytea)`.
Previously, its `installation_id` result was `b.installation_id`, the internal
row foreign key. It now returns both `installation_row_id` and
`installation_identifier`, with the latter obtained by joining
`private.fallback_installations`. Owner consistency is checked in the join.
All other result fields remain unchanged.

The repository maps these to `installationRowId` and
`installationIdentifier`. The core passes only `installationIdentifier` to
`decryptFallbackMasterKey`. It never guesses or falls back to the row UUID.
Provisioning encryption and provisioning decryption already both use
`body.installation_id`, passed into the provisioning RPC as
`p_installation_identifier`; they need no behavioral change. Shared crypto
parameter names now make that meaning explicit. The call-site audit found no
other production at-rest encryption/decryption paths.

The resolver return type requires transactional drop/recreation. Its explicit
service-role-only grants are restored in the same migration; no CASCADE is used.
The receipt constraint and recorder allow `KEY_UNWRAP_FAILED` and
`ENVELOPE_AUTHENTICATION_FAILED`. Historical `AUTHENTICATION_FAILED` remains
valid. No exception strings are persisted. Unexpected infrastructure failures
still propagate as unavailable; public adapter responses remain generic
200 after classified persistence versus 503 on unavailable processing.

No existing ciphertext, IV, KEK version, master key, binding, receipt, JC1 wire
format, or Android production code is changed. Existing encrypted keys should
remain valid when unwrapped with the original AAD. A real production unwrap
and attempt-5 decryption have not yet been performed with this correction.

## Regression and interoperability evidence

The regression deliberately makes the internal row UUID different from the
provisioning identifier. Wrap/unwrap with the identifier succeeds; unwrap using
the row UUID fails; the production inbound core uses the correct value.
Repository decoding rejects the old ambiguous response shape.

One shared resource,
`app/src/test/resources/jc1-v1-interoperability.properties`, contains only public
synthetic test material. Kotlin loads it as a JVM test resource; Node reads that
same file directly. It fixes the master key, installation metadata, handle,
header fields, nonce, plaintext fields/bytes, expected derived key, AAD and hash,
73-byte frame, and 102-character JC1. Expected bytes were generated independently
with Node native HKDF/AES-GCM. Both production implementations verify the same
expected HKDF output/frame, decryption, and header/nonce/ciphertext/tag tampering.
This fixture must never be used for real provisioning or transmission.

Local validation on 2026-09-24: server crypto/inbound/repository/shared-vector
tests 26/26, including five shared-vector tests; Android JVM tests 134/134,
including three shared-vector tests; adapter tests 11/11; inbound pgTAP 77/77;
provisioning pgTAP 36/36; watchdog pgTAP 37/37. Deno type/format checks and
public/private DB lint passed. All nine application migrations replayed in a
new isolated Supabase PostgreSQL 17 container. Its bundled auth schema predates
GoTrue's `email_confirmed_at` column, so that empty local prerequisite column
was added by the schema owner before linting. No shared local or hosted database
was reset or migrated.

## Existing receipt and retry contract

- Repeating the original Africa's Talking event ID returns the original immutable
  `AUTHENTICATION_FAILED` receipt before key lookup or authentication. Updating
  the code does not reprocess or rewrite it.
- Raw JC1 text is intentionally absent from the database. The failed receipt
  cannot supply the payload for automatic reprocessing.
- After correction and separately authorized sandbox acceptance, a fresh manual
  send of the exact same JC1 can supply a new genuine Africa's Talking event ID.
  Never fabricate a provider event ID or bypass the old receipt.
- No authenticated envelope currently exists for attempt 5. A successful new
  event can create that identity once. Subsequent distinct events attach duplicate
  receipts; they cannot duplicate the envelope or canonical observation.
- Both unique constraints and all immutable-evidence triggers remain in force.

## Safe hosted acceptance procedure (not executed)

1. Reverify hosted project identity, migration history, and the expected failed
   receipt by its known digest using read-only queries. Record only counts,
   lifecycle state, timestamps, and fingerprints. Preserve the old receipt.
2. With separate deployment authorization, apply only the new migration and
   deploy the matching adapter/core together in a controlled window. The old
   adapter expects the removed ambiguous field and will fail closed until the
   new code is deployed. Do not deliver callbacks during that window. Rebuild
   any Dashboard single-file artifact from the corrected source; the existing
   local artifact predates this fix and must not be reused.
3. For a read-only cryptographic preflight, use a trusted local diagnostic
   process with an externally supplied copy of the exact existing KEK version
   held only in process memory. Hosted secrets cannot be read back via ordinary
   secret-listing tools. If that KEK is unavailable, stop; do not rotate,
   reprovision, retrieve secrets through a public endpoint, or change hosted data.
4. Supply the exact persisted attempt-5 JC1 through a private in-memory input,
   never a command-line argument or log. Validate its known digest, canonical
   102-character shape, and sequence 5. Call only the read-only resolver for its
   header key ID/handle. Verify `installation_identifier` equals the original
   installation's client identifier; never use `installation_row_id` as AAD.
5. Call `decryptFallbackMasterKey` with the returned ciphertext/IV/version,
   configured KEK, owner ID, explicit installation identifier, and key ID.
   Output only unwrap status, key ID/version, and SHA-256 of the 32-byte result.
   Use `deriveJourneyKey` and `authenticateAndDecryptJc1V1` directly next;
   output only derived-key fingerprint, AAD fingerprint, authentication status,
   and whether telemetry sequence equals 38. Do not call
   `ingestVerifiedJc1Transport` or the recording RPC in this read-only preflight.
   Do not output body fields, coordinates, handles, key bytes, or full payload.
   Clear transient key arrays and close the diagnostic process afterward.
6. Only after successful preflight and explicit authorization, manually send
   that same existing envelope once through the simulator under a new real
   provider event ID. Do not allocate a new Android envelope.
7. Inspect the new receipt: authenticated envelope sequence 5 / telemetry 38,
   one canonical observation (or attachment to an observation already synced),
   provenance/conflict classification, and distinct event/provider/receive times.
   The old failed receipt must remain unchanged. Historical evidence must not
   manufacture freshness or alter cloud-contact time. Record actual HTTP status
   only from sanitized status metadata; never report a secret-bearing URL.
8. Stop on any mismatch. Duplicate/replay/out-of-order acceptance follows only
   after this corrected single-envelope acceptance succeeds.
