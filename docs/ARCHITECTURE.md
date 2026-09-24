# Journey Continuity — Architecture

**Version:** 0.1  
**Date:** 15 September 2026  
**Status:** Canonical engineering direction

---

## 1. Architecture Principle

Journey Continuity is a safety-oriented system.

Its architecture must remain deterministic where safety decisions are involved, observable, testable, resilient to degraded connectivity, explicit about evidence provenance, conservative about inference, and privacy-aware by design.

The architecture is divided into three major layers.

## 2. Layer 1 — Safety-Critical Deterministic Core

This layer owns journey state, journey transitions, timestamps, telemetry validity, authorization, verification state, escalation thresholds, timer/watchdog decisions, and terminal-state cleanup.

This layer must function without AI.

Safety-critical transitions must be driven by explicit rules and verified data.

An LLM must not determine whether a traveller is missing, kidnapped, safe, or in danger.

## 3. Layer 2 — Resilience

This layer will eventually own offline persistence, telemetry queueing, retries, sequence numbers, idempotency, duplicate detection, delayed/out-of-order event handling, degraded connectivity, sparse SMS fallback, and recovery reconciliation.

A resilience failure must not corrupt the underlying journey truth.

Event occurrence time and server arrival time must remain distinct.

Example:

```text
sequence: 105
event_time: 12:31:00
received_at: 12:38:45
```

A delayed event must not be mistaken for fresh proof of activity.

## 4. Layer 3 — Agent Experience

Alexa+/AI sits above the deterministic core.

The agent may retrieve structured journey state, summarize evidence, explain uncertainty, record bounded trusted-contact reports, request traveller verification, and begin an escalation workflow where the deterministic backend allows it.

The agent may not modify historical telemetry, invent device observations, bypass authorization, alter safety-critical rules, declare kidnapping, declare someone missing, or expose unauthorized location information.

## 5. Android Client

### 5.1 Technology

Primary client:

- Kotlin
- Jetpack Compose
- Android Architecture Components
- coroutines
- Flow / StateFlow
- Room
- ViewModel
- native Android foreground services
- WorkManager where appropriate
- native location APIs in the telemetry milestone

Native Android is intentional because the product depends deeply on foreground execution, background restrictions, location, battery behavior, network state, SIM/SMS behavior, device process lifecycle, and OEM-specific behavior.

These are core product concerns, not peripheral integrations.

## 6. Device-Side Source of Truth

Room is the device-side operational source of truth.

The phone must be able to continue recording useful journey state during poor or absent connectivity.

The UI must not own journey truth.

The foreground service must not own journey truth.

The repository/persistence layer remains authoritative.

## 7. Milestone 1 Baseline

Milestone 1 established durable Room persistence, a `Journey` domain model, `ACTIVE` and `COMPLETED` states, a database-enforced single-active-journey invariant, repository-backed `Flow`, ViewModel-backed `StateFlow`, Compose UI, a foreground service, an ongoing notification, persisted restoration, and clean completion.

Milestone 1 was physically tested and accepted on a Redmi 14C, including backgrounding, screen lock, reopening, clean completion, and Recents swipe behavior.

The no-location foreground service used a temporary legitimate `specialUse` classification during Milestone 1.

### 7.1 Milestone 2 Baseline

Milestone 2 replaced the temporary `specialUse` classification with the proper location foreground-service model and uses Google Play Services Fused Location Provider for real location updates.

Telemetry observations are persisted locally in Room. Their event time comes from Android `Location.time`; sequence numbers are allocated per Journey and protected by a database uniqueness guarantee. Battery and connectivity context are recorded with each observation. Internet connectivity is not required for local evidence collection.

### 7.2 Milestone 3 Baseline

Room remains the device-side operational source of truth, while Supabase is the server-side system of record for synchronized Journey evidence. Anonymous Supabase Auth currently provides a temporary identity bootstrap; permanent recoverable authentication or account linking remains required before public release.

Journeys retain their Android UUID in cloud storage, and each telemetry observation is identified by `(journey_id, sequence)`. A durable per-Journey local checkpoint records synchronization progress. WorkManager performs network-constrained, coalesced synchronization, including recovery of offline backlogs and Journeys completed while offline.

`event_time` is the device evidence time and `received_at` is the server arrival time. Journey and telemetry upserts are idempotent, so retries do not create duplicate logical evidence.

### 7.3 Milestone 4 Baseline

Telemetry observations and device heartbeats are separate evidence concepts. Telemetry is durable and backfilled after connectivity returns; a heartbeat is fresh device-contact evidence and is never queued or replayed. Heartbeats carry battery percentage, charging state, connectivity state, and the latest telemetry sequence. Heartbeat freshness is based on server-generated `received_at`, not the client clock.

Cloud monitoring phases are `EVIDENCE_FRESH`, `VERIFYING`, and `CLOSED`. The current development verification threshold is a provisional 300 seconds. One Amazon EventBridge schedule invokes one Lambda approximately once per minute, and the Lambda delegates deterministic, idempotent transitions to PostgreSQL. Monitoring transition history is immutable. Delayed telemetry cannot restore fresh contact, and a Journey completed while offline converges to `CLOSED` after its lifecycle state later synchronizes.

Device synchronization uses bounded `PRIMARY` and `WAKE` WorkManager lanes. Routine telemetry remains coalesced, while lifecycle and connectivity wake-ups can bypass stale retry backoff without repeatedly cancelling active synchronization work.

### 7.4 Milestone 5 Baseline

Trusted relationships and Journey authorization are separate records. An accepted relationship is persistent, but every trusted-contact Journey read still requires an explicit `journey_trusted_contact_access` row. For the Milestone 5 development policy, accepted, non-revoked relationships are provisioned idempotently for every newly created Journey, and accepting an invitation also provisions the traveller's current ACTIVE Journey. Future milestones may add per-Journey contact selection.

Invitation tokens are generated server-side with cryptographically secure randomness. Only a SHA-256 hash is retained; the raw token is returned once for manual sharing. The provisional invitation lifetime is centralized at seven days.

When the watchdog changes a Journey from `EVIDENCE_FRESH` to `VERIFYING`, PostgreSQL opens a verification case and snapshots the most recent five telemetry observations already known to Supabase in the same transaction. Its case-opening evidence and recent movement snapshot are immutable records of what the cloud knew at that moment. Later offline backfill remains Journey evidence but cannot rewrite that historical boundary.

Trusted browser access uses verified email Supabase Auth and narrow `SECURITY DEFINER` RPCs. Healthy monitoring does not expose precise location: healthy-Journey responses omit coordinates and movement evidence at the server. Precise location is available only to an authorized contact during verification access and every such reveal is audited.

Verification-case responses label device observations, system-derived transitions, and `trusted_contact_reported` evidence separately. Trusted-contact reports are append-only and cannot modify device telemetry, heartbeat evidence, or monitoring state.

A fresh heartbeat resolves an open case as `DEVICE_CONTACT_RESTORED`; Journey completion resolves it as `JOURNEY_COMPLETED` without inventing restored device contact. Resolved sensitive evidence remains available for a provisional centralized 24-hour grace period, after which the RPC denies access without deleting evidence or audit history.

Android cloud identity is sticky per installation. `TravellerIdentityCoordinator` is the sole authority permitted to establish an anonymous traveller identity, and the expected Supabase user ID is persisted independently of Supabase session storage. A temporary initialization, refresh, token-expiry, or network failure must never create a replacement anonymous identity. Identity mismatch and permanent session loss fail closed, while a shared mutex prevents concurrent first-establishment races. Authentication or authorization failure does not relabel or delete the local Journey, fallback binding, or evidence and is not evidence of lost network connectivity. After the legitimate owner session is restored, cloud synchronization may retry safely instead of remaining permanently blocked.

### 7.5 Milestone 6 Additive Trusted-Contact SMS Path

Trusted-contact SMS is a supplementary server-side awareness channel, not device evidence or the Android phone-to-cloud fallback transport. Android never sends these trusted-contact alerts. The separate phone-to-cloud fallback path may request narrowly scoped telephony permissions. An authenticated accepted contact owns their E.164 destination and must opt in explicitly for each trusted relationship; the traveller cannot configure that consent.

This additive notification path does not replace the separate device-to-cloud degraded-connectivity SMS direction in Sections 12 and 13. Compact phone-originated fallback payloads, provider ingestion, dual-SIM policy, and fallback reconciliation remain outstanding Milestone 6 responsibilities.

PostgreSQL enqueues notification outbox rows inside the authoritative verification-case opening and resolution transactions. A unique `(verification_case_id, relationship_id, notification_kind)` invariant prevents repeated logical alerts when the watchdog evaluates repeatedly. SMS state is independent of telemetry, heartbeat, monitoring phase, case state, and trusted-contact reports. Enqueue failures are contained so transport cannot corrupt a safety-state transition.

The existing scheduled watchdog Lambda claims a bounded batch with `FOR UPDATE SKIP LOCKED` and expiring leases, then uses AWS End User Messaging SMS with `TRANSACTIONAL` message type. A pending or retrying verification-started alert becomes terminal as `SUPERSEDED` when its case resolves, and claim-time validation independently rejects started alerts for resolved cases. Provider acceptance is stored as `PROVIDER_ACCEPTED`; it is not proof of handset delivery, receipt, or reading. Retryable failures use bounded exponential backoff, permanent failures become terminal, and one destination cannot block the rest of a batch. A process failure after provider acceptance but before database acknowledgement remains an unavoidable at-least-once edge because `SendTextMessage` has no caller idempotency key; leases prevent normal concurrent duplication but cannot prove exactly-once carrier submission.

SMS contains no coordinates or detailed evidence and links only to the normal authenticated viewer. Phone-number changes do not rewrite historical outbox snapshots. Opt-out, relationship revocation, authorization revocation, or a changed destination makes unsent work ineligible. Full product phone-possession verification remains a hardening requirement; Milestone 6 acceptance may use AWS sandbox destination verification for the controlled phone.

### 7.6 Milestone 6 Provisioned Device Fallback Foundation

An authenticated owner provisions an installation fallback key through the deployed `provision-fallback` Edge Function. The raw production KEK remains external secret configuration and is never stored in the repository, SQL migrations, or Android configuration. Supabase retains only encrypted installation-key material and an opaque Journey binding. Repeated provisioning for the same installation and Journey is idempotent; a different owner is rejected.

Android wraps the returned installation key with a non-exportable Android Keystore key before treating the Room binding as usable. A Journey key is derived with HKDF-SHA-256, and the compact `JC1.` envelope is protected with AES-256-GCM. The exact protected text and its SHA-256 digest are durably stored before any transport handoff.

Fallback attempts use explicit durable transport states. `ALLOCATED` means the protected envelope exists locally but has not been submitted to Android telephony. `HANDOFF_IN_PROGRESS` means one generation has been claimed for platform submission and its final outcome is not yet known. `HANDED_OFF` means Android reported successful SMS send handoff; it is not proof of carrier delivery, cloud ingestion, human receipt, or evidence freshness. Retryable, permanently failed, ambiguous, and `SUPERSEDED` outcomes remain distinguishable.

The first eligible fallback in a degradation episode is allocated once. Ordinary telemetry observations and periodic time advancement cannot authorize later same-episode attempts. A later sparse fallback requires an explicit `SparseFallbackTriggered` event plus newer telemetry and the normal minimum-interval and rate-window gates. Process or service recreation does not replay the first attempt.

Validated internet recovery moves device state from `DEGRADED` to `RECOVERING`, triggers authoritative backlog synchronization, and requires a genuinely fresh authenticated heartbeat before returning to `HEALTHY`. `RECOVERING` does not time back into `DEGRADED` merely because a grace interval elapsed, and ordinary fallback allocation remains suppressed during recovery despite queued telemetry, ticks, route availability, or process recreation. Foreground-service evaluation begins only after coordinator activation and current network reconciliation. Recovery supersedes an obsolete unsent fallback attempt without invalidating its hosted Journey binding, while an already `HANDED_OFF` attempt remains historical. Transport state never drives cloud monitoring or establishes fresh evidence.

The Android SMS handoff foundation exists, but its production inbound destination is intentionally unconfigured. Controlled carrier acceptance used a test-only externally injected E.164 route, an explicitly selected active SIM, and only `SEND_SMS` plus `READ_PHONE_STATE`. `READ_SMS`, `RECEIVE_SMS`, and `READ_PHONE_NUMBERS` are absent. Android reported `RESULT_OK` for one persisted attempt, and the recipient confirmed one 102-character, one-segment JC1 message matching the persisted Room text byte-for-byte. Provider-side JC1 receipt, authentication, decryption, duplicate handling, ordering, and reconciliation are not implemented yet.

## 8. Journey Domain

Current minimal domain:

```text
Journey
- id
- destination
- expectedArrivalAt
- startedAt
- status
- completedAt
```

Current statuses:

```text
ACTIVE
COMPLETED
```

Future states will only be introduced when their milestone requires them.

Do not pre-build the full state machine prematurely.

## 9. Telemetry Direction

A device observation may eventually contain:

```text
journeyId
sequence
eventTime
latitude
longitude
accuracy
batteryLevel
networkState
motion/context where justified
```

Important rules:

- sequence numbers are monotonic within a journey;
- event time and receive time remain distinct;
- stale data must never be displayed as live;
- location accuracy must be retained;
- device telemetry must carry provenance;
- the latest received observation is not automatically the latest event.

## 10. Cloud Architecture Direction

### 10.1 Supabase

Supabase remains the main backend/system of record for authentication, users, journeys, trusted contacts, synchronized telemetry, verification history, incident records, authorization/RLS, and storage where genuinely required.

Do not duplicate primary product state into DynamoDB merely to increase AWS usage.

### 10.2 AWS

AWS should be used where it has a real responsibility.

Current intended roles:

- Lambda for independent cloud execution;
- EventBridge Scheduler for watchdog/timer behavior;
- notification/SMS services where suitable;
- Bedrock/Strands for the later agent experience;
- MCP/Alexa+ integration;
- additional AWS services only where technically justified.

AWS must not become a decorative second backend.

## 11. Cloud Watchdog Direction

The cloud must eventually be able to notice silence independently of the phone.

Conceptually:

```text
phone telemetry
      ↓
cloud receives/records observation
      ↓
watchdog updates expected next evidence window
      ↓
expected evidence fails to arrive
      ↓
deterministic verification workflow
```

The phone must not be solely responsible for deciding that the phone itself has gone silent.

## 12. Connectivity and Offline Behavior

Normal path:

```text
device
  ↓ HTTP
cloud telemetry pipeline
```

Degraded path:

```text
device
  ↓ local persistence
retry queue
  ↓
sparse SMS fallback where justified
  ↓
same logical cloud heartbeat pipeline
```

When internet returns, queued observations synchronize, duplicates are rejected safely, ordering is reconciled, and SMS fallback stops.

## 13. SMS Direction

SMS is not a high-frequency telemetry bus.

It is intended as a sparse resilience mechanism.

Implemented constraints include compact protected payloads, explicit SIM selection, durable handoff state, and separation of platform handoff from evidence freshness. Remaining constraints include:

- provider-side authentication and decryption;
- duplicate, delayed, and out-of-order reconciliation;
- provider/carrier behavior must be tested;
- Google Play SMS permission policy must be respected;
- no assumption should be made that hackathon credits cover shortcode provisioning.

## 14. Security and Privacy Architecture

The architecture must eventually include journey-scoped authorization, least-privilege access, short-lived guardian access, auditability, provenance, RLS, secure API boundaries, retention policy, incident retention policy, deletion policy, and a threat model.

Location sharing should be explicit, temporary, and justified by the active Journey Session.

## 15. Terminal-State Cleanup

Every terminal state should eventually invoke common cleanup behavior.

Examples include stopping the foreground service, stopping location collection, cancelling heartbeat scheduling, stopping SMS fallback, cancelling verification timers, revoking temporary guardian access where appropriate, persisting the terminal state, and applying retention rules.

This cleanup must be deliberate and testable.

## 16. Testing Philosophy

Every milestone must prove one difficult thing in reality.

Examples:

- Milestone 1: real Android lifecycle behavior on Redmi 14C.
- Telemetry milestone: actual physical-device location collection and persistence.
- Cloud sync milestone: genuine connection loss and recovery.
- SMS milestone: real carrier delivery behavior.
- Watchdog milestone: actual silence and timer behavior.
- Agent milestone: uncertainty handling and authorization boundaries.

“Build succeeded” is not sufficient evidence.

## 17. OEM Testing

Redmi/HyperOS is a deliberate first stress environment because aggressive OEM background behavior is relevant to the product.

Do not add vendor-specific hacks based on assumptions.

Observe actual failure modes first.

Any Xiaomi/HyperOS-specific mitigation should be introduced only after evidence shows it is required.

## 18. Architecture Non-Goals

Do not add Hilt merely because it is common, introduce microservices prematurely, duplicate state stores without a concrete need, run an LLM on every heartbeat, let AI own safety-critical transitions, build route-risk scoring before credible data exists, over-engineer future states before their milestone, or add AWS services purely for hackathon optics.

## 19. Engineering Decision Rule

When choosing architecture, prefer:

> **the smallest structure that remains trustworthy under failure.**

The system should be easy to reason about when the app is backgrounded, the process dies, GPS is poor, internet disappears, events arrive late, SMS is delayed, the user ignores a prompt, a trusted contact reports conflicting information, or the agent does not know the answer.

Uncertainty must be represented, not hidden.
