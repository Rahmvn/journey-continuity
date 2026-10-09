package com.journeycontinuity.app.ui

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Explicit foreground launch avoids MIUI's blocked ActivityScenario/startActivitySync path. */
internal fun launchProbableEndUiTestHost(compose: ComposeTestRule): ComponentActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
        "am start -n $packageName/androidx.activity.ComponentActivity",
    )).use { it.readBytes() }
    var host: ComponentActivity? = null
    compose.waitUntil("Probable End UI host did not resume", 10_000) {
        compose.runOnUiThread {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { it.javaClass == ComponentActivity::class.java } as? ComponentActivity
        }
        host != null
    }
    return requireNotNull(host)
}
