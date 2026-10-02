package com.journeycontinuity.app.trusted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrustedContactContractsTest {
    @Test
    fun sharingConfigurationMustBeUsableBeforeCreate() {
        org.junit.Assert.assertFalse(viewerShareConfigurationAvailable(""))
        org.junit.Assert.assertFalse(viewerShareConfigurationAvailable("/relative"))
        org.junit.Assert.assertFalse(viewerShareConfigurationAvailable("http://viewer.example/view"))
        org.junit.Assert.assertFalse(viewerShareConfigurationAvailable("https://viewer.example/#fragment"))
        org.junit.Assert.assertTrue(viewerShareConfigurationAvailable("https://viewer.example/view"))
        org.junit.Assert.assertTrue(viewerShareConfigurationAvailable("HTTPS://viewer.example/view"))
    }

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
        assertThrows(IllegalArgumentException::class.java) {
            invitationUrl("http://viewer.example/view", "abc123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            invitationUrl("/relative", "abc123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            invitationUrl("https://viewer.example/view#fragment", "abc123")
        }
    }
}
