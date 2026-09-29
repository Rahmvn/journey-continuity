package com.journeycontinuity.app.auth

import android.content.Context
import androidx.work.WorkManager
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.degraded.FallbackHandoffWorker
import com.journeycontinuity.app.service.JourneyForegroundService
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only admission snapshot for a later memory-only returning-login flow. */
class AndroidOwnerAdoptionSnapshotReader(
    context: Context,
    private val database: JourneyDatabase,
    private val primaryAuth: TravellerAuthBackend,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : OwnerAdoptionSnapshotReader {
    private val appContext = context.applicationContext

    override suspend fun read(): OwnerAdoptionSnapshot = withContext(Dispatchers.IO) {
        primaryAuth.awaitInitialization()
        val ownerPreferences = preferences("journey-continuity-identity")
        val ownerMarkerPresent = ownerPreferences.contains("expectedTravellerUserId")
        val ownerId = if (ownerMarkerPresent) {
            ownerPreferences.getString("expectedTravellerUserId", null)
        } else null

        // Supabase-kt 3.8.0 uses multiplatform-settings' Android default
        // SharedPreferences file. A saved session or code verifier here means
        // this installation is not demonstrably untouched, even if Auth now
        // reports NotAuthenticated.
        val priorArtifacts = listOf(
            "journey-continuity-installation",
            "journey-continuity-sms-fallback",
            "${appContext.packageName}_preferences",
        ).any { preferences(it).all.isNotEmpty() } ||
            ownerPreferences.all.keys.any { it != "expectedTravellerUserId" }

        val wrappedKeysPresent = preferences("journey_fallback_wrapped_keys").all.isNotEmpty()
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyMaterialPresent = wrappedKeysPresent ||
            keystore.containsAlias("journey_continuity_fallback_wrapping_v1")

        // WorkManager adds the Worker class name as a tag to every request.
        // Generic JourneySyncWorker startup work is harmless when Room is empty.
        // Any fallback worker record is Journey-specific, even if its Room row
        // was lost in a partial restore.
        val fallbackWorkPresent = workManager
            .getWorkInfosByTag(FallbackHandoffWorker::class.java.name).get().isNotEmpty()
        val roomStatePresent = database.ownerAdoptionStateDao().hasAnyOwnerScopedRows()
        OwnerAdoptionSnapshot(
            persistedOwnerId = ownerId,
            ownerMarkerPresent = ownerMarkerPresent,
            primarySessionState = primaryAuth.sessionState(),
            roomOwnerStatePresent = roomStatePresent,
            priorInstallationArtifactsPresent = priorArtifacts,
            fallbackKeyMaterialPresent = keyMaterialPresent,
            pendingFallbackWorkPresent = fallbackWorkPresent,
            foregroundMonitoringActive = JourneyForegroundService.isMonitoringInThisProcess,
        )
    }

    private fun preferences(name: String) = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
}
