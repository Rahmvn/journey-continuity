package com.journeycontinuity.app.sync

import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.TelemetryObservation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.ResponseException
import java.net.ConnectException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class SupabaseCloudSyncGateway(
    private val client: SupabaseClient,
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
) : CloudSyncGateway {
    override suspend fun authenticatedOwnerId(): String {
        logger.info("Waiting for Supabase auth initialization")
        cloudCall(CloudStage.AUTH_INITIALIZATION) {
            client.auth.awaitInitialization()
        }
        val existingSessionPresent = client.auth.currentSessionOrNull() != null
        logger.info("Existing auth session present: $existingSessionPresent")
        if (!existingSessionPresent) {
            logger.info("Starting anonymous sign-in")
            cloudCall(CloudStage.ANONYMOUS_SIGN_IN) {
                client.auth.signInAnonymously()
            }
            logger.info("Anonymous sign-in succeeded")
        }
        return client.auth.currentUserOrNull()?.id
            ?: throw CloudSyncException(
                SyncFailureKind.AUTHENTICATION,
                "Supabase authentication did not produce a reusable user session.",
                "Authentication session lookup failed: no authenticated user was available.",
            )
    }

    override suspend fun upsertJourney(journey: Journey, ownerId: String) {
        logger.info("Starting Journey upsert")
        cloudCall(CloudStage.JOURNEY_UPSERT) {
            client.from("journeys").upsert(journey.toCloudRow(ownerId)) {
                onConflict = "id"
            }
        }
        logger.info("Journey upsert succeeded")
    }

    override suspend fun upsertTelemetry(observations: List<TelemetryObservation>) {
        if (observations.isEmpty()) return
        val firstSequence = observations.first().sequence
        val lastSequence = observations.last().sequence
        logger.info("Starting telemetry batch $firstSequence..$lastSequence")
        cloudCall(CloudStage.TELEMETRY_UPSERT) {
            client.from("telemetry_observations").upsert(observations.map { it.toCloudRow() }) {
                onConflict = "journey_id,sequence"
            }
        }
        logger.info("Telemetry batch $firstSequence..$lastSequence succeeded")
    }

    private suspend fun <T> cloudCall(stage: CloudStage, block: suspend () -> T): T = try {
        block()
    } catch (error: CloudSyncException) {
        logger.warning(error.diagnosticSummary)
        throw error
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        val classified = error.toCloudSyncException(stage)
        logger.warning(classified.diagnosticSummary)
        throw classified
    }
}

internal enum class CloudStage(val label: String) {
    AUTH_INITIALIZATION("Auth initialization"),
    ANONYMOUS_SIGN_IN("Anonymous sign-in"),
    JOURNEY_UPSERT("Journey upsert"),
    TELEMETRY_UPSERT("Telemetry batch"),
}

@Serializable
private data class CloudJourneyRow(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val destination: String,
    @SerialName("expected_arrival_at") val expectedArrivalAt: String,
    @SerialName("started_at") val startedAt: String,
    val status: String,
    @SerialName("completed_at") val completedAt: String?,
)

@Serializable
private data class CloudTelemetryRow(
    @SerialName("journey_id") val journeyId: String,
    val sequence: Long,
    @SerialName("event_time") val eventTime: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Double,
    @SerialName("battery_percent") val batteryPercent: Int?,
    val charging: Boolean?,
    @SerialName("connectivity_state") val connectivityState: String,
)

private fun Journey.toCloudRow(ownerId: String) = CloudJourneyRow(
    id = id,
    ownerId = ownerId,
    destination = destination,
    expectedArrivalAt = Instant.ofEpochMilli(expectedArrivalAt).toString(),
    startedAt = Instant.ofEpochMilli(startedAt).toString(),
    status = status.name,
    completedAt = completedAt?.let { Instant.ofEpochMilli(it).toString() },
)

private fun TelemetryObservation.toCloudRow() = CloudTelemetryRow(
    journeyId = journeyId,
    sequence = sequence,
    eventTime = Instant.ofEpochMilli(eventTime).toString(),
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters.toDouble(),
    batteryPercent = batteryPercent,
    charging = isCharging,
    connectivityState = connectivity.name,
)

internal fun Throwable.toCloudSyncException(stage: CloudStage): CloudSyncException {
    val causeChain = generateSequence(this as Throwable?) { it.cause }.toList()
    val restException = causeChain
        .filterIsInstance<RestException>()
        .firstOrNull()
    val responseException = causeChain
        .filterIsInstance<ResponseException>()
        .firstOrNull()
    val status = restException?.statusCode ?: responseException?.response?.status?.value
    val authError = causeChain.filterIsInstance<AuthRestException>().firstOrNull()
    val postgrestError = causeChain.filterIsInstance<PostgrestRestException>().firstOrNull()
    val postgrestCode = postgrestError?.code?.takeIf { it.matches(Regex("^[A-Za-z0-9_]+$")) }
    val exceptionName = (
        restException
            ?: causeChain.firstOrNull { it is UnknownHostException }
            ?: causeChain.firstOrNull { it is SSLException }
            ?: causeChain.firstOrNull { it is SocketTimeoutException }
            ?: causeChain.firstOrNull { it is ConnectException }
            ?: causeChain.firstOrNull { it is IOException }
            ?: this
        )::class.simpleName ?: "Exception"
    val isRlsOrAuthorization = postgrestCode == "42501" ||
        stage in setOf(CloudStage.JOURNEY_UPSERT, CloudStage.TELEMETRY_UPSERT) && status in setOf(401, 403)
    val isSchemaMismatch = postgrestCode in setOf("42P01", "42703", "42883", "PGRST200", "PGRST204", "PGRST205")
    val kind = when {
        status == 408 || status == 425 || status == 429 || status != null && status >= 500 ->
            SyncFailureKind.TRANSIENT
        causeChain.any { it is UnknownHostException || it is SSLException || it is IOException } ->
            SyncFailureKind.TRANSIENT
        authError != null || stage == CloudStage.ANONYMOUS_SIGN_IN || status == 401 ->
            SyncFailureKind.AUTHENTICATION
        isRlsOrAuthorization || isSchemaMismatch || postgrestError != null ->
            SyncFailureKind.PERMANENT
        causeChain.any { it::class.simpleName?.contains("Auth", ignoreCase = true) == true } ->
            SyncFailureKind.AUTHENTICATION
        else -> SyncFailureKind.PERMANENT
    }
    val statusText = status?.let { "HTTP $it" }
    val codeText = postgrestCode?.let { ", code $it" }.orEmpty()
    val safeMessage = when {
        causeChain.any { it is UnknownHostException } ->
            "${stage.label} failed: DNS lookup could not reach Supabase ($exceptionName)."
        causeChain.any { it is SSLException } ->
            "${stage.label} failed: TLS connection to Supabase failed ($exceptionName)."
        causeChain.any { it is SocketTimeoutException } ->
            "${stage.label} failed: network request timed out ($exceptionName)."
        causeChain.any { it is ConnectException } ->
            "${stage.label} failed: network connection to Supabase failed ($exceptionName)."
        authError != null || stage == CloudStage.ANONYMOUS_SIGN_IN && status != null ->
            "Anonymous authentication failed: ${statusText ?: "HTTP error"} ($exceptionName)."
        isRlsOrAuthorization ->
            "${stage.label} failed: PostgREST authorization/RLS error (${statusText ?: "HTTP error"}$codeText)."
        isSchemaMismatch ->
            "${stage.label} failed: cloud schema mismatch (${statusText ?: "PostgREST error"}$codeText)."
        postgrestError != null ->
            "${stage.label} failed: PostgREST error (${statusText ?: "HTTP error"}$codeText)."
        status != null ->
            "${stage.label} failed: HTTP $status ($exceptionName)."
        causeChain.any { it is IOException } ->
            "${stage.label} failed: network transport error ($exceptionName)."
        kind == SyncFailureKind.AUTHENTICATION ->
            "${stage.label} failed: authentication error ($exceptionName)."
        else ->
            "${stage.label} failed: unexpected cloud client error ($exceptionName)."
    }
    return CloudSyncException(
        kind = kind,
        safeMessage = safeMessage,
        diagnosticSummary = safeMessage,
        cause = this,
    )
}
