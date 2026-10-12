package com.journeycontinuity.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetScrimProgressTest {
    @Test fun scrimFollowsVisibleSheetHeightInBothDirections() {
        val viewport = 1640f
        val sheet = 740f
        assertEquals(1f, sheetVisibleFraction(viewport, sheet, 900f), 0.001f)
        assertEquals(0.5f, sheetVisibleFraction(viewport, sheet, 1270f), 0.001f)
        assertEquals(0f, sheetVisibleFraction(viewport, sheet, viewport), 0.001f)
        // A cancelled drag restores the same opacity as the opening position.
        assertEquals(1f, sheetVisibleFraction(viewport, sheet, 900f), 0.001f)
    }

    @Test fun invalidOrOvershootingOffsetsCannotLeaveAnOpaqueScrim() {
        assertEquals(0f, sheetVisibleFraction(1640f, 0f, 900f), 0f)
        assertEquals(0f, sheetVisibleFraction(1640f, 740f, Float.NaN), 0f)
        assertEquals(0f, sheetVisibleFraction(1640f, 740f, 1800f), 0f)
    }
}
