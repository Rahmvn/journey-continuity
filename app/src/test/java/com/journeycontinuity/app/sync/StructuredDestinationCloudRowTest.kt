package com.journeycontinuity.app.sync

import com.journeycontinuity.app.domain.DestinationKind
import com.journeycontinuity.app.domain.DestinationSelectionSource
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.StructuredDestination
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredDestinationCloudRowTest {
    private val base = Journey("journey", "Summit University, Offa", 9_000, 1_000,
        JourneyStatus.ACTIVE, null)

    @Test fun structuredPointUploadsAllFactsWithoutChangingLabel() {
        val point = StructuredDestination("NG", "NG-KW", "Kwara", "NG-KW-OFFA", "Offa",
            DestinationKind.POINT, 8.49, 4.56, DestinationSelectionSource.PLACE_SEARCH,
            provider = "provider", providerPlaceId = "place-1", adminDatasetVersion = "v1")
        val row = base.copy(structuredDestination = point).toCloudRow("owner")
        assertEquals(base.destination, row.destination)
        assertEquals("POINT", row.destinationKind)
        assertEquals(8.49, row.destinationLatitude!!, 0.0)
        assertEquals("place-1", row.destinationProviderPlaceId)
        assertEquals("v1", row.destinationAdminDatasetVersion)
        assertEquals(point.localUnitId, row.destinationLgaId)
        assertEquals(point.localUnitName, row.destinationLgaName)
        val json = Json.encodeToString(row)
        assertTrue(json.contains("\"destination_latitude\":8.49"))
        assertTrue(json.contains("\"destination_state_id\":\"NG-KW\""))
        assertTrue(json.contains("\"destination_lga_id\":\"NG-KW-OFFA\""))
    }

    @Test fun historicalTextOnlyAndTerminalRowsStillUpload() {
        val textOnly = base.toCloudRow("owner")
        assertNull(textOnly.destinationKind)
        assertNull(textOnly.destinationLatitude)
        assertTrue(Json.encodeToString(textOnly).contains("\"destination_latitude\":null"))
        val terminal = base.copy(status = JourneyStatus.CANCELLED, endedAt = 5_000).toCloudRow("owner")
        assertEquals("CANCELLED", terminal.status)
        assertNull(terminal.destinationKind)
        assertTrue(terminal.endedAt != null)
    }

    @Test fun localityUploadsWithoutAnyPointCoordinates() {
        val locality = StructuredDestination("NG", "NG-KW", "Kwara", "NG-KW-OFFA", "Offa",
            DestinationKind.LOCALITY, null, null, DestinationSelectionSource.LOCALITY_SELECTION)
        val row = base.copy(structuredDestination = locality).toCloudRow("owner")
        assertEquals("LOCALITY", row.destinationKind)
        assertEquals("LOCALITY_SELECTION", row.destinationSelectionSource)
        assertNull(row.destinationLatitude)
        assertNull(row.destinationLongitude)
        assertEquals(base.destination, row.destination)
    }
}
