package com.journeycontinuity.app.data.local

import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.DestinationKind
import com.journeycontinuity.app.domain.DestinationSelectionSource
import com.journeycontinuity.app.domain.StructuredDestination
import com.journeycontinuity.app.domain.DestinationEvidenceFixtures
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StructuredDestinationRoomMappingTest {
    private val historical = JourneyEntity(
        id = "historical", destination = "Ilorin", expectedArrivalAt = 5_000,
        startedAt = 1_000, status = JourneyStatus.ACTIVE, completedAt = null,
        activeSlot = 1,
    )

    @Test fun historicalTextOnlyRowRestoresWithoutInventedFacts() {
        check(historical.toDomain().structuredDestination == null)
    }

    @Test fun neutralLocalUnitNamesStillUseCompatibilityRoomColumns() {
        val facts = DestinationEvidenceFixtures.areaCouncil()
        val entity = historical.toDomain().copy(structuredDestination = facts).toEntity()
        assertEquals(facts.localUnitId, entity.destinationLgaId)
        assertEquals(facts.localUnitName, entity.destinationLgaName)
        assertEquals(facts, entity.toDomain().structuredDestination)
    }

    @Test fun allSubtypesRoundTripEveryFrozenFact() {
        listOf(DestinationEvidenceFixtures.mapPin(), DestinationEvidenceFixtures.mapPinWithProvider(),
            DestinationEvidenceFixtures.mapPinWithProvider("reverse-id"), DestinationEvidenceFixtures.placeSearch(),
            DestinationEvidenceFixtures.locality(), DestinationEvidenceFixtures.areaCouncil()).forEach { facts ->
            val entity = historical.toDomain().copy(structuredDestination = facts).toEntity()
            assertEquals(facts.localUnitType?.name, entity.destinationLocalUnitType)
            assertEquals(facts.boundaryDatasetId, entity.destinationBoundaryDatasetId)
            assertEquals(facts.boundaryDatasetVersion, entity.destinationBoundaryDatasetVersion)
            assertEquals(facts, entity.toDomain().structuredDestination)
            assertEquals(entity, entity.toDomain().toEntity())
        }
    }

    @Test fun legacyNewColumnsRemainNull() {
        val entity = historical.toDomain().toEntity()
        assertNull(entity.destinationLocalUnitType)
        assertNull(entity.destinationBoundaryDatasetId)
        assertNull(entity.destinationBoundaryDatasetVersion)
        assertNull(entity.destinationProviderAttribution)
    }

    @Test fun newPartialEvidenceCannotBecomeLegacy() {
        listOf(historical.copy(destinationLocalUnitType = "LGA"),
            historical.copy(destinationBoundaryDatasetId = "NG_OPERATIONAL_BOUNDARIES"),
            historical.copy(destinationBoundaryDatasetVersion = "v1"),
            historical.copy(destinationProviderAttribution = "{}")).forEach { rejects { it.toDomain() } }
    }

    @Test fun incompleteOrCorruptDurableEvidenceFailsClosed() {
        val pin = historical.toDomain().copy(structuredDestination = DestinationEvidenceFixtures.mapPin()).toEntity()
        listOf(pin.copy(destinationLocalUnitType = null), pin.copy(destinationLocalUnitType = "DISTRICT"),
            pin.copy(destinationBoundaryDatasetId = null), pin.copy(destinationBoundaryDatasetVersion = null),
            pin.copy(destinationProviderPlaceId = "fabricated")).forEach { rejects { it.toDomain() } }
        val search = historical.toDomain().copy(structuredDestination = DestinationEvidenceFixtures.placeSearch()).toEntity()
        listOf(null, "{}", "raw-secret-provider-response", "{\"sourceName\":\"raw-secret-provider-response\"}").forEach { bad ->
            rejects { search.copy(destinationProviderAttribution = bad).toDomain() }
            if (bad != null) {
                try { search.copy(destinationProviderAttribution = bad).toDomain(); fail("Must reject malformed attribution") }
                catch (e: IllegalArgumentException) { check(!e.toString().contains("raw-secret-provider-response")) }
            }
        }
    }

    @Test fun partialFactsCannotBecomeHistoricalTextOnlyJourney() {
        rejects { historical.copy(destinationStateId = "NG-KW").toDomain() }
        rejects { historical.copy(destinationLatitude = 8.49,
            destinationLongitude = 4.56).toDomain() }
    }

    @Test fun malformedPointOrLocalityCannotRestore() {
        val facts = historical.copy(
            destinationCountryCode = "NG", destinationStateId = "NG-KW",
            destinationStateName = "Kwara", destinationLgaId = "NG-KW-OFFA",
            destinationLgaName = "Offa",
        )
        rejects { facts.copy(destinationKind = "POINT",
            destinationSelectionSource = "MAP_PIN").toDomain() }
        rejects { facts.copy(destinationKind = "LOCALITY", destinationLatitude = 8.49,
            destinationLongitude = 4.56,
            destinationSelectionSource = "LOCALITY_SELECTION").toDomain() }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Malformed destination facts must fail closed") }
        catch (_: IllegalArgumentException) { /* expected */ }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
