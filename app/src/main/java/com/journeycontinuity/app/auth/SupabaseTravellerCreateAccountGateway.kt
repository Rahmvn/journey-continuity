package com.journeycontinuity.app.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Uses the installation's existing persistent Auth client and its own JWT. */
internal class SupabaseTravellerCreateAccountGateway(
    private val client: SupabaseClient,
) : TravellerCreateAccountGateway {
    override fun sessionUserId(): String? =
        (client.auth.sessionStatus.value as? SessionStatus.Authenticated)?.session?.user?.id

    override suspend fun currentIdentity(): CreateAccountAuthIdentity {
        val user = client.auth.retrieveUserForCurrentSession(updateSession = true)
        return CreateAccountAuthIdentity(user.id, user.isAnonymous, user.email,
            user.emailConfirmedAt != null)
    }

    override suspend fun requestEmailChange(email: String) {
        try {
            client.auth.updateUser { this.email = email }
        } catch (error: AuthRestException) {
            if (error.errorCode in setOf(AuthErrorCode.EmailExists,
                    AuthErrorCode.UserAlreadyExists, AuthErrorCode.IdentityAlreadyExists)) {
                throw EmailAlreadyInUseException()
            }
            throw error
        }
    }

    override suspend fun verifyEmailChange(email: String, code: String): String? {
        val result = client.auth.verifyEmailOtp(OtpType.Email.EMAIL_CHANGE, email, code)
        return (result as? OtpVerifyResult.Authenticated)?.session?.user?.id
    }

    override suspend fun hasTravellerProfile(): Boolean {
        val value = client.postgrest.rpc("get_my_traveller_identity_v1").decodeAs<JsonElement>()
        if (value == JsonNull) return false
        val profile = value as? JsonObject ?: error("Invalid Traveller profile result.")
        val name = (profile["full_name"] as? JsonPrimitive)?.content
        val handle = (profile["handle"] as? JsonPrimitive)?.content
        check(name != null && name.isNotBlank() && handle != null && handle.isNotBlank() &&
            profile.keys == setOf("full_name", "handle")) { "Invalid Traveller profile result." }
        return true
    }

    override suspend fun checkHandleAvailability(handle: String): Boolean =
        client.postgrest.rpc("check_traveller_handle_availability",
            buildJsonObject { put("p_handle", handle) }).decodeAs<Boolean>()

    override suspend fun finalizeProfile(identity: TravellerProfileIdentity): ProfileFinalization {
        val value = client.postgrest.rpc("complete_traveller_profile_identity_v1", buildJsonObject {
            put("p_full_name", identity.fullName)
            put("p_handle", identity.handle)
        }).decodeAs<JsonElement>()
        val result = value as? JsonObject ?: error("Invalid profile completion result.")
        val status = (result["status"] as? JsonPrimitive)?.content
        return when (status) {
            "CREATED" -> {
                check(result.keys == setOf("status")) { "Invalid profile completion result." }
                ProfileFinalization.Created
            }
            "ALREADY_COMPLETED" -> {
                check(result.keys == setOf("status", "matches_request")) {
                    "Invalid profile completion result."
                }
                val matches = (result["matches_request"] as? JsonPrimitive)?.booleanOrNull
                    ?: error("Invalid profile completion result.")
                ProfileFinalization.AlreadyCompleted(matches)
            }
            "HANDLE_UNAVAILABLE" -> {
                check(result.keys == setOf("status")) { "Invalid profile completion result." }
                ProfileFinalization.HandleUnavailable
            }
            else -> error("Invalid profile completion result.")
        }
    }

    override suspend fun clearMismatchedLocalSession(expectedOwnerId: String) {
        val actual = sessionUserId()
        if (actual != null && actual != expectedOwnerId) {
            client.auth.clearSession() // local only; leaves the persisted owner and Journey intact
        }
    }
}
