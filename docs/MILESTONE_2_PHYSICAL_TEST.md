# Milestone 2 — Redmi 14C Physical Acceptance Test

Use a debug build that includes the explicit Room v1-to-v2 migration. Do not clear app data before the migration check. Android Force Stop is not part of the success criteria.

## 1. Upgrade and migration check

1. If the accepted Milestone 1 build is installed with an existing Journey row, install the Milestone 2 APK over it without uninstalling or clearing storage.
2. Open the app.
3. Confirm the existing Journey still appears with the same destination, ETA, start time, and status.
4. If that Journey is ACTIVE, complete it before beginning the primary test.

Expected: the existing Journey survives and the app opens without a migration crash.

## 2. Primary movement and lifecycle test

1. Open the app on the Redmi 14C.
2. Enter a destination and choose a future ETA.
3. Tap **Start Journey**.
4. In Android's location prompt, choose **Precise** and **While using the app** if those choices are offered.
5. Handle the notification prompt if Android shows it. Granting notifications is preferred so the notification can be inspected directly.
6. Confirm the location foreground-service notification appears.
7. Wait for **Local telemetry evidence** to replace **Waiting for first location…** with an observation.
8. Record the telemetry count, latest sequence, observation time, coordinates, and accuracy.
9. Walk or travel far enough to generate multiple real location updates. The request interval is about 30 seconds, but delivery is not guaranteed at exact 30-second boundaries.
10. Confirm count and latest sequence increase without resetting.
11. Background the app and lock the screen.
12. Continue moving for several minutes where practical.
13. Unlock the phone. Before reopening the app, confirm the foreground notification remains.
14. Reopen the app.
15. Confirm the same Journey remains ACTIVE.
16. Confirm observations with later sequences and sensible event times were persisted while the Activity was backgrounded/locked.
17. Confirm Android-reported horizontal accuracy remains visible.
18. With the Journey still ACTIVE, swipe the Activity from Recents, move/wait, and reopen it.
19. Confirm the same Journey is restored and its persisted sequence continues rather than restarting at 1.

Expected: actual requested location updates—not a last-known-location seed—produce a durable local stream through normal Activity lifecycle changes.

## 3. Connectivity context test

1. Keep the Journey ACTIVE and keep device Location Services/GPS enabled.
2. Disable both Wi-Fi and mobile data.
3. Move or wait long enough for one or more new observations.
4. Confirm observations continue to persist locally.
5. Confirm a new observation reports **NONE**, or another state that truthfully reflects what Android reports on the device.
6. Restore Wi-Fi or mobile data.
7. Wait for another observation.
8. Confirm the later observation reports the restored connectivity type.

Expected: telemetry persistence does not depend on internet access. No upload or synchronization occurs because Milestone 2 has no network transport.

## 4. Completion test

1. Note the latest telemetry count and sequence.
2. Tap **End Journey**.
3. Confirm the Journey becomes COMPLETED and the foreground notification disappears.
4. Wait at least 60 seconds (longer than one desired request interval).
5. Reopen the app if needed.
6. Confirm the completed Journey did not resume and no later telemetry was added.

Expected: completion removes the location callback, stops foreground mode and the service, and the data layer rejects any callback racing after the terminal transition.

## 5. Permission and Location Services tests

Run each case with no ACTIVE Journey. End or clear only test data through normal app behavior; do not clear the database needed for the migration check.

### Denied location

1. Revoke this app's location permission in Android settings if needed.
2. Enter a valid destination and future ETA, then tap **Start Journey**.
3. Deny location access.
4. Confirm no Journey silently starts, no monitoring notification appears, and the app explains that location is required.
5. Tap **Start Journey** again to confirm there is a clean permission retry path.

### Approximate location

1. Grant **Approximate** location only.
2. Start a Journey.
3. Confirm monitoring operates and the ACTIVE screen explicitly says evidence precision is reduced.
4. Confirm the actual accuracy value supplied by Android is displayed; the app must not label it precise.
5. End the Journey normally.

### Location Services disabled

1. Leave the app's foreground location permission granted, but turn off device Location Services.
2. Enter valid Journey details and tap **Start Journey**.
3. Confirm no Journey/monitoring service starts and the app shows an **Open Location settings** action.
4. Use that action, enable Location Services, return to the app, and confirm the pending start can continue.
5. During a later ACTIVE Journey, turn Location Services off and confirm the monitoring notification/service stops rather than pretending to collect evidence.
6. Return to the app, confirm the ACTIVE Journey is preserved with an actionable paused state, enable Location Services, and use the retry path to resume the same Journey.

## Evidence to record

- Android and HyperOS versions.
- Whether Precise and Approximate paths behave as described.
- First-fix latency and observed update cadence while stationary and moving.
- A sequence/time sample before backgrounding and after reopening.
- Accuracy ranges observed outdoors, in a vehicle, and indoors if practical.
- Whether the notification survives lock and Recents swipe.
- Connectivity values before, during, and after offline mode.
- Count/sequence at completion and after the post-completion wait.
- Any OEM kill, delayed callback, permission downgrade restart, or notification anomaly.
