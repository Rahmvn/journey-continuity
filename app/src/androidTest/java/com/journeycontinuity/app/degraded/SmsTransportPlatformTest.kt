package com.journeycontinuity.app.degraded

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsTransportPlatformTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manifestRequestsOnlyRequiredSmsPermissions() {
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.SEND_SMS in requested)
        assertTrue(Manifest.permission.READ_PHONE_STATE in requested)
        assertFalse(Manifest.permission.READ_SMS in requested)
        assertFalse(Manifest.permission.RECEIVE_SMS in requested)
        assertFalse(Manifest.permission.READ_PHONE_NUMBERS in requested)
        assertFalse(Manifest.permission.READ_CONTACTS in requested)
    }

    @Test
    fun pendingIntentIdentityIncludesAttemptAndGeneration() {
        val first = sentPendingIntent(context, 10, 1)
        val otherAttempt = sentPendingIntent(context, 11, 1)
        val otherGeneration = sentPendingIntent(context, 10, 2)
        try {
            assertNotEquals(first, otherAttempt)
            assertNotEquals(first, otherGeneration)
        } finally {
            first.cancel()
            otherAttempt.cancel()
            otherGeneration.cancel()
        }
    }
}
