package com.journeycontinuity.app.ui

import com.journeycontinuity.app.auth.TravellerAccountGateway
import com.journeycontinuity.app.auth.TravellerAccountOwnerException
import com.journeycontinuity.app.auth.TravellerAccountProfile
import com.journeycontinuity.app.auth.TravellerAccountUnavailableException
import com.journeycontinuity.app.auth.TravellerHandleUnavailableException
import com.journeycontinuity.app.auth.NoActiveSignOutResult
import com.journeycontinuity.app.auth.TravellerNoActiveSignOutGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TravellerAccountViewModelTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val profile = TravellerAccountProfile(owner, "Ada Owner", "ada_owner", "ada@example.com")

    private fun accountTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    @Test fun ownerProfileLoadsAndEmailIsOnlyReadFromGateway() = accountTest {
        val gateway = FakeGateway(profile)
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        assertEquals(profile, account.state.value.profile)
        assertEquals("ada@example.com", account.state.value.profile?.verifiedEmail)
        assertNull(account.state.value.loadError)
        assertEquals(1, gateway.loads)
    }

    @Test fun missingAndUnavailableProfilesShowErrorsWithoutClaimingIdentity() = accountTest {
        val gateway = FakeGateway(profile).apply { loadFailure = TravellerAccountUnavailableException() }
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        assertNull(account.state.value.profile)
        assertNotNull(account.state.value.loadError)
        gateway.loadFailure = TravellerAccountOwnerException()
        account.load()
        advanceUntilIdle()
        assertTrue(account.state.value.loadError!!.contains("Verified Traveller"))
    }

    @Test fun editOpensWithCurrentValuesAndCancelDoesNotSave() = accountTest {
        val gateway = FakeGateway(profile)
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        account.openEdit()
        assertEquals("Ada Owner", account.state.value.fullNameInput)
        assertEquals("@ada_owner", account.state.value.handleInput)
        account.updateFullName("Discard me")
        account.cancelEdit()
        assertFalse(account.state.value.editOpen)
        assertEquals(profile, account.state.value.profile)
        assertEquals(0, gateway.saves)
    }

    @Test fun nameAndHandleValidationRejectInvalidInputBeforeSave() = accountTest {
        val gateway = FakeGateway(profile)
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        account.openEdit()
        account.updateFullName("  ")
        account.updateHandle("@a!")
        account.save()
        advanceUntilIdle()
        assertNotNull(account.state.value.nameError)
        assertEquals(AccountHandleStatus.INVALID, account.state.value.handleStatus)
        assertEquals(0, gateway.saves)
        assertTrue(account.state.value.editOpen)
    }

    @Test fun normalizedHandleAvailabilityReportsAvailableAndUnavailable() = accountTest {
        val gateway = FakeGateway(profile)
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        account.openEdit()
        account.updateHandle(" @New_Handle ")
        assertEquals(AccountHandleStatus.CHECKING, account.state.value.handleStatus)
        advanceUntilIdle()
        assertEquals("new_handle", gateway.checkedHandle)
        assertEquals(AccountHandleStatus.AVAILABLE, account.state.value.handleStatus)
        gateway.available = false
        account.updateHandle("@taken_handle")
        advanceUntilIdle()
        assertEquals(AccountHandleStatus.UNAVAILABLE, account.state.value.handleStatus)
        account.save()
        advanceUntilIdle()
        assertEquals(0, gateway.saves)
    }

    @Test fun saveUsesOwnerAndAuthoritativeReturnedIdentity() = accountTest {
        val gateway = FakeGateway(profile)
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        account.openEdit()
        account.updateFullName("  Ada New  ")
        account.updateHandle(" @New_Handle ")
        advanceUntilIdle()
        account.save()
        advanceUntilIdle()
        assertEquals(owner, gateway.savedOwner)
        assertEquals("Ada New", gateway.savedName)
        assertEquals("new_handle", gateway.savedHandle)
        assertEquals("new_handle", account.state.value.profile?.handle)
        assertFalse(account.state.value.editOpen)
    }

    @Test fun uniquenessRaceKeepsEditAndEnteredValues() = accountTest {
        val gateway = FakeGateway(profile).apply { saveFailure = TravellerHandleUnavailableException() }
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        account.openEdit()
        account.updateHandle("@new_handle")
        advanceUntilIdle()
        assertEquals(AccountHandleStatus.AVAILABLE, account.state.value.handleStatus)
        account.save()
        advanceUntilIdle()
        assertTrue(account.state.value.editOpen)
        assertEquals("@new_handle", account.state.value.handleInput)
        assertEquals(AccountHandleStatus.UNAVAILABLE, account.state.value.handleStatus)
        assertTrue(account.state.value.saveError!!.contains("no longer available"))
    }

    @Test fun transportAndOwnerFailuresKeepEditOpenWithoutReplacingProfile() = accountTest {
        val gateway = FakeGateway(profile).apply { saveFailure = IllegalStateException("offline") }
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        account.openEdit()
        account.updateFullName("Changed")
        account.save()
        advanceUntilIdle()
        assertTrue(account.state.value.editOpen)
        assertEquals("Changed", account.state.value.fullNameInput)
        assertEquals(profile, account.state.value.profile)
        assertNotNull(account.state.value.saveError)
        gateway.saveFailure = TravellerAccountOwnerException()
        account.save()
        advanceUntilIdle()
        assertTrue(account.state.value.saveError!!.contains("Verified Traveller"))
    }

    @Test fun mismatchedSavedOwnerCannotReplaceAccountProfile() = accountTest {
        val gateway = FakeGateway(profile).apply { savedProfile = profile.copy(ownerId = "other-owner") }
        val account = TravellerAccountViewModel(gateway)
        account.load()
        advanceUntilIdle()
        account.openEdit()
        account.save()
        advanceUntilIdle()
        assertEquals(profile, account.state.value.profile)
        assertTrue(account.state.value.editOpen)
    }

    @Test fun noActiveSignOutOpensConfirmationAndStaySignedInDoesNothing() = accountTest {
        val signOut = FakeSignOut()
        val account = TravellerAccountViewModel(FakeGateway(profile), signOut)
        account.load()
        advanceUntilIdle()
        account.requestNoActiveSignOut(ready = true)
        advanceUntilIdle()
        assertEquals(AccountSignOutStatus.CONFIRMING, account.state.value.signOutStatus)
        assertEquals(1, signOut.checks)
        account.cancelNoActiveSignOut()
        assertEquals(AccountSignOutStatus.IDLE, account.state.value.signOutStatus)
        assertEquals(0, signOut.calls)
    }

    @Test fun confirmationRechecksAndReassessesRootOnlyAfterSuccess() = accountTest {
        val signOut = FakeSignOut()
        val account = TravellerAccountViewModel(FakeGateway(profile), signOut)
        account.load()
        advanceUntilIdle()
        account.requestNoActiveSignOut(true)
        advanceUntilIdle()
        var rootAssessments = 0
        account.confirmNoActiveSignOut(true) { rootAssessments++ }
        advanceUntilIdle()
        assertEquals(owner, signOut.lastOwner)
        assertEquals(1, signOut.calls)
        assertEquals(1, rootAssessments)
        assertEquals(AccountSignOutStatus.SIGNED_OUT, account.state.value.signOutStatus)
    }

    @Test fun pendingStartOrRestorationAtConfirmationFailsClosed() = accountTest {
        val signOut = FakeSignOut()
        val account = TravellerAccountViewModel(FakeGateway(profile), signOut)
        account.load()
        advanceUntilIdle()
        account.requestNoActiveSignOut(true)
        advanceUntilIdle()
        account.confirmNoActiveSignOut(false) { error("Must not reassess") }
        assertEquals(AccountSignOutStatus.FAILURE, account.state.value.signOutStatus)
        assertEquals(0, signOut.calls)
        assertNotNull(account.state.value.signOutError)
    }

    @Test fun activeJourneyAtOpenOrAtConfirmationNeverCallsLocalSignOut() = accountTest {
        val signOut = FakeSignOut().apply { noActive = false }
        val account = TravellerAccountViewModel(FakeGateway(profile), signOut)
        account.load()
        advanceUntilIdle()
        account.requestNoActiveSignOut(true)
        advanceUntilIdle()
        assertEquals(AccountSignOutStatus.FAILURE, account.state.value.signOutStatus)
        signOut.noActive = true
        account.requestNoActiveSignOut(true)
        advanceUntilIdle()
        signOut.result = NoActiveSignOutResult.ActiveJourney
        account.confirmNoActiveSignOut(true) { error("Must not reassess") }
        advanceUntilIdle()
        assertEquals(AccountSignOutStatus.FAILURE, account.state.value.signOutStatus)
        assertEquals(1, signOut.calls)
        assertTrue(account.state.value.signOutError!!.contains("Journey is active"))
    }

    @Test fun signOutFailureStaysInAccountAndDoesNotReassessRoot() = accountTest {
        val signOut = FakeSignOut().apply { result = NoActiveSignOutResult.Failed }
        val account = TravellerAccountViewModel(FakeGateway(profile), signOut)
        account.load()
        advanceUntilIdle()
        account.requestNoActiveSignOut(true)
        advanceUntilIdle()
        account.confirmNoActiveSignOut(true) { error("Must not reassess") }
        advanceUntilIdle()
        assertEquals(AccountSignOutStatus.FAILURE, account.state.value.signOutStatus)
        assertEquals(profile, account.state.value.profile)
        assertNotNull(account.state.value.signOutError)
        assertEquals(1, signOut.calls)
    }

    @Test fun locallySignedOutResultReassessesRootWithoutAnErrorOrSecondCall() = accountTest {
        val signOut = FakeSignOut()
        val account = TravellerAccountViewModel(FakeGateway(profile), signOut)
        account.load()
        advanceUntilIdle()
        account.requestNoActiveSignOut(true)
        advanceUntilIdle()
        var rootAssessments = 0
        account.confirmNoActiveSignOut(true) { rootAssessments++ }
        advanceUntilIdle()
        assertEquals(1, rootAssessments)
        assertEquals(1, signOut.calls)
        assertEquals(AccountSignOutStatus.SIGNED_OUT, account.state.value.signOutStatus)
        assertNull(account.state.value.signOutError)
    }

    @Test fun uncertainSessionReassessesRootWithoutClaimingSignOutOrShowingError() = accountTest {
        val signOut = FakeSignOut().apply {
            result = NoActiveSignOutResult.AuthenticationReassessmentRequired
        }
        val account = TravellerAccountViewModel(FakeGateway(profile), signOut)
        account.load()
        advanceUntilIdle()
        account.requestNoActiveSignOut(true)
        advanceUntilIdle()
        var rootAssessments = 0
        account.confirmNoActiveSignOut(true) { rootAssessments++ }
        advanceUntilIdle()
        assertEquals(1, rootAssessments)
        assertEquals(1, signOut.calls)
        assertEquals(AccountSignOutStatus.IDLE, account.state.value.signOutStatus)
        assertNull(account.state.value.signOutError)
    }

    @Test fun duplicateConfirmationCannotIssueSecondSignOut() = accountTest {
        val signOut = FakeSignOut().apply { release = CompletableDeferred() }
        val account = TravellerAccountViewModel(FakeGateway(profile), signOut)
        account.load()
        advanceUntilIdle()
        account.requestNoActiveSignOut(true)
        advanceUntilIdle()
        account.confirmNoActiveSignOut(true) {}
        account.confirmNoActiveSignOut(true) {}
        advanceUntilIdle()
        assertEquals(AccountSignOutStatus.SIGNING_OUT, account.state.value.signOutStatus)
        assertEquals(1, signOut.calls)
        signOut.release!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, signOut.calls)
    }

    private class FakeSignOut : TravellerNoActiveSignOutGateway {
        var noActive = true
        var checks = 0
        var calls = 0
        var lastOwner: String? = null
        var result: NoActiveSignOutResult = NoActiveSignOutResult.SignedOut
        var release: CompletableDeferred<Unit>? = null
        override suspend fun hasNoActiveJourney(): Boolean {
            checks++
            return noActive
        }
        override suspend fun signOut(expectedOwnerId: String): NoActiveSignOutResult {
            calls++
            lastOwner = expectedOwnerId
            release?.await()
            return result
        }
    }

    private class FakeGateway(var profile: TravellerAccountProfile) : TravellerAccountGateway {
        var loads = 0
        var saves = 0
        var available = true
        var checkedHandle: String? = null
        var savedOwner: String? = null
        var savedName: String? = null
        var savedHandle: String? = null
        var savedProfile: TravellerAccountProfile? = null
        var loadFailure: Throwable? = null
        var saveFailure: Throwable? = null

        override suspend fun load(): TravellerAccountProfile {
            loads++
            loadFailure?.let { throw it }
            return profile
        }

        override suspend fun checkHandleAvailability(ownerId: String, handle: String): Boolean {
            assertEquals(profile.ownerId, ownerId)
            checkedHandle = handle
            return available
        }

        override suspend fun save(ownerId: String, fullName: String, handle: String): TravellerAccountProfile {
            saves++
            savedOwner = ownerId
            savedName = fullName
            savedHandle = handle
            saveFailure?.let { throw it }
            return savedProfile ?: profile.copy(fullName = fullName, handle = handle).also { profile = it }
        }
    }
}
