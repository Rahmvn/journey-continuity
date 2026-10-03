package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.auth.TravellerAccountGateway
import com.journeycontinuity.app.auth.TravellerAccountOwnerException
import com.journeycontinuity.app.auth.TravellerAccountProfile
import com.journeycontinuity.app.auth.TravellerAccountUnavailableException
import com.journeycontinuity.app.auth.TravellerHandleUnavailableException
import com.journeycontinuity.app.auth.NoActiveSignOutResult
import com.journeycontinuity.app.auth.TravellerNoActiveSignOutGateway
import com.journeycontinuity.app.auth.ActiveJourneySignOutResult
import com.journeycontinuity.app.auth.TravellerActiveJourneySignOutGateway
import com.journeycontinuity.app.auth.TravellerProfileIdentityInput
import com.journeycontinuity.app.domain.Journey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccountHandleStatus { IDLE, CHECKING, AVAILABLE, UNAVAILABLE, INVALID, UNKNOWN }
enum class AccountSignOutStatus {
    IDLE, CHECKING, CONFIRMING, SIGNING_OUT, FAILURE, SIGNED_OUT,
    CONFIRMING_ACTIVE, STOPPING_ACTIVE, MONITORING_STOPPED_RESULT,
}

data class TravellerAccountState(
    val reauthenticationNeeded: Boolean = false,
    val loading: Boolean = false,
    val profile: TravellerAccountProfile? = null,
    val loadError: String? = null,
    val editOpen: Boolean = false,
    val fullNameInput: String = "",
    val handleInput: String = "",
    val handleStatus: AccountHandleStatus = AccountHandleStatus.IDLE,
    val saving: Boolean = false,
    val nameError: String? = null,
    val saveError: String? = null,
    val signOutStatus: AccountSignOutStatus = AccountSignOutStatus.IDLE,
    val signOutError: String? = null,
    val activeSignOutJourneyId: String? = null,
    val monitoringStoppedJourney: Journey? = null,
    val monitoringStoppedSignedOut: Boolean = false,
    val monitoringStoppedError: String? = null,
    val restoringMonitoringStoppedResult: Boolean = false,
)

class TravellerAccountViewModel(
    private val gateway: TravellerAccountGateway,
    private val noActiveSignOut: TravellerNoActiveSignOutGateway? = null,
    private val activeSignOut: TravellerActiveJourneySignOutGateway? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TravellerAccountState(
        restoringMonitoringStoppedResult = activeSignOut != null,
    ))
    val state: StateFlow<TravellerAccountState> = mutableState
    private var handleJob: Job? = null
    private var handleGeneration = 0L
    private var resultRefreshJob: Job? = null
    private var profileLoadGeneration = 0L

    fun setReauthenticationNeeded(needed: Boolean) {
        if (state.value.reauthenticationNeeded == needed) return
        profileLoadGeneration++
        handleJob?.cancel()
        mutableState.update { it.copy(reauthenticationNeeded = needed,
            profile = if (needed) it.profile else null,
            loading = false, loadError = null, editOpen = false,
            signOutStatus = AccountSignOutStatus.IDLE, signOutError = null) }
    }

    init {
        if (activeSignOut != null) viewModelScope.launch {
            try {
                activeSignOut.restorePendingResult()?.let(::applyActiveSignOutResult)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            } finally {
                mutableState.update { it.copy(restoringMonitoringStoppedResult = false) }
            }
        }
    }

    /** Only checks a server-acknowledged explicit stop result; it does not observe auth loss. */
    fun refreshMonitoringStoppedResult() {
        val gateway = activeSignOut ?: return
        val current = state.value
        if (current.restoringMonitoringStoppedResult || current.monitoringStoppedJourney != null ||
            current.signOutStatus == AccountSignOutStatus.STOPPING_ACTIVE ||
            resultRefreshJob?.isActive == true) return
        resultRefreshJob = viewModelScope.launch {
            try {
                gateway.restorePendingResult()?.let(::applyActiveSignOutResult)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            }
        }
    }

    fun requestSignOut(ready: Boolean, activeJourneyId: String?) {
        if (state.value.reauthenticationNeeded) {
            signOutFailure("Reauthenticate before you can stop monitoring and sign out.")
            return
        }
        if (activeJourneyId == null) {
            requestNoActiveSignOut(ready)
            return
        }
        val current = state.value
        if ((current.signOutStatus != AccountSignOutStatus.IDLE &&
            current.signOutStatus != AccountSignOutStatus.FAILURE) || current.editOpen ||
            current.restoringMonitoringStoppedResult) return
        if (!ready || current.profile == null || activeSignOut == null) {
            signOutFailure("Sign out is unavailable until Journey and Account state are ready.")
            return
        }
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.CONFIRMING_ACTIVE,
            activeSignOutJourneyId = activeJourneyId, signOutError = null) }
    }

    fun cancelActiveSignOut() {
        if (state.value.signOutStatus != AccountSignOutStatus.CONFIRMING_ACTIVE) return
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.IDLE,
            activeSignOutJourneyId = null, signOutError = null) }
    }

    fun confirmActiveSignOut(ready: Boolean) {
        val current = state.value
        if (current.signOutStatus != AccountSignOutStatus.CONFIRMING_ACTIVE) return
        if (!ready) return signOutFailure("Journey state changed. Monitoring continues; try again.")
        val journeyId = current.activeSignOutJourneyId
            ?: return signOutFailure("The active Journey could not be verified.")
        val ownerId = current.profile?.ownerId
            ?: return signOutFailure("Verified Traveller identity is required to sign out.")
        val gateway = activeSignOut ?: return signOutFailure("Sign out is unavailable. Try again.")
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.STOPPING_ACTIVE,
            signOutError = null) }
        viewModelScope.launch {
            val result = try { gateway.confirmAndSignOut(journeyId, ownerId) }
                catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    ActiveJourneySignOutResult.ConnectionRequired
                }
            applyActiveSignOutResult(result)
        }
    }

    fun retryTerminalSignOut() {
        val current = state.value
        val journeyId = current.monitoringStoppedJourney?.id ?: return
        if (current.monitoringStoppedSignedOut ||
            current.signOutStatus == AccountSignOutStatus.STOPPING_ACTIVE) return
        val gateway = activeSignOut ?: return
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.STOPPING_ACTIVE,
            monitoringStoppedError = null) }
        viewModelScope.launch {
            val result = try { gateway.retryTerminalSignOut(journeyId) }
                catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    ActiveJourneySignOutResult.AuthenticationUncertain(current.monitoringStoppedJourney)
                }
            applyActiveSignOutResult(result)
        }
    }

    fun doneWithMonitoringStopped(onDone: () -> Unit) {
        val current = state.value
        val journeyId = current.monitoringStoppedJourney?.id ?: return
        if (!current.monitoringStoppedSignedOut ||
            current.signOutStatus == AccountSignOutStatus.STOPPING_ACTIVE) return
        val gateway = activeSignOut ?: return
        viewModelScope.launch {
            val cleared = try { gateway.completeResult(journeyId) } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
            if (cleared) {
                mutableState.update { TravellerAccountState() }
                onDone()
            } else {
                mutableState.update { it.copy(monitoringStoppedError =
                    "Could not finish sign out safely. Try again.") }
            }
        }
    }

    private fun applyActiveSignOutResult(result: ActiveJourneySignOutResult) {
        when (result) {
            is ActiveJourneySignOutResult.SignedOut -> terminalResult(result.journey, true, null)
            is ActiveJourneySignOutResult.SignOutRetryRequired -> terminalResult(result.journey, false,
                "Monitoring stopped. Sign out did not finish on this device. Retry sign out.")
            is ActiveJourneySignOutResult.TeardownRetryRequired -> terminalResult(result.journey, false,
                "Monitoring stopped. Device monitoring shutdown needs confirmation. Try again.")
            is ActiveJourneySignOutResult.AuthenticationUncertain -> terminalResult(result.journey, false,
                "Monitoring stopped. Sign-out status is still being checked. Try again.")
            ActiveJourneySignOutResult.ConnectionRequired -> signOutFailure(
                "A connection is required to stop monitoring and sign out. Monitoring continues. Try again.")
            ActiveJourneySignOutResult.JourneyChanged -> signOutFailure(
                "The active Journey changed. Nothing was stopped or signed out.")
            ActiveJourneySignOutResult.AuthenticationRequired -> signOutFailure(
                "The installation owner session could not be verified. Monitoring continues.")
            ActiveJourneySignOutResult.RemoteResultInvalid -> signOutFailure(
                "The server did not confirm Monitoring stopped for this Journey. This device remains signed in.")
            ActiveJourneySignOutResult.LocalPersistenceFailed -> {
                signOutFailure("The server stop could not be saved on this device. Stay signed in while it is reconciled.")
                refreshMonitoringStoppedResult()
            }
        }
    }

    private fun terminalResult(journey: Journey, signedOut: Boolean, error: String?) {
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.MONITORING_STOPPED_RESULT,
            monitoringStoppedJourney = journey, monitoringStoppedSignedOut = signedOut,
            monitoringStoppedError = error, activeSignOutJourneyId = null, signOutError = null) }
    }

    fun requestNoActiveSignOut(ready: Boolean) {
        val current = state.value
        if (current.signOutStatus == AccountSignOutStatus.CHECKING ||
            current.signOutStatus == AccountSignOutStatus.SIGNING_OUT ||
            current.signOutStatus == AccountSignOutStatus.CONFIRMING || current.editOpen) return
        if (!ready || current.profile == null || noActiveSignOut == null) {
            signOutFailure("Sign out is unavailable until Journey and Account state are ready.")
            return
        }
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.CHECKING, signOutError = null) }
        viewModelScope.launch {
            try {
                if (noActiveSignOut.hasNoActiveJourney()) {
                    mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.CONFIRMING) }
                } else signOutFailure("A Journey is active. Keep monitoring; sign out is unavailable here.")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                signOutFailure("Could not verify Journey state. Try again.")
            }
        }
    }

    fun cancelNoActiveSignOut() {
        if (state.value.signOutStatus != AccountSignOutStatus.CONFIRMING) return
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.IDLE, signOutError = null) }
    }

    fun confirmNoActiveSignOut(ready: Boolean, onSignedOut: () -> Unit) {
        val current = state.value
        if (current.signOutStatus != AccountSignOutStatus.CONFIRMING) return
        if (!ready) {
            signOutFailure("Journey state changed. Keep monitoring and try again later.")
            return
        }
        val ownerId = current.profile?.ownerId ?: return signOutFailure(
            "Verified Traveller identity is required to sign out.")
        val signOut = noActiveSignOut ?: return signOutFailure("Sign out is unavailable. Try again.")
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.SIGNING_OUT, signOutError = null) }
        viewModelScope.launch {
            val result = try {
                signOut.signOut(ownerId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                NoActiveSignOutResult.Failed
            }
            when (result) {
                NoActiveSignOutResult.SignedOut -> {
                    mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.SIGNED_OUT) }
                    onSignedOut()
                }
                NoActiveSignOutResult.ActiveJourney -> signOutFailure(
                    "A Journey is active. Keep monitoring; sign out is unavailable here.")
                NoActiveSignOutResult.AuthenticationRequired -> signOutFailure(
                    "The installation owner session could not be verified. Sign out was not attempted.")
                NoActiveSignOutResult.AuthenticationReassessmentRequired -> {
                    mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.IDLE, signOutError = null) }
                    onSignedOut()
                }
                NoActiveSignOutResult.Failed -> signOutFailure(
                    "Sign out could not be confirmed. Check your connection and try again.")
            }
        }
    }

    private fun signOutFailure(message: String) {
        mutableState.update { it.copy(signOutStatus = AccountSignOutStatus.FAILURE,
            activeSignOutJourneyId = null, signOutError = message) }
    }

    fun load() {
        if (state.value.reauthenticationNeeded || state.value.loading || state.value.saving) return
        val generation = ++profileLoadGeneration
        mutableState.update { it.copy(loading = true, profile = null, loadError = null) }
        viewModelScope.launch {
            try {
                val profile = gateway.load()
                if (generation == profileLoadGeneration) mutableState.update {
                    it.copy(loading = false, profile = profile, loadError = null) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == profileLoadGeneration) mutableState.update { it.copy(loading = false, profile = null,
                    loadError = when (error) {
                        is TravellerAccountOwnerException -> "Verified Traveller identity is required to view Account."
                        is TravellerAccountUnavailableException -> "Account profile is unavailable. Try again."
                        else -> "Could not load Account. Check your connection and try again."
                    }) }
            }
        }
    }

    fun openEdit() {
        if (state.value.reauthenticationNeeded) return
        val profile = state.value.profile ?: return
        handleJob?.cancel()
        mutableState.update { it.copy(editOpen = true, fullNameInput = profile.fullName,
            handleInput = "@${profile.handle}", handleStatus = AccountHandleStatus.IDLE,
            nameError = null, saveError = null) }
    }

    fun cancelEdit() {
        if (state.value.saving) return
        handleJob?.cancel()
        handleGeneration++
        mutableState.update { it.copy(editOpen = false, fullNameInput = "", handleInput = "",
            handleStatus = AccountHandleStatus.IDLE, nameError = null, saveError = null) }
    }

    fun updateFullName(value: String) {
        if (!state.value.editOpen || state.value.saving) return
        mutableState.update { it.copy(fullNameInput = value, nameError = null, saveError = null) }
    }

    fun updateHandle(value: String) {
        val current = state.value
        if (!current.editOpen || current.saving) return
        handleJob?.cancel()
        val generation = ++handleGeneration
        val normalized = TravellerProfileIdentityInput.handle(value)
        val status = when {
            normalized == null -> AccountHandleStatus.INVALID
            normalized == current.profile?.handle -> AccountHandleStatus.AVAILABLE
            else -> AccountHandleStatus.CHECKING
        }
        mutableState.update { it.copy(handleInput = value, handleStatus = status, saveError = null) }
        if (status != AccountHandleStatus.CHECKING) return
        val ownerId = current.profile?.ownerId ?: return
        handleJob = viewModelScope.launch {
            delay(350)
            val result = try {
                if (gateway.checkHandleAvailability(ownerId, normalized!!)) AccountHandleStatus.AVAILABLE
                else AccountHandleStatus.UNAVAILABLE
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AccountHandleStatus.UNKNOWN
            }
            if (generation == handleGeneration) mutableState.update { it.copy(handleStatus = result) }
        }
    }

    fun save() {
        val current = state.value
        val profile = current.profile ?: return
        if (!current.editOpen || current.saving) return
        val fullName = TravellerProfileIdentityInput.fullName(current.fullNameInput)
        val handle = TravellerProfileIdentityInput.handle(current.handleInput)
        if (fullName == null || handle == null) {
            mutableState.update { it.copy(
                nameError = if (fullName == null) "Enter a valid full name (1–100 characters)." else null,
                handleStatus = if (handle == null) AccountHandleStatus.INVALID else it.handleStatus,
                saveError = if (handle == null) "Enter a valid Alabarin handle." else null,
            ) }
            return
        }
        if (current.handleStatus == AccountHandleStatus.UNAVAILABLE) {
            mutableState.update { it.copy(saveError = "That handle is unavailable. Try another.") }
            return
        }
        handleJob?.cancel()
        handleGeneration++
        mutableState.update { it.copy(saving = true, nameError = null, saveError = null) }
        viewModelScope.launch {
            try {
                val saved = gateway.save(profile.ownerId, fullName, handle)
                if (saved.ownerId != profile.ownerId) throw TravellerAccountOwnerException()
                mutableState.update { it.copy(profile = saved, editOpen = false, saving = false,
                    fullNameInput = "", handleInput = "", handleStatus = AccountHandleStatus.IDLE) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(saving = false,
                    handleStatus = if (error is TravellerHandleUnavailableException)
                        AccountHandleStatus.UNAVAILABLE else it.handleStatus,
                    saveError = when (error) {
                        is TravellerHandleUnavailableException -> "That handle is no longer available. Try another."
                        is TravellerAccountOwnerException -> "Verified Traveller identity is required to save changes."
                        else -> "Could not save changes. Check your connection and try again."
                    }) }
            }
        }
    }
}

class TravellerAccountViewModelFactory(
    private val gateway: TravellerAccountGateway,
    private val noActiveSignOut: TravellerNoActiveSignOutGateway? = null,
    private val activeSignOut: TravellerActiveJourneySignOutGateway? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TravellerAccountViewModel(gateway, noActiveSignOut, activeSignOut) as T
}
