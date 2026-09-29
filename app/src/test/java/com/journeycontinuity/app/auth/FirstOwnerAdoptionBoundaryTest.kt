package com.journeycontinuity.app.auth

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstOwnerAdoptionBoundaryTest {
    private val ownerA = "10000000-0000-4000-8000-0000000000a1"
    private val ownerB = "10000000-0000-4000-8000-0000000000b2"
    private val clean = OwnerAdoptionSnapshot(
        persistedOwnerId = null,
        ownerMarkerPresent = false,
        primarySessionState = TravellerSessionState.NotAuthenticated,
        roomOwnerStatePresent = false,
        priorInstallationArtifactsPresent = false,
        fallbackKeyMaterialPresent = false,
        pendingFallbackWorkPresent = false,
        foregroundMonitoringActive = false,
    )

    private fun decide(
        snapshot: OwnerAdoptionSnapshot,
        candidate: String = ownerA,
        hasProfile: Boolean = true,
    ) = FirstOwnerAdoptionBoundary.decide(snapshot, candidate, hasProfile)

    @Test fun untouchedInstallationMayAdoptVerifiedTraveller() {
        assertEquals(OwnerAdoptionDecision.FIRST_OWNER_ELIGIBLE, decide(clean))
    }

    @Test fun persistedOwnerAllowsOnlySameIdRecovery() {
        val bound = clean.copy(persistedOwnerId = ownerA, ownerMarkerPresent = true)
        assertEquals(OwnerAdoptionDecision.SAME_OWNER_RECOVERY, decide(bound, ownerA))
        assertEquals(OwnerAdoptionDecision.DIFFERENT_OWNER_REJECTED, decide(bound, ownerB))
        // Same-ID recovery includes an anonymous owner before profile creation.
        assertEquals(OwnerAdoptionDecision.SAME_OWNER_RECOVERY,
            decide(bound, ownerA, hasProfile = false))
        assertEquals(OwnerAdoptionDecision.SAME_OWNER_RECOVERY,
            decide(bound.copy(primarySessionState = TravellerSessionState.Authenticated(ownerA)),
                ownerA))
        assertEquals(OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN,
            decide(bound.copy(primarySessionState = TravellerSessionState.Authenticated(ownerB)),
                ownerA))
    }

    @Test fun anyRoomJourneyOrEvidenceStateBlocksFirstOwnerAdoption() {
        assertEquals(OwnerAdoptionDecision.LOCAL_OWNER_STATE_PRESENT,
            decide(clean.copy(roomOwnerStatePresent = true)))
        // The single Room read covers completed/active Journeys and orphaned
        // telemetry, sync, heartbeat, degradation, binding and attempt rows.
    }

    @Test fun fallbackAndPriorInstallationArtifactsBlockAdoption() {
        assertEquals(OwnerAdoptionDecision.LOCAL_OWNER_STATE_PRESENT,
            decide(clean.copy(priorInstallationArtifactsPresent = true)))
        assertEquals(OwnerAdoptionDecision.LOCAL_OWNER_STATE_PRESENT,
            decide(clean.copy(fallbackKeyMaterialPresent = true)))
        assertEquals(OwnerAdoptionDecision.LOCAL_OWNER_STATE_PRESENT,
            decide(clean.copy(pendingFallbackWorkPresent = true)))
    }

    @Test fun foregroundMonitoringBlocksAdoption() {
        assertEquals(OwnerAdoptionDecision.LOCAL_OWNER_STATE_PRESENT,
            decide(clean.copy(foregroundMonitoringActive = true)))
    }

    @Test fun unsettledOrRestoredOwnerStateRequiresRecovery() {
        assertEquals(OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN,
            decide(clean.copy(primarySessionState = TravellerSessionState.Initializing)))
        assertEquals(OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN,
            decide(clean.copy(ownerMarkerPresent = true)))
        assertEquals(OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN,
            decide(clean.copy(persistedOwnerId = "corrupt-restored-id", ownerMarkerPresent = true)))
        assertEquals(OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN,
            decide(clean, candidate = ""))
    }

    @Test fun genericStartupSchedulerDoesNotMakeCleanSnapshotDirty() {
        // The reader deliberately ignores generic JourneySyncWorker work when
        // the authoritative owner-scoped Room tables are empty.
        assertEquals(OwnerAdoptionDecision.FIRST_OWNER_ELIGIBLE, decide(clean))
    }

    @Test fun verifiedAccountWithoutTravellerProfileIncludingViewerOnlyIsRejected() {
        assertEquals(OwnerAdoptionDecision.TRAVELLER_PROFILE_REQUIRED,
            decide(clean, hasProfile = false))
    }

    @Test fun snapshotReadFailureFailsClosed() = runBlocking {
        val boundary = FirstOwnerAdoptionBoundary { throw IOException("restored state unreadable") }
        assertEquals(OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN,
            boundary.assess(ownerA, true))
    }
}
