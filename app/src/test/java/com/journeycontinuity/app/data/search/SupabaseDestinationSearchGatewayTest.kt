package com.journeycontinuity.app.data.search

import com.journeycontinuity.app.ui.destination.DestinationSearchException
import com.journeycontinuity.app.ui.destination.DestinationSearchFailure
import com.journeycontinuity.app.ui.destination.DestinationSearchRequest
import com.journeycontinuity.app.ui.destination.DestinationSearchResponse
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SupabaseDestinationSearchGatewayTest {
    private val request = DestinationSearchRequest("hospital", "ng-fct", "ng-fct-abuja-municipal")

    @Test fun outgoingBodyUsesProductionSerializerAndIncludesExplicitAutocompleteMode() = runTest {
        var capturedBody: String? = null
        val gateway = SupabaseDestinationSearchGateway({ "fake-session-token" }) { _, body ->
            capturedBody = body
            DestinationSearchHttpResponse(200, """{"outcome":"NO_RESULTS","results":[]}""")
        }
        gateway.autocomplete(request)
        val body = Json.parseToJsonElement(requireNotNull(capturedBody)).jsonObject
        assertEquals(setOf("query", "regionId", "localUnitId", "mode"), body.keys)
        assertEquals(request.query, body.getValue("query").jsonPrimitive.content)
        assertEquals(request.regionId, body.getValue("regionId").jsonPrimitive.content)
        assertEquals(request.localUnitId, body.getValue("localUnitId").jsonPrimitive.content)
        assertEquals("autocomplete", body.getValue("mode").jsonPrimitive.content)
    }

    @Test fun authenticationFailureNeverDispatchesHttp() = runTest {
        var calls = 0
        val gateway = SupabaseDestinationSearchGateway({ null }) { _, _ ->
            calls++; DestinationSearchHttpResponse(200, "")
        }
        val failure = runCatching { gateway.autocomplete(request) }.exceptionOrNull() as DestinationSearchException
        assertEquals(DestinationSearchFailure.AUTHENTICATION, failure.reason)
        assertEquals(0, calls)
    }

    @Test fun statusMappingIsBoundedAndDoesNotRetry() = runTest {
        for (status in listOf(401, 403, 400, 429, 500, 503)) {
            var calls = 0
            val gateway = SupabaseDestinationSearchGateway({ "fake-session-token" }) { _, sent ->
                assertEquals(request.query, Json.parseToJsonElement(sent).jsonObject.getValue("query").jsonPrimitive.content)
                calls++; DestinationSearchHttpResponse(status, "untrusted error body")
            }
            val failure = runCatching { gateway.autocomplete(request) }.exceptionOrNull() as DestinationSearchException
            assertEquals(if (status == 401 || status == 403) DestinationSearchFailure.AUTHENTICATION
                else DestinationSearchFailure.UNAVAILABLE, failure.reason)
            assertEquals(1, calls)
        }
    }

    @Test fun ktorTimeoutIsUnavailableAndDoesNotRetry() = runTest {
        var calls = 0
        val gateway = SupabaseDestinationSearchGateway({ "fake-session-token" }) { _, _ ->
            calls++; throw HttpRequestTimeoutException("https://example.test", 12_000L)
        }
        val failure = runCatching { gateway.autocomplete(request) }.exceptionOrNull() as DestinationSearchException
        assertEquals(DestinationSearchFailure.UNAVAILABLE, failure.reason)
        assertEquals(1, calls)
    }

    @Test fun cancellationIsPropagatedUnchanged() = runTest {
        val cancelled = CancellationException("test cancellation")
        val gateway = SupabaseDestinationSearchGateway({ "fake-session-token" }) { _, _ -> throw cancelled }
        assertSame(cancelled, runCatching { gateway.autocomplete(request) }.exceptionOrNull())
    }

    @Test fun emptySuccessAndMalformedSuccessRemainDistinct() = runTest {
        val empty = SupabaseDestinationSearchGateway({ "fake-session-token" }) { _, _ ->
            DestinationSearchHttpResponse(200, """{"outcome":"NO_RESULTS","results":[]}""")
        }
        assertEquals(DestinationSearchResponse.NoResults, empty.autocomplete(request))
        val malformed = SupabaseDestinationSearchGateway({ "fake-session-token" }) { _, _ ->
            DestinationSearchHttpResponse(200, "{}")
        }
        val failure = runCatching { malformed.autocomplete(request) }.exceptionOrNull() as DestinationSearchException
        assertEquals(DestinationSearchFailure.UNAVAILABLE, failure.reason)
    }
}
