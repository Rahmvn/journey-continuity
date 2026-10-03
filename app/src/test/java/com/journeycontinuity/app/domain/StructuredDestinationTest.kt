package com.journeycontinuity.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class StructuredDestinationTest {
    private fun destination(
        kind: DestinationKind = DestinationKind.POINT,
        latitude: Double? = 8.49,
        longitude: Double? = 4.56,
        source: DestinationSelectionSource = DestinationSelectionSource.MAP_PIN,
    ) = StructuredDestination(
        countryCode = "NG", stateId = "NG-KW", stateName = "Kwara",
        localUnitId = "NG-KW-OFFA", localUnitName = "Offa", kind = kind,
        latitude = latitude, longitude = longitude, selectionSource = source,
        customPlaceName = if (source == DestinationSelectionSource.MAP_PIN) "Grandma's house" else null,
    )

    @Test fun pointPreservesExactCoordinatesAndCustomName() {
        val point = destination()
        assertEquals(8.49, point.latitude!!, 0.0)
        assertEquals(4.56, point.longitude!!, 0.0)
        assertEquals("Grandma's house", point.customPlaceName)
        assertEquals("StructuredDestination(redacted)", point.toString())
    }

    @Test fun placeSearchPointIsValid() {
        val point = destination(source = DestinationSelectionSource.PLACE_SEARCH)
        assertEquals(DestinationSelectionSource.PLACE_SEARCH, point.selectionSource)
        assertNull(point.customPlaceName)
    }

    @Test fun pointRequiresBothCoordinates() {
        rejects { destination(latitude = null) }
        rejects { destination(longitude = null) }
    }

    @Test fun coordinateRangesMustBeFiniteAndValid() {
        rejects { destination(latitude = 90.001) }
        rejects { destination(latitude = Double.NaN) }
        rejects { destination(longitude = -180.001) }
        rejects { destination(longitude = Double.POSITIVE_INFINITY) }
    }

    @Test fun localityIsValidWithoutInventingAPoint() {
        val locality = destination(DestinationKind.LOCALITY, null, null,
            DestinationSelectionSource.LOCALITY_SELECTION)
        assertNull(locality.latitude)
        assertNull(locality.longitude)
    }

    @Test fun localityRejectsAnyCoordinate() {
        rejects { destination(DestinationKind.LOCALITY, 8.49, 4.56,
            DestinationSelectionSource.LOCALITY_SELECTION) }
        rejects { destination(DestinationKind.LOCALITY, 8.49, null,
            DestinationSelectionSource.LOCALITY_SELECTION) }
        rejects { destination(DestinationKind.LOCALITY, null, 4.56,
            DestinationSelectionSource.LOCALITY_SELECTION) }
    }

    @Test fun requiredNamesAndMetadataCannotBeMeaningless() {
        rejects { destination().copy(stateName = " ") }
        rejects { destination().copy(localUnitId = " NG-KW-OFFA ") }
        rejects { destination().copy(providerPlaceId = "place-1") }
        rejects { destination().copy(countryCode = "GH") }
        rejects { destination().copy(stateId = " ") }
        rejects { destination().copy(localUnitName = " ") }
        rejects { destination().copy(customPlaceName = " ") }
        rejects { destination(source = DestinationSelectionSource.PLACE_SEARCH)
            .copy(customPlaceName = "Grandma's house") }
    }

    @Test fun sourceAndKindMustBeConsistent() {
        rejects { destination(DestinationKind.LOCALITY, null, null, DestinationSelectionSource.MAP_PIN) }
        rejects { destination(DestinationKind.POINT, 8.49, 4.56,
            DestinationSelectionSource.LOCALITY_SELECTION) }
        rejects { destination(DestinationKind.LOCALITY, null, null,
            DestinationSelectionSource.PLACE_SEARCH) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid structured destination to be rejected") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }
}
