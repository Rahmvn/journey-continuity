package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.JourneyEntity
import com.journeycontinuity.app.data.repository.RoomJourneyRepository
import com.journeycontinuity.app.data.repository.TravellerAuthorityActions
import com.journeycontinuity.app.data.unresolved.*
import com.journeycontinuity.app.degraded.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.service.JourneyServiceController
import com.journeycontinuity.app.sync.*
import com.journeycontinuity.app.trusted.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the established shell-launched UI host; the app and Room routing are real. */
class TravellerAuthorityNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    @After fun closeHost() { compose.runOnUiThread { host?.finish() } }

    @Test fun homeToPendingBackAndRecreationUseExactRoomState() {
        val id = "20000000-0000-4000-8000-000000000205"
        val owner = "10000000-0000-4000-8000-000000000205"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "10c3a-navigation-${java.util.UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, databaseName).build()
        val started = System.currentTimeMillis()
        runBlocking { assertTrue(db.journeyDao().insertIfNoActive(JourneyEntity(id, "Ilorin",
            started + 3_600_000L, started, JourneyStatus.ACTIVE, null, 1))) }
        val coordinator = DegradedConnectivityCoordinator(object : DegradedConnectivityStateStore {
            override suspend fun updateAtomically(journeyId: String,
                transform: (DegradedConnectivityState?, RecoveryBacklogSnapshot) -> DegradedConnectivityReduction?) = null
            override fun observe(journeyId: String) = kotlinx.coroutines.flow.flowOf<DegradedConnectivityState?>(null)
        }, LatestTelemetryReader { null }, policy = DegradedConnectivityPolicy(DegradedConnectivityLabConfiguration.policyConfig()))
        val repository = RoomJourneyRepository(db.journeyDao(), db.telemetryDao(), db.syncStateDao(),
            db.heartbeatDao(), SyncScheduler {}, coordinator)
        var finalizerCalls = 0
        val actions = TravellerAuthorityActions(db, object : CloudSyncGateway {
            override suspend fun authenticatedOwnerId() = owner
            override suspend fun ownerJourneyState(journeyId: String, ownerId: String) =
                RemoteJourneyState(id, owner, JourneyStatus.ACTIVE, null, null)
            override suspend fun upsertJourney(journey: Journey, ownerId: String) = Unit
            override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) = Unit
        }, object : IncidentAuthorityGateway {
            override suspend fun eligibility(journeyId: String) = AuthorityRead.Value(
                IncidentEligibility(UnresolvedEligibility.NOT_ELIGIBLE,
                    UnresolvedReason.BOUNDARY_NOT_REACHED, null))
            override suspend fun finalize(claim: IncidentClaim, beforeDispatch: suspend () -> Boolean):
                AuthorityRead<IncidentFinalizerResponse> {
                finalizerCalls++
                error("A waiting screen must not initiate finalization")
            }
            override suspend fun provenance(journeyId: String): AuthorityRead<IncidentProvenance> =
                error("No terminal result exists")
        }, { false }, { _, _ -> false }, { owner }, {}, {})
        var authority = TravellerAuthorityViewModel(actions) { false }
        lateinit var journey: JourneyViewModel
        fun render(activity: ComponentActivity) = activity.setContent {
            journey = remember { JourneyViewModel(repository, JourneyLifecycle(repository),
                JourneyServiceController(context), object : TrustedContactGateway {
                    override val invitationSharingAvailable = true
                    override suspend fun list(): List<TrustedContactSummary> = emptyList()
                    override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation = error("Not used")
                    override suspend fun revoke(subjectId: String): Boolean = error("Not used")
                }, coordinator) }
            JourneyProductRoot(journey, LocationUiState(),
                SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Unavailable"),
                "test@example.com", journey::startJourney, {}, {}, {}, {},
                authorityState = authority.state.collectAsState().value,
                onAuthorityCheck = authority::check,
                onAuthorityComplete = authority::complete)
        }
        try {
            host = launchProbableEndUiTestHost(compose)
            compose.runOnUiThread { render(requireNotNull(host)) }
            compose.waitUntil(10_000) {
                journey.uiState.value.activeJourney?.id == id &&
                    authority.state.value.records?.any { it.journey.id == id } == true
            }
            compose.onNodeWithText("Open Journey").performClick()
            compose.onNodeWithText("End Journey").assertIsDisplayed()
            val staleActiveSheetCallback = { journey.endJourney(id) }
            runBlocking {
                val op = requireNotNull(db.operationalBoundaryDao().get(id))
                assertEquals(1, db.operationalBoundaryDao().pauseExact(id, op.admissionId, op.revision,
                    "APPROXIMATE_MONOTONIC_CAP", "INSUFFICIENT_PROOF", null))
            }
            compose.waitUntil(10_000) { authority.state.value.pending?.journey?.id == id }
            compose.onNodeWithTag("pending_authority").assertIsDisplayed()
            compose.onNodeWithText("End Journey").assertDoesNotExist()
            runBlocking {
                val rejected = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(10_000) { journey.uiState.first {
                        it.message == "Journey status changed. Check the saved Journey status."
                    } }
                }
                compose.runOnUiThread { staleActiveSheetCallback() }
                assertEquals("Journey status changed. Check the saved Journey status.", rejected.await().message)
                assertEquals(id, db.journeyDao().getActive()?.id)
                assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById(id)?.status)
                assertNull(db.incidentReconciliationDao().get(id))
            }
            Espresso.pressBack()
            compose.onNodeWithTag("home_pending_authority").performClick()
            compose.onNodeWithTag("pending_authority").assertIsDisplayed()
            compose.onNodeWithText("Awaiting confirmation").assertIsDisplayed()
            compose.onNodeWithTag("pending_retry").assertDoesNotExist()
            Espresso.pressBack()
            compose.onNodeWithTag("home_pending_authority").assertIsDisplayed()
            compose.onNodeWithText("Open Journey").performClick()
            compose.onNodeWithTag("pending_authority").assertIsDisplayed()
            compose.onNodeWithTag("pending_complete").performScrollTo().performClick()
            compose.onNodeWithTag("pending_confirm").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                authority.state.value.pending?.reconciliation?.explicitState == "PENDING"
            }
            Espresso.pressBack()
            compose.onNodeWithText("Start Journey").assertDoesNotExist()
            compose.onNodeWithText("Open Journey").assertIsDisplayed()
            val previous = host
            compose.runOnUiThread { previous!!.recreate() }
            compose.waitUntil(10_000) {
                compose.runOnUiThread {
                    host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .firstOrNull { it !== previous && it.javaClass == ComponentActivity::class.java } as? ComponentActivity
                }
                host != null
            }
            authority = TravellerAuthorityViewModel(actions) { false }
            compose.runOnUiThread { render(requireNotNull(host)) }
            compose.waitUntil(10_000) { authority.state.value.pending?.journey?.id == id }
            compose.onNodeWithTag("home_pending_authority").performClick()
            compose.onNodeWithTag("pending_authority").assertIsDisplayed()
            compose.onNodeWithTag("pending_complete").assertDoesNotExist()
            compose.runOnIdle { assertNull(authority.state.value.result); assertEquals(0, finalizerCalls) }
            runBlocking { assertEquals(id, db.journeyDao().getActive()?.id) }
        } finally {
            compose.runOnUiThread { host?.finish() }
            db.close()
            context.deleteDatabase(databaseName)
        }
    }
}
