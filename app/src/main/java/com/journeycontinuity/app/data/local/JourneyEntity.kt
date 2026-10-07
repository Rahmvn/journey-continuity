package com.journeycontinuity.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.domain.DestinationKind
import com.journeycontinuity.app.domain.DestinationSelectionSource
import com.journeycontinuity.app.domain.StructuredDestination
import com.journeycontinuity.app.domain.AdminLocalUnitType
import com.journeycontinuity.app.domain.DestinationProviderAttribution
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "journeys",
    indices = [Index(value = ["activeSlot"], unique = true)],
)
data class JourneyEntity(
    @PrimaryKey val id: String,
    val destination: String,
    val expectedArrivalAt: Long,
    val startedAt: Long,
    val status: JourneyStatus,
    val completedAt: Long?,
    // SQLite permits many NULLs in a unique index, but only one ACTIVE row can hold 1.
    val activeSlot: Int?,
    val endedAt: Long? = null,
    val destinationCountryCode: String? = null,
    val destinationStateId: String? = null,
    val destinationStateName: String? = null,
    val destinationLgaId: String? = null,
    val destinationLgaName: String? = null,
    val destinationKind: String? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    val destinationSelectionSource: String? = null,
    val destinationProvider: String? = null,
    val destinationProviderPlaceId: String? = null,
    val destinationAdminDatasetVersion: String? = null,
    val destinationCustomPlaceName: String? = null,
    val destinationLocalUnitType: String? = null,
    val destinationBoundaryDatasetId: String? = null,
    val destinationBoundaryDatasetVersion: String? = null,
    val destinationProviderAttribution: String? = null,
) {
    override fun toString(): String = "JourneyEntity(id=$id, status=$status, destinationFacts=redacted)"
}

private fun JourneyEntity.toStructuredDestination(): StructuredDestination? {
    if (destinationKind == null) {
        check(listOf(destinationCountryCode, destinationStateId, destinationStateName,
            destinationLgaId, destinationLgaName, destinationSelectionSource, destinationProvider,
            destinationProviderPlaceId, destinationAdminDatasetVersion,
            destinationCustomPlaceName, destinationLocalUnitType, destinationBoundaryDatasetId,
            destinationBoundaryDatasetVersion, destinationProviderAttribution).all { it == null } &&
            destinationLatitude == null && destinationLongitude == null) {
            "A text-only Journey cannot contain partial destination facts."
        }
        return null
    }
    return StructuredDestination(
        countryCode = requireNotNull(destinationCountryCode),
        stateId = requireNotNull(destinationStateId),
        stateName = requireNotNull(destinationStateName),
        localUnitId = requireNotNull(destinationLgaId),
        localUnitName = requireNotNull(destinationLgaName),
        kind = DestinationKind.valueOf(destinationKind),
        latitude = destinationLatitude,
        longitude = destinationLongitude,
        selectionSource = DestinationSelectionSource.valueOf(requireNotNull(destinationSelectionSource)),
        provider = destinationProvider,
        providerPlaceId = destinationProviderPlaceId,
        adminDatasetVersion = destinationAdminDatasetVersion,
        customPlaceName = destinationCustomPlaceName,
        localUnitType = AdminLocalUnitType.valueOf(requireNotNull(destinationLocalUnitType)),
        boundaryDatasetId = destinationBoundaryDatasetId,
        boundaryDatasetVersion = destinationBoundaryDatasetVersion,
        providerAttribution = destinationProviderAttribution?.let {
            try { Json.decodeFromString<DestinationProviderAttribution>(it) }
            catch (_: Exception) { throw IllegalArgumentException("Invalid persisted destination attribution.") }
        },
    )
}

fun JourneyEntity.toDomain() = Journey(
    id = id,
    destination = destination,
    expectedArrivalAt = expectedArrivalAt,
    startedAt = startedAt,
    status = status,
    completedAt = completedAt,
    endedAt = endedAt,
    structuredDestination = toStructuredDestination(),
)

fun Journey.toEntity(): JourneyEntity {
    val structured = structuredDestination
    return JourneyEntity(
        id = id,
        destination = destination,
        expectedArrivalAt = expectedArrivalAt,
        startedAt = startedAt,
        status = status,
        completedAt = completedAt,
        activeSlot = if (status == JourneyStatus.ACTIVE) 1 else null,
        endedAt = endedAt,
        destinationCountryCode = structured?.countryCode,
        destinationStateId = structured?.stateId,
        destinationStateName = structured?.stateName,
        destinationLgaId = structured?.localUnitId,
        destinationLgaName = structured?.localUnitName,
        destinationKind = structured?.kind?.name,
        destinationLatitude = structured?.latitude,
        destinationLongitude = structured?.longitude,
        destinationSelectionSource = structured?.selectionSource?.name,
        destinationProvider = structured?.provider,
        destinationProviderPlaceId = structured?.providerPlaceId,
        destinationAdminDatasetVersion = structured?.adminDatasetVersion,
        destinationCustomPlaceName = structured?.customPlaceName,
        destinationLocalUnitType = structured?.localUnitType?.name,
        destinationBoundaryDatasetId = structured?.boundaryDatasetId,
        destinationBoundaryDatasetVersion = structured?.boundaryDatasetVersion,
        destinationProviderAttribution = structured?.providerAttribution?.let { Json.encodeToString(it) },
    )
}
