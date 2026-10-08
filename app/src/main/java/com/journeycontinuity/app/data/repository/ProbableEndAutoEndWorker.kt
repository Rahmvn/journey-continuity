package com.journeycontinuity.app.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.journeycontinuity.app.JourneyContinuityApplication
import java.util.concurrent.TimeUnit

/** The worker is a wake-up only. Room claim and the server RPC decide terminal truth. */
class WorkManagerProbableEndAutoEndScheduler(context: Context) : ProbableEndAutomaticWakeScheduler {
    private val work = WorkManager.getInstance(context)
    override fun schedule(episodeId: String, atMillis: Long) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ProbableEndAutoEndWorker>()
            .setInputData(Data.Builder().putString(KEY_EPISODE, episodeId).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork("probable-end-auto-end-$episodeId", ExistingWorkPolicy.KEEP, request)
    }
}

class ProbableEndAutoEndWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? JourneyContinuityApplication ?: return Result.failure()
        val episodeId = inputData.getString(KEY_EPISODE) ?: return Result.failure()
        return when (app.probableEndAutoEndCoordinator.advance(episodeId)) {
            ProbableEndAutomaticOutcome.ENDED, ProbableEndAutomaticOutcome.BLOCKED,
            ProbableEndAutomaticOutcome.OBSOLETE -> Result.success()
            ProbableEndAutomaticOutcome.PENDING, ProbableEndAutomaticOutcome.TOO_EARLY -> Result.retry()
        }
    }
}

private const val KEY_EPISODE = "probable_end_presentation_episode"
