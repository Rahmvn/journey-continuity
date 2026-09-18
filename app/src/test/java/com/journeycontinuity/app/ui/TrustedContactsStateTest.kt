package com.journeycontinuity.app.ui

import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.SyncFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrustedContactsStateTest {
    @Test
    fun temporaryAuthFailureIsUnavailableRatherThanSuccessfulEmptyContacts() {
        val state = JourneyUiState().withTrustedContactsFailure(
            CloudSyncException(
                SyncFailureKind.TRANSIENT,
                "Cloud authentication is temporarily unavailable while reconnecting.",
            ),
        )

        assertEquals(TrustedContactsAvailability.UNAVAILABLE, state.trustedContactsAvailability)
        assertEquals("Trusted contacts unavailable while reconnecting.", state.trustedContactsUnavailableMessage)
        assertFalse(state.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE)
    }

    @Test
    fun identityFailureUsesExplicitBlockedMessageRatherThanEmptySuccess() {
        val state = JourneyUiState().withTrustedContactsFailure(
            CloudSyncException(
                SyncFailureKind.AUTHENTICATION,
                "Traveller identity recovery is required before cloud access can resume.",
            ),
        )

        assertEquals(TrustedContactsAvailability.UNAVAILABLE, state.trustedContactsAvailability)
        assertEquals(
            "Trusted contacts unavailable because traveller identity needs attention.",
            state.trustedContactsUnavailableMessage,
        )
    }
}
