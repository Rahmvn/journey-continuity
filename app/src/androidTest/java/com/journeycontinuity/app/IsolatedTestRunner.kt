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
    ): Application = super.newApplication(
        cl,
        if (context?.packageName == "com.journeycontinuity.app.m6isolation") {
            JourneyContinuityApplication::class.java.name
        } else {
            Application::class.java.name
        },
        context,
    )
}
