package com.journeycontinuity.app.domain

object DestinationEvidenceFixtures {
    val attribution = DestinationProviderAttribution("OpenStreetMap", "© OpenStreetMap contributors",
        "ODbL", "https://www.openstreetmap.org/copyright")
    fun mapPin() = StructuredDestination("NG", "ng-kwara", "Kwara", "ng-kwara-offa", "Offa",
        DestinationKind.POINT, 8.1560008, 4.7227435, DestinationSelectionSource.MAP_PIN,
        adminDatasetVersion = "alabarin-2026-10-05-v2", localUnitType = AdminLocalUnitType.LGA,
        boundaryDatasetId = DESTINATION_BOUNDARY_DATASET, boundaryDatasetVersion = "alabarin-2026-10-03-v1")
    fun mapPinWithProvider(placeId: String? = null) = mapPin().copy(provider = "GEOAPIFY",
        providerPlaceId = placeId, providerAttribution = attribution)
    fun placeSearch() = mapPinWithProvider("provider-place-1").copy(
        selectionSource = DestinationSelectionSource.PLACE_SEARCH)
    fun locality() = StructuredDestination("NG", "ng-kwara", "Kwara", "ng-kwara-offa", "Offa",
        DestinationKind.LOCALITY, null, null, DestinationSelectionSource.LOCALITY_SELECTION,
        adminDatasetVersion = "alabarin-2026-10-05-v2", localUnitType = AdminLocalUnitType.LGA)
    fun areaCouncil() = locality().copy(stateId = "ng-fct", stateName = "Federal Capital Territory",
        localUnitId = "ng-fct-abuja-municipal", localUnitName = "Abuja Municipal Area Council",
        localUnitType = AdminLocalUnitType.AREA_COUNCIL)
}
