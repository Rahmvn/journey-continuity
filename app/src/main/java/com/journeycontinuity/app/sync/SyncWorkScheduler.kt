package com.journeycontinuity.app.sync

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

enum class SyncRequestUrgency {
    ROUTINE,
    URGENT,
}

fun interface SyncScheduler {
    fun schedule(urgency: SyncRequestUrgency)
}

internal enum class SyncWorkLane {
    PRIMARY,
    WAKE,
}

internal enum class SyncWorkState {
    ENQUEUED,
    RUNNING,
}

internal data class SyncWorkSnapshot(
    val lane: SyncWorkLane,
    val state: SyncWorkState,
    val runAttemptCount: Int,
) {
    val isBackedOff: Boolean
        get() = state == SyncWorkState.ENQUEUED && runAttemptCount > 0
}

internal data class SyncEnqueueDecision(
    val lane: SyncWorkLane,
    val policy: ExistingWorkPolicy,
)

internal fun planSyncEnqueue(
    urgency: SyncRequestUrgency,
    unfinished: List<SyncWorkSnapshot>,
): SyncEnqueueDecision? {
    if (unfinished.isEmpty()) {
        return SyncEnqueueDecision(SyncWorkLane.PRIMARY, ExistingWorkPolicy.KEEP)
    }

    val runningLanes = unfinished.filter { it.state == SyncWorkState.RUNNING }.map { it.lane }.toSet()
    if (runningLanes.isNotEmpty()) {
        val availableLane = SyncWorkLane.entries.firstOrNull { it !in runningLanes }
            ?: return null
        val pendingOnAvailableLane = unfinished.firstOrNull { it.lane == availableLane }
        return when {
            pendingOnAvailableLane == null ->
                SyncEnqueueDecision(availableLane, ExistingWorkPolicy.KEEP)
            urgency == SyncRequestUrgency.URGENT && pendingOnAvailableLane.isBackedOff ->
                SyncEnqueueDecision(availableLane, ExistingWorkPolicy.REPLACE)
            else -> null
        }
    }

    if (unfinished.any { !it.isBackedOff }) return null

    val freeLane = SyncWorkLane.entries.firstOrNull { lane -> unfinished.none { it.lane == lane } }
    if (freeLane != null) {
        return SyncEnqueueDecision(freeLane, ExistingWorkPolicy.KEEP)
    }
    return if (urgency == SyncRequestUrgency.URGENT) {
        SyncEnqueueDecision(SyncWorkLane.WAKE, ExistingWorkPolicy.REPLACE)
    } else null
}

class WorkManagerSyncScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : SyncScheduler {
    private val callbackExecutor = ContextCompat.getMainExecutor(context)

    override fun schedule(urgency: SyncRequestUrgency) {
        val query = workManager.getWorkInfosByTag(WORK_TAG)
        query.addListener(
            {
                val snapshots = runCatching { query.get().toSnapshots() }.getOrElse {
                    enqueue(SyncEnqueueDecision(SyncWorkLane.PRIMARY, ExistingWorkPolicy.KEEP))
                    return@addListener
                }
                planSyncEnqueue(urgency, snapshots)?.let(::enqueue)
            },
            callbackExecutor,
        )
    }

    private fun enqueue(decision: SyncEnqueueDecision) {
        val request = OneTimeWorkRequestBuilder<JourneySyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag(decision.lane.tag)
            .build()
        workManager.enqueueUniqueWork(decision.lane.workName, decision.policy, request)
        AndroidSyncDiagnosticLogger.info(
            "Cloud sync scheduled: lane=${decision.lane.name}, policy=${decision.policy.name}",
        )
    }

    private fun List<WorkInfo>.toSnapshots(): List<SyncWorkSnapshot> = mapNotNull { info ->
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SyncWorkState.ENQUEUED
            WorkInfo.State.RUNNING -> SyncWorkState.RUNNING
            else -> return@mapNotNull null
        }
        val lane = if (WAKE_LANE_TAG in info.tags) SyncWorkLane.WAKE else SyncWorkLane.PRIMARY
        SyncWorkSnapshot(lane, state, info.runAttemptCount)
    }

    companion object {
        const val WORK_NAME = "journey-evidence-cloud-sync"
        const val WAKE_WORK_NAME = "journey-evidence-cloud-sync-wake"
        const val WORK_TAG = "journey-cloud-sync"
        const val PRIMARY_LANE_TAG = "journey-cloud-sync-primary"
        const val WAKE_LANE_TAG = "journey-cloud-sync-wake"
    }
}

private val SyncWorkLane.workName: String
    get() = when (this) {
        SyncWorkLane.PRIMARY -> WorkManagerSyncScheduler.WORK_NAME
        SyncWorkLane.WAKE -> WorkManagerSyncScheduler.WAKE_WORK_NAME
    }

private val SyncWorkLane.tag: String
    get() = when (this) {
        SyncWorkLane.PRIMARY -> WorkManagerSyncScheduler.PRIMARY_LANE_TAG
        SyncWorkLane.WAKE -> WorkManagerSyncScheduler.WAKE_LANE_TAG
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
