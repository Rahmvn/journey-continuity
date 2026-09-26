package com.journeycontinuity.app.degraded

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.journeycontinuity.app.JourneyContinuityApplication
import java.util.concurrent.TimeUnit

class WorkManagerFallbackHandoffScheduler(context: Context) : FallbackHandoffScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun scheduleUncertainCheck(localAttemptId: Long, generation: Int, delayMillis: Long) {
        enqueue(
            name = "fallback-uncertain-$localAttemptId-$generation",
            attemptId = localAttemptId,
            generation = generation,
            delayMillis = delayMillis,
        )
    }

    override fun scheduleRetry(localAttemptId: Long, delayMillis: Long) {
        enqueue("fallback-retry-$localAttemptId", localAttemptId, null, delayMillis)
    }

    private fun enqueue(name: String, attemptId: Long, generation: Int?, delayMillis: Long) {
        val data = Data.Builder().putLong(KEY_ATTEMPT_ID, attemptId).apply {
            generation?.let { putInt(KEY_GENERATION, it) }
        }.build()
        val request = OneTimeWorkRequestBuilder<FallbackHandoffWorker>()
            .setInputData(data)
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }
}

class FallbackHandoffWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? JourneyContinuityApplication ?: return Result.failure()
        val attemptId = inputData.getLong(KEY_ATTEMPT_ID, -1L)
        if (attemptId <= 0) return Result.failure()
        return runCatching {
            val generation = inputData.getInt(KEY_GENERATION, -1)
            runFallbackHandoffWork(app.fallbackHandoffCoordinator, attemptId, generation)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

/** The same dispatch used by WorkManager, callable with an isolated no-send coordinator in tests. */
internal suspend fun runFallbackHandoffWork(
    coordinator: FallbackHandoffCoordinator,
    attemptId: Long,
    generation: Int,
) {
    if (generation > 0) coordinator.recoverUncertain(attemptId, generation)
    else coordinator.handoff(attemptId)
}

private const val KEY_ATTEMPT_ID = "fallback_attempt_id"
private const val KEY_GENERATION = "fallback_handoff_generation"
