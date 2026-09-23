package com.journeycontinuity.app.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallationIdentityStoreTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = IsolatedPreferencesContext(targetContext.applicationContext)

    @After
    fun clearTestOwnedPreferences() {
        testContext.getSharedPreferences(PREFERENCES_NAME, 0).edit().clear().commit()
    }

    @Test
    fun randomInstallationIdentitySurvivesStoreRecreationInIsolatedTargetStorage() {
        val first = SharedPreferencesInstallationIdentityStore(testContext).getOrCreate()
        UUID.fromString(first)

        val recreated = SharedPreferencesInstallationIdentityStore(testContext).getOrCreate()

        assertEquals(first, recreated)
    }

    private companion object {
        const val PREFERENCES_NAME = "journey-continuity-installation"
        const val TEST_PREFIX = "instrumentation-installation-identity-"
    }

    /**
     * The production store deliberately resolves applicationContext. Keep that behavior under
     * test while redirecting only this test's preferences into a target-package-owned namespace.
     */
    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(TEST_PREFIX + name, mode)
    }
}
