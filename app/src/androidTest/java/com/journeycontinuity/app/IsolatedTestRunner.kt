package com.journeycontinuity.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Prevents production Application startup, sync, and database access during device tests. */
class IsolatedTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, Application::class.java.name, context)
}
