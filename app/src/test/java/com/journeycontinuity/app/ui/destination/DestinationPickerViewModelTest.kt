package com.journeycontinuity.app.ui.destination

import com.journeycontinuity.app.domain.AdminCatalogue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DestinationPickerViewModelTest {
    private val catalogue by lazy {
        val file = listOf(File("src/main/assets/catalogue/ng_admin_v2.json"),
            File("app/src/main/assets/catalogue/ng_admin_v2.json")).first { it.isFile }
        AdminCatalogue.parse(file.readBytes())
    }

    private fun pickerTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    private fun point(suitability: SearchSuitability, boundary: BoundaryOutcome? = null,
        label: String = "Test hospital") = PlaceSearchResult(
        "GEOAPIFY", "place-1", label, "Abuja", 9.04, 7.50, SearchResultType.PLACE,
        "Federal Capital Territory", "Municipal Area Council", AdminTextMatch.MATCH,
        AdminTextMatch.MATCH, suitability, boundary,
        if (boundary == BoundaryOutcome.INSIDE) "alabarin-2026-10-03-v1" else null,
        ProviderAttribution("OpenStreetMap", "© OpenStreetMap contributors", "ODbL", "https://www.openstreetmap.org"),
    )

    @Test fun localityLabelUsesCanonicalUnitAndRegionAndCarriesType() = pickerTest {
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            DestinationSearchResponse.NoResults
        }, { false })
        listOf("ng-kwara" to "ng-kwara-offa", "ng-fct" to "ng-fct-abuja-municipal").forEach { (regionId, unitId) ->
            vm.selectRegion(regionId); vm.selectUnit(unitId)
            val draft = vm.selectLocality()!!.value
            val region = catalogue.region(regionId)!!
            val unit = catalogue.localUnit(unitId)!!
            assertEquals("${unit.canonicalName}, ${region.canonicalName}", draft.displayLabel)
            assertEquals(unit.type, draft.destination.localUnitType)
            assertNull(draft.destination.latitude)
            assertNull(draft.destination.boundaryDatasetId)
            assertNull(draft.destination.providerAttribution)
            com.journeycontinuity.app.domain.StructuredDestinationValidator.validate(draft.destination, catalogue)
        }
    }

    @Test fun catalogueDrivesRegionListFctTerminologyAndCanonicalChildren() = pickerTest {
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            DestinationSearchResponse.NoResults
        }, { false })
        assertEquals(37, vm.state.value.regions.size)
        vm.selectRegion("ng-kwara")
        assertEquals("Local government", vm.state.value.unitHeading)
        assertEquals(16, vm.state.value.units.size)
        vm.selectRegion("ng-fct")
        assertEquals("Area council", vm.state.value.unitHeading)
        assertEquals(setOf("Abaji", "Abuja Municipal Area Council", "Bwari", "Gwagwalada", "Kuje", "Kwali"),
            vm.state.value.units.map { it.canonicalName }.toSet())
    }

    @Test fun changingParentOrChildClearsInvalidSelectionAndBackDoesNotInventOne() = pickerTest {
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            DestinationSearchResponse.NoResults
        }, { false })
        vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa")
        vm.showSearch(); vm.updateQuery("Market")
        vm.selectRegion("ng-fct")
        assertNull(vm.state.value.unit)
        assertEquals("", vm.state.value.query)
        assertTrue(vm.state.value.results.isEmpty())
        vm.showUnits(); assertTrue(vm.back())
        assertEquals("ng-fct", vm.state.value.region?.id)
        vm.selectUnit("ng-fct-abaji")
        assertEquals("ng-fct-abaji", vm.state.value.unit?.id)
    }

    @Test fun shortQueryDoesNotSearchAndDebounceUsesLatestQueryOnly() = pickerTest {
        val calls = mutableListOf<String>()
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            calls += it.query; DestinationSearchResponse.NoResults
        }, { false })
        vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa"); vm.showSearch()
        vm.updateQuery("Of"); advanceTimeBy(500); runCurrent()
        assertTrue(calls.isEmpty())
        vm.updateQuery("Offa"); advanceTimeBy(200)
        vm.updateQuery("Offa road"); advanceTimeBy(399); runCurrent()
        assertTrue(calls.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("Offa road"), calls)
        assertTrue(vm.back())
        vm.showSearch(); advanceTimeBy(500); runCurrent()
        assertEquals(listOf("Offa road"), calls)
    }

    @Test fun cancelledOldSearchCannotOverwriteNewResults() = pickerTest {
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway { request ->
            if (request.query == "old query") delay(2_000)
            DestinationSearchResponse.Results(listOf(point(SearchSuitability.SELECTABLE_POINT,
                BoundaryOutcome.INSIDE, request.query)))
        }, { false })
        vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal"); vm.showSearch()
        vm.updateQuery("old query"); advanceTimeBy(400); runCurrent()
        vm.updateQuery("new query"); advanceTimeBy(400); runCurrent()
        assertEquals("new query", vm.state.value.results.single().primaryLabel)
        advanceTimeBy(2_000); runCurrent()
        assertEquals("new query", vm.state.value.results.single().primaryLabel)
    }

    @Test fun knownOfflineSkipsGatewayButTimeoutIsSearchUnavailable() = pickerTest {
        var offline = true
        var calls = 0
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            calls++
            throw DestinationSearchException(DestinationSearchFailure.UNAVAILABLE)
        }, { offline })
        vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa"); vm.showSearch()
        vm.updateQuery("hospital")
        assertEquals(DestinationSearchState.OFFLINE, vm.state.value.searchState)
        assertEquals(0, calls)
        offline = false; vm.retry(); advanceTimeBy(400); runCurrent()
        assertEquals(1, calls)
        assertEquals(DestinationSearchState.SEARCH_UNAVAILABLE, vm.state.value.searchState)
    }

    @Test fun emptyAndUnusableResultsAreTruthfulNoResults() = pickerTest {
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            DestinationSearchResponse.Results(listOf(point(SearchSuitability.NOT_USABLE)))
        }, { false })
        vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal"); vm.showSearch()
        vm.updateQuery("unknown"); advanceTimeBy(400); runCurrent()
        assertEquals(DestinationSearchState.NO_RESULTS, vm.state.value.searchState)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test fun verifiedPointReturnsDraftRoadReturnsMapAndUnverifiedIsNeverExact() = pickerTest {
        val inside = point(SearchSuitability.SELECTABLE_POINT, BoundaryOutcome.INSIDE)
        val road = point(SearchSuitability.NEEDS_MAP_PIN, BoundaryOutcome.BOUNDARY_AMBIGUOUS)
        val candidate = point(SearchSuitability.POINT_CANDIDATE, BoundaryOutcome.UNAVAILABLE)
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            DestinationSearchResponse.Results(listOf(inside, road, candidate))
        }, { false })
        vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal"); vm.showSearch()
        vm.updateQuery("hospital"); advanceTimeBy(400); runCurrent()
        assertEquals(2, vm.state.value.results.size)
        val draft = (vm.selectResult(inside) as DestinationPickerAction.Draft).value
            as DestinationDraft.ExactPoint
        assertEquals("ng-fct-abuja-municipal", draft.destination.localUnitId)
        assertEquals("alabarin-2026-10-03-v1", draft.boundaryDatasetVersion)
        assertTrue(vm.selectResult(road) is DestinationPickerAction.Map)
        assertNull(vm.selectResult(candidate))
        assertEquals("ng-fct", vm.selectLocality()!!.value.destination.stateId)
        assertEquals(catalogue.metadata.version, vm.selectLocality()!!.value.destination.adminDatasetVersion)
    }

    @Test fun verifierUnavailableIsDistinctAndOffersNoAutomaticMapSelection() = pickerTest {
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            DestinationSearchResponse.Results(listOf(point(SearchSuitability.POINT_CANDIDATE,
                BoundaryOutcome.UNAVAILABLE)))
        }, { false })
        vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal"); vm.showSearch()
        vm.updateQuery("hospital"); advanceTimeBy(400); runCurrent()
        assertEquals(DestinationSearchState.VERIFICATION_UNAVAILABLE, vm.state.value.searchState)
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals("Abuja Municipal Area Council, Federal Capital Territory", vm.selectLocality()!!.value.displayLabel)
        assertNull(vm.requestMap())
    }

    @Test fun offlineDuringDebounceSkipsDispatch() = pickerTest {
        var offline = false
        var calls = 0
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            calls++; DestinationSearchResponse.NoResults
        }, { offline })
        vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa"); vm.showSearch()
        vm.updateQuery("hospital"); advanceTimeBy(200); runCurrent()
        offline = true
        advanceTimeBy(200); runCurrent()
        assertEquals(0, calls)
        assertEquals(DestinationSearchState.OFFLINE, vm.state.value.searchState)
    }

    @Test fun requestQueryIsTrimmedBoundedByCodePointsAndNeverContainsControls() = pickerTest {
        val calls = mutableListOf<String>()
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            calls += it.query; DestinationSearchResponse.NoResults
        }, { false })
        vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa"); vm.showSearch()
        listOf("a".repeat(121), "hos\npital", "hospital\t", "hospital\u007f").forEach {
            vm.updateQuery(it); vm.retry(); advanceTimeBy(500); runCurrent()
            assertEquals(DestinationSearchState.IDLE, vm.state.value.searchState)
            assertTrue(calls.isEmpty())
        }
        vm.updateQuery("  hospital  "); advanceTimeBy(400); runCurrent()
        assertEquals(listOf("hospital"), calls)
        vm.updateQuery(" hospital "); advanceTimeBy(500); runCurrent()
        assertEquals(1, calls.size)
        val unicodeQuery = "\uD83D\uDE00".repeat(120)
        vm.updateQuery(unicodeQuery); advanceTimeBy(400); runCurrent()
        assertEquals(unicodeQuery, calls.last())
        vm.updateQuery(unicodeQuery + "a"); vm.retry(); advanceTimeBy(500); runCurrent()
        assertEquals(2, calls.size)
        assertEquals(DestinationQueryIssue.TOO_LONG, vm.state.value.queryIssue)
    }

    @Test fun retryAndCancellationResistantLateCompletionCannotOverwriteLatestResult() = pickerTest {
        val pending = mutableListOf<Continuation<DestinationSearchResponse>>()
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            // Deliberately ignores coroutine cancellation until the transport completes.
            suspendCoroutine { pending += it }
        }, { false })
        vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal"); vm.showSearch()
        vm.updateQuery("hospital"); advanceTimeBy(400); runCurrent()
        vm.retry(); advanceTimeBy(400); runCurrent()
        assertEquals(2, pending.size)
        pending[1].resume(DestinationSearchResponse.Results(listOf(
            point(SearchSuitability.SELECTABLE_POINT, BoundaryOutcome.INSIDE, "Latest hospital"))))
        runCurrent()
        pending[0].resume(DestinationSearchResponse.Results(listOf(
            point(SearchSuitability.SELECTABLE_POINT, BoundaryOutcome.INSIDE, "Stale hospital"))))
        runCurrent()
        assertEquals("Latest hospital", vm.state.value.results.single().primaryLabel)
    }

    @Test fun cancellationResistantLateErrorCannotOverwriteNewQuery() = pickerTest {
        val pending = mutableListOf<Continuation<DestinationSearchResponse>>()
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            suspendCoroutine { pending += it }
        }, { false })
        vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa"); vm.showSearch()
        vm.updateQuery("old hospital"); advanceTimeBy(400); runCurrent()
        vm.updateQuery("new hospital"); advanceTimeBy(400); runCurrent()
        pending[1].resume(DestinationSearchResponse.NoResults); runCurrent()
        pending[0].resumeWithException(DestinationSearchException(DestinationSearchFailure.UNAVAILABLE))
        runCurrent()
        assertEquals("new hospital", vm.state.value.query)
        assertEquals(DestinationSearchState.NO_RESULTS, vm.state.value.searchState)
    }

    @Test fun sameRegionUnitChangeClearsDraftAndRejectsOldRow() = pickerTest {
        val row = point(SearchSuitability.SELECTABLE_POINT, BoundaryOutcome.INSIDE)
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            DestinationSearchResponse.Results(listOf(row))
        }, { false })
        vm.open(); vm.selectRegion("ng-kwara"); vm.selectUnit("ng-kwara-offa"); vm.showSearch()
        vm.updateQuery("hospital"); advanceTimeBy(400); runCurrent()
        vm.acceptDraft((vm.selectResult(row) as DestinationPickerAction.Draft).value)
        assertFalse(vm.state.value.isOpen)
        assertTrue(vm.state.value.selectedDraft is DestinationDraft.ExactPoint)
        vm.open(); vm.selectUnit("ng-kwara-oyun")
        assertNull(vm.state.value.selectedDraft)
        assertEquals("", vm.state.value.query)
        assertTrue(vm.state.value.results.isEmpty())
        assertNull(vm.selectResult(row))
    }

    @Test fun catalogueFiltersAreLocalAndDoNotChangeSelectedIdentity() = pickerTest {
        var calls = 0
        val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
            calls++; DestinationSearchResponse.NoResults
        }, { true })
        vm.updateRegionFilter("FCT")
        assertEquals(listOf("ng-fct"), vm.state.value.filteredRegions.map { it.id })
        vm.updateRegionFilter("federal")
        assertEquals(listOf("ng-fct"), vm.state.value.filteredRegions.map { it.id })
        vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal")
        vm.updateUnitFilter("municipal")
        assertEquals(listOf("ng-fct-abuja-municipal"), vm.state.value.filteredUnits.map { it.id })
        vm.updateUnitFilter("AMAC")
        assertEquals(listOf("ng-fct-abuja-municipal"), vm.state.value.filteredUnits.map { it.id })
        vm.updateUnitFilter("bwari")
        assertEquals("ng-fct-abuja-municipal", vm.state.value.unit?.id)
        assertEquals(0, calls)
    }

    @Test fun heterogeneousResultsKeepVerifiedRowsAndNeverPromoteProviderMatches() = pickerTest {
        val inside = point(SearchSuitability.SELECTABLE_POINT, BoundaryOutcome.INSIDE)
        val map = point(SearchSuitability.NEEDS_MAP_PIN, BoundaryOutcome.BOUNDARY_AMBIGUOUS)
        val candidate = point(SearchSuitability.POINT_CANDIDATE, BoundaryOutcome.UNAVAILABLE)
        val unusable = point(SearchSuitability.NOT_USABLE)
        val combinations = listOf(
            listOf(inside, unusable) to listOf(inside),
            listOf(inside, map) to listOf(inside, map),
            listOf(inside, candidate) to listOf(inside),
            listOf(map, unusable) to listOf(map),
        )
        for ((response, expected) in combinations) {
            val vm = DestinationPickerViewModel(catalogue, DestinationSearchGateway {
                DestinationSearchResponse.Results(response)
            }, { false })
            vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal"); vm.showSearch()
            vm.updateQuery("hospital"); advanceTimeBy(400); runCurrent()
            assertEquals(expected, vm.state.value.results)
            assertNull(vm.selectResult(candidate))
            val generalMap = vm.requestMap()!!.value
            assertNull(generalMap.candidate)
            assertEquals("ng-fct-abuja-municipal", generalMap.localUnit.id)
            if (map in expected) assertEquals(map, (vm.selectResult(map) as DestinationPickerAction.Map).value.candidate)
        }
    }

    @Test fun recreatedOwnerRetainsMemoryDraftButNewStoreDiscardsIt() = pickerTest {
        val store = ViewModelStore()
        fun owner() = object : ViewModelStoreOwner { override val viewModelStore = store }
        val factory = DestinationPickerViewModelFactory(catalogue,
            DestinationSearchGateway { DestinationSearchResponse.NoResults }, { false })
        val vm = ViewModelProvider(owner(), factory)[DestinationPickerViewModel::class.java]
        vm.open(); vm.selectRegion("ng-fct"); vm.selectUnit("ng-fct-abuja-municipal")
        vm.acceptDraft(vm.selectLocality()!!.value)
        vm.open(); vm.showUnits()
        val recreated = ViewModelProvider(owner(), factory)[DestinationPickerViewModel::class.java]
        assertSame(vm, recreated)
        assertEquals(true, recreated.state.value.isOpen)
        assertEquals(DestinationPickerPage.LOCAL_UNITS, recreated.state.value.page)
        assertEquals("Abuja Municipal Area Council, Federal Capital Territory", recreated.state.value.selectedDraft?.displayLabel)
        store.clear()
        val fresh = ViewModelProvider(owner(), factory)[DestinationPickerViewModel::class.java]
        assertNull(fresh.state.value.selectedDraft)
        assertFalse(fresh.state.value.isOpen)
        store.clear()
    }
}
