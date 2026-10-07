package com.journeycontinuity.app.domain.probableend

import org.junit.Assert.*
import org.junit.Test

class ProbableEndPolicyTest {
    @Test fun accurateNearFixQualifies() = assertTrue(ProbableEndPolicy.isWithinProximity(35.0, 8.0))
    @Test fun poorAccuracyDoesNotExpandArea() = assertFalse(ProbableEndPolicy.isWithinProximity(35.0, 100.0))
    @Test fun uncertaintyBoundaryIsInclusive() = assertTrue(ProbableEndPolicy.isWithinProximity(65.0, 35.0))
    @Test fun justBeyondUncertaintyBoundaryFails() = assertFalse(ProbableEndPolicy.isWithinProximity(65.000001, 35.0))
    @Test fun maximumAccuracyIsInclusive() = assertTrue(ProbableEndPolicy.isWithinProximity(50.0, 50.0))
    @Test fun aboveMaximumAccuracyFailsEvenAtDestination() = assertFalse(ProbableEndPolicy.isWithinProximity(0.0, 50.000001))
    @Test fun zeroNegativeAndNonfiniteAccuracyFails() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertFalse(ProbableEndPolicy.hasQualifyingAccuracy(it))
            assertFalse(ProbableEndPolicy.isWithinProximity(0.0, it))
            assertFalse(ProbableEndPolicy.isReliableExit(500.0, it))
        }
    }
    @Test fun invalidDistancesNeverQualify() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertFalse(ProbableEndPolicy.isWithinProximity(it, 8.0))
            assertFalse(ProbableEndPolicy.isReliableExit(it, 8.0))
        }
    }
    @Test fun uncertaintyOverlapIsNotReliableExit() = assertFalse(ProbableEndPolicy.isReliableExit(110.0, 20.0))
    @Test fun exactExitBoundaryIsExcluded() = assertFalse(ProbableEndPolicy.isReliableExit(120.0, 20.0))
    @Test fun beyondExitBoundaryIsReliableExit() = assertTrue(ProbableEndPolicy.isReliableExit(120.000001, 20.0))
}
