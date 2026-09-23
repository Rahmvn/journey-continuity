package com.journeycontinuity.app.degraded

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FallbackKeyMaterialStoreInstrumentedTest {
    private lateinit var context: Context
    private var testKeyId = 0L
    private var createdWrappingAlias = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val keyStore = androidKeyStore()
        check(!keyStore.containsAlias(KEY_ALIAS)) {
            "Refusing to run: the production wrapping alias already exists."
        }
        check(!context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).all.keys.any {
            it.startsWith(KEY_PREFIX)
        }) { "Refusing to run: provisioned fallback key material already exists." }
        do {
            testKeyId = SecureRandom().nextInt().toLong() and UINT32_MAX
        } while (testKeyId == 0L)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$KEY_PREFIX$testKeyId")
            .commit()
        if (createdWrappingAlias) androidKeyStore().deleteEntry(KEY_ALIAS)
    }

    @Test
    fun provisionedKeyIsWrappedDurableAndFailsClosedWhenMissingOrUnreadable() {
        val original = ByteArray(AesGcmEnvelopeProtector.KEY_BYTES).also(SecureRandom()::nextBytes)
        val firstStore = AndroidKeystoreFallbackKeyMaterialStore(context)
        firstStore.provision(testKeyId, original)
        createdWrappingAlias = true

        val recovered = firstStore.useKey(testKeyId, ByteArray::copyOf)
        assertArrayEquals(original, recovered)

        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val wrappedText = preferences.getString("$KEY_PREFIX$testKeyId", null)
        assertTrue(!wrappedText.isNullOrBlank())
        assertNotEquals(Base64.getEncoder().encodeToString(original), wrappedText)
        assertFalse(Base64.getDecoder().decode(wrappedText).containsSubsequence(original))

        val recreatedStore = AndroidKeystoreFallbackKeyMaterialStore(context)
        assertArrayEquals(original, recreatedStore.useKey(testKeyId, ByteArray::copyOf))

        val corrupted = Base64.getDecoder().decode(wrappedText).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        preferences.edit()
            .putString("$KEY_PREFIX$testKeyId", Base64.getEncoder().encodeToString(corrupted))
            .commit()
        var callbackInvoked = false
        assertThrows(Exception::class.java) {
            recreatedStore.useKey(testKeyId) {
                callbackInvoked = true
            }
        }
        assertFalse(callbackInvoked)

        preferences.edit().remove("$KEY_PREFIX$testKeyId").commit()
        assertFalse(recreatedStore.hasKey(testKeyId))
        assertNull(recreatedStore.useKey(testKeyId, ByteArray::copyOf))
        original.fill(0)
    }

    private fun androidKeyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        candidate.isNotEmpty() && indices.any { start ->
            start + candidate.size <= size &&
                candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "journey_continuity_fallback_wrapping_v1"
        const val PREFERENCES_NAME = "journey_fallback_wrapped_keys"
        const val KEY_PREFIX = "wrapped_key_"
        const val UINT32_MAX = 0xffff_ffffL
    }
}
