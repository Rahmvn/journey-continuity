package com.journeycontinuity.app.ui.destination

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.journeycontinuity.app.data.catalogue.BundledAdminCatalogue
import com.journeycontinuity.app.ui.JourneyStartScreen
import com.journeycontinuity.app.ui.JourneyUiState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fake backend/map content: no tile key, live provider, journey or normal-app installation required. */
class DestinationMapScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val coordinate = MapCoordinate(8.1560008, 4.7227435)
    private val version = "alabarin-2026-10-03-v1"
    private fun result(outcome: BoundaryOutcome = BoundaryOutcome.INSIDE) = CoordinateVerification(outcome,
        if (outcome in setOf(BoundaryOutcome.UNAVAILABLE, BoundaryOutcome.INVALID_CONTEXT)) null else OPERATIONAL_BOUNDARY_DATASET,
        if (outcome in setOf(BoundaryOutcome.UNAVAILABLE, BoundaryOutcome.INVALID_CONTEXT)) null else version)
    private inner class Fake : DestinationMapGateway {
        var verify: suspend () -> CoordinateVerification = { result() }
        override suspend fun verify(request: MapPointRequest) = verify.invoke()
        override suspend fun viewport(regionId: String, localUnitId: String) =
            LocalUnitViewport.Available(4.6, 8.0, 4.8, 8.3, OPERATIONAL_BOUNDARY_DATASET, version)
        override suspend fun label(request: MapPointRequest) =
            MapLabelResponse.Verified(MapLabelStatus.NO_MEANINGFUL_LABEL, version, "Offa")
    }
    private fun open(vm: DestinationMapViewModel) {
        val catalogue = BundledAdminCatalogue(compose.activity).snapshot()
        vm.open(MapRefinementRequest(catalogue.region("ng-kwara")!!, catalogue.localUnit("ng-kwara-offa")!!,
            PlaceSearchResult("GEOAPIFY", "seed", "Olofa Way", null, coordinate.latitude, coordinate.longitude,
                SearchResultType.ROAD, "Kwara", "Offa", AdminTextMatch.MATCH, AdminTextMatch.MATCH,
                SearchSuitability.NEEDS_MAP_PIN, null, null, null)))
        vm.cameraIdle(MapCamera(coordinate, 15.0))
    }
    private fun model(fake: Fake = Fake(), online: Boolean = true) = DestinationMapViewModel(
        BundledAdminCatalogue(compose.activity).snapshot(), fake, fake, fake, online)
    private fun show(vm: DestinationMapViewModel, onDraft: (DestinationDraft) -> Unit = {}, short: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (short) 1.5f else 1f)) {
                Box(if (short) Modifier.size(320.dp, 340.dp) else Modifier) {
                    DestinationMapScreen(vm, {}, onDraft, {}, observeConnectivity = false,
                        mapContent = { modifier, _ -> Box(modifier) })
                }
            }
        }
    }
    private fun waitInside(vm: DestinationMapViewModel) = compose.waitUntil(5_000) {
        (vm.state.value as? DestinationMapState.Positioning)?.phase is MapPinPhase.Inside
    }

    @Test fun fixedPinMatchesViewportCentreAndCheckingDisablesContinue() {
        val pending = CompletableDeferred<CoordinateVerification>()
        val fake = Fake().apply { verify = { pending.await() } }
        val vm = model(fake); show(vm)
        compose.runOnIdle { open(vm) }
        compose.onNodeWithTag("map_helper").assertTextEquals("Checking this location…")
        compose.onNodeWithTag("map_continue").assertIsNotEnabled()
        val viewport = compose.onNodeWithTag("map_viewport").fetchSemanticsNode().boundsInRoot
        val pin = compose.onNodeWithTag("map_fixed_pin").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.center.x, pin.center.x, 1f); assertEquals(viewport.center.y, pin.center.y, 1f)
        compose.runOnIdle { pending.complete(result()) }
        waitInside(vm); compose.onNodeWithTag("map_continue").assertIsEnabled()
        compose.runOnIdle { vm.userCameraMoveStarted() }
        compose.onNodeWithTag("map_continue").assertIsNotEnabled()
    }
    @Test fun blockedOutcomesExposeTheirCopyAndOnlyTheIntendedActions() {
        val fake = Fake(); val vm = model(fake); show(vm)
        for ((outcome, copy) in listOf(
            BoundaryOutcome.OUTSIDE_SELECTED_UNIT to "Outside selected area",
            BoundaryOutcome.BOUNDARY_AMBIGUOUS to "Area boundary",
            BoundaryOutcome.COVERAGE_UNRESOLVED to "Can’t verify this point",
            BoundaryOutcome.UNAVAILABLE to "Verification unavailable",
            BoundaryOutcome.INVALID_CONTEXT to "Selected area unavailable")) {
            compose.runOnIdle { fake.verify = { result(outcome) }; open(vm) }
            compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Positioning)?.phase !is MapPinPhase.Checking }
            compose.onNodeWithTag("map_content").performScrollToNode(hasText(copy))
            compose.onNodeWithText(copy).assertIsDisplayed()
            when (outcome) {
                BoundaryOutcome.OUTSIDE_SELECTED_UNIT, BoundaryOutcome.BOUNDARY_AMBIGUOUS ->
                    compose.onNodeWithTag("map_continue").assertIsNotEnabled()
                BoundaryOutcome.COVERAGE_UNRESOLVED -> compose.onNodeWithTag("map_use_locality").assertIsDisplayed()
                BoundaryOutcome.UNAVAILABLE -> compose.onNodeWithTag("map_retry").assertIsDisplayed()
                BoundaryOutcome.INVALID_CONTEXT -> compose.onNodeWithTag("map_choose_area").assertIsDisplayed()
                else -> error("unexpected fixture")
            }
        }
    }
    @Test fun offlineLocalityAndMapUnavailableActionsFitShortScreenAtLargeFont() {
        val vm = model(online = false); var draft: DestinationDraft? = null
        show(vm, { draft = it }, short = true)
        compose.runOnIdle { open(vm) }
        compose.onNodeWithTag("map_use_locality").assertIsDisplayed()
        compose.onNodeWithTag("map_content").performScrollToNode(hasText("You’re offline"))
        compose.onNodeWithText("You’re offline").assertIsDisplayed()
        compose.runOnIdle { vm.mapLoadFailed() }
        compose.onNodeWithTag("map_retry").assertIsDisplayed()
        compose.onNodeWithTag("map_use_locality").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(draft is DestinationDraft.Locality); assertNull(draft?.destination?.latitude) }
    }
    @Test fun optionalNameRemainsReachableWithKeyboardAndReturnsPointWithoutStartingJourney() {
        val vm = model(); var draft: DestinationDraft? = null; var starts = 0
        show(vm, { draft = it }, short = true)
        compose.runOnIdle { open(vm) }; waitInside(vm)
        compose.onNodeWithTag("map_continue").performClick()
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Naming)?.label is MapNamingLabel.Ready }
        compose.onNodeWithTag("map_content").performScrollToNode(hasTestTag("map_optional_name"))
        compose.onNodeWithTag("map_optional_name").performClick().performTextInput("  Grandma’s house  ")
        compose.onNodeWithTag("map_helper").assertTextEquals("Pin set. Add a name if it helps you recognize this place.")
        compose.onNodeWithTag("map_use_location").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("Grandma’s house, Offa", draft?.displayLabel)
            assertEquals(coordinate.latitude, draft?.destination?.latitude)
            compose.activity.setContent {
                JourneyStartScreen(JourneyUiState(isRestoring = false,
                    draftExpectedArrivalAt = System.currentTimeMillis() + 7_200_000), {}, {}, {}, { starts++ },
                    structuredDraft = draft, onChooseDestination = {})
            }
        }
        compose.onNodeWithTag("journey_primary_action").assertIsNotEnabled()
        assertEquals(0, starts)
    }
    @Test fun blankNameAndBackPreserveTheSelectedCoordinate() {
        val vm = model(); var chosen: DestinationDraft? = null; show(vm, { chosen = it })
        compose.runOnIdle { open(vm) }; waitInside(vm)
        compose.onNodeWithTag("map_continue").performClick()
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Naming)?.label is MapNamingLabel.Ready }
        compose.onNodeWithTag("map_back").performClick()
        compose.runOnIdle { assertEquals(coordinate, (vm.state.value as DestinationMapState.Positioning).camera!!.target) }
        compose.onNodeWithTag("map_continue").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Naming)?.label is MapNamingLabel.Ready }
        compose.onNodeWithTag("map_use_location").performClick()
        compose.runOnIdle { assertNull(chosen?.destination?.customPlaceName); assertEquals("Pinned location in Offa", chosen?.displayLabel) }
    }
    @Test fun activityRecreationRetainsNamingAndEnteredNameInViewModelMemory() {
        val factory = DestinationMapViewModelFactory(BundledAdminCatalogue(compose.activity).snapshot(), Fake(), true)
        lateinit var vm: DestinationMapViewModel
        var initialized = false
        fun showActivity(activity: ComponentActivity) {
            val retained = ViewModelProvider(activity, factory)[DestinationMapViewModel::class.java]
            if (initialized) assertSame(vm, retained)
            initialized = true; vm = retained
            activity.setContent { DestinationMapScreen(vm, {}, {}, {}, observeConnectivity = false,
                mapContent = { modifier, _ -> Box(modifier) }) }
        }
        compose.runOnIdle { showActivity(compose.activity); open(vm) }; waitInside(vm)
        compose.onNodeWithTag("map_continue").performClick()
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Naming)?.label is MapNamingLabel.Ready }
        compose.runOnIdle { vm.updateName("My house") }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { showActivity(it) }
        compose.runOnIdle {
            val naming = vm.state.value as DestinationMapState.Naming
            assertEquals("My house", naming.name); assertEquals(coordinate, naming.point.coordinate)
        }
        compose.onNodeWithTag("map_content").performScrollToNode(hasTestTag("map_optional_name"))
        compose.onNodeWithTag("map_optional_name").assertTextContains("My house")
    }
}
