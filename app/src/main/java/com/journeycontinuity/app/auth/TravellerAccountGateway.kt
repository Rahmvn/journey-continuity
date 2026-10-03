package com.journeycontinuity.app.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TravellerAccountProfile(
    val ownerId: String,
    val fullName: String,
    val handle: String,
    val verifiedEmail: String,
)

class TravellerAccountUnavailableException : Exception()
class TravellerAccountOwnerException : Exception()
class TravellerHandleUnavailableException : Exception()

interface TravellerAccountGateway {
    suspend fun load(): TravellerAccountProfile
    suspend fun checkHandleAvailability(ownerId: String, handle: String): Boolean
    suspend fun save(ownerId: String, fullName: String, handle: String): TravellerAccountProfile
}

class SupabaseTravellerAccountGateway(
    private val client: SupabaseClient,
    private val identityCoordinator: TravellerIdentityCoordinator,
) : TravellerAccountGateway {
    override suspend fun load(): TravellerAccountProfile {
        val (ownerId, _) = verifiedOwner()
        val value = client.postgrest.rpc("get_my_traveller_identity_v1").decodeAs<JsonElement>()
        val identity = value.identityOrNull() ?: throw TravellerAccountUnavailableException()
        val (_, email) = verifiedOwner(ownerId)
        return TravellerAccountProfile(ownerId, identity.first, identity.second, email)
    }

    override suspend fun checkHandleAvailability(ownerId: String, handle: String): Boolean {
        verifiedOwner(ownerId)
        val available = client.postgrest.rpc("check_traveller_handle_availability",
            buildJsonObject { put("p_handle", handle) }).decodeAs<Boolean>()
        verifiedOwner(ownerId)
        return available
    }

    override suspend fun save(ownerId: String, fullName: String, handle: String): TravellerAccountProfile {
        verifiedOwner(ownerId)
        val value = try {
            client.postgrest.rpc("set_traveller_profile_identity", buildJsonObject {
                put("p_full_name", fullName)
                put("p_handle", handle)
            }).decodeAs<JsonElement>()
        } catch (error: PostgrestRestException) {
            if (error.code == "23505") throw TravellerHandleUnavailableException()
            throw error
        }
        val identity = value.identityOrNull() ?: throw TravellerAccountUnavailableException()
        val (_, email) = verifiedOwner(ownerId)
        return TravellerAccountProfile(ownerId, identity.first, identity.second, email)
    }

    private suspend fun verifiedOwner(expectedOwnerId: String? = null): Pair<String, String> {
        val ownerId = try {
            identityCoordinator.requireAuthenticatedTraveller().userId
        } catch (error: TravellerAuthException) {
            if (error.failureKind == TravellerAuthFailureKind.TEMPORARY_UNAVAILABLE) {
                throw TravellerAccountUnavailableException()
            }
            throw TravellerAccountOwnerException()
        }
        val user = client.auth.retrieveUserForCurrentSession(updateSession = true)
        val email = requireVerifiedAccountOwner(ownerId, expectedOwnerId, user.id,
            user.email, user.isAnonymous, user.emailConfirmedAt != null)
        return ownerId to email
    }
}

internal fun requireVerifiedAccountOwner(
    authenticatedOwnerId: String,
    expectedOwnerId: String?,
    verifiedUserId: String,
    email: String?,
    isAnonymous: Boolean?,
    emailConfirmed: Boolean,
): String {
    if (authenticatedOwnerId.isBlank() ||
        expectedOwnerId != null && authenticatedOwnerId != expectedOwnerId ||
        verifiedUserId != authenticatedOwnerId || isAnonymous != false || !emailConfirmed ||
        email.isNullOrBlank()) {
        throw TravellerAccountOwnerException()
    }
    return email
}

class UnavailableTravellerAccountGateway : TravellerAccountGateway {
    override suspend fun load(): TravellerAccountProfile = throw TravellerAccountUnavailableException()
    override suspend fun checkHandleAvailability(ownerId: String, handle: String): Boolean =
        throw TravellerAccountUnavailableException()
    override suspend fun save(ownerId: String, fullName: String, handle: String): TravellerAccountProfile =
        throw TravellerAccountUnavailableException()
}

private fun JsonElement.identityOrNull(): Pair<String, String>? {
    if (this == JsonNull) return null
    val value = this as? JsonObject ?: return null
    if (value.keys != setOf("full_name", "handle")) return null
    val name = (value["full_name"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
    val handle = (value["handle"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
    return if (name != null && handle != null) name to handle else null
}
