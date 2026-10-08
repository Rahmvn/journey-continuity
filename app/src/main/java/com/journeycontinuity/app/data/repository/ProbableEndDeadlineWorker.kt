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

/** WorkManager only wakes the exact Room episode; it does not grant expiry or termination. */
class WorkManagerProbableEndDeadlineScheduler(context: Context) : ProbableEndDeadlineScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun schedule(identity: ProbableEndDeadlineIdentity, nowMillis: Long) {
        val data = Data.Builder()
            .putString(KEY_JOURNEY, identity.journeyId)
            .putString(KEY_EPISODE, identity.episodeId)
            .putString(KEY_CANDIDATE, identity.candidateId)
            .putLong(KEY_GENERATION, identity.generation)
            .putLong(KEY_REVISION, identity.revision)
            .putLong(KEY_DEADLINE, identity.deadlineAtMillis)
            .build()
        val delayMillis = if (identity.deadlineAtMillis <= nowMillis) 0L else
            try { Math.subtractExact(identity.deadlineAtMillis, nowMillis) }
            catch (_: ArithmeticException) { Long.MAX_VALUE }
        val request = OneTimeWorkRequestBuilder<ProbableEndDeadlineWorker>()
            .setInputData(data)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork("probable-end-deadline-${identity.episodeId}", ExistingWorkPolicy.KEEP, request)
    }
}

class ProbableEndDeadlineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? JourneyContinuityApplication ?: return Result.failure()
        val identity = ProbableEndDeadlineIdentity(
            inputData.getString(KEY_JOURNEY) ?: return Result.failure(),
            inputData.getString(KEY_EPISODE) ?: return Result.failure(),
            inputData.getString(KEY_CANDIDATE) ?: return Result.failure(),
            inputData.getLong(KEY_GENERATION, -1),
            inputData.getLong(KEY_REVISION, -1),
            inputData.getLong(KEY_DEADLINE, -1),
        )
        if (identity.generation < 0 || identity.revision < 0 || identity.deadlineAtMillis <= 0)
            return Result.failure()
        return when (app.probableEndDeadlineCoordinator.expire(identity)) {
            ProbableEndExpiryOutcome.EXPIRED_PENDING_FINALIZATION -> {
                app.probableEndAutoEndCoordinator.advance(identity.episodeId)
                Result.success()
            }
            ProbableEndExpiryOutcome.OBSOLETE -> Result.success()
            ProbableEndExpiryOutcome.TOO_EARLY, ProbableEndExpiryOutcome.UNAVAILABLE -> Result.retry()
        }
    }
}

private const val KEY_JOURNEY = "probable_end_deadline_journey"
private const val KEY_EPISODE = "probable_end_deadline_episode"
private const val KEY_CANDIDATE = "probable_end_deadline_candidate"
private const val KEY_GENERATION = "probable_end_deadline_generation"
private const val KEY_REVISION = "probable_end_deadline_revision"
private const val KEY_DEADLINE = "probable_end_deadline_at"
