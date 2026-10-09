package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.data.local.ProbableEndCompletedResult
import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.data.probableend.*
import com.journeycontinuity.app.domain.*
import com.journeycontinuity.app.domain.probableend.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProbableEndAutomaticResultUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    @After fun closeHost() { compose.runOnUiThread { host?.finish() } }

    @Test fun resultUsesUncertainCopyAndDoneOnlyAcknowledges() {
        var acknowledged = 0
        host = launchProbableEndUiTestHost(compose)
        compose.runOnUiThread { host!!.setContent {
            ProbableEndAutomaticResultScreen(
                ProbableEndCompletedResult("episode", "journey", "Test destination", 1_700_000_000_000L),
            ) { acknowledged++ }
        } }
        compose.onNodeWithTag("probable_end_automatic_title").assertExists()
        compose.onNodeWithText("Monitoring ended automatically").assertExists()
        compose.onNodeWithText("This does not confirm arrival or safety.").assertExists()
        compose.onNodeWithTag("probable_end_automatic_done").performClick()
        compose.runOnIdle { assertEquals(1, acknowledged) }
    }

    @Test fun bothResultsKeepFullTextAndDoneReachableAtCompactAndReferenceSizes() {
        host = launchProbableEndUiTestHost(compose)
        val longDestination = "Ilorin — a long destination label that must remain readable without truncation"
        val result = ProbableEndCompletedResult("episode", "journey", longDestination, 1_700_000_000_000)
        for (automatic in listOf(true, false)) for ((width, height, fontScale) in listOf(
            Triple(320, 480, 1.5f), Triple(360, 640, 1f), Triple(390, 844, 1f), Triple(320, 480, 2f),
        )) {
            var acknowledged = 0
            fun render() = compose.runOnUiThread { host!!.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    Box(Modifier.requiredSize(width.dp, height.dp).clipToBounds()) {
                        if (automatic) ProbableEndAutomaticResultScreen(result) { acknowledged++ }
                        else ProbableEndCompletedResultScreen(result) { acknowledged++ }
                    }
                }
            } }
            render()
            val title = if (automatic) "probable_end_automatic_title" else "probable_end_completed_title"
            val body = if (automatic) "probable_end_automatic_body" else "probable_end_completed_body"
            assertFullTextCanBeScrolledTo(title)
            assertFullTextCanBeScrolledTo(body)
            if (automatic) assertFullTextCanBeScrolledTo("probable_end_automatic_caveat")
            val destinationLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(longDestination).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(destinationLayouts) }
            assertTrue(destinationLayouts.isNotEmpty() && destinationLayouts.none { it.hasVisualOverflow })
            val doneTag = if (automatic) "probable_end_automatic_done" else "probable_end_completed_done"
            val done = compose.onNodeWithTag(doneTag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            val bounds = done.getUnclippedBoundsInRoot()
            val clipped = done.getBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            assertEquals((bounds.bottom - bounds.top).value, (clipped.bottom - clipped.top).value, 0.5f)
            if (automatic && width == 320 && fontScale == 1.5f) {
                val scrollTag = "probable_end_automatic_result_scroll"
                val beforeRange = compose.onNodeWithTag(scrollTag).fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                val before = beforeRange.value()
                val beforeMax = beforeRange.maxValue()
                assertTrue(before > 0f)
                val previous = requireNotNull(host)
                compose.runOnUiThread { previous.recreate() }
                compose.waitUntil("Result host did not recreate", 10_000) {
                    compose.runOnUiThread {
                        host = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .firstOrNull { it.javaClass == ComponentActivity::class.java && it !== previous } as? ComponentActivity
                    }
                    host != null
                }
                render()
                val afterRange = compose.onNodeWithTag(scrollTag).fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                val after = afterRange.value()
                val afterMax = afterRange.maxValue()
                // Native insets can change the available scroll range on recreation.
                // Restore the offset, clamped to that range, rather than resetting to top.
                assertEquals("Scroll restoration: before=$before/$beforeMax, after=$after/$afterMax",
                    minOf(before, afterMax), after, 0.5f)
                assertTrue(after > 0f)
                val restored = compose.onNodeWithTag(doneTag).assertIsDisplayed().getBoundsInRoot()
                val restoredFull = compose.onNodeWithTag(doneTag).getUnclippedBoundsInRoot()
                assertEquals((restoredFull.bottom - restoredFull.top).value,
                    (restored.bottom - restored.top).value, 0.5f)
            }
            compose.onNodeWithTag(doneTag).assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, acknowledged) }
        }
    }

    private fun assertFullTextCanBeScrolledTo(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No essential result text may be truncated: $tag; " + layouts.joinToString {
            "size=${it.size}, lines=${it.lineCount}, heightOverflow=${it.didOverflowHeight}, widthOverflow=${it.didOverflowWidth}, constraints=${it.layoutInput.constraints}"
        }, layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
    }

    @Test fun compactScrolledDoneAcknowledgesExactDurableAutoEndWithoutChangingJourney() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "probable-end-ui-repair.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
        try {
            val start = 1_700_000_000_000L
            var now = start
            val clock = JourneyClock { now }
            val detector = RoomProbableEndStore(db, clock)
            val guard = RoomProbableEndGuardStore(db, clock)
            fun clear() = VerificationGuardRead.Available(AuthoritativeVerificationGuard("test", true,
                VerificationGuardDecision.CLEAR, VerificationKnowledge.CLEAR,
                VerificationGuardReason.VERIFICATION_CLEAR, 0, start, start, now, now + 300_000))
            lateinit var auto: RoomProbableEndAutoEndCoordinator
            lateinit var episodeId: String
            runBlocking {
                check(db.journeyDao().insertIfNoActive(Journey("test", "Ilorin", start + 3_600_000, start,
                    JourneyStatus.ACTIVE, null, structuredDestination = StructuredDestination("NG", "ng-kwara", "Kwara",
                        "ng-kwara-offa", "Offa", DestinationKind.POINT, 8.1560008, 4.7227435,
                        DestinationSelectionSource.MAP_PIN, adminDatasetVersion = "alabarin-2026-10-05-v2",
                        localUnitType = AdminLocalUnitType.LGA, boundaryDatasetId = DESTINATION_BOUNDARY_DATASET,
                        boundaryDatasetVersion = "alabarin-2026-10-03-v1")).toEntity()))
                suspend fun dwell() { repeat(4) {
                    now += 60_000
                    db.telemetryDao().insertForActiveJourney(TelemetrySample("test", now, 8.1560008,
                        4.7227435, 8f, null, null, ConnectivityState.NONE))
                    detector.consumePending("test")
                } }
                dwell()
                check(guard.apply(guard.prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!, clear()) == GuardReconciliationDecision.REBASE)
                dwell()
                check(guard.apply(guard.prepare("test", GuardReconciliationTrigger.NEW_CANDIDATE)!!, clear()) == GuardReconciliationDecision.PROVE_CONTINUITY)
                val presentation = RoomProbableEndPresentationCoordinator(db, guard, object : ProbableEndVerificationGuardGateway {
                    override suspend fun read(journeyId: String, previous: AuthoritativeVerificationGuard?) = clear()
                }, clock)
                episodeId = presentation.recheckAndPromote("test", db.probableEndDao().unclosed("test").single().id)!!
                check(presentation.recordForegroundOpportunity("test", episodeId))
                auto = RoomProbableEndAutoEndCoordinator(db, object : ProbableEndAutoEndGateway {
                    override suspend fun register(request: ProbableEndServerWindowRequest) = ProbableEndRegistration.Registered(now, now + 1_800_000)
                    override suspend fun finalize(journeyId: String, presentationEpisodeId: String) = ProbableEndFinalization.Ended(now)
                }, ProbableEndAutomaticWakeScheduler { _, _ -> }, {}, clock = clock)
                auto.register(episodeId)
                val p = db.probableEndPresentationDao().byId(episodeId)!!
                now = p.confirmationDeadlineAtMillis!!
                RoomProbableEndDeadlineCoordinator(db, ProbableEndDeadlineScheduler { _, _ -> }, clock).expire(p.deadlineIdentity())
                check(auto.advance(episodeId) == ProbableEndAutomaticOutcome.ENDED)
            }
            host = launchProbableEndUiTestHost(compose)
            val result = ProbableEndCompletedResult(episodeId, "test", "Ilorin", start + 3_600_000)
            compose.runOnUiThread { host!!.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                    Box(Modifier.requiredSize(320.dp, 480.dp).clipToBounds()) {
                        ProbableEndAutomaticResultScreen(result) { host!!.lifecycleScope.launch { assertTrue(auto.acknowledgeResult(episodeId)) } }
                    }
                }
            } }
            compose.onNodeWithTag("probable_end_automatic_caveat").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("probable_end_automatic_done").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil("Done acknowledgement did not persist", 10_000) {
                runBlocking { db.probableEndPresentationDao().byId(episodeId)?.automaticResultAcknowledgedAtMillis != null }
            }
            runBlocking {
                val saved = db.probableEndPresentationDao().byId(episodeId)!!
                assertNotNull(saved.automaticResultAcknowledgedAtMillis)
                assertNull(saved.respondedAtMillis)
                assertEquals("AUTO_ENDED", saved.status)
                assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById("test")!!.status)
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
