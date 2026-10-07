package com.journeycontinuity.app.domain.probableend

import org.junit.Assert.*
import org.junit.Test

class GeographicDistanceTest {
    @Test fun identicalPoint() = assertEquals(0.0, GeographicDistance.meters(8.15, 4.72, 8.15, 4.72), 0.0)

    @Test fun knownEquatorialDegree() = assertEquals(111_195.0802335,
        GeographicDistance.meters(0.0, 0.0, 0.0, 1.0), 0.00001)

    @Test fun knownLondonNewYorkDistance() = assertEquals(5_570_230.0,
        GeographicDistance.meters(51.5074, -0.1278, 40.7128, -74.0060), 10.0)

    @Test fun symmetric() = assertEquals(GeographicDistance.meters(8.15, 4.72, -35.0, 150.0),
        GeographicDistance.meters(-35.0, 150.0, 8.15, 4.72), 0.000001)

    @Test fun crossesAntimeridianViaShortArc() = assertEquals(222.3901605,
        GeographicDistance.meters(0.0, 179.999, 0.0, -179.999), 0.00001)

    @Test fun longitudeEndpointsRepresentSamePoint() = assertEquals(0.0,
        GeographicDistance.meters(0.0, -180.0, 0.0, 180.0), 0.0)

    @Test fun antipodesRemainFiniteWithClamping() = assertEquals(Math.PI * GeographicDistance.EARTH_RADIUS_METERS,
        GeographicDistance.meters(0.0, 0.0, 0.0, 180.0), 0.000001)

    @Test fun legalCoordinateBoundaries() {
        assertTrue(GeographicDistance.isValidCoordinate(-90.0, -180.0))
        assertTrue(GeographicDistance.isValidCoordinate(90.0, 180.0))
        assertTrue(GeographicDistance.meters(-90.0, -180.0, 90.0, 180.0).isFinite())
    }

    @Test fun invalidCoordinatesFailWithoutIncludingValuesInError() {
        val invalid = listOf(90.0001 to 0.0, -90.0001 to 0.0, 0.0 to 180.0001, 0.0 to -180.0001,
            Double.NaN to 0.0, Double.POSITIVE_INFINITY to 0.0, 0.0 to Double.NEGATIVE_INFINITY)
        invalid.forEach { (latitude, longitude) ->
            assertFalse(GeographicDistance.isValidCoordinate(latitude, longitude))
            for (first in listOf(true, false)) {
                try {
                    if (first) GeographicDistance.meters(latitude, longitude, 0.0, 0.0)
                    else GeographicDistance.meters(0.0, 0.0, latitude, longitude)
                    fail("Invalid coordinate accepted")
                } catch (expected: IllegalArgumentException) {
                    assertEquals(if (first) "Invalid first geographic coordinate." else
                        "Invalid second geographic coordinate.", expected.message)
                }
            }
        }
    }
}
