package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.auth.InstallationIdentityStore
import com.journeycontinuity.app.data.local.JourneyFallbackBindingEntity
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackProvisioningCoordinatorTest {
    @Test
    fun successfulProvisioningStoresKeyBeforeBindingAndClearsTransientBytes() = runBlocking {
        val events = mutableListOf<String>()
        val local = FakeLocalStore(events = events)
        val keys = FakeKeyStore(events = events)
        val gateway = FakeGateway()

        assertEquals(FallbackProvisioningResult.Provisioned, coordinator(local, keys, gateway).provisionIfEligible(JOURNEY))

        assertEquals(listOf("key", "binding"), events)
        assertTrue(hasUsableFallbackBinding(local.saved, keys))
        assertTrue(gateway.lastReturnedKey!!.all { it == 0.toByte() })
    }

    @Test
    fun processRestartRetainsCapabilityWithoutAnotherRequest() = runBlocking {
        val local = FakeLocalStore()
        val keys = FakeKeyStore()
        val firstGateway = FakeGateway()
        coordinator(local, keys, firstGateway).provisionIfEligible(JOURNEY)

        val restartedGateway = FakeGateway()
        assertEquals(
            FallbackProvisioningResult.AlreadyProvisioned,
            coordinator(local, keys, restartedGateway).provisionIfEligible(JOURNEY),
        )
        assertEquals(0, restartedGateway.calls)
    }

    @Test
    fun malformedKeyHandleAndKeyIdAreRejectedWithoutBinding() = runBlocking {
        val invalid = listOf(
            FakeGateway(keyId = -1),
            FakeGateway(keySize = 31),
            FakeGateway(handleSize = 11),
            FakeGateway(keyId = 0x1_0000_0000L),
        )
        invalid.forEach { gateway ->
            val local = FakeLocalStore()
            assertEquals(
                FallbackProvisioningResult.Unavailable,
                coordinator(local, FakeKeyStore(), gateway).provisionIfEligible(JOURNEY),
            )
            assertNull(local.saved)
        }
    }

    @Test
    fun keyStorageFailurePreventsRoomBinding() = runBlocking {
        val local = FakeLocalStore()
        val keys = FakeKeyStore(failProvision = true)
        assertEquals(
            FallbackProvisioningResult.Unavailable,
            coordinator(local, keys, FakeGateway()).provisionIfEligible(JOURNEY),
        )
        assertNull(local.saved)
    }

    @Test
    fun existingBindingWithoutKeyAndKeyWithoutBindingRemainUnavailable() {
        val keys = FakeKeyStore()
        val binding = binding()
        assertFalse(hasUsableFallbackBinding(binding, keys))
        keys.provision(KEY_ID, ByteArray(32) { 7 })
        assertFalse(hasUsableFallbackBinding(null, keys))
        assertTrue(hasUsableFallbackBinding(binding, keys))
    }

    @Test
    fun retryConvergesToSameBindingAndDoesNotRewriteIt() = runBlocking {
        val local = FakeLocalStore(failFirstPersist = true)
        val keys = FakeKeyStore()
        val gateway = FakeGateway()
        val coordinator = coordinator(local, keys, gateway)

        assertEquals(FallbackProvisioningResult.Unavailable, coordinator.provisionIfEligible(JOURNEY))
        assertEquals(FallbackProvisioningResult.Provisioned, coordinator.provisionIfEligible(JOURNEY))
        assertEquals(2, gateway.calls)
        assertEquals(KEY_ID, local.saved!!.keyId)
        assertArrayEquals(ByteArray(12) { 9 }, local.saved!!.journeyHandle)
    }

    @Test
    fun completedJourneyNeverRequestsProvisioning() = runBlocking {
        val gateway = FakeGateway()
        assertEquals(
            FallbackProvisioningResult.Ineligible,
            coordinator(FakeLocalStore(active = false), FakeKeyStore(), gateway).provisionIfEligible(JOURNEY),
        )
        assertEquals(0, gateway.calls)
    }

    @Test
    fun nonOwnerFailureCannotReplaceExistingFallbackBinding() = runBlocking {
        val existing = binding()
        val local = FakeLocalStore(existingBinding = existing)
        val keys = FakeKeyStore()
        val gateway = FakeGateway(failure = IllegalStateException("authorization denied"))

        assertEquals(
            FallbackProvisioningResult.Unavailable,
            coordinator(local, keys, gateway).provisionIfEligible(JOURNEY),
        )
        assertEquals(existing, local.saved)
        assertFalse(keys.hasKey(KEY_ID))
        assertEquals(1, gateway.calls)
    }

    private fun coordinator(
        local: FakeLocalStore,
        keys: FakeKeyStore,
        gateway: FakeGateway,
    ) = FallbackProvisioningCoordinator(
        localStore = local,
        installationIdentityStore = InstallationIdentityStore { INSTALLATION },
        keyMaterialStore = keys,
        gateway = gateway,
        clock = { 1234L },
    )

    private class FakeLocalStore(
        private val active: Boolean = true,
        private val events: MutableList<String> = mutableListOf(),
        private var failFirstPersist: Boolean = false,
        existingBinding: JourneyFallbackBindingEntity? = null,
    ) : FallbackProvisioningLocalStore {
        var saved: JourneyFallbackBindingEntity? = existingBinding
        override suspend fun activeJourneyExists(journeyId: String) = active
        override suspend fun binding(journeyId: String) = saved
        override suspend fun persistBinding(binding: JourneyFallbackBindingEntity) {
            events += "binding"
            if (failFirstPersist) {
                failFirstPersist = false
                error("simulated Room failure")
            }
            saved?.let {
                check(it.keyId == binding.keyId && MessageDigest.isEqual(it.journeyHandle, binding.journeyHandle))
            } ?: run { saved = binding }
        }
    }

    private class FakeKeyStore(
        private val events: MutableList<String> = mutableListOf(),
        private val failProvision: Boolean = false,
    ) : FallbackKeyMaterialStore {
        private val values = mutableMapOf<Long, ByteArray>()
        override fun hasKey(keyId: Long) = values.containsKey(keyId)
        override fun <T> useKey(keyId: Long, block: (ByteArray) -> T): T? = values[keyId]?.copyOf()?.let(block)
        override fun provision(keyId: Long, installationFallbackKey: ByteArray) {
            events += "key"
            if (failProvision) error("simulated keystore failure")
            values[keyId]?.let { check(MessageDigest.isEqual(it, installationFallbackKey)) }
                ?: run { values[keyId] = installationFallbackKey.copyOf() }
        }
    }

    private class FakeGateway(
        private val keyId: Long = KEY_ID,
        private val keySize: Int = 32,
        private val handleSize: Int = 12,
        private val failure: Throwable? = null,
    ) : AuthenticatedFallbackProvisioningGateway {
        var calls = 0
        var lastReturnedKey: ByteArray? = null
        override suspend fun provision(journeyId: String, installationId: String): FallbackProvisioningMaterial {
            calls += 1
            failure?.let { throw it }
            return FallbackProvisioningMaterial(
                keyId = keyId,
                installationMasterKey = ByteArray(keySize) { 7 }.also { lastReturnedKey = it },
                journeyHandle = ByteArray(handleSize) { 9 },
                bindingStatus = "ACTIVE",
                bindingVersion = 1,
            )
        }
    }

    private fun binding() = JourneyFallbackBindingEntity(
        journeyId = JOURNEY,
        keyId = KEY_ID,
        journeyHandle = ByteArray(12) { 9 },
        status = FallbackBindingStatus.PROVISIONED,
        provisionedAt = 1234,
        revokedAt = null,
    )

    private companion object {
        const val JOURNEY = "a0000000-0000-4000-8000-000000000001"
        const val INSTALLATION = "11111111-1111-4111-8111-111111111111"
        const val KEY_ID = 42L
    }
}
