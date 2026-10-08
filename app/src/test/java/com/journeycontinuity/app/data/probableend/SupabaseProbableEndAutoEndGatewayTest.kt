package com.journeycontinuity.app.data.probableend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupabaseProbableEndAutoEndGatewayTest {
    private val journey = "20000000-0000-4000-8000-0000000000a1"
    private val episode = "30000000-0000-4000-8000-0000000000a1"
    private val candidate = "40000000-0000-4000-8000-0000000000a1"
    private val baseline = 1_700_000_000_000L
    private val request = ProbableEndServerWindowRequest(journey, episode, candidate, 4,
        "alabarin-probable-end-v1", baseline, 7)
    private val registered = """[{"decision":"REGISTERED","presentation_episode_id":"$episode",
        "registered_at":"2023-11-14T22:13:20Z","not_before_at":"2023-11-14T22:43:20Z",
        "history_baseline_at":"2023-11-14T22:13:20Z","guard_version":7}]"""

    @Test fun registrationUsesExactServerTimesAndIdentity() = runBlocking {
        val gateway = SupabaseProbableEndAutoEndGateway({ "owner" }, { registered }, { _, _ -> error("unused") })
        assertEquals(ProbableEndRegistration.Registered(baseline, baseline + 1_800_000),
            gateway.register(request))
        assertEquals(ProbableEndRegistration.Registered(baseline, baseline + 1_800_000),
            gateway.register(request))
    }

    @Test fun malformedWrongIdentityAndClientClockClaimFailClosed() = runBlocking {
        val wrong = registered.replace(episode, "30000000-0000-4000-8000-0000000000a2")
        assertEquals(ProbableEndRegistration.Unknown,
            SupabaseProbableEndAutoEndGateway({ "owner" }, { wrong }, { _, _ -> "" }).register(request))
        val shortened = registered.replace("22:43:20", "22:42:20")
        assertEquals(ProbableEndRegistration.Unknown,
            SupabaseProbableEndAutoEndGateway({ "owner" }, { shortened }, { _, _ -> "" }).register(request))
        assertEquals(ProbableEndRegistration.Unknown,
            SupabaseProbableEndAutoEndGateway({ "owner" }, { "[]" }, { _, _ -> "" }).register(request))
        assertEquals(ProbableEndRegistration.Unknown,
            SupabaseProbableEndAutoEndGateway({ "owner" }, { "[]" }, { _, _ -> "" })
                .register(request.copy(historyBaselineAtMillis = baseline + 1)))
    }

    @Test fun offlineAuthLossAndCancellationAreDistinct() = runBlocking {
        val offline = SupabaseProbableEndAutoEndGateway({ "owner" }, { throw IllegalStateException("offline") },
            { _, _ -> throw IllegalStateException("offline") })
        assertEquals(ProbableEndRegistration.Unknown, offline.register(request))
        assertEquals(ProbableEndFinalization.Unknown, offline.finalize(journey, episode))
        var ownerReads = 0
        val changedOwner = SupabaseProbableEndAutoEndGateway({ if (++ownerReads == 1) "owner" else "other" },
            { registered }, { _, _ -> "" })
        assertEquals(ProbableEndRegistration.Unknown, changedOwner.register(request))
        val cancelled = SupabaseProbableEndAutoEndGateway({ "owner" },
            { throw CancellationException("cancel") }, { _, _ -> "" })
        try { cancelled.register(request); fail("Cancellation was swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun finalizationRequiresExactBoundedServerOutcome() = runBlocking {
        fun result(decision: String, status: String, ended: String? = null): String =
            """[{"decision":"$decision","presentation_episode_id":"$episode",
                "journey_status":"$status","ended_at":${ended?.let { "\"$it\"" } ?: "null"},
                "not_before_at":"2023-11-14T22:43:20Z"}]"""
        val endedAt = "2023-11-14T22:43:21Z"
        assertEquals(ProbableEndFinalization.Ended(baseline + 1_801_000),
            SupabaseProbableEndAutoEndGateway({ "owner" }, { registered },
                { _, _ -> result("AUTO_ENDED", "AUTO_ENDED", endedAt) }).finalize(journey, episode))
        assertEquals(ProbableEndFinalization.TooEarly(baseline + 1_800_000),
            SupabaseProbableEndAutoEndGateway({ "owner" }, { registered },
                { _, _ -> result("TOO_EARLY", "ACTIVE") }).finalize(journey, episode))
        assertEquals(ProbableEndFinalization.Blocked,
            SupabaseProbableEndAutoEndGateway({ "owner" }, { registered },
                { _, _ -> result("BLOCKED", "ACTIVE") }).finalize(journey, episode))
        assertEquals(ProbableEndFinalization.Unknown,
            SupabaseProbableEndAutoEndGateway({ "owner" }, { registered },
                { _, _ -> result("AUTO_ENDED", "COMPLETED", endedAt) }).finalize(journey, episode))
    }
}
