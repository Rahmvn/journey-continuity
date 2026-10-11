# Alabarin 10C-3C local Docker acceptance — 2026-10-11

## Scope and isolation

- Source checkout: `feat/milestone-7-alexa-mcp-agent`; pre-existing changes in `local.properties.example`, `artifacts/`, and the three protected `docs/JOURNEY_*` files were preserved.
- Existing `journey-continuity-m5-local` Docker stack on ports 56421–56424 was left running and unmodified. The disposable `alabarin-10c3c-acceptance-01` stack uses ports 57421–57424 and a separate database/container set. The 28 copied migration files matched source hashes and all 28 appear in `supabase_migrations.schema_migrations`.
- No hosted project, linked CLI operation, hosted migration, deployment, push, service-role APK credential, or clock override was used. The only privileged fixture writes targeted the disposable PostgreSQL container. The Android app used real local GoTrue owner and other-user sessions.
- Disposable device package: `com.journeycontinuity.app.localacceptance` on Redmi 14C `GYYHFEXSKBIJGYTG`. The normal `com.journeycontinuity.app` package was not installed over, cleared, or uninstalled. ADB reverse mapped device loopback port 57421 to the isolated host API.

## Static and database checks

- `assembleLocalAcceptance` and `assembleLocalAcceptanceAndroidTest`: `BUILD SUCCESSFUL`.
- Focused `CloudSyncDiagnosticsTest` under `testDebugUnitTest`: `BUILD SUCCESSFUL`.
- Local variant permits the exact `http://127.0.0.1:57421` URL, requires a `sb_publishable_` key when supplied, and enables cleartext only in its variant manifest. The ordinary configuration path retains HTTPS validation.
- The six named Section 09/10 authority RPCs have `EXECUTE` for `authenticated` and not `anon`; `journeys`, `journey_monitoring_state`, and `verification_cases` have RLS enabled.
- Section 10 fixture `10d1`: real authenticated owner admission was accepted with millisecond start/ETA, exact admission ID/policy, an ETA one hour after admission three days earlier, passed ETA+24h boundary, `VERIFYING` state, matching `VERIFYING_STARTED` event, and one `OPEN` case. The server evaluator opened the episode. `10d2` has a future boundary; `10d3` has a passed boundary but no current case.
- The direct SQL postcheck found exactly one immutable incident finalization for `10d1`, status `UNRESOLVED_INCIDENT`, monitoring `CLOSED`, case still `OPEN`, `ended_at = finalized_at`, and an exact 604800-second access window. Evaluating the watchdog again left the closed phase and event count unchanged (two events).
- The existing pgTAP `probable_end_auto_end_test.sql` and `probable_end_owner_provenance_test.sql` passed in the first run. Three other selected files initially failed 10 assertions in the populated acceptance database. The isolated rerun and diagnosis are below. The full SQL suite was not run.
- An attempted reset of this disposable CLI project failed before changing its database with `failed to read profile: Unsupported Config Type ""`; the 28 migrations and existing fixtures remained present. Fresh case IDs were used for the final device rerun. The separate shared local stack was not reset.

## Authenticated Redmi results

`LocalSupabaseAuthorityAcceptanceTest.authenticatedAndroidToLocalAuthority`: **PASS, 1 test**. The device refreshed two real local Auth sessions and used the production authority gateways. It observed `10d1` eligible, `10d2` and `10d3` ineligible, no terminal state from eligibility reads, wrong-owner RLS invisibility and authority rejection, then committed the Section 10 finalizer. An exact retry after discarding the first successful response returned the same terminal timestamp and expiry; the owner provenance read matched. A heartbeat RPC after finalization was rejected, and the server remained `CLOSED`.

The same test used the distinct Section 09 RPCs for `10c4`: a fresh authenticated heartbeat produced `CLEAR`, registration returned a server-created 30-minute window, an immediate finalizer returned `TOO_EARLY`, and the Journey remained `ACTIVE`. The elapsed-window follow-up for fresh `10f9` is recorded below.

`LocalSupabaseAuthorityAcceptanceTest.offlineCompleteAndStopReconcileAfterReconnect`: **PASS, 1 test**. With ADB reverse removed, two independent Room databases recorded an offline Complete and Stop at a passed local boundary. Complete retained its active slot pending acknowledgment; Stop retained an `ACTIVE` Journey with a pending explicit intent. Both survived database close/reopen. After restoring ADB reverse, owner-authenticated production reconciliation created/terminalized the corresponding server Journeys. Direct SQL postcheck: `10e1` = `COMPLETED`/`CLOSED`; `10e2` = `CANCELLED`/`CLOSED`. Both local active slots cleared only after reconciliation.

`LocalSupabaseAuthorityAcceptanceTest.correctOwnerRoomAdmissionAndIncidentReconciliation`: **PASS, 1 test**. The Redmi created `10f1` and its immutable admission in Room, then the production sync gateway inserted the same facts through the owner JWT. The local-only SQL helper inserted stale monitoring evidence and ran the real watchdog; it opened one current `VERIFYING`/`OPEN` episode at a passed boundary. The device finalized the incident and the production Room coordinator reconciled the exact owner status and provenance. Device assertions found `UNRESOLVED_INCIDENT`, a vacated active slot, and persisted provenance. SQL postcheck found one incident record, `CLOSED` monitoring, an `OPEN` case, matching admission IDs, and `ended_at = finalized_at`.

The first device run had a test-harness `InvalidTestClassError` (non-void JUnit method), which was corrected. A later run reached Section 09 but found its evidence stale after the APK build; the test now refreshes evidence through the real heartbeat RPC immediately before registration. The final targeted runs above passed.

## Targeted completion: isolated SQL tests

The separate disposable `alabarin-10c3c-sql-02` Docker stack uses ports 58421-58424. It started with the first 24 migrations, then applied the existing `supabase/tests/fixtures/unresolved_boundary_legacy.sql` before the four Section 10 migrations. All 28 migration files were copied without modification and SHA hashes matched source. The legacy row's 2026-01-01 to 2030-01-01 ETA was accepted before the 72-hour admission cap was introduced. The three pgTAP files use rollback-only transactions and ran independently with `supabase test db --local`.

| File | Initial populated-database failures | Isolated result |
| --- | --- | --- |
| `unresolved_boundary_foundation_test.sql` | 4: assertion 2 expected zero historical admissions (8 present), 7 expected the absent legacy Journey, 8 retried that absent overlong Journey under the new cap, 23 expected one admission (9 present). | **PASS**, 47/47 |
| `unresolved_incident_finalization_test.sql` | 3: assertion 12 expected zero historical finalizations (2 present), 13 expected the absent legacy Journey, 50 expected one finalization after retry (3 present). | **PASS**, 60/60 |
| `unresolved_incident_owner_provenance_test.sql` | 3: assertion 5 expected zero historical finalizations (2 present), 14 expected one after the read (3 present), 27 expected one after denied operations (3 present). | **PASS**, 27/27 |

These were fixture-environment failures: the populated acceptance DB currently has 10 admission rows, 3 incident finalizations, and no legacy fixture; the isolated SQL DB has 0 admission rows, 0 finalizations, and exactly 1 pre-policy legacy Journey after all test transactions rolled back. No SQL contract defect was reproduced. These three files passed 134/134 assertions in isolation; this is not a full-suite result.

## Checked-in Section 10 fixture reproducibility

The original checked-in Section 10 fixture used `10c1`–`10c3`, while the successful Android test uses `10d1`–`10d3`. For the local acceptance commit, the fixture source now uses `10d1`–`10d3` consistently for Journey IDs, admission IDs, monitoring rows, and its inspection query. The earlier `10c1`–`10c3` database rows and the historical test results above were not rewritten. Section 09 fixture `10c4` remains separate.

A third disposable local stack, `alabarin-10c3c-fixture-03`, applied all 28 source-identical migrations. Applying the corrected fixture with a disposable local owner created exactly the Android test's three Journeys. Each Journey had a matching owner and immutable admission ID/policy. `10d1` had a passed boundary, one `VERIFYING_STARTED` event, and one owner-matched `OPEN` case; `10d2` had a future boundary and no case; `10d3` had a passed boundary but no case. The real owner eligibility RPC returned `ELIGIBLE/CURRENT_UNRESOLVED` for `10d1`, `NOT_ELIGIBLE/BOUNDARY_NOT_REACHED` for `10d2`, and `NOT_ELIGIBLE/NOT_VERIFYING` for `10d3`. This focused reproduction did not rerun Android or the Section 09 elapsed window.

## Section 09 elapsed-window run

The first `10c4` window was registered at 11:26:38.383 UTC for a 11:56:38.383 UTC deadline, but its verification guard subsequently moved from version 2 to 4 and the monitoring phase became `VERIFYING`. The immutable window could not be reused for an eligible finalization. The device instead created fresh disposable Journey `10f9` in Room, synchronized it through the owner-authenticated production gateway, sent a real heartbeat, and registered a new Section 09 confirmation window through the production Android gateway. Registration: **2026-10-11 11:48:57.999 UTC**; eligibility deadline: **12:18:57.999 UTC**. Immediate Android finalization returned `TOO_EARLY`; server and Room were `ACTIVE`, with Room's active slot retained.

`LocalSupabaseAuthorityAcceptanceTest.section09RegisterRealWindow`: **PASS, 1 test**. `LocalSupabaseAuthorityAcceptanceTest.section09FinalizeAfterRealWindow`: **PASS, 1 test**. Immediately before finalization, Android reopened the persisted Room database and asserted `ACTIVE`, active slot 1, and no fabricated local presentation; server was also `ACTIVE`. It sent a fresh owner-authenticated heartbeat, observed guard `CLEAR` version 0, called the production Section 09 finalization gateway, read owner `AUTO_ENDED` provenance through the production authority gateway, then called the production Room terminal reconciler. Android assertions found `AUTO_ENDED` in Room, active slot cleared, auto-end provenance stored, no Section 10 incident provenance, and no invented presentation. Device logcat recorded the `CLEAR` guard and successful Room reconciliation.

A separate read of the disposable app's on-device Room SQLite file after the instrumentation process exited confirmed `journeys.status = AUTO_ENDED`, `activeSlot = NULL`, `endedAt = 1791721175297` milliseconds, exactly one `journey_auto_end_finalizations` row, zero `journey_incident_finalizations` rows, and zero `probable_end_presentations` rows for `10f9`. The database was copied to a temporary host file for read-only inspection and that temporary file was removed.

Direct SQL postcheck: `10f9` registered at **11:48:57.999 UTC**, deadline **12:18:57.999 UTC**, and `ended_at = window.finalized_at = 12:19:35.297 UTC`. Actual elapsed registration-to-finalization time: **1,837.298 seconds**; deadline margin: **37.298 seconds**. Exactly one finalized Section 09 window exists. Server status `AUTO_ENDED`, monitoring `CLOSED`, guard/window version 0, zero open cases, and zero Section 10 incident finalizations. This is distinct from `UNRESOLVED_INCIDENT` and does not imply safety or arrival.

## Completion and hosted boundary

**10C-3C LOCAL ACCEPTANCE PASSED.** The targeted Section 09 Android finalization and all three isolated SQL test files now pass. The full SQL suite was not run. No production migration or eligibility predicate was changed; server time and the real 30-minute window were used. No previously successful Android acceptance case was rerun during this completion pass.

Hosted-only checks remain open: exact hosted migration/schema/grant/RLS parity, hosted Auth and authority RPCs with a signed owner JWT, hosted watchdog/scheduler behavior, and real hosted network/TLS integration. No hosted state was inspected or changed in this pass.

The isolated acceptance and SQL test stacks remain available for inspection. The Redmi `GYYHFEXSKBIJGYTG` remained connected with ADB reverse `tcp:57421 tcp:57421`; only the disposable `.localacceptance.test` APK was updated in this pass. The ordinary `com.journeycontinuity.app` package remained installed and untouched. No changes were staged or committed; `local.properties.example`, `artifacts/`, and the protected `docs/JOURNEY_*` files were preserved.
