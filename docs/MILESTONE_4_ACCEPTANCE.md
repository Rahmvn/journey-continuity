# Milestone 4 Redmi, Supabase, and AWS Acceptance

Use the development Supabase and AWS environments. The configured heartbeat interval is approximately 60 seconds and the verification threshold is 300 seconds; allow at least one additional Scheduler interval before declaring a failure.

## Test A — Normal heartbeats

1. Install the debug APK over the accepted Milestone 3 installation without clearing app data.
2. Start a Journey online on the Redmi 14C.
3. Confirm local telemetry continues normally.
4. In Supabase, confirm `journey_heartbeats` rows appear for the Journey.
5. Confirm heartbeat sequences increase independently of telemetry sequences.
6. Confirm `journey_monitoring_state.phase = EVIDENCE_FRESH`.
7. Confirm `last_cloud_contact_at` advances using server timestamps.
8. Confirm exactly one `MONITORING_STARTED` event exists and routine heartbeats create no transition events.

## Test B — Cloud silence

1. Keep the Journey ACTIVE and record its latest heartbeat and telemetry sequences.
2. Disable both mobile data and Wi-Fi; keep Location Services enabled.
3. Confirm local telemetry continues and heartbeat rows stop.
4. Wait beyond five minutes plus at least one Scheduler timing allowance.
5. Without reopening the app or manually invoking the RPC, confirm the AWS schedule invoked Lambda.
6. Confirm monitoring changed to `VERIFYING`.
7. Confirm exactly one `VERIFYING_STARTED` event with reason `HEARTBEAT_TIMEOUT` and threshold `300`.
8. Wait through another watchdog cycle and confirm no duplicate transition event.

## Test C — Contact restoration

1. While cloud monitoring is `VERIFYING`, restore internet.
2. Confirm a newly generated heartbeat arrives promptly; missed offline heartbeat timestamps must not appear.
3. Confirm monitoring returns to `EVIDENCE_FRESH`.
4. Confirm exactly one `CONTACT_RESTORED` event for this transition.
5. Confirm the telemetry backlog synchronizes separately through the Milestone 3 checkpoint.
6. Confirm old telemetry retains its original `event_time` and did not itself change monitoring freshness.

## Test D — Complete while offline

1. Start or continue an ACTIVE Journey online, then go offline.
2. Wait until AWS changes cloud monitoring to `VERIFYING`.
3. End the Journey locally while still offline.
4. Confirm location and heartbeat production stop immediately on the device.
5. Confirm cloud monitoring may remain `VERIFYING` while completion is unknown remotely.
6. Restore internet and allow Milestone 3 synchronization to upload `COMPLETED`.
7. Confirm monitoring converges to `CLOSED` with one `MONITORING_CLOSED` event.
8. Confirm outstanding telemetry can finish synchronizing but does not reopen monitoring.
9. Wait through another watchdog cycle and confirm `CLOSED` remains terminal.

## Test E — AWS independence and duplicate delivery

1. With an ACTIVE Journey silent/offline, close the Activity and do not reopen it.
2. Confirm Scheduler and Lambda independently produce the `VERIFYING` transition.
3. Invoke Lambda manually twice or observe Scheduler retry/overlap around the same due Journey.
4. Confirm the state remains coherent and only one logical `VERIFYING_STARTED` event exists.
5. Inspect CloudWatch logs for safe transition counts only; confirm no secrets are logged.

Retain the Journey UUID, heartbeat and telemetry sequences, monitoring rows/events, Lambda invocation times, Scheduler state, and representative CloudWatch result logs. Never retain or screenshot secret values or auth tokens.
