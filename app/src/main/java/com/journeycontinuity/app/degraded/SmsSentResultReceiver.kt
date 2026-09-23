package com.journeycontinuity.app.degraded

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.journeycontinuity.app.JourneyContinuityApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsSentResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_SENT) return
        val attemptId = intent.getLongExtra(EXTRA_ATTEMPT_ID, -1L)
        val generation = intent.getIntExtra(EXTRA_HANDOFF_GENERATION, -1)
        if (attemptId <= 0 || generation <= 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? JourneyContinuityApplication ?: return@launch
                app.fallbackHandoffCoordinator.sentResult(attemptId, generation, resultCode)
            } finally {
                pending.finish()
            }
        }
    }
}
