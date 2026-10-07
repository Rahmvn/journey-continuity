package com.journeycontinuity.app.data.probableend

import com.journeycontinuity.app.auth.TravellerAuthException
import com.journeycontinuity.app.auth.TravellerAuthFailureKind
import com.journeycontinuity.app.domain.probableend.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SupabaseProbableEndVerificationGuardGatewayTest {
    private val journey = "20000000-0000-4000-8000-000000000091"
    private val otherJourney = "20000000-0000-4000-8000-000000000092"
    private fun row(version: Long = 0, reason: String = "VERIFICATION_CLEAR") = buildJsonObject {
        put("journey_id", journey); put("journey_active", reason != "JOURNEY_NOT_ACTIVE")
        put("decision", if (reason == "VERIFICATION_CLEAR") "CLEAR" else "BLOCKED")
        put("reason", reason)
        put("verification_state", when (reason) {
            "VERIFICATION_CLEAR" -> "CLEAR"
            "VERIFYING", "OPEN_VERIFICATION_CASE" -> "UNRESOLVED"
            else -> "UNKNOWN"
        })
        put("guard_version", version); put("history_baseline_at", "2026-10-07T09:00:00Z")
        put("evidence_not_before", "2026-10-07T09:00:00Z")
        put("evaluated_at", "2026-10-07T10:00:00Z")
        put("fresh_until", if (reason == "VERIFICATION_CLEAR") JsonPrimitive("2026-10-07T10:04:00Z") else JsonNull)
    }
    private fun body(row: JsonObject) = JsonArray(listOf(row)).toString()
    private fun changed(row: JsonObject = row(), key: String, value: JsonElement?) = JsonObject(row.toMutableMap().apply {
        if (value == null) remove(key) else put(key, value)
    })
    private fun gateway(payload: String = body(row())) = SupabaseProbableEndVerificationGuardGateway({ "owner" }) { payload }
    private suspend fun available(gateway: SupabaseProbableEndVerificationGuardGateway,
        previous: AuthoritativeVerificationGuard? = null) =
        (gateway.read(journey, previous) as VerificationGuardRead.Available).guard
    private suspend fun invalid(payload: String) {
        assertEquals(VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.INVALID_RESPONSE), gateway(payload).read(journey))
    }

    @Test fun clearAndAllBoundedBlockedReasonsParse() = runTest {
        val clear = available(gateway())
        assertEquals(journey, clear.journeyId)
        assertEquals(VerificationGuardDecision.CLEAR, clear.decision)
        assertEquals(VerificationKnowledge.CLEAR, clear.verification)
        assertEquals(0L, clear.version)
        for (reason in VerificationGuardReason.entries.filter { it != VerificationGuardReason.VERIFICATION_CLEAR }) {
            val blocked = available(gateway(body(row(3, reason.name))))
            assertEquals(VerificationGuardDecision.BLOCKED, blocked.decision)
            assertEquals(reason, blocked.reason)
            assertNull(blocked.freshUntilMillis)
        }
    }
    @Test fun exactJourneyRequestAndExistingOwnerAreCheckedBeforeAndAfterTransport() = runTest {
        var ownerChecks = 0
        val gateway = SupabaseProbableEndVerificationGuardGateway({ ownerChecks++; "owner" }) {
            assertEquals(journey, it); body(row())
        }
        available(gateway)
        assertEquals(2, ownerChecks)
    }
    @Test fun absentOrFailedAuthenticationNeverCallsTransport() = runTest {
        var calls = 0
        for (owner in listOf<suspend () -> String?>({ null }, {
            throw TravellerAuthException(TravellerAuthFailureKind.IDENTITY_NOT_ESTABLISHED, "private token")
        })) {
            val result = SupabaseProbableEndVerificationGuardGateway(owner) { calls++; body(row()) }.read(journey)
            assertEquals(VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.AUTHENTICATION), result)
            assertFalse(result.toString().contains("private token"))
        }
        assertEquals(0, calls)
    }
    @Test fun ownerChangeDuringReadCannotProvideClearance() = runTest {
        var checks = 0
        val gateway = SupabaseProbableEndVerificationGuardGateway({ if (checks++ == 0) "owner-a" else "owner-b" }) { body(row()) }
        assertEquals(VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.AUTHENTICATION), gateway.read(journey))
    }
    @Test fun networkBackendAndRpcAuthFailuresAreUnknownWithoutErrorContents() = runTest {
        for (error in listOf(IOException("private network data"), IllegalStateException("backend unavailable"),
            TravellerAuthException(TravellerAuthFailureKind.TEMPORARY_UNAVAILABLE, "token"))) {
            val gateway = SupabaseProbableEndVerificationGuardGateway({ "owner" }) { throw error }
            val result = gateway.read(journey)
            assertTrue(result is VerificationGuardRead.Unknown)
            assertFalse(result.toString().contains(error.message!!))
        }
    }
    @Test fun cancellationIsNotSwallowed() = runTest {
        val gateway = SupabaseProbableEndVerificationGuardGateway({ "owner" }) { throw CancellationException() }
        try { gateway.read(journey); fail("Expected cancellation") } catch (_: CancellationException) { }
    }
    @Test fun malformedAndNonSingleRowResponsesFailClosed() = runTest {
        for (payload in listOf("not json", "{}", "null", "[]", "[${row()},${row()}]")) invalid(payload)
    }
    @Test fun wrongJourneyAndInvalidRequestFailClosed() = runTest {
        invalid(body(changed(key = "journey_id", value = JsonPrimitive(otherJourney))))
        for (id in listOf("", "not-a-uuid", "2-0-4-8-91")) assertTrue(gateway().read(id) is VerificationGuardRead.Unknown)
    }
    @Test fun absentNullNegativeOrNonIntegralVersionFailsClosed() = runTest {
        for (version in listOf(null, JsonNull, JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("1"))) {
            invalid(body(changed(key = "guard_version", value = version)))
        }
    }
    @Test fun missingOrInvalidTimestampsFailClosed() = runTest {
        for (key in listOf("evaluated_at", "history_baseline_at", "evidence_not_before")) {
            for (value in listOf(null, JsonNull, JsonPrimitive("infinity"), JsonPrimitive("1970-01-01T00:00:00Z"))) {
                invalid(body(changed(key = key, value = value)))
            }
        }
        invalid(body(changed(key = "evidence_not_before", value = JsonPrimitive("2026-10-07T10:00:01Z"))))
        invalid(body(changed(key = "history_baseline_at", value = JsonPrimitive("2026-10-07T09:30:00Z"))))
    }
    @Test fun inconsistentDecisionKnowledgeAndFreshnessFailClosed() = runTest {
        for ((key, value) in listOf("decision" to JsonPrimitive("UNKNOWN"), "reason" to JsonPrimitive("OPEN_VERIFICATION_CASE"),
            "journey_active" to JsonPrimitive(false), "verification_state" to JsonPrimitive("UNKNOWN"),
            "journey_active" to JsonPrimitive("true"),
            "fresh_until" to JsonNull, "fresh_until" to JsonPrimitive("2026-10-07T10:00:00Z"))) {
            invalid(body(changed(key = key, value = value)))
        }
        invalid(body(changed(row(1, "VERIFYING"), "verification_state", JsonPrimitive("UNKNOWN"))))
        invalid(body(changed(row(1, "VERIFYING"), "fresh_until", JsonPrimitive("2026-10-07T10:04:00Z"))))
        invalid(body(changed(row(1, "VERIFYING"), "decision", JsonPrimitive("CLEAR"))))
    }
    @Test fun missingAuthoritativeStateWithNoVersionRemainsUnknown() = runTest {
        invalid(body(changed(row(0, "MISSING_MONITORING_STATE"), "guard_version", JsonNull)))
    }
    @Test fun verificationOpeningAndResolutionCannotRestoreOldVersionEligibility() = runTest {
        val before = available(gateway(body(row(7))))
        val verifying = available(gateway(body(row(9, "VERIFYING"))), before)
        val resolved = available(gateway(body(row(9))), verifying)
        assertEquals(VerificationHistoryChange.INTERVENING_VERIFICATION, resolved.historySince(before))
        assertEquals(VerificationGuardDecision.CLEAR, resolved.decision)
        assertEquals(VerificationHistoryChange.UNCHANGED, resolved.historySince(verifying))
        assertEquals(before, available(gateway(body(row(7))), before))
    }
    @Test fun firstClearIsUnboundEvenAfterUnobservedOpeningAndResolution() = runTest {
        // No earlier authoritative receipt exists for the candidate. Neither zero
        // nor an advanced cursor proves continuity across its already-old evidence.
        for (version in listOf(0L, 9L)) {
            val first = available(gateway(body(row(version))))
            assertEquals(VerificationGuardDecision.CLEAR, first.decision)
            assertEquals(VerificationHistoryChange.UNBOUND_BASELINE, first.historySince(null))
            assertEquals(VerificationHistoryChange.UNCHANGED, first.historySince(first))
            // UNCHANGED describes these reads only, never a candidate binding.
        }
    }
    @Test fun regressedVersionReplacedBaselineOlderReadOrWrongPreviousJourneyAreUnknown() = runTest {
        val previous = available(gateway(body(row(7))))
        for (old in listOf(previous.copy(version = 8), previous.copy(journeyId = otherJourney),
            previous.copy(historyBaselineAtMillis = previous.historyBaselineAtMillis - 1),
            previous.copy(evaluatedAtMillis = previous.evaluatedAtMillis + 1),
            previous.copy(evidenceNotBeforeMillis = previous.evidenceNotBeforeMillis + 1))) {
            assertEquals(VerificationGuardRead.Unknown(VerificationGuardUnavailableReason.INCONSISTENT_HISTORY), gateway(body(row(7))).read(journey, old))
        }
    }
    @Test fun unexpectedSensitiveFieldsAreRejectedAndNeverRetained() = runTest {
        for (key in listOf("latitude", "longitude", "destination", "provider", "report", "token")) {
            invalid(body(changed(key = key, value = JsonPrimitive("PRIVATE CONTENT"))))
        }
        assertFalse(available(gateway()).toString().contains(journey))
        assertFalse(AuthoritativeVerificationGuard::class.java.declaredFields.any {
            it.name in setOf("latitude", "longitude", "destination", "provider", "report", "token", "body")
        })
    }
}
