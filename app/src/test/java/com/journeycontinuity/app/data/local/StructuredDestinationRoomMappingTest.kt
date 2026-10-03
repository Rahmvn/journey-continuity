package com.journeycontinuity.app.data.local

import com.journeycontinuity.app.domain.JourneyStatus
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
