package com.journeycontinuity.app.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.time.Clock

sealed interface ReturningLoginState {
    data object Idle : ReturningLoginState
    data object EmailRequestStarted : ReturningLoginState
    data object CodeRequested : ReturningLoginState
    data object VerificationInProgress : ReturningLoginState
    data object VerifiedCandidateAvailable : ReturningLoginState
    data object Admitted : ReturningLoginState
    data class Rejected(val reason: ReturningLoginRejection) : ReturningLoginState
    data class Failed(val reason: ReturningLoginFailure) : ReturningLoginState
    data object Busy : ReturningLoginState
}

enum class ReturningLoginRejection {
    DIFFERENT_OWNER,
    TRAVELLER_PROFILE_REQUIRED,
    RECOVERY_REQUIRED,
}

enum class ReturningLoginFailure {
    CANCELLED,
    INVALID_EMAIL,
    INVALID_CODE,
    REQUEST_FAILED,
    VERIFICATION_FAILED,
    PROFILE_UNAVAILABLE,
    ADMISSION_RECOVERY_REQUIRED,
}

internal interface PrimaryTravellerSession {
    fun userId(): String?
    suspend fun import(session: UserSession)
    suspend fun clearLocalSession()
}

internal class SupabasePrimaryTravellerSession(private val client: SupabaseClient) : PrimaryTravellerSession {
    override fun userId(): String? =
        (client.auth.sessionStatus.value as? SessionStatus.Authenticated)?.session?.user?.id

    override suspend fun import(session: UserSession) {
        client.auth.importSession(session)
    }

    override suspend fun clearLocalSession() {
        client.auth.clearSession() // local only, unlike signOut()
    }
}

/** No UI, session import or owner write occurs before the final gated recheck. */
class ReturningTravellerLoginEngine internal constructor(
    private val attempts: ReturningLoginAttemptFactory,
    private val boundary: FirstOwnerAdoptionBoundary,
    private val ownerStore: TravellerIdentityStore,
    private val primary: PrimaryTravellerSession,
    private val identityCoordinator: TravellerIdentityCoordinator,
    private val admissionGate: OwnerAdmissionGate,
) {
    private val attemptMutex = Mutex()
    private val mutableState = MutableStateFlow<ReturningLoginState>(ReturningLoginState.Idle)
    val state: StateFlow<ReturningLoginState> = mutableState
    private var attempt: ReturningLoginAttempt? = null
    private var requestedEmail: String? = null
    @Volatile private var cancellationRequested = false

    suspend fun requestCode(email: String): ReturningLoginState {
        if (!attemptMutex.tryLock()) return ReturningLoginState.Busy
        try {
            if (attempt != null) return ReturningLoginState.Busy
            cancellationRequested = false
            val normalized = email.trim().lowercase(Locale.ROOT)
            if (normalized.length !in 3..254 || '@' !in normalized || normalized.any(Char::isWhitespace)) {
                return ReturningLoginState.Failed(ReturningLoginFailure.INVALID_EMAIL).also(::setState)
            }
            setState(ReturningLoginState.EmailRequestStarted)
            val newAttempt = attempts.create()
            attempt = newAttempt
            requestedEmail = normalized
            return try {
                newAttempt.requestCode(normalized) // OTP createUser=false at the client boundary
                if (cancellationRequested) ReturningLoginState.Failed(ReturningLoginFailure.CANCELLED)
                    .also(::setState)
                else ReturningLoginState.CodeRequested.also(::setState)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                ReturningLoginState.Failed(ReturningLoginFailure.REQUEST_FAILED).also(::setState)
            }.also { result ->
                if (result is ReturningLoginState.Failed) discardAttempt()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return ReturningLoginState.Failed(ReturningLoginFailure.REQUEST_FAILED).also(::setState)
        } finally {
            if (mutableState.value != ReturningLoginState.CodeRequested) {
                withContext(NonCancellable) { discardAttempt() }
                if (mutableState.value == ReturningLoginState.EmailRequestStarted) {
                    setState(ReturningLoginState.Idle)
                }
            }
            attemptMutex.unlock()
        }
    }

    suspend fun submitCode(code: String): ReturningLoginState {
        if (!attemptMutex.tryLock()) return ReturningLoginState.Busy
        try {
            val current = attempt ?: return ReturningLoginState.Failed(
                ReturningLoginFailure.VERIFICATION_FAILED,
            ).also(::setState)
            val email = requestedEmail ?: return ReturningLoginState.Failed(
                ReturningLoginFailure.VERIFICATION_FAILED,
            ).also(::setState)
            if (!CODE_PATTERN.matches(code)) {
                return ReturningLoginState.Failed(ReturningLoginFailure.INVALID_CODE).also(::setState)
            }
            setState(ReturningLoginState.VerificationInProgress)
            val candidate = try {
                current.verifyCode(email, code)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                return ReturningLoginState.Failed(ReturningLoginFailure.VERIFICATION_FAILED).also(::setState)
            }
            setState(ReturningLoginState.VerifiedCandidateAvailable)
            val hasProfile = try {
                current.hasTravellerProfile()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                return ReturningLoginState.Failed(ReturningLoginFailure.PROFILE_UNAVAILABLE).also(::setState)
            }
            if (!hasProfile) return ReturningLoginState.Rejected(
                ReturningLoginRejection.TRAVELLER_PROFILE_REQUIRED,
            ).also(::setState)
            if (cancellationRequested) return ReturningLoginState.Failed(
                ReturningLoginFailure.CANCELLED,
            ).also(::setState)

            val callerContext = currentCoroutineContext()
            // Once the final owner decision begins, complete the durable owner
            // write and primary import together even if the caller is cancelled.
            return withContext(NonCancellable) {
                val decision = admissionGate.withLock {
                    if (cancellationRequested || !callerContext.isActive) return@withLock ReturningLoginState.Failed(
                        ReturningLoginFailure.CANCELLED,
                    )
                    // A Journey start and the existing coordinator's anonymous sign-in
                    // use the same gate. Snapshot and first-owner persistence cannot
                    // race those two owner-establishing paths in this process.
                    when (boundary.assess(candidate.userId, true)) {
                        OwnerAdoptionDecision.DIFFERENT_OWNER_REJECTED -> ReturningLoginState.Rejected(
                            ReturningLoginRejection.DIFFERENT_OWNER,
                        )
                        OwnerAdoptionDecision.TRAVELLER_PROFILE_REQUIRED -> ReturningLoginState.Rejected(
                            ReturningLoginRejection.TRAVELLER_PROFILE_REQUIRED,
                        )
                        OwnerAdoptionDecision.LOCAL_OWNER_STATE_PRESENT,
                        OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN -> ReturningLoginState.Rejected(
                            ReturningLoginRejection.RECOVERY_REQUIRED,
                        )
                        OwnerAdoptionDecision.SAME_OWNER_RECOVERY -> {
                            if (cancellationRequested || !callerContext.isActive)
                                ReturningLoginState.Failed(ReturningLoginFailure.CANCELLED)
                            else if (candidate.session.expiresAt <= Clock.System.now())
                                ReturningLoginState.Failed(ReturningLoginFailure.VERIFICATION_FAILED)
                            else importCandidate(candidate)
                        }
                        OwnerAdoptionDecision.FIRST_OWNER_ELIGIBLE -> {
                            if (cancellationRequested || !callerContext.isActive)
                                return@withLock ReturningLoginState.Failed(ReturningLoginFailure.CANCELLED)
                            if (candidate.session.expiresAt <= Clock.System.now())
                                return@withLock ReturningLoginState.Failed(ReturningLoginFailure.VERIFICATION_FAILED)
                            // Durable owner first. An import failure leaves B bound with
                            // no usable session, requiring same-ID recovery on restart.
                            val persisted = ownerStore.persistExpectedTravellerUserIdIfAbsent(candidate.userId)
                            if (!persisted || ownerStore.expectedTravellerUserId() != candidate.userId) {
                                ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED)
                            } else importCandidate(candidate)
                        }
                    }
                }
                if (decision != ReturningLoginState.Admitted) decision
                else {
                    val resolved = identityCoordinator.resolve()
                    if (resolved is TravellerAuthOutcome.Authenticated &&
                        resolved.traveller.userId == candidate.userId
                    ) ReturningLoginState.Admitted
                    else ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED)
                }.also(::setState)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED).also(::setState)
        } finally {
            withContext(NonCancellable) { discardAttempt() }
            if (mutableState.value == ReturningLoginState.VerificationInProgress ||
                mutableState.value == ReturningLoginState.VerifiedCandidateAvailable
            ) setState(ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED))
            attemptMutex.unlock()
        }
    }

    suspend fun cancel() {
        cancellationRequested = true
        attemptMutex.lock()
        try {
            withContext(NonCancellable) { discardAttempt() }
            if (mutableState.value != ReturningLoginState.Admitted) setState(ReturningLoginState.Idle)
        } finally {
            attemptMutex.unlock()
        }
    }

    private suspend fun importCandidate(candidate: VerifiedLoginCandidate): ReturningLoginState = try {
        primary.import(candidate.session)
        if (primary.userId() != candidate.userId ||
            ownerStore.expectedTravellerUserId() != candidate.userId
        ) {
            clearWrongPrimary(candidate.userId)
            ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED)
        } else ReturningLoginState.Admitted
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        clearWrongPrimary(candidate.userId)
        ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED)
    }

    private suspend fun clearWrongPrimary(expectedUserId: String) {
        // Never clear a matching owner session after an uncertain import.
        if (primary.userId() != null && primary.userId() != expectedUserId) {
            primary.clearLocalSession()
        }
    }

    private suspend fun discardAttempt() {
        val old = attempt
        attempt = null
        requestedEmail = null
        if (old != null) runCatching { old.discard() }
    }

    private fun setState(next: ReturningLoginState) { mutableState.value = next }

    private companion object { val CODE_PATTERN = Regex("^[0-9]{6}$") }
}
