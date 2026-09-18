package com.journeycontinuity.app.trusted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrustedContactContractsTest {
    @Test
    fun invitationUrlAddsOneTimeTokenWithoutChangingConfiguredPath() {
        assertEquals(
            "https://trusted.example/view?token=abc123",
            invitationUrl("https://trusted.example/view", "abc123"),
        )
    }

    @Test
    fun invitationUrlPreservesExistingQueryParameters() {
        assertEquals(
            "https://trusted.example/view?source=android&token=abc123",
            invitationUrl("https://trusted.example/view?source=android", "abc123"),
        )
    }

    @Test
    fun invitationUrlRequiresConfiguredViewer() {
        assertThrows(IllegalArgumentException::class.java) { invitationUrl("", "abc123") }
    }
}
