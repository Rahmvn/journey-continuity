package com.journeycontinuity.app.ui

import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.service.CurrentJourneyConnectivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyProductPresentationTest {
    private val active = Journey("journey-1", "Ilorin", 5_000, 1_000,
        JourneyStatus.ACTIVE, null)

    @Test fun homeStartsFirstAndUsesPersistedActiveJourneyForAction() {
        assertEquals(JourneyProductRoute.HOME, JourneyUiState().productRoute)
        assertEquals("Start Journey", homeJourneyAction(null))
        assertEquals("Open Journey", homeJourneyAction(active))
        assertEquals(JourneyProductRoute.ACTIVE,
            routeAfterJourneyChange(JourneyProductRoute.ACTIVE, active))
        assertEquals(JourneyProductRoute.HOME,
            routeAfterJourneyChange(JourneyProductRoute.START, active))
        assertEquals(JourneyProductRoute.HOME,
            routeAfterJourneyChange(JourneyProductRoute.ACTIVE, null))
        // A new process constructs a new state; no route or Journey is inferred from an old screen.
        assertEquals(JourneyProductRoute.HOME, JourneyUiState(activeJourney = active).productRoute)
    }

    @Test fun startNeedsAnExplicitFutureArrivalAndNonblankDestination() {
        assertFalse(canSubmitJourneyStart("Ilorin", null, 1_000))
        assertFalse(canSubmitJourneyStart("  ", 2_000, 1_000))
        assertFalse(canSubmitJourneyStart("Ilorin", 1_000, 1_000))
        assertFalse(canSubmitJourneyStart("Ilorin", 999, 1_000))
        assertTrue(canSubmitJourneyStart("Ilorin", 2_000, 1_000))
    }

    @Test fun incompleteResilienceIsAdvisoryAndAcceptedContactIsNotStartGate() {
        assertTrue(needsResilienceCheckpoint(false, false))
        assertTrue(needsResilienceCheckpoint(true, false))
        assertFalse(needsResilienceCheckpoint(true, true))
        assertTrue(canSubmitJourneyStart("Ilorin", 2_000, 1_000))
    }

    @Test fun connectivityCopyIsCalmAndRequiresRealFallbackRoute() {
        assertNull(travellerConnectivityCopy(state(ConnectivityPhase.HEALTHY), null, false, false))
        val limited = travellerConnectivityCopy(state(ConnectivityPhase.INTERRUPTED), null, false, true)!!
        assertEquals("Limited connectivity", limited.title)
        assertTrue(limited.detail.contains("may not reach"))
        assertEquals(limited, travellerConnectivityCopy(state(ConnectivityPhase.DEGRADED), null, false, true))
        assertTrue(travellerConnectivityCopy(state(ConnectivityPhase.DEGRADED), null, true, true)!!
            .detail.contains("SMS fallback is available"))
        assertTrue(travellerConnectivityCopy(state(ConnectivityPhase.DEGRADED), null, true, false)!!
            .detail.contains("may not reach"))
        val recovering = travellerConnectivityCopy(state(ConnectivityPhase.RECOVERING),
            CurrentJourneyConnectivity(active.id, true), false, false)!!
        assertEquals("Connection restored", recovering.title)
        assertTrue(recovering.detail.contains("syncing recent information"))
        for (copy in listOf(limited, recovering)) {
            assertFalse(copy.detail.contains("delivered", ignoreCase = true))
            assertFalse(copy.detail.contains("read", ignoreCase = true))
            assertFalse(copy.detail.contains("danger", ignoreCase = true))
        }
    }

    @Test fun persistedRecoveryNeedsCurrentJourneyRuntimeConfirmation() {
        val persisted = state(ConnectivityPhase.RECOVERING)
        val pending = travellerConnectivityCopy(persisted, null, false, false)!!
        assertEquals("Checking connection", pending.title)
        assertFalse(pending.detail.contains("restored", ignoreCase = true))
        assertEquals(pending, travellerConnectivityCopy(persisted,
            CurrentJourneyConnectivity("previous-journey", true), false, false))

        val confirmed = travellerConnectivityCopy(persisted,
            CurrentJourneyConnectivity(active.id, true), false, false)!!
        assertEquals("Connection restored", confirmed.title)

        val offline = travellerConnectivityCopy(persisted,
            CurrentJourneyConnectivity(active.id, false), false, false)!!
        assertEquals("Limited connectivity", offline.title)
        assertFalse(offline.detail.contains("Alabarin is syncing"))
        assertEquals("Limited connectivity", travellerConnectivityCopy(
            state(ConnectivityPhase.HEALTHY),
            CurrentJourneyConnectivity(active.id, false), false, false,
        )?.title)
    }

    @Test fun activeRowAloneNeverProvesBackgroundServiceIsRunning() {
        assertFalse(backgroundMonitoringReady(active, null))
        assertFalse(backgroundMonitoringReady(active, "other-journey"))
        assertTrue(backgroundMonitoringReady(active, active.id))
        assertFalse(backgroundMonitoringReady(null, active.id))
    }

    private fun state(phase: ConnectivityPhase) = DegradedConnectivityState(
        journeyId = active.id,
        journeyActive = true,
        connectivityPhase = phase,
        fallbackBindingProvisioned = true,
        transportAvailable = true,
    )
}
