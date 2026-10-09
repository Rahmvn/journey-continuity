package com.journeycontinuity.app.sync

import com.journeycontinuity.app.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class UnresolvedAdmissionCloudRowTest {
    private val journey = Journey("20000000-0000-4000-8000-000000000010", "Label", 9000, 1000,
        JourneyStatus.ACTIVE, null)
    private val admission = BoundaryAdmission(journey.id, "30000000-0000-4000-8000-000000000010",
        1000, 9000, 9000 + 86_400_000)
    @Test fun serializedRetryCarriesExactOriginalAdmissionNoReceiptTime() {
        val row = journey.toCloudRow("owner", admission)
        val json = Json.parseToJsonElement(Json.encodeToString(row)).jsonObject
        assertEquals(admission.admissionId, json.getValue("unresolved_admission_id").jsonPrimitive.content)
        assertEquals(JourneyEtaPolicy.VERSION, json.getValue("unresolved_policy_version").jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:09Z", row.expectedArrivalAt)
        assertEquals(row, journey.toCloudRow("owner", admission))
    }
    @Test fun legacyPayloadDoesNotInventProvenance() {
        assertNull(journey.toCloudRow("owner").unresolvedAdmissionId)
    }
    @Test(expected = IllegalArgumentException::class) fun mismatchedAdmissionCannotUpload() {
        journey.toCloudRow("owner", admission.copy(etaMillis = 8000))
    }
}
