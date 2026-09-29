package com.journeycontinuity.app.auth

import java.util.UUID
import kotlinx.coroutines.CancellationException

/** These are observations only. Reading them must never establish or replace an owner. */
data class OwnerAdoptionSnapshot(
    val persistedOwnerId: String?,
    val ownerMarkerPresent: Boolean,
    val primarySessionState: TravellerSessionState,
    val roomOwnerStatePresent: Boolean,
    val priorInstallationArtifactsPresent: Boolean,
    val fallbackKeyMaterialPresent: Boolean,
    val pendingFallbackWorkPresent: Boolean,
    val foregroundMonitoringActive: Boolean,
)

fun interface OwnerAdoptionSnapshotReader {
    suspend fun read(): OwnerAdoptionSnapshot
}

enum class OwnerAdoptionDecision {
    FIRST_OWNER_ELIGIBLE,
    SAME_OWNER_RECOVERY,
    DIFFERENT_OWNER_REJECTED,
    TRAVELLER_PROFILE_REQUIRED,
    LOCAL_OWNER_STATE_PRESENT,
    OWNER_STATE_UNCERTAIN,
}

class FirstOwnerAdoptionBoundary(private val reader: OwnerAdoptionSnapshotReader) {
    /**
     * [candidateHasTravellerProfile] must come from get_my_traveller_identity_v1() on
     * the candidate's verified session in the later login slice. This check does
     * not accept a client-supplied owner ID as authority or import any session.
     */
    suspend fun assess(candidateUserId: String?, candidateHasTravellerProfile: Boolean): OwnerAdoptionDecision {
        val snapshot = try {
            reader.read()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN
        }
        return decide(snapshot, candidateUserId, candidateHasTravellerProfile)
    }

    companion object {
        fun decide(
            snapshot: OwnerAdoptionSnapshot,
            candidateUserId: String?,
            candidateHasTravellerProfile: Boolean,
        ): OwnerAdoptionDecision {
            if (!isCanonicalAuthId(candidateUserId)) return OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN
            val ownerId = snapshot.persistedOwnerId
            if (!snapshot.ownerMarkerPresent && ownerId != null) {
                return OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN
            }
            if (snapshot.ownerMarkerPresent && ownerId.isNullOrBlank()) {
                return OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN
            }
            if (ownerId != null) {
                if (!isCanonicalAuthId(ownerId)) return OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN
                if (ownerId != candidateUserId) return OwnerAdoptionDecision.DIFFERENT_OWNER_REJECTED
                return when (val session = snapshot.primarySessionState) {
                    TravellerSessionState.NotAuthenticated -> OwnerAdoptionDecision.SAME_OWNER_RECOVERY
                    is TravellerSessionState.Authenticated ->
                        if (!session.accessTokenExpired && session.userId == ownerId)
                            OwnerAdoptionDecision.SAME_OWNER_RECOVERY
                        else OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN
                    TravellerSessionState.Initializing,
                    TravellerSessionState.RefreshFailure -> OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN
                }
            }
            if (snapshot.primarySessionState != TravellerSessionState.NotAuthenticated) {
                return OwnerAdoptionDecision.OWNER_STATE_UNCERTAIN
            }
            if (snapshot.roomOwnerStatePresent || snapshot.priorInstallationArtifactsPresent ||
                snapshot.fallbackKeyMaterialPresent || snapshot.pendingFallbackWorkPresent ||
                snapshot.foregroundMonitoringActive
            ) return OwnerAdoptionDecision.LOCAL_OWNER_STATE_PRESENT
            if (!candidateHasTravellerProfile) return OwnerAdoptionDecision.TRAVELLER_PROFILE_REQUIRED
            return OwnerAdoptionDecision.FIRST_OWNER_ELIGIBLE
        }

        private fun isCanonicalAuthId(value: String?): Boolean = value != null &&
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
