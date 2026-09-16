# Milestone 3 — Redmi 14C Physical Acceptance Test

## Preflight

1. Apply the checked-in Milestone 3 SQL migration to the development Supabase project.
2. Enable anonymous sign-ins for that project.
3. Put `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` in ignored `local.properties`; never use a service-role key in Android.
4. Install the new debug APK over the accepted Milestone 2 app without clearing app data.
5. Confirm the existing Journey and telemetry data survive the Room v2-to-v3 migration.
6. Use the Supabase SQL editor or Table Editor only to inspect results. Do not modify synchronized rows during these tests.

For each cloud check, filter by the Android Journey UUID. Treat **Synced** only as transport status, never as evidence that the traveller is safe.

## Test A — Normal online synchronization

1. Connect the Redmi 14C to working internet and launch the app.
2. Start a Journey and grant the normal foreground location prerequisites.
3. Wait for several real location observations.
4. Confirm **Local observations** and the local sequence increase.
5. Wait for **Cloud-synced through sequence** to catch up and **Pending** to reach zero.
6. In Supabase, confirm `public.journeys.id` exactly matches the Android Journey UUID.
7. Confirm the cloud Journey belongs to the anonymous authenticated user and has the correct destination, timestamps, and `ACTIVE` status.
8. Query telemetry ordered by `sequence`; confirm cloud sequences match the local sequence range with no duplicates.
9. Compare `event_time` and `received_at`. Confirm `event_time` represents the Android observation and `received_at` is the later/equal server receipt time.

## Test B — Offline backlog and recovery

1. Keep or start a Journey while online and record the local/cloud latest sequence.
2. Disable both Wi-Fi and mobile data while keeping Location Services enabled.
3. Move or wait until several additional local observations appear.
4. Confirm local count and sequence continue increasing.
5. Confirm the Supabase telemetry count does not increase while offline.
6. Confirm the app reports pending cloud observations without implying a safety problem.
7. Restore internet.
8. Allow constrained WorkManager work to run; reopening the app is allowed but must not be required for Room data integrity.
9. Confirm every missing sequence arrives in ascending logical order.
10. Confirm the checkpoint advances only through the last accepted sequence, Pending returns to zero, and the cloud contains one row per `(journey_id, sequence)`.

## Test C — Process and Recents recovery

1. Record the latest local and cloud sequence while the Journey is ACTIVE.
2. Go offline and generate new local observations.
3. Swipe the Activity from Recents. Do not Force Stop the app.
4. Restore network connectivity.
5. Wait for WorkManager recovery, then reopen the app if needed for inspection.
6. Confirm the persisted checkpoint resumes and the entire backlog eventually reaches Supabase without sequence reset or duplication.

## Test D — Complete while offline

1. Start or continue a Journey online, then disable Wi-Fi and mobile data.
2. Generate multiple offline observations.
3. End the Journey while still offline.
4. Confirm local completion succeeds immediately, location collection stops, and no new local telemetry appears.
5. Confirm the cloud does not falsely advance while offline.
6. Restore internet and wait for constrained synchronization.
7. Confirm all outstanding telemetry uploads exactly once.
8. Confirm the cloud Journey eventually becomes `COMPLETED` with the original Android `completedAt` represented as `completed_at`.

## Test E — Idempotency

1. After a Journey is fully synchronized, close and reopen the app to safely cause recovery scheduling to be reconsidered.
2. Optionally toggle connectivity off and on, then wait for WorkManager.
3. Record the cloud telemetry count before and after the repeated synchronization opportunity.
4. Confirm the count does not increase for sequences already present and every `(journey_id, sequence)` remains unique.

## Evidence to retain

- Android and HyperOS versions and APK build time.
- Journey UUID and anonymous user UUID (never tokens or keys).
- Local count, checkpoint, and Pending values before/after each test.
- Ordered cloud sequence results and duplicate-count query results.
- Representative `event_time`/`received_at` pairs.
- WorkManager recovery delay after connectivity restoration and Recents swipe.
- Any permanent Auth/RLS/schema error shown by the engineering UI.
