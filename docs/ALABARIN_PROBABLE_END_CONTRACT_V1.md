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
