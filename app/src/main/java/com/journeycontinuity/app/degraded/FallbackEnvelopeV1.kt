package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.domain.ConnectivityState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object FallbackEnvelopeV1 {
    const val TEXT_PREFIX = "JC1."
    const val MAX_TEXT_LENGTH = 160
    const val JOURNEY_HANDLE_BYTES = 12
    const val NONCE_BYTES = 12
    const val BODY_BYTES = 24
    const val AUTHENTICATION_TAG_BYTES = 16
    const val FRAME_BYTES = 73

    private const val VERSION = 1
    private const val HEADER_BYTES = 33
    private const val UNKNOWN_BATTERY = 0xff
    private const val RESERVED_FLAG_MASK = 0xe0
    private val gsm7Safe =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_."

    enum class EventType(val wireValue: Int) {
        OBSERVATION(1),
        JOURNEY_COMPLETED(2),
        ;

        companion object {
            fun fromWire(value: Int): EventType = entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported fallback event type: $value")
        }
    }

    class Header(
        val eventType: EventType,
        val keyId: Long,
        journeyHandle: ByteArray,
        val envelopeSequence: Long,
        nonce: ByteArray,
    ) {
        private val journeyHandleBytes = journeyHandle.copyOf()
        private val nonceBytes = nonce.copyOf()

        val journeyHandle: ByteArray
            get() = journeyHandleBytes.copyOf()

        val nonce: ByteArray
            get() = nonceBytes.copyOf()

        init {
            require(keyId in 0..UINT32_MAX)
            require(journeyHandleBytes.size == JOURNEY_HANDLE_BYTES)
            require(envelopeSequence in 1..UINT32_MAX)
            require(nonceBytes.size == NONCE_BYTES)
        }
    }

    data class Body(
        val telemetrySequence: Long,
        val observationEventTimeUnixSeconds: Long,
        val latitudeE7: Int,
        val longitudeE7: Int,
        val accuracyDecimeters: Int,
        val batteryPercent: Int?,
        val charging: Boolean?,
        val connectivity: ConnectivityState,
    ) {
        init {
            require(telemetrySequence >= 0)
            require(observationEventTimeUnixSeconds in 0..UINT32_MAX)
            require(latitudeE7 in -900_000_000..900_000_000)
            require(longitudeE7 in -1_800_000_000..1_800_000_000)
            require(accuracyDecimeters in 0..0xffff)
            require(batteryPercent == null || batteryPercent in 0..100)
        }
    }

    class ProtectedFrame(
        val header: Header,
        ciphertext: ByteArray,
        authenticationTag: ByteArray,
    ) {
        private val ciphertextBytes = ciphertext.copyOf()
        private val authenticationTagBytes = authenticationTag.copyOf()

        val ciphertext: ByteArray
            get() = ciphertextBytes.copyOf()

        val authenticationTag: ByteArray
            get() = authenticationTagBytes.copyOf()

        init {
            require(ciphertextBytes.size == BODY_BYTES)
            require(authenticationTagBytes.size == AUTHENTICATION_TAG_BYTES)
        }
    }

    @JvmInline
    value class Encoded(val value: String) {
        init {
            require(value.length <= MAX_TEXT_LENGTH)
        }

        override fun toString(): String = value
    }

    fun protect(
        header: Header,
        body: Body,
        protector: EnvelopeProtector,
    ): ProtectedFrame {
        val plaintext = encodeBody(body)
        val protected = protector.protect(header, plaintext)
        return ProtectedFrame(header, protected.ciphertext, protected.authenticationTag)
    }

    fun authenticateAndDecrypt(
        frame: ProtectedFrame,
        protector: EnvelopeProtector,
    ): Body {
        val plaintext = protector.authenticateAndDecrypt(
            frame.header,
            frame.ciphertext.copyOf(),
            frame.authenticationTag.copyOf(),
        )
        require(plaintext.size == BODY_BYTES) { "Invalid protected body size." }
        return decodeBody(plaintext)
    }

    fun encode(frame: ProtectedFrame): Encoded {
        val bytes = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(versionAndType(frame.header.eventType))
            putInt(frame.header.keyId.toInt())
            put(frame.header.journeyHandle.copyOf())
            putInt(frame.header.envelopeSequence.toInt())
            put(frame.header.nonce.copyOf())
            put(frame.ciphertext.copyOf())
            put(frame.authenticationTag.copyOf())
        }.array()
        val encoded = TEXT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        require(encoded.length <= MAX_TEXT_LENGTH) { "Fallback envelope exceeds one SMS segment." }
        require(encoded.all { it in gsm7Safe }) { "Fallback envelope is not GSM-7 safe." }
        return Encoded(encoded)
    }

    fun decode(encoded: String): ProtectedFrame {
        require(encoded.startsWith(TEXT_PREFIX)) { "Invalid fallback envelope prefix." }
        require(encoded.length <= MAX_TEXT_LENGTH) { "Fallback envelope is oversized." }
        require(encoded.all { it in gsm7Safe }) { "Fallback envelope contains unsafe characters." }
        val payload = encoded.removePrefix(TEXT_PREFIX)
        require(payload.isNotEmpty() && '=' !in payload) { "Fallback envelope must use unpadded Base64URL." }
        val bytes = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Fallback envelope is not valid Base64URL.", error)
        }
        require(bytes.size == FRAME_BYTES) { "Invalid fallback envelope frame size." }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val versionAndType = buffer.get().toInt() and 0xff
        val version = versionAndType ushr 4
        require(version == VERSION) { "Unsupported fallback envelope version: $version" }
        val eventType = EventType.fromWire(versionAndType and 0x0f)
        val keyId = buffer.int.toLong() and UINT32_MAX
        val journeyHandle = ByteArray(JOURNEY_HANDLE_BYTES).also(buffer::get)
        val envelopeSequence = buffer.int.toLong() and UINT32_MAX
        require(envelopeSequence >= 1) { "Fallback envelope sequence must be positive." }
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val ciphertext = ByteArray(BODY_BYTES).also(buffer::get)
        val tag = ByteArray(AUTHENTICATION_TAG_BYTES).also(buffer::get)
        return ProtectedFrame(
            Header(eventType, keyId, journeyHandle, envelopeSequence, nonce),
            ciphertext,
            tag,
        )
    }

    internal fun authenticatedHeaderBytes(header: Header): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(versionAndType(header.eventType))
            putInt(header.keyId.toInt())
            put(header.journeyHandle.copyOf())
            putInt(header.envelopeSequence.toInt())
            put(header.nonce.copyOf())
        }.array()

    private fun encodeBody(body: Body): ByteArray =
        ByteBuffer.allocate(BODY_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            putLong(body.telemetrySequence)
            putInt(body.observationEventTimeUnixSeconds.toInt())
            putInt(body.latitudeE7)
            putInt(body.longitudeE7)
            putShort(body.accuracyDecimeters.toShort())
            put((body.batteryPercent ?: UNKNOWN_BATTERY).toByte())
            put(encodeFlags(body.connectivity, body.charging).toByte())
        }.array()

    private fun decodeBody(bytes: ByteArray): Body {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val telemetrySequence = buffer.long
        require(telemetrySequence >= 0) { "Telemetry sequence must not be negative." }
        val eventTime = buffer.int.toLong() and UINT32_MAX
        val latitude = buffer.int
        val longitude = buffer.int
        val accuracy = buffer.short.toInt() and 0xffff
        val batteryWire = buffer.get().toInt() and 0xff
        require(batteryWire == UNKNOWN_BATTERY || batteryWire in 0..100) {
            "Invalid battery percentage."
        }
        val flags = buffer.get().toInt() and 0xff
        require(flags and RESERVED_FLAG_MASK == 0) { "Reserved fallback flags must be zero." }
        val connectivity = decodeConnectivity(flags and 0x07)
        val charging = when ((flags ushr 3) and 0x03) {
            0 -> null
            1 -> false
            2 -> true
            else -> throw IllegalArgumentException("Invalid charging flags.")
        }
        return Body(
            telemetrySequence = telemetrySequence,
            observationEventTimeUnixSeconds = eventTime,
            latitudeE7 = latitude,
            longitudeE7 = longitude,
            accuracyDecimeters = accuracy,
            batteryPercent = if (batteryWire == UNKNOWN_BATTERY) null else batteryWire,
            charging = charging,
            connectivity = connectivity,
        )
    }

    private fun encodeFlags(connectivity: ConnectivityState, charging: Boolean?): Int =
        connectivity.wireValue or when (charging) {
            null -> 0
            false -> 1 shl 3
            true -> 2 shl 3
        }

    private fun decodeConnectivity(value: Int): ConnectivityState = when (value) {
        0 -> ConnectivityState.NONE
        1 -> ConnectivityState.CELLULAR
        2 -> ConnectivityState.WIFI
        3 -> ConnectivityState.OTHER
        4 -> ConnectivityState.UNKNOWN
        else -> throw IllegalArgumentException("Invalid connectivity flags.")
    }

    private val ConnectivityState.wireValue: Int
        get() = when (this) {
            ConnectivityState.NONE -> 0
            ConnectivityState.CELLULAR -> 1
            ConnectivityState.WIFI -> 2
            ConnectivityState.OTHER -> 3
            ConnectivityState.UNKNOWN -> 4
        }

    private fun versionAndType(eventType: EventType): Byte =
        ((VERSION shl 4) or eventType.wireValue).toByte()

    private const val UINT32_MAX = 0xffff_ffffL
}
