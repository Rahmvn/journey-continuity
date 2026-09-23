package com.journeycontinuity.app.degraded

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun interface FallbackNonceSource {
    fun nextNonce(): ByteArray
}

object SecureFallbackNonceSource : FallbackNonceSource {
    private val random = SecureRandom()

    override fun nextNonce(): ByteArray = ByteArray(FallbackEnvelopeV1.NONCE_BYTES).also(random::nextBytes)
}

class AesGcmEnvelopeProtector(
    installationFallbackKey: ByteArray,
) : EnvelopeProtector, AutoCloseable {
    private val installationKey = installationFallbackKey.copyOf()

    init {
        require(installationKey.size == KEY_BYTES) { "Installation fallback keys must be 256 bits." }
    }

    override fun protect(
        header: FallbackEnvelopeV1.Header,
        plaintextBody: ByteArray,
    ): ProtectedEnvelopePayload {
        val output = cipher(Cipher.ENCRYPT_MODE, header).doFinal(plaintextBody)
        val ciphertextLength = output.size - FallbackEnvelopeV1.AUTHENTICATION_TAG_BYTES
        require(ciphertextLength == plaintextBody.size)
        return ProtectedEnvelopePayload(
            ciphertext = output.copyOfRange(0, ciphertextLength),
            authenticationTag = output.copyOfRange(ciphertextLength, output.size),
        )
    }

    override fun authenticateAndDecrypt(
        header: FallbackEnvelopeV1.Header,
        ciphertext: ByteArray,
        authenticationTag: ByteArray,
    ): ByteArray = try {
        require(authenticationTag.size == FallbackEnvelopeV1.AUTHENTICATION_TAG_BYTES)
        cipher(Cipher.DECRYPT_MODE, header).doFinal(ciphertext + authenticationTag)
    } catch (error: AEADBadTagException) {
        throw EnvelopeAuthenticationException("Fallback envelope authentication failed.")
    } catch (error: GeneralSecurityException) {
        throw EnvelopeAuthenticationException("Fallback envelope authentication failed.")
    }

    private fun cipher(mode: Int, header: FallbackEnvelopeV1.Header): Cipher {
        val journeyKey = deriveJourneyKey(installationKey, header.journeyHandle)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    mode,
                    SecretKeySpec(journeyKey, "AES"),
                    GCMParameterSpec(TAG_BITS, header.nonce),
                )
                updateAAD(FallbackEnvelopeV1.authenticatedHeaderBytes(header))
            }
        } finally {
            journeyKey.fill(0)
        }
    }

    override fun close() {
        installationKey.fill(0)
    }

    companion object {
        const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private val HKDF_SALT = "JourneyContinuity/JC1/HKDF-SHA-256/v1".toByteArray(Charsets.UTF_8)
        private val HKDF_INFO = "journey-envelope-key".toByteArray(Charsets.UTF_8)

        internal fun deriveJourneyKey(installationKey: ByteArray, journeyHandle: ByteArray): ByteArray {
            require(installationKey.size == KEY_BYTES)
            require(journeyHandle.size == FallbackEnvelopeV1.JOURNEY_HANDLE_BYTES)
            val extract = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(HKDF_SALT, "HmacSHA256"))
                doFinal(installationKey)
            }
            return try {
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(extract, "HmacSHA256"))
                    doFinal(HKDF_INFO + journeyHandle + byteArrayOf(1)).copyOf(KEY_BYTES)
                }
            } finally {
                extract.fill(0)
            }
        }

        fun sha256(payload: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(payload)
    }
}
