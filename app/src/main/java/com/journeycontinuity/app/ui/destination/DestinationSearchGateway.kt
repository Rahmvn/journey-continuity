package com.journeycontinuity.app.ui.destination

import com.journeycontinuity.app.domain.AdminLocalUnit
import com.journeycontinuity.app.domain.AdminRegion
import com.journeycontinuity.app.domain.DestinationKind
import com.journeycontinuity.app.domain.DestinationSelectionSource
import com.journeycontinuity.app.domain.StructuredDestination

enum class SearchSuitability { SELECTABLE_POINT, POINT_CANDIDATE, NEEDS_MAP_PIN, NOT_USABLE }
enum class BoundaryOutcome {
    INSIDE, OUTSIDE_SELECTED_UNIT, BOUNDARY_AMBIGUOUS, COVERAGE_UNRESOLVED,
    INVALID_CONTEXT, UNAVAILABLE,
}
enum class AdminTextMatch { MATCH, UNKNOWN, CONTRADICTION }
enum class SearchResultType { PLACE, ROAD, LOCALITY, UNKNOWN }

data class ProviderAttribution(
    val sourceName: String,
    val attribution: String,
    val license: String,
    val sourceUrl: String,
)

/** The server decides suitability; Android does not infer containment from provider text. */
data class PlaceSearchResult(
    val provider: String,
    val providerPlaceId: String?,
    val primaryLabel: String,
    val secondaryLabel: String?,
    val latitude: Double?,
    val longitude: Double?,
    val resultType: SearchResultType,
    val providerRegionName: String?,
    val providerLocalityName: String?,
    val regionMatch: AdminTextMatch,
    val localUnitMatch: AdminTextMatch,
    val suitability: SearchSuitability,
    val boundaryOutcome: BoundaryOutcome?,
    val boundaryDatasetVersion: String?,
    val attribution: ProviderAttribution?,
) {
    // Destination queries and point metadata should never enter generic logs.
    override fun toString(): String = "PlaceSearchResult(redacted)"
}

data class DestinationSearchRequest(
    val query: String,
    val regionId: String,
    val localUnitId: String,
)

enum class DestinationQueryIssue { TOO_SHORT, TOO_LONG, CONTROL_CHARACTERS }

internal data class DestinationQueryValidation(val requestQuery: String?, val issue: DestinationQueryIssue?)

/** Match the server's Unicode code-point bounds; editing text is not trusted request text. */
internal fun validateDestinationQuery(value: String): DestinationQueryValidation {
    if (value.any { it <= '\u001f' || it == '\u007f' }) {
        return DestinationQueryValidation(null, DestinationQueryIssue.CONTROL_CHARACTERS)
    }
    val query = value.trim { it.isWhitespace() || it == '\uFEFF' }
    return when {
        query.codePointCount(0, query.length) < 3 -> DestinationQueryValidation(null, DestinationQueryIssue.TOO_SHORT)
        query.codePointCount(0, query.length) > 120 -> DestinationQueryValidation(null, DestinationQueryIssue.TOO_LONG)
        else -> DestinationQueryValidation(query, null)
    }
}

sealed interface DestinationSearchResponse {
    data class Results(val places: List<PlaceSearchResult>) : DestinationSearchResponse
    data object NoResults : DestinationSearchResponse
}

enum class DestinationSearchFailure { AUTHENTICATION, UNAVAILABLE }

class DestinationSearchException(val reason: DestinationSearchFailure) : Exception("Destination search unavailable")

fun interface DestinationSearchGateway {
    suspend fun autocomplete(request: DestinationSearchRequest): DestinationSearchResponse
}

sealed interface DestinationDraft {
    val displayLabel: String
    val destination: StructuredDestination

    data class ExactPoint(
        override val displayLabel: String,
        override val destination: StructuredDestination,
        val boundaryDatasetVersion: String,
        val attribution: ProviderAttribution,
    ) : DestinationDraft

    data class Locality(
        override val displayLabel: String,
        override val destination: StructuredDestination,
    ) : DestinationDraft
}

data class MapRefinementRequest(
    val region: AdminRegion,
    val localUnit: AdminLocalUnit,
    val candidate: PlaceSearchResult?,
)

internal fun localityDraft(region: AdminRegion, unit: AdminLocalUnit, catalogueVersion: String) =
    DestinationDraft.Locality(
        displayLabel = unit.canonicalName,
        destination = StructuredDestination(
            countryCode = "NG", stateId = region.id, stateName = region.canonicalName,
            localUnitId = unit.id, localUnitName = unit.canonicalName,
            kind = DestinationKind.LOCALITY, latitude = null, longitude = null,
            selectionSource = DestinationSelectionSource.LOCALITY_SELECTION,
            adminDatasetVersion = catalogueVersion,
        ),
    )

internal fun exactPointDraft(
    region: AdminRegion, unit: AdminLocalUnit, catalogueVersion: String,
    result: PlaceSearchResult,
): DestinationDraft.ExactPoint {
    require(result.suitability == SearchSuitability.SELECTABLE_POINT &&
        result.boundaryOutcome == BoundaryOutcome.INSIDE)
    val version = requireNotNull(result.boundaryDatasetVersion).takeIf { it.isNotBlank() }
        ?: error("Verified point must carry the boundary version")
    val attribution = requireNotNull(result.attribution)
    val name = result.primaryLabel.trim()
    require(name.isNotEmpty())
    val label = if (name.endsWith(unit.canonicalName, ignoreCase = true)) name
        else "$name, ${unit.canonicalName}"
    return DestinationDraft.ExactPoint(
        displayLabel = label,
        destination = StructuredDestination(
            countryCode = "NG", stateId = region.id, stateName = region.canonicalName,
            localUnitId = unit.id, localUnitName = unit.canonicalName,
            kind = DestinationKind.POINT,
            latitude = requireNotNull(result.latitude),
            longitude = requireNotNull(result.longitude),
            selectionSource = DestinationSelectionSource.PLACE_SEARCH,
            provider = result.provider,
            providerPlaceId = requireNotNull(result.providerPlaceId),
            adminDatasetVersion = catalogueVersion,
        ),
        boundaryDatasetVersion = version,
        attribution = attribution,
    )
}
