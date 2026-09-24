# Milestone 6 Acceptance Record

Milestone 6 remains **IN PROGRESS**. Provisioning, protected offline allocation, persistence, physical Android SMS carrier handoff, and authenticated internet recovery were accepted on 2026-09-23. A provider-neutral inbound authentication and reconciliation core and a thin Africa's Talking sandbox adapter were implemented and validated locally on 2026-09-24, but no hosted inbound route exists and no provider callback has reached the core. Sandbox provider acceptance and the remaining physical failure matrix remain outstanding.

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

This is local automated evidence only. Migration `20260924000100_milestone_6_inbound_jc1_core.sql` is not hosted, and no provider request has reached this core.

### Africa's Talking sandbox adapter repository evidence — 2026-09-24

The repository now contains a dedicated public Edge Function boundary for a future sandbox callback. Automated tests establish POST-only form parsing, body bounds, required-field and shortcode checks, exact SMS-text forwarding, stable use of Africa's Talking `id`, provider-event idempotency handoff, sender-number non-dependence, generic responses, and no application logging. A callback-specific shared URL secret is required because the reviewed provider documentation does not identify a signed incoming-SMS webhook mechanism.

This is not provider acceptance. The function is not deployed, shortcode `35549` is not configured in Supabase runtime state, the dashboard callback URL is not set, the sandbox's exact callback has not been observed, and migration `20260924000100_milestone_6_inbound_jc1_core.sql` remains **not hosted**.

### Still required

No provider-side inbound JC1 route is deployed or configured. Sandbox provider acceptance must verify:

1. The actual callback form shape and exact unchanged `text`, plus strict request validation and the documented limitations of the sandbox shared-secret boundary.
2. Journey binding lookup without exposing installation key material.
3. JC1 authentication and AES-GCM decryption with the provisioned key version.
4. Rejection of malformed, unknown, revoked, or authentication-failed envelopes.
5. Idempotent duplicate handling.
6. Delayed and out-of-order reconciliation against authoritative cloud telemetry and heartbeat sequence state.
7. Clear provenance distinguishing SMS-derived evidence from normal authenticated internet evidence.
8. Internet recovery stops ordinary fallback without allowing SMS transport state alone to establish `HEALTHY`.

Milestone 6 must not be marked accepted until the remaining physical failure matrix and cloud-ingestion section pass end to end.
