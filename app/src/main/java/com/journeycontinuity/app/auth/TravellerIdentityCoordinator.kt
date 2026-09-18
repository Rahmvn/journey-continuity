package com.journeycontinuity.app.auth

import android.annotation.SuppressLint
import android.content.Context
import com.journeycontinuity.app.sync.NoOpSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncDiagnosticLogger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

sealed interface TravellerSessionState {
    data object Initializing : TravellerSessionState
    data object NotAuthenticated : TravellerSessionState
    data object RefreshFailure : TravellerSessionState
    data class Authenticated(
        val userId: String?,
        val accessTokenExpired: Boolean = false,
    ) : TravellerSessionState
}

interface TravellerAuthBackend {
    suspend fun awaitInitialization()
    fun sessionState(): TravellerSessionState
    suspend fun signInAnonymously()
}

interface TravellerIdentityStore {
    fun expectedTravellerUserId(): String?
    fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean
}

data class AuthenticatedTraveller(val userId: String)

sealed interface TravellerAuthOutcome {
    data class Authenticated(val traveller: AuthenticatedTraveller) : TravellerAuthOutcome
    data object TemporaryAuthUnavailable : TravellerAuthOutcome
    data object TravellerIdentityMismatch : TravellerAuthOutcome
    data object TravellerIdentityRecoveryRequired : TravellerAuthOutcome
}

enum class TravellerAuthFailureKind {
    TEMPORARY_UNAVAILABLE,
    IDENTITY_MISMATCH,
    IDENTITY_RECOVERY_REQUIRED,
}

class TravellerAuthException(
    val failureKind: TravellerAuthFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class TravellerIdentityCoordinator(
    private val backend: TravellerAuthBackend,
    private val identityStore: TravellerIdentityStore,
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
) {
    private val decisionMutex = Mutex()

    suspend fun resolve(): TravellerAuthOutcome = decisionMutex.withLock {
        try {
            backend.awaitInitialization()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.warning("Traveller authentication initialization is temporarily unavailable.")
            return@withLock TravellerAuthOutcome.TemporaryAuthUnavailable
        }

        val expectedUserId = identityStore.expectedTravellerUserId()
        when (val state = backend.sessionState()) {
            TravellerSessionState.Initializing -> {
                logger.info("Traveller authentication is still initializing.")
                TravellerAuthOutcome.TemporaryAuthUnavailable
            }
            TravellerSessionState.RefreshFailure -> {
                // supabase-kt keeps retrying its saved session. Starting another anonymous
                // session here would permanently replace the traveller's cloud identity.
                logger.warning("Traveller session refresh is temporarily unavailable; identity is preserved.")
                TravellerAuthOutcome.TemporaryAuthUnavailable
            }
            TravellerSessionState.NotAuthenticated -> {
                if (expectedUserId != null) {
                    logger.warning("Established traveller session is missing; identity recovery is required.")
                    TravellerAuthOutcome.TravellerIdentityRecoveryRequired
                } else {
                    establishFirstIdentity()
                }
            }
            is TravellerSessionState.Authenticated -> {
                if (state.accessTokenExpired) {
                    logger.warning("Traveller access token is expired while session refresh is pending.")
                    TravellerAuthOutcome.TemporaryAuthUnavailable
                } else {
                    verifyAuthenticatedState(
                        expectedUserId = expectedUserId,
                        actualUserId = state.userId,
                        allowIdentityEstablishment = false,
                    )
                }
            }
        }
    }

    suspend fun requireAuthenticatedTraveller(): AuthenticatedTraveller = when (val outcome = resolve()) {
        is TravellerAuthOutcome.Authenticated -> outcome.traveller
        TravellerAuthOutcome.TemporaryAuthUnavailable -> throw TravellerAuthException(
            TravellerAuthFailureKind.TEMPORARY_UNAVAILABLE,
            "Cloud authentication is temporarily unavailable while reconnecting.",
        )
        TravellerAuthOutcome.TravellerIdentityMismatch -> throw TravellerAuthException(
            TravellerAuthFailureKind.IDENTITY_MISMATCH,
            "Cloud identity does not match this installation's established traveller.",
        )
        TravellerAuthOutcome.TravellerIdentityRecoveryRequired -> throw TravellerAuthException(
            TravellerAuthFailureKind.IDENTITY_RECOVERY_REQUIRED,
            "Traveller identity recovery is required before cloud access can resume.",
        )
    }

    private suspend fun establishFirstIdentity(): TravellerAuthOutcome {
        logger.info("No established traveller identity exists; starting one anonymous sign-in.")
        try {
            backend.signInAnonymously()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.warning("Initial traveller authentication is temporarily unavailable.")
            return TravellerAuthOutcome.TemporaryAuthUnavailable
        }
        val authenticated = backend.sessionState() as? TravellerSessionState.Authenticated
            ?: return TravellerAuthOutcome.TemporaryAuthUnavailable
        return verifyAuthenticatedState(
            expectedUserId = identityStore.expectedTravellerUserId(),
            actualUserId = authenticated.userId,
            allowIdentityEstablishment = true,
        )
    }

    private fun verifyAuthenticatedState(
        expectedUserId: String?,
        actualUserId: String?,
        allowIdentityEstablishment: Boolean,
    ): TravellerAuthOutcome {
        if (actualUserId.isNullOrBlank()) {
            logger.warning("Authenticated traveller session has no usable user ID.")
            return TravellerAuthOutcome.TemporaryAuthUnavailable
        }
        if (expectedUserId != null && expectedUserId != actualUserId) {
            logger.warning("Authenticated cloud identity does not match the established traveller identity.")
            return TravellerAuthOutcome.TravellerIdentityMismatch
        }
        if (expectedUserId == null) {
            if (!allowIdentityEstablishment) {
                logger.warning("Authenticated session has no established installation identity guard.")
                return TravellerAuthOutcome.TravellerIdentityRecoveryRequired
            }
            val persisted = identityStore.persistExpectedTravellerUserIdIfAbsent(actualUserId)
            val storedUserId = identityStore.expectedTravellerUserId()
            if (!persisted || storedUserId == null) {
                logger.warning("Traveller identity could not be persisted; cloud access is blocked.")
                return TravellerAuthOutcome.TravellerIdentityRecoveryRequired
            }
            if (storedUserId != actualUserId) {
                logger.warning("Traveller identity changed while the identity guard was being established.")
                return TravellerAuthOutcome.TravellerIdentityMismatch
            }
            logger.info("Traveller identity guard established.")
        }
        return TravellerAuthOutcome.Authenticated(AuthenticatedTraveller(actualUserId))
    }
}

class SupabaseTravellerAuthBackend(
    private val client: SupabaseClient,
) : TravellerAuthBackend {
    override suspend fun awaitInitialization() = client.auth.awaitInitialization()

    override fun sessionState(): TravellerSessionState = when (val status = client.auth.sessionStatus.value) {
        SessionStatus.Initializing -> TravellerSessionState.Initializing
        is SessionStatus.NotAuthenticated -> TravellerSessionState.NotAuthenticated
        is SessionStatus.RefreshFailure -> TravellerSessionState.RefreshFailure
        is SessionStatus.Authenticated -> TravellerSessionState.Authenticated(
            userId = status.session.user?.id,
            accessTokenExpired = status.session.expiresAt <= Clock.System.now(),
        )
    }

    override suspend fun signInAnonymously() {
        client.auth.signInAnonymously()
    }
}

class SharedPreferencesTravellerIdentityStore(
    context: Context,
) : TravellerIdentityStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun expectedTravellerUserId(): String? =
        preferences.getString(EXPECTED_USER_ID_KEY, null)?.takeIf(String::isNotBlank)

    @SuppressLint("UseKtx") // commit() must finish before any cloud operation may proceed.
    override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean {
        val existing = expectedTravellerUserId()
        if (existing != null) return existing == userId
        return preferences.edit().putString(EXPECTED_USER_ID_KEY, userId).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "journey-continuity-identity"
        const val EXPECTED_USER_ID_KEY = "expectedTravellerUserId"
    }
}
