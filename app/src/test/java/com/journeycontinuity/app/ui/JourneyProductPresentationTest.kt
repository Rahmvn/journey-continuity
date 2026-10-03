package com.journeycontinuity.app.ui

import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.degraded.SmsSubscriptionChoice
import com.journeycontinuity.app.degraded.UnconfiguredSmsFallbackRouteProvider
import com.journeycontinuity.app.degraded.evaluateSmsFallbackStatus
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

    @Test fun normalRequiresCurrentJourneyScopedConnectivityAndPersistedHealth() {
        assertEquals(JourneyConnectivityPresentation.NORMAL, presentation(ConnectivityPhase.HEALTHY,
            CurrentJourneyConnectivity(active.id, true)))
        assertEquals(JourneyConnectivityPresentation.CHECKING_CONNECTION,
            presentation(ConnectivityPhase.HEALTHY, null))
        assertEquals(JourneyConnectivityPresentation.CHECKING_CONNECTION,
            presentation(ConnectivityPhase.HEALTHY, CurrentJourneyConnectivity("old", true)))
        assertEquals(JourneyConnectivityPresentation.CHECKING_CONNECTION,
            travellerConnectivityPresentation(active.id, null,
                CurrentJourneyConnectivity(active.id, true), smsStatus(false), false))
        assertEquals(JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE,
            presentation(ConnectivityPhase.HEALTHY, CurrentJourneyConnectivity(active.id, false)))
        assertNull(JourneyConnectivityPresentation.NORMAL.title)
    }

    @Test fun actualUnconfiguredRouteOverridesStalePersistedTransportFlag() {
        assertNull(UnconfiguredSmsFallbackRouteProvider.currentRoute())
        val status = smsStatus(UnconfiguredSmsFallbackRouteProvider.currentRoute() != null)
        assertFalse(status.ready)
        assertEquals(JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE,
            presentation(ConnectivityPhase.DEGRADED,
                CurrentJourneyConnectivity(active.id, false), status, true))
        // Even an inconsistent stale snapshot cannot make an absent destination available.
        assertEquals(JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE,
            presentation(ConnectivityPhase.DEGRADED,
                CurrentJourneyConnectivity(active.id, false), status.copy(ready = true), true))
    }

    @Test fun configuredRouteAndBoundJourneyCanPresentFutureFallbackWithoutDeliveryClaim() {
        val available = presentation(ConnectivityPhase.DEGRADED,
            CurrentJourneyConnectivity(active.id, false), smsStatus(true), true)
        assertEquals(JourneyConnectivityPresentation.LIMITED_FALLBACK_AVAILABLE, available)
        assertTrue(available.detail!!.contains("available"))
        assertFalse(available.detail.contains("delivered", ignoreCase = true))
        assertEquals(JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE,
            presentation(ConnectivityPhase.DEGRADED,
                CurrentJourneyConnectivity(active.id, false), smsStatus(true), false))
        assertEquals(JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE,
            travellerConnectivityPresentation(active.id,
                state(ConnectivityPhase.DEGRADED).copy(fallbackBindingProvisioned = false),
                CurrentJourneyConnectivity(active.id, false), smsStatus(true), true))
    }

    @Test fun recoveryWaitsForCurrentNetworkAndClaimsNeitherSyncNorSafety() {
        val pending = presentation(ConnectivityPhase.RECOVERING, null)
        assertEquals(JourneyConnectivityPresentation.CHECKING_CONNECTION, pending)
        assertFalse(pending.detail!!.contains("offline", ignoreCase = true))
        val recovering = presentation(ConnectivityPhase.RECOVERING,
            CurrentJourneyConnectivity(active.id, true))
        assertEquals(JourneyConnectivityPresentation.RECOVERING, recovering)
        for (claim in listOf("syncing", "synced", "contacted", "delivered", "safe", "arrived")) {
            assertFalse(recovering.detail!!.contains(claim, ignoreCase = true))
        }
        assertEquals(JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE,
            presentation(ConnectivityPhase.RECOVERING,
                CurrentJourneyConnectivity(active.id, false)))
        assertEquals(JourneyStatus.ACTIVE, active.status)
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

    private fun presentation(
        phase: ConnectivityPhase,
        current: CurrentJourneyConnectivity?,
        status: com.journeycontinuity.app.degraded.SmsFallbackStatus = smsStatus(false),
        hasAcceptedContact: Boolean = false,
    ) = travellerConnectivityPresentation(active.id, state(phase), current, status, hasAcceptedContact)

    private fun smsStatus(destinationConfigured: Boolean) = evaluateSmsFallbackStatus(
        sendPermissionGranted = true,
        phoneStatePermissionGranted = true,
        telephonyMessagingSupported = true,
        activeSubscriptions = listOf(SmsSubscriptionChoice(1, "SIM 1")),
        selectedSubscriptionId = 1,
        destinationConfigured = destinationConfigured,
    )
}
