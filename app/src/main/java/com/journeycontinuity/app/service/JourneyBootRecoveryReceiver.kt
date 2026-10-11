package com.journeycontinuity.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** After unlock, persist one local recovery job. The receiver never opens Room or starts location. */
class JourneyBootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            Log.e("JourneyRecovery", "Boot recovery enqueue was not confirmed in broadcast time")
            if (finished.compareAndSet(false, true)) pending.finish()
        }
        handler.postDelayed(timeout, 8_000L)
        try {
            val handoff = JourneyBootRecoveryWorker.enqueue(context.applicationContext)
            handoff.result.addListener({
                try { handoff.result.get() }
                catch (error: Exception) {
                    Log.e("JourneyRecovery", "Boot recovery enqueue failed", error)
                } finally {
                    handler.removeCallbacks(timeout)
                    if (finished.compareAndSet(false, true)) pending.finish()
                }
            }, Executor { it.run() })
        } catch (error: Exception) {
            Log.e("JourneyRecovery", "Boot recovery enqueue unavailable", error)
            handler.removeCallbacks(timeout)
            if (finished.compareAndSet(false, true)) pending.finish()
        }
    }
}
