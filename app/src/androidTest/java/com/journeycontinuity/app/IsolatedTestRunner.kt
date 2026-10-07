package com.journeycontinuity.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Production startup is permitted only inside the separate opt-in acceptance package/UID. */
class IsolatedTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        if (context?.packageName == "com.journeycontinuity.app.structuredadmissionaudit") {
            check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty()) {
                "Structured admission device acceptance requires a credential-free isolated build."
            }
        }
        return super.newApplication(
        cl,
        if (context?.packageName in setOf("com.journeycontinuity.app.m6isolation",
                "com.journeycontinuity.app.structuredadmissionaudit")) {
            JourneyContinuityApplication::class.java.name
        } else {
            Application::class.java.name
        },
        context,
        )
    }
}
