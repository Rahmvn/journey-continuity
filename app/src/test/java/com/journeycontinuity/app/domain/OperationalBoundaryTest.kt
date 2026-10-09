package com.journeycontinuity.app.domain

import org.junit.Assert.*
import org.junit.Test

class OperationalBoundaryTest {
    private val admission = BoundaryAdmission(
        "20000000-0000-4000-8000-000000000010",
        "30000000-0000-4000-8000-000000000010",
        1_000_000L, 1_001_000L, 87_401_000L,
    )
    private val anchor = BoundaryReceipt(500L, 7, "installation")

    @Test fun nominalCapHasExactBoundaryWithoutCreatingUtcProof() {
        val duration = admission.boundaryMillis - admission.admittedAtMillis
        assertEquals(false, approximateOperationalCapReached(admission, anchor,
            anchor.copy(elapsedRealtimeMillis = 500L + duration - 1)))
        assertEquals(true, approximateOperationalCapReached(admission, anchor,
            anchor.copy(elapsedRealtimeMillis = 500L + duration)))
        assertEquals(true, approximateOperationalCapReached(admission, anchor,
            anchor.copy(elapsedRealtimeMillis = 500L + duration + 1)))
        assertEquals(BoundaryProof.INSUFFICIENT_PROOF,
            boundaryProof(admission, admission.boundaryMillis - 1))
    }

    @Test fun lostOrCorruptAnchorCannotYieldAnElapsedVerdict() {
        assertNull(approximateOperationalCapReached(admission, anchor,
            BoundaryReceipt(99_999_999L, 8, "installation")))
        assertNull(approximateOperationalCapReached(admission, anchor,
            BoundaryReceipt(99_999_999L, null, "installation")))
        assertNull(approximateOperationalCapReached(admission, anchor,
            BoundaryReceipt(99_999_999L, 7, "different-installation")))
        assertNull(approximateOperationalCapReached(admission, anchor,
            BoundaryReceipt(499L, 7, "installation")))
        assertNull(approximateOperationalCapReached(admission.copy(boundaryMillis = Long.MAX_VALUE),
            anchor, BoundaryReceipt(99_999_999L, 7, "installation")))
    }

}
