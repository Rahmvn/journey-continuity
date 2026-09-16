package com.journeycontinuity.app.sync

import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncDiagnosticsTest {
    @Test
    fun validPublishableConfigurationIsRecognizedWithoutExposingItsValue() {
        val configuration = SupabaseConfiguration(
            url = "https://abcdefghijklmnopqrst.supabase.co",
            publishableKey = "sb_publishable_secret-marker",
        )

        assertTrue(configuration.isConfigured)
        assertTrue(configuration.isHttpsUrl)
        assertTrue(configuration.hasExpectedProjectHostShape)
        assertEquals("sb_publishable", configuration.keyCategory)
        assertNull(configuration.validationError)
    }

    @Test
    fun invalidConfigurationIsDistinguishedBeforeClientInitialization() {
        val configuration = SupabaseConfiguration(
            url = "http://not-supabase.example",
            publishableKey = "unknown-secret-marker",
        )

        assertFalse(configuration.isHttpsUrl)
        assertEquals("unrecognized", configuration.keyCategory)
        assertEquals("Supabase URL is not a valid HTTPS URL.", configuration.validationError)
    }

    @Test
    fun dnsFailureIsRetryableAndDoesNotCopyRawExceptionMessage() {
        val failure = UnknownHostException("secret-marker").toCloudSyncException(
            CloudStage.ANONYMOUS_SIGN_IN,
        )

        assertEquals(SyncFailureKind.TRANSIENT, failure.kind)
        assertTrue(failure.safeMessage.contains("DNS lookup"))
        assertFalse(failure.safeMessage.contains("secret-marker"))
    }

    @Test
    fun tlsFailureIsDistinguishedFromGenericTransportFailure() {
        val failure = SSLHandshakeException("secret-marker").toCloudSyncException(
            CloudStage.ANONYMOUS_SIGN_IN,
        )

        assertEquals(SyncFailureKind.TRANSIENT, failure.kind)
        assertTrue(failure.safeMessage.contains("TLS connection"))
        assertFalse(failure.safeMessage.contains("secret-marker"))
    }

    @Test
    fun genericIoFailureIsReportedAsNetworkTransport() {
        val failure = IOException("secret-marker").toCloudSyncException(
            CloudStage.JOURNEY_UPSERT,
        )

        assertEquals(SyncFailureKind.TRANSIENT, failure.kind)
        assertTrue(failure.safeMessage.contains("network transport"))
        assertFalse(failure.safeMessage.contains("secret-marker"))
    }
}
