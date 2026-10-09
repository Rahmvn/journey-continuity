# Alabarin Unresolved Boundary Contract v1

**Status:** LOCKED PRODUCT CONTRACT. Exact pending/late-episode UI copy remains a later UI review gate. The 10A foundation and 10B server finalizer are committed and deployed. 10B hosted SQL/JWT fixture acceptance passed; signed-JWT HTTP and Android acceptance remain pending. No Section 10 operational stop or Android terminal/result behavior is active. This contract does not authorize another deployment.
**Date:** 2026-10-09
**Source baseline:** `132142aaaab1f28293f274de099b2ad1ecec676d`, branch `feat/milestone-7-alexa-mcp-agent`.
**10A/10B foundation:** Room v19 and the hosted admission/eligibility migration are deployed. The 10B migration adds hosted UNRESOLVED_INCIDENT finalization and seven-day incident-detail access. Hosted parity is 26/26. Android Journey statuses remain ACTIVE, COMPLETED, CANCELLED and AUTO_ENDED until 10C integration. Section 09 is closed for engineering and remains unchanged.
**Revised 10A authority (2026-10-09):** An authenticated server timestamp at or beyond the exact boundary can establish historical passage. An earlier sample cannot be advanced through Android `elapsedRealtime()` into an authoritative UTC lower bound without a supported clock-error envelope. The narrowed 10A foundation is authorized independently of the unresolved offline-stop policy question in section 6.
**10A policy identifier:** `alabarin-unresolved-boundary-v1` in Room and server admission provenance.

## 1. Authority, review status and scope

Read together with:

- [Evidence and Remote Uncertainty Rules](ALABARIN_EVIDENCE_AND_REMOTE_UNCERTAINTY_RULES.md), especially evidence classes, device silence and the unresolved boundary.
- [Traveller v1 Closure Plan](ALABARIN_TRAVELLER_V1_CLOSURE_PLAN.md), including Section 09 closure and deferred frontend/accessibility findings.
- [Probable End Contract v1](ALABARIN_PROBABLE_END_CONTRACT_V1.md), especially the Section 10 separation, authoritative terminal operation and final engineering closure.
- [Structured Destination Contract v1](ALABARIN_STRUCTURED_DESTINATION_CONTRACT_V1.md), especially local-first admission atomicity and privacy.
- [Architecture](ARCHITECTURE.md) and [Product Direction](PRODUCT_DIRECTION.md), interpreted with current implementation rather than historical milestone capability descriptions.
- The Section 10 read-only reconnaissance delivered in this conversation on 2026-10-09. It inspected the reconciled Phase 5 decisions in Journey User Flows sections 2.14/3.11 and Journey UX States L06, current production code and current Figma. Those protected ledgers were not reopened or edited in this contract pass.
- The user's subsequent Section 10 Final Contract Reconciliation instruction, which approves the operational stop independently of a fresh unresolved snapshot, pending recovery/admission, late-episode eligibility, seven-day restricted access and result Back acknowledgement. These explicit approvals supersede the earlier draft's proposal labels.

The latest locked Phase 5 meanings and subsequent explicit decisions outrank older descriptions. Old statements that AUTO_ENDED/cancellation are unimplemented, or that the hard-boundary duration is deferred, do not override current Section 09 closure or the locked 24-hour rule. The incident-access duration was previously deferred and is now explicitly locked at seven elapsed days; it is separate from the provisional resolved-case sensitive-access TTL.

Labels in this document:

- **LOCKED:** explicitly authorized product meaning or constraint; not a claim of implemented behavior.
- **ENGINEERING STRATEGY TO VALIDATE:** a proposed mechanism or slice boundary, not an implemented capability; it cannot relax a product invariant.
- **UI COPY REVIEW:** exact wording/presentation still requires review before the relevant user-visible slice, without reopening a locked policy.
- **ENGINEERING RISK:** a feasibility, integration or acceptance obligation to resolve during separately authorized implementation.

The original contract-only reconciliation preceded the separately authorized, committed and hosted-accepted 10A and 10B server foundations. This 10C-1 update adds only local operational machinery and contract clarification; no new hosted writes or Figma changes are authorized. Trusted Viewer, SMS and Alexa implementation remain outside scope.

## 2. Decision register

| ID | Decision | Status |
| --- | --- | --- |
| D01 | Preserve known facts and uncertainty within bounded monitoring; no danger/arrival/safety inference | LOCKED |
| D02 | Hard boundary is original immutable admitted ETA plus exactly 24 elapsed hours | LOCKED |
| D03 | Authenticated server proof or an approved approximate same-boot privacy cap can stop continuous operations into PENDING_AUTHORITY; neither locally invents an incident | LOCKED; 10C-1 runtime activation remains gated on truthful pending UI |
| D04 | ACTIVE plus valid ETA authority plus reached server boundary plus coherent current VERIFYING and exact OPEN case | LOCKED |
| D05 | Operational stop does not require a received unresolved-case snapshot; absent authority cannot manufacture incident eligibility | LOCKED |
| D06 | Pending authority retains the occupied active-Journey slot; no second admission or silent replacement | LOCKED |
| D07 | A genuinely new coherent verification episode after the fixed boundary may qualify immediately; preserve its own history | LOCKED rule; exact conditional result wording remains UI COPY REVIEW |
| D08 | Freeze original ETA/provenance; preserve delayed offline admission; invent no historical authority | LOCKED; committed 10A schema deployed and hosted-accepted |
| D09 | Device-initiated finalization; reconcile winning explicit intent first; terminal authority remains server-atomic and monotonic | LOCKED; exact claim/recovery mechanism requires validation |
| D10 | Seven elapsed days of restricted, revocable, read-only incident-detail access from authoritative terminalization | LOCKED |
| D11 | No new post-terminal reporting; evidence retention is separate from access expiry | LOCKED constraint; retention duration remains separately undecided |
| D12 | Back acknowledges the informational result through the same durable operation as Done | LOCKED; Section 10 only |
| D13 | Resolved/absent eligibility after operational stop keeps collection stopped; truthful operational-limit state permits established explicit completion/stop actions | LOCKED |
| D14 | Durable offline explicit terminal intent takes local precedence before unresolved finalization; intent is not an invented server acknowledgement | LOCKED |
| D15 | Original admitted ETA must be later than local admission and no later than admission plus 72 elapsed hours | LOCKED; new v1 admission policy |

## 3. Purpose and distinct meanings

Alabarin preserves factual evidence for trusted human follow-up within a bounded monitoring window. It does not collect location indefinitely merely because server authority is unavailable, when the admitted ETA boundary can be established reliably. Stopping monitoring does not resolve the underlying uncertainty.

`UNRESOLVED_INCIDENT` records an authoritative terminal bounded-monitoring outcome while the exact Journey has genuinely unresolved verification at finalization. Continuous operations may already have stopped under the pending-authority policy. It does not mean missing person confirmed, kidnapping, danger established, death, safe arrival, destination reached, contact restored or Traveller-confirmed completion.

`PENDING_AUTHORITY` means the device stopped continuous monitoring under its bounded operational policy, preserved the evidence, and has not obtained an authoritative terminal decision. It is an operational state, not a Journey terminal status or a finding of unresolved verification. The Journey may remain ACTIVE while continuous operations are stopped. Neither a local timer, a worker invocation, an absent fresh server snapshot nor that pending state authorizes UNRESOLVED_INCIDENT.

The server does not observe phone pixels or know an explicit action that has never left an offline device. There is no UI-attestation claim in Section 10 and no requirement to manufacture a confirmation opportunity or another Probable End grace window.

## 4. Genuine unresolved eligibility

**LOCKED D04:** A future authoritative finalizer may commit only when all of the following are true in one serialized operation:

1. Caller is an authenticated, non-anonymous exact owner under the current verified Traveller identity contract.
2. Exact Journey remains ACTIVE; no prior legitimate terminal outcome won.
3. An immutable admitted ETA, exact boundary and policy/source provenance exist and agree with the Journey identity.
4. Authoritative server time is at or after that boundary.
5. Monitoring is coherently VERIFYING, with valid owner/Journey/timestamp/history provenance.
6. There is exactly one current coherent OPEN verification case for that Journey and owner, bound to the current verification episode. Its opening/history references agree with monitoring. A guard-version delta is not an incident count or a case identity.
7. No qualified newer authenticated evidence or authoritative resolution has superseded that episode. If evidence requires resolution/state reconciliation, reconcile or fail closed before eligibility; do not terminalize from a stale VERIFYING flag.
8. The request's exact boundary, case/verification identity and revisions still match, with no conflicting finalization provenance.

The existing Probable End guard's `VERIFYING OR OPEN` classification is a conservative AUTO_ENDED blocker, not this stronger incident-finalization predicate. No CONTACT_UNVERIFIED, ESCALATING or other new escalation status is required.

| Facts | Terminal eligibility | Required treatment |
| --- | --- | --- |
| ACTIVE, valid immutable ETA, boundary reached, coherent VERIFYING plus exact current OPEN case | May qualify after exact atomic recheck | Preserve case identity and unresolved provenance |
| Same genuine unresolved facts, boundary not reached | No | Retain original boundary; never round backward |
| EVIDENCE_FRESH, no OPEN case | No | ETA alone does not terminalize |
| OPEN plus EVIDENCE_FRESH | No | Fail closed and investigate/reconcile state |
| VERIFYING without a coherent OPEN case | No | Fail closed; do not invent a case |
| Stale evidence before watchdog VERIFYING/OPEN transition | No | Freshness failure alone is insufficient |
| Historical RESOLVED case, currently clear | No | Historical uncertainty is not current eligibility |
| Human report alone, fallback allocation/handoff, local service interruption or device silence | No | Preserve the appropriate evidence class |
| Offline, backend unavailable, malformed/mismatched provenance | Unknown, not permission | Reconcile later; apply only the separately justified operational policy |
| Missing/corrupt/unproven ETA | No | Do not guess ETA or fabricate a deadline |
| COMPLETED, CANCELLED or AUTO_ENDED | Never | Preserve the existing terminal outcome |

Genuine resolution permanently supersedes eligibility attached to that old verification episode. A later new episode receives new eligibility identity, while the Journey's ETA boundary stays fixed.

## 5. Original ETA authority and temporal boundary

**LOCKED D02/D08/D15:** ETA is the Traveller-selected original arrival instant captured in the admitted Journey request. The Journey UUID, ETA, admission/source provenance and policy are frozen. Valid v1 ETA satisfies `admissionAt < ETA <= admissionAt + 259,200,000 milliseconds` (72 elapsed hours). The absolute unresolved boundary is `ETA + 86,400,000 milliseconds` / exactly 24 elapsed hours. The latest possible boundary for a newly admitted v1 Journey is admission plus 96 elapsed hours. This is not a universal terminal limit or a substitute for current coherent unresolved verification. The boundary is not a calendar-day calculation or server-registration-time plus 24 hours.

Android freezes the Start request before readiness/permissions and revalidates ETA against actual local admission. Room stores a non-null epoch-millisecond value. The deployed additive 10A server migration freezes accepted ETA against owner updates while preserving historical rows unchanged.

Admission authority preserves exact Journey/owner, original ETA, locally attested admission time, source/provenance, boundary and policy version. Validate finite, representable values, checked arithmetic, timestamp precision, `admissionAt < ETA`, and the approved 72-hour maximum. Compute fixed elapsed durations in UTC/epoch arithmetic; do not substitute a session-timezone calendar day across daylight-saving changes. Do not rewrite existing Journey ETAs to make them appear compliant. A delayed upload of a valid original admission is not rejected because its ETA is past at server receipt.

Admission remains local-first. A delayed offline upload is valid only when its original admission satisfied the current policy, including `admissionAt < ETA <= admissionAt + 72 elapsed hours`. Such an upload must preserve the original frozen ETA and local admission facts even if ETA is past at first hosted receipt. Server acceptance time is separate provenance, not a replacement ETA; requiring ETA to be future at upload is incorrect. Alabarin has not launched, so an invalid pre-release Journey whose first hosted upload exceeds the new 72-hour limit may be rejected. No metadata-less exception is required for that hypothetical case.

Once accepted for Section 10, owner-facing ACTIVE upsert/update cannot move ETA or the boundary. A mismatch must fail safely rather than move the timer. An owner-supplied admission time is device-attested input, not independent server observation; first server acceptance cannot prove that an unsupported older client never changed an earlier value.

Existing hosted Journeys remain unchanged, including their original ETAs. No migration may turn an existing timestamp into invented admitted-ETA authority. Historical Journeys without trustworthy provenance are ineligible for automatic unresolved terminalization. **ENGINEERING STRATEGY TO VALIDATE:** initially qualify newly admitted policy-bearing Journeys only; any later historical adoption requires actual proof and separate review, never invented backfill. A legacy destination classification alone does not determine ETA validity.

## 6. Reliable local time: strategy to validate and limits

The locked product separates an authoritative UTC boundary fact from an approximate local privacy cap. Authenticated server time alone can prove that ETA + 24 hours passed. A valid same-boot `elapsedRealtime()` anchor may instead reach an approximate operational cap and pause continuous collection without asserting UTC passage. A mutable device wall clock establishes neither fact. The 10A foundation adds authenticated observation metadata; 10C-1 adds separate operational anchor/state machinery without activating automatic pausing in production.

Persist the original admitted ETA/start facts and, where available, a Journey-bound authenticated server observation: server time, local elapsed-realtime value when the response was received, boot/installation identity, source and revision. The separate 10C-1 admission monotonic reference supports only the approximate operational cap; the narrowed 10A observation and raw admission wall-clock reading are not extrapolated into authoritative UTC time.

Android `SystemClock.elapsedRealtime()` includes deep sleep and advances monotonically since boot; it survives process recreation within that boot, not reboot. `uptimeMillis()` excludes deep sleep and is unsuitable here. A readable, validated `Settings.Global.BOOT_COUNT` may help identify the boot but is not UTC authority or a globally unique installation identity. Sources: [SystemClock](https://developer.android.com/reference/android/os/SystemClock), [BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT).

**Validated narrowed 10A rule:** authenticated server observation time `S >= original_boundary` establishes a retained historical boundary-passage fact. If `S < original_boundary`, return `INSUFFICIENT_PROOF` for later passage until another authoritative observation arrives. Local receipt `elapsedRealtime`, boot count and installation identity are diagnostic/tentative scheduling provenance only. They cannot advance `S` into a strict UTC lower bound. Reboot or wall-clock movement does not erase an already established server-time fact. Missing/corrupt authority must fail closed; no guessed tolerance is authorized.

The current testable proof predicate is `authenticated_server_observation_at >= original_boundary`, bound to the exact admitted Journey and accepted server provenance. WorkManager delivery is not that predicate. A raw device-wall-clock jump, timestamp insertion delay, worker count or process uptime cannot establish it.

| Condition | Required behavior |
| --- | --- |
| Process recreation in a provably identical boot | Restore the same admitted boundary and prior authenticated observation; do not extrapolate from elapsed time |
| Device wall clock moves forward/backward | Ignore wall-clock movement for boundary proof |
| Reboot before proof | Do not subtract elapsed counters across boots or credit unknown powered-off time; if offline and the approximate cap cannot be reconstructed, pause with a lost-anchor reason; a past authenticated boundary proof remains established history |
| Lost boot identity, restored/copied database, invalid anchor, arithmetic/clock regression | INSUFFICIENT_PROOF for unsupported UTC extrapolation; conservatively pause collection when the operational cap cannot be reconstructed; preserve any previously validated server-time passage fact |
| Offline admission with only untrusted wall-clock facts | Preserve ETA but do not claim an absolute UTC proof from monotonic time alone |
| Valid stop was already committed before process death/reboot | Restore PENDING_AUTHORITY and stop operations; do not restart collection because the current clock anchor is missing |

A fresh authenticated server observation can establish boundary passage without changing ETA. A same-boot monotonic anchor can schedule and justify only an approximate operational pause; it cannot turn an earlier server timestamp into UTC proof. If reboot or missing/corrupt boot provenance prevents reconstructing that local cap while offline, conservatively pause collection with an explicit lost-anchor reason. This can stop operations before the official UTC boundary and must never authorize incident finalization. A committed pause survives reboot and reconnection without automatic collection restart.

The subsequent 10C-1 product decision approves the approximate local privacy cap and conservative offline lost-anchor pause. This is an operational availability/privacy tradeoff, not an exception to server terminal authority. The source, uncertainty and distinct pause reason must be durable. Existing explicit stop/completion choices retain their contracts. 10C-1 keeps automatic production activation inactive until truthful pending UI and reconciliation are available.

### 10A time-proof validation finding - 2026-10-09

Read-only implementation investigation reached the explicit clock stop condition before code or migrations were changed. The proposed same-boot strategy above is not a validated production lower bound. [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock) guarantees a monotonic sleep-inclusive elapsed counter, but specifies no maximum fast-drift bound relative to the authenticated UTC source. [AOSP time detection](https://source.android.com/docs/core/connect/time) explicitly accounts for elapsed-clock inaccuracy over long periods. Existing app code has no qualified clock-rate/uncertainty source.

Inference: ignoring inbound network delay makes the initial server sample conservative, but does not bound later accumulated clock error. If the elapsed counter runs fast, `S + (E1 - E0)` can reach the boundary before enough real time has passed. A fixed margin, observed round-trip time or a guessed ppm allowance cannot establish the missing guarantee. A strict extrapolation would need a justified maximum rate/error envelope over the anchor's supported age, for example a supported `r_max` permitting conservative advancement no greater than `floor((E1 - E0) / (1 + r_max))`, with sampling/precision uncertainty also accounted for. No such production envelope was established by this investigation.

The narrowed foundation route was explicitly authorized after this finding. It stores authenticated server and local receipt provenance, returns `INSUFFICIENT_PROOF` for unsupported extrapolation, and retains a verified historical passage fact. This does not establish offline passage for a Journey whose latest authenticated server observation was before the boundary.

No elapsed-time guarantee, safety margin or new clock-sync system was invented. The approved bounded-monitoring purpose, seven-day access policy and all Section 09 behavior remain unchanged. The historical 10A interruption preceded the later explicit 10C-1 decision: approximate same-boot operational pausing and offline lost-anchor pausing are now approved, while authoritative UTC proof remains unchanged.

## 7. Offline operational stop and PENDING_AUTHORITY recovery

**LOCKED D03/D05:** For an exact locally ACTIVE Journey with trustworthy immutable admitted ETA, authenticated passage proof **or** an approved approximate same-boot privacy cap/lost-anchor condition may atomically persist a PENDING_AUTHORITY operational pause before continuous collection stops. Record the distinct reason and uncertainty. Only the authenticated server observation is a boundary-passage fact. Preserve existing observations, unsent records and verification/time provenance. Do not set local UNRESOLVED_INCIDENT or display the authoritative incident result.

The operational stop does not require a positive cached VERIFYING/OPEN snapshot or a fresh server response. Current Android has no durable current OPEN-case projection and cannot learn an episode opened while the phone is offline. That absence does not authorize an incident, but it does not veto the independently proven bounded operational stop. Any saved case snapshot must retain its evaluation time and episode/version as last-known knowledge, never a lease proving current server state.

Offline/ETA/silence alone must never be relabelled genuine unresolved verification. The approved policy separates operational cessation from authoritative terminal eligibility: lack of authority leaves the operational decision pending; only the deployed authoritative server operation may determine current incident eligibility. Android does not invoke it yet. Wall-clock movement alone never triggers the approximate cap.

Once PENDING_AUTHORITY commits, stop continuous location collection and heartbeat generation, new degradation evaluation and new fallback allocation under the bounded-stop policy. Server unavailability alone never resumes them. Cancel obsolete collection/Probable End/deadline execution; preserve pending transport records and already handed-off/uncertain transport history. Reconcile a handed-off attempt's acknowledgement where permitted; do not blindly allocate or re-send new fallback attempts after the stop. Preserve reconciliation of any already-claimed/uncertain terminal operation, including Section 09 finalization, rather than abandoning its possible server outcome. Keep only bounded authenticated time/status/terminal reconciliation, explicit-intent sync and permitted existing evidence upload; those operations must not generate a fake fresh device-contact signal merely to make reconciliation succeed.

Persist operational-stop time/proof and reason separately from any later authoritative ended time. A local stop must not fill Journey terminal timestamps or start the incident-access window. Subsequent authoritative finalization records its own server time and exact verification provenance, including when operations stopped earlier under a different knowledge state.

Worker/restart/service restoration must consult the operational stop before treating the still-ACTIVE Journey row as permission to collect. A last-known case snapshot is never sufficient for authoritative finalization; that always rechecks current server state.

**LOCKED D13:** if reconnection confirms current coherent unresolved verification and every authoritative terminal condition, request exact incident finalization. If verification resolved, eligibility is absent, or records require state repair, do not fabricate UNRESOLVED_INCIDENT or automatically mark COMPLETED/CANCELLED. Supersede eligibility tied to a resolved/replaced episode. Keep continuous collection stopped and present a truthful operational-limit state; permit the established Journey completed or Stop monitoring action under section 10's explicit-intent rules. Do not automatically resume collection or start another duration. UNKNOWN authority retains pending reconciliation while operations remain stopped; confirmed absent eligibility retains the stopped operational-limit disposition. Neither is a new Journey terminal status.

## 8. Pending-state admission and presentation

**LOCKED D06:** keep the existing `activeSlot = 1` occupied while Journey status remains ACTIVE and its operational state is PENDING_AUTHORITY or a stopped operational-limit disposition. Do not clear it merely because the service stopped. Block a second admission and silent replacement while that Journey remains locally active/pending. Release follows a legitimate exact-Journey terminal transition under the established contracts: explicit completion remains local-first with durable sync; cancellation requires authoritative acknowledgement; incident finalization requires authoritative success. A queued stop intent or an operational stop alone never frees the slot.

The current Room unique active-slot index and insert transaction enforce one admitted local ACTIVE Journey. Removing the slot would falsely treat operational stop as terminal closure and permit overlapping unresolved server ownership. UI and service must distinguish an occupied slot from running collection; do not weaken the database invariant to solve presentation.

Pending UI must state the bounded local operational fact and incomplete reconciliation, without UNRESOLVED_INCIDENT, danger, arrival or authoritative result language. Current Figma has no such frame. **UI COPY REVIEW:** exact pending/operational-limit wording and navigation require review before user-visible runtime activation, including the two approved explicit choices. The stopped/no-second-admission policy is already locked; this review does not authorize automatic resume, a new terminal state or an incident result before reconciliation.

## 9. Late verification episode

**LOCKED D07:** the original boundary never moves. If the Journey was not unresolved at that boundary but a genuinely new coherent authoritative VERIFYING/OPEN episode opens later, it may qualify immediately once all other authoritative conditions pass in an atomic current-state check. The normal watchdog's existing verification threshold still applies; do not add an arbitrary new minimum incident age or another case-opened-at plus 24 hours timer.

This is defensible as a fixed bounded monitoring policy, not evidence that the Journey was continuously unresolved before the new episode. Persist the current case/opening event, verification start time, original boundary and finalization time. Never borrow historical resolved uncertainty to satisfy the current predicate.

The current Figma body says the Journey "remained unresolved 24 hours after the expected arrival time." That may imply an earlier uninterrupted unresolved history when used for a late episode. **UI COPY REVIEW - conditional wording candidate:** "Alabarin stopped continuous monitoring because this Journey became unresolved after its monitoring boundary of 24 hours beyond the expected arrival time." Keep the existing title and uncertainty caveat. This exact wording is not approved and must not replace current Figma silently. It must also remain truthful if operational collection had already stopped before authoritative finalization.

Alternatively, UI review may approve one non-duration-implying body for all qualifying episodes. The immediate late-episode rule is locked; only exact result wording remains open for the later UI/runtime acceptance gate, not the 10A foundation. Do not release a result that invents historical continuity. A newly opened episode after earlier resolution cannot resurrect the old eligibility identity.

## 10. Explicit action precedence and authority

**LOCKED D09/D14:** use device-initiated unresolved finalization. Do not add an autonomous server sweep that ignores potentially unsynced explicit intent. Before its claim/request, check and reconcile durable local explicit completion/cancellation, queued explicit intent and any in-flight/uncertain explicit terminal operation. If explicit intent won locally, reject the unresolved path and finish exact-Journey explicit reconciliation. A stale worker must not override that intent merely because it has not reached the server.

Current completion is local-first; cancellation/active-Journey sign-out requires server acknowledgement before local CANCELLED. **Locked future Section 10 behavior:** an offline Traveller choice must be durable and exact-Journey-bound. Completion follows its existing local-first contract. Offline Stop monitoring records pending explicit stop intent, not a fabricated CANCELLED status/ended time or completed sign-out. It blocks unresolved finalization while awaiting the established authenticated stop operation and its authoritative acknowledgement. This requires narrow intent persistence/recovery during Section 10 integration; it is not implemented today and does not broaden the existing offline account-sign-out policy.

**ENGINEERING STRATEGY TO VALIDATE - durable winner:** an exact short Room transaction checks the Journey, operational/boundary record, current verification identity/revision, absence of accepted, queued or uncertain explicit terminal work and absence of a conflicting pending terminal claim from any branch, then records an unresolved-finalization claim. Explicit intent accepted before that claim prevents it. After the claim, stale explicit callbacks cannot partially terminalize the same Journey while the server outcome is uncertain. The claim is not a terminal finding. Network is outside Room and outside the terminal-handoff gate.

| Outcome | Recovery obligation |
| --- | --- |
| Explicit terminal intent commits first | No unresolved claim/request; finish the existing intent's sync/recovery |
| Unresolved claim commits first | Keep claim durable; subsequent conflicting callbacks do not apply partial consequences |
| Network response lost/timeout/backend unavailable | Retain claim; retry/read the same exact idempotent operation; no guessed failure or local incident |
| Server returns TOO_EARLY | Preserve original ETA/boundary; release or supersede only the exact claim according to a typed transition; safely retry later, never reset the boundary |
| Server proves resolution/incoherence/episode replacement | Supersede exact eligibility; do not retry the old case forever; already stopped operations remain stopped with truthful operational-limit presentation and explicit choices |
| Server already terminal COMPLETED/CANCELLED/AUTO_ENDED | Reconcile that distinct authoritative outcome; do not replace it with incident |
| Same incident finalization already committed | Return the same outcome/provenance; atomically reconcile local Journey and boundary result |
| Restart or delayed sync | Restore claim/intent identity; recheck exact owner and remote outcome before another terminal operation |

A server cannot know an unsent action on another offline client. Device initiation and local ordering prevent the official same-device race; they are not a distributed guarantee against arbitrary owner API calls or disconnected clients. If a different legitimate server terminal outcome already won, preserve it and retain conflicting local action provenance for reconciliation; never rewrite the server terminal state or erase intent history.

## 11. Atomic server finalization and Section 09 exclusion

The deployed authoritative server operation itself authorizes, locks, re-evaluates time/ETA/current VERIFYING/current OPEN case/authenticated evidence, then writes UNRESOLVED_INCIDENT and immutable provenance together. Android does not invoke it yet. A client eligibility read followed by generic Journey UPDATE is forbidden.

Use the accepted Journey `NO KEY UPDATE` then monitoring-row locking discipline. It preserves compatibility with state-first case insertion and Journey foreign-key KEY SHARE. Bind any subsequent case/boundary locks consistently and prove composition with every resolution writer, heartbeat, watchdog and existing terminal mutation using official isolation tests.

After authoritative success, one short exact-state Room transaction establishes UNRESOLVED_INCIDENT, authoritative ended time and exact finalization/result provenance. Only server success/already-committed reconciliation may do so. Pending teardown survives a crash between local commit and service shutdown. Unknown acknowledgement shows no incident result.

Extend existing status constraints, terminal predicates, RLS forgery prevention, sync deserialization/conflict reconciliation and terminal rewrite guards narrowly. UNRESOLVED_INCIDENT is distinct from COMPLETED/CANCELLED/AUTO_ENDED. Preserve first legitimate authoritative terminal outcome; stale ACTIVE or competing terminal payload cannot overwrite it.

Unresolved verification continues to block AUTO_ENDED. Old Probable End work cannot bypass a new verification episode. AUTO_ENDED never becomes UNRESOLVED_INCIDENT; Section 10 cannot rewrite COMPLETED or CANCELLED. No new escalation status, Probable End grace window, background notification, SMS feature or Alexa capability is required.

Closing continuous monitoring must not call the existing resolver as DEVICE_CONTACT_RESTORED, JOURNEY_COMPLETED or MONITORING_STOPPED merely to reuse cleanup. Preserve unresolved case meaning with separate operational closure/provenance and an incident-access bound. Do not emit restored-contact, Traveller-safe or arrival events.

## 12. Preserved evidence and restricted access

**LOCKED D11:** preserve existing Journey/telemetry, authenticated heartbeats, monitoring/verification history, case-opening snapshots, attributed reports, audit events, fallback receipt/attempt history and terminal provenance. Access expiry does not delete this evidence. No retention duration is invented by this contract; a later retention/deletion policy must remain distinct and authorized.

**LOCKED D10:** authoritative UNRESOLVED_INCIDENT finalization starts seven elapsed days (`168 hours`, `604,800,000` milliseconds) of restricted read-only incident-detail access. Set the immutable expiry to authoritative terminalization time plus that duration, using checked arithmetic and consistent timestamp precision. Permit only while server time is strictly before expiry; deny at/after it. Reconnecting, case reopening, repeated finalization, review or acknowledgement cannot refresh it. This is not invitation TTL, the provisional 24-hour resolved-case sensitive-access TTL or a new monitoring duration.

Contact access requires authenticated verified identity, an accepted authorized relationship, exact Journey-scoped authorization, no relationship/grant revocation and an unexpired incident window on every sensitive read. Revocation or a stricter applicable expiry can end access earlier; neither can be overridden by the seven-day window. An incident window cannot reopen previously expired resolved-case access or revive a revoked grant. No public incident disclosure or emergency-service authorization is implied. Owner authentication does not grant permission to forge access/finalization provenance or confer another person's contact access.

Only factual nonsensitive summaries already permitted by existing authorization may remain outside the incident-detail window. Do not attach case identity, precise observations, movement snapshots, reports or incident-detail history to such summaries after expiry. The window creates no new Traveller history/evidence browser or Trusted Viewer UI feature.

Read-only means no post-terminal report submission through the existing report RPC either. Deny it after incident terminalization, including within the seven days. Preserve older reports. Any later post-terminal reporting capability needs separate approval. Report/finalization races must serialize so a report accepted before terminalization remains attributed history and a later submission is rejected.

**ENGINEERING RISKS:** the seven-day detail-access checks are deployed and passed hosted SQL/JWT fixture acceptance; signed-JWT HTTP and Android acceptance remain. Confidentiality still depends on complete revocation/expiry checks; TTL is not deletion; direct routes and client caches can undermine enforcement if they bypass current authority; a summary must not accidentally carry protected detail. Revocation cannot undo a disclosure or offline copy already obtained legitimately. Previously resolved evidence remains subject to its stricter existing policy, not a newly extended seven-day TTL.

## 13. Existing access gap and complete route audit

The ordinary `trusted_contact_has_journey_access` helper checks accepted relationship and Journey grant revocation but is not by itself an incident-detail deadline check. Before 10B deployment, OPEN cases could bypass the resolved-case expiry treatment. The deployed 10B migration adds a separate incident-detail predicate for verified identity, exact grant, relationship, revocation and immutable expiry, and denies post-terminal report submission. Hosted SQL/JWT fixtures accepted these protections; signed-JWT HTTP and Android acceptance remain.

The deployed 10B migration applies that predicate to case detail and case-link disclosure in snapshots. Existing status/list routes retain only bounded factual summaries with verified-identity and current grant/relationship checks; they do not use the incident-detail predicate or expose case detail. Hosted SQL/JWT fixture acceptance covered the existing versions; signed-JWT HTTP acceptance remains. Android operational stopping, terminal reconciliation and result presentation remain future 10C work.

| Existing route | Required incident treatment |
| --- | --- |
| `trusted_contact_has_journey_access(uuid, uuid)` | Retain ordinary Journey authorization meaning; use a separate detail predicate or carefully scoped terminal check, not a silent global TTL rewrite |
| `get_verification_case(uuid)` | Recheck verified identity, exact grant/revocation, current terminal incident window and existing case expiry before any points/reports/history; audit authorized disclosure |
| `get_trusted_journey_snapshot(uuid)` | No sensitive case/location/history or actionable OPEN-case expansion after incident expiry; distinguish CLOSED operations from unresolved uncertainty |
| `list_trusted_journeys()` | Exact relationship/grant checks; bounded summary only; no incident-detail escape via listing |
| `list_trusted_journey_statuses_v1()` and `get_trusted_journey_status_v1(uuid)` | Preserve nonsensitive authorized status semantics, reject unauthorized identities and strip any future incident-detail additions outside the window |
| `list_trusted_journey_statuses_v2()` and `get_trusted_journey_status_v2(uuid)` | Same checks as v1; older versions remain audited, not assumed inaccessible |
| `submit_verification_case_report(uuid, text, timestamptz, text)` | Reject incident-terminal submissions irrespective of read-window duration; race-safe with finalization |
| Direct tables/views for cases, points, reports, audits and private provenance | Keep narrow privileges/RLS; no authenticated direct detail-read/write bypass |
| Current browser calls to list/snapshot/case/report RPCs | Backend checks remain authoritative; protected cached detail must not be treated as current permission after revocation/expiry; no new frontend implementation in this slice |
| Any future wrapper/export or privileged adapter using these projections | Revalidate end-user scope, expiry and read-only behavior; never expose broader service-role results to callers |

Also audit existing notification eligibility/dispatch predicates only for correct terminal cessation and preserved attempt history. Do not add incident SMS, contact-restored/completion messages or an Alexa action as part of Section 10. Backend housekeeping authority is not end-user disclosure authority.

## 14. Result, navigation and truthfulness

Current read-only Figma reference: file `j50QnKG0lscRYPB1h9Z9uV`, Page `74:2`, Section `78:4856`.

| Frame | ID | Meaning |
| --- | --- | --- |
| Active Journey - Unresolved monitoring continues | `93:616` | Existing active Journey presentation while continuous operations truly continue |
| Result - Monitoring window ended | `78:4883` | Authoritative reconciled incident result; no pending-authority use |
| Home - No active Journey | `78:4904` | After authoritative terminal reconciliation/acknowledgement |

Current result copy, unchanged by this document:

> Monitoring window ended
>
> Alabarin stopped continuous monitoring because this Journey remained unresolved 24 hours after the expected arrival time.
>
> Uncertainty remains. This does not mean the traveller is safe, missing, or in danger.

Figma Done navigates to `78:4904`; no result Back reaction is specified. **LOCKED D12:** Done and Android Back invoke the same exact durable informational-result acknowledgement. They do not alter Journey status, ETA, case evidence, access permissions/deadline or finalization provenance. Restart before acknowledgement restores the same result. Restart afterward does not resurface it. Return to normal no-active-Journey Home only after authoritative terminalization and local reconciliation. This changes no Section 09 Back behavior.

A PENDING_AUTHORITY or stopped operational-limit Journey must not use the no-active Home or continue showing "Alabarin keeps monitoring" while collection is stopped. Resolved/absent-eligibility recovery and the two established explicit choices are locked in sections 7/10. Exact pending/operational-limit copy and its layout remain UI COPY REVIEW before runtime UI is enabled; they must not claim an authoritative incident outcome.

Keep Section 09's repaired adaptive/scrollable layout quality: full body/caveat and Done/Back access, safe drawing insets, long destination wrapping and durable scroll/result identity. Required viewport acceptance includes 320 x 480 at 1.5x and 2.0x font, 360 x 640 and 390 x 844. The current 15px amber caveat on the off-white Figma background is approximately 4:1 contrast, below the existing 4.5:1 normal-text target; flag an accessible treatment for UI review, not a silent Figma edit. The amber exclamation must not become evidence of danger. No countdown, safety claim, missing-person label or permanent history screen is added.

## 15. Failure isolation, privacy and restoration

Read-only architecture checks against the source baseline, not a new hosted/runtime acceptance:

| Area | Current implementation | Required integration/validation |
| --- | --- | --- |
| ETA/admission | `JourneyLifecycle` validates and persists ETA locally; deployed 10A hosted admission provenance freezes qualifying ETA while ACTIVE and leaves historical rows unaltered | Preserve delayed valid offline upload and reject invented historical authority in 10B/10C paths |
| Clock | The 10A foundation persists authenticated server observation; 10C-1 adds a separate admission monotonic anchor and paused-operation state | No same-boot UTC extrapolation; approximate privacy pause is distinct from server proof |
| Active slot/restoration | Room `JourneyDao` retains one unique active slot; 10C-1 adds a collectable-Journey projection and pause guards, without invoking automatic pause in production | Keep the slot occupied; activate pause only with truthful pending UI and authority reconciliation |
| Explicit intent | Completion is local-first; `JourneyLifecycle.stopMonitoring` requires confirmed server ended time; local-only sign-out returns ConnectionRequired | Add exact pending stop-intent recovery for Section 10 without fabricated CANCELLED or offline account sign-out |
| Terminal/sync | AUTO_ENDED has exact durable claim, server-authoritative reconciliation and monotonic hosted terminal protection; deployed 10B adds a distinct server-only incident finalizer and extends hosted terminal protection | 10C must add durable device claim, explicit-intent exclusion and Android reconciliation before client use |
| SQL serialization | AUTO_ENDED locks Journey NO KEY UPDATE then monitoring; case opening uses monitoring state first and checks ACTIVE | Validate extension against all case/report/resolution writers using official isolation tests, not assumed lock compatibility |
| Sensitive access | Deployed 10B closes the OPEN-case expiry bypass, caps grants, gates case/snapshot detail and denies post-terminal reports; hosted SQL/JWT fixtures passed | Complete signed-JWT HTTP and Android acceptance; preserve the separate resolved-case TTL |

No approved rule is shown to be materially impossible by these checks. Hosted 10B SQL/JWT fixture acceptance is not signed-JWT HTTP or Android runtime acceptance. Stop the affected implementation slice if a safe mechanism cannot preserve a locked rule.

Before valid operational stop, optional eligibility/time reconciliation must not drop telemetry, block sync or crash core monitoring. After the stop, durable operational state must prevent location/heartbeat/fallback restart despite Journey.status remaining ACTIVE. Database truth and recoverable effects, not in-memory flags or WorkManager uniqueness, enforce this distinction.

Use bounded lifecycle/connectivity/restoration reconciliation, not a new aggressive polling loop. Operational-boundary work binds exact Journey, ETA/boundary policy and operational revision; it cannot require a known case identity. Incident-finalization work additionally binds the exact current verification episode/case and eligibility revision. Early/stale/duplicate work is harmless; late work preserves the original boundary. Android work is delivery machinery, not time or terminal authority. Propagate coroutine cancellation correctly; do not convert cancellation into permission or proof.

Do not put coordinates, destination labels, provider/case contents, reports or tokens in boundary/worker inputs, generic logs, errors or toString output. Opaque IDs, clock/boundary timestamps, bounded reasons, versions and revisions suffice. Precise retained evidence remains in existing authorized storage. No service-role credential belongs in Android.

No physical reboot, TalkBack or new hosted-runtime pass is claimed by this 10C-1 update. Previous Section 09 device/SQL/hosted evidence remains valid for unchanged behavior, not proof of a new Section 10 client path. Hosted 10B parity and SQL/JWT fixture acceptance are recorded above; signed-JWT HTTP and Android runtime acceptance remain separate.

## 16. Implementation strategies to validate: slice boundaries

The committed 10A foundation uses Room v19; deployed 10B adds server terminal provenance without backfilling existing data. 10C-1 adds an additive local operational-state migration and internal pause machinery, but leaves its production automatic trigger inactive until truthful UI and reconciliation ship. 10C-2/10C-3 still own finalization initiation, explicit-intent recovery and result. Additive migrations must fabricate no historical boundary authority or incident.

### 10A - Immutable ETA, eligibility and durable boundary foundation

Own validated original ETA authority/provenance, the 72-hour admitted ETA cap, exact ETA + 24-hour boundary, typed coherent unresolved observation and episode supersession, and minimal durable authenticated UTC observation with explicit `INSUFFICIENT_PROOF`. The server freezes admitted ETA and exposes an owner-scoped read, not a future finalization lease. No same-boot UTC lower-bound extrapolation, runtime stop, pending state or terminal path is activated. Legacy rows gain no invented admission authority.

Non-goals: UNRESOLVED_INCIDENT terminal mutation, incident access activation, final/pending runtime UI, unattended operational shutdown, new escalation/Viewer/SMS/Alexa behavior or Section 09 refactor. Stop if reliable proof cannot be validated, an ETA model requires guessed historical authority, delayed offline admission cannot retain its true ETA, or persistence would weaken the occupied-slot invariant. Product decisions above are locked; do not reopen them as implementation proposals. Commit boundary: foundation, additive migrations and deterministic policy/time/migration tests reviewed together; deployment separately authorized.

### 10B - Authoritative terminal operation and protected evidence

Own server-atomic idempotent finalization, status/terminal monotonicity, coherent case/monitoring closure provenance, immutable timing, locked seven-day detail-access/read-only enforcement across all routes and necessary sync/status interoperability. Preserve all old outcomes and evidence. No generic owner-forged incident write, independent server sweep, new notification/reporting capability or frontend expansion.

Stop on lock inversion, ambiguous explicit-action winner, an unenforceable access route, case closure that fabricates resolution or unsafe old-client writes. Commit boundary: minimum server/status interop changes with complete pgTAP/isolation/auth/evidence-preservation tests. Do not deploy a new terminal outcome into normal client use until client reconciliation/teardown/presentation is ready and separately accepted.

### 10C - Operational stop, device initiation, result and full acceptance

Own locked PENDING_AUTHORITY runtime behavior, stopped resolved/absent-eligibility recovery, blocked second admission, exact worker/claim execution, durable offline explicit-intent precedence, uncertain-ack recovery, local authoritative reconciliation, recoverable teardown, reviewed pending/final UI, Done/Back acknowledgement and full acceptance. All collection/heartbeat/degradation/fallback entry and restoration paths must honor the operational stop even while Journey status remains ACTIVE.

Non-goals: unrelated Journey/UI redesign, permanent history, Trusted Viewer work, new SMS/Alexa features or Section 09 behavior changes. Stop if exact pending/late-episode copy is not reviewed, UI cannot truthfully represent stopped operations with ACTIVE server status, or any restart path resumes collection after a valid stop. The recovery and late-episode policies are already locked. Commit boundary: full-flow, privacy, race/recovery, migration, Redmi and separately authorized hosted acceptance green; never infer closure from builds alone.

## 17. Acceptance requirements

| Area | Direct evidence required during implementation |
| --- | --- |
| Eligibility | Every matrix row; current VERIFYING plus current OPEN; incoherent combinations fail closed; human report does not resolve; resolved episode never reused |
| ETA | Atomic admission snapshot; ACTIVE ETA update rejected; delayed legitimate upload preserves original ETA; invalid/overflow/historical provenance fail closed |
| Time | 10A: fresh authenticated server sample before/exact/after original boundary; same-boot/reboot restoration without elapsed UTC extrapolation; wall-clock jumps; missing/corrupt observation; no receipt-time-plus-duration substitution |
| Operational stop | Authenticated boundary proof, approximate same-boot cap, or lost/invalid anchor creates only PENDING_AUTHORITY when automatic activation is approved; only authenticated server time proves boundary passage; evidence/pending transport preserved; location/heartbeat/new degradation/fallback allocation stop; no incident result or second admission |
| Restart | Pending state prevents service restart, preserves occupied slot and same boundary; uncertain claims/effects/results recover; no guessed powered-off duration |
| Resolution/late episode | Resolution/absent eligibility keeps collection stopped with truthful operational-limit state and explicit choices; old case identity is superseded; a genuinely new coherent late episode may qualify immediately, never by reusing resolved history |
| Explicit intent | Durable offline completion/stop intent first blocks claim; queued Stop does not invent CANCELLED/free the slot; claim first blocks partial late callback; in-flight uncertain explicit request reconciled first; delayed sync preserves intent and server monotonicity |
| Server races | Official isolation: finalizer versus heartbeat/resolution, watchdog/OPEN insertion, COMPLETED, CANCELLED, AUTO_ENDED, duplicate finalizer and stale owner sync |
| Atomic/retry | Same authoritative finalization outcome/provenance on retry; lost acknowledgement keeps claim; no local incident before server success; no duplicate teardown/result |
| Evidence/access | Preserve cases/reports/points/events/receipts; exact immutable terminal time + 168-hour access window; before/exact/after expiry; reconnect/reopen cannot refresh; all RPC/wrapper/direct-table paths reject expired/revoked detail even for OPEN cases; stricter resolved expiry not reopened; terminal report rejected |
| Security | Verified exact owner/contact; wrong-owner and nonexistent nondisclosing; anonymous rejected; direct forgery blocked; controlled definer/search_path/grants; no sensitive payload/log leak |
| UI/device | Truthful pending/final labels, complete caveat at large fonts, contrast/touch/focus, result recreation, Done/Back durable acknowledgement and normal Home admission only after legitimate closure |

Use Kotlin deterministic clock/policy tests, Room migration/reopen/fault tests, Redmi instrumentation, pgTAP and official PostgreSQL isolation tests. Hosted acceptance requires fresh exact project/history/hash parity and original-36 preservation evidence before/after, only disposable authorized fixtures and safe cleanup. No unnecessary real 24-hour wait: controlled local/server fixture clocks or historical fixture inputs are permissible only under the specifically approved acceptance contract, without weakening production timing or backdating production rows.

## 18. Locked decisions, remaining engineering risks and next action

All D01-D15 product decisions and the later two-clock operational privacy policy are locked. They do not claim production automatic pausing is active. Exact late-episode and pending/operational-limit copy/layout must be reviewed against current Figma before 10C runtime/UI activation; do not invent uninterrupted unresolved history. The amber uncertainty-caveat contrast and compact/adaptive layout remain future UI acceptance gates, not authorized Figma edits.

Remaining engineering risks: activate the approved approximate policy only with truthful pending UI; preserve the Room active slot while separating service permission from Journey status; integrate durable offline stop intent with current server-acknowledged cancellation; prove device claim/terminal lock order and uncertain-ack recovery; complete signed-JWT HTTP and Android acceptance for protected access. Do not weaken a locked rule to accommodate a shortcut. If a material conflict is discovered, stop the affected slice and report it.

The revised **10A - immutable ETA, eligibility and durable boundary foundation** was separately authorized, committed and deployed. The deployed 10B migration adds an exact owner finalizer that locks Journey, monitoring state and current case; it rechecks 10A eligibility under server time, commits distinct UNRESOLVED_INCIDENT with immutable case/boundary/timestamp provenance, preserves the OPEN case, and caps accepted-contact incident detail access at 168 elapsed hours. Hosted migration parity is 26/26 and hosted SQL/JWT fixture acceptance passed. Android has not invoked that finalizer. Section 09 engineering closure remains unchanged.

**Product-contract review decision:** SECTION 10 CONTRACT LOCKED - READY FOR 10A was the earlier reconciliation outcome. The first 10A investigation stopped on the clock-proof limitation recorded in section 6; the revised foundation and 72-hour maximum ETA were then authorized. The later two-clock decision permits an approximate local operational cap without UTC authority. 10C-1 machinery is not automatically activated in the production UI; Android incident reconciliation and result UI remain future work.
