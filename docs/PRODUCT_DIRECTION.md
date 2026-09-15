# Journey Continuity — Product Direction

**Version:** 0.1  
**Date:** 15 September 2026  
**Status:** Canonical product direction  
**Current stage:** Milestone 1 accepted on physical Redmi 14C

---

## 1. Core Problem

Journey Continuity exists to answer one question:

> **When someone travels by road in Nigeria and unexpectedly becomes unable to respond, how do we reduce the chance that they simply disappear without leaving enough reliable information for anyone to act?**

The problem is not kidnapping specifically. It is not route-risk prediction. It is not emergency response automation.

The real problem is the **information vacuum that appears after unexpected loss of contact**.

When a traveller becomes unreachable, family members may otherwise have to reconstruct the journey from scattered fragments: where they left from, where they were going, what vehicle or operator they used, when anyone last heard from them, where the phone was last reliably observed, whether the device was still active, whether connectivity was failing, and whether the silence is actually abnormal.

Journey Continuity should preserve that information **before it becomes urgently needed**.

## 2. Product Thesis

Most personal-safety tools depend heavily on the traveller taking action during an emergency, for example by pressing an SOS button.

Journey Continuity is designed around the opposite condition:

> **The traveller may no longer be able to help the system.**

A Journey Session gradually builds a verified record while the trip is happening.

If communication later becomes uncertain, trusted people should immediately have useful facts instead of beginning reconstruction from zero.

The strongest product objective is:

> **Reduce the time between “we cannot reach them” and “we have enough verified information to begin acting.”**

Journey Continuity is therefore not merely a panic-button app. It is a **journey continuity system**.

## 3. Product Boundaries

Journey Continuity v1 is deliberately **not**:

- a kidnapping detector;
- a crime-prediction system;
- a route-risk map;
- a public incident map;
- a permanent family tracker;
- a surveillance product;
- a replacement for police, FRSC, emergency services, or telecom operators;
- a system that labels roads as “safe” or “unsafe” based on weak evidence.

The system must never present inference as fact.

It should not say:

> “The traveller has been kidnapped.”

It should say what is actually known, for example:

> “Current whereabouts are unknown. The last verified device update was recorded at 12:37 PM.”

Silence, route deviation, missed GPS observations, poor connectivity, or low battery are **signals and context**, not proof of danger.

## 4. The Central Product Object: Journey Session

Tracking and monitoring exist only inside an explicitly started Journey Session.

A Journey Session may eventually contain intended destination, expected arrival time, start time, traveller identity, transport information where available, device observations, traveller confirmations, verification attempts, trusted-contact reports, incident escalation history, and journey outcome.

The Journey Session becomes a **living journey manifest**.

Traditional transport manifests describe the journey mostly at departure. Journey Continuity should preserve relevant evidence **after departure** as the journey unfolds.

A core rule is:

> **Last verified position is not the same thing as current position.**

Stale data must never be displayed as if it were live.

## 5. Evidence and Provenance

Trustworthiness matters more than quantity of data.

Important facts should eventually carry provenance.

Canonical provenance categories:

- `device_verified` — produced directly by the traveller’s device;
- `traveller_confirmed` — explicitly confirmed by the traveller;
- `trusted_contact_reported` — reported by an authorized trusted contact;
- `unverified` — not independently established.

AI may explain evidence.

AI must never silently convert an assumption into a fact.

## 6. Product State Direction

The full state machine will be introduced progressively.

The intended abnormal-flow direction is approximately:

```text
ACTIVE
   ↓
VERIFYING
   ├── successful verification ──→ ACTIVE
   ↓
CONTACT_UNVERIFIED
   ↓
ESCALATING
   ↓
RESOLVED / UNRESOLVED_INCIDENT
```

Normal terminal states are expected to include:

```text
COMPLETED
AUTO_ENDED
CANCELLED
```

Meanings:

- `COMPLETED` — traveller explicitly ends/confirms the journey.
- `AUTO_ENDED` — strong device/destination evidence suggests the journey has ended and monitoring is automatically stopped. It must **not** be described as “safe arrival.”
- `CANCELLED` — traveller intentionally cancels the journey.
- `RESOLVED` — an abnormal situation has been resolved.
- `UNRESOLVED_INCIDENT` — automated monitoring has exhausted useful actions; evidence remains preserved for human follow-up.

There will be **no `KIDNAPPED` state**.

The system must not automatically assert that a person is legally or factually “missing.”

## 7. Escalation Philosophy

Escalation must remain deterministic and evidence-based.

The following are not sufficient on their own: one missed heartbeat, loss of internet, temporary GPS failure, slight lateness, low battery, or route deviation.

Battery, connectivity, timing, and movement may provide useful context, but they are not proof of danger.

Escalation thresholds must be refined through field testing rather than invented AI confidence scores.

Two primary product-quality metrics:

> **Time to useful awareness ↓**

> **False escalation rate ↓**

These matter more than feature count.

## 8. Connectivity Philosophy

Normal operation should use internet communication.

SMS is a **degraded-connectivity fallback transport**, not the primary heartbeat channel.

Long-term direction:

```text
Internet healthy
      ↓
normal telemetry

Internet repeatedly failing
      ↓
local queue continues

meaningful degradation threshold reached
      ↓
sparse SMS fallback

internet restored
      ↓
queued history synchronizes
SMS fallback stops
```

If internet and SMS both disappear, the cloud may still observe that expected telemetry has stopped.

The cause of silence remains unknown.

## 9. Journey Ending

Monitoring must know when to stop.

Every terminal state should eventually perform common cleanup: stop location collection, stop foreground monitoring, stop fallback transmission, cancel journey timers, stop verification attempts, revoke temporary access where appropriate, and apply retention/deletion policy.

A safety system that tracks indefinitely because the user forgot to end a journey is a privacy failure.

## 10. Privacy Direction

Journey Continuity must remain **journey-scoped**, not permanent surveillance.

Principles:

- exact live location is private;
- trusted contacts receive access because the traveller explicitly authorizes them;
- access should eventually be temporary, auditable, and revocable;
- normal journey records should have short retention;
- incident records may require different retention because preserving evidence is part of their purpose;
- location data is not sold;
- travel history is not used for advertising profiles.

A formal threat model is required before public release.

## 11. Alexa+ and AI Role

Alexa+ belongs primarily on the **trusted-family side**, not as the traveller’s main interface.

Example:

> “Alexa, has Abdulrahman arrived?”

The agent should query authoritative journey tools and answer from structured evidence.

If contact is uncertain:

> “The last verified device update was at 12:37 PM. Contact has not yet been re-established.”

A trusted contact might say:

> “I spoke with him. Their vehicle broke down.”

That may be stored as `trusted_contact_reported`.

Potential bounded tools:

```text
get_journey_status
get_last_verified_state
get_verification_history
record_trusted_contact_update
request_traveller_verification
begin_escalation
```

The agent may not fabricate telemetry, alter historical evidence, declare kidnapping, declare someone missing, bypass authorization, or reveal exact location to an unauthorized person.

AI explains, summarizes, and coordinates. It does **not** own safety-critical decisions.

## 12. Relationship to the Amazon Hackathon

The Amazon Build, Ship, Shape hackathon provides a deadline and distribution opportunity.

It does not define the product.

Alexa+ fits because trusted family members need a natural way to understand and coordinate around journey state.

AWS fits where independent cloud execution, watchdog behavior, communication, and agent orchestration genuinely help.

The architecture must not be distorted merely to display sponsor technologies.

Even if the hackathon disappeared tomorrow, the product direction should still make sense.

## 13. Product Decision Filter

Whenever a new feature is proposed, ask:

> **Does this materially help preserve trustworthy journey evidence, reduce the information vacuum after unexpected loss of contact, improve resilience, or make the system easier and safer to use?**

If not, it probably does not belong in v1.

The sentence to keep returning to is:

> **When a road traveller unexpectedly becomes unable to respond, preserve enough verified information that the people responsible for them are not starting from zero.**
