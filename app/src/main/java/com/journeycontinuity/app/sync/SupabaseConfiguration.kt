package com.journeycontinuity.app.sync

import com.journeycontinuity.app.BuildConfig
import java.net.URI

data class SupabaseConfiguration(
    val url: String,
    val publishableKey: String,
) {
    val isConfigured: Boolean
        get() = url.isNotBlank() && publishableKey.isNotBlank()

    val isHttpsUrl: Boolean
        get() = parsedUri?.scheme.equals("https", ignoreCase = true) && !parsedUri?.host.isNullOrBlank()

    val hasExpectedProjectHostShape: Boolean
        get() = parsedUri?.host?.matches(SUPABASE_PROJECT_HOST) == true

    val keyCategory: String
        get() = when {
            publishableKey.isBlank() -> "blank"
            publishableKey.startsWith("sb_publishable_") -> "sb_publishable"
            publishableKey.startsWith("eyJ") -> "legacy_anon"
            else -> "unrecognized"
        }

    val validationError: String?
        get() = when {
            url.isBlank() -> "Supabase URL is missing from this build."
            publishableKey.isBlank() -> "Supabase publishable key is missing from this build."
            !isHttpsUrl -> "Supabase URL is not a valid HTTPS URL."
            keyCategory == "unrecognized" -> "Supabase client key has an unrecognized prefix."
            else -> null
        }

    private val parsedUri: URI?
        get() = runCatching { URI(url) }.getOrNull()

    companion object {
        private val SUPABASE_PROJECT_HOST = Regex("^[a-z0-9]{8,}\\.supabase\\.co$")

        fun fromBuildConfig() = SupabaseConfiguration(
            url = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
    }
}
