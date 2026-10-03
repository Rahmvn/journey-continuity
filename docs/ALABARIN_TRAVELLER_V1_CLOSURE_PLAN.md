# Alabarin Traveller v1 Closure Plan

**Status:** Active working plan
**Current Traveller position:** Page 05, Section 09 of 10
**Purpose of this file:** Make sure we do not call Traveller v1 finished just because the screens and flows exist.

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
- [ ] **09 - Probable end - separate branch**
- [ ] **10 - Unresolved boundary - separate branch**

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
- [ ] Future automatic end does not imply arrival or safety.
- [ ] Future unresolved end does not imply safe, missing or in danger.

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
- [ ] Future auto-ended/unresolved semantics do not collapse into `COMPLETED`.

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
- [ ] Section 09 implemented and closed
- [ ] Section 10 implemented and closed
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
