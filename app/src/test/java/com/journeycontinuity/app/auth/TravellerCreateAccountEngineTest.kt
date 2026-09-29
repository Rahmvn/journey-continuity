package com.journeycontinuity.app.auth

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravellerCreateAccountEngineTest {
    private val ownerA = "10000000-0000-4000-8000-0000000000a1"
    private val ownerB = "10000000-0000-4000-8000-0000000000b2"
    private val email = "owner@example.test"

    @Test fun anonymousOwnerUpgradesSameIdAndSavesNormalizedIdentity() = runBlocking {
        val rig = Rig()
        assertEquals(CreateAccountState.CodeRequired(),
            rig.engine.begin("  Abdulrahman Saheed  ", "  @RAHMVN  ", email))
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals(ownerA, rig.gateway.sessionId)
        assertTrue(rig.gateway.user.isAnonymous == true)
        assertFalse(rig.gateway.user.emailConfirmed)
        assertEquals(CreateAccountState.Completed, rig.engine.submitCode("123456"))
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals(ownerA, rig.gateway.sessionId)
        assertEquals(ownerA, rig.gateway.user.userId)
        assertEquals(false, rig.gateway.user.isAnonymous)
        assertTrue(rig.gateway.user.emailConfirmed)
        assertEquals(TravellerProfileIdentity("Abdulrahman Saheed", "rahmvn"), rig.gateway.profile)
        assertEquals(1, rig.gateway.saveCount)
        assertEquals(0, rig.store.persistCount)
    }

    @Test fun invalidNameAndHandleNeverRequestEmailChange() = runBlocking {
        val rig = Rig()
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.INVALID_FULL_NAME),
            rig.engine.begin("   ", "@rahmvn", email))
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.INVALID_FULL_NAME),
            rig.engine.begin("Bad\u0000Name", "@rahmvn", email))
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.INVALID_FULL_NAME),
            rig.engine.begin("a".repeat(101), "@rahmvn", email))
        for (handle in listOf("ab", "1hello", "hello_", "@@hello", "he.llo", "r\u00e9ne", "a".repeat(25))) {
            assertEquals(CreateAccountState.Failed(CreateAccountFailure.INVALID_HANDLE),
                rig.engine.begin("Valid Name", handle, email))
        }
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.INVALID_EMAIL),
            rig.engine.begin("Valid Name", "rahmvn", "bad email"))
        assertEquals(0, rig.gateway.requestCount)
    }

    @Test fun availabilityIsProvisionalAndFailuresRemainUnknown() = runBlocking {
        val rig = Rig()
        assertEquals(HandleAvailability.INVALID, rig.engine.checkHandleAvailability("@bad_"))
        assertEquals(HandleAvailability.AVAILABLE, rig.engine.checkHandleAvailability(" @RAHMVN "))
        assertEquals("rahmvn", rig.gateway.lastCheckedHandle)
        rig.gateway.occupiedHandles += "rahmvn"
        assertEquals(HandleAvailability.UNAVAILABLE, rig.engine.checkHandleAvailability("RAHMVN"))
        rig.gateway.availabilityFails = true
        assertEquals(HandleAvailability.UNKNOWN, rig.engine.checkHandleAvailability("otherhandle"))
        assertNull(rig.gateway.profile)
    }

    @Test fun duplicateEmailKeepsAnonymousOwnerAndJourney() = runBlocking {
        val rig = Rig().apply { activeJourney = true; telemetryRows = 5 }
        rig.gateway.duplicateEmail = true
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.EMAIL_UNAVAILABLE),
            rig.engine.begin("Valid Name", "rahmvn", email))
        assertEquals(ownerA, rig.gateway.sessionId)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertTrue(rig.gateway.user.isAnonymous == true)
        assertTrue(rig.activeJourney)
        assertEquals(5, rig.telemetryRows)
        assertEquals(0, rig.gateway.saveCount)
    }

    @Test fun wrongExpiredAndReplayedCodesDoNotUpgradeOrStopMonitoring() = runBlocking {
        for (failure in listOf("wrong", "expired", "replayed")) {
            val rig = Rig().apply { activeJourney = true; telemetryRows = 3 }
            rig.engine.begin("Valid Name", "rahmvn", email)
            rig.gateway.verificationFailure = failure
            assertEquals(CreateAccountState.CodeRequired(CreateAccountCodeIssue.INVALID_OR_EXPIRED),
                rig.engine.submitCode("123456"))
            assertTrue(rig.gateway.user.isAnonymous == true)
            assertEquals(ownerA, rig.gateway.sessionId)
            assertEquals(ownerA, rig.store.expectedTravellerUserId())
            assertEquals(0, rig.gateway.saveCount)
            assertTrue(rig.activeJourney)
            assertEquals(3, rig.telemetryRows)
        }
    }

    @Test fun wrongCodeCanBeCorrectedAndConsumedCodeCannotBeSubmittedAgain() = runBlocking {
        val rig = Rig()
        rig.engine.begin("Valid Name", "rahmvn", email)
        rig.gateway.verificationFailure = "wrong"
        assertEquals(CreateAccountState.CodeRequired(CreateAccountCodeIssue.INVALID_OR_EXPIRED),
            rig.engine.submitCode("123456"))
        rig.gateway.verificationFailure = null
        assertEquals(CreateAccountState.Completed, rig.engine.submitCode("123456"))
        assertEquals(CreateAccountState.Completed,
            rig.engine.submitCode("123456"))
        assertEquals(1, rig.gateway.saveCount)
    }

    @Test fun finalHandleConflictLeavesVerifiedSameIdAccountReadyForAnotherHandle() = runBlocking {
        val rig = Rig()
        assertEquals(HandleAvailability.AVAILABLE, rig.engine.checkHandleAvailability("@rahmvn"))
        rig.engine.begin("Valid Name", "@RAHMVN", email)
        rig.gateway.occupiedHandles += "rahmvn"
        assertEquals(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.HANDLE_UNAVAILABLE),
            rig.engine.submitCode("123456"))
        assertEquals(false, rig.gateway.user.isAnonymous)
        assertTrue(rig.gateway.user.emailConfirmed)
        assertNull(rig.gateway.profile)
        assertEquals(ownerA, rig.gateway.sessionId)
        assertEquals(CreateAccountState.Completed, rig.engine.completeProfile("Valid Name", "@NEW_HANDLE"))
        assertEquals("new_handle", rig.gateway.profile?.handle)
        assertEquals(1, rig.gateway.requestCount)
    }

    @Test fun profileRpcFailureAfterVerificationCanBeRetriedAfterRestart() = runBlocking {
        val rig = Rig()
        rig.engine.begin("Valid Name", "rahmvn", email)
        rig.gateway.profileReadFails = true
        assertEquals(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.SERVICE_UNAVAILABLE),
            rig.engine.submitCode("123456"))
        assertEquals(ownerA, rig.gateway.user.userId)
        assertTrue(rig.gateway.user.emailConfirmed)
        assertEquals("rahmvn", rig.gateway.profile?.handle)
        rig.gateway.profileReadFails = false
        val restarted = rig.newEngine()
        assertEquals(CreateAccountState.AlreadyCompleted,
            restarted.begin("Valid Name", "rahmvn", email))
        assertEquals(CreateAccountState.Completed,
            restarted.completeProfile("Valid Name", "rahmvn"))
        assertEquals("rahmvn", rig.gateway.profile?.handle)
        assertEquals(1, rig.gateway.requestCount)
        assertEquals(2, rig.gateway.saveCount)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
    }

    @Test fun profileWriteUnavailableAfterVerificationRetainsRecoverableAccount() = runBlocking {
        val rig = Rig()
        rig.engine.begin("Valid Name", "rahmvn", email)
        rig.gateway.saveFails = true
        assertEquals(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.SERVICE_UNAVAILABLE),
            rig.engine.submitCode("123456"))
        assertTrue(rig.gateway.user.emailConfirmed)
        assertNull(rig.gateway.profile)
        rig.gateway.saveFails = false
        assertEquals(CreateAccountState.Completed, rig.engine.completeProfile("Valid Name", "rahmvn"))
    }

    @Test fun lostProfileWriteResponseIsRecognizedOnRetryWithoutOverwriting() = runBlocking {
        val rig = Rig()
        rig.engine.begin("Valid Name", "rahmvn", email)
        rig.gateway.lostSaveResponse = true
        assertEquals(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.SERVICE_UNAVAILABLE),
            rig.engine.submitCode("123456"))
        assertEquals("rahmvn", rig.gateway.profile?.handle)
        assertEquals(CreateAccountState.AlreadyCompleted,
            rig.newEngine().completeProfile("Different Name", "otherhandle"))
        assertEquals(2, rig.gateway.saveCount)
        assertEquals("rahmvn", rig.gateway.profile?.handle)
    }

    @Test fun previouslyCompletedProfileIsNeverOverwrittenByCreateFlow() = runBlocking {
        val rig = Rig()
        rig.gateway.user = CreateAccountAuthIdentity(ownerA, false, email, true)
        rig.gateway.profile = TravellerProfileIdentity("Existing Name", "existinghandle")
        assertEquals(CreateAccountState.AlreadyCompleted,
            rig.engine.begin("New Name", "newhandle", email))
        assertEquals(CreateAccountState.AlreadyCompleted,
            rig.engine.completeProfile("New Name", "newhandle"))
        assertEquals(TravellerProfileIdentity("Existing Name", "existinghandle"), rig.gateway.profile)
        assertEquals(1, rig.gateway.saveCount)
        assertEquals(0, rig.gateway.requestCount)
    }

    @Test fun identicalExistingProfileIsIdempotentCompletion() = runBlocking {
        val rig = Rig()
        rig.gateway.user = CreateAccountAuthIdentity(ownerA, false, email, true)
        rig.gateway.profile = TravellerProfileIdentity("Existing Name", "existinghandle")
        assertEquals(CreateAccountState.Completed,
            rig.engine.completeProfile("Existing Name", "@EXISTINGHANDLE"))
        assertEquals(TravellerProfileIdentity("Existing Name", "existinghandle"), rig.gateway.profile)
        assertEquals(1, rig.gateway.saveCount)
    }

    @Test fun competingSameOwnerCompletionCannotOverwriteWinner() = runBlocking {
        val rig = Rig()
        rig.gateway.user = CreateAccountAuthIdentity(ownerA, false, email, true)
        val otherSession = rig.newEngine()
        assertEquals(CreateAccountState.Completed,
            rig.engine.completeProfile("First Name", "firsthandle"))
        assertEquals(CreateAccountState.AlreadyCompleted,
            otherSession.completeProfile("Second Name", "secondhandle"))
        assertEquals(TravellerProfileIdentity("First Name", "firsthandle"), rig.gateway.profile)
        assertEquals(2, rig.gateway.saveCount)
    }

    @Test fun ownerSessionMismatchFailsBeforeEmailRequest() = runBlocking {
        val rig = Rig()
        rig.gateway.sessionId = ownerB
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.OWNER_MISMATCH),
            rig.engine.begin("Valid Name", "rahmvn", email))
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertNull(rig.gateway.sessionId)
        assertEquals(0, rig.gateway.requestCount)
        assertEquals(0, rig.gateway.saveCount)
    }

    @Test fun installationWithoutPersistedOwnerCannotStartCreateAccount() = runBlocking {
        val rig = Rig(ownerId = null)
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.OWNER_UNAVAILABLE),
            rig.engine.begin("Valid Name", "rahmvn", email))
        assertEquals(0, rig.gateway.requestCount)
        assertEquals(0, rig.store.persistCount)
    }

    @Test fun sessionSwitchDuringCodeEntryFailsClosedWithoutProfileWrite() = runBlocking {
        val rig = Rig()
        rig.engine.begin("Valid Name", "rahmvn", email)
        rig.gateway.sessionId = ownerB
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.OWNER_MISMATCH),
            rig.engine.submitCode("123456"))
        assertNull(rig.gateway.sessionId)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals(0, rig.gateway.saveCount)
    }

    @Test fun differentIdAfterVerificationIsClearedLocallyAndNeverAdopted() = runBlocking {
        val rig = Rig().apply { activeJourney = true; telemetryRows = 7 }
        rig.engine.begin("Valid Name", "rahmvn", email)
        rig.gateway.verificationResultId = ownerB
        assertEquals(CreateAccountState.Failed(CreateAccountFailure.OWNER_MISMATCH),
            rig.engine.submitCode("123456"))
        assertNull(rig.gateway.sessionId)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals(1, rig.gateway.clearCount)
        assertNull(rig.gateway.profile)
        assertTrue(rig.activeJourney)
        assertEquals(7, rig.telemetryRows)
    }

    @Test fun activeJourneyAndEvidenceStayWithAThroughSuccess() = runBlocking {
        val rig = Rig().apply { activeJourney = true; telemetryRows = 12 }
        rig.engine.begin("Valid Name", "rahmvn", email)
        assertEquals(CreateAccountState.Completed, rig.engine.submitCode("123456"))
        assertTrue(rig.activeJourney)
        assertEquals(12, rig.telemetryRows)
        assertEquals(ownerA, rig.gateway.sessionId)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals(ownerA, (rig.coordinator.resolve() as TravellerAuthOutcome.Authenticated).traveller.userId)
    }

    @Test fun overlappingAttemptsAreBusyAndCancelClearsOnlyPendingDetails() = runBlocking {
        val rig = Rig()
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        rig.gateway.requestStarted = requestStarted
        rig.gateway.releaseRequest = releaseRequest
        val first = async { rig.engine.begin("Valid Name", "rahmvn", email) }
        requestStarted.await()
        assertEquals(CreateAccountState.Busy, rig.engine.begin("Other Name", "otherhandle", email))
        releaseRequest.complete(Unit)
        assertEquals(CreateAccountState.CodeRequired(), first.await())
        assertEquals(CreateAccountState.Busy, rig.engine.begin("Other Name", "otherhandle", email))
        rig.engine.cancel()
        assertEquals(CreateAccountState.Ready, rig.engine.state.value)
        assertEquals(ownerA, rig.gateway.sessionId)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals(0, rig.gateway.saveCount)
    }

    @Test fun cancellationDuringVerificationFinishesSafeSameIdOutcome() = runBlocking {
        val rig = Rig()
        rig.engine.begin("Valid Name", "rahmvn", email)
        val verificationStarted = CompletableDeferred<Unit>()
        val releaseVerification = CompletableDeferred<Unit>()
        rig.gateway.verificationStarted = verificationStarted
        rig.gateway.releaseVerification = releaseVerification
        val submit = async { rig.engine.submitCode("123456") }
        verificationStarted.await()
        submit.cancel()
        releaseVerification.complete(Unit)
        submit.cancelAndJoin()
        assertEquals(CreateAccountState.Completed, rig.engine.state.value)
        assertEquals(ownerA, rig.gateway.sessionId)
        assertEquals(ownerA, rig.store.expectedTravellerUserId())
        assertEquals("rahmvn", rig.gateway.profile?.handle)
    }

    @Test fun lostVerificationResponseProbesAuthTruthAndCompletesProfile() = runBlocking {
        val rig = Rig()
        rig.engine.begin("Valid Name", "rahmvn", email)
        rig.gateway.lostVerificationResponse = true
        assertEquals(CreateAccountState.Completed, rig.engine.submitCode("123456"))
        assertEquals(ownerA, rig.gateway.sessionId)
        assertEquals("rahmvn", rig.gateway.profile?.handle)
    }

    @Test fun emailConfirmationDisabledNeverPretendsCodeWasRequired() = runBlocking {
        val rig = Rig()
        rig.gateway.immediateConfirmation = true
        assertEquals(CreateAccountState.ProfileRequired(CreateAccountProfileIssue.VERIFICATION_NOT_REQUIRED),
            rig.engine.begin("Valid Name", "rahmvn", email))
        assertEquals(0, rig.gateway.saveCount)
        assertEquals(ownerA, rig.gateway.sessionId)
    }

    private inner class Rig(ownerId: String? = ownerA) {
        val store = FakeStore(ownerId)
        val gateway = FakeGateway(ownerA)
        var activeJourney = false
        var telemetryRows = 0
        val backend = object : TravellerAuthBackend {
            override suspend fun awaitInitialization() = Unit
            override fun sessionState(): TravellerSessionState = gateway.sessionId?.let {
                TravellerSessionState.Authenticated(it)
            } ?: TravellerSessionState.NotAuthenticated
            override suspend fun signInAnonymously() = error("Create account cannot sign up another user")
        }
        val coordinator = TravellerIdentityCoordinator(backend, store)
        val engine = newEngine()
        fun newEngine() = TravellerCreateAccountEngine(gateway, store, coordinator)
    }

    private class FakeStore(private val owner: String?) : TravellerIdentityStore {
        var persistCount = 0
        override fun expectedTravellerUserId() = owner
        override fun persistExpectedTravellerUserIdIfAbsent(userId: String): Boolean {
            persistCount++
            return false
        }
    }

    private class FakeGateway(owner: String) : TravellerCreateAccountGateway {
        var sessionId: String? = owner
        var user = CreateAccountAuthIdentity(owner, true, null, false)
        var profile: TravellerProfileIdentity? = null
        var requestCount = 0
        var saveCount = 0
        var clearCount = 0
        var requestedEmail: String? = null
        var lastCheckedHandle: String? = null
        val occupiedHandles = mutableSetOf<String>()
        var availabilityFails = false
        var duplicateEmail = false
        var verificationFailure: String? = null
        var verificationResultId: String? = null
        var lostVerificationResponse = false
        var profileReadFails = false
        var saveFails = false
        var lostSaveResponse = false
        var immediateConfirmation = false
        var requestStarted: CompletableDeferred<Unit>? = null
        var releaseRequest: CompletableDeferred<Unit>? = null
        var verificationStarted: CompletableDeferred<Unit>? = null
        var releaseVerification: CompletableDeferred<Unit>? = null

        override fun sessionUserId() = sessionId
        override suspend fun currentIdentity() = user
        override suspend fun requestEmailChange(email: String) {
            requestCount++
            requestedEmail = email
            requestStarted?.complete(Unit)
            releaseRequest?.await()
            if (duplicateEmail) throw EmailAlreadyInUseException()
            if (immediateConfirmation) user = user.copy(isAnonymous = false, email = email, emailConfirmed = true)
        }
        override suspend fun verifyEmailChange(email: String, code: String): String? {
            assertEquals(requestedEmail, email)
            assertEquals("123456", code)
            verificationStarted?.complete(Unit)
            releaseVerification?.await()
            if (verificationFailure != null) throw IOException(verificationFailure)
            val id = verificationResultId ?: user.userId
            sessionId = id
            user = user.copy(userId = id, isAnonymous = false, email = email, emailConfirmed = true)
            if (lostVerificationResponse) throw IOException("response lost after verification")
            return id
        }
        override suspend fun hasTravellerProfile(): Boolean {
            if (profileReadFails) throw IOException("profile RPC unavailable")
            return profile != null
        }
        override suspend fun checkHandleAvailability(handle: String): Boolean {
            lastCheckedHandle = handle
            if (availabilityFails) throw IOException("availability unavailable")
            return handle !in occupiedHandles
        }
        override suspend fun finalizeProfile(identity: TravellerProfileIdentity): ProfileFinalization {
            saveCount++
            check(sessionId == user.userId && user.isAnonymous == false && user.emailConfirmed)
            if (saveFails) throw IOException("profile setter unavailable")
            profile?.let { return ProfileFinalization.AlreadyCompleted(it == identity) }
            if (identity.handle in occupiedHandles) return ProfileFinalization.HandleUnavailable
            profile = identity
            if (lostSaveResponse) throw IOException("profile write succeeded but response was lost")
            return ProfileFinalization.Created
        }
        override suspend fun clearMismatchedLocalSession(expectedOwnerId: String) {
            if (sessionId != null && sessionId != expectedOwnerId) {
                clearCount++
                sessionId = null
            }
        }
    }
}
