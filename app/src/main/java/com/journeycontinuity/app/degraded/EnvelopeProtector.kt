package com.journeycontinuity.app.degraded

class ProtectedEnvelopePayload(
    ciphertext: ByteArray,
    authenticationTag: ByteArray,
) {
    private val ciphertextBytes = ciphertext.copyOf()
    private val authenticationTagBytes = authenticationTag.copyOf()

    val ciphertext: ByteArray
        get() = ciphertextBytes.copyOf()

    val authenticationTag: ByteArray
        get() = authenticationTagBytes.copyOf()
}

interface EnvelopeProtector {
    fun protect(
        header: FallbackEnvelopeV1.Header,
        plaintextBody: ByteArray,
    ): ProtectedEnvelopePayload

    fun authenticateAndDecrypt(
        header: FallbackEnvelopeV1.Header,
        ciphertext: ByteArray,
        authenticationTag: ByteArray,
    ): ByteArray
}

class EnvelopeAuthenticationException(message: String) : IllegalArgumentException(message)
