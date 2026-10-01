package com.journeycontinuity.app.ui

import com.journeycontinuity.app.auth.AuthenticatedTraveller
import com.journeycontinuity.app.auth.CreateAccountCodeIssue
import com.journeycontinuity.app.auth.CreateAccountProfileIssue
import com.journeycontinuity.app.auth.CreateAccountState
import com.journeycontinuity.app.auth.HandleAvailability
import com.journeycontinuity.app.auth.ReturningLoginFailure
import com.journeycontinuity.app.auth.ReturningLoginRejection
import com.journeycontinuity.app.auth.ReturningLoginState
import com.journeycontinuity.app.auth.TravellerAuthOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TravellerRootViewModelTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"

    private fun rootTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    @Test fun freshInstallIntroAndCompletionPersistence() = rootTest {
        val auth = FakeAuth()
        val intro = FakeIntro()
        val root = newRoot(auth, intro)
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.INTRO, root.state.value.route)
        assertEquals(0, auth.establishCalls)
        root.completeIntro()
        assertTrue(intro.completed)
        assertEquals(TravellerRootRoute.IDENTITY_CHOICE, root.state.value.route)
        val restarted = newRoot(auth, intro)
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.IDENTITY_CHOICE, restarted.state.value.route)
    }

    @Test fun existingOwnerPrecedesIntroAndKeepsLegacyRuntime() = rootTest {
        val auth = FakeAuth().apply { resolved = authenticated(a) }
        val root = newRoot(auth, FakeIntro())
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.ADMITTED_EXISTING, root.state.value.route)
        assertEquals(0, auth.establishCalls)
    }

    @Test fun openingCreateAndEditingHandleStayOwnerless() = rootTest {
        val auth = FakeAuth()
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        root.chooseCreateAccount()
        root.onHandleChange("@Handle")
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.CREATE_DETAILS, root.state.value.route)
        assertEquals(0, auth.establishCalls)
        assertEquals(0, auth.create.checkCalls)
    }

    @Test fun continueEstablishesExactlyAThenChecksHandleThenBegins() = rootTest {
        val auth = FakeAuth()
        val progress = FakeProgress()
        val root = newRoot(auth, FakeIntro(true), progress)
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(1, auth.establishCalls)
        assertEquals(listOf("establish", "check", "begin"), auth.events)
        assertEquals(a, progress.pendingOwnerId())
        assertEquals(TravellerRootRoute.CREATE_OTP, root.state.value.route)
        assertEquals(CreateAccountHandleStatus.AVAILABLE, root.state.value.handleFeedback?.status)
    }

    @Test fun establishmentFailurePreventsAvailabilityAndBegin() = rootTest {
        val auth = FakeAuth().apply { establishResult = TravellerAuthOutcome.TemporaryAuthUnavailable }
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(0, auth.create.checkCalls)
        assertEquals(0, auth.create.beginCalls)
        assertEquals(TravellerRootRoute.RECOVERY, root.state.value.route)
    }

    @Test fun progressWriteFailureAfterAStaysOutOfJourneyOnRestart() = rootTest {
        val auth = FakeAuth()
        val progress = FakeProgress().apply { failWrites = true }
        val entry = FakeCreateEntry()
        val root = newRoot(auth, FakeIntro(true), progress, createEntry = entry)
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(1, auth.establishCalls)
        assertEquals(0, auth.create.beginCalls)
        assertEquals(TravellerRootRoute.RECOVERY, root.state.value.route)
        val restarted = newRoot(auth, FakeIntro(true), progress, createEntry = entry)
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.CREATE_DETAILS, restarted.state.value.route)
    }

    @Test fun createIntentWriteFailurePreventsEstablishment() = rootTest {
        val auth = FakeAuth()
        val entry = FakeCreateEntry().apply { failWrites = true }
        val root = newRoot(auth, FakeIntro(true), createEntry = entry)
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(0, auth.establishCalls)
        assertEquals(0, auth.create.beginCalls)
        assertEquals(TravellerRootRoute.RECOVERY, root.state.value.route)
    }

    @Test fun handleUnavailableNeverBeginsAndUnknownIsNotTaken() = rootTest {
        val auth = FakeAuth().apply { create.availability = HandleAvailability.UNAVAILABLE }
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(0, auth.create.beginCalls)
        assertEquals(CreateAccountHandleStatus.TAKEN, root.state.value.handleFeedback?.status)
        auth.create.availability = HandleAvailability.UNKNOWN
        root.onHandleChange("different_handle")
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(null, root.state.value.handleFeedback)
        assertEquals(0, auth.create.beginCalls)
    }

    @Test fun oldAResultCannotWinAfterAtoBtoAEdits() = rootTest {
        val oldA = CompletableDeferred<HandleAvailability>()
        val newA = CompletableDeferred<HandleAvailability>()
        val auth = FakeAuth()
        var aRequests = 0
        auth.create.availabilityResolver = { raw ->
            if (raw == "traveller_one") {
                aRequests++
                if (aRequests == 1) oldA.await() else newA.await()
            } else HandleAvailability.AVAILABLE
        }
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        root.onHandleChange("other_handle")
        advanceUntilIdle()
        root.onHandleChange("traveller_one")
        advanceUntilIdle()
        oldA.complete(HandleAvailability.AVAILABLE)
        advanceUntilIdle()
        assertEquals(CreateAccountHandleStatus.CHECKING, root.state.value.handleFeedback?.status)
        newA.complete(HandleAvailability.UNAVAILABLE)
        advanceUntilIdle()
        assertEquals(CreateAccountHandleStatus.TAKEN, root.state.value.handleFeedback?.status)
        assertEquals(0, auth.create.beginCalls)
    }

    @Test fun loginEntryAndCodeRequestNeverEstablishAAndPrivacyRoutesMatch() = rootTest {
        for (decoy in listOf(false, true)) {
            val auth = FakeAuth().apply { login.decoy = decoy }
            val root = newRoot(auth, FakeIntro(true))
            advanceUntilIdle()
            root.chooseLogIn()
            root.onLoginEmailChange(if (decoy) "absent@example.com" else "exists@example.com")
            root.requestLoginCode()
            advanceUntilIdle()
            assertEquals(TravellerRootRoute.LOGIN_OTP, root.state.value.route)
            assertEquals(ReturningLoginOtpFeedback.NEUTRAL, root.state.value.loginFeedback)
            assertEquals(0, auth.establishCalls)
        }
    }

    @Test fun serviceNoticeSurvivesFieldEditAndClearsWhenRetryStarts() = rootTest {
        val retry = CompletableDeferred<ReturningLoginState>()
        val auth = FakeAuth()
        var requests = 0
        auth.login.requestResponder = {
            requests++
            if (requests == 1) ReturningLoginState.Failed(ReturningLoginFailure.REQUEST_FAILED)
            else retry.await()
        }
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        root.chooseLogIn()
        root.onLoginEmailChange("owner@example.com")
        root.requestLoginCode()
        advanceUntilIdle()
        val notice = root.state.value.notice
        assertEquals("Could not request a code. Try again.", notice)

        root.onLoginEmailChange("other@example.com")
        assertEquals(notice, root.state.value.notice)
        root.requestLoginCode()
        assertTrue(root.state.value.busy)
        assertEquals(null, root.state.value.notice)
        retry.complete(ReturningLoginState.CodeRequested)
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.LOGIN_OTP, root.state.value.route)
    }

    @Test fun absentLoginDecoyCannotAdmit() = rootTest {
        val auth = FakeAuth().apply { login.decoy = true }
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        root.chooseLogIn()
        root.onLoginEmailChange("absent@example.com")
        root.requestLoginCode()
        advanceUntilIdle()
        root.onCodeChange("123456")
        root.verifyLoginCode()
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.LOGIN_OTP, root.state.value.route)
        assertEquals(ReturningLoginOtpFeedback.INVALID_OR_EXPIRED, root.state.value.loginFeedback)
        assertFalse(root.state.value.loginAttemptActive)
    }

    @Test fun duplicateCreateCodeRouteMatchesNormalAndCannotAdmit() = rootTest {
        for (decoy in listOf(false, true)) {
            val auth = FakeAuth().apply { create.duplicate = decoy }
            val root = newRoot(auth, FakeIntro(true))
            advanceUntilIdle()
            enterCreateDetails(root)
            root.continueCreateAccount()
            advanceUntilIdle()
            assertEquals(TravellerRootRoute.CREATE_OTP, root.state.value.route)
            assertFalse(root.state.value.createCodeInvalid)
            if (decoy) {
                root.onCodeChange("123456")
                root.verifyCreateCode()
                advanceUntilIdle()
                assertEquals(TravellerRootRoute.CREATE_OTP, root.state.value.route)
                assertTrue(root.state.value.createCodeInvalid)
                assertEquals(0, auth.create.profileCalls)
            }
        }
    }

    @Test fun sameOwnerCreateCompletionAdmitsOnlyAfterProfile() = rootTest {
        val auth = FakeAuth()
        val progress = FakeProgress()
        val root = newRoot(auth, FakeIntro(true), progress)
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.CREATE_OTP, root.state.value.route)
        root.onCodeChange("123456")
        root.verifyCreateCode()
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.ADMITTED_NEW, root.state.value.route)
        assertEquals(null, progress.pendingOwnerId())
    }

    @Test fun profileRetryStillRequiresCurrentAvailableHandle() = rootTest {
        val auth = FakeAuth().apply {
            create.beginResult = CreateAccountState.ProfileRequired(CreateAccountProfileIssue.NOT_SAVED)
        }
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        assertTrue(root.state.value.profileRequired)
        auth.create.availability = HandleAvailability.UNAVAILABLE
        root.onHandleChange("second_handle")
        advanceUntilIdle()
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(0, auth.create.profileCalls)
        assertEquals(CreateAccountHandleStatus.TAKEN, root.state.value.handleFeedback?.status)
        auth.create.availability = HandleAvailability.AVAILABLE
        root.onHandleChange("third_handle")
        advanceUntilIdle()
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(1, auth.create.profileCalls)
    }

    @Test fun returningAdoptionAndSameOwnerRecoveryAdmitButDifferentOwnerFailsClosed() = rootTest {
        for (persisted in listOf<String?>(null, a)) {
            val auth = FakeAuth().apply { login.admittedId = a }
            if (persisted != null) auth.resolved = TravellerAuthOutcome.TravellerIdentityRecoveryRequired
            val root = newRoot(auth, FakeIntro(true))
            advanceUntilIdle()
            if (root.state.value.route == TravellerRootRoute.RECOVERY) {
                root.recoverSameOwnerWithEmail()
                assertEquals(TravellerRootRoute.LOGIN_EMAIL, root.state.value.route)
            } else root.chooseLogIn()
            root.onLoginEmailChange("owner@example.com")
            root.requestLoginCode()
            advanceUntilIdle()
            root.onCodeChange("123456")
            root.verifyLoginCode()
            advanceUntilIdle()
            assertEquals(TravellerRootRoute.ADMITTED_NEW, root.state.value.route)
        }
        val mismatch = FakeAuth().apply { login.result = ReturningLoginState.Rejected(
            ReturningLoginRejection.DIFFERENT_OWNER) }
        val root = newRoot(mismatch, FakeIntro(true))
        advanceUntilIdle()
        root.chooseLogIn()
        root.onLoginEmailChange("other@example.com")
        root.requestLoginCode()
        advanceUntilIdle()
        root.onCodeChange("123456")
        root.verifyLoginCode()
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.RECOVERY, root.state.value.route)
        assertEquals(0, mismatch.establishCalls)
    }

    @Test fun pendingCreateOwnerResumesDetailsAfterProcessRestartWithoutOtp() = rootTest {
        val auth = FakeAuth()
        val progress = FakeProgress()
        val root = newRoot(auth, FakeIntro(true), progress)
        advanceUntilIdle()
        enterCreateDetails(root)
        root.continueCreateAccount()
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.CREATE_OTP, root.state.value.route)
        val restarted = newRoot(auth, FakeIntro(true), progress)
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.CREATE_DETAILS, restarted.state.value.route)
        assertEquals("", restarted.state.value.code)
    }

    @Test fun cancelledLoginOtpClearsAttemptBeforeEmailEntry() = rootTest {
        val auth = FakeAuth()
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        root.chooseLogIn()
        root.onLoginEmailChange("owner@example.com")
        root.requestLoginCode()
        advanceUntilIdle()
        root.back()
        advanceUntilIdle()
        assertEquals(1, auth.login.cancelCalls)
        assertEquals(TravellerRootRoute.LOGIN_EMAIL, root.state.value.route)
        assertFalse(root.state.value.loginAttemptActive)
    }

    @Test fun processRestartDuringLoginReturnsToEmailWithoutActionableOtp() = rootTest {
        val auth = FakeAuth()
        val entry = FakeLoginEntry()
        val root = newRoot(auth, FakeIntro(true), loginEntry = entry)
        advanceUntilIdle()
        root.chooseLogIn()
        root.onLoginEmailChange("owner@example.com")
        root.requestLoginCode()
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.LOGIN_OTP, root.state.value.route)
        val restarted = newRoot(auth, FakeIntro(true), loginEntry = entry)
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.LOGIN_EMAIL, restarted.state.value.route)
        assertEquals("", restarted.state.value.code)
        assertFalse(restarted.state.value.loginAttemptActive)
        assertEquals(0, auth.establishCalls)
    }

    @Test fun existingOwnerRecoveryCannotSwitchToCreateOrFreshIdentityChoice() = rootTest {
        val auth = FakeAuth().apply { resolved = TravellerAuthOutcome.TravellerIdentityRecoveryRequired }
        val root = newRoot(auth, FakeIntro(true))
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.RECOVERY, root.state.value.route)
        root.recoverSameOwnerWithEmail()
        assertEquals(TravellerRootRoute.LOGIN_EMAIL, root.state.value.route)
        root.chooseCreateAccount()
        assertEquals(TravellerRootRoute.LOGIN_EMAIL, root.state.value.route)
        root.back()
        advanceUntilIdle()
        assertEquals(TravellerRootRoute.RECOVERY, root.state.value.route)
        assertEquals(0, auth.establishCalls)
    }

    private fun newRoot(auth: FakeAuth, intro: FakeIntro, progress: FakeProgress = FakeProgress(),
        loginEntry: FakeLoginEntry = FakeLoginEntry(), createEntry: FakeCreateEntry = FakeCreateEntry()) =
        TravellerRootViewModel(auth, intro, progress, createEntry, loginEntry)

    private fun enterCreateDetails(root: TravellerRootViewModel) {
        root.chooseCreateAccount()
        root.onFullNameChange("A Traveller")
        root.onHandleChange("traveller_one")
        root.onEmailChange("person@example.com")
    }

    private fun authenticated(id: String) = TravellerAuthOutcome.Authenticated(AuthenticatedTraveller(id))

    private class FakeIntro(var completed: Boolean = false) : IntroCompletionPreference {
        override fun isCompleted() = completed
        override fun complete(): Boolean { completed = true; return true }
    }

    private class FakeProgress : CreateAccountProgressPreference {
        var owner: String? = null
        var failWrites = false
        override fun pendingOwnerId() = owner
        override fun markStarted(ownerId: String): Boolean {
            if (failWrites) return false
            owner = ownerId
            return true
        }
        override fun clear(ownerId: String): Boolean {
            if (owner == null) return true
            if (owner != ownerId) return false
            owner = null
            return true
        }
    }

    private class FakeLoginEntry(var pending: Boolean = false) : LoginEntryPreference {
        override fun shouldReturnToEmailEntry() = pending
        override fun markEmailEntry(): Boolean { pending = true; return true }
        override fun clear(): Boolean { pending = false; return true }
    }

    private class FakeCreateEntry(var pending: Boolean = false) : CreateAccountEntryPreference {
        var failWrites = false
        override fun isPending() = pending
        override fun markPending(): Boolean {
            if (failWrites) return false
            pending = true
            return true
        }
        override fun clear(): Boolean { pending = false; return true }
    }

    private inner class FakeAuth : TravellerRootAuth {
        var resolved: TravellerAuthOutcome = TravellerAuthOutcome.TravellerIdentityNotEstablished
        var establishResult: TravellerAuthOutcome = authenticated(a)
        var establishCalls = 0
        val events = mutableListOf<String>()
        val create = FakeCreate(this)
        val login = FakeLogin(this)
        override val createAccount: CreateAccountActions = create
        override val returningLogin: ReturningLoginActions = login
        override suspend fun resolve() = resolved
        override suspend fun establishAnonymousOwner(): TravellerAuthOutcome {
            establishCalls++
            events += "establish"
            if (establishResult is TravellerAuthOutcome.Authenticated) resolved = establishResult
            return establishResult
        }
    }

    private class FakeCreate(private val auth: FakeAuth) : CreateAccountActions {
        var availability = HandleAvailability.AVAILABLE
        var availabilityResolver: (suspend (String) -> HandleAvailability)? = null
        var checkCalls = 0
        var beginCalls = 0
        var profileCalls = 0
        var duplicate = false
        var beginResult: CreateAccountState = CreateAccountState.CodeRequired()
        override suspend fun checkHandleAvailability(handle: String): HandleAvailability {
            checkCalls++
            auth.events += "check"
            return availabilityResolver?.invoke(handle) ?: availability
        }
        override suspend fun begin(fullName: String, handle: String, email: String): CreateAccountState {
            beginCalls++
            auth.events += "begin"
            return beginResult
        }
        override suspend fun submitCode(code: String): CreateAccountState =
            if (duplicate) CreateAccountState.CodeRequired(CreateAccountCodeIssue.INVALID_OR_EXPIRED)
            else CreateAccountState.Completed
        override suspend fun completeProfile(fullName: String, handle: String): CreateAccountState {
            profileCalls++
            return CreateAccountState.Completed
        }
        override suspend fun cancel() = Unit
    }

    private class FakeLogin(private val auth: FakeAuth) : ReturningLoginActions {
        var decoy = false
        var admittedId: String? = null
        var result: ReturningLoginState? = null
        var requestResponder: suspend (String) -> ReturningLoginState = {
            ReturningLoginState.CodeRequested
        }
        var cancelCalls = 0
        override suspend fun requestCode(email: String) = requestResponder(email)
        override suspend fun submitCode(code: String): ReturningLoginState {
            val next = result ?: if (decoy) ReturningLoginState.Failed(
                ReturningLoginFailure.VERIFICATION_FAILED) else ReturningLoginState.Admitted
            if (next == ReturningLoginState.Admitted) auth.resolved = TravellerAuthOutcome.Authenticated(
                AuthenticatedTraveller(admittedId ?: "22222222-2222-4222-8222-222222222222"))
            return next
        }
        override suspend fun cancel() { cancelCalls++ }
    }
}
