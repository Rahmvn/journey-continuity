package com.journeycontinuity.app.degraded

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface FallbackKeyMaterialStore {
    fun hasKey(keyId: Long): Boolean

    /** The supplied key is usable only for the duration of [block]. */
    fun <T> useKey(keyId: Long, block: (ByteArray) -> T): T?

    /** Called only by a future authenticated provisioning workflow. */
    fun provision(keyId: Long, installationFallbackKey: ByteArray)
}

class AndroidKeystoreFallbackKeyMaterialStore(context: Context) : FallbackKeyMaterialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun hasKey(keyId: Long): Boolean = runCatching {
        useKey(keyId) { true } == true
    }.getOrDefault(false)

    override fun <T> useKey(keyId: Long, block: (ByteArray) -> T): T? {
        val encoded = preferences.getString(preferenceKey(keyId), null) ?: return null
        val wrapped = Base64.getDecoder().decode(encoded)
        require(wrapped.size > NONCE_BYTES)
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                wrappingKey(),
                GCMParameterSpec(TAG_BITS, wrapped.copyOfRange(0, NONCE_BYTES)),
            )
            updateAAD(keyIdBytes(keyId))
            doFinal(wrapped.copyOfRange(NONCE_BYTES, wrapped.size))
        }
        return try {
            require(plaintext.size == AesGcmEnvelopeProtector.KEY_BYTES)
            block(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    @SuppressLint("UseKtx") // commit() must succeed before a Room binding may be persisted.
    override fun provision(keyId: Long, installationFallbackKey: ByteArray) {
        require(keyId in 0..UINT32_MAX)
        require(installationFallbackKey.size == AesGcmEnvelopeProtector.KEY_BYTES)
        if (hasKey(keyId)) {
            val matches = useKey(keyId) { existing ->
                MessageDigest.isEqual(existing, installationFallbackKey)
            } == true
            check(matches) { "Existing fallback key material cannot be replaced." }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey())
            updateAAD(keyIdBytes(keyId))
        }
        val ciphertext = cipher.doFinal(installationFallbackKey)
        val nonce = cipher.iv
        require(nonce.size == NONCE_BYTES)
        check(
            preferences.edit()
                .putString(preferenceKey(keyId), Base64.getEncoder().encodeToString(nonce + ciphertext))
                .commit(),
        ) { "Wrapped fallback key could not be persisted." }
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun preferenceKey(keyId: Long) = "wrapped_key_$keyId"

    private fun keyIdBytes(keyId: Long) = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(keyId).array()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "journey_continuity_fallback_wrapping_v1"
        const val PREFERENCES_NAME = "journey_fallback_wrapped_keys"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val UINT32_MAX = 0xffff_ffffL
    }
}
