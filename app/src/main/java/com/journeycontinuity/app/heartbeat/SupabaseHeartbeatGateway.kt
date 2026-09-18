package com.journeycontinuity.app.heartbeat

import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.domain.CloudMonitoringPhase
import com.journeycontinuity.app.domain.DeviceHeartbeat
import com.journeycontinuity.app.sync.CloudStage
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.NoOpSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncDiagnosticLogger
import com.journeycontinuity.app.sync.toCloudSyncException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseHeartbeatGateway(
    private val client: SupabaseClient,
    private val identityCoordinator: TravellerIdentityCoordinator,
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
) : HeartbeatGateway {
    override suspend fun requireAuthenticatedTraveller() {
        try {
            identityCoordinator.requireAuthenticatedTraveller()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw error.toCloudSyncException(CloudStage.AUTH_INITIALIZATION)
        }
    }

    override suspend fun recordFreshHeartbeat(heartbeat: DeviceHeartbeat): HeartbeatServerState {
        try {
            val parameters = heartbeat.toRpcParameters()
            logger.info(
                "Heartbeat RPC battery fields: percentage=${parameters.rpcNullability("p_battery_percent")}, " +
                    "charging=${parameters.rpcNullability("p_charging")}",
            )
            val response = client.postgrest.rpc(
                function = "record_journey_heartbeat",
                parameters = parameters,
            ).decodeSingle<HeartbeatRpcResponse>()
            return HeartbeatServerState(
                phase = CloudMonitoringPhase.valueOf(response.phase),
                lastCloudContactAt = Instant.parse(response.lastCloudContactAt).toEpochMilli(),
                latestHeartbeatSequence = response.latestHeartbeatSequence,
            )
        } catch (error: CloudSyncException) {
            throw error
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val classified = error.toCloudSyncException(CloudStage.HEARTBEAT_RPC)
            logger.warning(classified.diagnosticSummary)
            throw classified
        }
    }
}

@Serializable
private data class HeartbeatRpcResponse(
    val phase: String,
    @SerialName("last_cloud_contact_at") val lastCloudContactAt: String,
    @SerialName("latest_heartbeat_sequence") val latestHeartbeatSequence: Long,
)

internal fun DeviceHeartbeat.toRpcParameters() = buildJsonObject {
    put("p_journey_id", journeyId)
    put("p_sequence", sequence)
    put("p_client_sent_at", Instant.ofEpochMilli(clientSentAt).toString())
    if (batteryPercent == null) {
        put("p_battery_percent", JsonNull)
    } else {
        put("p_battery_percent", batteryPercent)
    }
    if (isCharging == null) {
        put("p_charging", JsonNull)
    } else {
        put("p_charging", isCharging)
    }
    put("p_connectivity_state", connectivity.name)
    put("p_latest_telemetry_sequence", latestTelemetrySequence)
}

private fun kotlinx.serialization.json.JsonObject.rpcNullability(name: String): String =
    if (this[name] == null || this[name] === JsonNull) "null" else "non-null"
