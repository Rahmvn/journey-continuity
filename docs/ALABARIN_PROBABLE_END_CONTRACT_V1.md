# Alabarin Probable End Contract v1

**Status:** LOCKED product and engineering contract. Section 09 engineering is CLOSED after final reacceptance on 2026-10-09. The broader Traveller visual/accessibility closure remains separate. Earlier slice boundaries and the original blocked acceptance record below remain historical.
**Date:** 2026-10-09
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

## Slice 3B: durable local guard continuity

Slice 3B joins the Slice 2 detector/episodes and Slice 3A authenticated guard. Room advances from v13 to v14 through additive `MIGRATION_13_14`, adding only `probable_end_guard_bindings` and `probable_end_candidate_continuity`. No existing Journey, telemetry, detector or episode row is rewritten or backfilled. Existing candidates start without an invented authoritative receipt; attachments are created lazily or atomically with new candidates. Both tables use exact foreign-key identities with NO ACTION retention. Hosted schema is unchanged.

The Journey binding stores the latest trustworthy guard's history epoch, opaque version, decision/verification/reason, evaluated time, evidence boundary and freshness bound, plus the baseline-establishment/receipt server time, local sequence/event floors, generation, revision and last attempt time. BOUND and BLOCKED distinguish a usable clear baseline from an invalidated baseline that must be re-established. NEEDS_BASELINE preserves a recognized blocker/intervening version whose server boundary cannot yet be mapped onto local observation time; it is never usable for continuity. These are recorded server receipts, never perpetual clearance. No row is created by UNKNOWN. Candidate attachments bind exact episode/Journey/generation to the epoch/version that preceded their evidence and retain checked epoch/version/time, durable request revision, attempt time and bounded unavailability reason. Local states are UNBOUND, PENDING_GUARD, CONTINUITY_PROVEN and INVALIDATED. No coordinate, private destination/provider data, case content or token is duplicated; new persistence/request rendering is redacted.

**First binding remains conservative.** An unbound candidate's first trustworthy CLEAR is only a baseline receipt. A single Room transaction permanently invalidates that candidate, saves the receipt, advances generation, quarantines telemetry through the latest persisted sequence (including observations arriving during the network call), and clears both detector streaks. The event floor is the greatest of the existing floor, local transaction time, server evaluation/evidence-not-before times and valid observed times already persisted within the existing future-skew allowance. Only observations strictly beyond both floors may accumulate again. No dwell or old candidate is resumed. Consuming this quarantined prefix is an explicit conservative discard for inference; authoritative telemetry remains preserved. A server/local ordering ambiguity beyond the detector's existing skew allowance fails closed instead of manufacturing a boundary.

New candidates attach a baseline only if their first observation is strictly after its sequence/event floors, their generation is at or after the bound generation, and the binding is valid and BOUND. Same-epoch/same-version current CLEAR may record CONTINUITY_PROVEN for that exact later candidate, provided its first observation is also after the returned current evidence-not-before barrier. A later barrier that overtakes its evidence forces fresh requalification. The fact means only that no guarded transition was recorded between the established clear baseline and this check and the server was CLEAR at this check. It proves neither arrival nor completion/safety and supplies no presentation/deadline/terminal permission.

A higher version permanently invalidates the old candidate even when the current result is CLEAR, and establishes a new baseline/floors requiring entirely fresh evidence. BLOCKED also invalidates and rebases, even without a version change (including direct staleness before watchdog execution); it records a BLOCKED binding. A later CLEAR re-establishes the baseline and resets any intervening accumulation. A genuinely different trustworthy history epoch also requires rebaselining/fresh evidence, never cross-epoch continuity. Regressed versions within the same epoch, older evaluated times, malformed or wrong-Journey receipts remain UNKNOWN; they cannot replace authoritative version/floors. Corrupt stored guard provenance fails closed without inventing replacement history. The gateway is called without its optional prior receipt so the durable integration can distinguish a real epoch replacement from same-epoch regression; it still enforces exact authenticated ownership and response shape.

UNKNOWN/unavailable leaves candidate evidence and floors unchanged and creates no baseline/version. An unbound candidate remains UNBOUND; a previously bound candidate waits as PENDING_GUARD, retaining any prior checked provenance as history. Neither is actionable. A CLEAR obtained after repeated offline UNKNOWN still rebases an unbound candidate; a higher version after unobserved offline verification/resolution invalidates a bound candidate. In-flight response latency consuming the returned server freshness allowance also fails closed. Wall-clock acquisition/clock-correction limitations from Slices 1/2 remain; no elapsed-realtime metadata is fabricated.

Request preparation and response application use separate short Room transactions; network work is outside them. Each request captures exact Journey/start, episode identity (or absence for BLOCKED-baseline recovery), generation, detector revision, binding receipt/revision and candidate attachment/request revision. Application re-reads them transactionally. Superseded requests, changed generation, invalidated/suppressed/replaced candidates or new candidates replacing absence are ignored. Compatible newer telemetry may advance the detector revision without changing the exact episode/floors; its response can still apply. Terminal/ineligible Journeys cannot regain continuity through a late CLEAR: their local candidate/attachment is invalidated without changing Journey lifecycle. Baseline/floor resets and candidate/binding updates commit or roll back together; inference failures cannot roll back primary telemetry or sync queueing.

Application/service restoration invokes bounded reconciliation after local incremental restoration. The post-telemetry path launches a new-candidate attempt in the optional inference scope after primary evidence commits; durable attempt metadata prevents UNKNOWN from causing a network call on every subsequent location callback. Retry opportunities reuse successful foreground-service heartbeats and process/service restoration. A genuinely new candidate can receive its first check promptly; its durable attempt marker suppresses duplicate first checks. Subsequent pending, proven or blocked-baseline checks are spaced by at least five existing 60-second heartbeat intervals. This five-minute interval is an engineering network throttle, not an evidence, freshness or safety threshold. Proven candidates are rechecked at that bounded cadence to discover later guarded transitions; a clear binding with no candidate does not poll. There is no new timer, WorkManager poll or network loop. An in-memory mutex coalesces overlapping calls; durable request revisions/generation checks provide correctness across competing coordinator instances and restart. Cancelled attempts remain recorded and retry at a later bounded lifecycle opportunity; coroutine cancellation propagates normally.

Reliable departure still invalidates the exact candidate/attachment; future suppression remains unwired. Suppression requires reliable exit then new return/dwell. Guard rebasing preserves the durable `requiresReliableExit` flag and monotonically advances existing floors, so verification resolution cannot undo suppression. Restart retains baseline/version, exact pending/proven/historical candidate, request revisions and evidence floors. Recreating Room/coordinator never adds count, dwell or authoritative knowledge. Proof is persisted historical continuity only; a later UNKNOWN/changed guard must still reconcile normally.

This slice does not present a Traveller question, create an actionable confirmation opportunity, start the 30-minute window, schedule a deadline, add notifications/AUTO_ENDED or implement Section 10. Later promotion/presentation must recheck current local evidence and authoritative guard instead of treating CONTINUITY_PROVEN as a cached permission. Focused pure/model/coordinator tests and real Room migration/reopen/race/fault/suppression fixtures cover these boundaries. Runtime device acceptance uses a separate credential-free package/UID with production Application/service wiring; compilation is not a claim of executable device acceptance.

## Slice 4A: durable foreground presentation foundation

Room v15 adds a dedicated presentation episode, separate from the system-inference candidate. Its exact candidate/Journey/generation/policy and authoritative baseline/version are immutable. `READY_TO_PRESENT` records a successful fresh foreground-requested guard recheck and atomic promotion; it does **not** say the Traveller saw anything. `PRESENTED` records the first genuine foreground rendering opportunity for the exact episode, once. An invalidated episode remains historical. No migration invents a presentation or first-seen receipt for older candidates. One live slot per Journey and a unique candidate reference make recreation/retries converge on the same episode.

The Activity requests a fresh authenticated guard read only while admitted and resumed with an exact continuity-proven candidate. The read occurs outside Room; its exact request is reconciled in a short transaction. Only an unchanged epoch/version, currently CLEAR guard and still-live exact candidate may create/reuse the presentation episode. Higher version, changed epoch and BLOCKED invalidate/rebase through Slice 3B; UNKNOWN does not present. A late response after departure, suppression, terminalization or generation/binding change cannot promote. Room is the UI source of episode truth; a process-local foreground gate merely ensures a READY/PRESENTED row is not exposed from a cached CLEAR while the Activity was backgrounded. Resuming the Activity requests a new guard check.

For this slice the foreground sheet follows current Section 09 Figma node `93:688`: "Has this Journey ended?", its locked explanatory sentence, and the "Journey completed" / "Keep monitoring" controls. Response controls are visibly dimmed and disabled; they do not perform a fake completion or suppression before response integration. Back/dismiss is not a response, does not close the episode, and a later visit to the relevant Journey may show that same episode again after fresh clearance. The first rendered/layout-complete, resumed and focused Activity records `presentedAt` transactionally; background creation, navigation intent or ViewModel emission alone cannot record it. Activity/store recreation cannot replace the episode ID or first `presentedAt`.

This slice adds no background notification, 30-minute deadline calculation/scheduling/enforcement, automatic terminalization, AUTO_ENDED, or Section 10 behavior. The locked Figma copy mentions the intended later 30-minute possibility, but that consequence does not run in Slice 4A. The next response/timing slices must use the persisted first opportunity and recheck authoritative verification before any automatic outcome.

## Slice 4B: explicit Traveller responses

Only a durably `PRESENTED`, live, exact Journey/candidate/generation/presentation-revision episode can accept a Traveller response. Back and dismiss remain non-responses. A response is first-party Traveller evidence, immutable for that episode; the two actions race through one Room transaction so at most one wins. Historical `READY_TO_PRESENT` and `PRESENTED` rows gain no fabricated response during the additive Room v16 migration.

Slice 4B enables the two previously disabled controls only for a presented episode; pending local work disables repeat taps. Durable state, rather than a transient button state, rejects stale or conflicting callbacks.

`Journey completed` atomically records `RESPONDED_COMPLETED` and invokes the existing local explicit-completion mutation for the exact active Journey. `COMPLETED` means the Traveller explicitly ended this Journey; it does not mean Alabarin independently established arrival, destination attainment or safety. The same transaction closes the live presentation and marks sync pending. Foreground-service teardown, fallback/degradation completion handling and urgent sync wake run after the local commit and are retried from durable pending-effects provenance after process recreation. Cloud acknowledgement is not a prerequisite for local completion. The Figma `78:4775` result says "Journey completed" and "Alabarin has stopped monitoring this Journey." Its Done acknowledgement is durable without inventing a general Journey history destination.

`Keep monitoring` atomically records `RESPONDED_KEEP_MONITORING` and applies the existing exact-candidate suppression primitive. The Journey stays ACTIVE. Suppression advances the evidence floor, invalidates the old guard continuity, and requires two reliable-exit observations before a completely new return and qualifying dwell sequence can form another candidate. The later candidate still needs fresh authoritative continuity and a fresh presentation guard. Reboot, guard rebase and connectivity changes cannot make the suppressed evidence live again. Returning to the active Journey sheet follows Figma `93:544`.

Slice 4B adds no automatic response, deadline, WorkManager/AlarmManager job, background Probable End notification, AUTO_ENDED terminal operation, or Section 10 unresolved incident path. The ask copy continues to describe the intended future possibility; only explicit Traveller actions are implemented here.

## Slice 4C1: durable no-response window, without automatic ending

Room v17 adds nullable deadline provenance to each presentation. The first genuine foreground `READY_TO_PRESENT` → `PRESENTED` write atomically records the first presentation time, an `ACTIVE` deadline exactly 30 minutes later, and the existing episode revision. Candidate creation, guard clearance, a backgrounded Activity, and `READY_TO_PRESENT` alone start no window. Historical episodes already `PRESENTED` when v17 is installed retain null deadline fields: no migration invents a deadline or makes them eligible for automatic ending. An older `READY_TO_PRESENT` episode can gain a truthful window only when first actually presented after migration.

The absolute deadline never extends on Back/dismiss, resurfacing, recomposition, or process/service recreation. Back/dismiss remains a non-response. Exact explicit responses atomically change any active or locally expired window to `CANCELLED`; terminal Journey closure and local candidate/guard invalidation do the same. These changes retain the original deadline as provenance without reopening it. Keep monitoring still requires reliable exit and fresh requalification. No background notification exists, so foreground presentation is the only implemented confirmation-opportunity origin.

Unique per-episode WorkManager work is a wake-up mechanism, not the source of truth. The worker carries exact Journey, presentation, candidate, generation, revision and deadline identity; at or after the deadline a short Room transaction rechecks the exact live, unanswered, ACTIVE Journey/presentation and records `EXPIRED_PENDING_FINALIZATION` once. Early execution retries; a stale or duplicate worker is a no-op. Room `ACTIVE` deadlines are rescheduled on Application restoration if scheduler state was lost. WorkManager persists work across ordinary process death and device reboot, subject to Android scheduling; actual execution can be late. The persisted wall-clock deadline rejects any wake while the device clock reads before it. An undetectable forward clock jump can make local expiry appear early relative to real elapsed time, so local expiry alone can never authorize terminalization; Slice 4C2 must obtain fresh authoritative guard/time clearance before any automatic terminal decision. No Journey is automatically terminalized in this slice.

`EXPIRED_PENDING_FINALIZATION` means only that the local no-response window appears elapsed. It does not change the presentation revision, so a concurrently tapped explicit response for that exact rendered episode remains acceptable until a later finalization operation actually wins. The Journey remains ACTIVE, and the existing question may remain visible with explicit response controls; it must never be represented as arrival, safety, completion, or AUTO_ENDED. Slice 4C1 adds no automatic-result screen, hosted RPC/schema, background notification, or Section 10 behavior. Slice 4C2 owns final guard reconciliation and any separate automatic terminal operation.

## Slice 4C2: guarded automatic monitoring end

`EXPIRED_PENDING_FINALIZATION` remains a local wall-clock observation, never terminal authority. After a genuine foreground `PRESENTED` write, Android may register the exact presentation with an authenticated, owner-scoped server RPC. Registration requires the current Slice 3A guard to be CLEAR at the presentation's exact history baseline and version. The server records its own immutable registration time and a not-before time exactly 30 minutes later; client presentation time cannot shorten that window. An offline presentation has no server window until a later successful registration, so automatic ending waits at least another 30 server-measured minutes. Identical registration retries return the same window and never restart it.

Automatic finalization requires both the local deadline to have elapsed and the server window to have reached its not-before time. Android durably claims the exact unanswered presentation before calling the finalization RPC; that claim wins over subsequent explicit-response callbacks. An explicit response that commits first prevents the claim. Unknown network outcome retains the claim and retries the same idempotent operation; it never grants a competing local response or manufactures a local terminal state. A trustworthy TOO_EARLY result releases the claim for later retry while preserving the immutable window. A trustworthy BLOCKED result permanently invalidates the old presentation and qualifying candidate, advances the local evidence floor/generation, and requires a fresh guard baseline and new qualifying evidence if the Journey remains ACTIVE.

The final server RPC locks the exact Journey and monitoring row, checks the immutable registered window and current direct-freshness guard, confirms the same history baseline/version and no unresolved verification, and atomically changes `ACTIVE` to the distinct `AUTO_ENDED` status. Verification opening and terminal writes serialize with this transaction. A committed retry returns the same automatic-end provenance. Local `AUTO_ENDED` is recorded only after that authoritative success, in one transaction with the exact presentation; service/fallback teardown and final sync wake are recoverable post-commit effects. Stale owner sync cannot rewrite a server terminal outcome. `AUTO_ENDED` means Alabarin automatically stopped monitoring after probable-end evidence, a genuine confirmation opportunity and no winning response. It does not mean arrival, destination reached, safety, contact restoration, or Traveller-confirmed completion.

The automatic result uses the current Section 09 copy and a transient, acknowledgeable result state. No background confirmation notification is implemented. Section 10 and `UNRESOLVED_INCIDENT` remain separate. The server can authorize an owner-scoped window based on an exact presentation identifier and current guard; it cannot independently attest that a phone actually rendered that presentation. The Android durable foreground receipt supplies that prerequisite. A device clock jump may advance local expiry, but cannot bypass the server's independent 30-minute not-before time and final guard.

The presentation fact is **device-attested**, not server-verified: the official Android path registers only after the exact durable episode is `PRESENTED`. The server does not observe phone pixels. An authenticated owner with raw API access can register outside that UI path; v1 accepts this owner-client trust assumption. Registration alone cannot terminalize anything, affects only that owner's Journey, starts a full server-measured 30 minutes, and leaves all ACTIVE/history/direct-freshness/unresolved-verification checks for the atomic finalizer. It creates no arrival or safety assertion.

Registration and finalization take a Journey `NO KEY UPDATE` lock before the monitoring lock. This serializes status writers without blocking the Journey foreign-key `KEY SHARE` used by state-first verification case insertion. Using `FOR UPDATE` here would invert those conflicting locks when a watchdog-style phase transition opens its case while the finalizer is waiting. The official isolation suite covers that overlap.

Local terminal reconciliation uses the existing shared SMS terminal-handoff gate around its short Room transaction, so a previously claimed carrier handoff and local terminal commit serialize. The server request remains outside that gate and outside Room. Teardown effects are recoverable and idempotent; a durable pending marker survives an interrupted post-commit service/sync wake.

## Section 09 final full-flow acceptance, 2026-10-08

Acceptance source is `03102c7ad6c3427b5daffe1017ad2f27dc6c2f42` on `feat/milestone-7-alexa-mcp-agent`. This gate changes documentation only; it does not deploy, change detector/terminal behavior, or begin Section 10. Current Figma Section `78:4707` was inspected read-only: ask `93:688`, Keep monitoring `93:544`, explicit result `78:4775`, automatic result `78:4796`, and no-active Home `78:4817`. The Keep monitoring reference was also rendered in isolation to exclude overlapping canvas content.

**Explicit completion:** targeted Redmi acceptance used production MainActivity, Room v18, response coordinator, service and sync scheduling. Controlled local observation times and a fake authenticated/guard transport exercised first-binding invalidation, fresh post-baseline candidate evidence, continuity and a separate fresh presentation check. MainActivity genuinely recorded PRESENTED before the real response tap. The exact immutable response and local COMPLETED committed together; monitoring stopped, sync remained durably requested despite unavailable cloud transport, Activity recreation restored the correct result, Done was acknowledged durably, and no-active Home/terminal service restoration did not revive the prompt. This is local production-path acceptance, not a new hosted authenticated completion run.

**Keep monitoring:** a distinct Journey went through the same production MainActivity path. Android Back hid the prompt without a response or deadline reset, and reopening used the same episode. The real Keep monitoring tap persisted its response and suppressed the exact candidate; the Journey stayed ACTIVE and service restoration retained suppression. Controlled observations proved replay and elapsed time alone cannot re-arm, one reliable outside observation and a 59-second span are insufficient, sufficient reliable-exit evidence only removes the exit requirement, and three return observations are insufficient. A fresh qualifying return/dwell produced a different candidate; UNKNOWN produced no presentation. Later continuity and a separate fresh presentation check were required for the new episode. Detector constants and the `distance - accuracy > 100 m` rule were unchanged.

**No response:** reuse the separately completed authenticated hosted 4C2 acceptance; no second 30-minute wait or timestamp manipulation is justified. The official Android path recorded PRESENTED at `2026-10-08T21:04:44.066Z`, with local deadline `21:34:44.066Z`; the server registered at `21:04:44.996Z`, with immutable not-before `21:34:44.996Z`. TOO_EARLY preserved ACTIVE, and the production Android finalization path obtained authoritative AUTO_ENDED at `21:34:46.020Z`. Exact Room reconciliation, service/fallback teardown, result recreation/Done, idempotency and one terminal event were verified. Real VERIFYING/OPEN-case blocking, resolution retaining the higher version, stale authenticated evidence blocking before watchdog transition, authorization/nondisclosure, and stale ACTIVE/COMPLETED owner-write rejection were also accepted. Device-attested presentation is not independent server observation of rendered pixels.

The deployed migration is `20261008000100_probable_end_auto_end.sql`, SHA-256 `D6CA189DF014A90B78299CBA7EF68872BEE1BFB9DA52A0E4881C2E7353E777CF`. This gate's read-only checks reconfirmed all 24 local/remote migration versions match and all original 36 Journey/full related-row fingerprints remain unchanged. No new hosted fixtures were created; the earlier five disposable Journeys and three accounts were cleaned. The accepted WSL baseline remains 40 automatic-end and 46 guard pgTAP assertions, plus nine and five official isolation permutations; SQL suites were not rerun in this documentation/Android gate.

**Historical decision on 2026-10-08: SECTION 09 BLOCKED.** Durable engineering acceptance does not make an unreadable response or unreachable truthfulness caveat acceptable. The existing Traveller closure worklist records these precise repairs:

- **High severity:** production MainActivity's READY-to-PRESENTED prompt renders the enabled Journey completed action as white text on an almost-white surface. A directly rendered PRESENTED component has the expected dark fill, so a static component test misses this runtime drawing transition. Restore the current Figma dark primary/outlined secondary treatment across state changes without changing response semantics.
- **High severity:** at a composition-scoped 320 x 480 dp viewport and 1.5x font scale, the automatic result clips its body before the arrival/safety caveat and Done. There is no scroll path. Make result content adaptive/scrollable and retain an accessible Done action and the full caveat; do not rewrite the locked copy or terminal behavior.
- **Medium severity:** the offline trusted-contact snackbar can obscure the Home Start action after Done; Home/terminal state itself was correct. Review placement/duration in the existing frontend closure pass.
- **Deferred truthfulness review:** the success-like automatic-result checkmark still matches Figma and remains acceptable for engineering where the explicit caveat is visible. Its combination with the clipped caveat is part of the blocking layout defect, not permission to omit the caveat or silently redesign Figma.

Direct regression tests cover opposing/duplicate responses, stale terminal callbacks, response/expiry races, the durable claim boundary, lost finalization acknowledgement followed by store recreation/retry, interrupted post-commit effects, server verification/terminal serialization, and stale terminal sync. Store recreation/fault injection is not a literal OS process kill or reboot. No real reboot, TalkBack walkthrough, uncertain-registration response-loss injection, or simultaneous claim-versus-response stress test is claimed here. Those limits must remain explicit; no elapsed local window or unresolved network outcome becomes terminal authority.

Validation in this gate: 112 focused JVM tests and the full 596-test debug suite passed, as did both debug APK builds. Redmi passed 99 targeted Room/state/migration/WorkManager tests, the two MainActivity stories, the existing presentation-control test, and an independent automatic-result copy/Done check. Layout captures exposed the defects above and are not layout passes. The existing automatic-result Compose-rule test stalled on the MIUI runner and was terminated; the isolated UI suite is therefore not claimed wholly green. The earlier Home assertion timed out because a contacts-unavailable snackbar obscured Start Journey; inspecting the screenshot and checking Home plus exact no-active state distinguished this from a failed Done acknowledgement.

After USB installation approval, a clean `com.journeycontinuity.app.section09finalflow` UID repeated both MainActivity stories successfully (two tests), then repeated the independent result/Done check and layout capture (two tests). The completion-action defect persisted in a two-second settled-state capture, and the short/enlarged-font automatic-result defect also reproduced. This excludes retained test-package history and the prompt's entry animation as explanations. Fresh-UID screenshots are under `%TEMP%/section09-final-flow/fresh-uid`; both temporary APK packages were removed afterward.

A later optional hosted definition refresh failed with a CLI database-query timeout after the successful read-only history/preservation checks. No bypass or mutation was attempted. Deployed-definition parity is the recorded 4C2 gate evidence, not a newly completed final refresh. This gate created no hosted fixtures and performed no hosted writes.

Local evidence and screenshots for this gate use temporary acceptance paths (`%TEMP%/section09-final-flow`); protected `artifacts/` and historical Journey UX documents were not edited. The normal Alabarin package was not replaced. Section 10 stays separate: its future ETA + 24-hour unresolved monitoring boundary must preserve uncertainty, authoritative ownership/terminal monotonicity, evidence access and recoverable teardown. AUTO_ENDED must never become a precursor to UNRESOLVED_INCIDENT.

### Narrow Section 09 UI repair, 2026-10-09

The primary-action defect was reproduced by a rendered-pixel regression through READY-to-PRESENTED in a Material modal sheet. The existing dark token was correct: Compose's `alpha(1f)` removes the graphics layer that existed in the disabled state, while the fill/border had been placed before that changing layer. On the Redmi this state transition lost their paint; the test sampled the light sheet instead of the dark control after two seconds. Both response controls now retain a graphics layer across enabled/in-flight transitions, with their existing background/border inside it. Resolved enabled fill remains `#1B3250`, with white text at 12.97:1 contrast. No enabled-state rule, exact response request or response consequence changed.

Both result screens now share an inset-aware scrollable layout with a viewport minimum height, preserving normal-size footer placement. Essential text is not constrained to a finite remaining column height, dynamic summary values are not ellipsized, and compact/large-font summaries stack. Done has a minimum 56dp height and separation from the summary. Saved scroll position is restored for the same episode, clamped to the available range if native insets change. The automatic title, body and arrival/safety caveat remain exact; the deferred checkmark treatment is unchanged.

The earlier Compose-rule stall was independently reproduced before the repair. A debugger stack showed the instrumentation thread waiting inside `Instrumentation.startActivitySync` / `ActivityScenario.launch`, before test composition, while the main thread was idle. The retained automatic-result test now uses an explicit foreground test-host launch and an empty Compose rule, consistent with neighboring device UI tests. No animation was removed, assertion deleted or timeout enlarged to conceal the stall.

Repair captures are outside the checkout under `%TEMP%/section09-ui-repair`: `story-a-prompt.png`, `story-a-prompt-recreated.png`, `story-a-prompt-reopened.png`, and `repair-automatic-<width>-<height>-font<scale>-{top,caveat,done}.png`. Both result variants were exercised at 320 x 480dp / 1.5x, 360 x 640dp / 1x, 390 x 844dp / 1x and 320 x 480dp / 2x, including long labels, scrolling, enabled Done and full text-layout assertions. A durable Room AUTO_ENDED fixture proves scrolling to Done acknowledges the exact result without changing Journey status or creating a Traveller response. MainActivity acceptance separately repeats the two-second settled prompt, Activity recreation, Back/reopen and the real completion tap, Room completion, monitoring teardown, result restoration and durable Done; only local observation/auth/guard transport seams are used. This is isolated local device acceptance, not a new hosted 30-minute run or a TalkBack walkthrough.

Final repair validation passed: 38/38 targeted Redmi UI/response/deadline/automatic-ending tests (including all five relevant UI tests), one MainActivity completion-flow test and one eight-viewport capture test. The full debug JVM run passed 596/596, including 112 Probable End tests; `assembleDebug` and `assembleDebugAndroidTest` passed. Slice-file whitespace checks passed. Nothing was staged or committed; the user-modified local configuration and the earlier acceptance documentation were preserved.

Current Figma `93:688` and `78:4796` were inspected read-only for this repair. Their normal-size hierarchy, colors, control geometry and locked copy remain intact; smaller/larger-font result layouts adapt for reachability. No inference, guard, response, deadline, finalization, sync, teardown or hosted definition changed. The previous hosted acceptance is retained without another window or hosted fixture. The Home snackbar obstruction and the success-like automatic-result checkmark remain on the separate frontend/truthfulness worklist. These repairs do not mark Section 09 closed or begin Section 10.

### Final Section 09 reacceptance and engineering closure, 2026-10-09

**Decision: SECTION 09 CLOSED.** All nine repair/documentation files were reviewed. Production changes remain UI-only; no detector, guard, eligibility, response, suppression, deadline, finalization, teardown, sync, Room schema or hosted behavior changed. The prior blocked acceptance and repair records remain historical evidence, not current unresolved blockers.

Redmi repeated all five relevant UI tests, including the formerly stalled automatic-result test, with no skips. Production MainActivity repeated genuine PRESENTED, the two-second settled dark primary, Activity recreation, Back/reopen and real touch on Journey completed. Exact response/COMPLETED commit, durable sync intent, service teardown, its durable effects marker, result recreation, Done acknowledgement and terminal service restoration passed. The temporary acceptance initially read the effects marker before asynchronous post-commit work finished; awaiting that same marker with the existing bounded wait corrected test ordering without changing production or weakening the assertion. Both result variants passed all four requested viewport/font combinations. Current Figma `93:688` and `78:4796` and actual first/scrolled captures were reviewed: essential text and Done are reachable, long labels wrap, scroll restoration handles native inset changes, and Done leaves AUTO_ENDED unchanged. Captures are outside the commit at `%TEMP%/section09-ui-repair/reacceptance`.

Story B's accepted suppression/reliable-exit/fresh-return/dwell/guard/presentation proof remains valid on unchanged code. Story C reuses the genuine authenticated hosted window: server registration `2026-10-08T21:04:44.996Z`, immutable not-before `21:34:44.996Z`, authoritative AUTO_ENDED `21:34:46.020Z`, with the independent local deadline also elapsed. No second 30-minute run, hosted fixture, write or deployment occurred. The migration hash remains the accepted hash above. Original-36 preservation, authorization, verification/freshness blocking, terminal monotonicity and idempotency are retained acceptance evidence, not freshly rerun hosted checks.

Direct response/worker races, stale callbacks, durable claim precedence, unknown-finalization retry, store recreation, interrupted teardown and offline timing evidence remain valid. The unchanged repair baseline passed 38/38 targeted Redmi tests, 596/596 JVM tests including 112 Probable End tests, and both debug APK builds. This gate reran the full five-test UI group, viewport capture test and MainActivity completion story; broader suites and WSL SQL were reused. No OS reboot, TalkBack walkthrough, registration-response-loss fault injection or simultaneous claim-versus-response stress test is newly claimed.

The checkmark was reassessed in compact first viewports where the caveat starts below the fold. The title explicitly describes monitoring ending, with no safe-arrival or Traveller-completion claim; the full uncertainty statement is reachable. The success-like mark remains a nonblocking discrepancy for the later Traveller truthfulness/frontend pass. The Home snackbar also remains deferred: this Redmi's existing accessibility UI timeouts are 120000ms, so it obscures Start Journey during that configured interval. Without changing user settings, the gate waited for expiry and used real touch to open Start Journey successfully. The earlier five-second Home probe failed because it assumed a shorter interval; the control is not permanently lost. Snackbar placement still needs the broader UX pass.

Section 09 engineering closure does not close or declare the whole Traveller frontend polished. Protected files and the normal Alabarin package remain untouched. Section 10 has not begun. Its separately authorized plan must use the genuinely unresolved ACTIVE branch at expected arrival plus 24 hours, preserve exact ownership and authoritative terminal monotonicity, retain evidence/uncertainty, stop monitoring/fallback/timers recoverably, and define independent runtime/race acceptance. AUTO_ENDED is distinct from COMPLETED and must never become a precursor to UNRESOLVED_INCIDENT.
