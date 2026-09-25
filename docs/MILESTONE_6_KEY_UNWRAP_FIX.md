# Milestone 6 installation-key unwrap correction

Milestone 6 remains **IN PROGRESS**. Migration
`20260924000200_fix_inbound_key_unwrap_identity.sql` and the matching function
code are hosted. The original failed receipt and all existing key ciphertext
were preserved. This document authorizes no further deployment, key rotation,
data rewrite, or sandbox resend.

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
format, or Android production code was changed. The corrected hosted path
subsequently unwrapped the existing key and authenticated the real attempt-5
JC1 without rotation or re-provisioning.

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
- After correction, a separately authorized manual send of the exact same JC1
  supplied a new genuine Africa's Talking event ID. Never fabricate a provider
  event ID or bypass the old receipt.
- Exactly one authenticated envelope now exists for attempt 5. A subsequent
  genuine distinct provider event was `AUTHENTICATED_DUPLICATE`, with a separate
  immutable receipt and no second envelope or canonical observation.
- Both unique constraints and all immutable-evidence triggers remain in force.

## Hosted acceptance outcome

The planned read-only hosted crypto preflight could not run: no existing
non-mutating deployed interface could access the hosted KEK and exact JC1
without creating a provider event. No diagnostic endpoint was added. After
separate authorization, the existing device JC1 was manually submitted under a
new genuine sandbox provider event ID. It became `AUTHENTICATED_NEW`, proving
the corrected unwrap and envelope authentication path against existing
production key material. The original failed receipt remained unchanged.
Subsequent genuine sandbox events established duplicate-envelope and older
out-of-order behavior; see `MILESTONE_6_ACCEPTANCE.md`.
