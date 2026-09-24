package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.domain.ConnectivityState
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Reads the same synthetic fixture as jc1_interoperability_node.test.mjs. */
class Jc1InteroperabilityTest {
    private val fixture = Properties().apply {
        Jc1InteroperabilityTest::class.java
            .getResourceAsStream("/jc1-v1-interoperability.properties")!!.use(::load)
    }

    @Test
    fun hkdfMatchesSharedExpectedBytes() {
        assertArrayEquals(
            bytes("derivedKeyHex"),
            AesGcmEnvelopeProtector.deriveJourneyKey(bytes("masterKeyHex"), bytes("journeyHandleHex")),
        )
    }

    @Test
    fun productionEncoderAndDecoderMatchSharedFrameExactly() {
        AesGcmEnvelopeProtector(bytes("masterKeyHex")).use { protector ->
            val frame = FallbackEnvelopeV1.protect(header(), body(), protector)
            val aad = FallbackEnvelopeV1.authenticatedHeaderBytes(frame.header)
            assertArrayEquals(bytes("headerHex"), aad)
            assertArrayEquals(bytes("headerSha256"), MessageDigest.getInstance("SHA-256").digest(aad))
            val encoded = FallbackEnvelopeV1.encode(frame).value
            assertEquals(102, encoded.length)
            assertEquals(value("jc1"), encoded)
            val decodedBytes = Base64.getUrlDecoder().decode(encoded.removePrefix("JC1."))
            assertEquals(73, decodedBytes.size)
            assertArrayEquals(bytes("frameHex"), decodedBytes)
            assertEquals(
                body(),
                FallbackEnvelopeV1.authenticateAndDecrypt(
                    FallbackEnvelopeV1.decode(value("jc1")), protector,
                ),
            )
        }
    }

    @Test
    fun sharedFrameHeaderNonceCiphertextAndTagTamperingFail() {
        listOf(17, 21, 33, 72).forEach { offset ->
            val changed = bytes("frameHex")
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            val text = "JC1." + Base64.getUrlEncoder().withoutPadding().encodeToString(changed)
            AesGcmEnvelopeProtector(bytes("masterKeyHex")).use { protector ->
                assertThrows(EnvelopeAuthenticationException::class.java) {
                    FallbackEnvelopeV1.authenticateAndDecrypt(FallbackEnvelopeV1.decode(text), protector)
                }
            }
        }
    }

    private fun header() = FallbackEnvelopeV1.Header(
        eventType = FallbackEnvelopeV1.EventType.valueOf(value("eventType")),
        keyId = value("keyId").toLong(),
        journeyHandle = bytes("journeyHandleHex"),
        envelopeSequence = value("envelopeSequence").toLong(),
        nonce = bytes("nonceHex"),
    )

    private fun body() = FallbackEnvelopeV1.Body(
        telemetrySequence = value("telemetrySequence").toLong(),
        observationEventTimeUnixSeconds = value("eventTimeUnixSeconds").toLong(),
        latitudeE7 = value("latitudeE7").toInt(),
        longitudeE7 = value("longitudeE7").toInt(),
        accuracyDecimeters = value("accuracyDecimeters").toInt(),
        batteryPercent = value("batteryPercent").toInt(),
        charging = value("charging").toBooleanStrict(),
        connectivity = ConnectivityState.valueOf(value("connectivity")),
    )

    private fun value(name: String): String = fixture.getProperty(name)!!
    private fun bytes(name: String) = value(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
