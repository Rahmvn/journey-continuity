package com.journeycontinuity.app.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.journeycontinuity.app.JourneyContinuityApplication
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.PendingAuthorityJourney
import com.journeycontinuity.app.domain.IncidentReconciliationOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** A wake may finish only after work was attempted, became obsolete, or awaits a session event. */
enum class AuthorityWakeResult { NO_PENDING, ATTEMPTED, BEFORE_DUE, OFFLINE, AUTHENTICATION_REQUIRED, OBSOLETE_WORK }

/** One delayed wake per exact persisted retry time. The Room coordinator owns every decision. */
class WorkManagerAuthorityRetryScheduler(
    context: Context,
    private val db: JourneyDatabase,
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun schedule(journeyId: String, retryAtMillis: Long?) = mutex.withLock {
        val tag = journeyTag(journeyId)
        val token = deadlineToken(retryAtMillis)
        val current = db.incidentReconciliationDao().pendingAuthority().firstOrNull { it.journeyId == journeyId }
        if (desiredToken(current) != token) return@withLock
        if (activeWork(tag).none { deadlineTag(token) in it.tags }) {
            // Recheck a persisted wall deadline within five minutes even after a clock change.
            val remaining = retryAtMillis?.let { boundedInitialDelay(it, nowMillis()) } ?: 0L
            val request = OneTimeWorkRequestBuilder<AuthorityRetryWorker>()
                .setInputData(Data.Builder().putString(JOURNEY_ID, journeyId)
                    .putString(RETRY_AT_MILLIS, token).build())
                .setInitialDelay(remaining, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(tag).addTag(deadlineTag(token))
                .build()
            // Enqueue the successor before retiring older exact-deadline jobs. Cancellation
            // targets their IDs, so a running old worker cannot cancel its successor.
            withContext(Dispatchers.IO) {
                workManager.enqueueUniqueWork("$tag-$token", ExistingWorkPolicy.KEEP, request)
                    .result.get(10, TimeUnit.SECONDS)
            }
        }
        retireObsoleteLocked(journeyId)
    }

    /** Called when Room no longer permits a scheduled retry, including COMPETING_TERMINAL. */
    suspend fun retireObsolete(journeyId: String) = mutex.withLock { retireObsoleteLocked(journeyId) }

    private suspend fun retireObsoleteLocked(journeyId: String) {
        val tag = journeyTag(journeyId)
        val current = db.incidentReconciliationDao().pendingAuthority().firstOrNull { it.journeyId == journeyId }
        val wanted = desiredToken(current)?.let(::deadlineTag)
        activeWork(tag).filter { wanted == null || wanted !in it.tags }.forEach { old ->
            workManager.cancelWorkById(old.id)
        }
    }

    private suspend fun activeWork(tag: String) = withContext(Dispatchers.IO) {
        workManager.getWorkInfosByTag(tag).get(10, TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }
    }

    companion object {
        const val JOURNEY_ID = "journeyId"
        const val RETRY_AT_MILLIS = "retryAtMillis"
        const val MAX_INITIAL_DELAY_MILLIS = 5 * 60 * 1000L
        const val IMMEDIATE_TOKEN = "immediate"

        fun journeyTag(journeyId: String) = "journey-authority-$journeyId"
        fun deadlineToken(retryAtMillis: Long?) = retryAtMillis?.toString() ?: IMMEDIATE_TOKEN
        private fun deadlineTag(token: String) = "journey-authority-deadline-$token"
        fun desiredToken(row: PendingAuthorityJourney?): String? = when {
            row == null -> null
            row.retryAtMillis != null && RoomIncidentReconciliationCoordinator.shouldScheduleRetry(row.lastOutcome) ->
                deadlineToken(row.retryAtMillis)
            row.retryAtMillis == null && row.lastOutcome == IncidentReconciliationOutcome.EXPLICIT_PENDING.name ->
                IMMEDIATE_TOKEN
            else -> null
        }

        fun boundedInitialDelay(retryAtMillis: Long, nowMillis: Long): Long =
            if (retryAtMillis <= nowMillis) 0L else
                runCatching { Math.subtractExact(retryAtMillis, nowMillis) }
                    .getOrDefault(Long.MAX_VALUE).coerceAtMost(MAX_INITIAL_DELAY_MILLIS)
    }
}

class AuthorityRetryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val journeyId = inputData.getString(WorkManagerAuthorityRetryScheduler.JOURNEY_ID)
            ?: return Result.failure()
        val token = inputData.getString(WorkManagerAuthorityRetryScheduler.RETRY_AT_MILLIS)
            ?: return Result.failure()
        if (token != WorkManagerAuthorityRetryScheduler.IMMEDIATE_TOKEN && token.toLongOrNull() == null)
            return Result.failure()
        return try {
            val app = applicationContext.applicationContext as JourneyContinuityApplication
            when (app.reconcilePendingAuthorityNow(journeyId, token)) {
                AuthorityWakeResult.NO_PENDING, AuthorityWakeResult.ATTEMPTED,
                AuthorityWakeResult.AUTHENTICATION_REQUIRED, AuthorityWakeResult.OBSOLETE_WORK -> Result.success()
                AuthorityWakeResult.BEFORE_DUE, AuthorityWakeResult.OFFLINE ->
                    if (app.scheduledAuthorityWakeStillCurrent(journeyId, token)) Result.retry()
                    else Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("AuthorityRetryWorker", "Authority wake failed", error)
            // A failed local read must not consume the only persisted due wake.
            Result.retry()
        }
    }
}
