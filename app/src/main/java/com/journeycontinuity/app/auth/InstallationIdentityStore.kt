package com.journeycontinuity.app.auth

import android.annotation.SuppressLint
import android.content.Context
import java.util.UUID

fun interface InstallationIdentityStore {
    fun getOrCreate(): String
}

class SharedPreferencesInstallationIdentityStore(context: Context) : InstallationIdentityStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @SuppressLint("UseKtx") // The identifier must be durable before it is sent to the backend.
    override fun getOrCreate(): String {
        preferences.getString(INSTALLATION_ID_KEY, null)?.let { existing ->
            runCatching { UUID.fromString(existing) }.getOrNull()?.let { return it.toString() }
        }
        val generated = UUID.randomUUID().toString()
        check(preferences.edit().putString(INSTALLATION_ID_KEY, generated).commit()) {
            "Installation identity could not be persisted."
        }
        return preferences.getString(INSTALLATION_ID_KEY, null)
            ?.takeIf { it == generated }
            ?: error("Installation identity persistence was not durable.")
    }

    private companion object {
        const val PREFERENCES_NAME = "journey-continuity-installation"
        const val INSTALLATION_ID_KEY = "installationId"
    }
}
