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

The final controlled hosted completion run used JC1 V1 event type `2` (`0x12` first frame byte). A current valid completion was `AUTHENTICATED_NEW` / `COMPLETION_APPLIED` and closed Journey monitoring once at its authenticated event time. An exact replay was `AUTHENTICATED_DUPLICATE` / `DUPLICATE_ENVELOPE`; older completions, including one after normal owner closure, were `AUTHENTICATED_NEW` / `COMPLETION_STALE` without rewriting terminal state. A later ordinary observation remained canonical history while the Journey and monitoring stayed closed. Cloud contact and current device freshness did not advance from completion or post-closure evidence. Eight receipts, seven authenticated envelopes, three canonical observations, and eight reconciliation records remain; all three test Journeys are closed and their keys/bindings revoked. The scoped deterministic hosted Milestone 6 acceptance is complete without a new deployment.

## Trusted-contact notification slice

The cloud-to-trusted-contact slice includes its Supabase outbox migrations, stale-started-alert supersession correction, trusted-viewer preference UI, and AWS watchdog dispatch implementation. Hosted migration `20260918000200` is applied.

The existing watchdog was updated through its SAM/CloudFormation deployment on 2026-09-26 (`UPDATE_COMPLETE`), checkpointed at `6d7a3a97c4ce39230d103b7f7e225d4d226de10c`. Reviewed/deployed artifact SHA-256: `3Tej0+nnkcAxgaqsJyUG1gL+sUZ0BLz8Cqjx52urNio=`. It includes the notification dispatcher/templates and resolvable SMS SDK; `index.handler` and the enabled minute schedule are retained. SMS configuration is present, the existing secret is retained, and only `sms-voice:SendTextMessage` was added to the watchdog role, scoped to the existing sender ARN. Three observed scheduled drain runs claimed zero notifications before fixture creation; the hosted outbox was empty and no SMS was sent during deployment validation.

One subsequently authorized controlled `VERIFICATION_STARTED` SMS passed end-to-end handset acceptance. Notification `de392087-2f7e-4246-873b-2c2a683b5775` was enqueued canonically with explicit contact consent and held at infinity before commit. A scheduled execution claimed zero while held. The user then authorized exactly one send; releasing only that row allowed the normal schedule to record one claim, one provider submission, and terminal `PROVIDER_ACCEPTED` with a persisted provider message ID. The user physically confirmed receipt, sender **JOURNEY**, and approved unknown-whereabouts/viewer-link wording. A subsequent cycle produced no duplicate. Release, processing, and acceptance times and the remaining matrix are in `MILESTONE_6_ACCEPTANCE.md`. Provider acceptance alone is not receipt evidence; this acceptance depends on the separate user observation and covers only this notification. No number, full viewer URL/token, or credential is recorded. Absence of an SMS preference is ineligible; the fixture explicitly opted in.

## Remaining deployment and acceptance boundaries

### Deployed notification dispatch-boundary correction (2026-09-27)

Migration `20260927000100_sms_dispatch_boundary.sql` and the matching watchdog update are **deployed**. The coordinated rollout replaced the old lease-expiry resend behavior and resolution race. Migration SHA-256: `94af45ef4cf7217d4351d67cd352e43baf8df6cdfadb5d5ce7860d719cfad3c0`. The hosted migration history records `20260927000100`; all six RPC bodies match the reviewed migration, the dispatch columns and protected attempt ledger exist, and service-only dispatch authorization is verified.

The corrected state machine separates revocable `SENDING` claims from irreversible `DISPATCHING` attempts. `authorize_sms_dispatch` locks/rechecks Journey/case lifecycle, relationship, grant, explicit consent and destination, then commits a unique dispatch token and increments the durable provider attempt count. Only the successful first authorization permits AWS invocation. A lost authorization response permits no provider call. An expired pre-dispatch claim is reclaimable without consuming a provider attempt; an expired dispatch becomes `PROVIDER_OUTCOME_UNKNOWN`, which is never automatically sendable.

Restoration/completion before this boundary supersedes the start alert (`FAILED / SUPERSEDED`). After the boundary, the in-flight start remains acknowledgeable even after resolution or consent/access revocation. Resolution notifications remain separate and deduplicated. Unknown outcomes retain attempt history and may accept a matching late provider acknowledgement; there is no automatic resend or inference of provider acceptance.

The installed SMS SDK previously defaulted to three total attempts (configurable through SDK/environment settings). The updated SMS client explicitly uses `maxAttempts: 1`; actual SDK middleware tests use a fake HTTP handler. Only explicit throttling rejection permits retry, up to five durable provider attempts per logical row with exponential backoff. Timeouts, network/server errors and missing message IDs are ambiguous. Successful AWS responses receive up to three idempotent database acknowledgement attempts, never another AWS invocation.

`SendTextMessage` has no application idempotency token. Its `Context` supports non-sensitive notification/dispatch identifiers for an existing configuration-set event destination. The correction adds those identifiers only; it adds no event infrastructure or automated event reconciliation. Future authenticated provider-event reconciliation could resolve unknown outcomes, but is not accepted by this slice. See the [AWS API contract](https://docs.aws.amazon.com/pinpoint/latest/apireference_smsvoicev2/API_SendTextMessage.html).

Journey grants remain revocation-based under `trusted_contact_has_journey_access`; the grant's sensitive-access field is not a general Journey authorization deadline. Resolved verification evidence expires under `get_verification_case`. Both claim and final dispatch now enforce that case expiry for resolution notifications.

The scheduler was disabled at `2026-09-27 05:53:06.767 UTC`; the final queued old invocation completed at `05:53:27.808 UTC` with zero claims. After the configured retry window plus function timeout, quiescence was verified at `05:59:17.891 UTC`. The standard direct-Postgres migration runner applied only the authorized migration from an isolated work directory. The existing Lambda then received the reviewed SAM-built code through `UpdateFunctionCode`, without a configuration or CloudFormation-stack update. Deployed artifact SHA-256 (base64): `k4JdWNJvDZucuj6yY8IcWo6veouy4B6ne+EkZgYk8sI=`. Lambda reported `Active` / `Successful`; handler, runtime, timeout, role and environment values matched their pre-update fingerprint. The scheduler was restored to `ENABLED`, `rate(1 minute)`, at `06:01:59.650 UTC` after hosted no-send acceptance and a fresh zero-eligible snapshot.

Pre/post-migration committed state was one `PROVIDER_ACCEPTED` outbox row, count 1, provider message ID present, zero nonterminal/failed rows, zero leases, zero eligible notifications and zero Journeys due for verification/closure. The prior handset-accepted row's full pre-existing field fingerprint remained unchanged. The migration added one historical attempt snapshot without changing that row or its count. Hosted rollback-only acceptance passed 146 pgTAP assertions, including before/after dispatch restoration/completion, pre-dispatch reclaim, unknown-outcome non-reclaim, late acknowledgement, final authorization revocation checks and evidence expiry. No fixture was committed and no provider was invoked by these tests. Sender, IAM, region, SMS/account settings, inbound-provider configuration and schedule cadence were unchanged.

The first three completed scheduled drains after restoration were recorded at `06:02:46.913`, `06:03:33.524` and `06:04:32.715 UTC`. Each reported zero claims, provider acceptances, retries, failures, unknown outcomes and dispatch denials; initialization completed without errors. The final hosted snapshot at `06:05:14.780 UTC` retained the same accepted-row fingerprint, zero eligible work and no pending migration. Zero real SMS/provider submissions occurred during this rollout. Watchdog tests passed 16/16; the expanded local and hosted pgTAP suites passed 146/146, and local concurrency/restart/fake-provider integration passed. `git diff --check` and sensitive-value scans passed. Milestone 6 remains **IN PROGRESS**; no commit or push accompanied this rollout.

No further deployment is authorized by this record. The scoped deterministic hosted Milestone 6 acceptance is complete: production-JC1 sandbox authentication, replay and historical ordering, controlled failure classifications, internet-first reconciliation, timely fallback evidence/watchdog transitions, and JC1 completion have passed. On 2026-09-26, physical selected-SIM loss (D) passed, and same-attempt transport restoration (G) passed only at a test-controlled no-send pre-claim boundary. Attempt 11 and its persisted JC1 stayed unchanged; no real SMS was sent. The production SMS destination remains unconfigured. These tests did not deploy or alter hosted infrastructure, AWS, or provider configuration, prove live-service restart under SIM loss, or establish carrier delivery. Remaining work covers the rest of the physical telephony failure matrix, trusted-contact notification receipt, production-grade provider authentication, and real carrier-to-provider delivery/retry/latency. The provider-production items may remain explicitly pending for the hackathon if live telecom provisioning is unavailable. Milestone 6 remains **IN PROGRESS**.

The subsequent Room v9 `UNKNOWN_OUTCOME` correction passed isolated Redmi v8-to-v9 migration, on-disk/process restart, and no-send state-machine acceptance. Timeout-created legacy retries became durable, non-sendable `UNKNOWN_OUTCOME`; confirmed-failure retries retained only the remaining portion of a two-claim budget. Recovery and Journey completion preserved ambiguity, while late matching callbacks and independently eligible new sparse evidence followed their distinct rules. The fixture ran under a separate test package/UID and did not migrate production Room data; zero real carrier sends occurred. The production worker dispatch path was exercised with a fake coordinator, **not** Android WorkManager framework scheduling. A later test-ordering-only edit compiled but did not receive a whole-class Redmi rerun after HyperOS blocked reinstall. This is device-local acceptance, not a hosted deployment, live callback/carrier-delivery, or provider-authentication claim. The remaining device, notification, and live-provider boundaries in `MILESTONE_6_ACCEPTANCE.md` still apply.

The latest receipt evidence supersedes earlier statements that all trusted-contact handset receipt remained outstanding. Remaining notification acceptance is opt-out, stale/superseded work, restoration before claim, completion before claim, retryable/permanent provider failure, and retained accepted history, using no-send/deterministic boundaries. Remaining device/carrier limits and production-grade inbound-provider authentication, real carrier-to-provider delivery/retry/failure, and latency characterization or explicit live-route limitation remain in `MILESTONE_6_ACCEPTANCE.md`. Do not mark Milestone 6 accepted until those boundaries are resolved or their limitations recorded explicitly. This checkpoint authorizes no additional send, fixture, or deployment.
