package com.journeycontinuity.app.ui

import com.journeycontinuity.app.trusted.TrustedContactStatus
import com.journeycontinuity.app.trusted.TrustedContactSubjectKind
import com.journeycontinuity.app.trusted.TrustedContactSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrustedContactsPresentationTest {
    private fun contact(id: String, kind: TrustedContactSubjectKind,
        status: TrustedContactStatus, name: String = id) = TrustedContactSummary(
        id, kind, name, "$id@example.com", status, null, null, 1)

    @Test fun activeListAndHomeSummaryExcludeRevokedAndExpiredRows() {
        val rows = listOf(
            contact("a", TrustedContactSubjectKind.RELATIONSHIP, TrustedContactStatus.ACCEPTED, "Aisha"),
            contact("b", TrustedContactSubjectKind.RELATIONSHIP, TrustedContactStatus.ACCEPTED, "Ibrahim"),
            contact("c", TrustedContactSubjectKind.INVITATION, TrustedContactStatus.PENDING),
            contact("d", TrustedContactSubjectKind.INVITATION, TrustedContactStatus.EXPIRED),
            contact("e", TrustedContactSubjectKind.RELATIONSHIP, TrustedContactStatus.REVOKED),
        )
        assertEquals(listOf("a", "b", "c"), activeTrustedContacts(rows).map { it.id })
        assertEquals("Aisha, Ibrahim · 1 pending", trustedContactsSummary(JourneyUiState(
            trustedContacts = rows, trustedContactsAvailability = TrustedContactsAvailability.AVAILABLE)))
        val inactiveOnly = JourneyUiState(trustedContacts = rows.drop(3),
            trustedContactsAvailability = TrustedContactsAvailability.AVAILABLE)
        assertEquals("No trusted contacts", trustedContactsSummary(inactiveOnly))
        assertEquals("1 invitation pending", trustedContactsSummary(JourneyUiState(
            trustedContacts = listOf(rows[2]),
            trustedContactsAvailability = TrustedContactsAvailability.AVAILABLE)))
        assertEquals("Currently unavailable", trustedContactsSummary(inactiveOnly.copy(
            trustedContactsAvailability = TrustedContactsAvailability.UNAVAILABLE)))
    }

    @Test fun removalCopyMakesNoGenderAssumption() {
        val copy = trustedContactRemovalCopy("Ibrahim")
        assertTrue(copy.contains("Ibrahim will no longer be a trusted contact"))
        assertFalse(copy.contains(" her "))
        assertFalse(copy.contains(" him "))
    }

    @Test fun confirmedAcceptanceEndsPendingInvitationReadyCopy() {
        val ready = JourneyUiState(trustedContactsPage = TrustedContactsPage.READY,
            invitationReadyId = "invite-1", invitationReadyName = "Aisha",
            invitationReadyEmail = "aisha@example.com",
            invitationShareUrl = "https://trusted.example/?token=secret")
        val accepted = contact("relationship-1", TrustedContactSubjectKind.RELATIONSHIP,
            TrustedContactStatus.ACCEPTED, "Aisha")
        val updated = ready.withTrustedContactsSnapshot(listOf(accepted))
        assertEquals(TrustedContactsPage.LIST, updated.trustedContactsPage)
        assertEquals(null, updated.invitationShareUrl)
        assertEquals(listOf(accepted), activeTrustedContacts(updated.trustedContacts))
    }

    @Test fun foregroundPollingStopsWhenItsSheetScopeIsCancelled() = runTest {
        var refreshes = 0
        val sheetScope = launch { pollTrustedContacts({ refreshes++ }, 30_000) }
        runCurrent()
        assertEquals(0, refreshes)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, refreshes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, refreshes)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, refreshes)
        sheetScope.cancel()
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(2, refreshes)
    }

    @Test fun resumedPollingWaitsBeforeItsFirstPeriodicRefresh() = runTest {
        var refreshes = 0
        val firstScope = launch { pollTrustedContacts({ refreshes++ }, 30_000) }
        advanceTimeBy(10_000)
        firstScope.cancel()
        val resumedScope = launch { pollTrustedContacts({ refreshes++ }, 30_000) }
        runCurrent()
        assertEquals(0, refreshes)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, refreshes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, refreshes)
        resumedScope.cancel()
    }
}
