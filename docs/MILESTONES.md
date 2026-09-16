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

**Status:** NEXT

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

## Milestone 5 — Trusted Contacts + Incident Record

**Status:** PLANNED

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

### Acceptance target

A trusted contact can understand what is known, what is stale, what is unverified, what attempts have been made, and what the last verified state was. Unauthorized users must not gain location access.

## Milestone 6 — Degraded Connectivity + SMS Fallback

**Status:** PLANNED

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

### Acceptance target

Test data good/SMS good, data bad/SMS good, data and SMS unavailable, delayed SMS, duplicate SMS, dual-SIM ambiguity, low battery, and recovery to internet.

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
