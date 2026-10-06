package com.journeycontinuity.app.data.search

import com.journeycontinuity.app.auth.TravellerAuthException
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.ui.destination.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Existing Traveller session only. No service role or provider geocoding credential. */
class SupabaseDestinationMapGateway internal constructor(
    private val accessToken: suspend () -> String?,
    private val transport: suspend (path: String, token: String, body: String) -> DestinationSearchHttpResponse,
) : DestinationMapGateway {
    constructor(client: SupabaseClient, identity: TravellerIdentityCoordinator,
        supabaseUrl: String, publishableKey: String,
        http: HttpClient = HttpClient(Android) {
            install(HttpTimeout) { requestTimeoutMillis = 12_000; connectTimeoutMillis = 8_000 }
        },
    ) : this({ identity.requireAuthenticatedTraveller(); client.auth.currentAccessTokenOrNull() },
        { path, token, body ->
            val response = http.post("${supabaseUrl.trimEnd('/')}/$path") {
                contentType(ContentType.Application.Json)
                headers {
                    append("Authorization", "Bearer $token")
                    append("apikey", publishableKey)
                    append("Cache-Control", "no-store")
                }
                setBody(body)
            }
            DestinationSearchHttpResponse(response.status.value, response.bodyAsText())
        })

    override suspend fun verify(request: MapPointRequest): CoordinateVerification = call(
        "rest/v1/rpc/verify_destination_coordinate_v1", buildJsonObject {
            put("p_region_id", request.regionId); put("p_local_unit_id", request.localUnitId)
            put("p_latitude", request.coordinate.latitude); put("p_longitude", request.coordinate.longitude)
        }, DestinationMapContractParser::verification)
    override suspend fun viewport(regionId: String, localUnitId: String): LocalUnitViewport = call(
        "rest/v1/rpc/get_destination_local_unit_viewport_v1", buildJsonObject {
            put("p_region_id", regionId); put("p_local_unit_id", localUnitId)
        }, DestinationMapContractParser::viewport)
    override suspend fun label(request: MapPointRequest): MapLabelResponse = call(
        "functions/v1/destination-map-label", buildJsonObject {
            put("regionId", request.regionId); put("localUnitId", request.localUnitId)
            put("latitude", request.coordinate.latitude); put("longitude", request.coordinate.longitude)
        }, DestinationMapContractParser::label)

    private suspend fun <T> call(path: String, body: JsonObject, parse: (String) -> T): T {
        try {
            val token = accessToken() ?: throw DestinationMapException(DestinationMapFailure.AUTHENTICATION)
            val response = transport(path, token, body.toString())
            if (response.status in setOf(401, 403)) throw DestinationMapException(DestinationMapFailure.AUTHENTICATION)
            if (response.status !in 200..299) throw DestinationMapException(DestinationMapFailure.UNAVAILABLE)
            return parse(response.body)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (known: DestinationMapException) { throw known
        } catch (_: TravellerAuthException) { throw DestinationMapException(DestinationMapFailure.AUTHENTICATION)
        } catch (_: Exception) { throw DestinationMapException(DestinationMapFailure.UNAVAILABLE) }
    }
}

object UnavailableDestinationMapGateway : DestinationMapGateway {
    override suspend fun verify(request: MapPointRequest): CoordinateVerification = unavailable()
    override suspend fun viewport(regionId: String, localUnitId: String): LocalUnitViewport = unavailable()
    override suspend fun label(request: MapPointRequest): MapLabelResponse = unavailable()
    private fun unavailable(): Nothing = throw DestinationMapException(DestinationMapFailure.UNAVAILABLE)
}
