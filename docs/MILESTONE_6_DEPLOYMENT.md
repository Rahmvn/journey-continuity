# Milestone 6 Deployment State

This document records the current deployment boundary. It is not authorization to deploy additional infrastructure or run physical SMS tests.

## Current hosted Supabase state

The Journey Continuity hosted project has migrations applied through:

- `20260915000100_milestone_3_cloud_sync.sql`
- `20260916000100_milestone_4_cloud_watchdog.sql`
- `20260917000100_milestone_5_trusted_contacts.sql`
- `20260918000100_milestone_6_trusted_contact_sms.sql`
- `20260918000200_milestone_6_supersede_stale_sms.sql`
- `20260921000100_milestone_6_fallback_provisioning.sql`
- `20260921000200_fix_fallback_key_rotation.sql`

The authenticated `provision-fallback` Edge Function is deployed. Owner provisioning returned HTTP 200, an idempotent retry returned the same key and binding material, and non-owner provisioning returned HTTP 403. The corrected hosted provisioning pgTAP suite passed 36/36 on 2026-09-23.

Before any future hosted mutation, reverify the project identity and migration ledger through an approved read-only direct PostgreSQL connection. Do not repair, rewrite, or falsify migration history.

## Secret boundary

Production KEKs are configured externally as Edge Function secrets. The repository contains only variable names and placeholder examples:

- `FALLBACK_KEY_KEKS_JSON`
- `FALLBACK_ACTIVE_KEK_VERSION`

A raw KEK, decrypted installation key, database password, service-role credential, authorization token, phone number, or complete successful provisioning response must never be printed, logged, committed, embedded in SQL, or placed in Android configuration. Old KEK versions must remain available while stored key records reference them.

## Android deployment state

The application contains authenticated provisioning, Android Keystore wrapping, Room binding and attempt persistence, production JC1 protection, degraded-connectivity recovery, and the Android SMS handoff foundation.

The production runtime SMS destination remains deliberately unconfigured through `UnconfiguredSmsFallbackRouteProvider`. Controlled physical carrier acceptance used a test-only, externally injected E.164 route and an explicitly selected active SIM; no destination is stored in Room, logged, committed, or added to production configuration. Attempt 2 / envelope sequence 2 reached `HANDED_OFF` after Android returned `RESULT_OK`, and the recipient confirmed one exact, one-segment copy of the persisted 102-character JC1 text. This handoff did not establish cloud freshness.

## Provider-side inbound state

No inbound provider route for phone-originated JC1 messages is deployed or configured. Server-side JC1 authentication, decryption, deduplication, ordering, and reconciliation remain future Milestone 6 work. The deployed `provision-fallback` function must not be described as an ingestion endpoint.

Any future inbound deployment requires a separate review of provider authentication, secret handling, replay resistance, binding lookup, key lifecycle, error redaction, idempotency, and evidence provenance.

## Trusted-contact notification slice

The cloud-to-trusted-contact slice includes its Supabase outbox migrations, stale-started-alert supersession correction, trusted-viewer preference UI, and AWS watchdog dispatch implementation. Hosted migration `20260918000200` is applied.

Repository tests cover the outbox and dispatcher behavior. Physical trusted-contact carrier receipt is not evidenced in the repository and must not be inferred from provider acceptance or automated tests.

## Remaining deployment sequence

When separately authorized:

1. Configure an approved inbound SMS route without placing destination or provider secrets in the repository.
2. Implement and review provider-side JC1 ingestion.
3. Deploy ingestion only after its authorization, cryptographic, idempotency, and reconciliation tests pass.
4. Complete the remaining physical and hosted failure matrix.

Do not mark Milestone 6 accepted until the remaining physical failure matrix and cloud-ingestion sections in `MILESTONE_6_ACCEPTANCE.md` pass.
