package com.journeycontinuity.app.ui.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.domain.AdminCatalogue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Activity-scoped, memory-only draft logic. No Context, native map, durable state or Journey API. */
class DestinationMapViewModel(
    private val catalogue: AdminCatalogue,
    private val verifier: DestinationCoordinateVerifierGateway,
    private val viewport: DestinationMapViewportGateway,
    private val labels: DestinationMapLabelGateway,
    initiallyOnline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow<DestinationMapState>(DestinationMapState.Closed)
    val state: StateFlow<DestinationMapState> = mutableState
    private var online = initiallyOnline
    private var generation = 0L
    private var work: Job? = null

    fun open(request: MapRefinementRequest) {
        cancelWork()
        val area = MapArea(request.region, request.localUnit, catalogue.metadata.version)
        if (catalogue.region(request.region.id) != request.region ||
            catalogue.localUnit(request.localUnit.id) != request.localUnit ||
            request.localUnit.parentRegionId != request.region.id) {
            mutableState.value = DestinationMapState.Positioning(area, phase = MapPinPhase.InvalidContext)
            return
        }
        val candidate = request.candidate
        if (candidate != null) {
            val coordinate = runCatching { MapCoordinate(requireNotNull(candidate.latitude),
                requireNotNull(candidate.longitude)) }.getOrNull()
            if (coordinate == null || candidate.suitability != SearchSuitability.NEEDS_MAP_PIN) {
                mutableState.value = DestinationMapState.Positioning(area, phase = MapPinPhase.InvalidContext)
                return
            }
            mutableState.value = DestinationMapState.Positioning(area,
                initialCamera = InitialMapCamera.Seed(MapCamera(coordinate, 15.0)),
                intent = PinIntent.CANDIDATE_REST, phase = MapPinPhase.ReadyUnverified)
        } else {
            mutableState.value = DestinationMapState.Positioning(area)
            initializeViewport()
        }
    }

    fun close() { cancelWork(); mutableState.value = DestinationMapState.Closed }

    private fun cancelWork() { generation++; work?.cancel(); work = null }

    private fun initializeViewport() {
        val current = mutableState.value as? DestinationMapState.Positioning ?: return
        cancelWork()
        val ticket = generation
        if (!online) {
            mutableState.value = current.copy(phase = MapPinPhase.OfflineUnverified)
            return
        }
        mutableState.value = current.copy(phase = MapPinPhase.Initializing)
        work = viewModelScope.launch {
            try {
                val result = viewport.viewport(current.area.region.id, current.area.unit.id)
                if (ticket != generation) return@launch
                mutableState.value = when (result) {
                    is LocalUnitViewport.Available -> current.copy(initialCamera = InitialMapCamera.Bounds(result),
                        phase = MapPinPhase.ReadyUnverified)
                    LocalUnitViewport.InvalidContext -> current.copy(phase = MapPinPhase.InvalidContext)
                    LocalUnitViewport.Unavailable -> current.copy(phase = MapPinPhase.MapLoadError)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (ticket == generation) mutableState.value = current.copy(phase =
                    if (failure is DestinationMapException && failure.reason == DestinationMapFailure.AUTHENTICATION)
                        MapPinPhase.InvalidContext else MapPinPhase.MapLoadError)
            }
        }
    }

    fun userCameraMoveStarted() {
        val current = mutableState.value as? DestinationMapState.Positioning ?: return
        if (current.phase == MapPinPhase.InvalidContext || current.phase == MapPinPhase.MapLoadError) return
        cancelWork()
        mutableState.value = current.copy(intent = PinIntent.USER_POSITIONED, phase = MapPinPhase.Moving)
    }

    /** Programmatic movement only invalidates a proof when its actual centre changes. */
    fun cameraMoving(coordinate: MapCoordinate) {
        val current = mutableState.value as? DestinationMapState.Positioning ?: return
        val proof = (current.phase as? MapPinPhase.Inside)?.point ?: return
        if (proof.coordinate != coordinate) {
            cancelWork()
            mutableState.value = current.copy(phase = MapPinPhase.Moving)
        }
    }

    fun cameraIdle(camera: MapCamera) {
        val current = mutableState.value as? DestinationMapState.Positioning ?: return
        if (current.phase == MapPinPhase.InvalidContext || current.phase == MapPinPhase.MapLoadError) return
        val updated = current.copy(camera = camera)
        mutableState.value = updated
        if ((current.phase as? MapPinPhase.Inside)?.point?.coordinate == camera.target) return
        // Initialization/recreation can repeat idle without selecting a new coordinate.
        if ((current.phase as? MapPinPhase.Checking)?.coordinate == camera.target) return
        if (current.intent == PinIntent.AWAITING_USER_POSITION) return
        checkStationaryPoint()
    }

    private fun checkStationaryPoint() {
        val current = mutableState.value as? DestinationMapState.Positioning ?: return
        val coordinate = current.camera?.target ?: return
        cancelWork()
        val ticket = generation
        if (!online) {
            mutableState.value = current.copy(phase = MapPinPhase.OfflineUnverified)
            return
        }
        mutableState.value = current.copy(phase = MapPinPhase.Checking(coordinate))
        work = viewModelScope.launch {
            delay(400)
            try {
                val result = verifier.verify(MapPointRequest(current.area.region.id, current.area.unit.id, coordinate))
                if (ticket != generation) return@launch
                val latest = mutableState.value as? DestinationMapState.Positioning ?: return@launch
                mutableState.value = latest.copy(phase = phase(result, latest.area, coordinate, ticket))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (ticket != generation) return@launch
                val latest = mutableState.value as? DestinationMapState.Positioning ?: return@launch
                mutableState.value = latest.copy(phase = when {
                    failure is DestinationMapException && failure.reason == DestinationMapFailure.AUTHENTICATION ->
                        MapPinPhase.InvalidContext
                    !online -> MapPinPhase.OfflineUnverified
                    else -> MapPinPhase.VerificationUnavailable
                })
            }
        }
    }

    private fun phase(result: CoordinateVerification, area: MapArea, coordinate: MapCoordinate, ticket: Long): MapPinPhase {
        if (result.outcome !in setOf(BoundaryOutcome.UNAVAILABLE, BoundaryOutcome.INVALID_CONTEXT) &&
            !result.hasBoundaryEvidence) return MapPinPhase.VerificationUnavailable
        return when (result.outcome) {
            BoundaryOutcome.INSIDE -> MapPinPhase.Inside(VerifiedMapPoint(area, coordinate,
                result.datasetId!!, result.datasetVersion!!, ticket))
            BoundaryOutcome.OUTSIDE_SELECTED_UNIT -> MapPinPhase.OutsideSelectedArea
            BoundaryOutcome.BOUNDARY_AMBIGUOUS -> MapPinPhase.BoundaryAmbiguous
            BoundaryOutcome.COVERAGE_UNRESOLVED -> MapPinPhase.CoverageUnresolved
            BoundaryOutcome.UNAVAILABLE -> MapPinPhase.VerificationUnavailable
            BoundaryOutcome.INVALID_CONTEXT -> MapPinPhase.InvalidContext
        }
    }

    fun connectivityChanged(available: Boolean) {
        if (online == available) return
        online = available
        when (val current = mutableState.value) {
            is DestinationMapState.Positioning -> {
                if (current.phase is MapPinPhase.Inside || current.phase == MapPinPhase.InvalidContext ||
                    current.phase == MapPinPhase.MapLoadError || current.phase == MapPinPhase.Moving) return
                if (!available) {
                    cancelWork()
                    mutableState.value = current.copy(phase = MapPinPhase.OfflineUnverified)
                } else if (current.initialCamera == null) initializeViewport()
                else if (current.intent != PinIntent.AWAITING_USER_POSITION && current.camera != null) checkStationaryPoint()
                else mutableState.value = current.copy(phase = MapPinPhase.ReadyUnverified)
            }
            is DestinationMapState.Naming -> if (!available && current.label == MapNamingLabel.Resolving) {
                cancelWork()
                mutableState.value = current.copy(label = fallback())
            }
            DestinationMapState.Closed -> Unit
        }
    }

    fun continueToNaming() {
        val current = mutableState.value as? DestinationMapState.Positioning ?: return
        val point = (current.phase as? MapPinPhase.Inside)?.point ?: return
        val camera = current.camera ?: return
        if (camera.target != point.coordinate) return
        cancelWork()
        val naming = DestinationMapState.Naming(point, camera, current.initialCamera ?: InitialMapCamera.Seed(camera),
            current.renderAttempt, label = if (online) MapNamingLabel.Resolving else fallback())
        mutableState.value = naming
        if (!online) return
        val ticket = generation
        work = viewModelScope.launch {
            try {
                val response = labels.label(MapPointRequest(point.area.region.id, point.area.unit.id, point.coordinate))
                if (ticket != generation) return@launch
                val latest = mutableState.value as? DestinationMapState.Naming ?: return@launch
                when (response) {
                    is MapLabelResponse.VerificationFailed -> mutableState.value = positioning(latest,
                        phase(response.verification, point.area, point.coordinate, ticket))
                    is MapLabelResponse.Verified -> {
                        if (response.canonicalLocalUnitName != point.area.unit.canonicalName ||
                            response.boundaryVersion.isBlank()) {
                            mutableState.value = positioning(latest, MapPinPhase.InvalidContext)
                        } else {
                            // A newer server snapshot may re-verify the same frozen coordinate.
                            mutableState.value = latest.copy(point = point.copy(datasetVersion = response.boundaryVersion),
                                label = MapNamingLabel.Ready(response.status, response.evidence))
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (ticket != generation) return@launch
                val latest = mutableState.value as? DestinationMapState.Naming ?: return@launch
                mutableState.value = when ((failure as? DestinationMapException)?.reason) {
                    DestinationMapFailure.AUTHENTICATION -> positioning(latest, MapPinPhase.InvalidContext)
                    DestinationMapFailure.INVALID_RESPONSE -> positioning(latest, MapPinPhase.VerificationUnavailable)
                    else -> latest.copy(label = fallback())
                }
            }
        }
    }

    private fun fallback() = MapNamingLabel.Ready(MapLabelStatus.PROVIDER_UNAVAILABLE, null)
    private fun positioning(naming: DestinationMapState.Naming, phase: MapPinPhase) =
        DestinationMapState.Positioning(naming.point.area, naming.initialCamera, naming.camera,
            PinIntent.USER_POSITIONED, phase, naming.renderAttempt)

    fun updateName(value: String) {
        val current = mutableState.value as? DestinationMapState.Naming ?: return
        mutableState.value = current.copy(name = value, issue = mapNameIssue(value))
    }
    fun finish(): DestinationDraft.ExactPoint? {
        val current = mutableState.value as? DestinationMapState.Naming ?: return null
        if (current.issue != null || current.label !is MapNamingLabel.Ready) return null
        val draft = mapPointDraft(current)
        close()
        return draft
    }
    fun locality(): DestinationDraft.Locality? {
        val current = mutableState.value as? DestinationMapState.Positioning ?: return null
        if (current.phase !in setOf(MapPinPhase.CoverageUnresolved, MapPinPhase.VerificationUnavailable,
                MapPinPhase.OfflineUnverified, MapPinPhase.MapLoadError)) return null
        val draft = localityDraft(current.area.region, current.area.unit, current.area.catalogueVersion)
        close()
        return draft
    }
    /** False means return to the picker. Naming Back preserves its exact centred proof. */
    fun back(): Boolean {
        val current = mutableState.value as? DestinationMapState.Naming ?: return false
        cancelWork()
        mutableState.value = positioning(current, MapPinPhase.Inside(current.point))
        return true
    }
    fun mapLoadFailed() {
        val current = mutableState.value
        if ((current as? DestinationMapState.Positioning)?.phase == MapPinPhase.InvalidContext) return
        cancelWork()
        mutableState.value = when (current) {
            is DestinationMapState.Positioning -> current.copy(phase = MapPinPhase.MapLoadError)
            is DestinationMapState.Naming -> positioning(current, MapPinPhase.MapLoadError)
            DestinationMapState.Closed -> current
        }
    }
    fun retry() {
        val current = mutableState.value as? DestinationMapState.Positioning ?: return
        if (current.phase == MapPinPhase.MapLoadError) {
            mutableState.value = current.copy(renderAttempt = current.renderAttempt + 1,
                phase = MapPinPhase.ReadyUnverified)
            if (current.initialCamera == null) initializeViewport()
        } else if (current.phase == MapPinPhase.VerificationUnavailable || current.phase == MapPinPhase.OfflineUnverified) {
            if (current.initialCamera == null) initializeViewport() else checkStationaryPoint()
        }
    }
}

class DestinationMapViewModelFactory(
    private val catalogue: AdminCatalogue, private val gateway: DestinationMapGateway,
    private val initiallyOnline: Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DestinationMapViewModel(catalogue, gateway, gateway, gateway, initiallyOnline) as T
}
