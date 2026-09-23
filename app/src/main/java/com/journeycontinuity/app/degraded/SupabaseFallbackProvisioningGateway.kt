package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.sync.CloudSyncException
import com.journeycontinuity.app.sync.SyncFailureKind
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SupabaseFallbackProvisioningGateway(
    private val client: SupabaseClient,
    private val identityCoordinator: TravellerIdentityCoordinator,
    private val supabaseUrl: String,
    private val publishableKey: String,
) : AuthenticatedFallbackProvisioningGateway {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun provision(
        journeyId: String,
        installationId: String,
    ): FallbackProvisioningMaterial {
        identityCoordinator.requireAuthenticatedTraveller()
        val accessToken = client.auth.currentAccessTokenOrNull()
            ?: throw CloudSyncException(
                SyncFailureKind.AUTHENTICATION,
                "Fallback provisioning requires an authenticated traveller session.",
            )
        return withContext(Dispatchers.IO) {
            val connection = (URL("${supabaseUrl.trimEnd('/')}/functions/v1/provision-fallback")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
            }
            try {
                connection.outputStream.use { output ->
                    output.write(
                        json.encodeToString(
                            ProvisionRequest.serializer(),
                            ProvisionRequest(installationId, journeyId),
                        ).encodeToByteArray(),
                    )
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    connection.errorStream?.use { it.readBytes() }
                    throw CloudSyncException(
                        kind = when {
                            status == 401 || status == 403 -> SyncFailureKind.AUTHENTICATION
                            status >= 500 || status == 429 -> SyncFailureKind.TRANSIENT
                            else -> SyncFailureKind.PERMANENT
                        },
                        safeMessage = "Fallback provisioning was rejected by the server.",
                        diagnosticSummary = "Fallback provisioning HTTP status $status.",
                    )
                }
                val response = connection.inputStream.bufferedReader().use { reader ->
                    json.decodeFromString(ProvisionResponse.serializer(), reader.readText())
                }
                val masterKey = decodeBase64Url(response.installationMasterKey)
                try {
                    FallbackProvisioningMaterial(
                        keyId = response.keyId,
                        installationMasterKey = masterKey,
                        journeyHandle = decodeBase64Url(response.journeyHandle),
                        bindingStatus = response.bindingStatus,
                        bindingVersion = response.bindingVersion,
                    )
                } catch (error: Throwable) {
                    masterKey.fill(0)
                    throw error
                }
            } catch (error: CloudSyncException) {
                throw error
            } catch (error: IOException) {
                throw CloudSyncException(
                    SyncFailureKind.TRANSIENT,
                    "Fallback provisioning is temporarily unavailable.",
                    "Fallback provisioning network request failed.",
                    error,
                )
            } catch (error: Throwable) {
                throw CloudSyncException(
                    SyncFailureKind.PERMANENT,
                    "Fallback provisioning returned an invalid response.",
                    "Fallback provisioning response validation failed.",
                    error,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun decodeBase64Url(value: String): ByteArray = try {
        Base64.getUrlDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw CloudSyncException(
            SyncFailureKind.PERMANENT,
            "Fallback provisioning returned an invalid response.",
            cause = error,
        )
    }

    @Serializable
    private data class ProvisionRequest(
        @SerialName("installation_id") val installationId: String,
        @SerialName("journey_id") val journeyId: String,
    )

    @Serializable
    private data class ProvisionResponse(
        @SerialName("key_id") val keyId: Long,
        @SerialName("installation_master_key") val installationMasterKey: String,
        @SerialName("journey_handle") val journeyHandle: String,
        @SerialName("binding_status") val bindingStatus: String,
        @SerialName("binding_version") val bindingVersion: Int,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 20_000
    }
}
