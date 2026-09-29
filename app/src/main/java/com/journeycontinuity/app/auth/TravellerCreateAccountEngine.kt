package com.journeycontinuity.app.auth

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class TravellerProfileIdentity(val fullName: String, val handle: String)

/** Mirrors the stored profile's SQL checks; the database remains authoritative. */
internal object TravellerProfileIdentityInput {
    private val handlePattern = Regex("^[a-z][a-z0-9_]{1,22}[a-z0-9]$")

    fun fullName(value: String): String? = value.trim(' ').takeIf { name ->
        name.codePointCount(0, name.length) in 1..100 &&
            name.any { !it.isWhitespace() } && name.none { Character.isISOControl(it) }
    }

    fun handle(value: String): String? {
        val normalized = value.trim(' ').lowercase(Locale.ROOT).removePrefix("@")
        return normalized.takeIf(handlePattern::matches)
    }
}

internal data class CreateAccountAuthIdentity(
    val userId: String,
    val isAnonymous: Boolean?,
    val email: String?,
    val emailConfirmed: Boolean,
)

internal sealed interface ProfileFinalization {
    data object Created : ProfileFinalization
    data class AlreadyCompleted(val matchesRequest: Boolean) : ProfileFinalization
    data object HandleUnavailable : ProfileFinalization
}

internal interface TravellerCreateAccountGateway {
    fun sessionUserId(): String?
    suspend fun currentIdentity(): CreateAccountAuthIdentity
    suspend fun requestEmailChange(email: String)
    /** Returns the verified session's user ID when GoTrue issues a new session. */
    suspend fun verifyEmailChange(email: String, code: String): String?
    suspend fun hasTravellerProfile(): Boolean
    suspend fun checkHandleAvailability(handle: String): Boolean
    suspend fun finalizeProfile(identity: TravellerProfileIdentity): ProfileFinalization
    suspend fun clearMismatchedLocalSession(expectedOwnerId: String)
}

internal class EmailAlreadyInUseException : Exception()

enum class HandleAvailability { AVAILABLE, UNAVAILABLE, INVALID, UNKNOWN }
enum class CreateAccountCodeIssue { INVALID_OR_EXPIRED }
enum class CreateAccountProfileIssue {
    NOT_SAVED,
    HANDLE_UNAVAILABLE,
    SERVICE_UNAVAILABLE,
    VERIFICATION_NOT_REQUIRED,
}
enum class CreateAccountFailure {
    INVALID_FULL_NAME,
    INVALID_HANDLE,
    INVALID_EMAIL,
    OWNER_UNAVAILABLE,
    OWNER_MISMATCH,
    AUTH_UNAVAILABLE,
    EMAIL_UNAVAILABLE,
    EMAIL_REQUEST_FAILED,
    CODE_REQUIRED,
    CANCELLED,
}

sealed interface CreateAccountState {
    data object Ready : CreateAccountState
    data object EmailChangeRequesting : CreateAccountState
    data class CodeRequired(val issue: CreateAccountCodeIssue? = null) : CreateAccountState
    data object Verifying : CreateAccountState
    data object ProfileSaving : CreateAccountState
    data class ProfileRequired(val issue: CreateAccountProfileIssue) : CreateAccountState
    data object Completed : CreateAccountState
    data object AlreadyCompleted : CreateAccountState
    data class Failed(val reason: CreateAccountFailure) : CreateAccountState
    data object Busy : CreateAccountState
}

/** Owns no Journey rows and never persists a new owner or an OTP. */
class TravellerCreateAccountEngine internal constructor(
    private val gateway: TravellerCreateAccountGateway,
    private val ownerStore: TravellerIdentityStore,
    private val identityCoordinator: TravellerIdentityCoordinator,
) {
    private data class Pending(
        val ownerId: String,
        val email: String,
        val identity: TravellerProfileIdentity,
    )

    private val attemptMutex = Mutex()
    private val mutableState = MutableStateFlow<CreateAccountState>(CreateAccountState.Ready)
    val state: StateFlow<CreateAccountState> = mutableState
    private var pending: Pending? = null
    @Volatile private var cancellationRequested = false

    suspend fun checkHandleAvailability(handle: String): HandleAvailability {
        val normalized = TravellerProfileIdentityInput.handle(handle) ?: return HandleAvailability.INVALID
        return try {
            val owner = guardedOwner() ?: return HandleAvailability.UNKNOWN
            if (gateway.currentIdentity().userId != owner) return HandleAvailability.UNKNOWN
            if (gateway.checkHandleAvailability(normalized)) HandleAvailability.AVAILABLE
            else HandleAvailability.UNAVAILABLE
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            HandleAvailability.UNKNOWN
        }
    }

    suspend fun begin(fullName: String, handle: String, email: String): CreateAccountState {
        if (!attemptMutex.tryLock()) return CreateAccountState.Busy
        try {
            if (pending != null) return CreateAccountState.Busy
            cancellationRequested = false
            val identity = validateIdentity(fullName, handle) ?: return mutableState.value
            val proposedEmail = email.trim().takeIf(::validEmail)
                ?: return finish(CreateAccountState.Failed(CreateAccountFailure.INVALID_EMAIL))
            val owner = guardedOwner()
                ?: return ownerFailure()
            val authUser = gateway.currentIdentity()
            if (authUser.userId != owner) return mismatch(owner)
            if (authUser.isAnonymous != true) {
                if (!isVerified(authUser)) return finish(CreateAccountState.Failed(CreateAccountFailure.AUTH_UNAVAILABLE))
                val hasProfile = try { gateway.hasTravellerProfile() } catch (_: Exception) {
                    return finish(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.SERVICE_UNAVAILABLE))
                }
                return if (hasProfile) finish(CreateAccountState.AlreadyCompleted)
                else finish(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.NOT_SAVED))
            }
            if (authUser.emailConfirmed) return finish(CreateAccountState.Failed(CreateAccountFailure.AUTH_UNAVAILABLE))

            mutableState.value = CreateAccountState.EmailChangeRequesting
            pending = Pending(owner, proposedEmail, identity)
            try {
                gateway.requestEmailChange(proposedEmail)
            } catch (_: EmailAlreadyInUseException) {
                return finish(CreateAccountState.Failed(CreateAccountFailure.EMAIL_UNAVAILABLE))
            }
            if (cancellationRequested) return finish(CreateAccountState.Failed(CreateAccountFailure.CANCELLED))
            if (guardedOwner() != owner) return mismatch(owner)
            val afterRequest = gateway.currentIdentity()
            if (afterRequest.userId != owner) return mismatch(owner)
            // A code must genuinely be required. If the Auth deployment verifies
            // immediately, leave the same-ID account intact for profile completion.
            if (isVerified(afterRequest)) return finish(CreateAccountState.ProfileRequired(
                CreateAccountProfileIssue.VERIFICATION_NOT_REQUIRED,
            ))
            if (afterRequest.isAnonymous != true) return finish(CreateAccountState.Failed(
                CreateAccountFailure.AUTH_UNAVAILABLE,
            ))
            return finish(CreateAccountState.CodeRequired())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return finish(CreateAccountState.Failed(CreateAccountFailure.EMAIL_REQUEST_FAILED))
        } finally {
            if (mutableState.value !is CreateAccountState.CodeRequired) pending = null
            if (mutableState.value == CreateAccountState.EmailChangeRequesting) {
                mutableState.value = CreateAccountState.Ready
                pending = null
            }
            attemptMutex.unlock()
        }
    }

    suspend fun submitCode(code: String): CreateAccountState {
        if (!attemptMutex.tryLock()) return CreateAccountState.Busy
        try {
            val current = pending ?: return when (mutableState.value) {
                CreateAccountState.Completed -> CreateAccountState.Completed
                CreateAccountState.AlreadyCompleted -> CreateAccountState.AlreadyCompleted
                else -> finish(CreateAccountState.Failed(CreateAccountFailure.CODE_REQUIRED))
            }
            if (!Regex("^[0-9]{6}$").matches(code)) {
                return finish(CreateAccountState.CodeRequired(CreateAccountCodeIssue.INVALID_OR_EXPIRED))
            }
            if (cancellationRequested) return finish(CreateAccountState.Failed(CreateAccountFailure.CANCELLED))
            if (guardedOwner() != current.ownerId) return mismatch(current.ownerId)
            mutableState.value = CreateAccountState.Verifying
            // Verification may have succeeded server-side even if the caller is
            // cancelled. Finish the same-ID check and profile outcome together.
            return withContext(NonCancellable) {
                val verifiedId = try {
                    gateway.verifyEmailChange(current.email, code)
                } catch (_: Exception) {
                    // The response may have been lost after GoTrue consumed the
                    // code. Probe current Auth truth before calling it invalid.
                    val observed = try { gateway.currentIdentity() } catch (_: Exception) { null }
                    if (observed != null && observed.userId != current.ownerId) {
                        return@withContext mismatch(current.ownerId)
                    }
                    if (gateway.sessionUserId() != null && gateway.sessionUserId() != current.ownerId) {
                        return@withContext mismatch(current.ownerId)
                    }
                    if (observed == null || observed.userId != current.ownerId || !isVerified(observed) ||
                        !observed.email.equals(current.email, ignoreCase = true)) {
                        return@withContext finish(CreateAccountState.CodeRequired(
                            CreateAccountCodeIssue.INVALID_OR_EXPIRED,
                        ))
                    }
                    null
                }
                if (verifiedId != null && verifiedId != current.ownerId) return@withContext mismatch(current.ownerId)
                val owner = guardedOwner()
                if (owner != current.ownerId) return@withContext mismatch(current.ownerId)
                val verified = try { gateway.currentIdentity() } catch (_: Exception) {
                    return@withContext finish(CreateAccountState.ProfileRequired(
                        CreateAccountProfileIssue.SERVICE_UNAVAILABLE,
                    ))
                }
                if (verified.userId != current.ownerId) return@withContext mismatch(current.ownerId)
                if (!isVerified(verified) || !verified.email.equals(current.email, ignoreCase = true)) {
                    return@withContext finish(CreateAccountState.CodeRequired(
                        CreateAccountCodeIssue.INVALID_OR_EXPIRED,
                    ))
                }
                pending = null
                saveProfile(current.ownerId, current.identity)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return finish(CreateAccountState.Failed(CreateAccountFailure.AUTH_UNAVAILABLE))
        } finally {
            if (mutableState.value !is CreateAccountState.CodeRequired) pending = null
            if (mutableState.value == CreateAccountState.Verifying) {
                mutableState.value = CreateAccountState.ProfileRequired(CreateAccountProfileIssue.SERVICE_UNAVAILABLE)
                pending = null
            }
            attemptMutex.unlock()
        }
    }

    suspend fun completeProfile(fullName: String, handle: String): CreateAccountState {
        if (!attemptMutex.tryLock()) return CreateAccountState.Busy
        try {
            if (pending != null) return CreateAccountState.Busy
            val identity = validateIdentity(fullName, handle) ?: return mutableState.value
            val owner = guardedOwner()
                ?: return ownerFailure()
            val authUser = gateway.currentIdentity()
            if (authUser.userId != owner) return mismatch(owner)
            if (!isVerified(authUser)) return finish(CreateAccountState.Failed(CreateAccountFailure.AUTH_UNAVAILABLE))
            return withContext(NonCancellable) { saveProfile(owner, identity) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return finish(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.SERVICE_UNAVAILABLE))
        } finally {
            attemptMutex.unlock()
        }
    }

    suspend fun cancel() {
        cancellationRequested = true
        attemptMutex.lock()
        try {
            pending = null
            if (mutableState.value !is CreateAccountState.ProfileRequired &&
                mutableState.value != CreateAccountState.Completed &&
                mutableState.value != CreateAccountState.AlreadyCompleted
            ) mutableState.value = CreateAccountState.Ready
        } finally {
            attemptMutex.unlock()
        }
    }

    private suspend fun saveProfile(owner: String, identity: TravellerProfileIdentity): CreateAccountState {
        if (guardedOwner() != owner) return mismatch(owner)
        mutableState.value = CreateAccountState.ProfileSaving
        try {
            when (val result = gateway.finalizeProfile(identity)) {
                ProfileFinalization.Created -> Unit
                is ProfileFinalization.AlreadyCompleted -> if (!result.matchesRequest) {
                    return finish(CreateAccountState.AlreadyCompleted)
                }
                ProfileFinalization.HandleUnavailable -> return finish(CreateAccountState.ProfileRequired(
                    CreateAccountProfileIssue.HANDLE_UNAVAILABLE,
                ))
            }
            if (guardedOwner() != owner) return mismatch(owner)
            val user = gateway.currentIdentity()
            if (user.userId != owner) return mismatch(owner)
            if (!isVerified(user) || !gateway.hasTravellerProfile()) {
                return finish(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.SERVICE_UNAVAILABLE))
            }
            return finish(CreateAccountState.Completed)
        } catch (_: Exception) {
            return finish(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.SERVICE_UNAVAILABLE))
        }
    }

    private suspend fun guardedOwner(): String? {
        val expected = ownerStore.expectedTravellerUserId()?.takeIf(String::isNotBlank) ?: return null
        if (gateway.sessionUserId() != expected) return null
        val outcome = identityCoordinator.resolve() as? TravellerAuthOutcome.Authenticated ?: return null
        return expected.takeIf { outcome.traveller.userId == it &&
            ownerStore.expectedTravellerUserId() == it && gateway.sessionUserId() == it }
    }

    private suspend fun mismatch(expectedOwnerId: String): CreateAccountState {
        runCatching { gateway.clearMismatchedLocalSession(expectedOwnerId) }
        return finish(CreateAccountState.Failed(CreateAccountFailure.OWNER_MISMATCH))
    }

    private suspend fun ownerFailure(): CreateAccountState {
        val expected = ownerStore.expectedTravellerUserId()
        val sessionId = gateway.sessionUserId()
        return if (expected != null && sessionId != null && sessionId != expected) mismatch(expected)
        else finish(CreateAccountState.Failed(CreateAccountFailure.OWNER_UNAVAILABLE))
    }

    private fun validateIdentity(fullName: String, handle: String): TravellerProfileIdentity? {
        val name = TravellerProfileIdentityInput.fullName(fullName)
        if (name == null) {
            finish(CreateAccountState.Failed(CreateAccountFailure.INVALID_FULL_NAME))
            return null
        }
        val normalizedHandle = TravellerProfileIdentityInput.handle(handle)
        if (normalizedHandle == null) {
            finish(CreateAccountState.Failed(CreateAccountFailure.INVALID_HANDLE))
            return null
        }
        return TravellerProfileIdentity(name, normalizedHandle)
    }

    private fun validEmail(value: String) = value.length in 3..254 &&
        value.indexOf('@') in 1 until value.lastIndex && value.none(Char::isWhitespace)

    private fun isVerified(user: CreateAccountAuthIdentity) = user.isAnonymous == false &&
        user.emailConfirmed && !user.email.isNullOrBlank()

    private fun finish(next: CreateAccountState): CreateAccountState = next.also { mutableState.value = it }
}
