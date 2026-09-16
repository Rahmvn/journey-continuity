package com.journeycontinuity.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

fun interface SyncScheduler {
    fun schedule()
}

class WorkManagerSyncScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : SyncScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<JourneySyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "journey-evidence-cloud-sync"
        const val WORK_TAG = "journey-cloud-sync"
    }
}

class JourneySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val application = applicationContext as com.journeycontinuity.app.JourneyContinuityApplication
        AndroidSyncDiagnosticLogger.info(
            "JourneySyncWorker executing; runAttemptCount=$runAttemptCount",
        )
        return when (application.syncEngine.synchronize()) {
            SyncRunResult.Success -> Result.success().also {
                AndroidSyncDiagnosticLogger.info("JourneySyncWorker result: success")
            }
            SyncRunResult.Retry -> Result.retry().also {
                AndroidSyncDiagnosticLogger.warning("JourneySyncWorker result: retry")
            }
            SyncRunResult.PermanentFailure -> Result.failure().also {
                AndroidSyncDiagnosticLogger.warning("JourneySyncWorker result: failure")
            }
        }
    }
}
