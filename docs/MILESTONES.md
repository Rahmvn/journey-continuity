# Journey Continuity — Milestones

**Version:** 0.1  
**Date:** 15 September 2026  
**Status:** Working delivery roadmap

---

## Delivery Rule

Each milestone must:

1. prove one difficult thing;
2. remain tightly scoped;
3. define a physical or end-to-end acceptance test;
4. be committed only after acceptance;
5. avoid beginning the next milestone early.

The roadmap may be reordered when evidence reveals a better dependency order, but changes must be deliberate.

## Milestone 1 — Local Journey Lifecycle

**Status:** ACCEPTED

### Goal

Prove that a native Android Journey Session can survive normal real-device lifecycle conditions and stop cleanly.

### Implemented

- native Kotlin/Compose project;
- Journey domain model;
- Room persistence;
- single-active-journey invariant;
- repository + Flow;
- ViewModel + StateFlow;
- minimal Compose UI;
- foreground service;
- ongoing notification;
- persisted restoration;
- clean completion.

### Physical acceptance

Passed on Redmi 14C:

- start journey;
- background app;
- lock device;
- leave locked;
- notification remains;
- reopen app;
- same persisted journey remains active;
- end journey;
- notification/service stop;
- Recents swipe test passes.

### Scope deliberately excluded

GPS, networking, Supabase, AWS, SMS, trusted contacts, AI, Alexa, and route-risk features.

## Milestone 2 — Local Device Telemetry

**Status:** ACCEPTED

### Goal

Prove that an active Journey Session can produce a trustworthy local stream of real device observations.

### Implemented

- proper Android location foreground-service type;
- runtime location permission flow;
- real latitude/longitude collection;
- accuracy;
- event timestamp;
- local sequence number;
- battery context;
- connectivity context;
- Room persistence;
- active-journey association;
- clean stop on journey completion;
- restoration without creating duplicate journeys.

### Important design rules

- no cloud yet;
- no SMS;
- no trusted contacts;
- no AI;
- no route-risk scoring;
- no fake “danger” detection;
- event time must remain distinct from display time;
- location accuracy must be retained.

### Physical acceptance

Passed on the Redmi 14C:

- real location observations;
- monotonically increasing persisted sequence numbers;
- continued collection while backgrounded and screen-locked;
- continued collection after a Recents swipe;
- local collection while offline;
- connectivity state changes across offline and restored-connectivity conditions;
- honest Precise and Approximate permission behavior;
- actionable non-monitoring behavior while Location Services are disabled;
- clean Journey completion with telemetry collection stopping and no later observations.

## Milestone 3 — Reliable Cloud Synchronization

**Status:** ACCEPTED

### Goal

Move local journey evidence to the cloud reliably without making the cloud required for device-side recording.

### Expected scope

- Supabase auth foundation where necessary;
- server-side journey record;
- telemetry upload;
- queue/retry;
- idempotency;
- sequence reconciliation;
- duplicate handling;
- delayed/out-of-order handling;
- offline-to-online recovery;
- server receive timestamps.

### Acceptance target

Demonstrate normal upload, connectivity loss, continued local recording, restored connectivity, queued backfill, no duplicate logical events, correct ordering by event sequence/time, and coherent journey state.

### Physical and remote acceptance

Passed with the Redmi 14C and the Supabase development project:

- anonymous Supabase authentication;
- `auth.uid()`-based Journey ownership and RLS enforcement;
- online Journey and telemetry synchronization;
- distinct `event_time` and `received_at` persistence in the cloud;
- continued local telemetry collection and durable backlog while offline;
- WorkManager recovery after connectivity returned;
- synchronization recovery across a Recents swipe and process lifecycle;
- local Journey completion while offline;
- later synchronization of the Journey's `COMPLETED` state;
- idempotent telemetry retries with no duplicate logical observations.

## Milestone 4 — Cloud Watchdog + Verification Engine

**Status:** ACCEPTED

### Goal

Allow the cloud to independently recognize meaningful telemetry silence and begin a deterministic verification workflow.

### Expected scope

- Lambda/EventBridge Scheduler or equivalent justified AWS runtime;
- expected heartbeat windows;
- missed-evidence evaluation;
- deterministic verification transitions;
- retry/backoff;
- no AI decision-making;
- no premature emergency claims.

### Acceptance target

Simulate healthy telemetry, a short harmless gap, longer meaningful silence, restored telemetry, repeated silence, correct state transitions, and no false escalation from a single missed event.

### Physical and cloud acceptance

Passed with the Redmi 14C, Supabase, and the deployed AWS watchdog:

- fresh heartbeat creation with an independent persisted heartbeat sequence;
- initialization of cloud monitoring as `EVIDENCE_FRESH`;
- Amazon EventBridge Scheduler invocation of the watchdog Lambda;
- Lambda delegation to the deterministic Supabase watchdog RPC;
- automatic `EVIDENCE_FRESH` to `VERIFYING` transition after cloud silence;
- no duplicate `VERIFYING_STARTED` events across repeated watchdog evaluations;
- fresh contact recording one `CONTACT_RESTORED` transition and restoring `EVIDENCE_FRESH`;
- offline Journey completion converging to `CLOSED` after later synchronization;
- stale telemetry not restoring fresh-contact state;
- Scheduler operation independently of the Android app;
- heartbeat battery percentage, charging, connectivity, and latest telemetry context;
- stale WorkManager retry/backoff recovery through bounded `PRIMARY`/`WAKE` scheduling.

## Milestone 5 — Trusted Contacts + Incident Record

**Status:** ACCEPTED

### Goal

Give authorized trusted contacts a useful, temporary, evidence-based view when contact becomes uncertain.

### Expected scope

- trusted contacts;
- explicit authorization;
- temporary journey access;
- last verified state;
- recent movement trail where appropriate;
- verification history;
- trusted-contact reports;
- provenance labels;
- incident record;
- auditability.

### Implemented

- hashed, single-use, expiring invitation model and manual link sharing;
- verified-email invitation acceptance through Supabase Auth;
- persistent relationships separated from explicit per-Journey access;
- automatic Milestone 5 access provisioning for accepted relationships;
- prompt revocation without deleting evidence history;
- transactionally opened and deterministically resolved verification cases;
- immutable opening evidence and five-point recent-location snapshots;
- append-only provenance-labelled trusted-contact reports;
- healthy-state server omission of precise location;
- temporary post-resolution sensitive access;
- deduplicated server-time access auditing;
- minimal trusted-contact web viewer;
- Android traveller management UI;
- authorization-focused pgTAP coverage.

### Acceptance target

A trusted contact can understand what is known, what is stale, what is unverified, what attempts have been made, and what the last verified state was. Unauthorized users must not gain location access.

### Physical, cloud, and authorization acceptance

Passed with the Redmi 14C, the Journey Continuity Supabase development project, the deployed AWS watchdog, and the deployed trusted-contact viewer.

Trusted contacts and authorization:

- the traveller created an invitation on the Redmi;
- a trusted contact authenticated through an email magic link and the correct invited identity accepted successfully;
- a wrong authenticated identity was denied;
- the invitation was single-use;
- the accepted relationship persisted independently of individual Journeys;
- explicit per-Journey authorization worked;
- revocation immediately blocked further access.

Healthy Journey privacy:

- an authorized trusted contact could view healthy Journey status;
- precise location and the movement trail were not exposed while monitoring was `EVIDENCE_FRESH`;
- an unauthorized authenticated user was denied.

Verification cases:

- the AWS watchdog independently transitioned the Journey to `VERIFYING`;
- exactly one verification case opened for each `VERIFYING` period;
- the immutable opening snapshot contained only evidence already known to the cloud;
- the recent movement snapshot was preserved;
- delayed telemetry did not rewrite the opening evidence;
- the trusted viewer used truthful `VERIFYING` language;
- precise location became available only through authorized verification access.

Trusted-contact reports and resolution:

- an authorized contact submitted evidence with `trusted_contact_reported` provenance;
- the report changed neither telemetry, heartbeat evidence, nor monitoring freshness;
- a fresh heartbeat resolved a case as `DEVICE_CONTACT_RESTORED`;
- later offline Journey completion resolved a case as `JOURNEY_COMPLETED` without creating a false `CONTACT_RESTORED` event.

Audit and sensitive access:

- `JOURNEY_VIEWED`, `VERIFICATION_CASE_VIEWED`, `PRECISE_LOCATION_REVEALED`, and `REPORT_SUBMITTED` were recorded with bounded deduplication;
- audit records contained no precise coordinates;
- the 24-hour post-resolution sensitive-access expiry remains provisional;
- pgTAP verified that sensitive access works before expiry and is denied after expiry without deleting evidence.

Identity continuity defect and final architecture:

- physical testing crossed the Supabase access-token lifetime during a prolonged offline period;
- the previous use of `currentSessionOrNull() == null` treated `RefreshFailure` as an absent identity, allowing simultaneous callers to create replacement anonymous identities;
- the resulting ownership mismatch caused Journey and heartbeat `403` / `42501` failures and a false empty trusted-contact state;
- `TravellerIdentityCoordinator` is now the centralized authority for cloud identity, with `expectedTravellerUserId` persisted independently;
- anonymous identity creation is permitted only for genuine first establishment;
- temporary refresh or network failure never creates a replacement identity;
- mismatch and recovery-required states fail closed;
- a shared mutex prevents concurrent anonymous-account creation;
- synchronization retries transient auth failure, heartbeat skips a failed fresh proof without replay, and the trusted-contact UI distinguishes unavailable or error states from a successful empty result.

Physical identity-continuity validation:

- the corrupted installation entered recovery-required state without creating another anonymous account;
- a clean installation created one controlled traveller identity;
- the Redmi remained offline beyond the token lifetime without creating a replacement identity;
- AWS `VERIFYING` and trusted-contact access continued correctly;
- reconnect restored the same traveller identity, synchronized the backlog, and resumed heartbeat;
- the trusted relationship remained intact and no `403` / `42501` ownership failures occurred.

Remote deployment acceptance:

- Milestone 3 and Milestone 4 migration history was reconciled after a read-only schema comparison;
- the Milestone 5 migration was applied successfully to Journey Continuity;
- existing Journey, telemetry, heartbeat, and monitoring data was preserved;
- remote authorization smoke tests passed;
- the trusted viewer deployed successfully.

## Milestone 6 — Degraded Connectivity + SMS Fallback

**Status:** IN PROGRESS — PROVISIONING, OFFLINE ALLOCATION, PHYSICAL CARRIER HANDOFF, RECOVERY, SELECTED-SIM LOSS (D), NO-SEND SAME-ATTEMPT RESTORATION (G), ISOLATED REDMI UNKNOWN-OUTCOME ACCEPTANCE, AND SCOPED DETERMINISTIC HOSTED JC1 ACCEPTANCE COMPLETE; REMAINING FAILURE-MATRIX, NOTIFICATION, AND PRODUCTION-PROVIDER ACCEPTANCE OUTSTANDING

### Goal

Preserve limited continuity when mobile internet becomes unreliable.

### Expected scope

- sparse SMS fallback;
- compact machine payload;
- provider ingestion;
- dual-SIM selection/binding;
- delayed/out-of-order reconciliation;
- fallback stop after internet recovery;
- carrier/provider testing.

### Completed checkpoint — 2026-09-23

- deterministic `HEALTHY`, `INTERRUPTED`, `DEGRADED`, and `RECOVERING` policy with authoritative Room persistence;
- physical Redmi acceptance of `HEALTHY -> INTERRUPTED -> DEGRADED`, followed by `DEGRADED -> RECOVERING -> backlog sync -> fresh heartbeat -> HEALTHY`;
- compact production `JC1.` envelope protected with AES-256-GCM using an HKDF-SHA-256-derived Journey key;
- durable, transactionally allocated fallback attempts with exact protected text and digest retained across Room reload and process restart;
- installation fallback key wrapping with Android Keystore and an opaque, Journey-scoped binding;
- hosted fallback provisioning with owner authorization, non-owner rejection, and idempotent retry returning the same key and binding material;
- hosted migrations applied through `20260921000200_fix_fallback_key_rotation.sql`;
- corrected hosted provisioning pgTAP suite passing 36/36;
- one real production-protected JC1 attempt allocated while offline, then terminalized as `SUPERSEDED` after authenticated recovery while its hosted binding remained valid;
- Android SMS handoff foundation: explicit permission/capability checks, SIM selection, one-segment enforcement, durable handoff state, platform result callback, and bounded retry/uncertainty handling;
- controlled physical Redmi carrier acceptance using an externally injected E.164 route and an explicitly selected active SIM, without persisting or committing the destination;
- only `SEND_SMS` and `READ_PHONE_STATE` were requested; `READ_SMS`, `RECEIVE_SMS`, and `READ_PHONE_NUMBERS` remained absent;
- attempt 2 / envelope sequence 2 advanced `ALLOCATED -> HANDOFF_IN_PROGRESS -> HANDED_OFF`, with Android's sent callback returning `RESULT_OK`;
- the controlled recipient received exactly one 102-character, one-segment `JC1.` message whose text matched the persisted Room text byte-for-byte and retained the same digest and envelope identity;
- SMS handoff did not establish cloud freshness; recovery still required `DEGRADED -> RECOVERING -> backlog sync -> fresh authenticated heartbeat -> HEALTHY`, and the `HANDED_OFF` attempt remained historical;
- physical-acceptance corrections now require an explicit `SparseFallbackTriggered` event for a later same-episode attempt, suppress ordinary allocation throughout `RECOVERING`, and make service evaluation wait for coordinator activation and network reconciliation;
- authentication and authorization mismatches fail closed without mutating local Journey evidence, ownership, or fallback bindings, remain retryable after legitimate owner-session restoration, and are not classified as connectivity degradation.

### Provider-neutral inbound core checkpoint — 2026-09-24

Implemented in the repository and deployed for Africa's Talking sandbox acceptance; this is not live-provider acceptance:

- strict canonical `JC1.` V1 parsing with exact frame bounds before private-key lookup;
- server-side installation-key decryption through the existing versioned KEK ring, Android-compatible HKDF-SHA-256 Journey-key derivation, and AES-256-GCM authentication;
- key and opaque Journey-binding resolution using only `key_id` plus the 12-byte handle, with active, retired, revoked, and binding lifecycle enforcement;
- immutable provider transport receipts and authenticated-envelope evidence with independent provider-event and cryptographic-envelope idempotency;
- one canonical observation per `(journey_id, telemetry_sequence)`, with SMS-first, internet-first, matching, conflicting, duplicate, delayed, and out-of-order reconciliation;
- distinct observation event time, provider arrival time, and server receive time;
- transport-neutral authenticated-device evidence that can prevent or resolve evidence silence when timely, without changing `last_cloud_contact_at`, claiming internet recovery, or asserting safety;
- deterministic completion handling that cannot reopen or rewrite a newer terminal Journey state;
- service-role-only backend RPCs and no public generic ingestion endpoint;
- a documented provider-adapter contract that requires provider authentication before normalization and core invocation.

Migrations `20260924000100_milestone_6_inbound_jc1_core.sql` and `20260924000200_fix_inbound_key_unwrap_identity.sql` are hosted. The corrected function is deployed. No AWS adapter exists.

### Africa's Talking sandbox adapter — hosted receipt, 2026-09-24

- dedicated `africastalking-inbound` Edge Function accepting only bounded form-encoded POST callbacks;
- external sandbox shortcode check for `35549` and external high-entropy callback URL secret;
- real Africa's Talking `id` used as the stable provider event ID, with no invented fallback identity;
- exact `JC1.` text passed unchanged to the core, with no sender-number authentication or persistence;
- backend core and key-material RPCs remaining service-role-only;
- adapter coverage for malformed, missing, wrong-shortcode, non-JC1, oversized, duplicate, idempotent, sender-independent, and no-sensitive-logging behavior.

The sandbox function, callback, external secrets, and both inbound migrations are deployed/configured. The first production attempt-5 receipt remains immutably `AUTHENTICATION_FAILED`: inbound unwrap had used the internal installation row UUID rather than the provisioning identifier as AAD. The deployed correction separates those identifiers and unwrap/envelope failure classifications; a shared Kotlin/TypeScript synthetic vector covers interoperability. Subsequent genuine sandbox events authenticated the same existing attempt-5 JC1 as `AUTHENTICATED_NEW` with `SMS_CREATED_CANONICAL`, then `AUTHENTICATED_DUPLICATE` with `DUPLICATE_ENVELOPE`. One envelope and one canonical observation remained. Existing attempt 4 (envelope 4 / telemetry 33) arrived after attempt 5 (5 / 38), authenticated as historical evidence, and created one older canonical observation without regressing current evidence. See `MILESTONE_6_ACCEPTANCE.md`.

Sandbox webhook delivery varied substantially, including roughly 13–20-minute delays. This is observed sandbox delivery latency, not JOURNEY processing latency or a production carrier/provider performance claim.

The reviewed provider material does not document a signed incoming-SMS callback, so the shared URL secret is sandbox-only and must not be represented as production-grade provider authentication.

### Controlled hosted valid-key acceptance — 2026-09-25

Controlled hosted Edge-path acceptance passed on 2026-09-25 using synthetic/test Journeys and keys: ACTIVE authentication (`AUTHENTICATED_NEW` / `SMS_CREATED_CANONICAL`), `KEY_REVOKED`, `BINDING_REVOKED`, `KEY_UNWRAP_FAILED`, `ENVELOPE_AUTHENTICATION_FAILED`, internet-first `SMS_MATCHED_INTERNET`, and same-sequence `SMS_CONFLICT`. Generic public responses, immutable internet provenance, canonical uniqueness, and unchanged freshness/verification/notification state were verified. Retained evidence comprises seven immutable receipts, three authenticated envelopes, three canonical observations, and three reconciliation records. All test keys are revoked and all synthetic Journeys are closed. This was direct deployed-function acceptance, not Africa's Talking delivery; see `MILESTONE_6_ACCEPTANCE.md`.

The subsequent controlled hosted timely-evidence run accepted `AUTHENTICATED_NEW` / `SMS_CREATED_CANONICAL` and advanced transport-neutral `FALLBACK_SMS` evidence. The watchdog's 300-second silence rule uses the newer of cloud contact and authenticated device evidence; JC1 eligibility requires observation age at most five minutes, future-clock tolerance of one minute, and strictly newer eligible envelope/telemetry/event-time progression. Timely evidence prevented false silence and resolved a real open verification (`AUTHENTICATED_DEVICE_EVIDENCE_TIMEOUT`) through exactly one `CONTACT_RESTORED` event with reason `AUTHENTICATED_FALLBACK_EVIDENCE`; the existing case resolution enum is `DEVICE_CONTACT_RESTORED`. Stale and duplicate envelopes did not refresh evidence. `last_cloud_contact_at` stayed unchanged, and no internet recovery, safety/danger, or present-location conclusion followed. Seven receipts, five authenticated envelopes, five canonical observations, seven reconciliation records, and one resolved case remain as controlled evidence; both synthetic Journeys were closed and their keys/bindings revoked.

The final controlled hosted JC1 V1 completion run used event type `2` (first frame byte `0x12`). A current authenticated completion was `AUTHENTICATED_NEW` / `COMPLETION_APPLIED`, closing Journey monitoring once at its authenticated event time. A replay was `AUTHENTICATED_DUPLICATE` / `DUPLICATE_ENVELOPE`; older completions after newer evidence or normal owner closure were `AUTHENTICATED_NEW` / `COMPLETION_STALE` and did not reopen or rewrite terminal state. A later ordinary observation was retained as canonical history without reopening monitoring or advancing device freshness. SMS receipt time did not replace event time or change `last_cloud_contact_at`; no internet recovery, safety/danger, present-location, or duplicate notification/closure conclusion followed. Eight receipts, seven authenticated envelopes, three canonical observations, and eight reconciliation records remain as controlled evidence; all three synthetic Journeys are closed with revoked keys/bindings. The scoped deterministic hosted Milestone 6 acceptance is complete.

### Durable unknown-outcome checkpoint — 2026-09-26

Room v9 closes the unsafe absent-callback path: `HANDOFF_IN_PROGRESS` becomes durable `UNKNOWN_OUTCOME` after the uncertainty timeout, not automatically sendable `RETRY_PENDING`. Only an explicit retryable sent callback can authorize the single remaining retry. The existing durable claim count limits each logical attempt to two total claims; a second confirmed retryable failure becomes `PERMANENT_FAILURE` / `RETRY_EXHAUSTED`. Recovery and completion preserve ambiguous history; matching late callbacks remain generation-scoped, and a future new sparse observation must independently satisfy newer telemetry, explicit trigger, minimum interval, and rate capacity.

Isolated Redmi acceptance passed the v8-to-v9 migration distinctions, payload/count preservation, on-disk and separate-instrumentation-process restart, no-send callback matrix, recovery/completion history, and sparse rate gating. The test package had a different UID and did not use the production Room database; zero real carrier sends occurred. The worker's extracted dispatch path was exercised with a fake coordinator, but Android WorkManager framework scheduling itself was **not** directly accepted. A later ordering-only test change compiled without a whole-class device rerun after HyperOS blocked reinstall. See `MILESTONE_6_ACCEPTANCE.md`; this does not complete the device failure matrix or carrier/provider acceptance.

### Additive trusted-contact notification slice

Implemented and covered by repository tests without replacing the phone-to-cloud fallback scope:

- contact-owned E.164 number and explicit SMS consent;
- durable, deduplicated Supabase notification outbox;
- transactional enqueue from deterministic verification-case transitions;
- stale verification-started alerts terminalized when a case resolves;
- bounded claim leases and retry policy;
- AWS End User Messaging SMS dispatch from the existing watchdog Lambda;
- minimum-necessary factual message templates and masked logging;
- provider-accepted state distinguished from handset delivery.

Hosted migration `20260918000200_milestone_6_supersede_stale_sms.sql` is applied. Repository evidence does not yet establish physical carrier receipt for this slice.

### Outstanding

- design a production-grade provider-authentication boundary before any live-provider claim;
- complete the remaining physical failure matrix, including permission denial, SMS unavailability beyond accepted selected-SIM loss, both transports unavailable, dual-SIM ambiguity, low battery, actual WorkManager framework scheduling, live delayed/missing/duplicate or ambiguous callbacks, and carrier/provider failures; no-send `UNKNOWN_OUTCOME` behavior is accepted separately, and a live-service restart during SIM loss was not proven;
- complete the additive trusted-contact notification acceptance matrix separately;
- establish production-grade provider authentication and record real carrier-to-provider delivery/retry/latency only if a suitable live route becomes available; these provider-production items may remain explicitly pending for the hackathon when live telecom provisioning is unavailable;
- complete Milestone 6 end-to-end acceptance.

### Acceptance target

The controlled data-bad/SMS-good carrier handoff path and the scoped deterministic hosted Milestone 6 acceptance, including JC1 completion behavior, have passed. Physical selected-SIM loss (D) passed; restoration of the same persisted attempt (G) passed at a test-only no-send pre-claim boundary, not as carrier delivery. Isolated Redmi no-send `UNKNOWN_OUTCOME` and finite-claim behavior also passed, without accepting live WorkManager scheduling or carrier callback reliability. The remaining target covers production-grade provider authentication and real carrier-to-provider delivery/retry/latency; permission denial, other SMS unavailability, both transports unavailable, live ambiguous outcomes, dual-SIM ambiguity, low battery, the rest of the physical telephony failure matrix, and trusted-contact notification receipt. Internet recovery and both unsent supersession and handed-off history retention have passed physically. Milestone 6 remains **IN PROGRESS**.

The additive trusted-contact path must also pass healthy-monitoring silence, real watchdog-driven `VERIFYING`, no stale started alert after case resolution, fresh-contact resolution, offline Journey completion, contact opt-out, and transport failure remaining independent of deterministic monitoring state.

No assumption of emergency causation from connectivity loss.

## Milestone 7 — Alexa+ / MCP Agent

**Status:** PLANNED

### Goal

Provide trusted family members with a natural-language interface to authoritative journey state.

### Expected scope

Bounded tools such as:

```text
get_journey_status
get_last_verified_state
get_verification_history
record_trusted_contact_update
request_traveller_verification
begin_escalation
```

### Agent rules

The agent may explain and coordinate.

The agent may not invent telemetry, rewrite historical evidence, declare kidnapping, declare someone missing, bypass authorization, or expose unauthorized exact location.

### Acceptance target

Test healthy journey, late but healthy journey, temporary internet loss, stale location, conflicting trusted-contact report, unknown state, and unauthorized request.

The agent must be able to say:

> “I do not know.”

## Milestone 8 — Hardening + Product Polish

**Status:** PLANNED

### Goal

Prepare the product for credible demonstration, review, and continued real-world development.

### Expected scope

- threat model;
- authorization review;
- retention/deletion policy;
- battery/OEM testing;
- failure-state UX;
- visual identity;
- typography;
- color system;
- iconography;
- motion;
- accessibility;
- error states;
- demo flow;
- public repository hygiene;
- architecture diagram;
- friction log;
- hackathon submission artifacts.

### Important rule

Visual polish happens after the important product states and interactions are stable enough to deserve a final system.

The default Material theme used during early milestones is not the permanent brand.

## Hackathon Delivery Guardrails

Amazon Build, Ship, Shape submission deadline: **23 October 2026**.

Internal target:

- feature freeze around 18–19 October;
- submission-ready artifacts around 20–21 October;
- final days remain buffer, not feature-development time.

Submission work is part of product work, not a last-night administrative task.

## Definition of Progress

The project is progressing when uncertainty decreases.

Examples: we know the service survives a real lock test, we know what happens when internet disappears, we know whether queued telemetry reconciles correctly, we know how HyperOS behaves, we know whether SMS arrives late, and we know the agent refuses unsupported conclusions.

Feature count is not the primary measure.

Reliable behavior under failure is.
