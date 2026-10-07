package com.journeycontinuity.app.sync

import com.journeycontinuity.app.domain.DestinationEvidenceFixtures
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.data.local.toEntity
import com.journeycontinuity.app.data.local.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StructuredDestinationCloudRowTest {
    private val base = Journey("journey", "Summit University, Offa", 9_000, 1_000,
        JourneyStatus.ACTIVE, null)
    private val evidenceKeys = setOf("destination_local_unit_type", "destination_boundary_dataset_id",
        "destination_boundary_dataset_version", "destination_provider_attribution")

    @Test fun mapPinWithoutProviderRetainsExactPointAndBoundaryEvidence() {
        val point = DestinationEvidenceFixtures.mapPin()
        val row = base.copy(structuredDestination = point).toCloudRow("owner")
        assertEquals(base.destination, row.destination)
        assertEquals("POINT", row.destinationKind)
        assertEquals(point.latitude, row.destinationLatitude)
        assertEquals(point.longitude, row.destinationLongitude)
        assertEquals(point.localUnitId, row.destinationLgaId)
        assertEquals(point.adminDatasetVersion, row.destinationAdminDatasetVersion)
        assertEquals("LGA", row.destinationLocalUnitType)
        assertEquals(point.boundaryDatasetId, row.destinationBoundaryDatasetId)
        assertEquals(point.boundaryDatasetVersion, row.destinationBoundaryDatasetVersion)
        assertNull(row.destinationProvider)
        assertNull(row.destinationProviderPlaceId)
        assertNull(row.destinationProviderAttribution)
        assertEquals(row, Json.decodeFromString<CloudJourneyRow>(Json.encodeToString(row)))
    }

    @Test fun mapPinReverseProvenanceRoundTripsWithOptionalPlaceId() {
        listOf(null, "reverse-id").forEach { placeId ->
            val point = DestinationEvidenceFixtures.mapPinWithProvider(placeId)
            val row = base.copy(structuredDestination = point).toCloudRow("owner")
            assertEquals("GEOAPIFY", row.destinationProvider)
            assertEquals(placeId, row.destinationProviderPlaceId)
            assertEquals(point.providerAttribution, row.destinationProviderAttribution)
            assertEquals(row, Json.decodeFromString<CloudJourneyRow>(Json.encodeToString(row)))
        }
    }

    @Test fun placeSearchPreservesRequiredMinimizedProvenance() {
        val point = DestinationEvidenceFixtures.placeSearch()
        val row = base.copy(structuredDestination = point).toCloudRow("owner")
        val json = Json.encodeToString(row)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("PLACE_SEARCH", row.destinationSelectionSource)
        assertEquals(point.providerPlaceId, row.destinationProviderPlaceId)
        assertEquals(point.providerAttribution, row.destinationProviderAttribution)
        assertTrue(obj.keys.containsAll(evidenceKeys))
        assertEquals(setOf("sourceName", "attribution", "license", "sourceUrl"),
            obj.getValue("destination_provider_attribution").jsonObject.keys)
        assertFalse(json.contains("raw"))
        assertFalse(json.contains("distanceMeters"))
        assertFalse(json.contains("geometry"))
        assertEquals(row, Json.decodeFromString<CloudJourneyRow>(json))
    }

    @Test fun legacyAllStructuredFieldsSerializeNullAndLifecycleStillUploads() {
        val row = base.toCloudRow("owner")
        val obj = Json.parseToJsonElement(Json.encodeToString(row)).jsonObject
        obj.filterKeys { it.startsWith("destination_") }.values.forEach { assertEquals(JsonNull, it) }
        assertTrue(obj.keys.containsAll(evidenceKeys))
        assertEquals(row, Json.decodeFromString<CloudJourneyRow>(Json.encodeToString(row)))
        val terminal = base.copy(status = JourneyStatus.CANCELLED, endedAt = 5_000).toCloudRow("owner")
        assertEquals("CANCELLED", terminal.status)
        assertNotNull(terminal.endedAt)
        assertNull(terminal.destinationKind)
    }

    @Test fun localityContainsCanonicalTypeWithoutPointEvidence() {
        listOf(DestinationEvidenceFixtures.locality(), DestinationEvidenceFixtures.areaCouncil()).forEach { locality ->
            val row = base.copy(structuredDestination = locality).toCloudRow("owner")
            assertEquals("LOCALITY", row.destinationKind)
            assertEquals("LOCALITY_SELECTION", row.destinationSelectionSource)
            assertEquals(locality.localUnitType?.name, row.destinationLocalUnitType)
            assertNull(row.destinationLatitude)
            assertNull(row.destinationLongitude)
            assertNull(row.destinationBoundaryDatasetId)
            assertNull(row.destinationBoundaryDatasetVersion)
            assertNull(row.destinationProvider)
            assertNull(row.destinationProviderPlaceId)
            assertNull(row.destinationProviderAttribution)
            assertNull(row.destinationCustomPlaceName)
            assertEquals(row, Json.decodeFromString<CloudJourneyRow>(Json.encodeToString(row)))
        }
    }

    @Test fun retryFromDurableRoomStatePreservesIdAndAllEvidence() {
        val journey = base.copy(structuredDestination = DestinationEvidenceFixtures.placeSearch())
        val first = journey.toCloudRow("owner")
        val restored = journey.toEntity().toDomain()
        assertEquals(first, restored.toCloudRow("owner"))
        assertEquals(Json.encodeToString(first), Json.encodeToString(restored.toCloudRow("owner")))
    }
}
