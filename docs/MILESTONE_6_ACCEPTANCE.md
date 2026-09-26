# Milestone 6 Acceptance Record

Recovery correction, 2026-09-26: the earlier recovery claims below did not prove the durable backlog barrier and must not be used as evidence for that invariant. The corrected case H evidence is recorded below. Physical cases D and G were subsequently accepted at the explicitly bounded no-send boundary recorded at the end of this document. Milestone 6 remains **IN PROGRESS**.

Milestone 6 remains **IN PROGRESS**. Provisioning, protected offline allocation, persistence, physical Android SMS carrier handoff, and authenticated internet recovery were accepted on 2026-09-23. The provider-neutral inbound core and Africa's Talking sandbox adapter are hosted; real production JC1 authentication, duplicate-envelope handling, SMS-first reconciliation, and historical out-of-order acceptance passed on 2026-09-24–25. Subsequent controlled Edge-path tests completed the scoped deterministic hosted acceptance. This does not establish production-provider or complete Milestone 6 acceptance.

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

The controlled data-unavailable/SMS-available success path, selected-SIM loss (D), and restoration of the same pending attempt at a no-send pre-claim boundary (G) are accepted. Remaining physical cases include permission denial, other SMS-unavailable conditions, both transports unavailable, dual-SIM ambiguity beyond explicit selected-SIM loss, delayed or missing callback, duplicate callback, retry and unknown-outcome handling, low-battery behavior, and provider/carrier failure outcomes. These cases must preserve durable attempt identity and must not immediately resend after an ambiguous handoff. A live foreground-service restart while the selected SIM was disabled was not exercised in D.

For the trusted-contact notification slice, the existing healthy-monitoring silence, watchdog-driven verification, restoration, offline completion, opt-out, and transport-failure checklist also remains physically unrecorded. Carrier receipt must not be generalized as guaranteed delivery, human reading, or evidence of traveller safety.

## Cloud-ingestion acceptance

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

These initial assertions are local automated evidence. Migrations `20260924000100` and `20260924000200` were subsequently hosted. Genuine sandbox events and the controlled hosted Edge-path cases below now establish the recorded authentication, failure, reconciliation, timely-evidence, and completion outcomes.

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

### Controlled hosted valid-key Edge-path acceptance — 2026-09-25

All cases below passed through the deployed `africastalking-inbound` Edge Function using isolated synthetic/test Journeys and provisioned test keys. Requests were submitted directly to the function; this run did not use Africa's Talking delivery or alter the production Journey/binding. Every callback used a unique synthetic provider event ID and returned generic HTTP 200 `accepted` after durable handling, without exposing internal or cryptographic failure details.

| Case | Durable classification | Reconciliation |
| --- | --- | --- |
| ACTIVE key, valid binding, valid JC1 | `AUTHENTICATED_NEW` | `SMS_CREATED_CANONICAL` |
| Revoked key | `KEY_REVOKED` | None |
| Revoked binding | `BINDING_REVOKED` | None |
| Invalid synthetic key wrap | `KEY_UNWRAP_FAILED` | None |
| Tampered envelope | `ENVELOPE_AUTHENTICATION_FAILED` | None |
| Internet-first matching observation | `AUTHENTICATED_NEW` | `SMS_MATCHED_INTERNET` |
| Same-sequence conflict | `AUTHENTICATED_NEW` | `SMS_CONFLICT` |

Preservation/freshness invariants passed across all callbacks: no silent overwrite, no duplicate canonical observation, no false authenticated-device freshness, and no verification or notification transition attributable to negative/conflict cases. Original internet observations and receipt timestamps remained unchanged. Negative cases created no authenticated envelope or new canonical observation; matching/conflicting SMS provenance remained attached through immutable reconciliation records. Historical SMS did not overwrite cloud-contact time or infer internet recovery, current location, safety, or danger.

Retain the controlled acceptance evidence: **7 immutable receipts, 3 authenticated envelopes, 3 canonical observations, and 3 reconciliation records**. All four test keys were revoked and all five synthetic Journeys were closed, including one setup-only fixture with no key or inbound receipt. No active trusted-contact access or notification rows remained for these fixtures. These records are acceptance evidence and should not be deleted.

### Hosted timely fallback evidence and watchdog acceptance — 2026-09-25

Two additional controlled synthetic Journeys used normal owner provisioning, authenticated heartbeats, and production-compatible protected JC1 frames sent directly through the deployed `africastalking-inbound` Edge Function. No Africa's Talking simulator delivery or production Journey was involved. The hosted watchdog opened verification naturally for the Journey without timely evidence; test code did not force its monitoring state or invoke the global evaluator.

- A timely protected observation was `AUTHENTICATED_NEW` / `SMS_CREATED_CANONICAL`. It advanced `last_authenticated_device_evidence_at` to the JC1 observation event time and recorded `FALLBACK_SMS` as the transport, while `last_cloud_contact_at` stayed at the original authenticated heartbeat time.
- The hosted watchdog applies a 300-second silence threshold to the newer of last cloud contact and last authenticated device evidence. Eligible fallback evidence must be no older than five minutes at receipt, with at most one minute of future-clock tolerance; provider arrival must satisfy the existing timing bounds. Advancing device evidence also requires an active Journey, increasing eligible envelope and telemetry sequences, and an observation event time newer than existing authenticated device evidence.
- Once both Journeys' cloud contact exceeded 300 seconds, the Journey with timely SMS evidence remained `EVIDENCE_FRESH` with no open verification case. The other reached `VERIFYING` with one open case. That case opened with reason `AUTHENTICATED_DEVICE_EVIDENCE_TIMEOUT`.
- An otherwise valid JC1 older than the five-minute eligibility window authenticated and remained immutable historical evidence but did not advance current evidence or resolve the open verification. Its provider/server receipt time did not substitute for observation event time.
- A new timely JC1 resolved the open case as `DEVICE_CONTACT_RESTORED` and produced exactly one `CONTACT_RESTORED` monitoring event with reason `AUTHENTICATED_FALLBACK_EVIDENCE`. This did not claim internet restoration. A lower-sequence envelope did not regress evidence; a duplicate was `AUTHENTICATED_DUPLICATE` / `DUPLICATE_ENVELOPE` and caused no refresh or repeated transition. A subsequent envelope with strictly newer eligible sequences and event time advanced evidence once.
- No SMS changed `last_cloud_contact_at`, inferred internet recovery, asserted safety or danger, or presented an event-time location as a current location.

Retain the separate controlled evidence from this run: **7 immutable receipts, 5 authenticated envelopes, 5 canonical observations, 7 reconciliation records, and 1 resolved verification case**. Both synthetic Journeys were closed; both test keys and bindings were revoked. No notification rows remained. This is hosted deterministic acceptance, not live-provider latency or handset-delivery acceptance.

### Hosted JC1 completion acceptance — 2026-09-25

JC1 V1 encodes `JOURNEY_COMPLETED` as event type `2`, making the first protected-frame byte `0x12`. The authenticated event time is the Journey completion timestamp. Controlled synthetic/test Journeys and provisioned keys exercised the deployed `africastalking-inbound` Edge Function directly; this run did not use the Africa's Talking simulator or production Journeys.

| Case | Durable classification | Reconciliation and Journey result |
| --- | --- | --- |
| Current, progressive completion | `AUTHENTICATED_NEW` | `COMPLETION_APPLIED`; Journey and monitoring closed with exactly one terminal transition |
| Exact completion replay under a new provider event | `AUTHENTICATED_DUPLICATE` | `DUPLICATE_ENVELOPE`; receipt retained separately, no second terminal transition |
| Older completion after newer accepted evidence | `AUTHENTICATED_NEW` | `COMPLETION_STALE`; terminal state and timestamp unchanged |
| Older completion after normal owner closure | `AUTHENTICATED_NEW` | `COMPLETION_STALE`; no reopening or repeated closure |
| Ordinary observation arriving after completion | `AUTHENTICATED_NEW` | `SMS_CREATED_CANONICAL`; retained as canonical history while Journey and monitoring remain closed, without advancing device freshness |

Completion evidence retains separate authenticated event, provider-arrival, and server-receipt times. SMS receipt did not replace the completion event time or change `last_cloud_contact_at`. No completion or later observation inferred internet recovery, safety/danger, or present location; no duplicate notification or watchdog/closure transition was produced.

Retain the controlled evidence from this run: **8 immutable receipts, 7 authenticated envelopes, 3 canonical observations, and 8 reconciliation records**. All three synthetic Journeys were closed and their test keys and bindings revoked. The scoped deterministic hosted Milestone 6 acceptance is complete.

### Still required

Accepted here: genuine sandbox callback shape/text preservation, active binding/key resolution, real JC1 authentication, SMS-first canonicalization, distinct-provider-event duplicate handling, historical out-of-order retention, and stale-evidence/watchdog safety. Deployed reject-path checks and controlled valid-key, timely-evidence, and completion cases complete the scoped deterministic hosted Milestone 6 acceptance. They do not establish production-grade provider authentication. Still required for production-quality completion:

1. The remaining physical SMS/telephony failure matrix, including permission denial, other SMS-unavailable conditions, both transports unavailable, dual-SIM ambiguity, missing/delayed/duplicate or ambiguous sent callbacks, low battery, bounded retry and unknown-outcome behavior, and carrier/provider failure outcomes. Selected-SIM loss and no-send restoration of the same attempt are accepted separately below.
2. The separate cloud-to-trusted-contact notification physical checklist; inbound traveller fallback does not prove trusted-contact handset receipt or human reading.
3. A production-grade provider authentication and operational route before any live-provider claim. The Africa's Talking sandbox shared URL secret is not a signed provider webhook. Live-carrier/provider delivery, provider failure behavior, and latency acceptance may remain explicitly pending for the hackathon when live telecom provisioning is unavailable.

### Remaining acceptance matrix

| Boundary | Accepted | Still required |
| --- | --- | --- |
| Device and carrier send | Provisioning, Keystore, durable offline JC1, explicit SIM/permission route, one-segment Android `RESULT_OK` handoff, controlled recipient byte equality, recovery and historical handoff retention; selected-SIM loss (D) and same-attempt restoration at a no-send pre-claim boundary (G) | Remaining physical failure matrix: permission denial, other SMS/data unavailability, both transports unavailable, dual-SIM ambiguity, low battery, missing/delayed/duplicate or ambiguous sent callback, retry/unknown outcome, carrier/provider failure outcomes, and no unintended resend; no live-service restart under SIM loss was proven |
| Hosted provider-neutral ingestion | Real sandbox production-JC1 authentication, SMS-first canonicalization, duplicate/historical ordering; deployed reject-path checks; controlled ACTIVE/revoked key/binding and distinct unwrap/envelope failure classifications; internet-first match/conflict; timely fallback evidence preventing false silence and resolving one open verification; current, duplicate, delayed, and post-closure JC1 completion behavior | Scoped deterministic hosted acceptance complete; production-grade provider boundary remains separate |
| Trusted-contact notification | Outbox/supersession implementation and hosted migrations | Physical notification failure/receipt checklist; provider acceptance does not prove handset delivery or human reading |
| Live provider | No production-provider claim | A provider-authenticated live inbound route and operational acceptance; real carrier-to-provider delivery, provider retry/failure behavior, and latency characterization cannot be established with this sandbox and may remain explicitly pending for the hackathon |

Milestone 6 remains in progress pending the physical telephony failure matrix, trusted-contact notification receipt acceptance, and explicit resolution or limitation of production-provider authentication and live carrier-to-provider behavior.

## Corrected recovery barrier / case H — 2026-09-26

Continued the existing dirty worktree on `feat/milestone-6-degraded-connectivity`, initially at HEAD `af49ec8`. Existing recovery implementation, tests, schema export, and the untracked telephony matrix test were inspected and preserved. During physical acceptance, no commit, push, hosted deployment, AWS/provider change, Journey recreation, checkpoint reset, telemetry deletion, or real SMS occurred.

### Contract and durable representation

Before: a successful fresh authenticated heartbeat could independently establish `HEALTHY`, even with an ineligible permanently blocked sync row and checkpoint behind required evidence. Restoring the legitimate owner session alone did not repair that legacy row.

After: validated internet establishes `RECOVERING`. In the same Room transaction as the policy update, capture the maximum local telemetry sequence as a finite target. Only observing `highestTelemetrySequenceSynced >= recoveryTargetTelemetrySequence` satisfies the backlog barrier. Record that observation time durably; require an authenticated heartbeat whose start is strictly later than that time before `HEALTHY` and unsent supersession. Earlier/in-flight heartbeats, worker success, absent worker candidates, ticks, and process recreation cannot bypass the barrier. New telemetry does not move an existing recovery target.

Room v8 adds nullable `recoveryTargetTelemetrySequence` and `recoveryBacklogSatisfiedAtMillis` to `journey_degradation_states`. `MIGRATION_7_8` captures targets for existing active `RECOVERING` rows and revalidates old active `HEALTHY` rows with unsynchronized telemetry. It preserves checkpoint, observations, and attempt history. The barrier time records the first transactional observation of a satisfied checkpoint, conservatively later than or equal to actual checkpoint advancement.

Legacy reactivation requires the exact previous Journey-upsert or telemetry-batch HTTP 403 / PostgREST 42501 safe message, `ERROR`, permanently blocked, no work requested, and no prior legacy reactivation. The existing installation identity must match the current unexpired authenticated session. A normal user-JWT `journeys` read filters by Journey ID and owner ID; server RLS and an exact returned ID/owner comparison establish ownership. The session identity is checked again after the read. A conditional update matches Journey ID, change version, error, and eligibility before reopening work. It preserves the checkpoint and stores original error, original attempt time, and reactivation time in three diagnostic columns. Wrong-owner or failed proof stays blocked; other permanent categories are untouched.

`ReliableSyncEngine` checks these otherwise-ineligible legacy rows before selecting normal work. No eligible candidate returns `Success` only when no outstanding local work remains; blocked outstanding work returns `PermanentFailure`. Neither result controls recovery completion. Current authentication/authorization failures remain retryable and are not connectivity-degradation evidence. The service reconciles current validated network state before heartbeat attempts, including after a cloud failure that did not produce a network callback.

### Physical Redmi evidence

The installed production Journey and saved legitimate owner session were used. No injected replacement owner, fixed-auth backend, RLS bypass, ownership/binding mutation, or synthetic telemetry was used in this run. Test stages ran in separate instrumentation processes; the final transition was performed by the real production foreground service.

| Stage | Observed result |
| --- | --- |
| Baseline | Checkpoint `2`; latest local telemetry `4064`; permanently blocked legacy authorization row; migration revalidated phase to `RECOVERING` |
| Controlled offline interval | Actual Wi-Fi/data disabled; real 180-second policy interval; episode `8`, new attempt `10` allocated; no SMS |
| Validated internet restored | Phase `RECOVERING`; finite target `4064` captured |
| Early authenticated heartbeat | Server success with checkpoint below target; remained `RECOVERING`; attempt 10 remained `ALLOCATED` |
| Separate process / normal sync | Same target retained; verified owner reactivated legacy row; checkpoint advanced `2 -> 4064`; diagnostic history retained |
| Barrier satisfied | `1790381302165` ms (`2026-09-26 00:08:22.165 UTC`); still `RECOVERING`; no allocation replay or early supersession |
| Production process/service restart | Service initially observed persisted `RECOVERING`, target and barrier; WorkManager reported no outstanding work without completing recovery |
| Fresh production heartbeat | Sequence `1653`; attempt time `1790381316072` ms, strictly after barrier; success then transitioned to `HEALTHY` |
| Independent owner-scoped hosted reads | All `4064` local sequences through target present; `0` missing; checkpoint `4064`; no permanent block |
| Attempt history | Attempt 10 became `SUPERSEDED` only after full recovery; attempt 9 remained `SUPERSEDED`, episode 7's sole allocation, with no handoff timestamp; full-row fingerprints of attempt 9, all `HANDED_OFF` rows, and binding unchanged |

Case H's corrected recovery criteria now pass for this controlled physical run. Process restart was exercised before backlog drain and after barrier satisfaction, with the production service performing final recovery. The instrumentation stages deliberately controlled when normal sync ran; this is not a latency or uninterrupted background-scheduling soak test. Wi-Fi and mobile data were restored to their original enabled settings.

### Validation and remaining work

- Focused sync/recovery/scheduling unit tests passed; final full unit suite: **140 tests, 0 failures, 0 errors**.
- Redmi isolated Room/instrumentation: **19 tests passed**, including v7-to-v8 schema validation, preserved legacy block/checkpoint, durable target/barrier across database reopen, fallback suppression, and handed-off history preservation.
- Five selected physical stages passed: baseline, controlled offline episode, early heartbeat rejection, backlog sync after process restart, and production-service/hosted verification. The alternate test-driven final-heartbeat stage was not needed or run.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` passed on the final source; lint reported **0 errors, 18 warnings**.
- The interrupted Room test double's missing `provision` method was completed. A verification-only query was corrected to use explicit `and` for two bounds on the same column: this SDK otherwise emits only the first bound. The corrected hosted check passed.
- `git diff --check` and a credential-pattern scan of changed/new files passed. No credential values were added to the record.
- No remaining defect was observed in the corrected recovery acceptance. At the time of case H, physical cases **D and G remained outstanding**; their subsequent bounded acceptance is recorded below. The wider physical telephony matrix, notification receipt, and provider limitations remain separate. Milestone 6 is not complete.

### Exact changed/new worktree files

Paths below record the acceptance-time worktree, including the preserved interrupted-session changes, not just edits made during continuation.

Modified:

```text
app/src/androidTest/java/com/journeycontinuity/app/data/local/FallbackAttemptDatabaseTest.kt
app/src/androidTest/java/com/journeycontinuity/app/degraded/Milestone6RedmiAcceptanceTest.kt
app/src/androidTest/java/com/journeycontinuity/app/degraded/Milestone6SmsCarrierAcceptanceTest.kt
app/src/main/java/com/journeycontinuity/app/JourneyContinuityApplication.kt
app/src/main/java/com/journeycontinuity/app/data/local/JourneyDatabase.kt
app/src/main/java/com/journeycontinuity/app/data/local/JourneyDatabaseMigrations.kt
app/src/main/java/com/journeycontinuity/app/data/local/JourneyDegradationStateEntity.kt
app/src/main/java/com/journeycontinuity/app/data/local/JourneySyncStateEntity.kt
app/src/main/java/com/journeycontinuity/app/data/local/SyncStateDao.kt
app/src/main/java/com/journeycontinuity/app/degraded/DegradedConnectivityIntegration.kt
app/src/main/java/com/journeycontinuity/app/degraded/DegradedConnectivityPolicy.kt
app/src/main/java/com/journeycontinuity/app/service/JourneyForegroundService.kt
app/src/main/java/com/journeycontinuity/app/sync/ReliableSyncEngine.kt
app/src/main/java/com/journeycontinuity/app/sync/RoomLocalSyncStore.kt
app/src/main/java/com/journeycontinuity/app/sync/SupabaseCloudSyncGateway.kt
app/src/main/java/com/journeycontinuity/app/sync/SyncContracts.kt
app/src/test/java/com/journeycontinuity/app/degraded/DegradedConnectivityCoordinatorTest.kt
app/src/test/java/com/journeycontinuity/app/degraded/DegradedConnectivityPolicyTest.kt
app/src/test/java/com/journeycontinuity/app/sync/ReliableSyncEngineTest.kt
docs/MILESTONE_6_ACCEPTANCE.md
```

New/untracked at acceptance review:

```text
app/schemas/com.journeycontinuity.app.data.local.JourneyDatabase/8.json
app/src/androidTest/java/com/journeycontinuity/app/data/local/RecoveryBarrierDatabaseTest.kt
app/src/androidTest/java/com/journeycontinuity/app/degraded/Milestone6RecoveryBarrierAcceptanceTest.kt
app/src/androidTest/java/com/journeycontinuity/app/degraded/Milestone6TelephonyFailureMatrixTest.kt
```

At the time of case H, the telephony matrix file was inspected and preserved without editing or running its separate physical cases. Subsequent D/G changes and results are recorded below.

## Physical selected-SIM loss (D) and same-attempt transport restoration (G) — 2026-09-26

### D — SELECTED SIM LOSS: PASS

- With both SIMs initially active, JOURNEY's explicit choice was Android subscription `1`, mapped to physical SIM slot 2. The user manually disabled only MTN / SIM 2 through HyperOS's visible **Turn on** switch; no ADB UI injection, undocumented telephony shell command, or airplane-mode surrogate was used.
- Android's active-subscription list then contained only the other subscription (`3`, slot 1). The stored JOURNEY choice remained `1`; it was not replaced by the remaining SIM. Production SMS status reported the selected SIM unavailable. With a test-only configured route, the production status evaluator over the real subscription list rejected handoff before the fake telephony gateway's message-division method was called.
- The existing episode-9 attempt `11` remained `ALLOCATED`, with handoff generation `0`, zero transport attempts, no claim, and no handoff timestamp. The complete attempt-ledger fingerprint stayed `074d201bb68705ff1291041d845b2a2a2d1f9b2db2d083da885e9d6750e8628a`. Its persisted JC1 was 102 characters with SHA-256 `c6f3de01b1b00b6f419ccce3769eb119bb23c1bf9e4e0ef4dad9293a26385c03`; the nonce fingerprint and envelope sequence `11` remained unchanged. The next-envelope counter stayed `12`, and the attempt count stayed `11`.
- Repeated ordinary time advances and a telemetry-observation event did not allocate another attempt or advance cloud-success freshness. No safety, danger, or current-location conclusion was inferred from SIM loss. Separate instrumentation processes reopened Room and repeated the disabled-SIM assertions successfully. The production foreground service was already stopped, so **a live-service restart under SIM loss was not proven**.
- No real SMS was sent.

### G — TRANSPORT RESTORATION FOR EXISTING ATTEMPT: PASS AT NO-SEND PRE-CLAIM BOUNDARY

- The user restored MTN / SIM 2 with the same HyperOS switch. Android again reported subscription `1` active alongside the other SIM, and JOURNEY's explicit selection remained `1`.
- A test-only configured-route probe read the real active-subscription state and the production Room attempt store. The same pending attempt `11` became eligible; its **exact persisted JC1 text** and selected subscription reached a fake telephony gateway. That gateway stopped at message division **before claim and before `SmsManager`**; its send method cannot invoke the carrier. No new JC1 or fallback attempt was allocated.
- The logical attempt, exact protected text, envelope sequence `11`, nonce, payload digest, full-row/ledger fingerprint, generation `0`, and next-envelope counter `12` remained unchanged. The production SMS destination remains deliberately unconfigured. **This is not carrier-delivery acceptance.** No real SMS was sent.

Regression coverage is in `FallbackHandoffCoordinatorTest`, `FallbackAttemptDatabaseTest`, and `Milestone6TelephonyFailureMatrixTest`. Focused D/G instrumentation and handoff unit tests, full `testDebugUnitTest`, `assembleDebugAndroidTest`, and `lintDebug` passed (lint: 0 errors). `git diff --check` and a sensitive-value scan passed; existing E.164 literals in the first two tests are synthetic test-only fixtures. Both SIMs, Wi-Fi, and mobile data were restored on. The foreground service was stopped before and after this run; normal service resumption was not claimed.

### Exact remaining Milestone 6 acceptance boundaries after D/G

1. **Device/telephony:** permission denial; SMS unavailability other than the accepted selected-SIM-loss case; both transports unavailable; dual-SIM ambiguity beyond explicit selection loss; low battery; delayed, missing, duplicate, or ambiguous sent callbacks; bounded retry and unknown-outcome behavior without unintended resend; and carrier/provider failure outcomes. A live foreground-service restart during selected-SIM loss remains unproven.
2. **Trusted-contact notification:** physical watchdog/verification, restoration, offline completion, opt-out, transport-failure, and handset receipt checklist. Provider acceptance is not handset delivery or human reading.
3. **Production-provider authentication:** establish and accept a production-grade authenticated inbound-provider boundary. The shared-secret Africa's Talking sandbox callback is not that boundary.
4. **Live carrier-to-provider path:** real delivery, retry/failure behavior, and latency characterization remain unproven by the no-send G probe or controlled Edge-path tests; record an explicit limitation if a live route is unavailable.

Milestone 6 remains **IN PROGRESS**. No AWS/provider configuration change or hosted deployment accompanied D/G.
