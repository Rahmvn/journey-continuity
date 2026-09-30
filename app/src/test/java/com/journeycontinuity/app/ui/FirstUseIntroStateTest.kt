package com.journeycontinuity.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstUseIntroStateTest {
    @Test
    fun startsAtPurposeAndProgressesThroughTheFrozenThreeSteps() {
        val initial = FirstUseStep.initial
        val second = initial.nextOrNull()
        val third = second?.nextOrNull()

        assertEquals(FirstUseStep.PURPOSE, initial)
        assertEquals(FirstUseStep.JOURNEY_SCOPED_MONITORING, second)
        assertEquals(FirstUseStep.PRIVACY_CONTROL, third)
        assertNull(third?.nextOrNull())
    }
}
