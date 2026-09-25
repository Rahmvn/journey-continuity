# Milestone 6 Acceptance Record

Milestone 6 remains **IN PROGRESS**. Provisioning, protected offline allocation, persistence, physical Android SMS carrier handoff, and authenticated internet recovery were accepted on 2026-09-23. The provider-neutral inbound core and Africa's Talking sandbox adapter are hosted; real production JC1 authentication, duplicate-envelope handling, SMS-first reconciliation, and historical out-of-order acceptance passed on 2026-09-24–25. This is sandbox acceptance, not production-provider or complete Milestone 6 acceptance.

## Completed acceptance — 2026-09-23

### Hosted provisioning

- Hosted migration history was applied through `20260921000200_fix_fallback_key_rotation.sql`.
- The authenticated `provision-fallback` Edge Function was deployed with production KEK material held only in external secret configuration.
- An authenticated Journey owner received HTTP 200 and usable provisioning material.
- Repeating the same owner, installation, and Journey request returned the same key ID, installation master key, and opaque Journey handle.
- A non-owner request was rejected with HTTP 403.
- The corrected hosted provisioning pgTAP suite passed 36/36.

These results establish the provisioning and authorization contract. They do not establish SMS delivery or inbound provider processing.

### Physical Redmi provisioning and protected allocation

- A fresh Redmi installation established a stable installation identifier and provisioned its active Journey.
- The installation key was wrapped by Android Keystore and remained usable after process restart.
- A fresh authenticated heartbeat established `HEALTHY`.
- Loss of validated internet produced `HEALTHY -> INTERRUPTED`.
- After the configured offline interval, the same Journey reached `DEGRADED` and allocated exactly one fallback attempt.
- The attempt contained a production `JC1.` envelope protected with AES-256-GCM and an HKDF-SHA-256-derived Journey key.
- The attempt remained `ALLOCATED`; no SMS was handed to Android telephony during this acceptance run.
- The exact protected text and its SHA-256 digest survived Room close/reopen and a separate process restart.

### Physical recovery

- Restored validated internet produced `DEGRADED -> RECOVERING`.
- The authoritative Room backlog synchronized successfully.
- A genuinely fresh authenticated heartbeat produced `RECOVERING -> HEALTHY`.
- The unsent fallback attempt became `SUPERSEDED`.
- The provisioned hosted Journey binding and Android Keystore capability remained valid.

### Physical Android SMS carrier handoff

- The controlled destination was supplied externally as a test-only E.164 route. It was not committed, added to production configuration, persisted in Room, or logged.
- The active SIM was explicitly selected and revalidated before handoff.
- The application requested `SEND_SMS` and `READ_PHONE_STATE` only. `READ_SMS`, `RECEIVE_SMS`, and `READ_PHONE_NUMBERS` remained absent.
- Loss of validated internet reproduced the physical `HEALTHY -> INTERRUPTED -> DEGRADED` path.
- Existing attempt 2 / envelope sequence 2 advanced atomically from `ALLOCATED` to `HANDOFF_IN_PROGRESS` before Android telephony submission.
- The exact persisted Room text was submitted without regenerating JC1.
- Android's sent-result callback returned `RESULT_OK`, after which attempt 2 became `HANDED_OFF`.
- The controlled recipient confirmed exactly one SMS beginning with `JC1.`.
- The received text was 102 characters and one `SmsManager` segment, matched the persisted Room text byte-for-byte, and retained the same persisted digest and envelope identity.
- `HANDED_OFF` recorded only Android's successful telephony handoff. It did not establish cloud freshness or prove provider ingestion.
- Restored internet produced `DEGRADED -> RECOVERING`, authoritative backlog synchronization, a fresh authenticated heartbeat, and then `HEALTHY`.
- Attempt 2 remained historical as `HANDED_OFF`; it was not changed to `SUPERSEDED` during recovery.

### Defects exposed and corrected

The physical acceptance lifecycle exposed two unexpected later allocations in degradation episode 2:

- Attempt 3 was permitted because ordinary newer telemetry plus the elapsed minimum interval satisfied the former same-episode resend condition. Ordinary telemetry was not a legitimate sparse-fallback trigger.
- Attempt 4 was permitted after `RECOVERING` timed back into `DEGRADED` while validated internet remained available. Service/process startup ordering could also evaluate policy before activation and current network reconciliation completed.

The corrected policy and integration now enforce:

- ordinary `TimeAdvanced` and `TelemetryObserved` events cannot authorize a later same-episode fallback;
- a later attempt requires an explicit `SparseFallbackTriggered` event, newer telemetry, the minimum interval, and available rate-window capacity;
- `RECOVERING` does not degrade merely because the former recovery grace elapsed;
- ordinary fallback allocation is suppressed throughout `RECOVERING`, including during ticks, backlog work, route availability, and process or coordinator recreation;
- foreground-service evaluation waits for degraded-connectivity activation and current network reconciliation.

A reduced physical Redmi regression on 2026-09-24 established one first allocation in a new degradation episode, no additional allocation across multiple service evaluation and telemetry cycles or a force-stop/cold restart, no allocation across six `RECOVERING` evaluations and coordinator recreation, and safe recovery to `HEALTHY`. The regression did not send another SMS. The new unsent attempt became `SUPERSEDED`, while attempt 2 remained `HANDED_OFF`.

### Authentication and Journey-owner consistency

- The observed owner-Journey/non-owner-session mismatch was created by the physical harness: hosted calls used the injected owner identity while production Supabase session storage retained a previous anonymous non-owner session.
- Production cloud operations failed that mismatch closed; RLS and Journey authorization were not bypassed.
- Authentication and authorization failures are now retryable after legitimate owner-session restoration instead of permanently blocking synchronization, and they are not classified as connectivity degradation.
- An identity mismatch cannot relabel or delete the local Journey or mutate its evidence, ownership, or fallback binding.
- The reusable harness now rotates and persists a complete owner session, proves the active Journey is visible through normal hosted RLS, and verifies local Journey, binding, attempt, state, sequence, and digest evidence remains unchanged.

## Existing trusted-contact SMS evidence

The separate cloud-to-trusted-contact notification slice has repository implementation and automated coverage for:

- a durable, deduplicated notification outbox;
- transactional `VERIFICATION_STARTED`, `DEVICE_CONTACT_RESTORED`, and `JOURNEY_COMPLETED` enqueueing;
- bounded claims, retry classification, and provider-accepted recording;
- stale unsent `VERIFICATION_STARTED` terminalization as `SUPERSEDED` after case resolution;
- contact opt-in, authorization, and masked-number behavior;
- factual message construction and log redaction.

Hosted migration `20260918000200_milestone_6_supersede_stale_sms.sql` is applied. Repository evidence does not establish physical carrier receipt, handset delivery, or human reading for this slice, so those outcomes are not recorded as accepted.

## Remaining physical SMS failure-matrix acceptance

The controlled data-unavailable/SMS-available success path is accepted. Remaining physical cases include permission denial, SMS unavailable, both transports unavailable, SIM removal or ambiguity, delayed or missing callback, duplicate callback, retry and unknown-outcome handling, low-battery behavior, and provider/carrier failure outcomes. These cases must preserve durable attempt identity and must not immediately resend after an ambiguous handoff.

For the trusted-contact notification slice, the existing healthy-monitoring silence, watchdog-driven verification, restoration, offline completion, opt-out, and transport-failure checklist also remains physically unrecorded. Carrier receipt must not be generalized as guaranteed delivery, human reading, or evidence of traveller safety.

## Remaining cloud-ingestion acceptance

### Repository evidence — 2026-09-24

Automated repository validation now establishes:

- strict canonical JC1 V1 parsing before key lookup and AES-256-GCM authentication with the existing KEK/HKDF contracts;
- active and retired-bound key acceptance plus revoked key/binding rejection;
- immutable transport receipts, provider-event idempotency, and envelope replay/conflict classification;
- SMS-first and internet-first convergence on one `(journey_id, telemetry_sequence)` observation;
- discrepancy preservation instead of silent overwrite for conflicting same-sequence observations;
- delayed and out-of-order evidence retention without regressing current evidence;
- separate observation, provider-arrival, and server-receive timestamps;
- timely fallback evidence participating in watchdog freshness while never updating `last_cloud_contact_at` or asserting internet restoration;
- delayed historical fallback remaining valid evidence without falsely resolving current silence;
- deterministic completion handling and service-role-only ingestion RPC access.

These initial assertions are local automated evidence. Migrations `20260924000100` and `20260924000200` were subsequently hosted, and genuine sandbox events exercised the corrected crypto and reconciliation paths as recorded below. Other failure and convergence cases remain local-test evidence only.

### Africa's Talking sandbox adapter repository evidence — 2026-09-24

The repository contains the deployed public sandbox Edge Function boundary. Automated tests establish POST-only form parsing, body bounds, required-field and shortcode checks, exact SMS-text forwarding, stable use of Africa's Talking `id`, provider-event idempotency handoff, sender-number non-dependence, generic responses, and no application logging. A callback-specific shared URL secret is required because the reviewed provider documentation does not identify a signed incoming-SMS webhook mechanism.

The sandbox function, shortcode configuration, callback, and migration `20260924000100` are now hosted. On 2026-09-24, the exact existing attempt-5 JC1 produced one matching 102-character receipt: provider arrival 18:53:44 UTC; server receive 18:53:46.963 UTC. Strict framing and active key/binding lookup succeeded. The receipt is `AUTHENTICATION_FAILED`; decryption/reconciliation and delayed authenticated-evidence acceptance were not reached. No freshness update was attributed to this SMS. HTTP 200 is the adapter's classified-persistence response; an actual response status was not independently obtained from invocation metadata.

Diagnosis found a different installation UUID in inbound key-unwrapping AAD than in provisioning. Migration `20260924000200` and the corrected Dashboard function are hosted. Existing ciphertext and the original failed receipt remain unchanged; no key rotation or re-provisioning was needed. See `MILESTONE_6_KEY_UNWRAP_FIX.md` for the contract and regression evidence.

### Hosted production-JC1 sandbox acceptance — 2026-09-24–25

- The same existing Redmi attempt 5 (envelope sequence 5, telemetry sequence 38, 102 characters) arrived under a new genuine Africa's Talking provider event ID after the fix. The exact-body SHA-256 matched persisted Room evidence. It was `AUTHENTICATED_NEW`, with a resolved active key/binding and `SMS_CREATED_CANONICAL`: one authenticated envelope and one canonical `(journey_id, 38)` observation. The earlier `AUTHENTICATION_FAILED` receipt remained immutable.
- Its observation time was 2026-09-24 09:50:49 UTC, while provider arrival and server receipt were 23:31:38 and 23:31:40.773 UTC. This historical SMS did not advance current authenticated-device evidence, overwrite cloud-contact time, resolve verification, infer internet recovery/current location, or assert safety/danger. A later independent `CLOUD_HEARTBEAT` remained the current evidence transport.
- A later genuine provider event carrying the identical attempt-5 JC1 was `AUTHENTICATED_DUPLICATE` and recorded `DUPLICATE_ENVELOPE`. The provider receipt was separately retained, while the authenticated envelope and canonical telemetry 38 observation each remained exactly one; no conflict or watchdog/verification event was produced at duplicate receipt time. Original observation and first-receipt timestamps were preserved.
- Existing Redmi attempt 4 (envelope sequence 4, telemetry sequence 33, `SUPERSEDED`) arrived after attempt 5. It was `AUTHENTICATED_NEW` and `SMS_CREATED_CANONICAL`, creating one historical `(journey_id, 33)` observation without disturbing the existing `(journey_id, 38)` observation. Its 2026-09-23 18:46:27 UTC event time remained distinct from 2026-09-25 04:56:13 UTC provider arrival and 04:56:15.552 UTC server receipt. No conflict, monitoring event, or verification resolution was observed at arrival; current evidence remained `CLOUD_HEARTBEAT` and fallback progression did not regress.
- Provider-event uniqueness, envelope-identity uniqueness, canonical observation uniqueness, and immutable receipt/envelope/reconciliation triggers are active. Same-provider-event retry is covered locally by Node and pgTAP; a fabricated hosted provider retry is not required for this slice.
- Sandbox webhook delivery latency varied substantially, including roughly 13–20 minutes in some runs. These delays occurred before JOURNEY received the callbacks. They are not JOURNEY processing latency and are not evidence of live-provider or production-carrier performance.

### Still required

Accepted here: genuine sandbox callback shape/text preservation, active binding/key resolution, real JC1 authentication, SMS-first canonicalization, distinct-provider-event duplicate handling, historical out-of-order retention, and stale-evidence/watchdog safety. Still required for production-quality completion:

1. Hosted failure/security acceptance for malformed, unknown, revoked, and authentication-failed envelopes without sensitive disclosure; local tests already cover these paths.
2. Hosted internet-first matching and conflicting same-sequence reconciliation; accepted sandbox events exercised SMS-first and historical out-of-order paths.
3. Timely authenticated fallback evidence and verification resolution without treating SMS as cloud contact or safety evidence; the accepted real envelopes were historical and correctly ineligible for current freshness.
4. The remaining physical SMS/telephony failure matrix, including both transports unavailable, ambiguous callback/outcome, SIM ambiguity/removal, low battery, and bounded retry behavior.
5. A production-grade provider authentication and operational route before any live-provider claim. The Africa's Talking sandbox shared URL secret is not a signed provider webhook. Live-carrier/provider delivery, provider failure behavior, and latency acceptance may remain explicitly pending for the hackathon.
6. The separate cloud-to-trusted-contact notification physical checklist; inbound traveller fallback does not prove trusted-contact handset receipt or human reading.

### Remaining acceptance matrix

| Boundary | Accepted | Still required |
| --- | --- | --- |
| Device and carrier send | Provisioning, Keystore, durable offline JC1, explicit SIM/permission route, one-segment Android `RESULT_OK` handoff, controlled recipient byte equality, recovery and historical handoff retention | Physical failure matrix: permission/SMS/data unavailability, SIM removal or ambiguity, low battery, missing or ambiguous sent callback, retry/unknown outcome, and no unintended resend |
| Hosted provider-neutral ingestion | Real sandbox production-JC1 authentication after unwrap correction; SMS-first canonicalization; immutable failed/new/duplicate receipts; distinct-provider-event replay; older envelope 4 arriving after 5 without freshness regression | Hosted malformed/unknown/revoked/auth-failure handling, internet-first match and conflict preservation, timely evidence/verification transitions, and terminal completion behavior beyond local tests |
| Trusted-contact notification | Outbox/supersession implementation and hosted migrations | Physical notification failure/receipt checklist; provider acceptance does not prove handset delivery or human reading |
| Live provider | No production-provider claim | A provider-authenticated live inbound route and operational acceptance; real carrier-to-provider delivery, provider retry/failure behavior, and latency characterization cannot be established with this sandbox and may remain explicitly pending for the hackathon |

Milestone 6 must not be marked accepted until the remaining physical failure matrix and cloud-ingestion section pass end to end.
