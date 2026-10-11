package com.journeycontinuity.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyRuntimeSignalsTest {
    @Test fun delayedOldStopCannotStopNewJourneyOrItsListener() {
        val signals = JourneyRuntimeSignals()
        signals.serviceStarted(); signals.startCommand(1)
        signals.begin("journey-a")
        val ticket = requireNotNull(signals.stopTicket("journey-a"))
        signals.startCommand(2)
        val newer = signals.begin("journey-b")
        signals.listenerRegistered(newer, "journey-b")
        repeat(2) {
            assertTrue(signals.executeStop(ticket, { error("Old Android stop must not execute") },
                { error("New collection must not stop") }))
        }
        signals.stopRegistration(ticket.generation)
        assertEquals("journey-b", signals.monitoringJourneyId.value)
    }

    @Test fun queuedNewStartAndAndroidRefusalDoNotStopCollection() {
        val signals = JourneyRuntimeSignals()
        signals.serviceStarted(); signals.startCommand(1); signals.begin("journey-a")
        val ticket = requireNotNull(signals.stopTicket("journey-a"))
        signals.startCommand(2)
        assertFalse(signals.executeStop(ticket, { error("Stale start ID") }, { error("Collection") }))
        val current = requireNotNull(signals.stopTicket("journey-a"))
        assertFalse(signals.executeStop(current, { false }, { error("Android rejected stop") }))
        assertEquals("journey-a", signals.activeRuntimeJourneyId.value)
    }

    @Test fun privacyStopClosesExactListenerDespiteNewStartIdWithoutStoppingService() {
        val signals = JourneyRuntimeSignals()
        signals.serviceStarted(); signals.startCommand(1)
        val a = signals.begin("journey-a")
        signals.listenerRegistered(a, "journey-a")
        val ticket = requireNotNull(signals.stopTicket("journey-a"))
        signals.startCommand(2)
        var removed = 0
        assertTrue(signals.executePrivacyStop(ticket,
            stopCollection = {
                removed++
                signals.stopRegistration(a)
            },
            stopSelfResult = { error("New start ID must retain its service") },
            stopForeground = { error("New start ID must retain its foreground") }))
        assertEquals(1, removed)
        assertNull(signals.monitoringJourneyId.value)
        assertTrue(signals.serviceRunning.value)
        val b = signals.begin("journey-b")
        signals.listenerRegistered(b, "journey-b")
        assertTrue(signals.executePrivacyStop(ticket,
            stopCollection = { error("Old A cannot remove B") },
            stopSelfResult = { error("Old A cannot stop B") },
            stopForeground = { error("Old A cannot remove B's foreground") }))
        assertEquals("journey-b", signals.monitoringJourneyId.value)
    }

    @Test fun privacyStopClosesListenerBeforeStoppingOnlyItsCurrentStartId() {
        val signals = JourneyRuntimeSignals()
        signals.serviceStarted(); signals.startCommand(3)
        val a = signals.begin("journey-a")
        signals.listenerRegistered(a, "journey-a")
        val ticket = requireNotNull(signals.stopTicket("journey-a"))
        var foregroundRemoved = 0
        assertTrue(signals.executePrivacyStop(ticket,
            stopCollection = { signals.stopRegistration(a) },
            stopSelfResult = { startId ->
                assertEquals(3, startId)
                assertNull(signals.monitoringJourneyId.value)
                true
            },
            stopForeground = { foregroundRemoved++ }))
        assertEquals(1, foregroundRemoved)
        assertTrue(signals.executePrivacyStop(ticket,
            stopCollection = { error("Duplicate stop") },
            stopSelfResult = { error("Duplicate Android stop") },
            stopForeground = { error("Duplicate foreground removal") }))
    }

    @Test fun oldServiceDestructionCannotClearRestoredServiceSignals() {
        val signals = JourneyRuntimeSignals()
        val oldService = signals.serviceStarted(); signals.startCommand(1); signals.begin("journey-a")
        val ticket = requireNotNull(signals.stopTicket("journey-a"))
        signals.serviceStarted(); signals.startCommand(1)
        val newer = signals.begin("journey-b"); signals.listenerRegistered(newer, "journey-b")
        signals.serviceStopped(oldService); signals.stopRegistration(ticket.generation)
        assertTrue(signals.executeStop(ticket, { error("Old service") }, { error("New listener") }))
        assertTrue(signals.serviceRunning.value)
        assertEquals("journey-b", signals.monitoringJourneyId.value)
    }

    @Test fun exactCurrentTicketStopsOnlyItsOwnRegistrationOnce() {
        val signals = JourneyRuntimeSignals()
        signals.serviceStarted(); signals.startCommand(7)
        val registration = signals.begin("journey-a"); signals.listenerRegistered(registration, "journey-a")
        val ticket = requireNotNull(signals.stopTicket("journey-a"))
        var stopped = 0
        assertTrue(signals.executeStop(ticket, { assertEquals(7, it); true }, {
            stopped++; signals.stopRegistration(registration)
        }))
        assertTrue(signals.executeStop(ticket, { error("Duplicate Android stop") }, { stopped++ }))
        assertEquals(1, stopped); assertNull(signals.monitoringJourneyId.value)
    }
    @Test fun collectionStopDoesNotClaimForegroundServiceDestroyed() {
        val signals = JourneyRuntimeSignals()
        signals.serviceStarted()
        signals.begin("journey-a")
        assertTrue(signals.serviceRunning.value)
        assertEquals("journey-a", signals.activeRuntimeJourneyId.value)
        signals.stop()
        assertNull(signals.activeRuntimeJourneyId.value)
        assertTrue(signals.serviceRunning.value)
        signals.serviceStopped()
        assertFalse(signals.serviceRunning.value)
    }

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
        assertFalse(signals.isRegistrationCurrent(repeated, "journey-a"))
        assertTrue(signals.isRegistrationCurrent(later, "journey-b"))
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
