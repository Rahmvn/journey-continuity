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
        if (context?.packageName in setOf("com.journeycontinuity.app.structuredadmissionaudit",
                "com.journeycontinuity.app.probableendguardacceptance",
                "com.journeycontinuity.app.boundaryacceptance")) {
            check(BuildConfig.SUPABASE_URL.isEmpty() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty()) {
                "Isolated device acceptance requires a credential-free build."
            }
        }
        return super.newApplication(
        cl,
        if (context?.packageName == "com.journeycontinuity.app.boundaryacceptance") {
            BoundaryAcceptanceApplication::class.java.name
        } else if (context?.packageName in setOf("com.journeycontinuity.app.m6isolation",
                "com.journeycontinuity.app.structuredadmissionaudit", "com.journeycontinuity.app.probableendguardacceptance",
                )) {
            JourneyContinuityApplication::class.java.name
        } else {
            Application::class.java.name
        },
        context,
        )
    }
}
