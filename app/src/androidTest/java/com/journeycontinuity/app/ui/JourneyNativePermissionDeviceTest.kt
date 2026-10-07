package com.journeycontinuity.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.domain.JourneyStartRequest
import com.journeycontinuity.app.service.JourneyForegroundService
import com.journeycontinuity.app.ui.destination.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Run each case with fresh isolated package data and location permission not yet granted.
 * Uses real Android dialogs and the exact production JourneyStartPermissions implementation.
 */
class JourneyNativePermissionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as JourneyContinuityApplication
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var journey: JourneyViewModel
    private lateinit var picker: DestinationPickerViewModel
    private var nativeRequest: JourneyStartRequest? = null

    private fun showStart() {
        check(context.packageName == "com.journeycontinuity.app.structuredadmissionaudit" &&
            BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty())
        assertEquals(PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))
        scenario = ActivityScenario.launch(Intent().setClassName(context.packageName,
            "com.journeycontinuity.app.ui.JourneyNativePermissionAcceptanceActivity"))
        scenario.onActivity { activity ->
            journey = activity.javaClass.getMethod("getJourneyViewModel").invoke(activity) as JourneyViewModel
            picker = DestinationPickerViewModel(app.adminCatalogue.snapshot(),
                DestinationSearchGateway { DestinationSearchResponse.NoResults }, { true })
            activity.viewModelStore.put("native-picker", picker)
        }
        installContent()
        compose.waitUntil(10_000) { !journey.uiState.value.isRestoring }
        compose.onNodeWithText("Start Journey").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { journey.uiState.value.productRoute == JourneyProductRoute.START }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("choose_structured_destination").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("choose_structured_destination").performClick()
        compose.runOnIdle { picker.selectRegion("ng-kwara"); picker.selectUnit("ng-kwara-offa") }
        compose.onNodeWithTag("destination_locality").performClick()
        compose.runOnIdle { journey.updateExpectedArrival(System.currentTimeMillis() + 7_200_000) }
    }

    private fun installContent() = scenario.onActivity { activity ->
        val permissions = activity.javaClass.getMethod("getStartPermissions").invoke(activity) as JourneyStartPermissions
        activity.setContent {
            JourneyProductRoot(journey, LocationUiState(), unavailableSms, null,
                onRetryMonitoring = {}, onOpenLocationSettings = {}, onRequestSmsPermissions = {},
                onSelectSmsSubscription = {}, destinationPickerViewModel = picker,
                onStartRequested = { _, _ -> error("Native structured start downgraded to text") },
                onStructuredStartRequested = { nativeRequest = it; permissions.begin(it) })
        }
    }

    private fun startThroughCheckpoint() {
        compose.onNodeWithTag("journey_primary_action").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitCheckpoint()
        compose.onNodeWithText("Start anyway").performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun awaitCheckpoint() {
        compose.waitUntil(10_000) { journey.uiState.value.productRoute == JourneyProductRoute.CHECKPOINT }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Start anyway").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun nativeLocationGrantAdmitsExactlyOnceAndStartsPersistedMonitoring() {
        showStart()
        val frozen = picker.state.value.selectedDraft!!
        // Rotation at the checkpoint retains the frozen request; no disk/SavedState evidence.
        compose.onNodeWithTag("journey_primary_action").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitCheckpoint()
        scenario.recreate()
        installContent()
        awaitCheckpoint()
        compose.onNodeWithText("Start anyway").performSemanticsAction(SemanticsActions.OnClick) { it() }
        clickNativePermission(grant = true)
        assertAdmitted(frozen)
        scenario.recreate()
        installContent()
        compose.waitForIdle()
        assertEquals(nativeRequest!!.destination, runBlocking { app.journeyRepository.activeJourney.first() }!!.structuredDestination)
        assertOneJourney()
    }

    @Test fun nativeLocationDenialCreatesNothingRetainsSelectionAndGrantRetryAdmitsOnce() {
        showStart()
        val frozen = picker.state.value.selectedDraft!!
        startThroughCheckpoint()
        clickNativePermission(grant = false)
        compose.waitUntil(10_000) { !journey.uiState.value.isActionInProgress }
        assertNull(runBlocking { app.journeyRepository.activeJourney.first() })
        assertFalse(JourneyForegroundService.serviceRunning.value)
        assertEquals(frozen, picker.state.value.selectedDraft)
        assertNotNull(journey.uiState.value.draftExpectedArrivalAt)
        compose.onNodeWithTag("journey_primary_action").assertIsEnabled()
        val rejectedRequest = nativeRequest!!
        startThroughCheckpoint()
        clickNativePermission(grant = true)
        assertAdmitted(frozen)
        scenario.onActivity { journey.startJourney(rejectedRequest); journey.startJourney(nativeRequest!!) }
        assertOneJourney()
    }

    private fun assertAdmitted(frozen: DestinationDraft) {
        compose.waitUntil(15_000) { journey.uiState.value.activeJourney != null }
        val active = runBlocking { app.journeyRepository.activeJourney.first() }!!
        assertEquals(frozen.destination, active.structuredDestination)
        assertSame(frozen.destination, nativeRequest!!.destination)
        assertEquals(frozen.displayLabel, active.destination)
        compose.waitUntil(20_000) { JourneyForegroundService.monitoringJourneyId.value == active.id }
        assertOneJourney()
    }

    private fun assertOneJourney() {
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("journey-continuity.db").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT count(*) FROM journeys j JOIN journey_sync_states s ON s.journeyId=j.id
                JOIN journey_heartbeat_states h ON h.journeyId=j.id WHERE j.activeSlot=1""", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals(1, rows.getInt(0))
            }
            db.rawQuery("SELECT count(*) FROM journeys", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals(1, rows.getInt(0))
            }
        }
    }

    private fun clickNativePermission(grant: Boolean) {
        val ids = if (grant) listOf("permission_allow_foreground_only_button", "permission_allow_button")
            else listOf("permission_deny_button", "permission_deny_and_dont_ask_again_button")
        val labels = if (grant) listOf("While using the app", "Allow only while using the app")
            else listOf("Don't allow", "Don’t allow", "Deny")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            val root = automation.rootInActiveWindow
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.packageName?.toString() in setOf("com.google.android.permissioncontroller",
                        "com.android.permissioncontroller", "com.lbe.security.miui") &&
                    (ids.any { node.viewIdResourceName?.endsWith("/$it") == true } ||
                        labels.any { node.text?.toString().equals(it, ignoreCase = true) })) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            val button = find(root)
            if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(200)
        }
        fail("Required real native location permission dialog was not found.")
    }

    @After fun cleanup() {
        if (context.packageName != "com.journeycontinuity.app.structuredadmissionaudit") return
        runBlocking { app.journeyRepository.completeActive(System.currentTimeMillis()) }
        app.journeyServiceController.stop()
        if (::scenario.isInitialized) scenario.close()
    }

    companion object {
        private val unavailableSms = SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Unavailable")
    }
}
