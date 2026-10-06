package com.journeycontinuity.app.data.search

import com.journeycontinuity.app.ui.destination.AdminTextMatch
import com.journeycontinuity.app.ui.destination.BoundaryOutcome
import com.journeycontinuity.app.ui.destination.DestinationSearchException
import com.journeycontinuity.app.ui.destination.DestinationSearchFailure
import com.journeycontinuity.app.ui.destination.DestinationSearchResponse
import com.journeycontinuity.app.ui.destination.PlaceSearchResult
import com.journeycontinuity.app.ui.destination.ProviderAttribution
import com.journeycontinuity.app.ui.destination.SearchResultType
import com.journeycontinuity.app.ui.destination.SearchSuitability
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** Parses only the minimized Edge Function response, never provider JSON. */
internal object DestinationSearchContractParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): DestinationSearchResponse {
        try {
            val wire = json.decodeFromString(WireResponse.serializer(), body)
            return when (wire.outcome) {
                "NO_RESULTS" -> if (wire.results.isEmpty()) DestinationSearchResponse.NoResults else invalid()
                "OK" -> {
                    if (wire.results.isEmpty() || wire.results.size > 5) invalid()
                    DestinationSearchResponse.Results(wire.results.map { it.toDomain() })
                }
                else -> invalid()
            }
        } catch (known: DestinationSearchException) {
            throw known
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun invalid(): Nothing = throw DestinationSearchException(DestinationSearchFailure.UNAVAILABLE)

    private fun WireResult.toDomain(): PlaceSearchResult {
        val suitability = enumValueOf<SearchSuitability>(suitability)
        val boundary = boundaryOutcome?.let { enumValueOf<BoundaryOutcome>(it) }
        val type = enumValueOf<SearchResultType>(resultType)
        val region = enumValueOf<AdminTextMatch>(regionMatch)
        val localUnit = enumValueOf<AdminTextMatch>(localUnitMatch)
        if (!canonicalText(primaryLabel, 160) || !canonicalText(provider, 80)) invalid()
        if (providerPlaceId != null && !canonicalText(providerPlaceId, 512)) invalid()
        if (boundaryDatasetVersion != null && !canonicalText(boundaryDatasetVersion, 120)) invalid()
        if (attribution != null && !attribution.valid()) invalid()
        if (suitability != SearchSuitability.NOT_USABLE) {
            if (providerPlaceId == null || attribution == null ||
                !validCoordinate(latitude, -90.0, 90.0) ||
                !validCoordinate(longitude, -180.0, 180.0) ||
                region == AdminTextMatch.CONTRADICTION || localUnit == AdminTextMatch.CONTRADICTION) invalid()
        }
        // These are transport consistency checks, not client-side containment reasoning.
        val consistent = when (suitability) {
            SearchSuitability.SELECTABLE_POINT -> type == SearchResultType.PLACE &&
                boundary == BoundaryOutcome.INSIDE && boundaryDatasetVersion != null
            SearchSuitability.POINT_CANDIDATE -> type == SearchResultType.PLACE &&
                boundary == BoundaryOutcome.UNAVAILABLE && boundaryDatasetVersion == null
            SearchSuitability.NEEDS_MAP_PIN -> when (type) {
                SearchResultType.ROAD, SearchResultType.LOCALITY ->
                    boundary == null && boundaryDatasetVersion == null
                SearchResultType.PLACE -> boundary in setOf(BoundaryOutcome.BOUNDARY_AMBIGUOUS,
                    BoundaryOutcome.COVERAGE_UNRESOLVED) && boundaryDatasetVersion != null
                SearchResultType.UNKNOWN -> false
            }
            SearchSuitability.NOT_USABLE -> (boundary == null && boundaryDatasetVersion == null) ||
                (type == SearchResultType.PLACE && boundary == BoundaryOutcome.OUTSIDE_SELECTED_UNIT &&
                    boundaryDatasetVersion != null)
        }
        if (!consistent) invalid()
        return PlaceSearchResult(provider, providerPlaceId, primaryLabel, secondaryLabel,
            latitude, longitude, type, providerRegionName, providerLocalityName, region,
            localUnit, suitability, boundary,
            boundaryDatasetVersion, attribution?.let {
                ProviderAttribution(it.sourceName, it.attribution, it.license, it.sourceUrl)
            })
    }

    private fun canonicalText(value: String, maxCodePoints: Int): Boolean =
        value.isNotBlank() && value == value.trim() &&
            value.codePointCount(0, value.length) <= maxCodePoints &&
            value.none { it <= '\u001f' || it == '\u007f' }

    private fun validCoordinate(value: Double?, min: Double, max: Double): Boolean =
        value != null && value.isFinite() && value in min..max

    private fun WireAttribution.valid(): Boolean {
        if (!canonicalText(sourceName, 80) || !canonicalText(attribution, 160) ||
            !canonicalText(license, 80) || !canonicalText(sourceUrl, 300)) return false
        val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return false
        return uri.scheme == "https" && !uri.host.isNullOrBlank()
    }

    @Serializable private data class WireResponse(val outcome: String, val results: List<WireResult>)
    @Serializable private data class WireAttribution(
        val sourceName: String, val attribution: String, val license: String, val sourceUrl: String,
    )
    @Serializable private data class WireResult(
        val provider: String, val providerPlaceId: String?, val primaryLabel: String,
        val secondaryLabel: String?, val latitude: Double?, val longitude: Double?,
        val resultType: String, val providerRegionName: String?, val providerLocalityName: String?,
        val regionMatch: String, val localUnitMatch: String, val suitability: String,
        val boundaryOutcome: String?, val boundaryDatasetVersion: String?,
        val attribution: WireAttribution?,
    )
}
