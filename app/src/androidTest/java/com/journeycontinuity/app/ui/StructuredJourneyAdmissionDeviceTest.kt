package com.journeycontinuity.app.ui

import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.service.JourneyForegroundService
import com.journeycontinuity.app.trusted.*
import com.journeycontinuity.app.ui.destination.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real product root, Room repository and location FGS in a credential-free disposable package.
 * Permission decisions and destination gateways are deterministic; no hosted rows or live geocoder.
 */
class StructuredJourneyAdmissionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as JourneyContinuityApplication
    private val catalogue get() = app.adminCatalogue.snapshot()
    private val store = ViewModelStore()
    private lateinit var journey: JourneyViewModel
    private lateinit var picker: DestinationPickerViewModel
    private var verifierCalls = 0
    private var legacyCalls = 0
    private var pending: JourneyStartRequest? = null
    private var allowPrerequisites = true
    private var monitor = true

    @After fun cleanup() {
        if (context.packageName != "com.journeycontinuity.app.structuredadmissionaudit") return
        runBlocking { app.journeyRepository.completeActive(System.currentTimeMillis()) }
        app.journeyServiceController.stop()
        compose.waitUntil(10_000) { !JourneyForegroundService.serviceRunning.value }
        if (::journey.isInitialized) compose.runOnIdle { store.clear() }
    }

    private fun showStart() {
        check(context.packageName == "com.journeycontinuity.app.structuredadmissionaudit")
        check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty()) {
            "Device admission acceptance requires the credential-free isolated build."
        }
        compose.runOnUiThread {
            picker = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
                DestinationSearchResponse.Results(listOf(searchResult()))
            }, { false })
            journey = JourneyViewModel(app.journeyRepository, app.journeyLifecycle,
                app.journeyServiceController, emptyContacts, app.degradedConnectivityCoordinator)
            store.put("picker", picker); store.put("journey", journey)
        }
        compose.setContent {
            val state by journey.uiState.collectAsState()
            LaunchedEffect(state.activeJourney?.id) {
                if (monitor && state.activeJourney != null) app.journeyServiceController.start()
            }
            JourneyProductRoot(journey, LocationUiState(), unavailableSms, null,
                onStartRequested = { _, _ -> legacyCalls++ }, onRetryMonitoring = {},
                onOpenLocationSettings = {}, onRequestSmsPermissions = {}, onSelectSmsSubscription = {},
                destinationPickerViewModel = picker,
                onStructuredStartRequested = { request ->
                    pending = request
                    if (allowPrerequisites) journey.startJourney(request)
                    else journey.cancelPendingStart()
                })
        }
        compose.waitUntil(10_000) { !journey.uiState.value.isRestoring }
        compose.onNodeWithText("Start Journey").performClick()
    }

    private fun selectArea(region: String = "ng-kwara", unit: String = "ng-kwara-offa") {
        compose.onNodeWithTag("choose_structured_destination").performClick()
        compose.runOnIdle { picker.selectRegion(region); picker.selectUnit(unit) }
    }

    private fun startSelected(): DestinationDraft {
        val frozen = picker.state.value.selectedDraft!!
        compose.runOnIdle { journey.updateExpectedArrival(System.currentTimeMillis() + 7_200_000) }
        compose.onNodeWithTag("journey_primary_action").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { journey.uiState.value.productRoute == JourneyProductRoute.CHECKPOINT }
        compose.onNodeWithText("Start anyway").performClick()
        return frozen
    }

    private fun assertAdmitted(frozen: DestinationDraft): Journey {
        compose.waitUntil(15_000) { journey.uiState.value.activeJourney != null }
        val persisted = runBlocking { app.journeyRepository.activeJourney.first() }!!
        assertEquals(frozen.displayLabel, persisted.destination)
        assertEquals(frozen.destination, persisted.structuredDestination)
        assertEquals(JourneyStatus.ACTIVE, persisted.status)
        assertEquals(0, legacyCalls)
        compose.waitUntil(15_000) { picker.state.value.selectedDraft == null }
        if (monitor) compose.waitUntil(20_000) {
            JourneyForegroundService.monitoringJourneyId.value == persisted.id
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("journey-continuity.db").path,
            null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT j.id, s.workRequested, s.permanentlyBlocked, h.journeyId
                FROM journeys j JOIN journey_sync_states s ON s.journeyId = j.id
                JOIN journey_heartbeat_states h ON h.journeyId = j.id WHERE j.activeSlot = 1""", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(persisted.id, rows.getString(0))
                assertEquals(1, rows.getInt(1)); assertEquals(0, rows.getInt(2))
                assertEquals(persisted.id, rows.getString(3))
                assertFalse(rows.moveToNext())
            }
        }
        return persisted
    }

    @Test fun localityPickerCheckpointRoomAndRealMonitoring() {
        showStart(); selectArea()
        compose.onNodeWithTag("destination_locality").performClick()
        compose.onNodeWithText("Offa, Kwara").assertIsDisplayed()
        val persisted = assertAdmitted(startSelected())
        assertEquals(AdminLocalUnitType.LGA, persisted.structuredDestination!!.localUnitType)
        assertNull(persisted.structuredDestination!!.latitude)
    }

    @Test fun areaCouncilRetainsCanonicalTypeAndContextualLabel() {
        showStart(); selectArea("ng-fct", "ng-fct-abuja-municipal")
        compose.onNodeWithTag("destination_locality").performClick()
        val frozen = startSelected()
        val persisted = assertAdmitted(frozen)
        assertEquals(AdminLocalUnitType.AREA_COUNCIL, persisted.structuredDestination!!.localUnitType)
        assertEquals("${catalogue.localUnit("ng-fct-abuja-municipal")!!.canonicalName}, ${catalogue.region("ng-fct")!!.canonicalName}", persisted.destination)
    }

    @Test fun mapPinFrozenPointWithoutProviderStartsWithoutReverification() {
        showStart(); selectArea()
        val frozen = prepareMapDraft(withProvider = false)
        compose.runOnIdle { picker.acceptDraft(frozen) }
        val persisted = assertAdmitted(startSelected())
        assertEquals(1, verifierCalls)
        assertNull(persisted.structuredDestination!!.provider)
        assertEquals(8.1560008, persisted.structuredDestination!!.latitude!!, 0.0)
        assertEquals(4.7227435, persisted.structuredDestination!!.longitude!!, 0.0)
    }

    @Test fun mapPinWithMinimizedReverseEvidenceStartsWithoutReverification() {
        showStart(); selectArea()
        val frozen = prepareMapDraft(withProvider = true)
        compose.runOnIdle { picker.acceptDraft(frozen) }
        val persisted = assertAdmitted(startSelected())
        assertEquals(1, verifierCalls)
        assertEquals("GEOAPIFY", persisted.structuredDestination!!.provider)
        assertNull(persisted.structuredDestination!!.providerPlaceId)
        assertEquals(attribution, persisted.structuredDestination!!.providerAttribution)
    }

    @Test fun placeSearchSelectionStartsWithCompleteEvidence() {
        showStart(); selectArea()
        compose.onNodeWithTag("destination_search_open").performClick()
        compose.onNodeWithTag("destination_query").performTextInput("Summit")
        compose.waitUntil(10_000) { picker.state.value.searchState == DestinationSearchState.RESULTS }
        compose.onNodeWithTag("result_fixture-place-id").performClick()
        val persisted = assertAdmitted(startSelected())
        assertEquals(DestinationSelectionSource.PLACE_SEARCH, persisted.structuredDestination!!.selectionSource)
        assertEquals("fixture-place-id", persisted.structuredDestination!!.providerPlaceId)
    }

    @Test fun frozenPointOfflineStartsRealMonitoringWithPendingSync() {
        showStart(); selectArea()
        val frozen = prepareMapDraft(withProvider = false)
        compose.runOnIdle { picker.acceptDraft(frozen) }
        val wifi = shell("settings get global wifi_on").trim() == "1"
        val data = shell("settings get global mobile_data").trim() == "1"
        try {
            shell("svc wifi disable"); shell("svc data disable")
            compose.waitUntil(15_000) { !com.journeycontinuity.app.telemetry.DeviceContextReader(context).usableInternet() }
            assertAdmitted(startSelected())
            assertEquals(1, verifierCalls)
        } finally {
            shell("svc wifi ${if (wifi) "enable" else "disable"}")
            shell("svc data ${if (data) "enable" else "disable"}")
        }
    }

    @Test fun repeatedStartPermissionAndServiceCommandsKeepOneJourneyAndRuntime() {
        showStart(); selectArea()
        compose.onNodeWithTag("destination_locality").performClick()
        val frozen = startSelected()
        val persisted = assertAdmitted(frozen)
        compose.runOnIdle {
            journey.startJourney(pending!!)
            journey.startStructuredAnyway { error("Consumed checkpoint replayed") }
            app.journeyServiceController.start(); app.journeyServiceController.start()
        }
        compose.waitForIdle()
        assertEquals(persisted.id, runBlocking { app.journeyRepository.activeJourney.first() }!!.id)
        assertEquals(persisted.id, JourneyForegroundService.monitoringJourneyId.value)
    }

    @Test fun deniedPrerequisiteCreatesNothingAndRetainsPickerSelection() {
        allowPrerequisites = false
        showStart(); selectArea()
        compose.onNodeWithTag("destination_locality").performClick()
        val frozen = startSelected()
        compose.runOnIdle {
            journey.startJourney(pending!!)
            assertNull(journey.uiState.value.activeJourney)
            assertEquals(frozen, picker.state.value.selectedDraft)
        }
        assertNull(runBlocking { app.journeyRepository.activeJourney.first() })
        assertNull(JourneyForegroundService.monitoringJourneyId.value)
    }

    @Test fun failedRoomAdmissionCreatesNoJourneySyncHeartbeatOrMonitoring() {
        showStart(); selectArea()
        compose.onNodeWithTag("destination_locality").performClick()
        SQLiteDatabase.openDatabase(context.getDatabasePath("journey-continuity.db").path,
            null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("""CREATE TRIGGER admission_test_reject_heartbeat BEFORE INSERT ON journey_heartbeat_states
                BEGIN SELECT RAISE(ABORT, 'isolated heartbeat failure'); END""")
            try {
                val frozen = startSelected()
                compose.waitUntil(10_000) { pending != null && !journey.uiState.value.isActionInProgress }
                compose.onNodeWithText("Could not start journey. Try again.").assertIsDisplayed()
                assertNull(runBlocking { app.journeyRepository.activeJourney.first() })
                assertFalse(JourneyForegroundService.serviceRunning.value)
                assertEquals(frozen, picker.state.value.selectedDraft)
                db.rawQuery("SELECT count(*) FROM journeys WHERE activeSlot = 1", null).use { rows ->
                    assertTrue(rows.moveToFirst()); assertEquals(0, rows.getInt(0))
                }
                db.rawQuery("""SELECT count(*) FROM journey_sync_states s LEFT JOIN journeys j ON j.id = s.journeyId
                    WHERE j.id IS NULL""", null).use { rows ->
                    assertTrue(rows.moveToFirst()); assertEquals(0, rows.getInt(0))
                }
            } finally { db.execSQL("DROP TRIGGER admission_test_reject_heartbeat") }
        }
    }

    private fun prepareMapDraft(withProvider: Boolean): DestinationDraft.ExactPoint {
        lateinit var map: DestinationMapViewModel
        compose.runOnIdle {
            map = DestinationMapViewModel(catalogue, DestinationCoordinateVerifierGateway {
                verifierCalls++
                CoordinateVerification(BoundaryOutcome.INSIDE, OPERATIONAL_BOUNDARY_DATASET, boundaryVersion)
            }, DestinationMapViewportGateway { _, _ -> LocalUnitViewport.Available(4.6, 8.1, 4.8, 8.3,
                OPERATIONAL_BOUNDARY_DATASET, boundaryVersion) }, DestinationMapLabelGateway {
                MapLabelResponse.Verified(if (withProvider) MapLabelStatus.SPECIFIC_LABEL else MapLabelStatus.PROVIDER_UNAVAILABLE,
                    boundaryVersion, "Offa", if (withProvider) MapLabelEvidence("Fixture place", null,
                        MapLabelResultType.PLACE, "GEOAPIFY", null, null, attribution) else null)
            }, initiallyOnline = true)
            store.put("map", map)
            map.open(picker.requestMap()!!.value)
        }
        compose.waitUntil(10_000) { (map.state.value as? DestinationMapState.Positioning)?.initialCamera != null }
        compose.runOnIdle {
            map.userCameraMoveStarted()
            map.cameraIdle(MapCamera(MapCoordinate(8.1560008, 4.7227435), 15.0))
        }
        compose.waitUntil(10_000) { (map.state.value as? DestinationMapState.Positioning)?.phase is MapPinPhase.Inside }
        compose.runOnIdle { map.continueToNaming() }
        compose.waitUntil(10_000) { (map.state.value as? DestinationMapState.Naming)?.label is MapNamingLabel.Ready }
        lateinit var draft: DestinationDraft.ExactPoint
        compose.runOnIdle { map.updateName("Frozen fixture name"); draft = map.finish()!! }
        return draft
    }

    private fun searchResult() = PlaceSearchResult("GEOAPIFY", "fixture-place-id", "Summit fixture", null,
        8.1560008, 4.7227435, SearchResultType.PLACE, "Kwara", "Offa", AdminTextMatch.MATCH,
        AdminTextMatch.MATCH, SearchSuitability.SELECTABLE_POINT, BoundaryOutcome.INSIDE, boundaryVersion, attribution)

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }

    companion object {
        private const val boundaryVersion = "alabarin-2026-10-03-v1"
        private val attribution = ProviderAttribution("OpenStreetMap", "OpenStreetMap contributors", "ODbL",
            "https://www.openstreetmap.org/copyright")
        private val unavailableSms = SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Unavailable")
        private val emptyContacts = object : TrustedContactGateway {
            override val invitationSharingAvailable = false
            override suspend fun list() = emptyList<TrustedContactSummary>()
            override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation = error("Unused")
            override suspend fun revoke(subjectId: String) = false
        }
    }
}
