package com.journeycontinuity.app.auth

import com.journeycontinuity.app.sync.SupabaseConfiguration
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

internal data class VerifiedLoginCandidate(val userId: String, val session: UserSession)

/** Kept inside the auth boundary; both outcomes have the same public CodeRequested state. */
internal enum class LoginCodeRequestResult { SENT, ACCOUNT_ABSENT }

internal fun isAbsentLoginAccountResponse(statusCode: Int, errorCode: AuthErrorCode?): Boolean =
    statusCode == 422 && errorCode == AuthErrorCode.OtpDisabled

internal interface ReturningLoginAttempt {
    suspend fun requestCode(email: String): LoginCodeRequestResult
    suspend fun verifyCode(email: String, code: String): VerifiedLoginCandidate
    suspend fun hasTravellerProfile(): Boolean
    suspend fun discard()
}

internal fun interface ReturningLoginAttemptFactory {
    fun create(): ReturningLoginAttempt
}

/** The redirect is only the exact local email-presentation selector, never authority. */
internal const val ANDROID_CODE_LOGIN_REDIRECT = "http://127.0.0.1:4173/auth/traveller-code"

internal class SupabaseReturningLoginAttemptFactory(
    private val configuration: SupabaseConfiguration,
) : ReturningLoginAttemptFactory {
    override fun create(): ReturningLoginAttempt {
        check(configuration.validationError == null) { "Traveller login configuration is unavailable." }
        val client = createSupabaseClient(configuration.url, configuration.publishableKey) {
            install(Auth) { minimalConfig() }
            install(Postgrest)
        }
        return SupabaseReturningLoginAttempt(client)
    }
}

private class SupabaseReturningLoginAttempt(private val client: SupabaseClient) : ReturningLoginAttempt {
    override suspend fun requestCode(email: String): LoginCodeRequestResult {
        return try {
            client.auth.signInWith(OTP, redirectUrl = ANDROID_CODE_LOGIN_REDIRECT) {
                this.email = email
                createUser = false
            }
            LoginCodeRequestResult.SENT
        } catch (error: AuthRestException) {
            // Verified by traveller_auth_integration.mjs for create_user=false on an absent email.
            if (isAbsentLoginAccountResponse(error.statusCode, error.errorCode)) {
                LoginCodeRequestResult.ACCOUNT_ABSENT
            } else throw error
        }
    }

    override suspend fun verifyCode(email: String, code: String): VerifiedLoginCandidate {
        val verified = client.auth.verifyEmailOtp(OtpType.Email.EMAIL, email, code)
            as? OtpVerifyResult.Authenticated
            ?: error("Email verification did not issue a session.")
        val session = verified.session
        val sessionUserId = session.user?.id ?: error("Verified session has no user ID.")
        check(session.refreshToken.isNotBlank() && session.expiresAt > Clock.System.now()) {
            "Verified session is not usable."
        }
        val authUser = client.auth.retrieveUserForCurrentSession()
        check(authUser.id == sessionUserId && authUser.isAnonymous == false &&
            authUser.emailConfirmedAt != null) { "Verified Traveller Auth identity is unavailable." }
        return VerifiedLoginCandidate(sessionUserId, session)
    }

    override suspend fun hasTravellerProfile(): Boolean {
        val value = client.postgrest.rpc("get_my_traveller_identity_v1").decodeAs<JsonElement>()
        if (value == JsonNull) return false
        val profile = value as? JsonObject ?: error("Invalid Traveller identity result.")
        val name = (profile["full_name"] as? JsonPrimitive)?.content
        val handle = (profile["handle"] as? JsonPrimitive)?.content
        check(name != null && name.isNotBlank() && handle != null && handle.isNotBlank() &&
            profile.keys == setOf("full_name", "handle")) { "Invalid Traveller identity result." }
        return true
    }

    override suspend fun discard() {
        try {
            client.auth.clearSession() // local memory only; does not revoke the primary owner
        } finally {
            client.close()
        }
    }
}
