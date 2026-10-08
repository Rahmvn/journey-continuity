package com.journeycontinuity.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.LocalProbableEndLifecycle
import com.journeycontinuity.app.data.local.ProbableEndPresentationEntity
import com.journeycontinuity.app.data.local.ProbableEndPresentationStatus
import com.journeycontinuity.app.data.local.toEntity
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import com.journeycontinuity.app.service.JourneyForegroundService
import com.journeycontinuity.app.service.JourneyServiceController
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real UI tap -> production Application response coordinator, in a credential-free isolated UID. */
class ProbableEndResponseRuntimeAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as JourneyContinuityApplication
    private var host: ComponentActivity? = null
    private var db: JourneyDatabase? = null
    private var fixtureId: String? = null
    private val serviceController by lazy { JourneyServiceController(context) }
    private val sms = SmsFallbackStatus(false, false, false, emptyList(), null, false, false, "Unavailable")

    @After fun cleanup() {
        serviceController.stop()
        compose.runOnUiThread { host?.finish() }
        runBlocking {
            val id = fixtureId
            val database = db
            if (id != null && database?.journeyDao()?.getActive()?.id == id)
                database.journeyDao().completeActive(System.currentTimeMillis())
        }
        db?.close()
    }

    private suspend fun fixture(): Pair<Journey, ProbableEndPresentationEntity> {
        check(context.packageName == "com.journeycontinuity.app.probableendguardacceptance")
        check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty())
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val database = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        db = database
        check(database.journeyDao().getActive() == null) { "Refusing an existing ACTIVE Journey" }
        val id = UUID.randomUUID().toString()
        fixtureId = id
        val base = System.currentTimeMillis()
        val started = base - 660_000
        val journey = Journey(id, "Synthetic isolated destination", base + 3_600_000, started,
            JourneyStatus.ACTIVE, null, structuredDestination = StructuredDestination("NG", "ng-kwara", "Kwara",
                "ng-kwara-offa", "Offa", DestinationKind.POINT, 8.1560008, 4.7227435,
                DestinationSelectionSource.MAP_PIN, adminDatasetVersion = "alabarin-2026-10-05-v2",
                localUnitType = AdminLocalUnitType.LGA, boundaryDatasetId = DESTINATION_BOUNDARY_DATASET,
                boundaryDatasetVersion = "alabarin-2026-10-03-v1"))
        assertTrue(database.journeyDao().insertIfNoActive(journey.toEntity()))
        var evaluatedAt = base - 600_000
        val clock = JourneyClock { evaluatedAt }
        val detector = RoomProbableEndStore(database, clock)
        val guard = RoomProbableEndGuardStore(database, clock)
        fun clear() = VerificationGuardRead.Available(AuthoritativeVerificationGuard(id, true,
            VerificationGuardDecision.CLEAR, VerificationKnowledge.CLEAR,
            VerificationGuardReason.VERIFICATION_CLEAR, 0, base - 600_000,
            base - 600_000, evaluatedAt, evaluatedAt + 300_000))
        suspend fun fourFixes(offset: Long) {
            repeat(4) { index ->
                evaluatedAt = base + offset + index * 60_000L
                database.telemetryDao().insertForActiveJourney(TelemetrySample(id, evaluatedAt,
                    8.1560008, 4.7227435, 8f, null, null, ConnectivityState.NONE))
                detector.consumePending(id)
            }
        }
        fourFixes(-600_000)
        val first = guard.prepare(id, GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.REBASE, guard.apply(first, clear()))
        fourFixes(-180_000)
        val candidate = database.probableEndDao().unclosed(id).single()
        val proof = guard.prepare(id, GuardReconciliationTrigger.NEW_CANDIDATE)!!
        assertEquals(GuardReconciliationDecision.PROVE_CONTINUITY, guard.apply(proof, clear()))
        val presentation = RoomProbableEndPresentationCoordinator(database, guard,
            object : ProbableEndVerificationGuardGateway {
                override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?) = clear()
            }, clock)
        val presentationId = presentation.recheckAndPromote(id, candidate.id)!!
        assertTrue(presentation.recordForegroundOpportunity(id, presentationId))
        return journey to database.probableEndPresentationDao().byId(presentationId)!!
    }

    private fun show(journey: Journey, episode: ProbableEndPresentationEntity) {
        val packageName = context.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n $packageName/androidx.activity.ComponentActivity").use { }
        compose.waitUntil("Compose host did not resume", 10_000) {
            compose.runOnUiThread {
                host = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull() as? ComponentActivity
            }
            host != null
        }
        compose.runOnUiThread {
            host!!.setContent {
                JourneyActiveScreen(journey, JourneyUiState(isRestoring = false, activeJourney = journey),
                    LocationUiState(), sms, onBack = {}, onRetry = {}, onEndCompleted = {},
                    probableEnd = episode, onProbableEndResponse = { request, response ->
                        host!!.lifecycleScope.launch { app.respondToProbableEnd(request, response) }
                    }, modifier = Modifier.fillMaxWidth().height(632.dp))
            }
        }
        compose.onNodeWithTag("probable_end_prompt").assertExists()
    }

    @Test fun completedTapCommitsJourneyAndRestoresResult() { runBlocking {
        val (journey, episode) = fixture()
        show(journey, episode)
        instrumentation.runOnMainSync { serviceController.start() }
        compose.waitUntil("Foreground monitoring did not start", 10_000) {
            JourneyForegroundService.serviceRunning.value
        }
        compose.onNodeWithTag("probable_end_completed_action").performClick()
        compose.waitUntil("Completion did not commit", 10_000) {
            runBlocking { db!!.journeyDao().getById(journey.id)!!.status == JourneyStatus.COMPLETED }
        }
        assertEquals(ProbableEndPresentationStatus.RESPONDED_COMPLETED.name,
            db!!.probableEndPresentationDao().byId(episode.id)!!.status)
        assertNull(db!!.journeyDao().getActive())
        assertTrue(db!!.syncStateDao().get(journey.id)!!.workRequested)
        compose.waitUntil("Post-commit monitoring effects did not finish", 10_000) {
            runBlocking { db!!.probableEndPresentationDao().byId(episode.id)!!
                .completionEffectsAppliedAtMillis != null }
        }
        assertFalse(JourneyForegroundService.serviceRunning.value)
        assertEquals(episode.id, db!!.probableEndPresentationDao().observePendingCompletedResult().first()!!.episodeId)
        db!!.close()
        db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        assertEquals(JourneyStatus.COMPLETED, db!!.journeyDao().getById(journey.id)!!.status)
        val result = db!!.probableEndPresentationDao().observePendingCompletedResult().first()!!
        compose.runOnUiThread { host!!.setContent { ProbableEndCompletedResultScreen(result) {
            host!!.lifecycleScope.launch { app.acknowledgeProbableEndCompletion(result.episodeId) }
        } } }
        compose.onNodeWithText("Journey completed").assertExists()
        compose.onNodeWithText("Alabarin has stopped monitoring this Journey.").assertExists()
        compose.onNodeWithTag("probable_end_completed_done").assertExists()
        compose.onNodeWithTag("probable_end_completed_done").performClick()
        compose.waitUntil("Done did not acknowledge result", 10_000) {
            runBlocking { db!!.probableEndPresentationDao().byId(episode.id)!!.resultAcknowledgedAtMillis != null }
        }
        assertNotEquals(episode.id,
            db!!.probableEndPresentationDao().observePendingCompletedResult().first()?.episodeId)
    } }

    @Test fun keepTapSuppressesAndDoesNotResurfaceAfterStoreRecreation() { runBlocking {
        val (journey, episode) = fixture()
        show(journey, episode)
        compose.onNodeWithTag("probable_end_keep_monitoring_action").performClick()
        compose.waitUntil("Suppression did not commit", 10_000) {
            runBlocking { db!!.probableEndPresentationDao().byId(episode.id)!!.status ==
                ProbableEndPresentationStatus.RESPONDED_KEEP_MONITORING.name }
        }
        assertEquals(JourneyStatus.ACTIVE, db!!.journeyDao().getById(journey.id)!!.status)
        assertEquals(LocalProbableEndLifecycle.SUPPRESSED.name,
            db!!.probableEndDao().episodes(journey.id).last().lifecycle)
        assertTrue(db!!.probableEndDao().state(journey.id)!!.requiresReliableExit)
        assertNull(db!!.probableEndPresentationDao().live(journey.id))
        db!!.close()
        db = Room.databaseBuilder(context, JourneyDatabase::class.java, "journey-continuity.db").build()
        assertNull(db!!.probableEndPresentationDao().live(journey.id))
        assertEquals(ProbableEndPresentationStatus.RESPONDED_KEEP_MONITORING.name,
            db!!.probableEndPresentationDao().byId(episode.id)!!.status)
    } }
}
