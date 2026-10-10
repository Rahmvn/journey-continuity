package com.journeycontinuity.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import com.journeycontinuity.app.data.local.*
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.domain.JourneyStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap
import java.io.File
import com.journeycontinuity.app.domain.IncidentClaim
import com.journeycontinuity.app.domain.IncidentProvenance
import com.journeycontinuity.app.domain.JourneyEtaPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

@OptIn(ExperimentalTestApi::class)
class TravellerAuthorityUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    @After fun closeHost() { compose.runOnUiThread { host?.finish() } }
    private val journey = JourneyEntity("20000000-0000-4000-8000-000000000202", "Ilorin",
        1_790_003_600_000L, 1_790_000_000_000L, JourneyStatus.ACTIVE, null, 1)
    private fun pending(reason: String = "LOST_OR_INVALID_BOOT_ANCHOR",
        workflow: JourneyTerminalReconciliationEntity? = null) = TravellerAuthorityRecord(journey,
        OperationalBoundaryEntity(journey.id, "30000000-0000-4000-8000-000000000202",
            state = "PENDING_AUTHORITY", pauseReason = reason), workflow, null, null, emptyList())

    @Test fun resultFollowsFigmaAtReferenceAndRemainsScrollableAtLargeFonts() {
        host = launchProbableEndUiTestHost(compose)
        val eta = Instant.parse("2026-10-08T17:30:00Z")
        val boundary = eta.plusSeconds(86_400)
        val proof = IncidentProvenance(IncidentClaim(journey.id,
            "10000000-0000-4000-8000-000000000202", "30000000-0000-4000-8000-000000000202",
            JourneyEtaPolicy.VERSION, null, eta.toString(), boundary.toString(),
            "40000000-0000-4000-8000-000000000202", 1, boundary.minusSeconds(60).toString(),
            1, boundary.minusSeconds(60).toString()), boundary.toString(),
            boundary.plusSeconds(604_800).toString(), "AUTHORITATIVE_UNRESOLVED_FINALIZER_V1")
        proof.validate()
        val result = UnresolvedJourneyResult(journey.id, journey.destination, eta.toEpochMilli(),
            proof.finalizedAt, Json.encodeToString(proof))
        for ((width, height, scale) in listOf(Triple(390, 844, 1f), Triple(320, 480, 1.5f),
            Triple(320, 480, 2f))) {
            var done = 0
            compose.runOnUiThread { host!!.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    Box(Modifier.requiredSize(width.dp, height.dp).clipToBounds()
                        .consumeWindowInsets(WindowInsets.safeDrawing)) {
                        UnresolvedJourneyResultScreen(result, false, null) { done++ }
                    }
                }
            } }
            if (width == 390) capture("10c3a_result_reference.png")
            compose.onNodeWithTag("unresolved_title").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("unresolved_title")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            compose.onNodeWithTag("unresolved_body").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("unresolved_caveat").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("unresolved_caveat")
                .assertTextEquals("Uncertainty remains. This does not mean the traveller is safe, missing, or in danger.")
            compose.onNodeWithTag("unresolved_body").assertTextEquals(
                "Alabarin stopped continuous monitoring because this Journey remained unresolved 24 hours after the expected arrival time.")
            compose.onNodeWithTag("unresolved_identity").assertDoesNotExist()
            compose.onNodeWithText("Finalized by server").assertDoesNotExist()
            compose.onNodeWithText(result.journeyId, substring = true).assertDoesNotExist()
            compose.onNodeWithText(result.finalizedAt, substring = true).assertDoesNotExist()
            compose.onAllNodesWithText("Done").assertCountEquals(1)
            for (tag in listOf("unresolved_title", "unresolved_body", "unresolved_caveat")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    it(layouts)
                }
                assertTrue("$tag clipped at $scale: ${layouts.map { "size=${it.size}, lines=${it.lineCount}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}" }}",
                    layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
            }
            compose.onNodeWithTag("unresolved_done").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, done) }
            Espresso.pressBack()
            compose.runOnIdle { assertEquals(2, done) }
        }
    }

    @Test fun pendingShowsDistinctEvidenceAndOnlyAcceptableActions() {
        host = launchProbableEndUiTestHost(compose)
        var completes = 0
        var openedNetworkSettings = 0
        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(pending(), TravellerAuthorityUiState(records = listOf(pending()), offline = true),
                {}, {}, { completes++ }, {}, {}, { openedNetworkSettings++ })
        } }
        compose.onNodeWithText("Monitoring paused").assertIsDisplayed()
        compose.onNodeWithTag("pending_title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithText("Alabarin").assertIsDisplayed()
        compose.onNodeWithText("Network settings").assertIsDisplayed()
        capture("10c3a_pending_reference.png")
        compose.onNodeWithTag("pending_timing").assertIsDisplayed()
        compose.onNodeWithTag("pending_status").assertIsDisplayed()
        compose.onNodeWithTag("pending_retry").assertDoesNotExist()
        compose.onNodeWithTag("pending_network_settings").performClick()
        compose.onNodeWithTag("pending_network_settings").assertHasClickAction()
        val networkBounds = compose.onNodeWithTag("pending_network_settings")
            .getUnclippedBoundsInRoot()
        assertTrue(networkBounds.bottom - networkBounds.top >= 48.dp)
        compose.runOnIdle { assertEquals(1, openedNetworkSettings) }
        compose.onNodeWithTag("pending_network_settings")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(2, openedNetworkSettings) }
        compose.onNodeWithTag("pending_complete").performScrollTo().performClick()
        compose.onNodeWithTag("pending_confirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, completes) }

        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(pending(), TravellerAuthorityUiState(records = listOf(pending())),
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Awaiting confirmation").assertIsDisplayed()
        compose.onNodeWithTag("pending_retry").assertDoesNotExist()
        capture("10c3a_awaiting_automatic.png")

        val blocked = pending(workflow = JourneyTerminalReconciliationEntity(journey.id,
            claimState = "PENDING", claimDispatch = "MAY_HAVE_DISPATCHED"))
        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(blocked, TravellerAuthorityUiState(records = listOf(blocked)),
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("pending_complete").assertDoesNotExist()
        compose.onNodeWithTag("pending_stop").assertDoesNotExist()
        compose.onNodeWithText("Finalizer acknowledgement is uncertain.", substring = true).assertExists()

        var accountReviews = 0
        val authentication = pending(workflow = JourneyTerminalReconciliationEntity(journey.id,
            lastOutcome = "AUTHENTICATION_REQUIRED"))
        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(authentication, TravellerAuthorityUiState(records = listOf(authentication)),
                {}, {}, {}, {}, { accountReviews++ }, {})
        } }
        compose.onNodeWithTag("pending_auth").performClick()
        compose.runOnIdle { assertEquals(1, accountReviews) }

        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(pending(), TravellerAuthorityUiState(records = listOf(pending()),
                returnedFromNetworkSettings = true), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Connection restored").assertIsDisplayed()
        compose.onNodeWithText("Alabarin will check automatically when authentication and network access permit.").assertIsDisplayed()
        compose.onNodeWithTag("pending_retry").assertDoesNotExist()
        compose.onNodeWithTag("pending_network_settings").assertDoesNotExist()
        capture("10c3a_returned_connected.png")

        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(pending(), TravellerAuthorityUiState(records = listOf(pending()),
                offline = true, returnedFromNetworkSettings = true), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Still offline").assertIsDisplayed()
        compose.onNodeWithTag("pending_network_settings").assertIsDisplayed()
        compose.onNodeWithTag("pending_retry").assertDoesNotExist()

        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(pending(), TravellerAuthorityUiState(records = listOf(pending()),
                busyJourneyId = journey.id, checking = true), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Checking status").assertIsDisplayed()
        compose.onNodeWithTag("pending_retry").assertDoesNotExist()

        var retries = 0
        val failed = pending(workflow = JourneyTerminalReconciliationEntity(journey.id,
            lastOutcome = "UNAVAILABLE", retryAtMillis = System.currentTimeMillis() - 1))
        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(failed, TravellerAuthorityUiState(records = listOf(failed)),
                {}, { retries++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("pending_retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        capture("10c3a_failed_retry.png")
        compose.runOnUiThread { host!!.setContent {
            PendingAuthorityScreen(failed, TravellerAuthorityUiState(records = listOf(failed),
                busyJourneyId = journey.id, checking = true), {}, { retries++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("pending_retry").assertDoesNotExist()

        for (scale in listOf(1.5f, 2f)) {
            compose.runOnUiThread { host!!.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    Box(Modifier.requiredSize(320.dp, 480.dp).clipToBounds()) {
                        PendingAuthorityScreen(pending(), TravellerAuthorityUiState(records = listOf(pending()), offline = true),
                            {}, {}, {}, {}, {}, {})
                    }
                }
            } }
            if (scale == 2f) capture("10c3a_pending_compact.png")
            compose.onNodeWithTag("pending_title").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pending_status").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pending_complete").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pending_stop").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No connection").performScrollTo().assertIsDisplayed()
            for (tag in listOf("pending_title", "pending_timing")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(tag).performScrollTo()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("$tag clipped at $scale: ${layouts.map { "size=${it.size}, lines=${it.lineCount}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}" }}",
                    layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
            }
        }
    }

    private fun capture(name: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(requireNotNull(target.getExternalFilesDir(null)), name)
        file.outputStream().use { output ->
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
