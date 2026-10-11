package com.journeycontinuity.app.data.repository

import android.util.Base64
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.auth.SupabaseTravellerAuthBackend
import com.journeycontinuity.app.auth.SupabaseActiveJourneyStopRemote
import com.journeycontinuity.app.auth.TravellerIdentityCoordinator
import com.journeycontinuity.app.auth.TravellerIdentityStore
import com.journeycontinuity.app.data.probableend.ProbableEndFinalization
import com.journeycontinuity.app.data.probableend.ProbableEndRegistration
import com.journeycontinuity.app.data.probableend.ProbableEndServerWindowRequest
import com.journeycontinuity.app.data.probableend.SupabaseProbableEndAutoEndGateway
import com.journeycontinuity.app.data.unresolved.AuthorityRead
import com.journeycontinuity.app.domain.IncidentReconciliationOutcome
import com.journeycontinuity.app.data.unresolved.SupabaseIncidentAuthorityGateway
import com.journeycontinuity.app.domain.UnresolvedEligibility
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.data.local.JourneyDatabase
import com.journeycontinuity.app.data.local.toEntity
import com.journeycontinuity.app.data.local.toDomain
import com.journeycontinuity.app.sync.SupabaseCloudSyncGateway
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.net.InetSocketAddress
import java.net.Socket

/** Disposable package + real local GoTrue sessions and PostgREST; no privileged key in Android. */
class LocalSupabaseAuthorityAcceptanceTest {
    private val incident = "20000000-0000-4000-8000-0000000010d1"
    private val early = "20000000-0000-4000-8000-0000000010d2"
    private val noCase = "20000000-0000-4000-8000-0000000010d3"
    private val probable = "20000000-0000-4000-8000-0000000010c4"
    private val elapsedProbable = "20000000-0000-4000-8000-0000000010f9"
    private val elapsedEpisode = "30000000-0000-4000-8000-0000000010f9"

    @Test fun section09RegisterRealWindow(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.journeycontinuity.app.localacceptance", context.packageName)
        val owner = client(requireNotNull(InstrumentationRegistry.getArguments().getString("ownerRefreshB64")))
        val ownerId = requireNotNull(owner.auth.currentSessionOrNull()?.user?.id)
        val name = "local-10c3c-section09.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
        try {
            val now = System.currentTimeMillis()
            assertTrue(db.journeyDao().insertIfNoActive(Journey(elapsedProbable,
                "Local Section 09 elapsed acceptance", now + 7_200_000L, now,
                JourneyStatus.ACTIVE, null).toEntity()))
            val cloud = SupabaseCloudSyncGateway(owner, identity(owner, ownerId),
                boundaryAdmission = { id -> db.unresolvedBoundaryDao().get(id)?.admission() })
            cloud.upsertJourney(requireNotNull(db.journeyDao().getById(elapsedProbable)).toDomain(), ownerId)
            owner.postgrest.rpc("record_journey_heartbeat", buildJsonObject {
                put("p_journey_id", elapsedProbable); put("p_sequence", 1)
                put("p_client_sent_at", Instant.now().toString())
                put("p_battery_percent", 50); put("p_charging", false)
                put("p_connectivity_state", "WIFI"); put("p_latest_telemetry_sequence", 0)
            })
            val guard = row(owner, "get_probable_end_verification_guard_v1", elapsedProbable)
            assertEquals("CLEAR", guard.getValue("decision").jsonPrimitive.content)
            val auto = SupabaseProbableEndAutoEndGateway(owner, identity(owner, ownerId))
            val registration = auto.register(ProbableEndServerWindowRequest(elapsedProbable,
                elapsedEpisode, "40000000-0000-4000-8000-0000000010f9", 1,
                "alabarin-probable-end-v1",
                Instant.parse(guard.getValue("history_baseline_at").jsonPrimitive.content).toEpochMilli(),
                guard.getValue("guard_version").jsonPrimitive.long))
            assertTrue("registration=$registration", registration is ProbableEndRegistration.Registered)
            assertTrue(auto.finalize(elapsedProbable, elapsedEpisode) is ProbableEndFinalization.TooEarly)
            assertEquals("ACTIVE", status(owner, elapsedProbable))
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById(elapsedProbable)?.status)
            assertEquals(1, db.journeyDao().getById(elapsedProbable)?.activeSlot)
            assertNull(db.probableEndPresentationDao().byId(elapsedEpisode))
            Log.i("Local10C3C", "SECTION09_REGISTERED $registration; server and Room remain ACTIVE")
        } finally { db.close(); owner.close() }
    }

    @Test fun section09FinalizeAfterRealWindow(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.journeycontinuity.app.localacceptance", context.packageName)
        val owner = client(requireNotNull(InstrumentationRegistry.getArguments().getString("ownerRefreshB64")))
        val ownerId = requireNotNull(owner.auth.currentSessionOrNull()?.user?.id)
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java,
            "local-10c3c-section09.db").build()
        try {
            assertEquals("ACTIVE", status(owner, elapsedProbable))
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById(elapsedProbable)?.status)
            assertEquals(1, db.journeyDao().getById(elapsedProbable)?.activeSlot)
            owner.postgrest.rpc("record_journey_heartbeat", buildJsonObject {
                put("p_journey_id", elapsedProbable); put("p_sequence", 2)
                put("p_client_sent_at", Instant.now().toString())
                put("p_battery_percent", 50); put("p_charging", false)
                put("p_connectivity_state", "WIFI"); put("p_latest_telemetry_sequence", 0)
            })
            val guard = row(owner, "get_probable_end_verification_guard_v1", elapsedProbable)
            Log.i("Local10C3C", "SECTION09_ELAPSED_GUARD decision=${guard.getValue("decision").jsonPrimitive.content} version=${guard.getValue("guard_version").jsonPrimitive.long}")
            val auto = SupabaseProbableEndAutoEndGateway(owner, identity(owner, ownerId))
            val result = auto.finalize(elapsedProbable, elapsedEpisode)
            if (result !is ProbableEndFinalization.Ended) {
                val raw = runCatching { owner.postgrest.rpc("finalize_probable_end_auto_end_v1", buildJsonObject {
                    put("p_journey_id", elapsedProbable); put("p_presentation_episode_id", elapsedEpisode)
                }).data }.fold({ it }, { "RPC_ERROR ${it.javaClass.simpleName}: ${it.message}" })
                fail("Section 09 finalization=$result; raw=$raw; server=${status(owner, elapsedProbable)}")
            }
            val ended = result as ProbableEndFinalization.Ended
            assertEquals("AUTO_ENDED", status(owner, elapsedProbable))
            val authority = SupabaseIncidentAuthorityGateway(owner, identity(owner, ownerId))
            val proof = (authority.autoEndProvenance(elapsedProbable) as AuthorityRead.Value).value
            assertEquals(elapsedEpisode, proof.presentationEpisodeId)
            assertEquals("AUTO_ENDED", proof.decision)
            assertEquals(ended.endedAtMillis, Instant.parse(proof.finalizedAt).toEpochMilli())
            assertTrue(Instant.parse(proof.finalizedAt) >= Instant.parse(proof.notBeforeAt))
            val cloud = SupabaseCloudSyncGateway(owner, identity(owner, ownerId),
                boundaryAdmission = { id -> db.unresolvedBoundaryDao().get(id)?.admission() })
            val server = requireNotNull(cloud.ownerJourneyState(elapsedProbable, ownerId))
            val reconciler = RoomIncidentReconciliationCoordinator(db, authority, cloud,
                SupabaseActiveJourneyStopRemote(owner, SupabaseTravellerAuthBackend(owner)),
                localOwner = { ownerId })
            assertTrue(reconciler.reconcileServerTerminal(server))
            assertEquals(JourneyStatus.AUTO_ENDED, db.journeyDao().getById(elapsedProbable)?.status)
            assertNull(db.journeyDao().getById(elapsedProbable)?.activeSlot)
            assertNotNull(db.incidentReconciliationDao().autoEndProvenance(elapsedProbable))
            assertNull(db.incidentReconciliationDao().provenance(elapsedProbable))
            assertNull(db.probableEndPresentationDao().byId(elapsedEpisode))
            Log.i("Local10C3C", "SECTION09_AUTO_ENDED endedAt=${proof.endedAt} notBefore=${proof.notBeforeAt}; Room reconciled")
        } finally { db.close(); owner.close() }
    }

    @Test fun correctOwnerRoomAdmissionAndIncidentReconciliation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.journeycontinuity.app.localacceptance", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val client = client(requireNotNull(args.getString("ownerRefreshB64")))
        val ownerId = requireNotNull(client.auth.currentSessionOrNull()?.user?.id)
        val identity = identity(client, ownerId)
        val name = "local-10c3c-incident.db"
        val id = "20000000-0000-4000-8000-0000000010f1"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
        try {
            val admitted = System.currentTimeMillis() - 3 * 86_400_000L
            assertTrue(db.journeyDao().insertIfNoActive(Journey(id, "Local owner incident",
                admitted + 3_600_000, admitted, JourneyStatus.ACTIVE, null).toEntity()))
            val originalAdmission = requireNotNull(db.unresolvedBoundaryDao().get(id)).admission()
            val cloud = SupabaseCloudSyncGateway(client, identity,
                boundaryAdmission = { journeyId -> db.unresolvedBoundaryDao().get(journeyId)?.admission() })
            cloud.upsertJourney(requireNotNull(db.journeyDao().getById(id)).toDomain(), ownerId)
            assertEquals("ACTIVE", status(client, id))
            val authority = SupabaseIncidentAuthorityGateway(client, identity)
            Log.i("Local10C3C", "INCIDENT_FIXTURE_READY")
            val claim = withTimeout(120_000) {
                var found: com.journeycontinuity.app.domain.IncidentClaim? = null
                while (found == null) {
                    found = (authority.eligibility(id) as? AuthorityRead.Value)?.value?.claim
                    if (found == null) delay(250)
                }
                requireNotNull(found)
            }
            assertEquals(JourneyStatus.ACTIVE, db.journeyDao().getById(id)?.status)
            assertEquals(originalAdmission, requireNotNull(db.unresolvedBoundaryDao().get(id)).admission())
            assertEquals("UNRESOLVED_INCIDENT",
                (authority.finalize(claim) as AuthorityRead.Value).value.decision)
            val server = requireNotNull(cloud.ownerJourneyState(id, ownerId))
            val reconciler = RoomIncidentReconciliationCoordinator(db, authority, cloud,
                SupabaseActiveJourneyStopRemote(client, SupabaseTravellerAuthBackend(client)),
                localOwner = { ownerId })
            assertTrue(reconciler.reconcileServerTerminal(server))
            assertEquals(JourneyStatus.UNRESOLVED_INCIDENT, db.journeyDao().getById(id)?.status)
            assertNull(db.journeyDao().getById(id)?.activeSlot)
            assertNotNull(db.incidentReconciliationDao().provenance(id))
            Log.i("Local10C3C", "Room admission, real watchdog case, owner incident reconciliation passed")
        } finally { db.close(); client.close() }
    }

    @Test fun offlineCompleteAndStopReconcileAfterReconnect(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.journeycontinuity.app.localacceptance", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val client = client(requireNotNull(args.getString("ownerRefreshB64")))
        val ownerId = requireNotNull(client.auth.currentSessionOrNull()?.user?.id)
        val identity = identity(client, ownerId)
        val authority = SupabaseIncidentAuthorityGateway(client, identity)
        val stopRemote = SupabaseActiveJourneyStopRemote(client, SupabaseTravellerAuthBackend(client))
        val completeId = "20000000-0000-4000-8000-0000000010e1"
        val stopId = "20000000-0000-4000-8000-0000000010e2"
        val completeName = "local-10c3c-complete.db"
        val stopName = "local-10c3c-stop.db"
        context.deleteDatabase(completeName); context.deleteDatabase(stopName)
        fun open(name: String) = Room.databaseBuilder(context, JourneyDatabase::class.java, name).build()
        var completeDb = open(completeName)
        var stopDb = open(stopName)
        try {
            val now = System.currentTimeMillis()
            val admitted = now - 3 * 86_400_000L
            assertTrue(completeDb.journeyDao().insertIfNoActive(Journey(completeId,
                "Local offline completion", admitted + 3_600_000, admitted, JourneyStatus.ACTIVE, null).toEntity()))
            assertTrue(stopDb.journeyDao().insertIfNoActive(Journey(stopId,
                "Local offline stop", admitted + 3_600_000, admitted, JourneyStatus.ACTIVE, null).toEntity()))
            for ((db, id) in listOf(completeDb to completeId, stopDb to stopId)) {
                val boundary = requireNotNull(db.operationalBoundaryDao().get(id))
                assertEquals(1, db.operationalBoundaryDao().pauseExact(id, boundary.admissionId,
                    boundary.revision, "APPROXIMATE_MONOTONIC_CAP", "INSUFFICIENT_PROOF", null))
            }
            assertNull(status(client, completeId)); assertNull(status(client, stopId))
            fun cloud(db: JourneyDatabase) = SupabaseCloudSyncGateway(client, identity,
                boundaryAdmission = { id -> db.unresolvedBoundaryDao().get(id)?.admission() })
            fun coordinator(db: JourneyDatabase) = RoomIncidentReconciliationCoordinator(db,
                authority, cloud(db), stopRemote, localOwner = { ownerId })
            val completeCoordinator = coordinator(completeDb)
            val stopCoordinator = coordinator(stopDb)
            val completeActions = TravellerAuthorityActions(completeDb, cloud(completeDb), authority,
                completeCoordinator::reconcileServerTerminal, completeCoordinator::requestStop,
                { ownerId }, {}, {})
            val stopActions = TravellerAuthorityActions(stopDb, cloud(stopDb), authority,
                stopCoordinator::reconcileServerTerminal, stopCoordinator::requestStop,
                { ownerId }, {}, {})

            Log.i("Local10C3C", "ROUTE_REMOVE_READY")
            awaitRoute(false)
            assertTrue(completeActions.complete(completeId))
            assertTrue(stopActions.stop(stopId))
            assertEquals(JourneyStatus.COMPLETED, completeDb.journeyDao().getById(completeId)?.status)
            assertEquals(1, completeDb.journeyDao().getById(completeId)?.activeSlot)
            assertEquals(JourneyStatus.ACTIVE, stopDb.journeyDao().getById(stopId)?.status)
            assertEquals("PENDING", stopDb.incidentReconciliationDao().get(stopId)?.explicitState)
            completeDb.close(); stopDb.close()
            completeDb = open(completeName); stopDb = open(stopName)
            assertEquals(JourneyStatus.COMPLETED, completeDb.journeyDao().getById(completeId)?.status)
            assertEquals("PENDING", stopDb.incidentReconciliationDao().get(stopId)?.explicitState)
            Log.i("Local10C3C", "ROUTE_RESTORE_READY")
            awaitRoute(true)
            assertNull(status(client, completeId)); assertNull(status(client, stopId))
            assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator(completeDb).advance(completeId))
            assertEquals(IncidentReconciliationOutcome.TERMINAL, coordinator(stopDb).advance(stopId))
            assertEquals("COMPLETED", status(client, completeId))
            assertEquals("CANCELLED", status(client, stopId))
            assertNull(completeDb.journeyDao().getById(completeId)?.activeSlot)
            assertNull(stopDb.journeyDao().getById(stopId)?.activeSlot)
            Log.i("Local10C3C", "offline Complete and Stop persisted, then authenticated local reconciliation passed")
        } finally {
            completeDb.close(); stopDb.close(); client.close()
        }
    }

    private suspend fun awaitRoute(connected: Boolean) = withTimeout(120_000) {
        while (routeConnected() != connected) delay(200)
    }

    private fun routeConnected(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 57421), 400) }
        true
    } catch (_: Exception) { false }

    @Test fun authenticatedAndroidToLocalAuthority(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.journeycontinuity.app.localacceptance", context.packageName)
        assertEquals("http://127.0.0.1:57421", BuildConfig.SUPABASE_URL)
        val args = InstrumentationRegistry.getArguments()
        val owner = client(requireNotNull(args.getString("ownerRefreshB64")))
        val other = client(requireNotNull(args.getString("otherRefreshB64")))
        try {
            val ownerId = requireNotNull(owner.auth.currentSessionOrNull()?.user?.id)
            val otherId = requireNotNull(other.auth.currentSessionOrNull()?.user?.id)
            assertNotEquals(ownerId, otherId)
            val authority = SupabaseIncidentAuthorityGateway(owner, identity(owner, ownerId))
            val wrongAuthority = SupabaseIncidentAuthorityGateway(other, identity(other, otherId))

            assertEquals("ACTIVE", status(owner, incident))
            val eligible = authority.eligibility(incident) as AuthorityRead.Value
            assertEquals(UnresolvedEligibility.ELIGIBLE, eligible.value.eligibility)
            val claim = requireNotNull(eligible.value.claim)
            assertEquals(UnresolvedEligibility.NOT_ELIGIBLE,
                (authority.eligibility(early) as AuthorityRead.Value).value.eligibility)
            assertEquals(UnresolvedEligibility.NOT_ELIGIBLE,
                (authority.eligibility(noCase) as AuthorityRead.Value).value.eligibility)
            assertEquals("ACTIVE", status(owner, incident)) // read cannot terminalize
            assertNull(status(other, incident)) // Journey RLS
            assertTrue(wrongAuthority.eligibility(incident) is AuthorityRead.Failed)

            // Treat the first successful response as lost, then retry the exact claim.
            val first = (authority.finalize(claim) as AuthorityRead.Value).value
            assertEquals("UNRESOLVED_INCIDENT", first.decision)
            val retry = (authority.finalize(claim) as AuthorityRead.Value).value
            assertEquals(first.endedAt, retry.endedAt)
            assertEquals(first.accessExpiresAt, retry.accessExpiresAt)
            assertEquals("UNRESOLVED_INCIDENT", status(owner, incident))
            assertEquals(first.endedAt, authority.provenance(incident).let {
                (it as AuthorityRead.Value).value.finalizedAt
            })
            assertEquals("ACTIVE", status(owner, early))
            assertEquals("ACTIVE", status(owner, noCase))
            assertTrue(wrongAuthority.finalize(claim) is AuthorityRead.Failed)
            assertHeartbeatRejected(owner, incident)

            owner.postgrest.rpc("record_journey_heartbeat", buildJsonObject {
                put("p_journey_id", probable); put("p_sequence", 1)
                put("p_client_sent_at", Instant.now().toString())
                put("p_battery_percent", 50); put("p_charging", false)
                put("p_connectivity_state", "WIFI"); put("p_latest_telemetry_sequence", 0)
            })
            val guard = row(owner, "get_probable_end_verification_guard_v1", probable)
            assertEquals("CLEAR", guard.getValue("decision").jsonPrimitive.content)
            val auto = SupabaseProbableEndAutoEndGateway(owner, identity(owner, ownerId))
            val episode = "30000000-0000-4000-8000-0000000010c4"
            val registration = auto.register(ProbableEndServerWindowRequest(probable, episode,
                "40000000-0000-4000-8000-0000000010c4", 1,
                "alabarin-probable-end-v1",
                Instant.parse(guard.getValue("history_baseline_at").jsonPrimitive.content).toEpochMilli(),
                guard.getValue("guard_version").jsonPrimitive.long))
            assertTrue(registration is ProbableEndRegistration.Registered)
            assertTrue(auto.finalize(probable, episode) is ProbableEndFinalization.TooEarly)
            assertEquals("ACTIVE", status(owner, probable))
            Log.i("Local10C3C", "owner/other Auth, eligibility, finalization, retry, provenance, RLS, cutoff, Section09 too-early passed")
        } finally {
            owner.close(); other.close()
        }
    }

    private suspend fun client(encodedRefresh: String): SupabaseClient = createSupabaseClient(
        BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
        install(Auth) { autoLoadFromStorage = false; autoSaveToStorage = false; alwaysAutoRefresh = false }
        install(Postgrest)
    }.also { client ->
        val refresh = String(Base64.decode(encodedRefresh, Base64.NO_WRAP), Charsets.UTF_8)
        client.auth.importSession(client.auth.refreshSession(refresh), autoRefresh = false)
    }

    private fun identity(client: SupabaseClient, id: String) = TravellerIdentityCoordinator(
        SupabaseTravellerAuthBackend(client), object : TravellerIdentityStore {
            override fun expectedTravellerUserId() = id
            override fun persistExpectedTravellerUserIdIfAbsent(userId: String) = userId == id
        })

    private suspend fun status(client: SupabaseClient, id: String): String? {
        val body = client.from("journeys").select {
            filter { eq("id", id) }
        }.data
        return Json.parseToJsonElement(body).jsonArray.singleOrNull()?.jsonObject
            ?.get("status")?.jsonPrimitive?.content
    }

    private suspend fun row(client: SupabaseClient, rpc: String, id: String) =
        Json.parseToJsonElement(client.postgrest.rpc(rpc, buildJsonObject {
            put("p_journey_id", id)
        }).data).jsonArray.single().jsonObject

    private suspend fun assertHeartbeatRejected(client: SupabaseClient, id: String) {
        try {
            client.postgrest.rpc("record_journey_heartbeat", buildJsonObject {
                put("p_journey_id", id); put("p_sequence", 9001)
                put("p_client_sent_at", Instant.now().toString())
                put("p_battery_percent", 50); put("p_charging", false)
                put("p_connectivity_state", "WIFI"); put("p_latest_telemetry_sequence", 0)
            })
            fail("Heartbeat restarted terminal monitoring")
        } catch (_: io.github.jan.supabase.postgrest.exception.PostgrestRestException) {
            // The real RPC rejects a terminal Journey; verify the persisted phase below.
        }
        assertEquals("UNRESOLVED_INCIDENT", status(client, id))
    }
}
