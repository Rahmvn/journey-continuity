package com.journeycontinuity.app.ui.destination

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeycontinuity.app.data.catalogue.BundledAdminCatalogue
import com.journeycontinuity.app.ui.JourneyStartScreen
import com.journeycontinuity.app.ui.JourneyUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class DestinationPickerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fctCatalogueLabelsAndOfflineFallbackAppearWithoutNetwork() {
        var calls = 0
        val vm = DestinationPickerViewModel(
            BundledAdminCatalogue(compose.activity).snapshot(),
            DestinationSearchGateway { calls++; DestinationSearchResponse.NoResults },
            { true },
        )
        compose.setContent {
            DestinationPickerScreen(vm, {}, {}, {})
        }
        compose.onNodeWithTag("destination_region").performClick()
        compose.onNodeWithTag("destination_regions").performScrollToNode(hasTestTag("region_ng-fct"))
        compose.onNodeWithTag("region_ng-fct").performClick()
        compose.onNodeWithText("Area council").assertIsDisplayed()
        compose.onNodeWithTag("destination_local_unit").performClick()
        compose.onNodeWithTag("unit_ng-fct-abuja-municipal").performClick()
        compose.onNodeWithTag("destination_search_open").performClick()
        compose.onNodeWithTag("destination_query").performTextInput("hospital")
        compose.onNodeWithTag("destination_search_content").performScrollToNode(
            hasText("Place search needs an internet connection"))
        compose.onNodeWithText("Place search needs an internet connection").assertIsDisplayed()
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasTestTag("destination_locality"))
        compose.onNodeWithTag("destination_locality").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, calls) }
    }

    @Test fun verificationUnavailableOffersLocalityButNoMapAction() {
        val vm = DestinationPickerViewModel(
            BundledAdminCatalogue(compose.activity).snapshot(),
            DestinationSearchGateway {
                DestinationSearchResponse.Results(listOf(PlaceSearchResult(
                    "GEOAPIFY", "test-place", "Test hospital", "Abuja", 9.04, 7.50,
                    SearchResultType.PLACE, "Federal Capital Territory", "Municipal Area Council",
                    AdminTextMatch.MATCH, AdminTextMatch.MATCH,
                    SearchSuitability.POINT_CANDIDATE, BoundaryOutcome.UNAVAILABLE, null,
                    ProviderAttribution("OSM", "© OpenStreetMap", "ODbL", "https://osm.org"),
                )))
            }, { false },
        )
        compose.setContent { DestinationPickerScreen(vm, {}, {}, {}) }
        compose.runOnIdle {
            vm.selectRegion("ng-fct")
            vm.selectUnit("ng-fct-abuja-municipal")
            vm.showSearch()
            vm.updateQuery("hospital")
        }
        compose.waitUntil(5_000) {
            vm.state.value.searchState == DestinationSearchState.VERIFICATION_UNAVAILABLE
        }
        compose.onNodeWithTag("destination_search_content").performScrollToNode(
            hasText("We can’t verify exact places right now"))
        compose.onNodeWithText("We can’t verify exact places right now").assertIsDisplayed()
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasTestTag("destination_retry"))
        compose.onNodeWithTag("destination_retry").assertIsDisplayed()
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasTestTag("destination_locality"))
        compose.onNodeWithTag("destination_locality").assertIsDisplayed()
        compose.onNodeWithTag("destination_map_request").assertDoesNotExist()
        compose.onNodeWithTag("result_test-place").assertDoesNotExist()
    }

    @Test fun catalogueFiltersAndSelectedChecksUseCanonicalOfflineData() {
        var calls = 0
        val vm = DestinationPickerViewModel(BundledAdminCatalogue(compose.activity).snapshot(),
            DestinationSearchGateway { calls++; DestinationSearchResponse.NoResults }, { true })
        compose.setContent { DestinationPickerScreen(vm, {}, {}, {}) }
        compose.onNodeWithTag("destination_region").performClick()
        compose.onNodeWithTag("destination_region_filter").performTextInput("Kwara")
        compose.onNodeWithTag("region_ng-kwara").performClick()
        compose.onNodeWithText("Local government").assertIsDisplayed()
        compose.onNodeWithTag("destination_region").performClick()
        compose.onNodeWithTag("region_ng-kwara").assertIsSelected()
        compose.onNodeWithTag("destination_region_filter").performTextReplacement("Federal")
        compose.onNodeWithTag("region_ng-fct").performClick()
        compose.onNodeWithTag("destination_local_unit").performClick()
        compose.onNodeWithTag("destination_unit_filter").performTextInput("Municipal")
        compose.onNodeWithTag("unit_ng-fct-abuja-municipal").performClick()
        compose.onNodeWithTag("destination_local_unit").performClick()
        compose.onNodeWithTag("unit_ng-fct-abuja-municipal").assertIsSelected()
        compose.onNodeWithTag("destination_unit_filter").performTextReplacement("Bwari")
        compose.onNodeWithTag("unit_ng-fct-bwari").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("ng-fct-abuja-municipal", vm.state.value.unit?.id)
            assertEquals(0, calls)
        }
    }

    private fun result(suitability: SearchSuitability, type: SearchResultType = SearchResultType.PLACE) =
        PlaceSearchResult("GEOAPIFY", "test-place", "Test hospital", "Abuja", 9.04, 7.50, type,
            "Federal Capital Territory", "Municipal Area Council", AdminTextMatch.MATCH, AdminTextMatch.MATCH,
            suitability, if (suitability == SearchSuitability.SELECTABLE_POINT) BoundaryOutcome.INSIDE else null,
            if (suitability == SearchSuitability.SELECTABLE_POINT) "alabarin-2026-10-03-v1" else null,
            ProviderAttribution("OSM", "© OpenStreetMap", "ODbL", "https://osm.org"))

    @Test fun resultsScrollAtShortHeightAndEmitGeneralOrCandidateMapRequestsOnly() {
        val candidate = result(SearchSuitability.NEEDS_MAP_PIN, SearchResultType.ROAD)
        val maps = mutableListOf<MapRefinementRequest>()
        var exactSelections = 0
        val vm = DestinationPickerViewModel(BundledAdminCatalogue(compose.activity).snapshot(),
            DestinationSearchGateway { DestinationSearchResponse.Results(listOf(candidate)) }, { false })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                // Models a narrow, short post-IME viewport without taking ownership of system insets.
                Box(Modifier.size(320.dp, 240.dp)) {
                    DestinationPickerScreen(vm, {}, { exactSelections++ }, { maps += it })
                }
            }
        }
        compose.runOnIdle {
            vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal")
            vm.showSearch(); vm.updateQuery("hospital")
        }
        compose.waitUntil(5_000) { vm.state.value.searchState == DestinationSearchState.RESULTS }
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasTestTag("result_test-place"))
        compose.onNodeWithTag("result_test-place").performClick()
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasTestTag("destination_map_request"))
        compose.onNodeWithTag("destination_map_request").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, exactSelections)
            assertEquals(candidate, maps[0].candidate)
            assertNull(maps[1].candidate)
            assertEquals("ng-fct-abuja-municipal", maps[1].localUnit.id)
        }
    }

    @Test fun activityRecreationRetainsOpenPageAndUnsubmittedExactAndLocalityDrafts() {
        val catalogue = BundledAdminCatalogue(compose.activity).snapshot()
        val factory = DestinationPickerViewModelFactory(catalogue,
            DestinationSearchGateway { DestinationSearchResponse.NoResults }, { false })
        lateinit var vm: DestinationPickerViewModel
        var initialized = false
        var starts = 0
        fun show(activity: ComponentActivity) {
            val retained = ViewModelProvider(activity, factory)[DestinationPickerViewModel::class.java]
            if (initialized) assertSame(vm, retained)
            vm = retained
            initialized = true
            activity.setContent {
                val state = vm.state.collectAsStateWithLifecycle().value
                if (state.isOpen) DestinationPickerScreen(vm, vm::close, vm::acceptDraft, {})
                else JourneyStartScreen(JourneyUiState(isRestoring = false, draftDestination = "Legacy text",
                    draftExpectedArrivalAt = System.currentTimeMillis() + 7_200_000), {}, {}, {}, { starts++ },
                    structuredDraft = state.selectedDraft, onChooseDestination = vm::open)
            }
        }
        compose.runOnIdle {
            show(compose.activity)
            vm.open(); vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal"); vm.showSearch()
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { show(it) }
        compose.runOnIdle {
            assertEquals(true, vm.state.value.isOpen)
            assertEquals(DestinationPickerPage.SEARCH, vm.state.value.page)
        }
        val context = vm.state.value
        val region = requireNotNull(context.region)
        val unit = requireNotNull(context.unit)
        val drafts = listOf(localityDraft(region, unit, catalogue.metadata.version),
            exactPointDraft(region, unit, catalogue.metadata.version,
                result(SearchSuitability.SELECTABLE_POINT)))
        for (draft in drafts) {
            compose.runOnIdle { vm.acceptDraft(draft) }
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { show(it) }
            compose.onNodeWithText(draft.displayLabel).assertIsDisplayed()
            compose.onNodeWithTag("journey_primary_action").assertIsNotEnabled()
            compose.runOnIdle {
                assertSame(draft, vm.state.value.selectedDraft)
                assertEquals(0, starts)
            }
        }
    }

    @Test fun shortViewportKeepsNoResultsRetryAndOfflineLocalityReachable() {
        var unavailable = false
        var offline = false
        var chosen: DestinationDraft? = null
        var mapRequests = 0
        val vm = DestinationPickerViewModel(BundledAdminCatalogue(compose.activity).snapshot(),
            DestinationSearchGateway {
                if (unavailable) throw DestinationSearchException(DestinationSearchFailure.UNAVAILABLE)
                DestinationSearchResponse.NoResults
            }, { offline })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.size(360.dp, 240.dp)) {
                    DestinationPickerScreen(vm, {}, { chosen = it }, { mapRequests++ })
                }
            }
        }
        compose.runOnIdle {
            vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal")
            vm.showSearch(); vm.updateQuery("hospital")
        }
        compose.waitUntil(5_000) { vm.state.value.searchState == DestinationSearchState.NO_RESULTS }
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasText("No places found"))
        compose.onNodeWithText("No places found").assertIsDisplayed()
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasTestTag("destination_map_request"))
        compose.onNodeWithTag("destination_map_request").performClick()
        compose.runOnIdle { unavailable = true; vm.retry() }
        compose.waitUntil(5_000) { vm.state.value.searchState == DestinationSearchState.SEARCH_UNAVAILABLE }
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasTestTag("destination_retry"))
        compose.onNodeWithTag("destination_retry").assertIsDisplayed()
        compose.runOnIdle { offline = true }
        compose.onNodeWithTag("destination_retry").performClick()
        compose.onNodeWithTag("destination_search_content").performScrollToNode(hasTestTag("destination_locality"))
        compose.onNodeWithTag("destination_locality").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, mapRequests)
            assertEquals("ng-fct-abuja-municipal", chosen?.destination?.localUnitId)
        }
    }
}
