package com.journeycontinuity.app.auth

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TravellerIdentityCoordinatorTest {
    @Test
    fun freshInstallationCreatesExactlyOneAnonymousIdentityAndPersistsIt() = runBlocking {
        val backend = FakeBackend(
            state = TravellerSessionState.NotAuthenticated,
            signInResult = TravellerSessionState.Authenticated("first-user"),
        )
        val store = FakeIdentityStore()

        val outcome = coordinator(backend, store).resolve()

        assertAuthenticated(outcome, "first-user")
        assertEquals(1, backend.signInCount)
        assertEquals(1, store.persistCount)
        assertEquals("first-user", store.expectedTravellerUserId())
    }

    @Test
    fun concurrentSyncHeartbeatAndContactsCallsCreateOnlyOneAnonymousIdentity() = runBlocking {
        val backend = FakeBackend(
            state = TravellerSessionState.NotAuthenticated,
            signInResult = TravellerSessionState.Authenticated("first-user"),
            signInDelayMillis = 25,
        )
        val store = FakeIdentityStore()
        val coordinator = coordinator(backend, store)

        val outcomes = coroutineScope {
            List(3) { async { coordinator.resolve() } }.awaitAll()
        }

        outcomes.forEach { assertAuthenticated(it, "first-user") }
        assertEquals(1, backend.signInCount)
        assertEquals(1, store.persistCount)
    }

    @Test
    fun existingIdentityWithMatchingAuthenticatedSessionSucceeds() = runBlocking {
        val backend = FakeBackend(TravellerSessionState.Authenticated("expected"))
        val store = FakeIdentityStore("expected")

        assertAuthenticated(coordinator(backend, store).resolve(), "expected")
        assertEquals(0, backend.signInCount)
        assertEquals(0, store.persistCount)
    }

    @Test
    fun preexistingAuthenticatedSessionWithoutIdentityGuardRequiresRecovery() = runBlocking {
        val backend = FakeBackend(TravellerSessionState.Authenticated("untrusted-existing-user"))
        val store = FakeIdentityStore()

        assertEquals(
            TravellerAuthOutcome.TravellerIdentityRecoveryRequired,
            coordinator(backend, store).resolve(),
        )
        assertEquals(null, store.expectedTravellerUserId())
        assertEquals(0, store.persistCount)
        assertEquals(0, backend.signInCount)
    }

    @Test
    fun refreshFailurePreservesExistingIdentityAndNeverSignsInAnonymously() = runBlocking {
        val backend = FakeBackend(TravellerSessionState.RefreshFailure)
        val store = FakeIdentityStore("expected")

        assertEquals(TravellerAuthOutcome.TemporaryAuthUnavailable, coordinator(backend, store).resolve())
        assertEquals(0, backend.signInCount)
        assertEquals("expected", store.expectedTravellerUserId())
    }

    @Test
    fun initializingExistingSessionIsTemporarilyUnavailableWithoutAnonymousSignIn() = runBlocking {
        val backend = FakeBackend(TravellerSessionState.Initializing)
        val store = FakeIdentityStore("expected")

        assertEquals(TravellerAuthOutcome.TemporaryAuthUnavailable, coordinator(backend, store).resolve())
        assertEquals(0, backend.signInCount)
    }

    @Test
    fun offlineExpiredSessionIsTemporarilyUnavailableWithoutAnonymousSignIn() = runBlocking {
        val backend = FakeBackend(
            TravellerSessionState.Authenticated(
                userId = "expected",
                accessTokenExpired = true,
            ),
        )
        val store = FakeIdentityStore("expected")
        val coordinator = coordinator(backend, store)

        repeat(4) {
            assertEquals(TravellerAuthOutcome.TemporaryAuthUnavailable, coordinator.resolve())
        }

        assertEquals(0, backend.signInCount)
        assertEquals("expected", store.expectedTravellerUserId())
    }

    @Test
    fun laterSuccessfulRefreshResumesTheSameIdentity() = runBlocking {
        val backend = FakeBackend(TravellerSessionState.RefreshFailure)
        val store = FakeIdentityStore("expected")
        val coordinator = coordinator(backend, store)

        assertEquals(TravellerAuthOutcome.TemporaryAuthUnavailable, coordinator.resolve())
        backend.state = TravellerSessionState.Authenticated("expected")

        assertAuthenticated(coordinator.resolve(), "expected")
        assertEquals(0, backend.signInCount)
        assertEquals("expected", store.expectedTravellerUserId())
    }

    @Test
    fun prolongedOfflineAcrossTokenExpiryRestoresOnlyTheExpectedTraveller() = runBlocking {
        val backend = FakeBackend(
            TravellerSessionState.Authenticated("expected", accessTokenExpired = true),
        )
        val store = FakeIdentityStore("expected")
        val coordinator = coordinator(backend, store)

        assertEquals(TravellerAuthOutcome.TemporaryAuthUnavailable, coordinator.resolve())
        backend.state = TravellerSessionState.RefreshFailure
        assertEquals(TravellerAuthOutcome.TemporaryAuthUnavailable, coordinator.resolve())
        backend.state = TravellerSessionState.Authenticated("expected")
        assertAuthenticated(coordinator.resolve(), "expected")

        assertEquals(0, backend.signInCount)
        assertEquals(0, store.persistCount)
        assertEquals("expected", store.expectedTravellerUserId())
    }

    @Test
    fun differentAuthenticatedUserFailsClosedWithoutOverwritingExpectedIdentity() = runBlocking {
        val backend = FakeBackend(TravellerSessionState.Authenticated("different"))
        val store = FakeIdentityStore("expected")

        assertEquals(TravellerAuthOutcome.TravellerIdentityMismatch, coordinator(backend, store).resolve())
        assertEquals("expected", store.expectedTravellerUserId())
        assertEquals(0, store.persistCount)
        assertEquals(0, backend.signInCount)
    }

    @Test
    fun mismatchedSessionPreservesIdentityUntilCorrectOwnerSessionReturns() = runBlocking {
        val backend = FakeBackend(TravellerSessionState.Authenticated("non-owner"))
        val store = FakeIdentityStore("owner")
        val coordinator = coordinator(backend, store)

        assertEquals(TravellerAuthOutcome.TravellerIdentityMismatch, coordinator.resolve())
        assertEquals("owner", store.expectedTravellerUserId())
        assertEquals(0, store.persistCount)

        backend.state = TravellerSessionState.Authenticated("owner")
        assertAuthenticated(coordinator.resolve(), "owner")
        assertEquals("owner", store.expectedTravellerUserId())
        assertEquals(0, store.persistCount)
        assertEquals(0, backend.signInCount)
    }

    @Test
    fun missingSessionForEstablishedIdentityRequiresRecoveryWithoutAnonymousSignIn() = runBlocking {
        val backend = FakeBackend(TravellerSessionState.NotAuthenticated)
        val store = FakeIdentityStore("expected")

        assertEquals(
            TravellerAuthOutcome.TravellerIdentityRecoveryRequired,
            coordinator(backend, store).resolve(),
        )
        assertEquals(0, backend.signInCount)
        assertEquals("expected", store.expectedTravellerUserId())
    }

    @Test
    fun initializationNetworkFailureIsTemporaryAndDoesNotCreateAnIdentity() = runBlocking {
        val backend = FakeBackend(
            state = TravellerSessionState.NotAuthenticated,
            initializationFailure = IOException("offline"),
        )
        val store = FakeIdentityStore()

        assertEquals(TravellerAuthOutcome.TemporaryAuthUnavailable, coordinator(backend, store).resolve())
        assertEquals(0, backend.signInCount)
        assertEquals(null, store.expectedTravellerUserId())
    }

    private fun coordinator(backend: FakeBackend, store: FakeIdentityStore) =
        TravellerIdentityCoordinator(backend, store)

    private fun assertAuthenticated(outcome: TravellerAuthOutcome, expectedUserId: String) {
        assertTrue(outcome is TravellerAuthOutcome.Authenticated)
        assertEquals(
            expectedUserId,
            (outcome as TravellerAuthOutcome.Authenticated).traveller.userId,
        )
    }

    private class FakeBackend(
        var state: TravellerSessionState,
        private val signInResult: TravellerSessionState = state,
        private val signInDelayMillis: Long = 0,
        private val initializationFailure: Throwable? = null,
    ) : TravellerAuthBackend {
        var signInCount = 0

        override suspend fun awaitInitialization() {
            initializationFailure?.let { throw it }
        }

        override fun sessionState() = state

        override suspend fun signInAnonymously() {
            signInCount++
            if (signInDelayMillis > 0) delay(signInDelayMillis)
            state = signInResult
        }
    }

    private class FakeIdentityStore(initialUserId: String? = null) : TravellerIdentityStore {
        private var expectedUserId = initialUserId
        var persistCount = 0

        override fun expectedTravellerUserId() = expectedUserId

        override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean {
            if (expectedUserId != null) return expectedUserId == userId
            persistCount++
            expectedUserId = userId
            return true
        }
    }
}
