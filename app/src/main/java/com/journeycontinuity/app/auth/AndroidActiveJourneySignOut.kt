package com.journeycontinuity.app.auth

import android.annotation.SuppressLint
import android.content.Context
import com.journeycontinuity.app.data.local.DegradedConnectivityDao
import com.journeycontinuity.app.data.local.FallbackAttemptDao
import com.journeycontinuity.app.data.local.JourneyDao
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.data.repository.JourneyRepository
import com.journeycontinuity.app.degraded.FallbackDisposition
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.service.JourneyForegroundService
import com.journeycontinuity.app.service.JourneyServiceController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull

class RoomActiveJourneyStopLocal(
    private val journeyDao: JourneyDao,
    private val repository: JourneyRepository,
    private val degradedDao: DegradedConnectivityDao,
    private val fallbackDao: FallbackAttemptDao,
) : ActiveJourneyStopLocal {
    override suspend fun activeJourney() = journeyDao.getActive()?.toDomain()

    override suspend fun journey(journeyId: String) = journeyDao.getById(journeyId)?.toDomain()

    override suspend fun persistStopped(journeyId: String, endedAt: Long) =
        repository.stopMonitoringActive(journeyId, endedAt)

    override suspend fun fallbackStopped(journeyId: String): Boolean {
        val state = degradedDao.get(journeyId)
        return (state == null || !state.journeyActive &&
            state.fallbackDisposition == FallbackDisposition.INACTIVE) &&
            fallbackDao.countUnsentForJourney(journeyId) == 0
    }
}

class AndroidActiveJourneyRuntimeStop(
    private val controller: JourneyServiceController,
) : ActiveJourneyRuntimeStop {
    override suspend fun stopAndAwait(journeyId: String): Boolean {
        controller.stop()
        return withTimeoutOrNull(5_000L) {
            combine(JourneyForegroundService.activeRuntimeJourneyId,
                JourneyForegroundService.serviceRunning) { activeId, running ->
                activeId != journeyId && !running
            }.first { it }
            true
        } == true
    }
}

class SharedPreferencesMonitoringStoppedResultStore(context: Context) : MonitoringStoppedResultStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "journey-continuity-monitoring-stopped-result", Context.MODE_PRIVATE,
    )

    override fun pendingJourneyId(): String? = preferences.getString("journeyId", null)

    @SuppressLint("UseKtx") // Persist the acknowledged result pointer before the local terminal write.
    override fun savePendingJourneyId(journeyId: String): Boolean =
        preferences.edit().putString("journeyId", journeyId).commit()

    @SuppressLint("UseKtx")
    override fun clearPendingJourneyId(journeyId: String): Boolean =
        pendingJourneyId() == journeyId && preferences.edit().remove("journeyId").commit()
}

/** Keeps the exact durable result visible if Supabase configuration cannot initialize. */
class LocalOnlyMonitoringStoppedResultGateway(
    private val local: ActiveJourneyStopLocal,
    private val resultStore: MonitoringStoppedResultStore,
    private val runtime: ActiveJourneyRuntimeStop,
) : TravellerActiveJourneySignOutGateway {
    override suspend fun confirmAndSignOut(journeyId: String,
        expectedOwnerId: String): ActiveJourneySignOutResult =
        ActiveJourneySignOutResult.ConnectionRequired

    override suspend fun restorePendingResult(): ActiveJourneySignOutResult? {
        val journeyId = resultStore.pendingJourneyId() ?: return null
        val row = local.journey(journeyId) ?: return null
        if (row.status != JourneyStatus.CANCELLED || row.endedAt == null || row.completedAt != null ||
            local.activeJourney() != null || !local.fallbackStopped(journeyId)) return null
        return if (runtime.stopAndAwait(journeyId))
            ActiveJourneySignOutResult.AuthenticationUncertain(row)
        else ActiveJourneySignOutResult.TeardownRetryRequired(row)
    }

    override suspend fun retryTerminalSignOut(journeyId: String): ActiveJourneySignOutResult =
        restorePendingResult() ?: ActiveJourneySignOutResult.JourneyChanged

    override suspend fun completeResult(journeyId: String): Boolean = false
}
