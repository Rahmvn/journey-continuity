package com.journeycontinuity.app.data.search

import com.journeycontinuity.app.ui.destination.BoundaryOutcome
import com.journeycontinuity.app.ui.destination.DestinationSearchException
import com.journeycontinuity.app.ui.destination.DestinationSearchResponse
import com.journeycontinuity.app.ui.destination.SearchSuitability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationSearchContractParserTest {
    private val result = """{
      "provider":"GEOAPIFY","providerPlaceId":"point-1",
      "primaryLabel":"Hospital","secondaryLabel":"Abuja",
      "latitude":9.04,"longitude":7.50,"resultType":"PLACE",
      "providerRegionName":"Federal Capital Territory",
      "providerLocalityName":"Municipal Area Council",
      "regionMatch":"MATCH","localUnitMatch":"MATCH",
      "suitability":"SELECTABLE_POINT","boundaryOutcome":"INSIDE",
      "boundaryDatasetVersion":"alabarin-2026-10-03-v1",
      "attribution":{"sourceName":"OSM","attribution":"© contributors",
        "license":"ODbL","sourceUrl":"https://openstreetmap.org"}
    }"""

    @Test fun verifiedResponsePreservesNeutralContractAndBoundaryVersion() {
        val response = DestinationSearchContractParser.parse("""{"outcome":"OK","results":[$result]}""")
            as DestinationSearchResponse.Results
        val point = response.places.single()
        assertEquals(SearchSuitability.SELECTABLE_POINT, point.suitability)
        assertEquals(BoundaryOutcome.INSIDE, point.boundaryOutcome)
        assertEquals("alabarin-2026-10-03-v1", point.boundaryDatasetVersion)
        assertEquals("OSM", point.attribution?.sourceName)
    }

    @Test fun serverNoResultsIsNotAProviderFailure() {
        assertEquals(DestinationSearchResponse.NoResults,
            DestinationSearchContractParser.parse("""{"outcome":"NO_RESULTS","results":[]}"""))
    }

    @Test fun claimedSelectableWithoutBoundaryProofFailsClosed() {
        val response = result.replace("\"boundaryOutcome\":\"INSIDE\"",
            "\"boundaryOutcome\":\"UNAVAILABLE\"")
        assertTrue(runCatching { DestinationSearchContractParser.parse(
            """{"outcome":"OK","results":[$response]}""") }.exceptionOrNull()
            is DestinationSearchException)
    }

    @Test fun malformedOrOversizedResponseFailsClosed() {
        assertTrue(runCatching { DestinationSearchContractParser.parse("{}") }.exceptionOrNull()
            is DestinationSearchException)
        assertTrue(runCatching { DestinationSearchContractParser.parse(
            """{"outcome":"OK","results":[${List(6) { result }.joinToString(",") }]}""") }
            .exceptionOrNull() is DestinationSearchException)
    }

    private fun rejects(row: String) {
        assertTrue(runCatching { DestinationSearchContractParser.parse(
            """{"outcome":"OK","results":[$row]}""") }.exceptionOrNull() is DestinationSearchException)
    }

    @Test fun paddedProviderIdentityIsRejectedBeforeExactSelection() {
        rejects(result.replace("\"point-1\"", "\" point-1 \""))
        rejects(result.replace("\"GEOAPIFY\"", "\" GEOAPIFY \""))
    }

    @Test fun allRequiredAttributionFieldsMustBeUsable() {
        listOf("OSM", "© contributors", "ODbL", "https://openstreetmap.org").forEach {
            rejects(result.replace("\"$it\"", "\" \""))
        }
        rejects(result.replace("https://openstreetmap.org", "http://openstreetmap.org"))
        rejects(result.replace("https://openstreetmap.org", "https://"))
    }

    @Test fun selectableRoadAndProviderContradictionFailClosed() {
        rejects(result.replace("\"PLACE\"", "\"ROAD\""))
        rejects(result.replace("\"regionMatch\":\"MATCH\"", "\"regionMatch\":\"CONTRADICTION\""))
    }

    @Test fun malformedExactMetadataAndUnknownEnumsFailClosed() {
        rejects(result.replace("\"point-1\"", "null"))
        rejects(result.replace("\"alabarin-2026-10-03-v1\"", "\" \""))
        rejects(result.replace("\"alabarin-2026-10-03-v1\"", "\" alabarin-2026-10-03-v1 \""))
        rejects(result.replace("\"INSIDE\"", "\"NEW_OUTCOME\""))
        rejects(result.replace("\"SELECTABLE_POINT\"", "\"NEW_SUITABILITY\""))
    }

    @Test fun exactAndMapSeedCoordinatesMustBeFiniteAndInRange() {
        val road = result.replace("\"SELECTABLE_POINT\"", "\"NEEDS_MAP_PIN\"")
            .replace("\"PLACE\"", "\"ROAD\"")
            .replace("\"INSIDE\"", "null")
            .replace("\"alabarin-2026-10-03-v1\"", "null")
        assertTrue(DestinationSearchContractParser.parse(
            """{"outcome":"OK","results":[$road]}""") is DestinationSearchResponse.Results)
        listOf(result, road).forEach { row ->
            rejects(row.replace("9.04", "91.0"))
            rejects(row.replace("9.04", "-91.0"))
            rejects(row.replace("7.50", "181.0"))
            rejects(row.replace("7.50", "-181.0"))
            rejects(row.replace("9.04", "null"))
            rejects(row.replace("7.50", "1e999"))
        }
    }
}
