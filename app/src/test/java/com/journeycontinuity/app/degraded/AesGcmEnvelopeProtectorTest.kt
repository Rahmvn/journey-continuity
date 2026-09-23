package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.domain.ConnectivityState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmEnvelopeProtectorTest {
    @Test
    fun aesGcmRoundTripAndSingleSegmentEncoding() {
        val frame = protect(KEY_ONE, header(), body())
        val decoded = AesGcmEnvelopeProtector(KEY_ONE).use { protector ->
            FallbackEnvelopeV1.authenticateAndDecrypt(frame, protector)
        }

        assertEquals(body(), decoded)
        val encoded = FallbackEnvelopeV1.encode(frame).value
        assertEquals(102, encoded.length)
        assertTrue(encoded.length <= FallbackEnvelopeV1.MAX_TEXT_LENGTH)
        assertTrue(encoded.all { it.isLetterOrDigit() || it in "-_." })
    }

    @Test
    fun bodyModificationIsRejected() {
        val frame = protect(KEY_ONE, header(), body())
        val changed = frame.ciphertext.also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertAuthenticationFailure(
            FallbackEnvelopeV1.ProtectedFrame(frame.header, changed, frame.authenticationTag),
            KEY_ONE,
        )
    }

    @Test
    fun authenticatedHeaderModificationIsRejected() {
        val frame = protect(KEY_ONE, header(), body())
        val changedHeader = header(envelopeSequence = 2)

        assertAuthenticationFailure(
            FallbackEnvelopeV1.ProtectedFrame(changedHeader, frame.ciphertext, frame.authenticationTag),
            KEY_ONE,
        )
    }

    @Test
    fun wrongJourneyDerivedKeyIsRejected() {
        val frame = protect(KEY_ONE, header(), body())
        val changedHeader = header(journeyHandle = ByteArray(12) { 9 })

        assertAuthenticationFailure(
            FallbackEnvelopeV1.ProtectedFrame(changedHeader, frame.ciphertext, frame.authenticationTag),
            KEY_ONE,
        )
    }

    @Test
    fun wrongInstallationKeyIsRejected() {
        assertAuthenticationFailure(protect(KEY_ONE, header(), body()), KEY_TWO)
    }

    @Test
    fun differentJourneyHandlesDeriveDifferentKeys() {
        val first = AesGcmEnvelopeProtector.deriveJourneyKey(KEY_ONE, ByteArray(12) { 1 })
        val second = AesGcmEnvelopeProtector.deriveJourneyKey(KEY_ONE, ByteArray(12) { 2 })

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun secureNonceSourceProducesDistinctNinetySixBitValues() {
        val first = SecureFallbackNonceSource.nextNonce()
        val second = SecureFallbackNonceSource.nextNonce()

        assertEquals(12, first.size)
        assertEquals(12, second.size)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun payloadDigestIsStable() {
        val payload = FallbackEnvelopeV1.encode(protect(KEY_ONE, header(), body())).value.toByteArray()
        assertArrayEquals(
            AesGcmEnvelopeProtector.sha256(payload),
            AesGcmEnvelopeProtector.sha256(payload),
        )
    }

    private fun protect(
        key: ByteArray,
        header: FallbackEnvelopeV1.Header,
        body: FallbackEnvelopeV1.Body,
    ) = AesGcmEnvelopeProtector(key).use { protector ->
        FallbackEnvelopeV1.protect(header, body, protector)
    }

    private fun assertAuthenticationFailure(frame: FallbackEnvelopeV1.ProtectedFrame, key: ByteArray) {
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmEnvelopeProtector(key).use { protector ->
                FallbackEnvelopeV1.authenticateAndDecrypt(frame, protector)
            }
        }
    }

    private fun header(
        envelopeSequence: Long = 1,
        journeyHandle: ByteArray = ByteArray(12) { 1 },
    ) = FallbackEnvelopeV1.Header(
        eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
        keyId = 7,
        journeyHandle = journeyHandle,
        envelopeSequence = envelopeSequence,
        nonce = ByteArray(12) { 3 },
    )

    private fun body() = FallbackEnvelopeV1.Body(
        telemetrySequence = 11,
        observationEventTimeUnixSeconds = 1_700_000_000,
        latitudeE7 = 90_765_000,
        longitudeE7 = 73_986_000,
        accuracyDecimeters = 125,
        batteryPercent = 64,
        charging = false,
        connectivity = ConnectivityState.CELLULAR,
    )

    private companion object {
        val KEY_ONE = ByteArray(32) { it.toByte() }
        val KEY_TWO = ByteArray(32) { (it + 1).toByte() }
    }
}
