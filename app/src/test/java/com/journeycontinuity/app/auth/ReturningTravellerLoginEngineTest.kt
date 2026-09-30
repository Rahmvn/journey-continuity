package com.journeycontinuity.app.auth

import io.github.jan.supabase.auth.user.UserSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturningTravellerLoginEngineTest {
    private val ownerA = "10000000-0000-4000-8000-0000000000a1"
    private val ownerB = "10000000-0000-4000-8000-0000000000b2"

    @Test fun boundOwnerCanRecoverSameIdWithoutChangingJourneyState() = runBlocking {
        val rig = Rig(ownerA, primaryId = null, candidateId = ownerA)
        rig.roomState = true // An active Journey and its evidence remain local.
        rig.telemetryRows = 4
        assertEquals(ReturningLoginState.CodeRequested, rig.engine.requestCode(" A@EXAMPLE.TEST "))
        assertEquals("a@example.test", rig.attempt.requestedEmail)
        assertEquals(ReturningLoginState.Admitted, rig.engine.submitCode("123456"))
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals(ownerA, rig.primary.id)
        assertEquals(4, rig.telemetryRows)
        assertEquals(0, rig.store.persistCount)
        assertEquals(1, rig.attempt.discardCount)
        assertEquals(ownerA, (rig.coordinator.resolve() as TravellerAuthOutcome.Authenticated).traveller.userId)
    }

    @Test fun alreadyAuthenticatedPrimaryARejectsTemporaryBWithoutDisturbingA() = runBlocking {
        val rig = Rig(ownerA, primaryId = ownerA, candidateId = ownerB)
        rig.roomState = true
        rig.telemetryRows = 7
        rig.engine.requestCode("b@example.test")
        assertEquals(ReturningLoginState.Rejected(ReturningLoginRejection.DIFFERENT_OWNER),
            rig.engine.submitCode("123456"))
        assertEquals(ownerA, rig.primary.id)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals(0, rig.primary.importCount)
        assertEquals(0, rig.primary.clearCount)
        assertEquals(7, rig.telemetryRows)
        assertEquals(1, rig.attempt.discardCount)
        assertEquals(ownerA, (rig.coordinator.resolve() as TravellerAuthOutcome.Authenticated).traveller.userId)
    }

    @Test fun cleanInstallAdoptsVerifiedTravellerAndSurvivesRestart() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.engine.requestCode("b@example.test")
        assertEquals(ReturningLoginState.Admitted, rig.engine.submitCode("123456"))
        assertEquals(ownerB, rig.store.expectedTravellerUserId())
        assertEquals(ownerB, rig.primary.id)
        assertEquals(1, rig.store.persistCount)
        assertEquals(1, rig.primary.importCount)
        assertEquals(1, rig.attempt.discardCount)
        val restarted = TravellerIdentityCoordinator(rig.backend, rig.store, admissionGate = rig.gate)
        assertEquals(ownerB, (restarted.resolve() as TravellerAuthOutcome.Authenticated).traveller.userId)
    }

    @Test fun localStateAppearingDuringCodeEntryBlocksFinalAdmission() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.engine.requestCode("b@example.test")
        rig.roomState = true
        assertEquals(ReturningLoginState.Rejected(ReturningLoginRejection.RECOVERY_REQUIRED),
            rig.engine.submitCode("123456"))
        assertNull(rig.store.expectedTravellerUserId())
        assertNull(rig.primary.id)
        assertEquals(0, rig.primary.importCount)
    }

    @Test fun viewerOnlyAndProfilelessAccountsCannotAdopt() = runBlocking {
        for (id in listOf(ownerA, ownerB)) {
            val rig = Rig(null, primaryId = null, candidateId = id)
            rig.attempt.hasProfile = false
            rig.engine.requestCode("viewer@example.test")
            assertEquals(ReturningLoginState.Rejected(ReturningLoginRejection.TRAVELLER_PROFILE_REQUIRED),
                rig.engine.submitCode("123456"))
            assertNull(rig.store.expectedTravellerUserId())
            assertEquals(0, rig.primary.importCount)
        }
    }

    @Test fun profileRpcFailureNeverImportsCandidate() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.attempt.profileFailure = true
        rig.engine.requestCode("b@example.test")
        assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.PROFILE_UNAVAILABLE),
            rig.engine.submitCode("123456"))
        assertEquals(1, rig.attempt.discardCount)
        assertNull(rig.store.expectedTravellerUserId())
        assertEquals(0, rig.primary.importCount)
    }

    @Test fun wrongExpiredAndReplayedCodeCannotImport() = runBlocking {
        for (failure in listOf("wrong", "expired", "replayed")) {
            val rig = Rig(null, primaryId = null, candidateId = ownerB)
            rig.attempt.verificationFailure = failure
            rig.engine.requestCode("b@example.test")
            assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.VERIFICATION_FAILED),
                rig.engine.submitCode("123456"))
            assertEquals(1, rig.attempt.discardCount)
            assertEquals(0, rig.primary.importCount)
        }
    }

    @Test fun absentAndExistingEmailsHaveTheSamePreVerificationState() = runBlocking {
        val existing = Rig(null, primaryId = null, candidateId = ownerB)
        val absent = Rig(null, primaryId = null, candidateId = ownerB)
        absent.attempt.requestResult = LoginCodeRequestResult.ACCOUNT_ABSENT

        assertEquals(ReturningLoginState.CodeRequested, existing.engine.requestCode("b@example.test"))
        assertEquals(existing.engine.state.value, absent.engine.requestCode("absent@example.test"))
        assertEquals(ReturningLoginState.CodeRequested, absent.engine.state.value)
        assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.VERIFICATION_FAILED),
            absent.engine.submitCode("123456"))
        assertEquals(0, absent.attempt.verificationCount)
        assertEquals(0, absent.primary.importCount)
        assertNull(absent.store.expectedTravellerUserId())
        assertEquals(1, absent.attempt.discardCount)
    }

    @Test fun absentEmailCannotReplacePersistedOwnerAndCancelDiscardsAttempt() = runBlocking {
        val rig = Rig(ownerA, primaryId = ownerA, candidateId = ownerB)
        rig.attempt.requestResult = LoginCodeRequestResult.ACCOUNT_ABSENT
        assertEquals(ReturningLoginState.CodeRequested, rig.engine.requestCode("absent@example.test"))
        rig.engine.cancel()
        assertEquals(ReturningLoginState.Idle, rig.engine.state.value)
        assertEquals(1, rig.attempt.discardCount)
        assertEquals(ReturningLoginState.CodeRequested, rig.engine.requestCode("absent@example.test"))
        assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.VERIFICATION_FAILED),
            rig.engine.submitCode("123456"))
        assertEquals(0, rig.attempt.verificationCount)
        assertEquals(0, rig.primary.importCount)
        assertEquals(ownerA, rig.primary.id)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
    }

    @Test fun genericRequestFailureRemainsFailureAndCannotImport() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.attempt.requestFailure = true
        assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.REQUEST_FAILED),
            rig.engine.requestCode("absent@example.test"))
        assertEquals(1, rig.attempt.discardCount)
        assertNull(rig.primary.id)
        assertNull(rig.store.expectedTravellerUserId())
    }

    @Test fun ownerPersistenceFailurePreventsPrimaryImport() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.store.failPersist = true
        rig.engine.requestCode("b@example.test")
        assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED),
            rig.engine.submitCode("123456"))
        assertNull(rig.primary.id)
        assertEquals(0, rig.primary.importCount)
    }

    @Test fun importFailureLeavesDurableOwnerForSameIdRestartRecovery() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.primary.failImport = true
        rig.engine.requestCode("b@example.test")
        assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED),
            rig.engine.submitCode("123456"))
        assertEquals(ownerB, rig.store.expectedTravellerUserId())
        assertNull(rig.primary.id)
        assertEquals(TravellerAuthOutcome.TravellerIdentityRecoveryRequired,
            TravellerIdentityCoordinator(rig.backend, rig.store, admissionGate = rig.gate).resolve())
        rig.primary.failImport = false
        val retry = Rig(rig.store, rig.primary, rig.gate, ownerB)
        retry.engine.requestCode("b@example.test")
        assertEquals(ReturningLoginState.Admitted, retry.engine.submitCode("123456"))
        assertEquals(ownerB, rig.primary.id)
    }

    @Test fun cancellingCallerDuringFirstOwnerImportCannotStrandPersistedOwner() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        val importStarted = CompletableDeferred<Unit>()
        val releaseImport = CompletableDeferred<Unit>()
        rig.primary.importStarted = importStarted
        rig.primary.releaseImport = releaseImport
        rig.engine.requestCode("b@example.test")
        val login = async { rig.engine.submitCode("123456") }
        importStarted.await()
        assertEquals(ownerB, rig.store.expectedTravellerUserId())
        login.cancel()
        releaseImport.complete(Unit)
        login.cancelAndJoin()
        assertEquals(ownerB, rig.primary.id)
        assertEquals(ownerB, rig.store.expectedTravellerUserId())
        assertEquals(ReturningLoginState.Admitted, rig.engine.state.value)
        assertEquals(1, rig.attempt.discardCount)
        assertEquals(ownerB, (rig.coordinator.resolve() as TravellerAuthOutcome.Authenticated).traveller.userId)
    }

    @Test fun partialWrongPrimaryImportIsLocallyClearedAndFailsClosed() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.primary.wrongImportId = ownerA
        rig.engine.requestCode("b@example.test")
        assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.ADMISSION_RECOVERY_REQUIRED),
            rig.engine.submitCode("123456"))
        assertEquals(ownerB, rig.store.expectedTravellerUserId())
        assertNull(rig.primary.id)
        assertEquals(1, rig.primary.clearCount)
    }

    @Test fun concurrentRequestsAreBoundedAndCancellationDiscardsOnlyTemporaryClient() = runBlocking {
        val rig = Rig(ownerA, primaryId = ownerA, candidateId = ownerA)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.attempt.requestStarted = started
        rig.attempt.releaseRequest = release
        val first = async { rig.engine.requestCode("a@example.test") }
        started.await()
        assertEquals(ReturningLoginState.Busy, rig.engine.requestCode("b@example.test"))
        assertEquals(ownerA, rig.primary.id)
        release.complete(Unit)
        assertEquals(ReturningLoginState.CodeRequested, first.await())
        assertEquals(ReturningLoginState.Busy, rig.engine.requestCode("b@example.test"))
        rig.engine.cancel()
        assertEquals(ReturningLoginState.Idle, rig.engine.state.value)
        assertEquals(1, rig.attempt.discardCount)
        assertEquals(ownerA, rig.primary.id)
    }

    @Test fun admissionWaitsForSharedGateAndSeesNewJourneyState() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.engine.requestCode("b@example.test")
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val competingJourneyStart = async {
            rig.gate.withLock {
                gateEntered.complete(Unit)
                releaseGate.await()
                rig.roomState = true
            }
        }
        gateEntered.await()
        val login = async { rig.engine.submitCode("123456") }
        releaseGate.complete(Unit)
        competingJourneyStart.await()
        assertEquals(ReturningLoginState.Rejected(ReturningLoginRejection.RECOVERY_REQUIRED),
            login.await())
        assertNull(rig.store.expectedTravellerUserId())
        assertEquals(0, rig.primary.importCount)
    }

    @Test fun cancelledInFlightRequestDiscardsTemporaryClient() = runBlocking {
        val rig = Rig(ownerA, primaryId = ownerA, candidateId = ownerA)
        val started = CompletableDeferred<Unit>()
        rig.attempt.requestStarted = started
        rig.attempt.releaseRequest = CompletableDeferred()
        val request = async { rig.engine.requestCode("a@example.test") }
        started.await()
        request.cancelAndJoin()
        assertEquals(ReturningLoginState.Idle, rig.engine.state.value)
        assertEquals(1, rig.attempt.discardCount)
        assertEquals(ownerA, rig.primary.id)
    }

    @Test fun cancellationDuringProfileLookupPreventsAdmission() = runBlocking {
        val rig = Rig(null, primaryId = null, candidateId = ownerB)
        rig.engine.requestCode("b@example.test")
        val profileStarted = CompletableDeferred<Unit>()
        val releaseProfile = CompletableDeferred<Unit>()
        rig.attempt.profileStarted = profileStarted
        rig.attempt.releaseProfile = releaseProfile
        val verification = async { rig.engine.submitCode("123456") }
        profileStarted.await()
        val cancelling = async(start = CoroutineStart.UNDISPATCHED) { rig.engine.cancel() }
        releaseProfile.complete(Unit)
        assertEquals(ReturningLoginState.Failed(ReturningLoginFailure.CANCELLED), verification.await())
        cancelling.await()
        assertEquals(ReturningLoginState.Idle, rig.engine.state.value)
        assertNull(rig.store.expectedTravellerUserId())
        assertEquals(0, rig.primary.importCount)
        assertEquals(1, rig.attempt.discardCount)
    }

    private inner class Rig(
        val store: FakeStore,
        val primary: FakePrimary,
        val gate: OwnerAdmissionGate,
        candidateId: String,
    ) {
        constructor(ownerId: String?, primaryId: String?, candidateId: String) :
            this(FakeStore(ownerId), FakePrimary(primaryId), OwnerAdmissionGate(), candidateId)

        var roomState = false
        var telemetryRows = 0
        val backend = object : TravellerAuthBackend {
            override suspend fun awaitInitialization() = Unit
            override fun sessionState(): TravellerSessionState = primary.id?.let {
                TravellerSessionState.Authenticated(it)
            } ?: TravellerSessionState.NotAuthenticated
            override suspend fun signInAnonymously() = error("must not create an anonymous owner")
        }
        val coordinator = TravellerIdentityCoordinator(backend, store, admissionGate = gate)
        val attempt = FakeAttempt(candidateId)
        val engine = ReturningTravellerLoginEngine(
            attempts = ReturningLoginAttemptFactory { attempt },
            boundary = FirstOwnerAdoptionBoundary {
                OwnerAdoptionSnapshot(
                    persistedOwnerId = store.expectedTravellerUserId(),
                    ownerMarkerPresent = store.expectedTravellerUserId() != null,
                    primarySessionState = backend.sessionState(),
                    roomOwnerStatePresent = roomState,
                    priorInstallationArtifactsPresent = false,
                    fallbackKeyMaterialPresent = false,
                    pendingFallbackWorkPresent = false,
                    foregroundMonitoringActive = roomState,
                )
            },
            ownerStore = store,
            primary = primary,
            identityCoordinator = coordinator,
            admissionGate = gate,
        )
    }

    private class FakeStore(private var id: String?) : TravellerIdentityStore {
        var persistCount = 0
        var failPersist = false
        override fun expectedTravellerUserId() = id
        override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean {
            persistCount++
            if (failPersist) return false
            if (id != null) return id == userId
            id = userId
            return true
        }
    }

    private class FakePrimary(var id: String?) : PrimaryTravellerSession {
        var importCount = 0
        var clearCount = 0
        var failImport = false
        var wrongImportId: String? = null
        var importStarted: CompletableDeferred<Unit>? = null
        var releaseImport: CompletableDeferred<Unit>? = null
        override fun userId() = id
        override suspend fun import(session: UserSession) {
            importCount++
            importStarted?.complete(Unit)
            releaseImport?.await()
            if (failImport) throw IOException("import failed")
            id = wrongImportId ?: session.accessToken
        }
        override suspend fun clearLocalSession() { clearCount++; id = null }
    }

    private class FakeAttempt(private val candidateId: String) : ReturningLoginAttempt {
        var requestedEmail: String? = null
        var requestFailure = false
        var requestResult = LoginCodeRequestResult.SENT
        var verificationCount = 0
        var verificationFailure: String? = null
        var profileFailure = false
        var hasProfile = true
        var discardCount = 0
        var requestStarted: CompletableDeferred<Unit>? = null
        var releaseRequest: CompletableDeferred<Unit>? = null
        var profileStarted: CompletableDeferred<Unit>? = null
        var releaseProfile: CompletableDeferred<Unit>? = null
        override suspend fun requestCode(email: String): LoginCodeRequestResult {
            requestedEmail = email
            requestStarted?.complete(Unit)
            releaseRequest?.await()
            if (requestFailure) throw IOException("transport unavailable")
            return requestResult
        }
        override suspend fun verifyCode(email: String, code: String): VerifiedLoginCandidate {
            verificationCount++
            assertEquals(requestedEmail, email)
            assertEquals("123456", code)
            if (verificationFailure != null) throw IOException(verificationFailure)
            return VerifiedLoginCandidate(candidateId, UserSession(
                accessToken = candidateId, refreshToken = "temporary-refresh",
                expiresIn = 3600, tokenType = "bearer",
            ))
        }
        override suspend fun hasTravellerProfile(): Boolean {
            profileStarted?.complete(Unit)
            releaseProfile?.await()
            if (profileFailure) throw IOException("profile unavailable")
            return hasProfile
        }
        override suspend fun discard() { discardCount++ }
    }
}
