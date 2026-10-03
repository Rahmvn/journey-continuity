package com.journeycontinuity.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TravellerAccountGatewayTest {
    private val owner = "11111111-1111-4111-8111-111111111111"

    @Test fun verifiedSameOwnerEmailIsAccepted() {
        assertEquals("ada@example.com", requireVerifiedAccountOwner(owner, owner, owner,
            "ada@example.com", false, true))
    }

    @Test fun wrongOrMissingOwnerCannotSupplyAccountIdentity() {
        data class Candidate(
            val expectedOwnerId: String?,
            val verifiedUserId: String,
            val email: String?,
            val isAnonymous: Boolean?,
            val emailConfirmed: Boolean,
        )
        val rejected = listOf(
            Candidate("other-owner", owner, "ada@example.com", false, true),
            Candidate(owner, "other-owner", "ada@example.com", false, true),
            Candidate(owner, owner, null, false, true),
            Candidate(owner, owner, "ada@example.com", true, true),
            Candidate(owner, owner, "ada@example.com", false, false),
        )
        rejected.forEach { case ->
            assertThrows(TravellerAccountOwnerException::class.java) {
                requireVerifiedAccountOwner(owner, case.expectedOwnerId, case.verifiedUserId,
                    case.email, case.isAnonymous, case.emailConfirmed)
            }
        }
        assertThrows(TravellerAccountOwnerException::class.java) {
            requireVerifiedAccountOwner("", null, "", "ada@example.com", false, true)
        }
    }
}
