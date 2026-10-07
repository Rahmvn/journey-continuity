# Alabarin Probable End Contract v1

**Status:** LOCKED product and engineering contract. Section 09 Slice 1 implements only the pure detector and evidence policy.
**Date:** 2026-10-07
**Detector policy:** `alabarin-probable-end-v1`
**Baseline:** `cf2b7fd6e1e5cdaef70bd74f9203be9a1d6a0e28`

## Authority and scope

Read together with:

- [Evidence and Remote Uncertainty Rules](ALABARIN_EVIDENCE_AND_REMOTE_UNCERTAINTY_RULES.md)
- [Traveller v1 Closure Plan](ALABARIN_TRAVELLER_V1_CLOSURE_PLAN.md)
- [Structured Destination Contract v1](ALABARIN_STRUCTURED_DESTINATION_CONTRACT_V1.md)
- Phase 5 reconciled decision ledger: [Journey User Flows, sections 2.13, 2.14 and 3.11](JOURNEY_USER_FLOWS.md), and [Journey UX States, L05/L06](JOURNEY_UX_STATES.md)

This contract records the subsequent Section 09 locks, including the explicit evidence thresholds and confirmation-window origin. Older descriptions of those details as deferred do not reopen these decisions. Historical implementation-status statements are not evidence that later capabilities exist.

Slice 1 adds a pure Kotlin reducer, local geographic helper, typed evidence models and JVM tests. It does not wire the detector into monitoring. Room, cloud, verification projection, prompts, notifications, deadlines, terminal mutations and Section 10 remain unimplemented by this slice. No migration or deployment is required.

## LOCK NOW: meaning and eligibility

Probable End is **SYSTEM INFERENCE**. It means only:

> There is enough qualifying Journey-specific device evidence to justify asking whether the Journey ended.

It does not establish arrival, safety, destination attainment, restored contact, Traveller confirmation or a Trusted Contact report. A device near the destination is not arrival proof. Cloud acknowledgement is not Probable End evidence. Device silence alone is never Probable End evidence. Poor connectivity is neither danger nor Journey completion. Last verified is not current.

Eligibility requires:

- `ACTIVE` Journey, valid Journey identity and positive start timestamp;
- complete valid immutable structured destination;
- destination kind `POINT`, with an authoritative coordinate and the existing required administrative/boundary/provider evidence;
- selection source `MAP_PIN` or `PLACE_SEARCH`.

Both sources use exactly the same geographic policy once they supply an authoritative POINT. Reuse `StructuredDestinationValidator`; do not validate historical snapshots against a newer administrative catalogue. The immutable creation-time Journey destination is authoritative, not a new provider response.

`LOCALITY`, legacy free text, malformed/partial/contradictory evidence and terminal Journeys are ineligible. Do not invent a locality centroid, use an LGA as a proximity zone, or geocode legacy text. Ineligibility never ends a Journey or interferes with ordinary monitoring or explicit Traveller controls.

The pure `ProbableEndJourney.read` boundary accepts a destination decoder and converts its validation/decoding `IllegalArgumentException` into `INCOMPLETE_DESTINATION`, without exposing the exception text. A later persistence adapter must reconstruct corrupt/unknown destination evidence inside this boundary and distinguish genuinely absent legacy evidence from incomplete structured fields. `from(Journey)` is the convenience path for an already validated domain model. This slice changes no Room reconstruction behavior.

## LOCK NOW: geographic and accuracy policy

Use deterministic Haversine distance in metres, with fixed mean Earth radius **6,371,008.8 m**. Validate finite latitude in `[-90, 90]` and longitude in `[-180, 180]`, wrap longitude differences through the short arc, and clamp the Haversine intermediate to `[0, 1]`. This is spherical distance, not a survey-grade ellipsoidal calculation.

No map-provider/network call, polygon, rectangular coordinate threshold or additional geography dependency is involved.

| Parameter | v1 value |
| --- | --- |
| Destination proximity radius | 100 m |
| Maximum horizontal accuracy | 50 m |
| Maximum observation age at evaluation | 90 s |
| Maximum future skew | 30 s |
| Maximum consecutive evidence gap | 90 s |
| Minimum proximity observations | 4 |
| Minimum observed proximity span | 180 s |
| Minimum reliable-exit observations | 2 |
| Minimum observed exit span | 60 s |

A geographically qualifying observation requires finite `accuracy > 0`, `accuracy <= 50`, and:

```text
distanceMeters + accuracyMeters <= 100
```

Poor accuracy cannot expand the qualifying area. Android horizontal accuracy is not guaranteed containment; this arithmetic is a conservative inference policy, not physical proof.

A reliable-exit observation requires the same coordinate/accuracy/freshness validation and:

```text
distanceMeters - accuracyMeters > 100
```

Equality is not reliable exit. The nominal uncertainty region must no longer overlap the same 100 m radius. No separate outer radius is introduced. Reliable departure remains device evidence, not proof about Traveller movement or possession of the device.

## LOCK NOW: observed time and continuity

Every evaluation takes an explicit `evaluatedAtMillis`. The detector never reads wall clock internally. Evidence qualification uses observation `eventTime`, not upload/processing time.

Reject qualification when:

- `eventTime <= 0`;
- observation precedes Journey start;
- age exceeds 90 seconds at evaluation;
- observation is more than 30 seconds ahead of evaluation time;
- coordinate, accuracy, observation identity or Journey binding is invalid.

Exact age 90 seconds and future skew 30 seconds are allowed. Event time equal to Journey start is allowed. Age and observed-span calculations use milliseconds. Rejected telemetry remains historical evidence; the detector cannot delete it.

Require at least four distinct, strictly advancing qualifying observations, observed span at least 180 seconds, and no consecutive gap above 90 seconds. The final observation must be fresh at evaluation. All observations must satisfy proximity and accuracy policy. A single fix never raises a candidate. At the current desired 30-second production location cadence, an uninterrupted sequence normally needs at least seven fixes to span three minutes; requested cadence is not a delivery guarantee.

A nonqualifying new observation clears both streaks; historical replay is ignored as described below. Poor accuracy, invalid data, stale new evidence or an ambiguous outside fix resets proximity accumulation. A reliable-exit fix clears proximity and starts/continues its separate exit streak. A return to qualifying proximity clears the exit streak. A gap above 90 seconds clears the old streak and seeds a new streak with the current qualifying observation; the result reports `RESET / EVIDENCE_GAP` with that one-fix state.

Reliable exit requires at least two distinct, advancing, fresh exit observations spanning at least 60 seconds with no gap above 90 seconds. One outside fix is insufficient. No GPS-jump smoothing is used: noisy evidence may delay the question.

### Current timing limitation

Current telemetry persists Android `Location.time` as wall-clock event time, plus Journey-local sequence. It has no persisted elapsed-realtime acquisition timestamp or boot identity. Wall-clock corrections can distort apparent dwell/freshness; replay checks and age/skew limits reduce mistakes but cannot prove real elapsed physical time after clock changes. This limitation applies to legacy and current persisted observations. Do not manufacture monotonic metadata for them. A later telemetry decision may add actual Android acquisition elapsed time with boot identity; no schema change or invented metadata is part of Slice 1.

## LOCK NOW: replay identity and reducer behavior

Use Journey-local telemetry sequence together with observed time. Room row ID is not physical-acquisition identity. A deterministic SHA-256 fingerprint covers Journey ID, observed time, canonical latitude/longitude binary representation and horizontal accuracy. It excludes row ID, sequence, battery and connectivity. Zero and equivalent `-180/+180` longitude are canonicalized.

Within a retained detector state:

- a sequence at/below the consumed sequence watermark cannot advance evidence;
- observed time at/below the advancing event-time watermark cannot advance evidence;
- the same physical fields/time under a new row/sequence retain the same fingerprint;
- consumed sequence IDs return `IGNORED / DUPLICATE` before freshness/quality checks; old replay must not destroy newer evidence;
- a new sequence with a positive, non-pre-Journey but non-advancing observed time also returns `IGNORED`, with no count/dwell advancement and no candidate/departure emission;
- identical coordinates/accuracy at strictly advancing observed times and sequences remain distinct evidence; event time is part of the fingerprint;
- genuinely new advancing stale/future-dated evidence, invalid event times and other invalid new input reset accumulation;
- sequence watermarks are retained through resets, including for rejected telemetry;
- rejected future timestamps do not advance the observed-time watermark and cannot poison subsequent valid times.

Reevaluating an expired latest fix returns `IGNORED`, never a new candidate or additional dwell. The next new observation must still satisfy freshness and the consecutive-gap limit. Ignoring replay therefore neither manufactures continuity nor erases a valid newer streak.

Without an acquisition identifier/elapsed time, physically identical acquisitions with different event timestamps and different sequences are indistinguishable from genuinely new fixes. Do not claim otherwise. Fingerprints are evidence identifiers, not anonymization or permission to publish telemetry.

The reducer accepts immutable prior state and returns new state; no singleton mutable history exists. State binds to Journey ID, Journey start, immutable destination reference and policy version. A different binding starts empty. The existing destination-immutability contract must hold: replacing a POINT within the same Journey is not supported.

Typed outcomes are:

- `INELIGIBLE` with a typed eligibility reason;
- `ACCUMULATING` with proximity/exit kind and streak progress;
- `QUALIFYING_CANDIDATE` with provenance summary;
- `RESET` with typed reason and cleared/reseeded state;
- `IGNORED` for replay/non-advancing time;
- `RELIABLE_DEPARTURE` with exit evidence summary.

Reasons distinguish locality, legacy, incomplete destination, terminal/invalid Journey, Journey mismatch, invalid observation identity, coordinate, accuracy, evaluation/event time, stale/future/pre-Journey, duplicate, non-advancing time, gap, outside proximity and reliable departure. There is no `AWAITING_RESPONSE` here: a candidate has no authoritative verification clearance.

## LOCK NOW: provenance, privacy and future persistence

Candidate/exit summaries retain:

- Journey ID and detector policy version;
- reference to that Journey's immutable creation-time structured destination slot;
- evidence kind;
- first/final telemetry sequence, observed time and deterministic fingerprint;
- qualifying count and observed span;
- final computed distance and accuracy.

No raw location history, provider response or coordinate pair is copied into summary/state/result models. `ProbableEndJourney.toString()` is redacted; other new models retain only references, hashes, times, counts and distance/accuracy scalars, so their generated representations do not include raw coordinates. Geographic validation errors contain no input values. Existing telemetry models are not changed and must not be logged indiscriminately by future integration.

The summary references the existing evidence range; intermediate observations remain in existing telemetry. Later persistence/retention must ensure references remain meaningful, without duplicating raw histories unnecessarily. The eventual durable episode separately records its raised/presented timestamps, authoritative verification clearance/version, response, invalidation and deadline; candidate creation is not presentation.

`sequenceKey` is stable for the policy/Journey/evidence-kind/first-fingerprint of one uninterrupted sequence. Later accepted observations may refresh a candidate summary under the same key. They do not authorize independent episodes/prompts. Persistence must freeze the evidence used to raise each episode, upsert idempotently and serialize concurrent evaluation. Compose/service recreation and sync replay must read the same episode. Restoring empty state and replaying telemetry is not adequate duplicate suppression.

The reducer does not provide durable recovery, concurrency coordination or Keep-monitoring suppression by itself. Later slices must persist state/watermarks and an episode lifecycle atomically. Policy changes require a new explicit identifier; already-raised evidence/deadlines retain the policy that produced them.

## LOCK NOW, later slices: verification and offline behavior

Local candidate detection continues while offline and across HEALTHY, INTERRUPTED, DEGRADED and RECOVERING connectivity. Cloud availability is not a detector input. Cloud acknowledgement/connectivity state cannot establish evidence qualification or verification clearance.

Reuse authoritative verification runtime/case evidence rather than create a second independent definition of an unresolved case. Current server `VERIFYING` runtime/open case requires blocking; stale/cached/unknown local verification knowledge cannot establish clearance. A current-looking `EVIDENCE_FRESH` snapshot alone cannot prove that no intervening episode occurred. The next guard contract must account for verification episode/history/version and authoritative resolution.

- Unknown verification never means clear.
- No actionable Probable End prompt and no automatic-stop clock begin until authoritative verification guard clearance exists.
- If an existing prompt's guard later becomes unknown, explicit Traveller responses remain possible, but `AUTO_ENDED` cannot finalize.
- An intervening verification episode invalidates the automatic-end episode and cancels its deadline. Keep the Journey ACTIVE; do not pause/resume remaining minutes.
- After authoritative resolution, require a completely fresh qualifying sequence of observations occurring after that resolution. Old evidence cannot revive the invalidated episode.
- Guard knowledge, invalidation, episode identity and evidence lower bounds must survive process death/restart. Offline recovery must remain fail closed; a cached clear value is not perpetual clearance.

## LOCK NOW, later slices: opportunity, response and terminal behavior

The question is **"Has this Journey ended?"** Actions are **"Journey completed"** and **"Keep monitoring"**.

The 30-minute confirmation window starts at the **first durable genuine confirmation opportunity**, consisting of either:

1. foreground Probable End prompt actually presented; or
2. dedicated Probable End notification successfully posted.

Silent candidate creation does not start it. If backgrounded and notification presentation is unavailable, do not start the clock. Once started, persist one absolute episode-specific deadline; process/UI recreation never restarts it.

**Keep monitoring** is an explicit Traveller response. Permanently suppress that exact episode, cancel its deadline and clear accumulation while retaining replay protection. Require reliable exit evidence observed after the response, then a completely new qualifying return/dwell sequence. Do not use a time-only cooldown. A verification episode in the meantime adds its own fresh-evidence requirement; resolution does not undo Keep-monitoring suppression or bypass reliable exit. Recovery must preserve both constraints and their observed-time lower bounds.

**Back/dismiss** is not a response. It may hide the sheet, but pending episode/deadline remains; returning to the relevant Journey surfaces the same question, subject to authoritative verification/invalidation rules.

**Journey completed** is explicit Traveller completion. Reuse the authoritative existing completion path in the future UI integration, preserving monotonic terminal state, monitoring teardown, normal sync and immutable destination evidence. Record Probable End episode/response provenance separately; do not relabel it as device-proved arrival or safety.

**AUTO_ENDED** is a distinct future terminal operation after no Traveller response for 30 minutes, allowed only while the unresolved-verification guard remains authoritatively clear and the episode remains valid. Never reuse `COMPLETED` or manufacture Traveller confirmation. It means only that monitoring ended automatically under recorded Probable End policy, not arrived, safe, destination reached or contact restored. Unknown verification blocks finalization; intervening verification cancels the episode rather than resuming a remaining timer. No autonomous server-only action may ignore a possible unsynced local response.

## LOCK NOW, later Section 10: separate unresolved branch

Probable End and genuine unresolved verification are separate, mutually exclusive branches. Unresolved verification blocks Probable End promotion and `AUTO_ENDED`. There is no transition `AUTO_ENDED -> UNRESOLVED_INCIDENT`.

Section 10 later owns the genuinely unresolved ACTIVE Journey boundary at expected arrival plus 24 hours, with `UNRESOLVED_INCIDENT` stopping continuous monitoring/fallback/timers while preserving uncertainty and evidence. It does not mean safe, missing, kidnapped, danger or resolved. Silence alone never establishes why a Journey is unresolved.

Section 09 must expose episode invalidation, guard state and evidence lower bounds without resolving cases or consuming Section 10's terminal responsibility. This slice implements no Section 10 mechanism.

## DEFER: implementation work, not reopened product choices

- Room detector/episode schema and migrations, atomic update/concurrency strategy and restart/reboot recovery.
- Authoritative verification guard projection, episode/history/version recovery and observation lower-bound enforcement around responses/resolution.
- Foreground/notification presentation receipts, absolute deadline scheduling, explicit response integration and distinct AUTO_ENDED terminal operation.
- Cloud evidence/episode representation and reconciliation, preserving system inference separately from Traveller response; no cloud schema is required for this pure slice.
- Any actual elapsed-realtime acquisition metadata/schema extension, with explicit handling of legacy records and boot boundaries.
- Evidence-reference retention and physical Redmi acceptance through isolated location-source injection after production integration exists.
- Section 10 implementation and its own acceptance.

No additional human threshold or product-semantic approval is required before Slice 2 on this contract. Durable adapter/schema and verification interfaces still need review within their authorized slices; this document does not claim they already exist.

## NOT NEEDED FOR V1

Approach scoring, speed/bearing thresholds, stationary classification, movement prediction, GPS smoothing, provider-network distance calls, LGA arrival polygons, legacy geocoding and time-only Keep-monitoring cooldowns. Proximity continuity is sufficient evidence to ask; it is never proof of arrival.

## Slice 1 validation boundary

Focused deterministic JVM coverage must exercise eligibility, malformed decoding, geographic distance/wrapping/boundaries, accuracy arithmetic, observed-time freshness/skew, distinct count/dwell/gaps, resets, replay, reliable exit, policy binding, connectivity independence and coordinate-safe model output. Run relevant Journey/domain/location regressions and `testDebugUnitTest`.

Unit success validates the pure policy only. Room recovery, authoritative guard behavior, deadlines, UI/notification presentation, Redmi and hosted behavior require their later integration/acceptance slices.

## Slice 2: durable local implementation boundary

Slice 2 adds Room v13 through additive `MIGRATION_12_13`. The earlier Slice 1 scope descriptions remain historical. This implementation persists **local qualifying candidates only**; there is no authoritative guard projection, actionable episode, presentation, deadline, notification, AUTO_ENDED or Section 10 behavior.

`probable_end_detector_states` has one Journey-keyed row with policy/start binding, generation, consumed sequence, sequence/event-time evidence floors, the advancing observation reference/fingerprint, flattened proximity/exit streak references/counts, a reliable-exit requirement, revision and last evaluation wall time. Observed spans derive from first/final acquisition times. Current distances/accuracy can be recomputed locally from referenced telemetry; they are frozen explicitly in candidate provenance. No coordinate history is duplicated.

`probable_end_episodes` freezes the first sufficient candidate evidence, creation time, policy, SYSTEM_INFERENCE class, destination slot reference, generation/floors and deterministic episode identity. Local lifecycle values are CANDIDATE, INVALIDATED and SUPPRESSED. Closure records a time/reason without rewriting the evidence snapshot. Unique Journey/generation and Journey/live-slot indexes prevent duplicate episodes and simultaneous live candidates. Both tables use Journey foreign keys with NO ACTION deletion, matching retained telemetry; terminal handling preserves inference history.

Telemetry and its existing sync request commit first. Optional inference then reads at most 128 observations beyond the durable cursor per transaction. Cursor/streak updates and episode creation/closure commit together in a separate Room transaction. SQLite write transactions and uniqueness constraints provide correctness across competing store/database instances; no in-memory mutex is the correctness boundary. Application/service restoration drains incremental batches in separate background jobs, rather than ahead of the location persistence channel. The per-fix observer consumes at most one batch, so a long historical backlog cannot require an unbounded drain before the next telemetry write. A crash before the inference commit leaves observations pending; a crash after it resumes beyond the committed cursor. No elapsed restart time contributes to dwell.

Episode IDs are deterministic name UUIDs over the generation and Slice 1 sequence key. Later qualifying observations preserve the original live candidate and its exact frozen evidence, not a second candidate. Two fresh reliable-exit observations invalidate it, advance generation/floors through the accepted departure evidence and clear streaks. Subsequent observations must cross both durable floors before contributing to a new candidate. Historical replay below a floor advances only the consumed storage cursor, never evidence.

An internal, unwired suppression persistence primitive closes only the identified candidate, rebases beyond already persisted telemetry and the accepted event-time boundary, and requires reliable exit followed by new return/dwell. It is not a Traveller action implementation. Future verification and response integration must supply authoritative boundaries; neither connectivity nor this local primitive provides guard clearance.

Malformed restored state/candidate, missing state with historical episodes, or a detected evaluation-clock rollback invalidates local live candidates and establishes a conservative new generation at the current telemetry tail. This deliberately quarantines the entire existing prefix, including unevaluated rows, because its continuity can no longer be trusted; it neither deletes telemetry nor excludes later arrivals. Recovery clears streaks and requires fresh reliable exit before return evidence. Event floors include recovery time and stored observation times within the normal future-skew limit; rejected far-future telemetry and invalid detector fields cannot poison future eligibility. Only a validated prior state's floor is retained. During clock rollback its last evaluation watermark remains authoritative until the clock catches up. Partial nullable embedded-state decoding failures also recover this way. Unrecoverable database failures or exhausted generation/revision counters leave inference unavailable, without throwing ordinary errors through primary telemetry persistence. Cancellation retains normal coroutine lifecycle semantics. Candidate creation conflicts roll back the inference transaction and can converge on retry; they never roll back telemetry.

The raw persisted destination is validated independently for inference. The foreground service observes the original ACTIVE Room row for identity and its existing notification label, without decoding structured destination evidence. The shared repository Journey stream, shared mapper and cloud mapping remain strict. There is no fallback Journey with a null structured destination, no conversion of corruption into valid legacy meaning, and no persisted destination normalization or repair. Ineligible/corrupt evidence cannot terminalize a Journey. Optional inference initialization/restoration failures are contained at application/service entry points as well as the repository boundary.

Wall-clock and physical-acquisition identity limitations above still apply. Persisting evaluation time detects backward evaluation clocks; it is not reboot-safe monotonic acquisition evidence. Slice 4/device-source integration may later supply real elapsed-realtime acquisition/boot metadata under a separate telemetry decision. Historical rows never receive fabricated timing metadata. A retained local candidate is historical inference, not permission to present an old question: future guard/presentation integration must check current qualifying evidence and all intervening episode boundaries.

Slice 2 validation covers JVM restoration/boundary/privacy models and real Room migration, reopen, replay, concurrent consumers, fault-trigger rollback, suppression/invalidation, corruption and Journey isolation using disposable local databases. It does not establish authoritative verification, presentation, timers or hosted acceptance. The Room/schema work listed under DEFER above is implemented by Slice 2; guard/response/deadline/cloud/Section 10 work remains deferred.

## Slice 3A: authoritative verification guard foundation

Slice 3A adds the repository migration `20261007000100_probable_end_verification_guard.sql` and an authenticated Android gateway. The implementation slice did not deploy it; the separately authorized hosted deployment gate applied it on 2026-10-07 and completed hosted runtime acceptance as recorded below. Room remains v13. Local CANDIDATE remains non-actionable: no candidate promotion, presentation, notification, confirmation opportunity, deadline, AUTO_ENDED or Section 10 operation is implemented.

The sources of verification truth remain `journey_monitoring_state`, `verification_cases`, authenticated heartbeat/eligible fallback evidence, monitoring events and Journey status. Trusted Contact reports do not resolve verification. Latest `CloudMonitoringState.phase` alone cannot establish clearance or reveal a verification episode that opened and resolved between reads.

Existing monitoring events have bigint identity IDs, but case opening accepts a supplied event reference without requiring a new unique reference per case. Those IDs alone do not cover every permitted opening. The existing monitoring-state row therefore gains `verification_guard_version` and immutable `verification_guard_baseline_at`. Database triggers advance the version on entry to VERIFYING and on each OPEN case insertion/reopening, independently of event ID. The canonical watchdog's phase entry plus case insertion can advance it twice: this is an opaque history version, **not an episode count**. Concurrent increments serialize on the monitoring row and commit with the originating verification transaction. Resolution/fresh heartbeat never decreases it. Android cannot write it.

A case BEFORE INSERT/UPDATE hook acquires the monitoring row before an insertion reserves the unique OPEN-case slot. This matches the watchdog's state-then-case insertion order. The AFTER hook increments only for an actual OPEN insertion/reopening; ON CONFLICT DO NOTHING and OPEN-to-OPEN updates do not advance it. Failed inserts roll back their work; no loop/recursive case write is introduced. Reopening also advances the cursor when phase was already VERIFYING. Ordinary fresh heartbeat, duplicate heartbeat and unrelated state updates do not advance it. Guard versions are compared only as greater/equal/less; their numeric difference has no incident-count meaning.

Existing rows initialize at version zero and migration transaction time. The timestamp is an immutable migration epoch marker, not a historical event cursor or proof that every transition since that wall-clock instant was counted. Counter continuity is authoritative between successful reads after the migration commits, with the same baseline; activity before installation/commit is not retroactively counted. Rows created later use their creation transaction's epoch; neither value is a retrospective CLEAR receipt. No older verification episode is reconstructed, counted or fabricated. Retained pre-migration events/case resolution times remain usable evidence boundaries. Missing monitoring state is BLOCKED, with no invented version; no prior CLEAR is possible without that row. Existing APIs retain monitoring rows and Android cannot delete/replace them. A changed baseline or regressed version is inconsistent and requires fail-closed reconciliation, never reuse of earlier clearance.

`get_probable_end_verification_guard_v1(p_journey_id)` is a SECURITY DEFINER RPC with empty search_path, executable only by `authenticated`, with explicit exact `auth.uid()` ownership. Wrong-owner and absent Journey IDs share the established 42501 error. Unauthenticated `anon` has no EXECUTE permission. Supabase anonymous-auth owners also fail closed: the RPC requires `auth.users.is_anonymous IS FALSE`, reusing the Auth system of record already used by Traveller identity RPCs. This guard-only restriction does not change existing Journey admission, heartbeat authorization or monitoring for legacy owners, and never establishes a new owner.

The RPC returns only Journey identity/ACTIVE fact, CLEAR or BLOCKED, bounded reason, verification knowledge (CLEAR/UNRESOLVED/UNKNOWN), version, history baseline, evidence-not-before boundary, server evaluation time and a freshness upper bound for CLEAR. It returns no locations, destination, case IDs/content, actor details or provider metadata. All decision inputs share one MVCC statement snapshot. A concurrent opening/resolution is visible either before or after its atomic commit; a CLEAR read is **point-in-time knowledge, not a lease** against future changes.

CLEAR requires all of: exact owner; ACTIVE Journey; present, owner-consistent EVIDENCE_FRESH monitoring with no verifying/closed marker; no OPEN case or mismatched case owner; valid represented version/baseline; finite, present and source-consistent authenticated evidence metadata; no future evidence/receipt/baseline/barrier; and direct evidence age **strictly less than 300 seconds**. Exactly 300 seconds is stale under the existing watchdog rule. The guard and watchdog call the same private timeout helper; the watchdog's existing transition/resolution semantics otherwise remain unchanged. Freshness uses the existing greatest(last_cloud_contact_at, last_authenticated_device_evidence_at): cloud heartbeat is server-received time, eligible authenticated fallback retains its accepted observed time. Authenticated evidence must represent that latest instant; CLOUD_HEARTBEAT evidence/receipt must match cloud contact, as maintained by the existing synchronization trigger. FALLBACK_SMS requires the envelope/telemetry sequence metadata maintained by authenticated fallback ingestion. The guard trusts that existing restricted server path, not an arbitrary SMS or upload timestamp. Raw telemetry upload, connectivity and contact reports do not refresh it.

BLOCKED reasons are JOURNEY_NOT_ACTIVE, MISSING_MONITORING_STATE, INCONSISTENT_STATE, VERIFYING, OPEN_VERIFICATION_CASE and STALE_AUTHENTICATED_EVIDENCE. Any phase other than the explicitly supported EVIDENCE_FRESH is denied. Missing/inconsistent metadata never supplies clearance. Direct freshness assessment works even when watchdog execution is delayed; the read itself neither opens nor resolves verification. `fresh_until` bounds evidence freshness only and cannot promise absence of a later case opening.

The evidence-not-before boundary is the greatest of guard baseline, retained case opening/resolution times and VERIFYING_STARTED/CONTACT_RESTORED event times. Future integration must combine this with durable local sequence/generation floors and fresh post-resolution observations; wall-clock equality alone is not episode identity. A higher version permanently invalidates a previously bound episode cleared under an earlier version even when the new decision is CLEAR. The same version cannot revive evidence already invalidated/suppressed locally.

**LOCK NOW: UNBOUND CANDIDATE RULE.** A candidate never associated with a prior authoritative CLEAR guard receipt cannot become actionable solely from a later CLEAR response. If verification opens and resolves between candidate creation and its FIRST read, the returned version has no earlier candidate-bound version to compare against. FIRST CLEAR does not retroactively prove continuity, for version zero or any higher value. Future integration must either (A) prove continuity from a durably stored authoritative CLEAR receipt for the same Journey/history baseline that predates the candidate's entire qualifying evidence sequence, with unchanged version and all current clearance/eligibility checks; or (B) establish the first returned CLEAR version as a new binding baseline/evidence floor and require a completely fresh qualifying local sequence after that receipt before presentation. In B, the floor must honor at least that receipt's `evaluated_at` and `evidence_not_before`, together with the accepted local telemetry sequence/generation boundary. The migration history baseline alone is insufficient. Any clock/ordering ambiguity fails closed; stale/pre-floor evidence cannot be relabelled as post-baseline evidence. Keep-monitoring suppression and verification-resolution barriers remain additional constraints. This slice implements neither binding nor promotion.

`historySince(null)` explicitly returns UNBOUND_BASELINE. Equal versions mean only no guarded transition was recorded between those authoritative reads; they never prove that an unbound candidate's earlier evidence crossed none. The immutable `history_baseline_at` identifies the recording epoch, not first candidate binding and not proof about earlier verification. A changed baseline remains inconsistent. Future integration must persist the receipt association and floors across process death before using either continuity path.

Terminal transitions are read from the same decision snapshot. A completion/stop committed before that snapshot yields BLOCKED; an overlapping read may describe the preceding ACTIVE snapshot. CLEAR therefore cannot authorize a later action by itself. Later actionable/terminal operations must recheck exact Journey status, episode and current guard under their own concurrency boundary.

`SupabaseProbableEndVerificationGuardGateway` uses the existing authenticated Traveller coordinator and Supabase RPC transport, checks owner continuity before/after the request, and validates exactly one typed response for the requested UUID. Missing/negative/non-integer version, malformed timestamps/enums, contradictory decisions, changed baseline, decreasing version/time boundary or older server evaluation fail closed. An explicit previous guard supplies history comparison; this gateway keeps no hidden mutable cache. Transport/backend/auth failures produce bounded UNKNOWN/unavailable values without retaining error bodies. Coroutine cancellation still cancels. Callers must retain the previous version durably and perform a new authoritative read before any future actionable transition; a cached CLEAR is never perpetual permission.

Slice 3A needs no Room migration because it does not attach guard receipts to candidates. A later integration slice owns durable candidate/version/baseline binding, invalidation and sequence floors across restart, verification resolution and offline uncertainty. Neither the gateway nor this server projection mutates local candidates, Journey lifecycle or existing case resolution meaning.

Validation includes focused Android JVM response/history/auth/privacy cases, PostgreSQL/PLpgSQL syntax/static checks, pgTAP database acceptance and a PostgreSQL isolation fixture for opening/resolution races. Syntax/static success is not executable database or hosted acceptance. The database fixtures require a disposable migrated native PostgreSQL/Supabase-compatible test database. The implementation slice authorized no Docker, hosted deployment or paid CI; hosted deployment and acceptance were subsequently authorized in the separate gate below.

The pgTAP fixture invokes the global watchdog and must not be run unchanged against hosted production data. Future controlled hosted acceptance must use explicitly isolated owner/Journey fixtures and fixture-scoped transitions, or separately authorize a global watchdog run after assessing its real-data effects. Neither this contract nor a pre-deployment review authorizes deployment. Migration failure before COMMIT rolls back its transactional DDL; after deployment, any reversal requires a separately reviewed forward migration rather than editing applied history.

### Controlled hosted gate status, 2026-10-07

The separately authorized deployment targeted Journey Continuity (`tixjtxtptytokadpyvwq`) after confirming ACTIVE_HEALTHY status, exact migration-history parity, prerequisite signatures and watchdog semantic parity. The isolated dry-run selected only `20261007000100`. The deployed migration SHA-256 is `f34b8462c0fc2d33b8fc2b4b12a63e6b51e0a342612b6881dd9bdbc728eae958`; history recording and deployed function-body/trigger/grant checks passed. All original 36 Journey full-row hashes and existing monitoring/case/event/heartbeat hashes matched immediately after deployment. The 28 existing monitoring rows gained only version zero and one migration baseline epoch; no historical verification event was fabricated.

Local executable acceptance passed on Ubuntu WSL PostgreSQL 17.11 with PostGIS 3.6.4 and pgTAP 1.3.4: the full repository migration chain, 46 pgTAP assertions and all five authored official PostgreSQL isolation permutations. The only test correction was making the schema-qualified `has_column` assertion use its four-argument overload; no production defect was found. The isolation fixture's assertion output was inspected and rerun using a temporary expected-output baseline outside the repository.

Hosted access was interrupted during the initial disposable run. After access was restored, its two disposable Journeys and two Auth accounts were reconciled and removed, with original-data fingerprints unchanged. The completed replacement run passed 66 acceptance checks through the real hosted API and fixture-scoped administrative setup: owner CLEAR, wrong-owner/nonexistent nondisclosure, anonymous denial, client version/baseline write denial, bounded response privacy, ordinary-heartbeat stability, 300-second and older staleness before watchdog execution, future/incomplete/inconsistent evidence rejection, VERIFYING and independent OPEN-case blocking, legitimate heartbeat resolution, later verification opening, and COMPLETED/CANCELLED blocking. Stale setup uses server time minus 300 seconds; the subsequent guard's evaluation is at or after that boundary, while the deployed inclusive comparison matches the locally accepted SQL.

The disposable history demonstrated `0 -> 2 -> 2 -> 4 -> 5`: phase entry and case insertion may both advance the opaque version, resolution retains it, and a later independent OPEN case also advances it. Baseline identity remained unchanged. A first CLEAR still cannot prove an unbound candidate's earlier continuity; neither candidate binding nor promotion was implemented. Hosted FALLBACK_SMS acceptance was omitted because no genuine accepted disposable fallback fixture was provisioned; canonical fallback metadata behavior remains covered by local pgTAP. No provider/device evidence was fabricated, no contacts/outbox submissions were created, and the global watchdog was not invoked by the acceptance runner.

All replacement-run disposable Journeys and Auth accounts were removed. Final hosted Journey count is 36 (35 legacy and one original structured terminal acceptance Journey). All original Journey IDs/full-row fingerprints, owners, statuses, destination evidence and terminal timestamps remain unchanged; existing monitoring/case/event/heartbeat fingerprints also match the pre-deployment snapshot. No Probable End candidate/episode was fabricated. Slice 3A hosted acceptance is complete; future candidate/guard integration remains separately scoped.
