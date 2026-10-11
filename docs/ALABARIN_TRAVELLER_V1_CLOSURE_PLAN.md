# Alabarin Traveller v1 Closure Plan

**Status:** Active working plan
**Current Traveller position:** Page 05 Sections 09 and 10 implemented; 10C-3C local acceptance passed; Traveller v1 closure and hosted 10C parity remain open.
**Purpose of this file:** Make sure we do not call Traveller v1 finished just because the screens and flows exist.

**Implementation checkpoint — 2026-10-11:** Section 09 `AUTO_ENDED` remains the distinct guarded 30-minute Probable End outcome (`03102c7`, `132142a`). Section 10 `UNRESOLVED_INCIDENT` is a separate server-authorized ETA-plus-24-hour outcome; neither establishes arrival or safety. The 10C-3A pending-authority screens and reviewed Figma handoff are committed at `732ed2e`. The 10C-3B privacy cutoff, automatic authority reconciliation and process/boot recovery are committed at `addb40a`, `1bf3721` and `96c1176`. A pending local Stop retains `ACTIVE` and the occupied slot until server acknowledgement; offline Complete retains its separate local-first intent. The authenticated Android-to-Docker Section 09/10, owner, retry, offline-intent and Room checks passed in `8801b74`; see [10C-3C local acceptance](ALABARIN_10C3C_LOCAL_DOCKER_ACCEPTANCE.md). These commits supersede older future-work statements below, which remain dated implementation history. Local acceptance does **not** verify hosted 10C migration/RPC/Android parity.

**Physical Slice D reboot evidence — 2026-10-11:** Retained files under `%TEMP%/alabarin-slice-d-20261011-110817` include `preboot-bootcount.txt` (178), `postboot-broadcasts-beforelaunch.txt` (the app's `BOOT_COMPLETED` receiver pending), `postboot-logcat-afterdispatch.txt` (receiver process start and recovery worker `SUCCESS`), and before/after Room snapshots. A read-only check of the connected Redmi on 2026-10-11 returned boot count 179. The saved snapshots show the same Journey staying `ACTIVE` with `activeSlot = 1` while operational state changed from `MONITORING` to `PENDING_AUTHORITY` with `LOST_OR_INVALID_BOOT_ANCHOR`; after connectivity returned, the authority wake finished without a fabricated terminal state. Service/location inspections observed no monitoring restart in this run. The automated `JourneyProcessRecoveryAcceptanceTest` exercises controlled process restoration and explicitly does not simulate a reboot. This one physical run does not establish exact reboot wake timing, every OEM behavior, or a platform scheduling guarantee.

Traveller v1 is only closed when the product is:

- functionally complete
- understandable in purpose, not only easy to operate
- truthful about what Alabarin knows and does not know
- native-feeling on Android
- tested on a real device
- stable across auth, connectivity, monitoring and terminal-state edges

---

## 1. When this closure plan starts

Finish the remaining current Traveller Page 05 sections first:

- [x] **08 - Connectivity states - system-triggered changes**
- [x] **09 - Probable end - separate branch** (engineering reacceptance, 2026-10-09; broader frontend closure remains pending)
- [ ] **10 - Unresolved boundary - separate branch** (implementation and authenticated local acceptance passed 2026-10-11; hosted parity and wider Traveller closure remain open)

After Section 10, **do not immediately jump to Trusted Viewer, TextBee or Alexa+**.

Traveller first goes through the closure passes in this document.

---

# 2. The most important closure question: does the user understand why Alabarin exists?

This is not the same as usability.

A user may understand:

- how to create an account
- how to start a Journey
- how to add Trusted Contacts
- how to open the active Journey
- how to end or stop monitoring

...and still not actually understand **what Alabarin is for**.

That would mean the UI is usable but the product has not communicated its purpose.

## Our goal

A new user should quickly understand something close to:

> **Alabarin helps the people you trust keep the thread of your road Journey when normal communication becomes unreliable.**

They should also understand that:

- Alabarin works around a specific **Journey**
- monitoring does not begin merely because the app is open
- trusted people receive only authorized Journey information
- Alabarin preserves verified information and continuity when normal communication weakens
- Alabarin does not independently decide that the Traveller is in danger
- Alabarin does not independently confirm safe arrival
- Alabarin is not public tracking
- Alabarin is not permanent surveillance
- Alabarin is not an emergency-service replacement

The product should communicate this **without filling every screen with explanation text**.

The first-use experience should do most of the conceptual work. Later screens should reinforce meaning only where the state itself requires it.

---

# 3. First-use comprehension: the first three conceptual pages

We previously agreed that the first three pages have a special job. They are not just onboarding decoration.

They should establish the product's mental model before permissions, readiness, setup and routine use.

## Page 1 - Why Alabarin exists

**Job:** Explain the purpose.

Agreed direction:

> **Alabarin helps the people you trust keep the thread of your road Journey when normal communication becomes unreliable.**

The user should leave this screen understanding:

- this is about a road Journey
- trusted people matter
- continuity matters when communication becomes unreliable
- Alabarin is there to accompany the Journey, not to act as a general tracker

### Closure audit question

After seeing only Page 1, can a new user roughly answer:

> "What is Alabarin for?"

If the answer is only "tracking a Journey", the purpose has not been communicated well enough.

---

## Page 2 - When Alabarin operates

**Job:** Explain that monitoring is Journey-scoped.

Agreed direction:

> **Only while a Journey is running**

Supporting meaning:

> Monitoring starts when you start a Journey and stops when the Journey ends. Opening the app alone never starts monitoring.

The user should understand:

- monitoring is bounded
- monitoring is not permanent
- simply installing or opening Alabarin does not mean continuous tracking
- the Journey is the monitored object

### Closure audit question

Can a new user answer:

> "When is Alabarin monitoring me?"

without guessing that it is always running?

---

## Page 3 - Control, authorization and limits

**Job:** Explain who can see Journey information and what Alabarin cannot conclude.

Agreed direction:

> **You stay in control**

Supporting meaning:

> Trusted people only get authorized Journey information. Alabarin does not decide that you are in danger or independently confirm safe arrival.

The user should understand:

- Trusted Contacts are relationship-authorized
- Journey information is not public
- Alabarin reports evidence and state
- Alabarin does not invent conclusions from uncertainty

### Closure audit question

Can a new user explain:

- who gets Journey information?
- whether Alabarin decides they are in danger?
- whether Alabarin can independently confirm they arrived safely?

If not, the conceptual onboarding needs work.

---

# 4. Purpose comprehension audit after all Traveller screens are complete

Once Traveller implementation is functionally complete, review the full experience as **one product**, not as isolated screens.

The audit should test whether the purpose introduced in the first three pages survives the rest of the app.

## Core comprehension checks

- [ ] A new user can explain what Alabarin is for after roughly the first-use experience.
- [ ] Home does not reduce the product to a generic tracker or utility dashboard.
- [ ] Starting a Journey feels like intentionally beginning accompaniment/monitoring.
- [ ] The active Journey makes clear that Alabarin is preserving Journey understanding, not claiming omniscience.
- [ ] Trusted Contacts feel like authorized trusted people, not public viewers.
- [ ] Resilience/connectivity states reinforce why Alabarin exists without overexplaining.
- [ ] Account and auth flows do not distract from the product's mental model.
- [ ] Terminal states preserve the difference between "monitoring ended" and "the Traveller is safe."

## Truthfulness checks

- [ ] **Last verified is not presented as current.**
- [ ] Poor connectivity does not imply danger.
- [ ] Fallback unavailable does not imply monitoring stopped.
- [ ] Fallback attempted or handed off does not imply delivery to a recipient.
- [ ] Recovery does not imply safety.
- [ ] Recovery does not imply arrival.
- [ ] Recovery does not imply successful contact with Trusted Contacts.
- [ ] `COMPLETED` does not automatically mean safe arrival.
- [ ] `CANCELLED` means intentional monitoring stop, not danger or failure.
- [ ] `AUTO_ENDED` does not imply arrival or safety.
- [ ] `UNRESOLVED_INCIDENT` does not imply safe, missing or in danger.

## Product-feeling checks

No screen should accidentally make Alabarin feel like:

- a public tracking map
- permanent surveillance
- a kidnapping detector
- a crime prediction system
- an emergency-service replacement
- a system that "knows" safety or danger beyond the evidence available

## Audit output

Create a short issue list divided into:

1. **Purpose comprehension**
2. **Truthfulness**
3. **Terminology**
4. **State consistency**
5. **Only-if-needed copy changes**

Do not redesign stable screens merely for taste.

The goal is not to explain more everywhere.

The goal is to make the product's purpose unmistakable with the **least necessary explanation**.

---

# 5. Native Android interaction-feel pass

After the comprehension audit and any structural/copy fixes, perform a dedicated **feel** pass on the real Android app.

Figma is still the visual source of truth, but this pass cannot be approved from Figma.

## Target feeling

Alabarin should feel:

- immediate under the finger
- substantial enough to feel deliberate
- smooth enough that motion disappears from attention
- restrained
- calm
- native to Android

Not flashy.

Not full of animation for animation's sake.

A good interaction should feel roughly like:

`touch -> immediate response -> short deliberate transition -> settled state`

## What to inspect

### Press response

- [ ] Buttons respond immediately on touch-down.
- [ ] Rows and text actions have appropriate pressed/ripple feedback.
- [ ] Destructive actions feel deliberate without feeling sluggish.
- [ ] Disabled actions look and behave clearly disabled.

### Motion and weight

- [ ] Bottom sheets enter and settle naturally.
- [ ] Dialogs do not pop in abruptly.
- [ ] Screen/state changes do not feel like web-page replacements.
- [ ] Springs/easing are short and controlled.
- [ ] No unnecessary bouncing or decorative animation.

### Bottom-sheet physics

Audit at minimum:

- Account
- Active Journey
- Edit identity
- any confirmation sheets/dialogs

Check:

- drag response
- release/settle behavior
- back dismissal
- scrim response
- interruption by another state

### State transitions

Check transitions such as:

- Home -> Open Journey
- Home -> Account
- Account -> Edit
- Account -> sign-out confirmation
- authenticated -> reauthentication-needed
- healthy -> degraded
- degraded -> recovering
- Journey active -> terminal result
- result -> Home

Transitions should not cause awkward layout jumps or flicker.

### Haptics

Use only where meaningful.

Do **not** add haptics everywhere.

Potential candidates should be evaluated on device rather than assumed.

### Android system behavior

- [ ] Back button behavior
- [ ] predictive/system back gesture behavior
- [ ] keyboard/IME opening and closing
- [ ] focus movement
- [ ] edge-to-edge layout
- [ ] status/navigation bar handling
- [ ] overscroll and scrolling
- [ ] touch targets

### Performance feel

Inspect for:

- [ ] dropped frames
- [ ] delayed press response
- [ ] unnecessary recompositions
- [ ] state changes rebuilding too much UI
- [ ] main-thread work during taps/transitions
- [ ] foreground-service updates causing visible UI churn
- [ ] animations restarting unnecessarily

Do not confuse bad frame pacing with bad animation design.

---

# 6. Real-device Traveller acceptance

Use the Redmi as the main Traveller acceptance device.

Fixture tests are useful, but critical Traveller behaviors should be exercised as real Android flows where technically safe.

## Core real-device flows

- [ ] Create account
- [ ] Returning login
- [ ] Same-owner recovery
- [ ] OTP entry with real keyboard/IME behavior
- [ ] Home
- [ ] Start Journey
- [ ] Return Home during an active Journey
- [ ] Open Journey
- [ ] Process/app recreation with the same active Journey restored
- [ ] Trusted Contacts
- [ ] Resilience
- [ ] Account
- [ ] Edit identity
- [ ] No-active-Journey sign out
- [ ] Active-Journey sign out
- [ ] Reauthentication-needed state
- [ ] Same-owner reauthentication
- [ ] Connectivity states
- [ ] Probable-end flow
- [ ] Unresolved boundary
- [ ] Result screens
- [ ] Back, dismiss, retry and double-tap boundaries

---

# 7. High-priority acceptance debts already known

These should not be forgotten merely because fixture/device presentation tests passed.

## Section 09 final full-flow frontend/truthfulness worklist

Observed on 2026-10-08 at `03102c7ad6c3427b5daffe1017ad2f27dc6c2f42`. The first two issues blocked final full-flow sign-off despite durable engineering evidence. Their narrow UI repairs passed final reacceptance on 2026-10-09; Section 09 engineering is closed, while broader Traveller frontend/accessibility closure remains pending. Current Figma was inspected read-only and was not redesigned.

| Issue | Severity / closure impact | Evidence and smallest proposed repair |
| --- | --- | --- |
| Enabled probable-end primary action is almost invisible in MainActivity | Originally high; resolved and reaccepted on 2026-10-09 | Original settled reproduction: `%TEMP%/section09-final-flow/fresh-uid/story-a-prompt.png`. The enabled transition removed the disabled graphics layer while fill/border painting preceded it. Persistent layers with paint inside now retain Figma `93:688` dark primary and outlined secondary. A pixel regression fails on the old implementation and passes through settled/recomposed/in-flight/reopened states; enabled white text on `#1B3250` measures 12.97:1. Fixed MainActivity captures are `%TEMP%/section09-ui-repair/story-a-prompt{,-recreated,-reopened}.png`. Real completion/Done remains exact and durable. No response/domain changes. |
| Automatic result loses caveat and Done at short height/larger fonts | Originally high; resolved and reaccepted on 2026-10-09 | Original: `%TEMP%/section09-final-flow/fresh-uid/layout-automatic-320-480-font1.5.png`. Bounded column measurement and fixed summary rows supplied no scroll route. Shared inset-aware result scrolling now preserves normal geometry, expands text, stacks compact summaries and restores episode scroll position. Both results pass 320x480 / 1.5x and 2x, 360x640 / 1x, and 390x844 / 1x with long labels and reachable full-sized Done. Scrolled automatic captures are `%TEMP%/section09-ui-repair/repair-automatic-320-480-font1.5-{caveat,done}.png`. Done acknowledges exact Room provenance without changing AUTO_ENDED. No copy/terminal-policy redesign. |
| Home snackbar covers Start Journey after Done while contacts are unavailable | Medium; existing frontend closure debt | `%TEMP%/section09-final-flow/story-a-home.png`: correct no-active Home and durable acknowledgement, but the contacts-unavailable snackbar obscures the primary action. Review snackbar placement/duration without changing ownership or Journey lifecycle. |
| Success-like checkmark on automatic result | Deferred truthfulness review; engineering classification retained | Current Figma `78:4796` and the authenticated Redmi result both retain the mark and exact uncertainty text. Do not treat it as arrival/safety proof. The small-height missing caveat compounded this risk; the layout repair above makes that caveat reachable. Reconsider the mark in the later frontend/truthfulness pass; do not silently edit Figma. |

The explicit completion and Keep monitoring checks use production MainActivity/Room/service with declared local evidence/auth/guard test seams; they are not newly authenticated hosted A/B tests. Story C reuses the genuine hosted 30-minute acceptance and result restoration/Done proof. Original hosted population remains 36 with preserved fingerprints; no new hosted fixture or deployment was needed. See [Probable End contract: final full-flow record](ALABARIN_PROBABLE_END_CONTRACT_V1.md#section-09-final-full-flow-acceptance-2026-10-08) for evidence boundaries and recovery-test limits. Section 10 has not begun.

The later clean-UID Redmi retry passed both functional stories and the independent result/Done check, while reproducing both high-severity defects. Use `%TEMP%/section09-final-flow/fresh-uid/story-a-prompt.png` (two-second settled capture) and `layout-automatic-320-480-font1.5.png` as the latest reproductions. The temporary package was removed; the normal Alabarin package was not replaced.

The 2026-10-09 repair also diagnoses the earlier automatic-result Compose test stall as MIUI's synchronous ActivityScenario launch waiting before composition; explicit foreground test-host launch restores the retained test. Device tests cover settled rendered colors, exact response callbacks, full text/Done reachability at the four requested viewport/font combinations, Activity scroll restoration, and durable automatic-result acknowledgement. These are isolated local tests with declared time/auth/guard seams, not new hosted automatic-ending evidence or a TalkBack walkthrough. The normal Alabarin package, protected local configuration/artifacts and historical UX documents remain untouched. The separate snackbar/checkmark work remains deferred; Section 09 is not marked closed by this repair.

Final repair checks passed 38/38 targeted Redmi tests, the MainActivity completion story and the viewport capture test, plus 596/596 JVM tests (112 Probable End) and both debug APK builds. The two blocking repairs are **READY FOR SECTION 09 REACCEPTANCE**; existing scope/recovery limits and the separate frontend worklist remain in force.

Final reacceptance on 2026-10-09: **SECTION 09 CLOSED** for engineering. The five-test relevant UI group, viewport captures and real-touch MainActivity completion/Done story passed. Reviewed result screens preserve full text and reachable Done at all four requested viewport/font combinations, with recreation and immutable terminal status. Story B and the genuine authenticated 30-minute Story C are reused on unchanged domain/server code; previous 38/38 Redmi and 596/596 JVM/build evidence remains valid. No UI tests were skipped; no hosted test, deployment or WSL suite was needlessly repeated.

The compact checkmark remains deferred because the first viewport explicitly describes monitoring ending without claiming safe arrival, and the full caveat is reachable. The Home snackbar can obscure Start Journey for this Redmi configured 120000ms accessibility timeout; without changing that setting, a real touch opened Start Journey after the interval. This is finite obstruction, still a placement/accessibility debt. A five-second acceptance probe failed for that reason; a separate temporary assertion also needed to await the asynchronous durable teardown marker. Neither required a production change. No OS reboot or TalkBack walkthrough is claimed. See the canonical contract closure record and `%TEMP%/section09-ui-repair/reacceptance` for evidence. Both original defects, all earlier findings and the broader Traveller closure checklist remain preserved. Section 10 is pending and separately authorized, not started.

## Active-Journey sign out - real hosted path

Still prove using a safe disposable real Journey:

`hosted CANCELLED + authoritative ended_at`
`-> exact Room endedAt`
`-> monitoring service teardown`
`-> LOCAL signout`
`-> Monitoring stopped result`
`-> same-owner recovery`

This is a Traveller closure acceptance target.

---

## Same-owner reauthentication during a real active Journey

Still prove, where practical:

`active foreground-service Journey`
`-> auth session genuinely unavailable`
`-> local monitoring continues`
`-> same-owner OTP reauthentication`
`-> same Journey retained`
`-> owner-scoped sync/reconciliation resumes`

Fixture-only UI proof is not the final acceptance proof.

---

## Live SMS fallback

This is **not** a Traveller v1 closure blocker.

Current production truth remains:

- local monitoring continues
- live SMS fallback route is not yet provisioned
- fallback-available presentation may exist as a future/fixture state
- production must not claim it is available

TextBee/live SMS integration happens after Traveller v1 closes.

---

# 8. Reliability and regression closure pass

After purpose and feel changes, rerun the core safety boundaries.

## Ownership

- [ ] Persisted owner A remains A after ordinary sign out.
- [ ] Candidate B cannot adopt A's installation.
- [ ] A's Journey is never relabelled as B's.
- [ ] A's local Journey is never uploaded under B.
- [ ] No closure work introduces owner release.

## Auth

- [ ] Unexpected auth loss never terminalizes an active Journey.
- [ ] Unexpected auth loss never stops local monitoring.
- [ ] Auth initialization is not mistaken for auth loss.
- [ ] Network failure alone is not mistaken for auth loss.
- [ ] Same-owner recovery remains fail-closed.

## Active sign out

- [ ] No offline active-Journey sign out.
- [ ] No pending cancellation.
- [ ] No fabricated `ended_at`.
- [ ] No `GLOBAL` signout.
- [ ] No duplicate remote stop on retry after an already authoritative remote `CANCELLED`.

## Terminal behavior

- [ ] Terminal states remain monotonic.
- [ ] Stale `ACTIVE` cannot reopen a terminal Journey.
- [ ] `CANCELLED` uses authoritative `ended_at`.
- [ ] `COMPLETED` semantics remain separate.
- [ ] `AUTO_ENDED` and `UNRESOLVED_INCIDENT` semantics do not collapse into `COMPLETED`.

## Connectivity

- [ ] Connectivity degradation alone never terminalizes the Journey.
- [ ] Local monitoring continues through degradation.
- [ ] Startup uncertainty is not presented as verified current health.
- [ ] Recovery requires real recovery evidence before `HEALTHY`.
- [ ] Current fallback capability is derived truthfully.

## Layout/accessibility

Test at minimum:

- [ ] 320dp width
- [ ] 360dp width
- [ ] 390dp reference width
- [ ] shorter screen height
- [ ] keyboard open
- [ ] larger font scale
- [ ] touch target sanity
- [ ] focus order
- [ ] contrast
- [ ] content descriptions where required

No skeleton-loading pattern should be introduced.

---

# 9. Final Traveller v1 closure record

Only say **"Traveller v1 closed"** after a final closure record exists.

That record should include:

- [ ] final Page 05 Traveller section/state coverage
- [ ] current commit considered Traveller v1 closure point
- [ ] comprehension/purpose audit result
- [ ] native Android feel audit result
- [ ] real-device acceptance result
- [ ] regression/test results
- [ ] intentional Figma deviations
- [ ] known technical limitations
- [ ] explicitly deferred work
- [ ] external dependencies still outstanding
- [ ] screenshot/artifact paths for important acceptance evidence

**"All screens implemented" is not the closure criterion.**

---

# 10. What happens after Traveller v1 closes

Default direction:

1. **Traveller v1 closure approved**
2. **Trusted Viewer**
3. **TextBee / live SMS route**
4. **Alexa+ / MCP agent integration**
5. **Hackathon end-to-end integration and demo hardening**

Trusted Viewer and TextBee order may move slightly if a real dependency requires it, but Traveller closure comes first.

Alexa+ should not jump ahead of the core product surfaces it depends on.

---

# 11. Deferred post-Traveller work

## Trusted Viewer

Relationship-authorized trusted-person surface.

Must preserve:

- no public lookup
- sparse healthy-state information
- verified evidence over speculation
- last verified vs current distinction
- no automatic safety/danger conclusions
- trusted reports do not overwrite device truth

---

## TextBee / live SMS route

Planned interim live route:

`Traveller Android`
`-> carrier SMS`
`-> gateway Android/SIM`
`-> TextBee`
`-> signed webhook`
`-> canonical JC1 processor`
`-> Alabarin backend`

This is what will eventually make the "fallback available" state truthfully live.

---

## Alexa+ / MCP

Alexa+ is a conversational interface over authorized Journey information.

It must remain bounded by Trusted Viewer authorization.

Planned tools include:

- `list_authorized_journeys`
- `get_journey_status`
- `get_verification_evidence`
- `prepare_trusted_report`
- `confirm_trusted_report`

Alexa must not:

- start or end a Journey
- declare the Traveller safe
- declare the Traveller in danger
- independently close verification
- change telemetry/fallback state
- bypass trusted-person authorization

---

# 12. Working source of truth

Current Traveller design source:

**Alabarin - Implementation Handoff v1**
**Page 05 - Traveller - Typography & Spacing Study**
**Node `74:2`**

Before every remaining Traveller UI slice:

- inspect current Page 05 directly
- follow current Figma rather than stale notes
- do not implement from old `10:*` nodes
- if Figma conflicts with product truth, preserve truth and document the intentional deviation

---

# 13. Final closure checklist

- [x] Section 08 implemented and closed
- [x] Section 09 implemented and closed (engineering reacceptance, 2026-10-09)
- [ ] Section 10 implemented and closed (implementation/local acceptance passed; hosted parity and Traveller closure not yet verified)
- [ ] first-three-pages purpose comprehension audited
- [ ] whole-product purpose comprehension audited
- [ ] product can be understood without explanations scattered everywhere
- [ ] truthfulness audit passed
- [ ] native Android interaction-feel pass completed
- [ ] real-device acceptance completed
- [ ] high-priority acceptance debts exercised where technically available
- [ ] regression and reliability pass completed
- [ ] layout/accessibility sanity pass completed
- [ ] intentional Figma deviations documented
- [ ] deferred work documented
- [ ] final Traveller v1 closure record created
- [ ] Traveller v1 officially closed
- [ ] primary implementation focus moves to the post-Traveller roadmap

## Closure assessment — 2026-10-11

This assesses the existing gates above; unchecked boxes are not approved by implementation alone.

| Existing requirement | Assessment |
| --- | --- |
| Page 05 Sections 08 and 09 | **COMPLETE** for their recorded engineering gates. Section 09 remains distinct from Section 10. |
| Page 05 Section 10 | **PARTIAL:** committed 10C-3A/3B behavior and 10C-3C authenticated local acceptance passed; Android-to-hosted parity and whole-Traveller closure are unverified. |
| First-three-page and whole-product purpose comprehension | **NOT IMPLEMENTED as closure audits:** Figma/screens and UI tests exist, but no recorded new-user comprehension assessment or resulting issue list closes sections 3–4. |
| Truthfulness and product feeling | **PARTIAL:** selected terminal/pending copy and device tests passed; the Section 09 compact checkmark, Home snackbar, late-episode Section 10 wording and full-product truthfulness review remain open. The dedicated native Android feel pass in section 5 is **NOT IMPLEMENTED** as a closure pass. |
| Real-device Traveller flow matrix and high-priority debts | **PARTIAL:** Milestone 5/6, Section 09, 10C local Android and the bounded physical reboot have evidence; the full section 6 sweep, active sign-out hosted path, genuine same-owner reauthentication during an active Journey, and remaining snackbar/checkmark review are not closed. |
| Reliability, regression and layout/accessibility | **PARTIAL:** targeted Room, UI, SQL and Redmi checks exist, including selected 320/360/390dp result layouts; the section 8 pass after any closure changes, full keyboard/focus/touch coverage and a TalkBack walkthrough are not recorded as complete. |
| Hosted 10C parity and real hosted auth/network flows | **RELEASE-ONLY evidence still required** for hosted acceptance; local Docker and the physical reboot cannot substitute. The existing plan calls active sign-out a Traveller closure target, so moving that target after a Trusted Viewer development start needs an explicit sequencing decision, not an assumed pass. |
| Live SMS fallback | **OBSOLETE OR SUPERSEDED as a Traveller v1 blocker:** section 7 already explicitly defers the live route until after Traveller closure; keep its truthful unavailable presentation rule. |
| Final closure record, approval and focus shift | **NOT IMPLEMENTED:** section 9's record, approved deviations/limitations/deferred-work account and formal closure decision are still needed. The focus shift in section 13 is a consequence of approval, not independent acceptance evidence. |
