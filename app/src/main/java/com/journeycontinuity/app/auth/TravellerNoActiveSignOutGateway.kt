package com.journeycontinuity.app.auth

import com.journeycontinuity.app.data.repository.JourneyRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

sealed interface NoActiveSignOutResult {
    data object SignedOut : NoActiveSignOutResult
    data object ActiveJourney : NoActiveSignOutResult
    data object AuthenticationRequired : NoActiveSignOutResult
    data object AuthenticationReassessmentRequired : NoActiveSignOutResult
    data object Failed : NoActiveSignOutResult
}

interface TravellerNoActiveSignOutGateway {
    suspend fun hasNoActiveJourney(): Boolean
    suspend fun signOut(expectedOwnerId: String): NoActiveSignOutResult
}

internal fun interface AccountLocalSession {
    suspend fun signOutLocal()
}

internal class SupabaseAccountLocalSession(private val client: SupabaseClient) : AccountLocalSession {
    override suspend fun signOutLocal() {
        client.auth.signOut(scope = SignOutScope.LOCAL)
    }
}

class GuardedTravellerNoActiveSignOutGateway internal constructor(
    private val repository: JourneyRepository,
    private val identityCoordinator: TravellerIdentityCoordinator,
    private val identityStore: TravellerIdentityStore,
    private val authBackend: TravellerAuthBackend,
    private val admissionGate: OwnerAdmissionGate,
    private val localSession: AccountLocalSession,
) : TravellerNoActiveSignOutGateway {
    override suspend fun hasNoActiveJourney(): Boolean = admissionGate.withLock {
        repository.activeJourney.first() == null
    }

    override suspend fun signOut(expectedOwnerId: String): NoActiveSignOutResult {
        val authenticatedOwner = try {
            identityCoordinator.requireAuthenticatedTraveller().userId
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return NoActiveSignOutResult.AuthenticationRequired
        }
        if (authenticatedOwner != expectedOwnerId) return NoActiveSignOutResult.AuthenticationRequired
        return admissionGate.withLock {
            val session = authBackend.sessionState() as? TravellerSessionState.Authenticated
            if (identityStore.expectedTravellerUserId() != expectedOwnerId ||
                session?.userId != expectedOwnerId || session.accessTokenExpired) {
                return@withLock NoActiveSignOutResult.AuthenticationRequired
            }
            val activeJourney = try {
                repository.activeJourney.first()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                return@withLock NoActiveSignOutResult.Failed
            }
            if (activeJourney != null) {
                NoActiveSignOutResult.ActiveJourney
            } else {
                try {
                    localSession.signOutLocal()
                    NoActiveSignOutResult.SignedOut
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    // LOCAL sign-out may clear the SDK session before a remote request fails.
                    // The saved installation owner remains bound in every outcome.
                    when (val state = try { authBackend.sessionState() } catch (stateError: Throwable) {
                        if (stateError is CancellationException) throw stateError
                        TravellerSessionState.Initializing
                    }) {
                        TravellerSessionState.NotAuthenticated -> NoActiveSignOutResult.SignedOut
                        is TravellerSessionState.Authenticated -> if (state.userId == expectedOwnerId)
                            NoActiveSignOutResult.Failed else NoActiveSignOutResult.AuthenticationReassessmentRequired
                        else -> NoActiveSignOutResult.AuthenticationReassessmentRequired
                    }
                }
            }
        }
    }
}
