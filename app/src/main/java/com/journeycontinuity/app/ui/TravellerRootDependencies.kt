package com.journeycontinuity.app.ui

import android.annotation.SuppressLint
import android.content.Context
import com.journeycontinuity.app.auth.CreateAccountState
import com.journeycontinuity.app.auth.HandleAvailability
import com.journeycontinuity.app.auth.ReturningLoginState
import com.journeycontinuity.app.auth.TravellerAuthOutcome
import com.journeycontinuity.app.auth.TravellerCreateAccountEngine
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.auth.ReturningTravellerLoginEngine

interface IntroCompletionPreference {
    fun isCompleted(): Boolean
    fun complete(): Boolean
}

interface CreateAccountProgressPreference {
    fun pendingOwnerId(): String?
    fun markStarted(ownerId: String): Boolean
    fun clear(ownerId: String): Boolean
}

interface CreateAccountEntryPreference {
    fun isPending(): Boolean
    fun markPending(): Boolean
    fun clear(): Boolean
}

/** Route intent only; stored outside the owner snapshot scan before A is established. */
class CreateAccountEntryStore(context: Context) : CreateAccountEntryPreference {
    private val preferences = context.applicationContext.getSharedPreferences(
        "alabarin-create-entry-only", Context.MODE_PRIVATE,
    )
    override fun isPending(): Boolean = preferences.getBoolean("detailsCommitted", false)
    @SuppressLint("UseKtx")
    override fun markPending(): Boolean = preferences.edit().putBoolean("detailsCommitted", true).commit()
    @SuppressLint("UseKtx")
    override fun clear(): Boolean = preferences.edit().remove("detailsCommitted").commit()
}

interface LoginEntryPreference {
    fun shouldReturnToEmailEntry(): Boolean
    fun markEmailEntry(): Boolean
    fun clear(): Boolean
}

/** A route hint only. It stores no email, OTP, candidate or owner ID. */
class LoginEntryStore(context: Context) : LoginEntryPreference {
    private val preferences = context.applicationContext.getSharedPreferences(
        "alabarin-login-entry-only", Context.MODE_PRIVATE,
    )
    override fun shouldReturnToEmailEntry(): Boolean = preferences.getBoolean("emailEntryPending", false)
    @SuppressLint("UseKtx") // The route hint must be durable before changing screens.
    override fun markEmailEntry(): Boolean = preferences.edit().putBoolean("emailEntryPending", true).commit()
    @SuppressLint("UseKtx")
    override fun clear(): Boolean = preferences.edit().remove("emailEntryPending").commit()
}

/** Written only after the owner guard has persisted A; a stale marker fails closed. */
class CreateAccountProgressStore(context: Context) : CreateAccountProgressPreference {
    private val preferences = context.applicationContext.getSharedPreferences(
        "journey-continuity-identity", Context.MODE_PRIVATE,
    )
    override fun pendingOwnerId(): String? = preferences.getString("createAccountPendingOwnerId", null)
    @SuppressLint("UseKtx") // An established A needs a durable resume marker before begin().
    override fun markStarted(ownerId: String): Boolean = preferences.edit()
        .putString("createAccountPendingOwnerId", ownerId).commit()
    @SuppressLint("UseKtx")
    override fun clear(ownerId: String): Boolean {
        val pending = pendingOwnerId()
        if (pending == null) return true // A prior completion attempt already removed this marker.
        if (pending != ownerId) return false
        return preferences.edit().remove("createAccountPendingOwnerId").commit()
    }
}

/** This preference file is deliberately outside the owner-adoption snapshot's scan. */
class IntroCompletionStore(context: Context) : IntroCompletionPreference {
    private val preferences = context.applicationContext.getSharedPreferences(
        "alabarin-introduction-only", Context.MODE_PRIVATE,
    )

    override fun isCompleted(): Boolean = preferences.getBoolean("introductionCompleted", false)

    @SuppressLint("UseKtx") // Completion must survive a process restart before routing onward.
    override fun complete(): Boolean = preferences.edit().putBoolean("introductionCompleted", true).commit()
}

interface CreateAccountActions {
    suspend fun checkHandleAvailability(handle: String): HandleAvailability
    suspend fun begin(fullName: String, handle: String, email: String): CreateAccountState
    suspend fun submitCode(code: String): CreateAccountState
    suspend fun completeProfile(fullName: String, handle: String): CreateAccountState
    suspend fun cancel()
}

interface ReturningLoginActions {
    suspend fun requestCode(email: String): ReturningLoginState
    suspend fun submitCode(code: String): ReturningLoginState
    suspend fun cancel()
}

interface TravellerRootAuth {
    suspend fun resolve(): TravellerAuthOutcome
    suspend fun establishAnonymousOwner(): TravellerAuthOutcome
    val createAccount: CreateAccountActions?
    val returningLogin: ReturningLoginActions?
}

class ProductionTravellerRootAuth(
    private val coordinator: TravellerIdentityCoordinator?,
    createEngine: TravellerCreateAccountEngine?,
    loginEngine: ReturningTravellerLoginEngine?,
) : TravellerRootAuth {
    override suspend fun resolve(): TravellerAuthOutcome =
        coordinator?.resolve() ?: TravellerAuthOutcome.TemporaryAuthUnavailable

    override suspend fun establishAnonymousOwner(): TravellerAuthOutcome =
        coordinator?.establishAnonymousOwner() ?: TravellerAuthOutcome.TemporaryAuthUnavailable

    override val createAccount: CreateAccountActions? = createEngine?.let { engine ->
        object : CreateAccountActions {
            override suspend fun checkHandleAvailability(handle: String) = engine.checkHandleAvailability(handle)
            override suspend fun begin(fullName: String, handle: String, email: String) =
                engine.begin(fullName, handle, email)
            override suspend fun submitCode(code: String) = engine.submitCode(code)
            override suspend fun completeProfile(fullName: String, handle: String) =
                engine.completeProfile(fullName, handle)
            override suspend fun cancel() = engine.cancel()
        }
    }

    override val returningLogin: ReturningLoginActions? = loginEngine?.let { engine ->
        object : ReturningLoginActions {
            override suspend fun requestCode(email: String) = engine.requestCode(email)
            override suspend fun submitCode(code: String) = engine.submitCode(code)
            override suspend fun cancel() = engine.cancel()
        }
    }
}
