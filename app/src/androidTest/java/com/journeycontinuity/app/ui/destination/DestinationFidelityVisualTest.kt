package com.journeycontinuity.app.ui.destination

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.journeycontinuity.app.data.catalogue.BundledAdminCatalogue
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Isolated current-build Redmi renders. No account, Journey admission or hosted request. */
class DestinationFidelityVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null

    private fun show(vm: DestinationPickerViewModel, width: Int, height: Int,
        fontScale: Float = 1f) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n $packageName/androidx.activity.ComponentActivity").use { }
        compose.waitUntil("Destination host did not resume", 10_000) {
            compose.runOnUiThread {
                host = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull() as? ComponentActivity
            }
            host != null
        }
        compose.runOnUiThread {
            host!!.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.size(width.dp, height.dp).testTag("destinationFrame")) {
                        DestinationPickerScreen(vm, {}, vm::acceptDraft, {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun closeHost() { compose.runOnUiThread { host?.finish() } }

    @Test fun setupSearchErrorAndSelectedStatesMatchApprovedFrameStructure() {
        val vm = DestinationPickerViewModel(
            BundledAdminCatalogue(InstrumentationRegistry.getInstrumentation().targetContext).snapshot(),
            DestinationSearchGateway { DestinationSearchResponse.NoResults }, { false })
        show(vm, 390, 844)
        capture("setup-390")
        compose.runOnUiThread { vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa") }
        capture("setup-area-390")
        compose.runOnUiThread { vm.showSearch(); vm.updateQuery("Ogele family house") }
        compose.waitUntil("No-results state did not settle", 5_000) {
            vm.state.value.searchState == DestinationSearchState.NO_RESULTS
        }
        capture("no-results-390")
        compose.runOnUiThread { vm.back(); vm.acceptDraft(vm.selectLocality()!!.value) }
        capture("change-selected-390")
        assertEquals("ng-kwara-offa", vm.state.value.selectedDraft?.destination?.localUnitId)
        compose.runOnUiThread { vm.selectRegion("ng-fct") }
        assertNull(vm.state.value.selectedDraft)
        capture("setup-changed-area-390")
    }

    @Test fun narrowAndShortDestinationLayoutsRemainReachable() {
        val vm = DestinationPickerViewModel(
            BundledAdminCatalogue(InstrumentationRegistry.getInstrumentation().targetContext).snapshot(),
            DestinationSearchGateway { DestinationSearchResponse.NoResults }, { true })
        show(vm, 320, 480)
        capture("setup-320x480")
        compose.runOnUiThread { vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa") }
        compose.onNodeWithTag("destination_map_request").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread {
            vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal")
            vm.showSearch(); vm.updateQuery("hospital")
        }
        compose.waitUntil("Offline state did not settle", 5_000) {
            vm.state.value.searchState == DestinationSearchState.OFFLINE
        }
        capture("offline-320x480")
        compose.onNodeWithTag("destination_search_content")
            .performScrollToNode(hasTestTag("destination_locality"))
        compose.onNodeWithTag("destination_locality").assertIsDisplayed()
    }

    @Test fun largerTextAt360KeepsSelectionAndMapActionReachable() {
        val vm = DestinationPickerViewModel(
            BundledAdminCatalogue(InstrumentationRegistry.getInstrumentation().targetContext).snapshot(),
            DestinationSearchGateway { DestinationSearchResponse.NoResults }, { false })
        show(vm, 360, 640, 1.5f)
        capture("setup-360x640-font1.5")
        compose.runOnUiThread { vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa") }
        compose.onNodeWithTag("destination_map_request").performScrollTo().assertIsDisplayed()
        capture("setup-area-360x640-font1.5")
    }

    @Test fun focusedSearchUsesReadableHintAndEnteredValue() {
        val vm = DestinationPickerViewModel(
            BundledAdminCatalogue(InstrumentationRegistry.getInstrumentation().targetContext).snapshot(),
            DestinationSearchGateway { DestinationSearchResponse.NoResults }, { false })
        show(vm, 390, 844)
        compose.runOnUiThread {
            vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa"); vm.showSearch()
        }
        compose.onNodeWithTag("destination_query").performClick()
        capture("search-focused-empty-390")
        compose.onNodeWithTag("destination_query").performTextInput("Ogele family house")
        capture("search-focused-filled-390")
    }

    @Test fun verifiedResultReturnsAsConfirmedDestination() {
        val catalogue = BundledAdminCatalogue(InstrumentationRegistry.getInstrumentation().targetContext).snapshot()
        val result = PlaceSearchResult("GEOAPIFY", "offa-hospital", "Offa General Hospital",
            "Offa, Kwara", 8.1560008, 4.7227435, SearchResultType.PLACE,
            "Kwara", "Offa", AdminTextMatch.MATCH, AdminTextMatch.MATCH,
            SearchSuitability.SELECTABLE_POINT, BoundaryOutcome.INSIDE,
            catalogue.metadata.version,
            ProviderAttribution("OSM", "© OpenStreetMap", "ODbL", "https://osm.org"))
        val vm = DestinationPickerViewModel(catalogue,
            DestinationSearchGateway { DestinationSearchResponse.Results(listOf(result)) }, { false })
        show(vm, 390, 844)
        compose.runOnUiThread {
            vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa")
            vm.showSearch(); vm.updateQuery("Offa General Hospital")
        }
        compose.waitUntil("Results did not settle", 5_000) {
            vm.state.value.searchState == DestinationSearchState.RESULTS
        }
        capture("search-result-390")
        compose.onNodeWithTag("result_offa-hospital").performClick()
        compose.runOnUiThread { vm.open() }
        compose.onNodeWithTag("destination_search_open").assertIsDisplayed()
        assertEquals("Offa General Hospital, Offa", vm.state.value.selectedDraft?.displayLabel)
        capture("setup-confirmed-390")
    }

    @Test fun searchUnavailableKeepsAreaAndShowsRecoveryActions() {
        val vm = DestinationPickerViewModel(
            BundledAdminCatalogue(InstrumentationRegistry.getInstrumentation().targetContext).snapshot(),
            DestinationSearchGateway { throw DestinationSearchException(DestinationSearchFailure.UNAVAILABLE) },
            { false })
        show(vm, 390, 844)
        compose.runOnUiThread {
            vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa")
            vm.showSearch(); vm.updateQuery("Summit University")
        }
        compose.waitUntil("Search error did not settle", 5_000) {
            vm.state.value.searchState == DestinationSearchState.SEARCH_UNAVAILABLE
        }
        compose.onNodeWithTag("destination_retry").assertIsDisplayed()
        compose.onNodeWithTag("destination_map_request").assertIsDisplayed()
        assertEquals("ng-kwara-offa", vm.state.value.unit?.id)
        capture("search-unavailable-390")
    }

    @Test fun mapChromeAndNamingRemainReachableOnRedmi() {
        val catalogue = BundledAdminCatalogue(InstrumentationRegistry.getInstrumentation().targetContext).snapshot()
        val picker = DestinationPickerViewModel(catalogue,
            DestinationSearchGateway { DestinationSearchResponse.NoResults }, { false })
        show(picker, 390, 844)
        val coordinate = MapCoordinate(8.1560008, 4.7227435)
        val fake = object : DestinationMapGateway {
            override suspend fun verify(request: MapPointRequest) = CoordinateVerification(
                BoundaryOutcome.INSIDE, OPERATIONAL_BOUNDARY_DATASET, catalogue.metadata.version)
            override suspend fun viewport(regionId: String, localUnitId: String) =
                LocalUnitViewport.Available(4.6, 8.0, 4.8, 8.3,
                    OPERATIONAL_BOUNDARY_DATASET, catalogue.metadata.version)
            override suspend fun label(request: MapPointRequest) = MapLabelResponse.Verified(
                MapLabelStatus.NO_MEANINGFUL_LABEL, catalogue.metadata.version, "Offa")
        }
        val vm = DestinationMapViewModel(catalogue, fake, fake, fake, true)
        compose.runOnUiThread {
            vm.open(MapRefinementRequest(catalogue.region("ng-kwara")!!,
                catalogue.localUnit("ng-kwara-offa")!!,
                PlaceSearchResult("GEOAPIFY", "seed", "Olofa Way", null,
                    coordinate.latitude, coordinate.longitude, SearchResultType.ROAD,
                    "Kwara", "Offa", AdminTextMatch.MATCH, AdminTextMatch.MATCH,
                    SearchSuitability.NEEDS_MAP_PIN, null, null, null)))
            vm.cameraIdle(MapCamera(coordinate, 15.0))
            host!!.setContent {
                Box(Modifier.size(390.dp, 844.dp).testTag("destinationFrame")) {
                    DestinationMapScreen(vm, {}, {}, {}, observeConnectivity = false,
                        mapContent = { modifier, _ -> Box(modifier.background(Color(0xFFF0F1F0))) })
                }
            }
        }
        compose.waitUntil("Map verification did not settle", 5_000) {
            (vm.state.value as? DestinationMapState.Positioning)?.phase is MapPinPhase.Inside
        }
        capture("map-verified-390")
        compose.onNodeWithTag("map_continue").performClick()
        compose.waitUntil("Map name did not settle", 5_000) {
            (vm.state.value as? DestinationMapState.Naming)?.label is MapNamingLabel.Ready
        }
        capture("map-name-empty-390")
        compose.onNodeWithTag("map_optional_name").performClick().performTextInput("Grandma’s house")
        capture("map-name-filled-390")
        compose.runOnUiThread {
            host!!.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.size(320.dp, 480.dp).testTag("destinationFrame")) {
                        DestinationMapScreen(vm, {}, {}, {}, observeConnectivity = false,
                            mapContent = { modifier, _ -> Box(modifier.background(Color(0xFFF0F1F0))) })
                    }
                }
            }
        }
        compose.onNodeWithTag("map_use_location").assertIsDisplayed()
        compose.onNodeWithTag("map_content").performScrollToNode(hasTestTag("map_optional_name"))
        compose.onNodeWithTag("map_optional_name").assertIsDisplayed()
        capture("map-name-320x480-font1.5")
        compose.runOnUiThread {
            vm.back(); vm.mapLoadFailed()
            host!!.setContent {
                Box(Modifier.size(390.dp, 844.dp).testTag("destinationFrame")) {
                    DestinationMapScreen(vm, {}, {}, {}, observeConnectivity = false,
                        mapContent = { modifier, _ -> Box(modifier.background(Color(0xFFF0F1F0))) })
                }
            }
        }
        compose.onNodeWithTag("map_retry").assertIsDisplayed()
        compose.onNodeWithTag("map_use_locality").assertIsDisplayed()
        capture("map-unavailable-390")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "destination-fidelity")
        check(output.mkdirs() || output.isDirectory)
        val bitmap = compose.onNodeWithTag("destinationFrame").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
