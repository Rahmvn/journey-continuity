package com.journeycontinuity.app.ui.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.domain.AdminCatalogue
import com.journeycontinuity.app.domain.AdminLocalUnit
import com.journeycontinuity.app.domain.AdminRegion
import com.journeycontinuity.app.domain.AdminRegionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class DestinationPickerPage { SETUP, REGIONS, LOCAL_UNITS, SEARCH }
enum class DestinationSearchState {
    IDLE, LOADING, RESULTS, NO_RESULTS, SEARCH_UNAVAILABLE, OFFLINE, VERIFICATION_UNAVAILABLE,
}

data class DestinationPickerState(
    val isOpen: Boolean = false,
    // Memory only: retained across configuration changes, never saved to disk or SavedStateHandle.
    val selectedDraft: DestinationDraft? = null,
    val page: DestinationPickerPage = DestinationPickerPage.SETUP,
    val regions: List<AdminRegion>,
    val units: List<AdminLocalUnit> = emptyList(),
    val region: AdminRegion? = null,
    val unit: AdminLocalUnit? = null,
    val regionFilter: String = "",
    val unitFilter: String = "",
    val query: String = "",
    val queryIssue: DestinationQueryIssue? = DestinationQueryIssue.TOO_SHORT,
    val searchState: DestinationSearchState = DestinationSearchState.IDLE,
    val results: List<PlaceSearchResult> = emptyList(),
) {
    val unitHeading: String get() = if (region?.type == AdminRegionType.FCT) "Area council"
        else "Local government"
    val filteredRegions: List<AdminRegion> get() = regions.filter {
        it.canonicalName.contains(regionFilter.trim(), ignoreCase = true) ||
            (it.type == AdminRegionType.FCT && it.type.name.contains(regionFilter.trim(), ignoreCase = true))
    }
    val filteredUnits: List<AdminLocalUnit> get() = units.filter {
        it.canonicalName.contains(unitFilter.trim(), ignoreCase = true) ||
            it.aliases.any { alias -> alias.contains(unitFilter.trim(), ignoreCase = true) }
    }
}

sealed interface DestinationPickerAction {
    data class Draft(val value: DestinationDraft) : DestinationPickerAction
    data class Map(val value: MapRefinementRequest) : DestinationPickerAction
}

/** A draft-only state machine. No method here can create or update a Journey. */
class DestinationPickerViewModel(
    private val catalogue: AdminCatalogue,
    private val gateway: DestinationSearchGateway,
    private val knownOffline: () -> Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DestinationPickerState(regions = catalogue.regions))
    val state: StateFlow<DestinationPickerState> = mutableState
    private var searchJob: Job? = null
    private var searchKey: Triple<String, String, String>? = null
    private var generation = 0L

    fun open() { mutableState.value = mutableState.value.copy(isOpen = true, page = DestinationPickerPage.SETUP) }

    fun close() {
        if (searchJob?.isActive == true) {
            cancelSearch()
            mutableState.value = mutableState.value.copy(results = emptyList(), searchState = DestinationSearchState.IDLE)
        }
        mutableState.value = mutableState.value.copy(isOpen = false)
    }

    /** Clear only after durable admission succeeds; failed prerequisites retain the selection. */
    fun clearAdmittedDraft() {
        cancelSearch()
        mutableState.value = DestinationPickerState(regions = catalogue.regions)
    }

    fun acceptDraft(draft: DestinationDraft) {
        val current = mutableState.value
        if (draft.destination.stateId != current.region?.id ||
            draft.destination.localUnitId != current.unit?.id ||
            draft.destination.adminDatasetVersion != catalogue.metadata.version) return
        close()
        mutableState.value = mutableState.value.copy(selectedDraft = draft)
    }

    /** Returns false when the host should close the picker. */
    fun back(): Boolean {
        when (mutableState.value.page) {
            DestinationPickerPage.SEARCH -> {
                if (searchJob?.isActive == true) {
                    cancelSearch()
                    mutableState.value = mutableState.value.copy(page = DestinationPickerPage.SETUP,
                        results = emptyList(), searchState = DestinationSearchState.IDLE)
                } else {
                    mutableState.value = mutableState.value.copy(page = DestinationPickerPage.SETUP)
                }
            }
            DestinationPickerPage.LOCAL_UNITS, DestinationPickerPage.REGIONS ->
                mutableState.value = mutableState.value.copy(page = DestinationPickerPage.SETUP)
            DestinationPickerPage.SETUP -> return false
        }
        return true
    }

    fun showRegions() { mutableState.value = mutableState.value.copy(page = DestinationPickerPage.REGIONS) }
    fun updateRegionFilter(value: String) { mutableState.value = mutableState.value.copy(regionFilter = value) }
    fun updateUnitFilter(value: String) { mutableState.value = mutableState.value.copy(unitFilter = value) }
    fun showUnits() {
        if (mutableState.value.region != null) {
            mutableState.value = mutableState.value.copy(page = DestinationPickerPage.LOCAL_UNITS)
        }
    }

    fun selectRegion(id: String) {
        val region = catalogue.region(id) ?: return
        val previous = mutableState.value
        if (previous.region?.id == id) {
            mutableState.value = previous.copy(page = DestinationPickerPage.SETUP)
            return
        }
        cancelSearch()
        mutableState.value = previous.copy(page = DestinationPickerPage.SETUP, region = region,
            units = catalogue.localUnitsFor(id),
            unit = null, unitFilter = "", query = "", queryIssue = DestinationQueryIssue.TOO_SHORT,
            selectedDraft = null,
            results = emptyList(), searchState = DestinationSearchState.IDLE)
    }

    fun selectUnit(id: String) {
        val previous = mutableState.value
        val unit = previous.region?.let { region -> catalogue.localUnit(id)?.takeIf {
            it.parentRegionId == region.id
        } } ?: return
        if (previous.unit?.id == id) {
            mutableState.value = previous.copy(page = DestinationPickerPage.SETUP)
            return
        }
        cancelSearch()
        mutableState.value = previous.copy(page = DestinationPickerPage.SETUP, unit = unit,
            query = "", queryIssue = DestinationQueryIssue.TOO_SHORT, selectedDraft = null,
            results = emptyList(), searchState = DestinationSearchState.IDLE)
    }

    fun showSearch() {
        if (mutableState.value.unit == null) return
        mutableState.value = mutableState.value.copy(page = DestinationPickerPage.SEARCH)
        scheduleSearch()
    }

    fun updateQuery(value: String) {
        val previous = mutableState.value
        if (previous.query == value) return
        val validation = validateDestinationQuery(value)
        if (validation.requestQuery != null &&
            validation.requestQuery == validateDestinationQuery(previous.query).requestQuery) {
            mutableState.value = previous.copy(query = value, queryIssue = null)
            return
        }
        cancelSearch()
        mutableState.value = mutableState.value.copy(query = value, queryIssue = validation.issue, results = emptyList(),
            searchState = DestinationSearchState.IDLE)
        if (mutableState.value.page == DestinationPickerPage.SEARCH) scheduleSearch()
    }

    fun retry() { cancelSearch(); scheduleSearch() }

    fun selectResult(result: PlaceSearchResult): DestinationPickerAction? {
        val current = mutableState.value
        val region = current.region ?: return null
        val unit = current.unit ?: return null
        if (current.page != DestinationPickerPage.SEARCH ||
            current.searchState != DestinationSearchState.RESULTS ||
            current.results.none { it === result }) return null
        return when (result.suitability) {
            SearchSuitability.SELECTABLE_POINT -> DestinationPickerAction.Draft(
                exactPointDraft(region, unit, catalogue.metadata.version, result))
            SearchSuitability.NEEDS_MAP_PIN -> DestinationPickerAction.Map(
                MapRefinementRequest(region, unit, result))
            SearchSuitability.NOT_USABLE, SearchSuitability.POINT_CANDIDATE -> null
        }
    }

    fun selectLocality(): DestinationPickerAction.Draft? {
        val current = mutableState.value
        return DestinationPickerAction.Draft(localityDraft(current.region ?: return null,
            current.unit ?: return null, catalogue.metadata.version))
    }

    fun requestMap(): DestinationPickerAction.Map? {
        val current = mutableState.value
        if (current.searchState == DestinationSearchState.VERIFICATION_UNAVAILABLE) return null
        return DestinationPickerAction.Map(MapRefinementRequest(current.region ?: return null,
            current.unit ?: return null, null))
    }

    private fun cancelSearch() {
        generation++
        searchJob?.cancel()
        searchJob = null
        searchKey = null
    }

    private fun scheduleSearch() {
        val current = mutableState.value
        val region = current.region ?: return
        val unit = current.unit ?: return
        val query = validateDestinationQuery(current.query).requestQuery ?: return
        val key = Triple(query, region.id, unit.id)
        if (searchKey == key && current.searchState != DestinationSearchState.SEARCH_UNAVAILABLE &&
            current.searchState != DestinationSearchState.OFFLINE &&
            current.searchState != DestinationSearchState.VERIFICATION_UNAVAILABLE) return
        cancelSearch()
        if (knownOffline()) {
            mutableState.value = current.copy(searchState = DestinationSearchState.OFFLINE)
            return
        }
        searchKey = key
        val requestGeneration = generation
        mutableState.value = current.copy(searchState = DestinationSearchState.LOADING)
        searchJob = viewModelScope.launch {
            delay(400)
            try {
                if (requestGeneration != generation) return@launch
                if (knownOffline()) {
                    mutableState.value = mutableState.value.copy(searchState = DestinationSearchState.OFFLINE,
                        results = emptyList())
                    return@launch
                }
                val response = gateway.autocomplete(DestinationSearchRequest(key.first, key.second, key.third))
                if (requestGeneration != generation) return@launch
                mutableState.value = when (response) {
                    DestinationSearchResponse.NoResults -> mutableState.value.copy(
                        searchState = DestinationSearchState.NO_RESULTS, results = emptyList())
                    is DestinationSearchResponse.Results -> {
                        val presentable = response.places.filter { it.suitability == SearchSuitability.SELECTABLE_POINT ||
                            it.suitability == SearchSuitability.NEEDS_MAP_PIN }
                        val unavailable = response.places.any {
                            it.suitability == SearchSuitability.POINT_CANDIDATE &&
                                it.boundaryOutcome == BoundaryOutcome.UNAVAILABLE
                        }
                        mutableState.value.copy(results = presentable, searchState = when {
                            presentable.isNotEmpty() -> DestinationSearchState.RESULTS
                            unavailable -> DestinationSearchState.VERIFICATION_UNAVAILABLE
                            else -> DestinationSearchState.NO_RESULTS
                        })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) {
                    mutableState.value = mutableState.value.copy(
                        searchState = if (knownOffline()) DestinationSearchState.OFFLINE
                            else DestinationSearchState.SEARCH_UNAVAILABLE,
                        results = emptyList())
                }
            }
        }
    }
}

class DestinationPickerViewModelFactory(
    private val catalogue: AdminCatalogue,
    private val gateway: DestinationSearchGateway,
    private val knownOffline: () -> Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DestinationPickerViewModel(catalogue, gateway, knownOffline) as T
}
