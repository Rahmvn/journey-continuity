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
- `20260924000100_milestone_6_inbound_jc1_core.sql`
- `20260924000200_fix_inbound_key_unwrap_identity.sql`

The matching unwrap/classification correction is deployed in the Africa's Talking sandbox function. It required no key rotation or ciphertext rewrite. The original failed receipt is retained alongside later successful and duplicate receipts.

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

The Africa's Talking sandbox adapter is deployed with JWT verification disabled, external callback-secret/shortcode configuration, and a configured callback. Both inbound migrations through `20260924000200` are hosted. The backend core RPCs remain service-role-only; `provision-fallback` remains a provisioning endpoint.

On 2026-09-24 the initial production attempt-5 JC1 was durably classified `AUTHENTICATION_FAILED` because unwrap used the internal installation UUID. After the hosted correction, the same existing JC1 authenticated under a new genuine provider event and created one canonical observation; another genuine provider event was `AUTHENTICATED_DUPLICATE` without duplicating the envelope or observation. Existing earlier attempt 4 authenticated later as historical evidence without regressing freshness. No AWS adapter was added.

The reviewed provider material does not document a cryptographic signature for incoming SMS callbacks. The shared callback URL secret is therefore a limited sandbox control, not production-grade provider authentication. Configuration and the historical correction procedure are recorded in `MILESTONE_6_AFRICASTALKING_SANDBOX.md` and `MILESTONE_6_KEY_UNWRAP_FIX.md`.

Any future inbound deployment requires a separate review of provider authentication, secret handling, replay resistance, binding lookup, key lifecycle, error redaction, idempotency, and evidence provenance.

On 2026-09-25, controlled synthetic/test Journeys exercised the deployed Edge path directly: ACTIVE authentication, revoked key/binding rejection, distinct `KEY_UNWRAP_FAILED` and `ENVELOPE_AUTHENTICATION_FAILED`, internet-first matching, and same-sequence conflict all passed with generic public responses. Canonical internet provenance and freshness/verification state were preserved. Seven immutable receipts, three authenticated envelopes, three canonical observations, and three reconciliation records remain as acceptance evidence; all test keys are revoked and all synthetic Journeys are closed. This acceptance required no deployment and did not use Africa's Talking delivery. See `MILESTONE_6_ACCEPTANCE.md` for results.

A separate 2026-09-25 controlled Edge-path run authenticated recent JC1 as `AUTHENTICATED_NEW` / `SMS_CREATED_CANONICAL` and advanced `FALLBACK_SMS` device evidence. The hosted watchdog uses the newer of cloud contact and authenticated device evidence with a 300-second silence threshold; timely fallback prevented a false case and resolved one legitimately open case through exactly one `CONTACT_RESTORED` event (`AUTHENTICATED_FALLBACK_EVIDENCE`, case resolution `DEVICE_CONTACT_RESTORED`). Stale and duplicate envelopes remained historical without refreshing evidence; `last_cloud_contact_at` did not change. Seven receipts, five authenticated envelopes, five canonical observations, seven reconciliation records, and one resolved case remain as controlled evidence. Both test Journeys are closed and their keys/bindings revoked. No new deployment or provider delivery was used.

## Trusted-contact notification slice

The cloud-to-trusted-contact slice includes its Supabase outbox migrations, stale-started-alert supersession correction, trusted-viewer preference UI, and AWS watchdog dispatch implementation. Hosted migration `20260918000200` is applied.

Repository tests cover the outbox and dispatcher behavior. Physical trusted-contact carrier receipt is not evidenced in the repository and must not be inferred from provider acceptance or automated tests.

## Remaining deployment and acceptance boundaries

No further deployment is authorized by this record. The sandbox-only inbound route is accepted for production-JC1 authentication, duplicate-envelope handling, SMS-first reconciliation, and historical ordering; controlled hosted Edge-path failure classifications, internet-first matching, conflict preservation, and timely fallback evidence/watchdog transitions have also passed. Remaining deterministic hosted acceptance covers JC1 completion behavior. Physical/live work remains for the telephony failure matrix, production-grade provider authentication, real carrier-to-provider delivery/retry/latency, and trusted-contact notification receipt. Some live-provider-only tests cannot be completed in the Africa's Talking sandbox and may remain explicitly pending for the hackathon. Milestone 6 remains **IN PROGRESS**.

Do not mark Milestone 6 accepted until the remaining physical failure matrix and cloud-ingestion sections in `MILESTONE_6_ACCEPTANCE.md` pass.
