package com.journeycontinuity.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.journeycontinuity.app.auth.TravellerAccountGateway
import com.journeycontinuity.app.auth.TravellerAccountOwnerException
import com.journeycontinuity.app.auth.TravellerAccountProfile
import com.journeycontinuity.app.auth.TravellerAccountUnavailableException
import com.journeycontinuity.app.auth.TravellerHandleUnavailableException
import com.journeycontinuity.app.auth.TravellerProfileIdentityInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccountHandleStatus { IDLE, CHECKING, AVAILABLE, UNAVAILABLE, INVALID, UNKNOWN }

data class TravellerAccountState(
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
)

class TravellerAccountViewModel(private val gateway: TravellerAccountGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(TravellerAccountState())
    val state: StateFlow<TravellerAccountState> = mutableState
    private var handleJob: Job? = null
    private var handleGeneration = 0L

    fun load() {
        if (state.value.loading || state.value.saving) return
        mutableState.update { it.copy(loading = true, profile = null, loadError = null) }
        viewModelScope.launch {
            try {
                val profile = gateway.load()
                mutableState.update { it.copy(loading = false, profile = profile, loadError = null) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, profile = null,
                    loadError = when (error) {
                        is TravellerAccountOwnerException -> "Verified Traveller identity is required to view Account."
                        is TravellerAccountUnavailableException -> "Account profile is unavailable. Try again."
                        else -> "Could not load Account. Check your connection and try again."
                    }) }
            }
        }
    }

    fun openEdit() {
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

class TravellerAccountViewModelFactory(private val gateway: TravellerAccountGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TravellerAccountViewModel(gateway) as T
}
