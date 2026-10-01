package com.journeycontinuity.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyRuntimeSignalsTest {
    @Test fun listenerSuccessPublishesReadinessAndStopClearsIt() {
        val signals = JourneyRuntimeSignals()
        val registration = signals.begin("journey-a")
        assertNull(signals.monitoringJourneyId.value)

        signals.listenerRegistered(registration, "journey-a")
        assertEquals("journey-a", signals.monitoringJourneyId.value)
        signals.connectivityReconciled(registration, "journey-a", true)
        assertEquals(CurrentJourneyConnectivity("journey-a", true),
            signals.currentConnectivity.value)

        signals.stop()
        assertNull(signals.monitoringJourneyId.value)
        assertNull(signals.currentConnectivity.value)
    }

    @Test fun failedRegistrationNeverPublishesReadiness() {
        val signals = JourneyRuntimeSignals()
        val registration = signals.begin("journey-a")
        // The service's registration failure path calls stop before any success callback.
        signals.stop()
        signals.listenerRegistered(registration, "journey-a")
        assertNull(signals.monitoringJourneyId.value)
        assertNull(signals.currentConnectivity.value)
    }

    @Test fun repeatedRegistrationAndNewJourneyRejectOldCallbacks() {
        val signals = JourneyRuntimeSignals()
        val first = signals.begin("journey-a")
        signals.listenerRegistered(first, "journey-a")

        val repeated = signals.begin("journey-a")
        assertNull(signals.monitoringJourneyId.value)
        signals.listenerRegistered(first, "journey-a")
        signals.connectivityReconciled(first, "journey-a", true)
        assertNull(signals.monitoringJourneyId.value)
        assertNull(signals.currentConnectivity.value)

        signals.listenerRegistered(repeated, "journey-a")
        val later = signals.begin("journey-b")
        assertNull(signals.monitoringJourneyId.value)
        signals.listenerRegistered(repeated, "journey-a")
        signals.connectivityReconciled(repeated, "journey-a", true)
        assertNull(signals.monitoringJourneyId.value)
        assertNull(signals.currentConnectivity.value)

        signals.listenerRegistered(later, "journey-b")
        assertEquals("journey-b", signals.monitoringJourneyId.value)
        signals.connectivityReconciled(later, "journey-b", false)
        assertEquals(CurrentJourneyConnectivity("journey-b", false),
            signals.currentConnectivity.value)
    }

    @Test fun startupSnapshotCannotOverwriteNewerNetworkCallback() {
        val signals = JourneyRuntimeSignals()
        val registration = signals.begin("journey-a")
        signals.connectivityReconciled(registration, "journey-a", false)
        signals.initialConnectivityReconciled(registration, "journey-a", true)
        assertEquals(CurrentJourneyConnectivity("journey-a", false),
            signals.currentConnectivity.value)

        signals.stop()
        val restarted = signals.begin("journey-a")
        signals.initialConnectivityReconciled(restarted, "journey-a", true)
        assertEquals(CurrentJourneyConnectivity("journey-a", true),
            signals.currentConnectivity.value)
    }
}
