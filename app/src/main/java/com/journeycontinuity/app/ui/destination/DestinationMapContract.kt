package com.journeycontinuity.app.ui.destination

import com.journeycontinuity.app.domain.AdminLocalUnit
import com.journeycontinuity.app.domain.AdminRegion

const val OPERATIONAL_BOUNDARY_DATASET = "NG_OPERATIONAL_BOUNDARIES"

data class MapCoordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
    override fun toString() = "MapCoordinate(redacted)"
}
data class MapCamera(val target: MapCoordinate, val zoom: Double) {
    init { require(zoom.isFinite() && zoom in 0.0..22.0) }
    override fun toString() = "MapCamera(redacted)"
}
data class MapArea(val region: AdminRegion, val unit: AdminLocalUnit, val catalogueVersion: String)
data class CoordinateVerification(
    val outcome: BoundaryOutcome, val datasetId: String?, val datasetVersion: String?,
) {
    val hasBoundaryEvidence get() = datasetId == OPERATIONAL_BOUNDARY_DATASET &&
        !datasetVersion.isNullOrBlank() && datasetVersion == datasetVersion.trim() && datasetVersion.length <= 120 &&
        datasetVersion.none { Character.isISOControl(it) }
}
sealed interface LocalUnitViewport {
    data class Available(
        val west: Double, val south: Double, val east: Double, val north: Double,
        val datasetId: String, val datasetVersion: String,
    ) : LocalUnitViewport {
        init {
            require(listOf(west, south, east, north).all { it.isFinite() })
            require(west in -180.0..180.0 && east in -180.0..180.0 && west < east && east - west <= 180)
            require(south in -90.0..90.0 && north in -90.0..90.0 && south < north)
            require(datasetId == OPERATIONAL_BOUNDARY_DATASET && datasetVersion.isNotBlank())
        }
        override fun toString() = "LocalUnitViewport.Available(redacted)"
    }
    data object InvalidContext : LocalUnitViewport
    data object Unavailable : LocalUnitViewport
}
sealed interface InitialMapCamera {
    data class Seed(val camera: MapCamera) : InitialMapCamera
    data class Bounds(val viewport: LocalUnitViewport.Available) : InitialMapCamera
}
enum class MapLabelStatus { SPECIFIC_LABEL, BROAD_ONLY, NO_MEANINGFUL_LABEL, PROVIDER_UNAVAILABLE }
enum class MapLabelResultType { PLACE, ROAD, LOCALITY, POSTCODE, UNKNOWN }
data class MapLabelEvidence(
    val label: String, val secondaryLabel: String?, val resultType: MapLabelResultType,
    val provider: String, val providerPlaceId: String?, val distanceMeters: Double?,
    val attribution: ProviderAttribution,
) {
    override fun toString() = "MapLabelEvidence(redacted)"
}
sealed interface MapLabelResponse {
    data class Verified(
        val status: MapLabelStatus, val boundaryVersion: String,
        val canonicalLocalUnitName: String, val evidence: MapLabelEvidence? = null,
    ) : MapLabelResponse
    data class VerificationFailed(val verification: CoordinateVerification) : MapLabelResponse
}
data class MapPointRequest(val regionId: String, val localUnitId: String, val coordinate: MapCoordinate) {
    override fun toString() = "MapPointRequest(redacted)"
}
fun interface DestinationCoordinateVerifierGateway {
    suspend fun verify(request: MapPointRequest): CoordinateVerification
}
fun interface DestinationMapViewportGateway {
    suspend fun viewport(regionId: String, localUnitId: String): LocalUnitViewport
}
fun interface DestinationMapLabelGateway {
    suspend fun label(request: MapPointRequest): MapLabelResponse
}
interface DestinationMapGateway : DestinationCoordinateVerifierGateway,
    DestinationMapViewportGateway, DestinationMapLabelGateway
enum class DestinationMapFailure { AUTHENTICATION, UNAVAILABLE, INVALID_RESPONSE }
class DestinationMapException(val reason: DestinationMapFailure) : Exception("Destination map unavailable")

data class VerifiedMapPoint(
    val area: MapArea, val coordinate: MapCoordinate, val datasetId: String,
    val datasetVersion: String, val generation: Long,
) {
    init { require(CoordinateVerification(BoundaryOutcome.INSIDE, datasetId, datasetVersion).hasBoundaryEvidence) }
    override fun toString() = "VerifiedMapPoint(redacted)"
}
sealed interface MapPinPhase {
    data object Initializing : MapPinPhase
    data object ReadyUnverified : MapPinPhase
    data object Moving : MapPinPhase
    data class Checking(val coordinate: MapCoordinate) : MapPinPhase
    data class Inside(val point: VerifiedMapPoint) : MapPinPhase
    data object OutsideSelectedArea : MapPinPhase
    data object BoundaryAmbiguous : MapPinPhase
    data object CoverageUnresolved : MapPinPhase
    data object VerificationUnavailable : MapPinPhase
    data object InvalidContext : MapPinPhase
    data object OfflineUnverified : MapPinPhase
    data object MapLoadError : MapPinPhase
}
enum class PinIntent { AWAITING_USER_POSITION, CANDIDATE_REST, USER_POSITIONED }
sealed interface MapNamingLabel {
    data object Resolving : MapNamingLabel
    data class Ready(val status: MapLabelStatus, val evidence: MapLabelEvidence?) : MapNamingLabel
}
enum class MapNameIssue { TOO_LONG, CONTROL_CHARACTERS }
sealed interface DestinationMapState {
    data object Closed : DestinationMapState
    data class Positioning(
        val area: MapArea, val initialCamera: InitialMapCamera? = null,
        val camera: MapCamera? = null, val intent: PinIntent = PinIntent.AWAITING_USER_POSITION,
        val phase: MapPinPhase = MapPinPhase.Initializing, val renderAttempt: Int = 0,
    ) : DestinationMapState
    data class Naming(
        val point: VerifiedMapPoint, val camera: MapCamera, val initialCamera: InitialMapCamera,
        val renderAttempt: Int, val name: String = "", val issue: MapNameIssue? = null,
        val label: MapNamingLabel = MapNamingLabel.Resolving,
    ) : DestinationMapState
}

internal fun mapNameIssue(name: String): MapNameIssue? = when {
    name.any { Character.isISOControl(it) } -> MapNameIssue.CONTROL_CHARACTERS
    (trimmedMapName(name)?.let { it.codePointCount(0, it.length) } ?: 0) > 160 -> MapNameIssue.TOO_LONG
    else -> null
}
internal fun trimmedMapName(name: String): String? =
    name.trim { it.isWhitespace() || it == '\uFEFF' }.takeIf { it.isNotBlank() }

internal fun mapPointDraft(state: DestinationMapState.Naming): DestinationDraft.ExactPoint {
    require(state.issue == null && state.label is MapNamingLabel.Ready)
    val point = state.point
    val evidence = state.label.evidence
    val custom = trimmedMapName(state.name)
    val specific = custom ?: evidence?.label
    val unit = point.area.unit
    val label = when {
        specific == null -> "Pinned location in ${unit.canonicalName}"
        specific.equals(unit.canonicalName, true) || specific.endsWith(", ${unit.canonicalName}", true) -> specific
        else -> "$specific, ${unit.canonicalName}"
    }
    return DestinationDraft.ExactPoint(label,
        com.journeycontinuity.app.domain.StructuredDestination(
            countryCode = "NG", stateId = point.area.region.id, stateName = point.area.region.canonicalName,
            localUnitId = unit.id, localUnitName = unit.canonicalName, localUnitType = unit.type,
            kind = com.journeycontinuity.app.domain.DestinationKind.POINT,
            latitude = point.coordinate.latitude, longitude = point.coordinate.longitude,
            selectionSource = com.journeycontinuity.app.domain.DestinationSelectionSource.MAP_PIN,
            adminDatasetVersion = point.area.catalogueVersion, customPlaceName = custom,
            provider = evidence?.provider, providerPlaceId = evidence?.providerPlaceId,
            boundaryDatasetId = point.datasetId, boundaryDatasetVersion = point.datasetVersion,
            providerAttribution = evidence?.attribution,
        ), point.datasetVersion, evidence?.attribution, point.datasetId, evidence)
}
