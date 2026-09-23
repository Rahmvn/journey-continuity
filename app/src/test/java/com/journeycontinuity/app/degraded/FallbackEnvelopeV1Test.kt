package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.domain.ConnectivityState
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FallbackEnvelopeV1Test {
    private val protector = DeterministicTestEnvelopeProtector()

    @Test
    fun protectedFrameRoundTrips() {
        val header = header()
        val body = body()
        val encoded = FallbackEnvelopeV1.encode(
            FallbackEnvelopeV1.protect(header, body, protector),
        )
        val decodedFrame = FallbackEnvelopeV1.decode(encoded.value)
        val decodedBody = FallbackEnvelopeV1.authenticateAndDecrypt(decodedFrame, protector)

        assertHeaderEquals(header, decodedFrame.header)
        assertEquals(body, decodedBody)
    }

    @Test
    fun allMaximumBoundaryValuesRoundTrip() {
        val header = header(
            eventType = FallbackEnvelopeV1.EventType.JOURNEY_COMPLETED,
            keyId = 0xffff_ffffL,
            sequence = 0xffff_ffffL,
            handleByte = 0xff.toByte(),
            nonceByte = 0xfe.toByte(),
        )
        val body = FallbackEnvelopeV1.Body(
            telemetrySequence = Long.MAX_VALUE,
            observationEventTimeUnixSeconds = 0xffff_ffffL,
            latitudeE7 = 900_000_000,
            longitudeE7 = 1_800_000_000,
            accuracyDecimeters = 0xffff,
            batteryPercent = 100,
            charging = true,
            connectivity = ConnectivityState.UNKNOWN,
        )

        val decoded = FallbackEnvelopeV1.authenticateAndDecrypt(
            FallbackEnvelopeV1.decode(
                FallbackEnvelopeV1.encode(
                    FallbackEnvelopeV1.protect(header, body, protector),
                ).value,
            ),
            protector,
        )

        assertEquals(body, decoded)
    }

    @Test
    fun minimumBoundaryAndUnknownValuesRoundTrip() {
        val body = FallbackEnvelopeV1.Body(
            telemetrySequence = 0,
            observationEventTimeUnixSeconds = 0,
            latitudeE7 = -900_000_000,
            longitudeE7 = -1_800_000_000,
            accuracyDecimeters = 0,
            batteryPercent = null,
            charging = null,
            connectivity = ConnectivityState.NONE,
        )
        val frame = FallbackEnvelopeV1.protect(header(keyId = 0, sequence = 1), body, protector)

        assertEquals(body, FallbackEnvelopeV1.authenticateAndDecrypt(frame, protector))
    }

    @Test
    fun malformedPrefixIsRejected() {
        expectIllegalArgument { FallbackEnvelopeV1.decode("JC2.AAAA") }
    }

    @Test
    fun invalidBase64UrlIsRejected() {
        expectIllegalArgument { FallbackEnvelopeV1.decode("JC1.A") }
    }

    @Test
    fun wrongFrameSizeIsRejected() {
        val shortFrame = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(72))
        expectIllegalArgument { FallbackEnvelopeV1.decode("JC1.$shortFrame") }
    }

    @Test
    fun wrongVersionIsRejected() {
        val bytes = ByteArray(FallbackEnvelopeV1.FRAME_BYTES)
        bytes[0] = 0x21
        val encoded = "JC1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        expectIllegalArgument { FallbackEnvelopeV1.decode(encoded) }
    }

    @Test
    fun maximumV1PayloadFitsOneGsm7Segment() {
        val encoded = FallbackEnvelopeV1.encode(
            FallbackEnvelopeV1.protect(
                header(keyId = 0xffff_ffffL, sequence = 0xffff_ffffL),
                body(
                    telemetrySequence = Long.MAX_VALUE,
                    eventTime = 0xffff_ffffL,
                ),
                protector,
            ),
        ).value

        assertEquals(102, encoded.length)
        assertTrue(encoded.length <= FallbackEnvelopeV1.MAX_TEXT_LENGTH)
        assertTrue(encoded.all { it.isLetterOrDigit() || it in "-_." })
    }

    @Test
    fun textualPayloadContainsNoHumanReadableIdentityFields() {
        val encoded = FallbackEnvelopeV1.encode(
            FallbackEnvelopeV1.protect(header(), body(), protector),
        ).value.lowercase()

        listOf(
            "traveller@example.com",
            "bilal",
            "+234",
            "destination",
            "3fb6a1da-48bc-4e4a-8a37-85f839b019c2",
        ).forEach { forbidden -> assertFalse(forbidden in encoded) }
    }

    @Test
    fun changedCiphertextFailsAuthentication() {
        val frame = FallbackEnvelopeV1.protect(header(), body(), protector)
        val changed = frame.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = FallbackEnvelopeV1.ProtectedFrame(
            frame.header,
            changed,
            frame.authenticationTag,
        )

        expectAuthenticationFailure {
            FallbackEnvelopeV1.authenticateAndDecrypt(tampered, protector)
        }
    }

    @Test
    fun samePersistedLogicalAttemptRetainsSequenceAndBytes() {
        val frame = FallbackEnvelopeV1.protect(header(sequence = 7), body(), protector)
        val first = FallbackEnvelopeV1.encode(frame)
        val restored = FallbackEnvelopeV1.encode(FallbackEnvelopeV1.decode(first.value))

        assertEquals(first, restored)
        assertEquals(7L, FallbackEnvelopeV1.decode(restored.value).header.envelopeSequence)
    }

    @Test
    fun protectedFrameDefensivelyCopiesSecuritySensitiveBytes() {
        val handle = ByteArray(FallbackEnvelopeV1.JOURNEY_HANDLE_BYTES) { 1 }
        val nonce = ByteArray(FallbackEnvelopeV1.NONCE_BYTES) { 2 }
        val header = FallbackEnvelopeV1.Header(
            FallbackEnvelopeV1.EventType.OBSERVATION,
            42,
            handle,
            1,
            nonce,
        )
        val frame = FallbackEnvelopeV1.protect(header, body(), protector)
        val encodedBeforeMutation = FallbackEnvelopeV1.encode(frame)

        handle.fill(9)
        nonce.fill(9)
        frame.ciphertext.fill(9)
        frame.authenticationTag.fill(9)
        header.journeyHandle.fill(9)
        header.nonce.fill(9)

        assertEquals(encodedBeforeMutation, FallbackEnvelopeV1.encode(frame))
    }

    @Test
    fun duplicateEnvelopeSequenceIsVisibleEvenWhenNonceAndBytesDiffer() {
        val first = FallbackEnvelopeV1.encode(
            FallbackEnvelopeV1.protect(header(sequence = 9, nonceByte = 1), body(), protector),
        )
        val duplicateSequence = FallbackEnvelopeV1.encode(
            FallbackEnvelopeV1.protect(header(sequence = 9, nonceByte = 2), body(), protector),
        )

        assertNotEquals(first, duplicateSequence)
        assertEquals(
            FallbackEnvelopeV1.decode(first.value).header.envelopeSequence,
            FallbackEnvelopeV1.decode(duplicateSequence.value).header.envelopeSequence,
        )
    }

    @Test
    fun lowerUnseenTelemetrySequenceRemainsRepresentable() {
        val laterArrival = FallbackEnvelopeV1.protect(
            header(sequence = 2, nonceByte = 2),
            body(telemetrySequence = 5),
            protector,
        )
        val earlierArrival = FallbackEnvelopeV1.protect(
            header(sequence = 1, nonceByte = 1),
            body(telemetrySequence = 10),
            protector,
        )

        assertEquals(
            10L,
            FallbackEnvelopeV1.authenticateAndDecrypt(earlierArrival, protector).telemetrySequence,
        )
        assertEquals(
            5L,
            FallbackEnvelopeV1.authenticateAndDecrypt(laterArrival, protector).telemetrySequence,
        )
    }

    @Test
    fun invalidLogicalBoundsAreRejected() {
        expectIllegalArgument { header(sequence = 0) }
        expectIllegalArgument { body(battery = 101) }
        expectIllegalArgument {
            body().copy(latitudeE7 = 900_000_001)
        }
    }

    private fun header(
        eventType: FallbackEnvelopeV1.EventType = FallbackEnvelopeV1.EventType.OBSERVATION,
        keyId: Long = 42,
        sequence: Long = 1,
        handleByte: Byte = 0x11,
        nonceByte: Byte = 0x22,
    ) = FallbackEnvelopeV1.Header(
        eventType = eventType,
        keyId = keyId,
        journeyHandle = ByteArray(FallbackEnvelopeV1.JOURNEY_HANDLE_BYTES) { handleByte },
        envelopeSequence = sequence,
        nonce = ByteArray(FallbackEnvelopeV1.NONCE_BYTES) { nonceByte },
    )

    private fun body(
        telemetrySequence: Long = 123,
        eventTime: Long = 1_700_000_000,
        battery: Int? = 71,
    ) = FallbackEnvelopeV1.Body(
        telemetrySequence = telemetrySequence,
        observationEventTimeUnixSeconds = eventTime,
        latitudeE7 = 90_123_456,
        longitudeE7 = 70_123_456,
        accuracyDecimeters = 125,
        batteryPercent = battery,
        charging = false,
        connectivity = ConnectivityState.CELLULAR,
    )

    private fun assertHeaderEquals(
        expected: FallbackEnvelopeV1.Header,
        actual: FallbackEnvelopeV1.Header,
    ) {
        assertEquals(expected.eventType, actual.eventType)
        assertEquals(expected.keyId, actual.keyId)
        assertArrayEquals(expected.journeyHandle, actual.journeyHandle)
        assertEquals(expected.envelopeSequence, actual.envelopeSequence)
        assertArrayEquals(expected.nonce, actual.nonce)
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun expectAuthenticationFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected EnvelopeAuthenticationException")
        } catch (_: EnvelopeAuthenticationException) {
            // Expected.
        }
    }
}

private class DeterministicTestEnvelopeProtector : EnvelopeProtector {
    override fun protect(
        header: FallbackEnvelopeV1.Header,
        plaintextBody: ByteArray,
    ): ProtectedEnvelopePayload {
        val ciphertext = plaintextBody.map { (it.toInt() xor MASK).toByte() }.toByteArray()
        return ProtectedEnvelopePayload(ciphertext, tag(header, ciphertext))
    }

    override fun authenticateAndDecrypt(
        header: FallbackEnvelopeV1.Header,
        ciphertext: ByteArray,
        authenticationTag: ByteArray,
    ): ByteArray {
        if (!MessageDigest.isEqual(tag(header, ciphertext), authenticationTag)) {
            throw EnvelopeAuthenticationException("Test envelope authentication failed.")
        }
        return ciphertext.map { (it.toInt() xor MASK).toByte() }.toByteArray()
    }

    private fun tag(header: FallbackEnvelopeV1.Header, ciphertext: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(FallbackEnvelopeV1.authenticatedHeaderBytes(header))
            update(ciphertext)
            digest().copyOf(FallbackEnvelopeV1.AUTHENTICATION_TAG_BYTES)
        }

    private companion object {
        const val MASK = 0x5a
    }
}
