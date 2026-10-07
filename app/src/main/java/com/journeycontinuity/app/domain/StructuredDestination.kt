package com.journeycontinuity.app.domain

import java.net.URI
import kotlinx.serialization.Serializable

enum class DestinationKind { POINT, LOCALITY }
enum class DestinationSelectionSource { PLACE_SEARCH, MAP_PIN, LOCALITY_SELECTION }

const val DESTINATION_BOUNDARY_DATASET = "NG_OPERATIONAL_BOUNDARIES"

/** Minimized place evidence, never a raw geocoder response or map tile attribution. */
@Serializable
data class DestinationProviderAttribution(
    val sourceName: String,
    val attribution: String,
    val license: String,
    val sourceUrl: String,
) {
    init {
        requireDestinationText(sourceName, 80)
        requireDestinationText(attribution, 160)
        requireDestinationText(license, 80)
        requireDestinationText(sourceUrl, 300)
        val uri = requireNotNull(runCatching { URI(sourceUrl) }.getOrNull()) {
            "Destination attribution source URL is invalid."
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Destination attribution requires a public HTTPS source URL."
        }
    }

    override fun toString() = "DestinationProviderAttribution(redacted)"
}

/** Immutable creation-time evidence. Journey.destination remains the display label. */
data class StructuredDestination(
    val countryCode: String,
    val stateId: String,
    val stateName: String,
    val localUnitId: String,
    val localUnitName: String,
    val kind: DestinationKind,
    val latitude: Double?,
    val longitude: Double?,
    val selectionSource: DestinationSelectionSource,
    val provider: String? = null,
    val providerPlaceId: String? = null,
    val adminDatasetVersion: String? = null,
    val customPlaceName: String? = null,
    val localUnitType: AdminLocalUnitType? = null,
    val boundaryDatasetId: String? = null,
    val boundaryDatasetVersion: String? = null,
    val providerAttribution: DestinationProviderAttribution? = null,
) {
    init { StructuredDestinationValidator.validate(this) }

    override fun toString() = "StructuredDestination(redacted)"
}

/** Shared by construction, durable reconstruction and future admission; never normalizes evidence. */
object StructuredDestinationValidator {
    fun validate(value: StructuredDestination, catalogue: AdminCatalogue? = null) {
        with(value) {
            require(countryCode == "NG") { "Structured destination must be in Nigeria." }
            listOf(stateId, stateName, localUnitId, localUnitName).forEach { requireDestinationText(it, 160) }
            require(stateId.matches(Regex("ng-[a-z0-9]+(?:-[a-z0-9]+)*")) &&
                localUnitId.startsWith("$stateId-") &&
                localUnitId.matches(Regex("ng-[a-z0-9]+(?:-[a-z0-9]+)*"))) {
                "Destination canonical identifiers have contradictory context."
            }
            require(localUnitType != null && localUnitType ==
                if (stateId == "ng-fct") AdminLocalUnitType.AREA_COUNCIL else AdminLocalUnitType.LGA) {
                "Destination local-unit type is missing or contradicts its region."
            }
            requireDestinationText(requireNotNull(adminDatasetVersion), 120)
            provider?.let { requireDestinationText(it, 80) }
            providerPlaceId?.let { requireDestinationText(it, 512) }
            customPlaceName?.let { requireDestinationText(it, 160) }
            boundaryDatasetId?.let { requireDestinationText(it, 120) }
            boundaryDatasetVersion?.let { requireDestinationText(it, 120) }
            require((latitude == null) == (longitude == null)) { "Destination coordinates must be a pair." }
            require(latitude == null || (latitude.isFinite() && latitude in -90.0..90.0)) {
                "Destination latitude is invalid."
            }
            require(longitude == null || (longitude.isFinite() && longitude in -180.0..180.0)) {
                "Destination longitude is invalid."
            }
            when (kind) {
                DestinationKind.LOCALITY -> require(
                    selectionSource == DestinationSelectionSource.LOCALITY_SELECTION &&
                        latitude == null && longitude == null && boundaryDatasetId == null &&
                        boundaryDatasetVersion == null && provider == null && providerPlaceId == null &&
                        providerAttribution == null && customPlaceName == null
                ) { "Locality cannot contain point or provider evidence." }
                DestinationKind.POINT -> {
                    require(latitude != null && longitude != null) { "A point needs coordinates." }
                    require(boundaryDatasetId == DESTINATION_BOUNDARY_DATASET && boundaryDatasetVersion != null) {
                        "A point needs operational boundary identity and version."
                    }
                    when (selectionSource) {
                        DestinationSelectionSource.PLACE_SEARCH -> require(
                            provider != null && providerPlaceId != null && providerAttribution != null &&
                                customPlaceName == null
                        ) { "Place search requires complete provenance and cannot contain a custom pin name." }
                        DestinationSelectionSource.MAP_PIN -> require(
                            (provider == null && providerPlaceId == null && providerAttribution == null) ||
                                (provider == "GEOAPIFY" && providerAttribution != null)
                        ) { "Map pin provider evidence must match the reverse-label contract." }
                        DestinationSelectionSource.LOCALITY_SELECTION ->
                            require(false) { "A point cannot use locality selection." }
                    }
                }
            }
            if (catalogue != null) {
                val region = requireNotNull(catalogue.region(stateId))
                val unit = requireNotNull(catalogue.localUnit(localUnitId))
                require(adminDatasetVersion == catalogue.metadata.version && region.canonicalName == stateName &&
                    unit.parentRegionId == region.id && unit.canonicalName == localUnitName && unit.type == localUnitType) {
                    "Destination does not match the catalogue snapshot."
                }
            }
        }
    }
}

private fun requireDestinationText(value: String, maxCodePoints: Int) {
    require(value.isNotBlank() && value == value.trim() &&
        value.codePointCount(0, value.length) <= maxCodePoints && value.none { Character.isISOControl(it) }) {
        "Destination evidence must be nonblank, trimmed and bounded without control characters."
    }
}
