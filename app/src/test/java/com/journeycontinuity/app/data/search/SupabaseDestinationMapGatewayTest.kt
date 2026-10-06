package com.journeycontinuity.app.data.search

import com.journeycontinuity.app.ui.destination.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SupabaseDestinationMapGatewayTest {
    private val version = "alabarin-2026-10-03-v1"
    private val point = MapPointRequest("ng-kwara", "ng-kwara-offa", MapCoordinate(8.1560008, 4.7227435))
    private fun verification(outcome: String = "INSIDE") = buildJsonObject {
        put("outcome", outcome)
        val hasDataset = outcome !in setOf("INVALID_CONTEXT", "UNAVAILABLE")
        put("boundary_dataset_id", if (hasDataset) JsonPrimitive(OPERATIONAL_BOUNDARY_DATASET) else JsonNull)
        put("boundary_dataset_version", if (hasDataset) JsonPrimitive(version) else JsonNull)
    }
    private fun label(status: String = "NO_MEANINGFUL_LABEL") = buildJsonObject {
        put("status", status); put("boundaryOutcome", "INSIDE"); put("boundaryDatasetVersion", version)
        put("canonicalLocalUnitName", "Offa"); put("resultType", "UNKNOWN")
        for (key in listOf("label", "secondaryLabel", "provider", "providerPlaceId", "distanceMeters", "attribution"))
            put(key, JsonNull)
    }
    private fun specific() = JsonObject(label().toMutableMap().apply {
        put("status", JsonPrimitive("SPECIFIC_LABEL")); put("resultType", JsonPrimitive("PLACE"))
        put("label", JsonPrimitive("Summit University")); put("provider", JsonPrimitive("GEOAPIFY"))
        put("distanceMeters", JsonPrimitive(12.0))
        put("attribution", buildJsonObject {
            put("sourceName", "OpenStreetMap"); put("attribution", "OpenStreetMap contributors")
            put("license", "ODbL"); put("sourceUrl", "https://www.openstreetmap.org/copyright")
        })
    })
    private fun gateway(body: String, status: Int = 200) = SupabaseDestinationMapGateway({ "traveller-jwt" }) {
        _, _, _ -> DestinationSearchHttpResponse(status, body)
    }
    private suspend fun failure(reason: DestinationMapFailure = DestinationMapFailure.INVALID_RESPONSE,
        block: suspend () -> Unit) {
        try { block(); fail("Expected fail-closed transport") }
        catch (expected: DestinationMapException) { assertEquals(reason, expected.reason) }
    }

    @Test fun allThreeCallsPreserveTravellerJwtAndUseOnlyTheirExactContracts() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val gateway = SupabaseDestinationMapGateway({ "traveller-jwt" }) { path, token, body ->
            assertEquals("traveller-jwt", token)
            calls += path to (Json.parseToJsonElement(body) as JsonObject)
            DestinationSearchHttpResponse(200, when {
                path.endsWith("verify_destination_coordinate_v1") -> verification().toString()
                path.endsWith("get_destination_local_unit_viewport_v1") -> "{\"status\":\"UNAVAILABLE\"}"
                else -> label().toString()
            })
        }
        gateway.verify(point); gateway.viewport(point.regionId, point.localUnitId); gateway.label(point)
        assertEquals(listOf("rest/v1/rpc/verify_destination_coordinate_v1",
            "rest/v1/rpc/get_destination_local_unit_viewport_v1", "functions/v1/destination-map-label"), calls.map { it.first })
        assertEquals(setOf("p_region_id", "p_local_unit_id", "p_latitude", "p_longitude"), calls[0].second.keys)
        assertEquals(setOf("p_region_id", "p_local_unit_id"), calls[1].second.keys)
        assertEquals(setOf("regionId", "localUnitId", "latitude", "longitude"), calls[2].second.keys)
        assertEquals(point.coordinate.latitude, calls[0].second.getValue("p_latitude").jsonPrimitive.double, 0.0)
        assertEquals(point.coordinate.longitude, calls[0].second.getValue("p_longitude").jsonPrimitive.double, 0.0)
        assertEquals(point.coordinate.latitude, calls[2].second.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(point.coordinate.longitude, calls[2].second.getValue("longitude").jsonPrimitive.double, 0.0)
    }
    @Test fun absentAuthenticationNeverReachesTransport() = runTest {
        var calls = 0
        val gateway = SupabaseDestinationMapGateway({ null }) { _, _, _ -> calls++; error("must not call") }
        failure(DestinationMapFailure.AUTHENTICATION) { gateway.verify(point) }
        failure(DestinationMapFailure.AUTHENTICATION) { gateway.viewport(point.regionId, point.localUnitId) }
        failure(DestinationMapFailure.AUTHENTICATION) { gateway.label(point) }
        assertEquals(0, calls)
    }
    @Test fun authenticationAndOtherHttpFailuresFailClosedWithoutLeakingBody() = runTest {
        for (status in listOf(401, 403, 429, 500)) {
            failure(if (status in listOf(401, 403)) DestinationMapFailure.AUTHENTICATION else DestinationMapFailure.UNAVAILABLE) {
                gateway("private server diagnostic", status).verify(point)
            }
        }
    }
    @Test fun cancellationRemainsCancellation() = runTest {
        val gateway = SupabaseDestinationMapGateway({ "jwt" }) { _, _, _ -> throw CancellationException("cancelled") }
        try { gateway.verify(point); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }
    @Test fun allSixVerifierOutcomesRetainOnlyMatchingDatasetEvidence() = runTest {
        for (outcome in BoundaryOutcome.entries) {
            val parsed = gateway(verification(outcome.name).toString()).verify(point)
            assertEquals(outcome, parsed.outcome)
            assertEquals(outcome !in setOf(BoundaryOutcome.INVALID_CONTEXT, BoundaryOutcome.UNAVAILABLE), parsed.hasBoundaryEvidence)
        }
    }
    @Test fun malformedVerifierCannotBecomeInside() = runTest {
        for (body in listOf("{}", "[]", "null", "not json",
            verification().toString().replace("INSIDE", "UNKNOWN"),
            verification().toString().replace(OPERATIONAL_BOUNDARY_DATASET, "NG_ADMIN"),
            verification().toString().replace("\"$version\"", "null"),
            verification("UNAVAILABLE").toString().replace("\"boundary_dataset_version\":null", "\"boundary_dataset_version\":\"$version\""))) {
            failure { gateway(body).verify(point) }
        }
    }
    @Test fun availableViewportParsesVersionAndFiniteOrderedBounds() = runTest {
        val body = """{"status":"AVAILABLE","west":4.6,"south":8.0,"east":4.8,"north":8.3,"boundary_dataset_id":"$OPERATIONAL_BOUNDARY_DATASET","boundary_dataset_version":"$version"}"""
        val parsed = gateway(body).viewport(point.regionId, point.localUnitId) as LocalUnitViewport.Available
        assertEquals(4.6, parsed.west, 0.0); assertEquals(8.3, parsed.north, 0.0)
        assertEquals(version, parsed.datasetVersion)
        for (invalid in listOf(body.replace("\"east\":4.8", "\"east\":4.5"),
            body.replace("\"north\":8.3", "\"north\":8.0"), body.replace("\"west\":4.6", "\"west\":1e300"),
            body.replace("\"south\":8.0", "\"south\":\"8.0\""))) {
            failure { gateway(invalid).viewport(point.regionId, point.localUnitId) }
        }
        assertEquals(LocalUnitViewport.InvalidContext, gateway("{\"status\":\"INVALID_CONTEXT\"}").viewport("a", "b"))
    }
    @Test fun labelSpecificEvidenceAllowsMissingPlaceIdAndDoesNotContainCoordinates() = runTest {
        val parsed = gateway(specific().toString()).label(point) as MapLabelResponse.Verified
        assertEquals(MapLabelStatus.SPECIFIC_LABEL, parsed.status); assertEquals(version, parsed.boundaryVersion)
        assertEquals("Summit University", parsed.evidence!!.label); assertNull(parsed.evidence.providerPlaceId)
        assertEquals(12.0, parsed.evidence.distanceMeters!!, 0.0)
        assertEquals("ODbL", parsed.evidence.attribution.license)
        assertFalse(MapLabelEvidence::class.java.declaredFields.any { it.name in setOf("latitude", "longitude", "raw", "properties") })
    }
    @Test fun nonSpecificLabelsNeverSupplyFalseProviderEvidence() = runTest {
        for (status in listOf("BROAD_ONLY", "NO_MEANINGFUL_LABEL", "PROVIDER_UNAVAILABLE")) {
            val parsed = gateway(label(status).toString()).label(point) as MapLabelResponse.Verified
            assertNull(parsed.evidence); assertEquals(MapLabelStatus.valueOf(status), parsed.status)
        }
        failure { gateway(specific().toString().replace("SPECIFIC_LABEL", "BROAD_ONLY")).label(point) }
    }
    @Test fun malformedLabelAttributionAndDistanceFailClosed() = runTest {
        for (body in listOf(specific().toString().replace("https://", "http://"),
            specific().toString().replace("\"attribution\":{", "\"attribution\":{\"ignored\":null,"),
            specific().toString().replace("\"distanceMeters\":12.0", "\"distanceMeters\":1e300"))) {
            // Extra provider fields are ignored, never forwarded; malformed retained fields fail closed.
            if (body.contains("ignored")) {
                assertEquals("Summit University", (gateway(body).label(point) as MapLabelResponse.Verified).evidence!!.label)
            } else failure { gateway(body).label(point) }
        }
        failure { gateway(specific().toString().replace("\"license\":\"ODbL\"", "\"license\":null")).label(point) }
        failure { gateway(specific().toString().replace("\"resultType\":\"PLACE\"", "\"resultType\":\"LOCALITY\"")).label(point) }
    }
    @Test fun allNonInsideLabelReverificationsStayFailuresAndInsideFailureIsRejected() = runTest {
        for (outcome in BoundaryOutcome.entries) {
            val body = buildJsonObject {
                put("status", "VERIFICATION_FAILED"); put("boundaryOutcome", outcome.name)
                put("boundaryDatasetVersion", verification(outcome.name).getValue("boundary_dataset_version"))
            }.toString()
            if (outcome == BoundaryOutcome.INSIDE) failure { gateway(body).label(point) }
            else assertEquals(outcome, (gateway(body).label(point) as MapLabelResponse.VerificationFailed).verification.outcome)
        }
    }
}
