package com.journeycontinuity.app.domain

enum class DestinationKind { POINT, LOCALITY }

enum class DestinationSelectionSource { PLACE_SEARCH, MAP_PIN, LOCALITY_SELECTION }

/** Creation-time destination evidence. The Journey's destination text remains its display label. */
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
) {
    init {
        require(countryCode == "NG") { "Traveller v1 structured destinations must be in Nigeria." }
        listOf(stateId, stateName, localUnitId, localUnitName).forEach {
            require(it.isNotBlank() && it == it.trim()) { "Region and local unit identity must be nonblank and trimmed." }
        }
        listOfNotNull(provider, providerPlaceId, adminDatasetVersion, customPlaceName).forEach {
            require(it.isNotBlank() && it == it.trim()) { "Optional destination metadata must be nonblank and trimmed." }
        }
        require(providerPlaceId == null || provider != null) { "A provider place ID needs a provider." }
        require((latitude == null) == (longitude == null)) { "Destination coordinates must be a pair." }
        require(latitude == null || (latitude.isFinite() && latitude in -90.0..90.0)) {
            "Destination latitude is invalid."
        }
        require(longitude == null || (longitude.isFinite() && longitude in -180.0..180.0)) {
            "Destination longitude is invalid."
        }
        require(kind != DestinationKind.POINT || latitude != null) { "A point needs coordinates." }
        require(kind != DestinationKind.LOCALITY || latitude == null) {
            "A locality cannot carry point coordinates."
        }
        require(
            (kind == DestinationKind.POINT && selectionSource in setOf(
                DestinationSelectionSource.PLACE_SEARCH, DestinationSelectionSource.MAP_PIN,
            )) || (kind == DestinationKind.LOCALITY &&
                selectionSource == DestinationSelectionSource.LOCALITY_SELECTION)
        ) { "Destination kind and selection source must agree." }
        require(customPlaceName == null || selectionSource == DestinationSelectionSource.MAP_PIN) {
            "A custom place name belongs to a map pin."
        }
    }

    // Avoid leaking coordinates or provider identifiers through generic Journey logging.
    override fun toString(): String = "StructuredDestination(redacted)"
}
