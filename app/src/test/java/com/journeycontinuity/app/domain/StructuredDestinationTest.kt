package com.journeycontinuity.app.domain

import org.junit.Assert.*
import org.junit.Test
import com.journeycontinuity.app.domain.DestinationEvidenceFixtures.attribution
import com.journeycontinuity.app.domain.DestinationEvidenceFixtures.mapPin
import com.journeycontinuity.app.domain.DestinationEvidenceFixtures.mapPinWithProvider
import com.journeycontinuity.app.domain.DestinationEvidenceFixtures.placeSearch
import com.journeycontinuity.app.domain.DestinationEvidenceFixtures.locality
import com.journeycontinuity.app.domain.DestinationEvidenceFixtures.areaCouncil

class StructuredDestinationTest {
    @Test fun localityLgaIsValid() { assertEquals(AdminLocalUnitType.LGA, locality().localUnitType) }
    @Test fun localityAreaCouncilIsValid() { assertEquals(AdminLocalUnitType.AREA_COUNCIL, areaCouncil().localUnitType) }
    @Test fun localityRejectsCoordinates() { rejects { locality().copy(latitude = 8.0, longitude = 4.0) } }
    @Test fun localityRejectsBoundaryId() { rejects { locality().copy(boundaryDatasetId = DESTINATION_BOUNDARY_DATASET) } }
    @Test fun localityRejectsBoundaryVersion() { rejects { locality().copy(boundaryDatasetVersion = "v1") } }
    @Test fun localityRejectsProvider() { rejects { locality().copy(provider = "GEOAPIFY") } }
    @Test fun localityRejectsPlaceId() { rejects { locality().copy(providerPlaceId = "id") } }
    @Test fun localityRejectsAttribution() { rejects { locality().copy(providerAttribution = attribution) } }
    @Test fun localityRejectsCustomPinName() { rejects { locality().copy(customPlaceName = "Home") } }
    @Test fun mapPinPreservesExactCoordinateWithoutProvider() {
        val point = mapPin().copy(customPlaceName = "Grandma's house")
        assertEquals(8.1560008, point.latitude!!, 0.0)
        assertEquals(4.7227435, point.longitude!!, 0.0)
        assertNull(point.provider); assertNull(point.providerPlaceId); assertNull(point.providerAttribution)
        assertEquals("StructuredDestination(redacted)", point.toString())
    }
    @Test fun mapPinAcceptsReverseEvidenceWithoutPlaceId() { assertEquals(attribution, mapPinWithProvider().providerAttribution) }
    @Test fun mapPinAcceptsReverseEvidenceWithPlaceId() { assertEquals("id", mapPinWithProvider("id").providerPlaceId) }
    @Test fun mapPinRejectsProviderWithoutAttribution() { rejects { mapPin().copy(provider = "GEOAPIFY") } }
    @Test fun mapPinRejectsAttributionWithoutProvider() { rejects { mapPin().copy(providerAttribution = attribution) } }
    @Test fun mapPinRejectsPlaceIdWithoutProvider() { rejects { mapPin().copy(providerPlaceId = "id") } }
    @Test fun mapPinRejectsUnsupportedReverseProvider() { rejects { mapPinWithProvider().copy(provider = "OTHER") } }
    @Test fun pointRequiresBoundaryId() { rejects { mapPin().copy(boundaryDatasetId = null) } }
    @Test fun pointRequiresKnownBoundaryIdentity() { rejects { mapPin().copy(boundaryDatasetId = "OTHER") } }
    @Test fun pointRequiresBoundaryVersion() { rejects { mapPin().copy(boundaryDatasetVersion = null) } }
    @Test fun pointRequiresBothCoordinates() {
        rejects { mapPin().copy(latitude = null) }; rejects { mapPin().copy(longitude = null) }
    }
    @Test fun coordinatesMustBeFiniteAndWithinRange() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -90.001, 90.001).forEach { bad ->
            rejects { mapPin().copy(latitude = bad) }
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, -180.001, 180.001).forEach { bad ->
            rejects { mapPin().copy(longitude = bad) }
        }
    }
    @Test fun kindAndSourceMustAgree() {
        rejects { mapPin().copy(selectionSource = DestinationSelectionSource.LOCALITY_SELECTION) }
        rejects { locality().copy(selectionSource = DestinationSelectionSource.MAP_PIN) }
    }
    @Test fun placeSearchRetainsFullProvenance() { assertEquals(attribution, placeSearch().providerAttribution) }
    @Test fun placeSearchRequiresProvider() { rejects { placeSearch().copy(provider = null) } }
    @Test fun placeSearchRequiresPlaceId() { rejects { placeSearch().copy(providerPlaceId = null) } }
    @Test fun placeSearchRequiresAttribution() { rejects { placeSearch().copy(providerAttribution = null) } }
    @Test fun placeSearchRequiresBoundaryEvidence() {
        rejects { placeSearch().copy(boundaryDatasetId = null) }
        rejects { placeSearch().copy(boundaryDatasetVersion = null) }
    }
    @Test fun placeSearchRejectsCustomPinName() { rejects { placeSearch().copy(customPlaceName = "Home") } }
    @Test fun optionalStringsCannotBeBlankOrPadded() {
        listOf("", " ", " id", "id ", "id\n").forEach { bad ->
            rejects { mapPin().copy(customPlaceName = bad) }
            rejects { mapPinWithProvider().copy(providerPlaceId = bad) }
            rejects { mapPinWithProvider().copy(provider = bad) }
            rejects { mapPin().copy(boundaryDatasetVersion = bad) }
        }
    }
    @Test fun localUnitTypeIsRequired() { rejects { mapPin().copy(localUnitType = null) } }
    @Test fun unsupportedLocalUnitTypeIsRejectedOnDecode() { rejects { AdminLocalUnitType.valueOf("LOCAL_GOVERNMENT") } }
    @Test fun localUnitTypeMustAgreeWithRegion() {
        rejects { locality().copy(localUnitType = AdminLocalUnitType.AREA_COUNCIL) }
        rejects { areaCouncil().copy(localUnitType = AdminLocalUnitType.LGA) }
    }
    @Test fun canonicalIdentityMustBeMeaningfulAndConsistent() {
        rejects { mapPin().copy(stateId = " ") }; rejects { mapPin().copy(stateName = " ") }
        rejects { mapPin().copy(localUnitId = " ") }; rejects { mapPin().copy(localUnitName = " ") }
        rejects { mapPin().copy(localUnitId = "ng-lagos-ikeja") }
        rejects { mapPin().copy(countryCode = "GH") }
        rejects { mapPin().copy(adminDatasetVersion = null) }
    }
    @Test fun attributionIsMinimizedValidatedAndRedacted() {
        assertEquals("DestinationProviderAttribution(redacted)", attribution.toString())
        rejects { attribution.copy(sourceName = " ") }; rejects { attribution.copy(attribution = " ") }
        rejects { attribution.copy(license = " ") }; rejects { attribution.copy(sourceUrl = "http://osm.org") }
        rejects { attribution.copy(sourceUrl = "https://user:password@osm.org") }
        rejects { attribution.copy(sourceUrl = "invalid URL") }
    }
    @Test fun optionalCatalogueValidationRejectsForgedNamesAndVersions() {
        val file = listOf(java.io.File("src/main/assets/catalogue/ng_admin_v2.json"),
            java.io.File("app/src/main/assets/catalogue/ng_admin_v2.json")).first { it.isFile }
        val catalogue = AdminCatalogue.parse(file.readBytes())
        StructuredDestinationValidator.validate(mapPin(), catalogue)
        StructuredDestinationValidator.validate(areaCouncil(), catalogue)
        rejects { StructuredDestinationValidator.validate(mapPin().copy(stateName = "Lagos"), catalogue) }
        rejects { StructuredDestinationValidator.validate(mapPin().copy(localUnitName = "Other"), catalogue) }
        rejects { StructuredDestinationValidator.validate(mapPin().copy(adminDatasetVersion = "unknown"), catalogue) }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid destination evidence must fail closed") }
        catch (_: IllegalArgumentException) { }
    }
}
