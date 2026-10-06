package com.journeycontinuity.app.data.search

import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.auth.TravellerAuthException
import com.journeycontinuity.app.ui.destination.DestinationSearchException
import com.journeycontinuity.app.ui.destination.DestinationSearchFailure
import com.journeycontinuity.app.ui.destination.DestinationSearchGateway
import com.journeycontinuity.app.ui.destination.DestinationSearchRequest
import com.journeycontinuity.app.ui.destination.DestinationSearchResponse
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only the Alabarin Edge Function is reachable from Android; the provider key stays server-side. */
class SupabaseDestinationSearchGateway internal constructor(
    private val authenticatedAccessToken: suspend () -> String?,
    private val transport: suspend (String, String) -> DestinationSearchHttpResponse,
) : DestinationSearchGateway {
    constructor(
        client: SupabaseClient,
        identityCoordinator: TravellerIdentityCoordinator,
        supabaseUrl: String,
        publishableKey: String,
        http: HttpClient = HttpClient(Android) {
            install(HttpTimeout) { requestTimeoutMillis = 12_000; connectTimeoutMillis = 8_000 }
        },
    ) : this(
        authenticatedAccessToken = {
            identityCoordinator.requireAuthenticatedTraveller()
            client.auth.currentAccessTokenOrNull()
        },
        transport = { token, body ->
            val response = http.post("${supabaseUrl.trimEnd('/')}/functions/v1/destination-search") {
                contentType(ContentType.Application.Json)
                headers {
                    append("Authorization", "Bearer $token")
                    append("apikey", publishableKey)
                    append("Cache-Control", "no-store")
                }
                setBody(body)
            }
            DestinationSearchHttpResponse(response.status.value, response.bodyAsText())
        },
    )

    override suspend fun autocomplete(request: DestinationSearchRequest): DestinationSearchResponse {
        try {
            val token = authenticatedAccessToken()
                ?: throw DestinationSearchException(DestinationSearchFailure.AUTHENTICATION)
            val body = Json.encodeToString(WireRequest.serializer(), WireRequest(
                request.query, request.regionId, request.localUnitId, mode = "autocomplete",
            ))
            val response = transport(token, body)
            if (response.status == 401 || response.status == 403) {
                throw DestinationSearchException(DestinationSearchFailure.AUTHENTICATION)
            }
            if (response.status !in 200..299) {
                throw DestinationSearchException(DestinationSearchFailure.UNAVAILABLE)
            }
            return DestinationSearchContractParser.parse(response.body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: DestinationSearchException) {
            throw known
        } catch (_: TravellerAuthException) {
            throw DestinationSearchException(DestinationSearchFailure.AUTHENTICATION)
        } catch (_: Exception) {
            throw DestinationSearchException(DestinationSearchFailure.UNAVAILABLE)
        }
    }

    @Serializable private data class WireRequest(
        val query: String, val regionId: String, val localUnitId: String,
        val mode: String,
    )
}

/** A narrow injectable HTTP boundary for deterministic status/timeout tests, not a provider DTO. */
internal data class DestinationSearchHttpResponse(val status: Int, val body: String)
