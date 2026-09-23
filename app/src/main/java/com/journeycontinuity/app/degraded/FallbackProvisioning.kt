package com.journeycontinuity.app.degraded

import androidx.room.withTransaction
import com.journeycontinuity.app.auth.InstallationIdentityStore
import com.journeycontinuity.app.data.local.FallbackAttemptDao
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.JourneyFallbackBindingEntity
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.sync.NoOpSyncDiagnosticLogger
import com.journeycontinuity.app.sync.SyncDiagnosticLogger
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface FallbackProvisioningLocalStore {
    suspend fun activeJourneyExists(journeyId: String): Boolean
    suspend fun binding(journeyId: String): JourneyFallbackBindingEntity?
    suspend fun persistBinding(binding: JourneyFallbackBindingEntity)
}

class RoomFallbackProvisioningLocalStore(
    private val database: JourneyDatabase,
    private val fallbackAttemptDao: FallbackAttemptDao,
) : FallbackProvisioningLocalStore {
    override suspend fun activeJourneyExists(journeyId: String): Boolean =
        database.journeyDao().getById(journeyId)?.status == JourneyStatus.ACTIVE

    override suspend fun binding(journeyId: String): JourneyFallbackBindingEntity? =
        fallbackAttemptDao.getBinding(journeyId)

    override suspend fun persistBinding(binding: JourneyFallbackBindingEntity) {
        database.withTransaction {
            val current = fallbackAttemptDao.getBinding(binding.journeyId)
            if (current == null) {
                fallbackAttemptDao.insertBinding(binding)
            } else {
                check(current.status == binding.status)
                check(current.keyId == binding.keyId)
                check(MessageDigest.isEqual(current.journeyHandle, binding.journeyHandle))
            }
        }
    }
}

data class FallbackProvisioningMaterial(
    val keyId: Long,
    val installationMasterKey: ByteArray,
    val journeyHandle: ByteArray,
    val bindingStatus: String,
    val bindingVersion: Int,
)

fun hasUsableFallbackBinding(
    binding: JourneyFallbackBindingEntity?,
    keyMaterialStore: FallbackKeyMaterialStore,
): Boolean = binding?.status == FallbackBindingStatus.PROVISIONED &&
    keyMaterialStore.hasKey(binding.keyId)

fun interface AuthenticatedFallbackProvisioningGateway {
    suspend fun provision(journeyId: String, installationId: String): FallbackProvisioningMaterial
}

sealed interface FallbackProvisioningResult {
    data object Provisioned : FallbackProvisioningResult
    data object AlreadyProvisioned : FallbackProvisioningResult
    data object Ineligible : FallbackProvisioningResult
    data object Unavailable : FallbackProvisioningResult
}

class FallbackProvisioningCoordinator(
    private val localStore: FallbackProvisioningLocalStore,
    private val installationIdentityStore: InstallationIdentityStore,
    private val keyMaterialStore: FallbackKeyMaterialStore,
    private val gateway: AuthenticatedFallbackProvisioningGateway,
    private val capabilityChanged: suspend (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: SyncDiagnosticLogger = NoOpSyncDiagnosticLogger,
) {
    private val mutex = Mutex()

    suspend fun provisionIfEligible(journeyId: String): FallbackProvisioningResult = mutex.withLock {
        if (!localStore.activeJourneyExists(journeyId)) return@withLock FallbackProvisioningResult.Ineligible
        val existing = localStore.binding(journeyId)
        if (existing?.status == FallbackBindingStatus.PROVISIONED && keyMaterialStore.hasKey(existing.keyId)) {
            return@withLock FallbackProvisioningResult.AlreadyProvisioned
        }

        val material = try {
            gateway.provision(journeyId, installationIdentityStore.getOrCreate())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.warning("Fallback provisioning is temporarily unavailable.")
            return@withLock FallbackProvisioningResult.Unavailable
        }
        try {
            validate(material)
            keyMaterialStore.provision(material.keyId, material.installationMasterKey)
            localStore.persistBinding(
                JourneyFallbackBindingEntity(
                    journeyId = journeyId,
                    keyId = material.keyId,
                    journeyHandle = material.journeyHandle.copyOf(),
                    status = FallbackBindingStatus.PROVISIONED,
                    provisionedAt = clock(),
                    revokedAt = null,
                ),
            )
            capabilityChanged(journeyId)
            FallbackProvisioningResult.Provisioned
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.warning("Fallback provisioning material could not be persisted safely.")
            FallbackProvisioningResult.Unavailable
        } finally {
            material.installationMasterKey.fill(0)
        }
    }

    private fun validate(material: FallbackProvisioningMaterial) {
        require(material.keyId in 0..UINT32_MAX)
        require(material.installationMasterKey.size == AesGcmEnvelopeProtector.KEY_BYTES)
        require(material.journeyHandle.size == FallbackEnvelopeV1.JOURNEY_HANDLE_BYTES)
        require(material.bindingStatus == "ACTIVE")
        require(material.bindingVersion > 0)
    }

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
    }
}
