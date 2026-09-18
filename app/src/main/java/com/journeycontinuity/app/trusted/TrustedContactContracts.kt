package com.journeycontinuity.app.trusted

import java.time.Instant
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class TrustedContactSubjectKind { INVITATION, RELATIONSHIP }
enum class TrustedContactStatus { PENDING, ACCEPTED, REVOKED, EXPIRED }

data class TrustedContactSummary(
    val id: String,
    val kind: TrustedContactSubjectKind,
    val displayName: String,
    val email: String,
    val status: TrustedContactStatus,
    val expiresAt: Long?,
    val acceptedAt: Long?,
    val createdAt: Long,
)

data class CreatedTrustedContactInvitation(
    val id: String,
    val displayName: String,
    val email: String,
    val expiresAt: Long,
    val rawToken: String,
    val shareUrl: String,
)

interface TrustedContactGateway {
    suspend fun list(): List<TrustedContactSummary>
    suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation
    suspend fun revoke(subjectId: String): Boolean
}

class UnavailableTrustedContactGateway(
    private val message: String,
) : TrustedContactGateway {
    override suspend fun list(): List<TrustedContactSummary> = throw IllegalStateException(message)
    override suspend fun create(displayName: String, email: String): CreatedTrustedContactInvitation =
        throw IllegalStateException(message)
    override suspend fun revoke(subjectId: String): Boolean = throw IllegalStateException(message)
}

internal fun invitationUrl(baseUrl: String, token: String): String {
    require(baseUrl.isNotBlank()) { "Trusted viewer URL is not configured." }
    val separator = if ('?' in baseUrl) '&' else '?'
    return baseUrl.trimEnd('/') + separator + "token=" +
        URLEncoder.encode(token, StandardCharsets.UTF_8.toString())
}

internal fun parseServerInstant(value: String?): Long? = value?.let { Instant.parse(it).toEpochMilli() }
