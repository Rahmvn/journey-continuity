package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.auth.CreateAccountCodeIssue
import com.journeycontinuity.app.auth.CreateAccountFailure
import com.journeycontinuity.app.auth.CreateAccountProfileIssue
import com.journeycontinuity.app.auth.CreateAccountState
import com.journeycontinuity.app.auth.HandleAvailability
import com.journeycontinuity.app.auth.ReturningLoginFailure
import com.journeycontinuity.app.auth.ReturningLoginRejection
import com.journeycontinuity.app.auth.ReturningLoginState
import com.journeycontinuity.app.auth.TravellerAuthOutcome
import com.journeycontinuity.app.auth.TravellerProfileIdentityInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class TravellerRootRoute {
    CHECKING, INTRO, IDENTITY_CHOICE, CREATE_DETAILS, CREATE_OTP, LOGIN_EMAIL, LOGIN_OTP,
    ADMITTED_EXISTING, ADMITTED_NEW, RECOVERY,
}

data class TravellerRootState(
    val route: TravellerRootRoute = TravellerRootRoute.CHECKING,
    val fullName: String = "",
    val handle: String = "",
    val createEmail: String = "",
    val loginEmail: String = "",
    val code: String = "",
    val handleFeedback: CreateAccountHandleFeedback? = null,
    val createCodeInvalid: Boolean = false,
    val loginFeedback: ReturningLoginOtpFeedback = ReturningLoginOtpFeedback.NEUTRAL,
    val busy: Boolean = false,
    val notice: String? = null,
    val loginAttemptActive: Boolean = false,
    val profileRequired: Boolean = false,
    val recoveryLoginAllowed: Boolean = false,
)

/** Activity-scoped. Application-scoped auth engines retain an OTP attempt across Activity recreation. */
class TravellerRootViewModel(
    private val auth: TravellerRootAuth,
    private val intro: IntroCompletionPreference,
    private val createProgress: CreateAccountProgressPreference,
    private val createEntry: CreateAccountEntryPreference,
    private val loginEntry: LoginEntryPreference,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TravellerRootState())
    val state: StateFlow<TravellerRootState> = mutableState
    private var establishedCreateOwner: String? = null
    private var recoveringExistingOwner = false
    private var handleGeneration = 0L
    private var operationGeneration = 0L
    private var handleJob: Job? = null

    init { assessStartup() }

    fun assessStartup() = viewModelScope.launch {
        mutableState.value = TravellerRootState()
        recoveringExistingOwner = false
        try {
            when (val outcome = auth.resolve()) {
                is TravellerAuthOutcome.Authenticated -> {
                    val pending = createProgress.pendingOwnerId()
                    when {
                        pending == outcome.traveller.userId ||
                            (pending == null && createEntry.isPending()) -> {
                            establishedCreateOwner = outcome.traveller.userId
                            route(TravellerRootRoute.CREATE_DETAILS)
                        }
                        pending == null -> route(TravellerRootRoute.ADMITTED_EXISTING)
                        else -> recovery("This installation's account progress does not match its Traveller owner.")
                    }
                }
                TravellerAuthOutcome.TravellerIdentityNotEstablished ->
                    route(if (!intro.isCompleted()) TravellerRootRoute.INTRO
                        else if (createEntry.isPending()) TravellerRootRoute.CREATE_DETAILS
                        else if (loginEntry.shouldReturnToEmailEntry()) TravellerRootRoute.LOGIN_EMAIL
                        else TravellerRootRoute.IDENTITY_CHOICE)
                TravellerAuthOutcome.TravellerIdentityRecoveryRequired -> {
                    recoveringExistingOwner = true
                    if (loginEntry.shouldReturnToEmailEntry()) route(TravellerRootRoute.LOGIN_EMAIL)
                    else recovery("Recover this Traveller with the same verified email account.",
                        allowSameOwnerLogin = true)
                }
                else -> recovery("This Traveller identity needs a safe recovery before continuing.")
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            recovery("Authentication is temporarily unavailable. Try again.")
        }
    }

    fun completeIntro() {
        if (state.value.route != TravellerRootRoute.INTRO) return
        if (intro.complete()) route(TravellerRootRoute.IDENTITY_CHOICE)
        else recovery("Could not save the introduction. Try again.")
    }

    fun chooseCreateAccount() {
        if (state.value.route != TravellerRootRoute.IDENTITY_CHOICE &&
            state.value.route != TravellerRootRoute.LOGIN_EMAIL &&
            state.value.route != TravellerRootRoute.LOGIN_OTP) return
        if (recoveringExistingOwner) return notice(
            "This installation is bound to a Traveller. Recover that same account before continuing.")
        if (state.value.busy) return
        if (!createEntry.clear()) return recovery("Could not leave account creation safely. Try again.")
        if (!loginEntry.clear()) return recovery("Could not clear the previous log in entry. Try again.")
        val previous = state.value.route
        invalidateOperation()
        if (previous == TravellerRootRoute.LOGIN_OTP) {
            val generation = operationGeneration
            busy(true)
            viewModelScope.launch {
                auth.returningLogin?.cancel()
                if (generation == operationGeneration) toCreateDetails()
            }
        } else toCreateDetails()
    }

    fun chooseLogIn() {
        if (state.value.route != TravellerRootRoute.IDENTITY_CHOICE &&
            state.value.route != TravellerRootRoute.CREATE_DETAILS &&
            state.value.route != TravellerRootRoute.CREATE_OTP) return
        if (establishedCreateOwner != null) {
            notice("This device is already bound to this Traveller. Complete this account or recover the same owner.")
            return
        }
        if (state.value.busy) return
        if (!createEntry.clear()) return recovery("Could not leave account creation safely. Try again.")
        if (!loginEntry.markEmailEntry()) return recovery("Could not save the log in entry. Try again.")
        val previous = state.value.route
        invalidateOperation()
        if (previous == TravellerRootRoute.CREATE_OTP) viewModelScope.launch { auth.createAccount?.cancel() }
        mutableState.value = state.value.copy(route = TravellerRootRoute.LOGIN_EMAIL,
            code = "", notice = null, loginAttemptActive = false)
    }

    fun recoverSameOwnerWithEmail() {
        if (state.value.route == TravellerRootRoute.RECOVERY && state.value.recoveryLoginAllowed) {
            if (loginEntry.markEmailEntry()) route(TravellerRootRoute.LOGIN_EMAIL)
        }
    }

    private fun toCreateDetails() {
        mutableState.value = state.value.copy(route = TravellerRootRoute.CREATE_DETAILS,
            code = "", busy = false, notice = null, loginAttemptActive = false)
    }

    fun onFullNameChange(value: String) {
        if (!state.value.busy) mutableState.value = state.value.copy(fullName = value)
    }
    fun onEmailChange(value: String) {
        if (!state.value.busy) mutableState.value = state.value.copy(createEmail = value)
    }
    fun onLoginEmailChange(value: String) {
        if (!state.value.busy) mutableState.value = state.value.copy(loginEmail = value)
    }
    fun onCodeChange(value: String) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(code = value.filter { it in '0'..'9' }.take(6),
            createCodeInvalid = false, loginFeedback = ReturningLoginOtpFeedback.NEUTRAL)
    }

    fun onHandleChange(value: String) {
        if (state.value.busy) return
        handleGeneration++
        handleJob?.cancel()
        mutableState.value = state.value.copy(handle = value, handleFeedback = null)
        // The authenticated RPC cannot run while this fresh installation is ownerless.
        if (establishedCreateOwner != null && state.value.route == TravellerRootRoute.CREATE_DETAILS) {
            checkHandle(value)
        }
    }

    private fun checkHandle(raw: String, afterCheck: (suspend (HandleAvailability) -> Unit)? = null) {
        val engine = auth.createAccount ?: return
        val generation = ++handleGeneration
        handleJob?.cancel()
        if (TravellerProfileIdentityInput.handle(raw) == null) return
        mutableState.value = state.value.copy(handleFeedback =
            CreateAccountHandleFeedback(raw, CreateAccountHandleStatus.CHECKING), notice = null)
        handleJob = viewModelScope.launch {
            val result = engine.checkHandleAvailability(raw)
            if (generation != handleGeneration || state.value.handle != raw ||
                state.value.route != TravellerRootRoute.CREATE_DETAILS) return@launch
            val status = when (result) {
                HandleAvailability.AVAILABLE -> CreateAccountHandleStatus.AVAILABLE
                HandleAvailability.UNAVAILABLE -> CreateAccountHandleStatus.TAKEN
                HandleAvailability.INVALID, HandleAvailability.UNKNOWN -> null
            }
            mutableState.value = state.value.copy(handleFeedback = status?.let {
                CreateAccountHandleFeedback(raw, it)
            }, notice = if (result == HandleAvailability.UNKNOWN)
                "Could not check this handle. Try again." else null)
            afterCheck?.invoke(result)
        }
    }

    fun continueCreateAccount() {
        val snapshot = state.value
        if (snapshot.route != TravellerRootRoute.CREATE_DETAILS || snapshot.busy) return
        if (TravellerProfileIdentityInput.fullName(snapshot.fullName) == null ||
            TravellerProfileIdentityInput.handle(snapshot.handle) == null ||
            !validEmail(snapshot.createEmail)) {
            notice("Enter a valid full name, handle, and email address.")
            return
        }
        if (snapshot.handleFeedback?.takeIf { it.handle == snapshot.handle }?.status ==
            CreateAccountHandleStatus.TAKEN) return
        val engine = auth.createAccount ?: return recovery("Account creation is unavailable. Try again later.")
        if (!createEntry.markPending()) return recovery("Could not save account progress. Try again.")
        val operation = ++operationGeneration
        busy(true)
        viewModelScope.launch {
            try {
                if (establishedCreateOwner == null) {
                    val established = auth.establishAnonymousOwner() as? TravellerAuthOutcome.Authenticated
                    if (operation != operationGeneration) return@launch
                    if (established == null) return@launch recovery(
                        "Could not safely establish this Traveller. Try again or recover this installation.")
                    establishedCreateOwner = established.traveller.userId
                }
                if (createProgress.pendingOwnerId() != establishedCreateOwner &&
                    !createProgress.markStarted(establishedCreateOwner!!)) return@launch recovery(
                        "Could not save account progress for this Traveller. Recover this installation.")
                if (state.value.handle != snapshot.handle || state.value.fullName != snapshot.fullName ||
                    state.value.createEmail != snapshot.createEmail || state.value.route != TravellerRootRoute.CREATE_DETAILS) {
                    busy(false)
                    return@launch
                }
                val feedback = state.value.handleFeedback?.takeIf { it.handle == snapshot.handle }?.status
                if (feedback != CreateAccountHandleStatus.AVAILABLE) {
                    busy(false)
                    checkHandle(snapshot.handle) { result ->
                        if (result == HandleAvailability.AVAILABLE && operation == operationGeneration &&
                            state.value.handle == snapshot.handle && state.value.fullName == snapshot.fullName &&
                            state.value.createEmail == snapshot.createEmail) {
                            busy(true)
                            handleCreateResult(if (snapshot.profileRequired)
                                engine.completeProfile(snapshot.fullName, snapshot.handle)
                            else engine.begin(snapshot.fullName, snapshot.handle, snapshot.createEmail), operation)
                        }
                    }
                    return@launch
                }
                handleCreateResult(if (snapshot.profileRequired)
                    engine.completeProfile(snapshot.fullName, snapshot.handle)
                else engine.begin(snapshot.fullName, snapshot.handle, snapshot.createEmail), operation)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                recovery("Account creation is temporarily unavailable. Try again.")
            }
        }
    }

    fun verifyCreateCode() {
        val snapshot = state.value
        if (snapshot.route != TravellerRootRoute.CREATE_OTP || snapshot.busy || snapshot.code.length != 6) return
        val engine = auth.createAccount ?: return recovery("Account verification is unavailable.")
        val operation = ++operationGeneration
        busy(true)
        viewModelScope.launch {
            try { handleCreateResult(engine.submitCode(snapshot.code), operation) }
            catch (error: Throwable) {
                if (error is CancellationException) throw error
                recovery("Could not verify this code. Request a new code and try again.")
            }
        }
    }

    private suspend fun handleCreateResult(result: CreateAccountState, operation: Long) {
        if (operation != operationGeneration) return
        when (result) {
            is CreateAccountState.CodeRequired -> mutableState.value = state.value.copy(
                route = TravellerRootRoute.CREATE_OTP, busy = false,
                createCodeInvalid = result.issue == CreateAccountCodeIssue.INVALID_OR_EXPIRED,
                notice = null)
            CreateAccountState.Completed, CreateAccountState.AlreadyCompleted -> {
                val resolved = auth.resolve() as? TravellerAuthOutcome.Authenticated
                if (resolved?.traveller?.userId == establishedCreateOwner &&
                    createProgress.clear(establishedCreateOwner!!) && createEntry.clear())
                    route(TravellerRootRoute.ADMITTED_NEW)
                else recovery("This Traveller could not be safely admitted. Recover the same owner.")
            }
            is CreateAccountState.ProfileRequired -> mutableState.value = state.value.copy(
                route = TravellerRootRoute.CREATE_DETAILS, busy = false, profileRequired = true,
                handleFeedback = if (result.issue == CreateAccountProfileIssue.HANDLE_UNAVAILABLE)
                    CreateAccountHandleFeedback(state.value.handle, CreateAccountHandleStatus.TAKEN)
                else state.value.handleFeedback,
                notice = when (result.issue) {
                    CreateAccountProfileIssue.HANDLE_UNAVAILABLE -> "That handle is taken. Try another."
                    CreateAccountProfileIssue.VERIFICATION_NOT_REQUIRED -> "Email is verified. Continue to save your profile."
                    else -> "Your email is verified. Retry saving your profile."
                })
            is CreateAccountState.Failed -> when (result.reason) {
                CreateAccountFailure.OWNER_MISMATCH, CreateAccountFailure.OWNER_UNAVAILABLE ->
                    recovery("This device's Traveller owner could not be verified. Recover the same owner.")
                else -> mutableState.value = state.value.copy(
                    route = TravellerRootRoute.CREATE_DETAILS, busy = false,
                    notice = "Account creation could not continue. Check your details or try again.")
            }
            else -> busy(false)
        }
    }

    fun resendCreateCode() {
        if (state.value.route != TravellerRootRoute.CREATE_OTP || state.value.busy) return
        val engine = auth.createAccount ?: return recovery("Account verification is unavailable.")
        val snapshot = state.value
        val operation = ++operationGeneration
        busy(true)
        viewModelScope.launch {
            engine.cancel()
            if (operation == operationGeneration) {
                mutableState.value = state.value.copy(code = "", createCodeInvalid = false)
                handleCreateResult(engine.begin(snapshot.fullName, snapshot.handle, snapshot.createEmail), operation)
            }
        }
    }

    fun requestLoginCode() {
        val snapshot = state.value
        if (snapshot.route != TravellerRootRoute.LOGIN_EMAIL || snapshot.busy) return
        if (!validEmail(snapshot.loginEmail)) return notice("Enter a valid email address.")
        val engine = auth.returningLogin ?: return recovery("Log in is unavailable. Try again later.")
        val operation = ++operationGeneration
        busy(true)
        viewModelScope.launch {
            try {
                val result = engine.requestCode(snapshot.loginEmail)
                if (operation != operationGeneration) return@launch
                if (result == ReturningLoginState.CodeRequested) mutableState.value = state.value.copy(
                    route = TravellerRootRoute.LOGIN_OTP, code = "", busy = false, notice = null,
                    loginFeedback = ReturningLoginOtpFeedback.NEUTRAL, loginAttemptActive = true)
                else mutableState.value = state.value.copy(busy = false,
                    notice = "Could not request a code. Try again.")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = state.value.copy(busy = false,
                    notice = "Could not request a code. Try again.")
            }
        }
    }

    fun verifyLoginCode() {
        val snapshot = state.value
        if (snapshot.route != TravellerRootRoute.LOGIN_OTP || snapshot.busy ||
            !snapshot.loginAttemptActive || snapshot.code.length != 6) return
        val engine = auth.returningLogin ?: return recovery("Log in is unavailable.")
        val operation = ++operationGeneration
        busy(true)
        viewModelScope.launch {
            try {
                when (val result = engine.submitCode(snapshot.code)) {
                    ReturningLoginState.Admitted -> {
                        if (operation != operationGeneration) return@launch
                        if (auth.resolve() is TravellerAuthOutcome.Authenticated) {
                            loginEntry.clear()
                            route(TravellerRootRoute.ADMITTED_NEW)
                        } else recovery("This Traveller could not be safely admitted. Retry log in.")
                    }
                    is ReturningLoginState.Failed -> {
                        if (operation != operationGeneration) return@launch
                        if (result.reason == ReturningLoginFailure.INVALID_CODE ||
                            result.reason == ReturningLoginFailure.VERIFICATION_FAILED) {
                            mutableState.value = state.value.copy(busy = false, loginAttemptActive = false,
                                loginFeedback = ReturningLoginOtpFeedback.INVALID_OR_EXPIRED)
                        } else recovery("Log in could not be completed safely. Retry or recover this Traveller.")
                    }
                    is ReturningLoginState.Rejected -> if (operation == operationGeneration) {
                        recovery(when (result.reason) {
                            ReturningLoginRejection.DIFFERENT_OWNER ->
                                "This verified account does not match the Traveller bound to this device. Recover the same owner."
                            ReturningLoginRejection.TRAVELLER_PROFILE_REQUIRED ->
                                "This verified account does not have a Traveller profile. Account recovery is required."
                            ReturningLoginRejection.RECOVERY_REQUIRED ->
                                "This installation needs same-owner recovery before log in can continue."
                        })
                    }
                    else -> if (operation == operationGeneration)
                        recovery("Log in could not be completed safely. Recover the same Traveller.")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                recovery("Log in could not be completed safely. Retry or recover this Traveller.")
            }
        }
    }

    fun resendLoginCode() {
        if (state.value.route != TravellerRootRoute.LOGIN_OTP || state.value.busy) return
        val email = state.value.loginEmail
        val engine = auth.returningLogin ?: return recovery("Log in is unavailable.")
        val operation = ++operationGeneration
        busy(true)
        viewModelScope.launch {
            try {
                engine.cancel()
                if (operation != operationGeneration) return@launch
                val result = engine.requestCode(email)
                if (operation != operationGeneration) return@launch
                mutableState.value = state.value.copy(code = "", busy = false,
                    loginFeedback = ReturningLoginOtpFeedback.NEUTRAL,
                    loginAttemptActive = result == ReturningLoginState.CodeRequested,
                    notice = if (result == ReturningLoginState.CodeRequested) null else "Could not request a new code. Try again.")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = state.value.copy(busy = false, loginAttemptActive = false,
                    notice = "Could not request a new code. Try again.")
            }
        }
    }

    fun back() {
        val route = state.value.route
        if (state.value.busy) return
        invalidateOperation()
        when (route) {
            TravellerRootRoute.CREATE_OTP -> {
                val generation = operationGeneration
                busy(true)
                viewModelScope.launch {
                    auth.createAccount?.cancel()
                    if (generation == operationGeneration) mutableState.value = state.value.copy(
                        route = TravellerRootRoute.CREATE_DETAILS, code = "",
                        createCodeInvalid = false, busy = false, notice = null)
                }
            }
            TravellerRootRoute.CREATE_DETAILS -> if (establishedCreateOwner == null)
                if (createEntry.clear()) route(TravellerRootRoute.IDENTITY_CHOICE)
                else recovery("Could not leave account creation safely. Try again.")
            else notice("Complete this account or recover the same Traveller on this device.")
            TravellerRootRoute.LOGIN_OTP -> {
                val generation = operationGeneration
                busy(true)
                viewModelScope.launch {
                    auth.returningLogin?.cancel()
                    if (generation == operationGeneration) mutableState.value = state.value.copy(
                        route = TravellerRootRoute.LOGIN_EMAIL, code = "",
                        loginFeedback = ReturningLoginOtpFeedback.NEUTRAL,
                        loginAttemptActive = false, busy = false, notice = null)
                }
            }
            TravellerRootRoute.LOGIN_EMAIL -> {
                viewModelScope.launch { auth.returningLogin?.cancel() }
                loginEntry.clear()
                if (recoveringExistingOwner) recovery(
                    "Recover this Traveller with the same verified email account.", allowSameOwnerLogin = true)
                else route(TravellerRootRoute.IDENTITY_CHOICE)
            }
            else -> Unit
        }
    }

    fun differentCreateEmail() {
        if (state.value.route != TravellerRootRoute.CREATE_OTP) return
        back()
        mutableState.value = state.value.copy(createEmail = "")
    }

    fun differentLoginEmail() { if (state.value.route == TravellerRootRoute.LOGIN_OTP) back() }

    private fun validEmail(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.length in 3..254 && trimmed.indexOf('@') in 1 until trimmed.lastIndex &&
            trimmed.none(Char::isWhitespace)
    }

    private fun invalidateOperation() { operationGeneration++; handleGeneration++; handleJob?.cancel() }
    private fun route(destination: TravellerRootRoute) {
        mutableState.value = state.value.copy(route = destination, busy = false, notice = null)
    }
    private fun busy(value: Boolean) { mutableState.value = state.value.copy(
        busy = value, notice = if (value) null else state.value.notice) }
    private fun notice(value: String) { mutableState.value = state.value.copy(notice = value) }
    private fun recovery(value: String, allowSameOwnerLogin: Boolean = false) {
        mutableState.value = state.value.copy(route = TravellerRootRoute.RECOVERY, busy = false,
            notice = value, recoveryLoginAllowed = allowSameOwnerLogin)
    }
}

class TravellerRootViewModelFactory(
    private val auth: TravellerRootAuth,
    private val intro: IntroCompletionPreference,
    private val createProgress: CreateAccountProgressPreference,
    private val createEntry: CreateAccountEntryPreference,
    private val loginEntry: LoginEntryPreference,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TravellerRootViewModel(auth, intro, createProgress, createEntry, loginEntry) as T
}
