# Milestone 6 Acceptance Record

Milestone 6 remains **IN PROGRESS**. Provisioning, protected offline allocation, persistence, and authenticated internet recovery were accepted on 2026-09-23. Physical SMS carrier handoff and provider-side cloud ingestion remain outstanding.

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

## Existing trusted-contact SMS evidence

The separate cloud-to-trusted-contact notification slice has repository implementation and automated coverage for:

- a durable, deduplicated notification outbox;
- transactional `VERIFICATION_STARTED`, `DEVICE_CONTACT_RESTORED`, and `JOURNEY_COMPLETED` enqueueing;
- bounded claims, retry classification, and provider-accepted recording;
- stale unsent `VERIFICATION_STARTED` terminalization as `SUPERSEDED` after case resolution;
- contact opt-in, authorization, and masked-number behavior;
- factual message construction and log redaction.

Hosted migration `20260918000200_milestone_6_supersede_stale_sms.sql` is applied. Repository evidence does not establish physical carrier receipt, handset delivery, or human reading for this slice, so those outcomes are not recorded as accepted.

## Remaining SMS-carrier acceptance

Before physical execution, configure an authorized inbound SMS destination without committing a phone number, credential, token, or provider secret.

The controlled Redmi acceptance must then verify:

1. Required SMS and phone-state permissions are explicit and denial fails safely.
2. The intended active SIM is selected; ambiguity or removal blocks handoff rather than silently choosing another SIM.
3. A persisted `JC1.` payload remains exactly one SMS segment.
4. One eligible `ALLOCATED` attempt advances through `HANDOFF_IN_PROGRESS` only once per handoff generation.
5. Android's sent callback records `HANDED_OFF`, retryable, permanent, unavailable, or ambiguous outcomes without claiming carrier delivery.
6. Retry and uncertainty recovery do not duplicate a successfully acknowledged handoff.
7. Recovery before handoff leaves the obsolete attempt `SUPERSEDED` and unsent.
8. Data unavailable/SMS available, both unavailable, delayed callback, duplicate callback, dual-SIM ambiguity, low battery, and process-restart cases behave deterministically.

For the trusted-contact notification slice, the existing healthy-monitoring silence, watchdog-driven verification, restoration, offline completion, opt-out, and transport-failure checklist also remains physically unrecorded. Carrier receipt must not be generalized as guaranteed delivery, human reading, or evidence of traveller safety.

## Remaining cloud-ingestion acceptance

No provider-side inbound JC1 route is deployed or configured. After that implementation exists, acceptance must verify:

1. Provider authenticity and strict request validation before payload processing.
2. Journey binding lookup without exposing installation key material.
3. JC1 authentication and AES-GCM decryption with the provisioned key version.
4. Rejection of malformed, unknown, revoked, or authentication-failed envelopes.
5. Idempotent duplicate handling.
6. Delayed and out-of-order reconciliation against authoritative cloud telemetry and heartbeat sequence state.
7. Clear provenance distinguishing SMS-derived evidence from normal authenticated internet evidence.
8. Internet recovery stops ordinary fallback without allowing SMS transport state alone to establish `HEALTHY`.

Milestone 6 must not be marked accepted until both remaining sections pass end to end.
