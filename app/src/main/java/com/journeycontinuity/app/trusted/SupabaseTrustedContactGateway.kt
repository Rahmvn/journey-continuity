package com.journeycontinuity.app.trusted

import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.sync.CloudStage
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.NoOpSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncDiagnosticLogger
import com.journeycontinuity.app.sync.toCloudSyncException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseTrustedContactGateway(
    private val client: SupabaseClient,
    private val identityCoordinator: TravellerIdentityCoordinator,
    private val trustedViewerBaseUrl: String,
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
) : TrustedContactGateway {
    override suspend fun list(): List<TrustedContactSummary> = trustedCall {
        authenticate()
        client.postgrest.rpc("list_trusted_contact_management")
            .decodeList<TrustedContactManagementRow>()
            .map { it.toDomain() }
    }

    override suspend fun create(
        displayName: String,
        email: String,
    ): CreatedTrustedContactInvitation = trustedCall {
        authenticate()
        val row = client.postgrest.rpc(
            function = "create_trusted_contact_invitation",
            parameters = buildJsonObject {
                put("p_display_name", displayName)
                put("p_email", email)
            },
        ).decodeSingle<CreatedInvitationRow>()
        CreatedTrustedContactInvitation(
            id = row.invitationId,
            displayName = row.displayName,
            email = row.invitedEmailNormalized,
            expiresAt = checkNotNull(parseServerInstant(row.expiresAt)),
            rawToken = row.invitationToken,
            shareUrl = invitationUrl(trustedViewerBaseUrl, row.invitationToken),
        )
    }

    override suspend fun revoke(subjectId: String): Boolean = trustedCall {
        authenticate()
        client.postgrest.rpc(
            function = "revoke_trusted_contact",
            parameters = buildJsonObject { put("p_subject_id", subjectId) },
        ).decodeAs<Boolean>()
    }

    private suspend fun authenticate() {
        identityCoordinator.requireAuthenticatedTraveller()
    }

    private suspend fun <T> trustedCall(block: suspend () -> T): T = try {
        block()
    } catch (error: CloudSyncException) {
        throw error
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        val classified = error.toCloudSyncException(CloudStage.TRUSTED_CONTACT_RPC)
        logger.warning(classified.diagnosticSummary)
        throw classified
    }
}

@Serializable
private data class TrustedContactManagementRow(
    @SerialName("subject_id") val subjectId: String,
    @SerialName("subject_kind") val subjectKind: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("contact_email_normalized") val contactEmailNormalized: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String?,
    @SerialName("accepted_at") val acceptedAt: String?,
    @SerialName("created_at") val createdAt: String,
) {
    fun toDomain() = TrustedContactSummary(
        id = subjectId,
        kind = TrustedContactSubjectKind.valueOf(subjectKind),
        displayName = displayName,
        email = contactEmailNormalized,
        status = TrustedContactStatus.valueOf(status),
        expiresAt = parseServerInstant(expiresAt),
        acceptedAt = parseServerInstant(acceptedAt),
        createdAt = checkNotNull(parseServerInstant(createdAt)),
    )
}

@Serializable
private data class CreatedInvitationRow(
    @SerialName("invitation_id") val invitationId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("invited_email_normalized") val invitedEmailNormalized: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("invitation_token") val invitationToken: String,
)
