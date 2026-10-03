# Alabarin Evidence and Remote Uncertainty Rules

**Status:** Carry-forward product decisions
**Purpose:** Preserve important evidence, Trusted Contact, Alexa+, device-silence, and device-separation rules that are not being implemented yet, so later work does not accidentally weaken Alabarin's truthfulness model.

This file is intentionally separate from the Traveller v1 Closure Plan.

The closure plan answers:

> When can we call Traveller v1 closed?

This file answers:

> When Alabarin receives information from devices, Travellers, Trusted Contacts, Alexa+, or system inference, what does that information actually mean?

These rules should be revisited when implementing Trusted Viewer, Alexa+/MCP, remote Journey evidence, device-silence handling, probable-end logic, and future incident/recovery flows.

---

# 1. Core principle

Alabarin must preserve the difference between:

- what a device observed
- what the Traveller explicitly confirmed
- what a Trusted Contact reported
- what the system inferred
- what the server last received
- what remains unknown

No evidence class silently becomes another evidence class.

**Last verified is not current.**

**A report is not the same as device proof.**

**An inference is not the same as a confirmation.**

**No recent device contact does not reveal why the device is silent.**

---

# 2. Evidence classes

Alabarin should treat these as separate evidence classes.

## 2.1 Traveller-confirmed evidence

Examples:

- Traveller taps **Journey completed**
- Traveller taps **Keep monitoring**
- Traveller intentionally stops monitoring

Meaning:

- this is a first-party action from the Traveller flow
- it can change Journey state where the product contract explicitly allows it
- it is still not a general proof of "safe arrival" unless the action itself is defined to mean that

For current v1 semantics:

- `COMPLETED` means the Traveller explicitly completed the Journey
- `CANCELLED` means monitoring was intentionally stopped
- neither status should be expanded into claims the action did not establish

---

## 2.2 Device-observed evidence

Examples:

- latitude / longitude observation
- observation timestamp
- accuracy
- connectivity/runtime state
- last authenticated cloud contact
- telemetry movement history

Meaning:

- evidence from the physical Traveller device
- it is evidence about what the device observed
- it is not automatically proof of the Traveller's physical condition
- it is not automatically proof that the Traveller still possesses the device

Device telemetry must preserve provenance and time.

A new location observation should not silently overwrite historical evidence in a way that makes the earlier timeline disappear.

---

## 2.3 Trusted-human report

Examples:

A Trusted Contact tells Alexa+:

> "He reached safely."

> "I am with her now."

> "His phone was stolen."

> "She no longer has the phone."

Meaning:

- this is a report made by an authorized human
- Alabarin stores the report with who reported it, when it was reported, and how it entered the system
- the report does not become device telemetry
- Alexa+ does not independently promote the claim into objective system truth
- the report can materially affect how later evidence is interpreted, but only through explicit product rules

A future record may conceptually include:

- Journey
- reporting Trusted Contact
- reported time
- received time
- claim type
- statement/details
- channel, such as Alexa+
- provenance / authorization context

Exact schema is deferred.

---

## 2.4 System inference

Examples:

- probable Journey-end evidence
- future destination-proximity reasoning
- device-contact becoming stale
- future anomaly or continuity classifications

Meaning:

- the system inferred something from evidence
- the inference must be labelled as an inference
- it must not be displayed as confirmed Traveller or Trusted Contact testimony

For Probable End:

> "There are enough signals to ask whether the Journey ended"

is acceptable.

> "The Traveller arrived"

is not.

---

## 2.5 Server communication evidence

Examples:

- last authenticated cloud success
- last device event received
- SMS fallback handoff
- backend acknowledgement

Meaning:

- evidence about communication with Alabarin infrastructure
- not necessarily evidence about current device state
- not necessarily evidence that a Trusted Contact received anything

Examples:

- fallback allocated is not delivery
- handoff to an SMS provider is not recipient delivery
- successful cloud contact at 6:24 PM is not proof the device is connected now

---

# 3. Trusted Contact reports through Alexa+

Alexa+ is an interface over authorized Journey information and trusted-human reporting.

Alexa+ must not become the authority that decides what happened.

## Example

A Trusted Contact says:

> "Abdulrahman reached safely. I am with him."

Alabarin may preserve a trusted-human report such as:

```text
Trusted report
Reported by: Aisha
Reported at: 8:42 PM
Claim: Traveller reached safely
Additional context: Reporter says they are physically with Traveller
Source: Alexa+
```

The system may then show that human report to other authorized surfaces.

It must not rewrite the device history to make it look like the device proved safe arrival.

## Locked rule

**Trusted reports coexist with device evidence. They do not overwrite it.**

This matters when sources disagree.

Example:

```text
Trusted Contact:
"Traveller is safe with me."

Device:
continues moving elsewhere
```

Both facts can be preserved.

The correct product question becomes:

> Is the device still reliable evidence of the Traveller's location?

not:

> Which source should we delete?

---

# 4. Traveller separated from device

This is an important future evidence state.

Possible causes include:

- phone stolen
- phone lost
- phone left behind
- Traveller gives the phone to someone else
- damaged device is moved separately

A trusted person may report:

> "The Traveller no longer has the phone."

## Required semantic effect

Once Alabarin has an authorized, accepted **Traveller separated from device** report:

- preserve all subsequent device telemetry
- do not delete it
- do not relabel it as fake
- but stop treating new device movement as strong evidence of the Traveller's whereabouts
- remote surfaces must make the separation clear
- evidence collected before the separation time remains historical device evidence

Conceptually:

```text
Before separation report:
device location can support Traveller-location understanding

After accepted separation report:
device location remains device evidence
but may no longer represent Traveller location
```

This is a relevance/provenance change, not data deletion.

## Important future design question

We still need to define what makes a device-separation report sufficiently authoritative to change remote interpretation.

Possible factors may include:

- reporter is an active authorized Trusted Contact
- explicit confirmation
- relation to an active Journey
- timestamp
- later Traveller confirmation or contradiction

Do not implement this from a casual conversational phrase alone without a locked product rule.

---

# 5. Trusted Contact report does not automatically end a Journey

A statement such as:

> "He reached safely."

should initially be treated as a trusted-human report.

It should **not automatically invoke a Journey terminal action** merely because the sentence sounds conclusive.

If Alabarin later allows Trusted Contacts to resolve or end an active Journey remotely, that should be a separate explicit product capability with:

- clear authorization
- explicit confirmation
- exact Journey identity
- auditable provenance
- conflict handling
- truthful terminal semantics

Alexa+ may help prepare such an action, but should not silently convert a conversational statement into a terminal Journey mutation.

This preserves the current Alexa+ boundary:

- Alexa+ may retrieve authorized evidence
- Alexa+ may prepare a trusted report
- Alexa+ may confirm an explicitly prepared report
- Alexa+ does not independently start/end a Journey or declare safety/danger

---

# 6. Device silence: what Alabarin can and cannot know

A server-side absence of new device information is not a diagnosis.

From the remote system's point of view, similar silence can be caused by:

- phone powered off
- battery depleted
- airplane mode
- no mobile data
- no cellular coverage
- damaged phone
- stolen phone
- Android restrictions
- app force-stop
- operating-system/process failure
- prolonged network outage

Unless Alabarin has explicit evidence of the cause, remote surfaces must not claim the cause.

## Do not say

> "The phone is off."

> "The battery died."

> "The phone was stolen."

> "The Traveller turned on airplane mode."

unless a trustworthy source actually establishes that fact.

## Prefer evidence-bounded language

> "No recent device contact."

> "Last verified device contact: 6:24 PM."

> "Alabarin has not received newer device information."

This follows the core rule:

**Last verified is not current.**

---

# 7. Airplane mode

Airplane mode does not necessarily stop local Journey monitoring.

Depending on device/platform state:

- GPS/location may remain available
- the foreground Journey may continue locally
- new observations may continue to be stored locally
- internet/cloud communication may be unavailable
- carrier SMS fallback may also be unavailable

Conceptually:

```text
Journey ACTIVE
-> network becomes unavailable
-> local monitoring may continue
-> cloud updates stop
-> connectivity state degrades
-> queued evidence can synchronize when communication returns
```

Airplane mode must not itself terminalize the Journey.

The remote server should not infer "airplane mode" merely because device contact disappeared.

---

# 8. Phone powered off or battery depleted

When the device is physically powered off:

- the app cannot run
- the foreground service cannot run
- new GPS observations cannot be collected
- new local evidence cannot be stored
- new cloud/SMS communication cannot occur

The server sees only the absence of new contact.

Therefore the remote truth is:

```text
last verified device contact = T
newer device state = unknown
```

not:

```text
device is definitely powered off
```

This distinction is essential for Trusted Viewer and Alexa+.

---

# 9. Local connectivity state vs remote device-contact state

These are different concepts.

## Local Traveller-side connectivity

A running device may classify its own current runtime as:

- healthy
- interrupted
- degraded
- recovering

Those are local/runtime facts backed by Journey-scoped evidence.

## Remote Trusted Viewer / Alexa+ state

The server cannot directly reuse those concepts when the device has stopped communicating.

Remote surfaces need a separate communication-age concept, for example:

```text
recent device contact
device contact becoming stale
no recent device contact
```

Exact thresholds and names are deferred.

The important locked rule is:

**Remote silence must be described as silence/staleness, not as a guessed device cause.**

---

# 10. Stolen-phone scenario

Example:

```text
Traveller is physically safe
-> phone is stolen
-> stolen phone continues moving
-> Trusted Contact reports Traveller is with them and no longer has the phone
```

Correct Alabarin behavior should preserve:

1. the last trustworthy device observations before separation
2. the Trusted Contact report
3. the time of the reported separation
4. later device telemetry as device telemetry
5. the fact that later device movement may not represent the Traveller

Incorrect behavior would be:

- deleting later device evidence
- pretending the device stopped moving
- presenting the thief's device path as confirmed Traveller movement
- overwriting device telemetry with the Trusted Contact's statement
- letting Alexa+ independently declare the Traveller safe

---

# 11. Relationship to Probable End

These rules do not replace the Probable End detector.

Probable End will later be a **system inference** based on a structured Journey destination and qualifying device evidence.

Trusted-human reports are a different evidence class.

Device silence is also not Probable End evidence by itself.

Examples:

```text
No recent device contact
!= Journey probably ended
```

```text
Trusted Contact says "I am with the Traveller"
!= device proved Journey completion
```

```text
Device enters destination area
!= confirmed arrival
```

Section 09 must continue to preserve these distinctions.

---

# 12. Relationship to Unresolved Boundary

The future unresolved boundary must also preserve uncertainty.

If continuous monitoring eventually ends because the Journey remains unresolved:

- it does not mean the Traveller is safe
- it does not mean the Traveller is missing
- it does not mean the Traveller is in danger
- it does not erase Trusted Contact reports
- it does not erase the last verified device evidence
- it does not transform device silence into a diagnosis

The unresolved result should remain an operational monitoring boundary, not a conclusion about what happened.

---

# 13. Trusted Viewer implications

Trusted Viewer should eventually present evidence by provenance rather than flattening everything into one "current status."

Possible categories include:

- Traveller-confirmed state
- last verified device contact
- recent device observations where authorized
- system inference
- trusted-human reports
- communication/fallback evidence
- unresolved/unknown information

The UI does not necessarily need to expose these as technical labels.

But the data model and presentation logic must preserve the distinction.

Example response:

> Aisha reported at 8:42 PM that she was with the Traveller and that he had arrived safely. The last verified device contact was 8:10 PM. Alabarin has not received newer Traveller-device evidence.

This is much better than collapsing everything into:

> Traveller is safe.

---

# 14. Alexa+ implications

Alexa+ should answer from authorized evidence with bounded language.

Good pattern:

> "The last verified device contact was at 6:24 PM. Aisha later reported at 6:51 PM that she was with the Traveller. Alabarin has not independently verified a newer device location."

Bad pattern:

> "His phone is off and he is safe."

unless those exact facts are supported by authorized evidence.

Alexa+ must distinguish:

- device observation
- system inference
- Trusted Contact report
- Traveller confirmation
- unknown

---

# 15. Future data-model requirements

Exact schema is deferred, but future evidence storage should be able to preserve at least:

- Journey identity
- evidence/report identity
- evidence class/source
- actor identity when human-reported
- observed time when applicable
- reported time
- server received time
- claim/event type
- provenance/channel
- relationship authorization context
- whether the evidence concerns the Traveller, device, or communication channel
- lifecycle/revocation/invalidated state where required

Avoid designing a generic "status" column that destroys these distinctions.

---

# 16. Open product decisions for later

These are intentionally not decided yet:

- Can a Trusted Contact ever explicitly end/resolve a Journey?
- If yes, under what authorization and confirmation?
- How should conflicting Trusted Contact reports be presented?
- What exact action accepts a "Traveller separated from device" report?
- Can the Traveller later revoke/correct such a report?
- How should device evidence after separation be visually labelled?
- When does remote device contact become "stale" versus "no recent contact"?
- Should trusted-human reports expire, remain permanent evidence, or be superseded?
- How does a recovered/replaced device affect a prior separation report?
- Which report classes should trigger notifications to other Trusted Contacts?
- Which evidence is retained after Journey closure?
- Which exact evidence may Alexa+ summarize versus quote as attributed human reporting?

These should be answered during Trusted Viewer/Alexa+/evidence-model implementation, not improvised during unrelated slices.

---

# 17. Carry-forward checklist

Before Trusted Viewer is closed:

- [ ] preserve evidence provenance
- [ ] design remote device-contact staleness without cause guessing
- [ ] represent Trusted Contact reports separately from device evidence
- [ ] define Traveller-separated-from-device handling
- [ ] decide whether Trusted Contacts can explicitly resolve/end Journeys
- [ ] ensure Trusted Viewer does not flatten reports/inference into fact
- [ ] test stolen/lost/offline/device-silent scenarios

Before Alexa+/MCP is closed:

- [ ] Alexa answers with attributed evidence
- [ ] Alexa does not independently declare safe/danger
- [ ] `prepare_trusted_report` preserves reporter/provenance
- [ ] `confirm_trusted_report` requires explicit confirmation
- [ ] conversational reports do not silently terminalize Journeys
- [ ] device-silence answers use last verified contact, not guessed causes

Before Probable End / Unresolved Boundary is closed:

- [ ] system inference remains distinct from human confirmation
- [ ] device silence alone is not probable-end evidence
- [ ] terminal states preserve uncertainty
- [ ] trusted reports survive and remain attributed after terminalization

---

# 18. Relationship to other working documents

Related:

- `ALABARIN_TRAVELLER_V1_CLOSURE_PLAN.md`
- future Trusted Viewer implementation/contract docs
- future Alexa+/MCP contract docs
- future Probable End evidence contract

This file is a carry-forward semantic contract. It should be updated when these rules are intentionally changed, not silently contradicted by implementation.
