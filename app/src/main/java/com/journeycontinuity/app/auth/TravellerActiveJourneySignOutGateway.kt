package com.journeycontinuity.app.auth

import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.sync.RemoteJourneyState
import kotlinx.coroutines.CancellationException

/** The request may have reached Postgres even though its acknowledgement was lost. */
class AmbiguousMonitoringStopException(cause: Throwable) : Exception(cause)

data class MonitoringStopAcknowledgement(val status: JourneyStatus, val endedAt: Long?)

interface ActiveJourneyStopRemote {
    suspend fun stop(journeyId: String, ownerId: String): MonitoringStopAcknowledgement
    suspend fun ownerJourneyState(journeyId: String, ownerId: String): RemoteJourneyState?
}

interface ActiveJourneyStopLocal {
    suspend fun activeJourney(): Journey?
    suspend fun journey(journeyId: String): Journey?
    suspend fun persistStopped(journeyId: String, endedAt: Long): Journey?
    suspend fun fallbackStopped(journeyId: String): Boolean
}

interface MonitoringStoppedResultStore {
    fun pendingJourneyId(): String?
    fun savePendingJourneyId(journeyId: String): Boolean
    fun clearPendingJourneyId(journeyId: String): Boolean
}

fun interface ActiveJourneyRuntimeStop {
    suspend fun stopAndAwait(journeyId: String): Boolean
}

sealed interface ActiveJourneySignOutResult {
    data class SignedOut(val journey: Journey) : ActiveJourneySignOutResult
    data class SignOutRetryRequired(val journey: Journey) : ActiveJourneySignOutResult
    data class TeardownRetryRequired(val journey: Journey) : ActiveJourneySignOutResult
    data class AuthenticationUncertain(val journey: Journey) : ActiveJourneySignOutResult
    data object JourneyChanged : ActiveJourneySignOutResult
    data object AuthenticationRequired : ActiveJourneySignOutResult
    data object ConnectionRequired : ActiveJourneySignOutResult
    data object RemoteResultInvalid : ActiveJourneySignOutResult
    data object LocalPersistenceFailed : ActiveJourneySignOutResult
}

interface TravellerActiveJourneySignOutGateway {
    suspend fun confirmAndSignOut(journeyId: String, expectedOwnerId: String): ActiveJourneySignOutResult
    suspend fun restorePendingResult(): ActiveJourneySignOutResult?
    suspend fun retryTerminalSignOut(journeyId: String): ActiveJourneySignOutResult
    suspend fun completeResult(journeyId: String): Boolean
}

/** One explicit user operation. Admission stays locked until terminal persistence and teardown. */
class GuardedTravellerActiveJourneySignOutGateway internal constructor(
    private val local: ActiveJourneyStopLocal,
    private val remote: ActiveJourneyStopRemote,
    private val identityCoordinator: TravellerIdentityCoordinator,
    private val identityStore: TravellerIdentityStore,
    private val authBackend: TravellerAuthBackend,
    private val localSession: AccountLocalSession,
    private val runtime: ActiveJourneyRuntimeStop,
    private val resultStore: MonitoringStoppedResultStore,
    private val admissionGate: OwnerAdmissionGate,
    private val reconciliationRequested: () -> Unit = {},
) : TravellerActiveJourneySignOutGateway {
    override suspend fun confirmAndSignOut(
        journeyId: String,
        expectedOwnerId: String,
    ): ActiveJourneySignOutResult {
        val owner = try {
            identityCoordinator.requireAuthenticatedTraveller().userId
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return ActiveJourneySignOutResult.AuthenticationRequired
        }
        if (owner != expectedOwnerId) return ActiveJourneySignOutResult.AuthenticationRequired
        return admissionGate.withLock {
            if (!authenticatedAs(expectedOwnerId)) return@withLock ActiveJourneySignOutResult.AuthenticationRequired
            val active = try { local.activeJourney() } catch (error: Throwable) {
                if (error is CancellationException) throw error
                return@withLock ActiveJourneySignOutResult.JourneyChanged
            }
            if (active?.id != journeyId || active.status != JourneyStatus.ACTIVE) {
                return@withLock ActiveJourneySignOutResult.JourneyChanged
            }
            val previousPendingId = resultStore.pendingJourneyId()
            if (previousPendingId != null && previousPendingId != journeyId) {
                return@withLock ActiveJourneySignOutResult.JourneyChanged
            }
            // Read on every explicit attempt. A previous timeout may have committed,
            // even though this device could not safely acknowledge it at that time.
            val before = ownerState(journeyId, expectedOwnerId)
                ?: return@withLock ActiveJourneySignOutResult.ConnectionRequired
            val previouslyStopped = when (before.status) {
                JourneyStatus.CANCELLED -> before.takeIf(::validStoppedState)
                    ?: return@withLock ActiveJourneySignOutResult.RemoteResultInvalid
                JourneyStatus.ACTIVE -> null
                JourneyStatus.COMPLETED -> return@withLock ActiveJourneySignOutResult.RemoteResultInvalid
            }

            val acknowledged = previouslyStopped ?: run {
                val response = try { remote.stop(journeyId, expectedOwnerId) } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error !is AmbiguousMonitoringStopException) {
                        return@withLock ActiveJourneySignOutResult.ConnectionRequired
                    }
                    val lookedUp = ownerState(journeyId, expectedOwnerId)
                        ?: return@withLock ActiveJourneySignOutResult.ConnectionRequired
                    if (!validStoppedState(lookedUp)) {
                        return@withLock ActiveJourneySignOutResult.ConnectionRequired
                    }
                    return@run lookedUp
                }
                if (response.status != JourneyStatus.CANCELLED ||
                    response.endedAt == null || response.endedAt <= 0L) {
                    return@withLock ActiveJourneySignOutResult.RemoteResultInvalid
                }
                val lookedUp = ownerState(journeyId, expectedOwnerId)
                    ?: return@withLock ActiveJourneySignOutResult.ConnectionRequired
                if (!validStoppedState(lookedUp) || lookedUp.endedAt != response.endedAt) {
                    return@withLock ActiveJourneySignOutResult.RemoteResultInvalid
                }
                lookedUp
            }

            val endedAt = requireNotNull(acknowledged.endedAt)
            // This marker is a result pointer only after authenticated server acknowledgement.
            // It can never queue a cancellation or sign-out after an unconfirmed timeout.
            if (previousPendingId == null && !resultStore.savePendingJourneyId(journeyId)) {
                return@withLock localPersistenceFailed()
            }
            val persisted = try { local.persistStopped(journeyId, endedAt) } catch (error: Throwable) {
                if (error is CancellationException) throw error
                null
            } ?: try { local.journey(journeyId) } catch (error: Throwable) {
                if (error is CancellationException) throw error
                null
            }?.takeIf {
                it.status == JourneyStatus.CANCELLED && it.endedAt == endedAt && it.completedAt == null
            } ?: return@withLock localPersistenceFailed()
            if (persisted.id != journeyId || persisted.status != JourneyStatus.CANCELLED ||
                persisted.endedAt != endedAt || persisted.completedAt != null) {
                return@withLock localPersistenceFailed()
            }
            finishTerminal(journeyId, endedAt)
        }
    }

    override suspend fun restorePendingResult(): ActiveJourneySignOutResult? = admissionGate.withLock {
        val journeyId = resultStore.pendingJourneyId() ?: return@withLock null
        val row = try { local.journey(journeyId) } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return@withLock null
        }
        if (row?.status != JourneyStatus.CANCELLED || row.endedAt == null || row.completedAt != null) {
            return@withLock null
        }
        try { authBackend.awaitInitialization() } catch (error: Throwable) {
            if (error is CancellationException) throw error
        }
        finishTerminal(journeyId, row.endedAt)
    }

    override suspend fun retryTerminalSignOut(journeyId: String): ActiveJourneySignOutResult =
        admissionGate.withLock {
            if (resultStore.pendingJourneyId() != journeyId) {
                return@withLock ActiveJourneySignOutResult.JourneyChanged
            }
            val row = try { local.journey(journeyId) } catch (error: Throwable) {
                if (error is CancellationException) throw error
                null
            }
            if (row?.status != JourneyStatus.CANCELLED || row.endedAt == null || row.completedAt != null) {
                return@withLock ActiveJourneySignOutResult.JourneyChanged
            }
            finishTerminal(journeyId, row.endedAt)
        }

    override suspend fun completeResult(journeyId: String): Boolean = admissionGate.withLock {
        if (resultStore.pendingJourneyId() != journeyId ||
            sessionStateOrInitializing() != TravellerSessionState.NotAuthenticated) return@withLock false
        val row = local.journey(journeyId)
        if (row?.status != JourneyStatus.CANCELLED || row.endedAt == null ||
            row.completedAt != null || local.activeJourney() != null ||
            !local.fallbackStopped(journeyId)) return@withLock false
        if (!runtime.stopAndAwait(journeyId)) return@withLock false
        resultStore.clearPendingJourneyId(journeyId)
    }

    private suspend fun finishTerminal(journeyId: String, endedAt: Long): ActiveJourneySignOutResult {
        val row = try { local.journey(journeyId) } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        } ?: return ActiveJourneySignOutResult.LocalPersistenceFailed
        if (row.status != JourneyStatus.CANCELLED || row.endedAt != endedAt ||
            row.completedAt != null) return ActiveJourneySignOutResult.LocalPersistenceFailed
        val safe = try { local.activeJourney() == null && local.fallbackStopped(journeyId) }
            catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
        if (!safe) return ActiveJourneySignOutResult.TeardownRetryRequired(row)
        val stopped = try { runtime.stopAndAwait(journeyId) } catch (error: Throwable) {
            if (error is CancellationException) throw error
            false
        }
        if (!stopped) return ActiveJourneySignOutResult.TeardownRetryRequired(row)
        val ownerId = identityStore.expectedTravellerUserId()
            ?: return ActiveJourneySignOutResult.AuthenticationUncertain(row)
        return when (val state = sessionStateOrInitializing()) {
            TravellerSessionState.NotAuthenticated -> ActiveJourneySignOutResult.SignedOut(row)
            is TravellerSessionState.Authenticated -> {
                if (state.userId != ownerId || state.accessTokenExpired) {
                    ActiveJourneySignOutResult.AuthenticationUncertain(row)
                } else {
                    try { localSession.signOutLocal() } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                    }
                    when (val after = sessionStateOrInitializing()) {
                        TravellerSessionState.NotAuthenticated -> ActiveJourneySignOutResult.SignedOut(row)
                        is TravellerSessionState.Authenticated -> if (after.userId == ownerId)
                            ActiveJourneySignOutResult.SignOutRetryRequired(row)
                        else ActiveJourneySignOutResult.AuthenticationUncertain(row)
                        else -> ActiveJourneySignOutResult.AuthenticationUncertain(row)
                    }
                }
            }
            else -> ActiveJourneySignOutResult.AuthenticationUncertain(row)
        }
    }

    private fun authenticatedAs(ownerId: String): Boolean =
        identityStore.expectedTravellerUserId() == ownerId &&
            (sessionStateOrInitializing() as? TravellerSessionState.Authenticated)?.let {
                it.userId == ownerId && !it.accessTokenExpired
            } == true

    private fun sessionStateOrInitializing(): TravellerSessionState = try {
        authBackend.sessionState()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        TravellerSessionState.Initializing
    }

    private suspend fun ownerState(journeyId: String, ownerId: String): RemoteJourneyState? = try {
        remote.ownerJourneyState(journeyId, ownerId)?.takeIf {
            it.journeyId == journeyId && it.ownerId == ownerId
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    }

    private fun validStoppedState(state: RemoteJourneyState): Boolean =
        state.status == JourneyStatus.CANCELLED && state.endedAt != null &&
            state.endedAt > 0L && state.completedAt == null

    private fun localPersistenceFailed(): ActiveJourneySignOutResult {
        runCatching(reconciliationRequested)
        return ActiveJourneySignOutResult.LocalPersistenceFailed
    }
}
