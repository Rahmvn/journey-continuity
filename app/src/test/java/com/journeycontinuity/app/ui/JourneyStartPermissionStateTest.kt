package com.journeycontinuity.app.ui

import com.journeycontinuity.app.domain.DestinationEvidenceFixtures
import com.journeycontinuity.app.domain.JourneyStartRequest
import org.junit.Assert.*
import org.junit.Test

class JourneyStartPermissionStateTest {
    private fun request() = JourneyStartRequest("Offa, Kwara", DestinationEvidenceFixtures.locality(), 5_000)
    private val location = JourneyStartPermissionState.Permission.LOCATION
    private val notifications = JourneyStartPermissionState.Permission.NOTIFICATIONS

    @Test fun nativeResultOwnsTheOriginalRequestAndCannotBorrowItsReplacement() {
        val state = JourneyStartPermissionState()
        val original = request()
        val replacement = request() // Equal values are deliberately different identities.
        assertEquals(original, replacement)
        assertTrue(state.begin(original))
        val ticket = state.launch(location, original)!!
        assertTrue(state.begin(original)) // Duplicate handoff leaves the original dialog intact.
        assertFalse(state.begin(replacement)) // New Start must cancel, not replace an outstanding dialog.
        assertNull(state.launch(location, replacement))
        assertSame(original, state.consume(location)!!.request)
        assertNotSame(replacement, ticket.request)
        assertNull(state.consume(location)) // Callback replay is consumed.
        assertTrue(state.begin(replacement))
    }

    @Test fun locationReplayCannotConsumeTheFollowingNotificationDialog() {
        val state = JourneyStartPermissionState()
        val request = request()
        state.begin(request)
        state.launch(location, request)
        state.consume(location)
        val notification = state.launch(notifications, request)
        assertNull(state.consume(location))
        assertTrue(state.inFlight)
        assertSame(notification, state.consume(notifications))
        assertFalse(state.inFlight)
        state.notificationDecided(request)
        assertFalse(state.needsNotificationDecision(request))
    }

    @Test fun cancelledDecisionDoesNotApplyToANewEqualRequestOrProcessRestart() {
        val state = JourneyStartPermissionState()
        val original = request()
        val replacement = request()
        state.begin(original)
        state.notificationDecided(original)
        assertTrue(state.begin(replacement))
        state.notificationDecided(original)
        assertTrue(state.needsNotificationDecision(replacement))
        assertNull(JourneyStartPermissionState().consume(location))
        assertFalse(JourneyStartPermissionState().inFlight)
    }

    @Test fun activeMonitoringPermissionDoesNotOwnANewAdmissionRequest() {
        val state = JourneyStartPermissionState()
        state.launch(location, null)
        assertFalse(state.begin(request()))
        assertNull(state.consume(location)!!.request)
        assertNull(state.consume(location))
    }
}
