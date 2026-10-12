package com.journeycontinuity.app.ui.destination

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.journeycontinuity.app.domain.AdminCatalogue
import com.journeycontinuity.app.domain.DestinationSelectionSource
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DestinationMapViewModelTest {
    private val catalogue by lazy { AdminCatalogue.parse(listOf(
        File("src/main/assets/catalogue/ng_admin_v2.json"), File("app/src/main/assets/catalogue/ng_admin_v2.json")
    ).first { it.isFile }.readBytes()) }
    private val coordinate = MapCoordinate(8.1560008, 4.7227435)
    private val other = MapCoordinate(8.1998492, 4.6316354)
    private val version = "alabarin-2026-10-03-v1"
    private val attribution = ProviderAttribution("OSM", "OpenStreetMap contributors", "ODbL", "https://osm.org")
    private fun result(outcome: BoundaryOutcome = BoundaryOutcome.INSIDE) = CoordinateVerification(outcome,
        if (outcome in setOf(BoundaryOutcome.UNAVAILABLE, BoundaryOutcome.INVALID_CONTEXT)) null else OPERATIONAL_BOUNDARY_DATASET,
        if (outcome in setOf(BoundaryOutcome.UNAVAILABLE, BoundaryOutcome.INVALID_CONTEXT)) null else version)
    private fun request(candidate: Boolean = true) = MapRefinementRequest(catalogue.region("ng-kwara")!!,
        catalogue.localUnit("ng-kwara-offa")!!, if (!candidate) null else PlaceSearchResult(
            "GEOAPIFY", "road-seed", "Olofa Way", null, coordinate.latitude, coordinate.longitude,
            SearchResultType.ROAD, "Kwara", "Offa", AdminTextMatch.MATCH, AdminTextMatch.MATCH,
            SearchSuitability.NEEDS_MAP_PIN, null, null, attribution))
    private inner class Fake : DestinationMapGateway {
        val verified = mutableListOf<MapPointRequest>()
        val labelled = mutableListOf<MapPointRequest>()
        val viewed = mutableListOf<Pair<String, String>>()
        var verify: suspend (MapPointRequest) -> CoordinateVerification = { result() }
        var viewport: LocalUnitViewport = LocalUnitViewport.Available(4.6, 8.0, 4.8, 8.3, OPERATIONAL_BOUNDARY_DATASET, version)
        var label: suspend () -> MapLabelResponse = { MapLabelResponse.Verified(MapLabelStatus.NO_MEANINGFUL_LABEL, version, "Offa") }
        override suspend fun verify(request: MapPointRequest): CoordinateVerification { verified += request; return verify.invoke(request) }
        override suspend fun viewport(regionId: String, localUnitId: String): LocalUnitViewport { viewed += regionId to localUnitId; return viewport }
        override suspend fun label(request: MapPointRequest): MapLabelResponse { labelled += request; return label.invoke() }
    }
    private fun vm(fake: Fake, online: Boolean = true) = DestinationMapViewModel(catalogue, fake, fake, fake, online)
    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }
    private fun DestinationMapViewModel.position() = state.value as DestinationMapState.Positioning
    private fun DestinationMapViewModel.naming() = state.value as DestinationMapState.Naming
    private fun TestScope.inside(model: DestinationMapViewModel) {
        model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
        assertTrue(model.position().phase is MapPinPhase.Inside)
    }

    @Test fun candidateSeedIsCameraOnlyAndOnlyRestingCoordinateIsVerified() = test {
        val fake = Fake(); val model = vm(fake)
        model.open(request())
        assertEquals(coordinate, (model.position().initialCamera as InitialMapCamera.Seed).camera.target)
        assertEquals(MapPinPhase.ReadyUnverified, model.position().phase)
        model.continueToNaming(); assertTrue(model.state.value is DestinationMapState.Positioning)
        assertTrue(fake.viewed.isEmpty()); assertTrue(fake.verified.isEmpty())
        model.cameraIdle(MapCamera(other, 15.0)); advanceTimeBy(399); runCurrent()
        assertTrue(fake.verified.isEmpty()); advanceUntilIdle()
        assertEquals(other, fake.verified.single().coordinate)
    }
    @Test fun generalEntryFitsBoundsButRequiresDeliberatePositioning() = test {
        val fake = Fake(); val model = vm(fake); model.open(request(false)); advanceUntilIdle()
        assertEquals(listOf("ng-kwara" to "ng-kwara-offa"), fake.viewed)
        assertTrue(model.position().initialCamera is InitialMapCamera.Bounds)
        model.cameraIdle(MapCamera(coordinate, 12.0)); advanceUntilIdle()
        assertTrue(fake.verified.isEmpty()); model.continueToNaming()
        assertEquals(MapPinPhase.ReadyUnverified, model.position().phase)
        model.userCameraMoveStarted(); model.cameraIdle(MapCamera(coordinate, 12.0)); advanceUntilIdle()
        assertTrue(model.position().phase is MapPinPhase.Inside)
    }
    @Test fun viewportInvalidAndUnavailableStaySeparateFromPointVerification() = test {
        for ((response, expected) in listOf(LocalUnitViewport.InvalidContext to MapPinPhase.InvalidContext,
            LocalUnitViewport.Unavailable to MapPinPhase.MapLoadError)) {
            val fake = Fake().apply { viewport = response }; val model = vm(fake)
            model.open(request(false)); advanceUntilIdle(); assertEquals(expected, model.position().phase)
            assertTrue(fake.verified.isEmpty()); assertNull(model.finish()); model.close()
        }
    }
    @Test fun wrongCanonicalParentFailsClosedBeforeAnyGateway() = test {
        val fake = Fake(); val model = vm(fake)
        model.open(request().copy(region = catalogue.region("ng-lagos")!!)); advanceUntilIdle()
        assertEquals(MapPinPhase.InvalidContext, model.position().phase)
        assertTrue(fake.verified.isEmpty()); assertTrue(fake.viewed.isEmpty()); assertNull(model.locality())
        model.mapLoadFailed()
        assertEquals(MapPinPhase.InvalidContext, model.position().phase); assertNull(model.locality())
    }
    @Test fun allAuthoritativeOutcomesMapToExactTypedStates() = test {
        for ((outcome, expected) in listOf(
            BoundaryOutcome.OUTSIDE_SELECTED_UNIT to MapPinPhase.OutsideSelectedArea,
            BoundaryOutcome.BOUNDARY_AMBIGUOUS to MapPinPhase.BoundaryAmbiguous,
            BoundaryOutcome.COVERAGE_UNRESOLVED to MapPinPhase.CoverageUnresolved,
            BoundaryOutcome.UNAVAILABLE to MapPinPhase.VerificationUnavailable,
            BoundaryOutcome.INVALID_CONTEXT to MapPinPhase.InvalidContext)) {
            val fake = Fake().apply { verify = { result(outcome) } }; val model = vm(fake)
            model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
            assertEquals(expected, model.position().phase); model.continueToNaming()
            assertTrue(fake.labelled.isEmpty()); assertNull(model.finish()); model.close()
        }
    }
    @Test fun malformedInsideEvidenceAndVerifierErrorsNeverEnableContinue() = test {
        for (evidence in listOf(CoordinateVerification(BoundaryOutcome.INSIDE, "NG_ADMIN", version),
            CoordinateVerification(BoundaryOutcome.INSIDE, OPERATIONAL_BOUNDARY_DATASET, null),
            CoordinateVerification(BoundaryOutcome.INSIDE, OPERATIONAL_BOUNDARY_DATASET, " bad "))) {
            val fake = Fake().apply { verify = { evidence } }; val model = vm(fake)
            model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
            assertEquals(MapPinPhase.VerificationUnavailable, model.position().phase); model.close()
        }
    }
    @Test fun movementImmediatelyInvalidatesProofButSameProgrammaticCentrePreservesIt() = test {
        val fake = Fake(); val model = vm(fake); inside(model)
        val proof = (model.position().phase as MapPinPhase.Inside).point
        model.cameraMoving(coordinate); model.cameraIdle(MapCamera(coordinate, 14.0)); advanceUntilIdle()
        assertSame(proof, (model.position().phase as MapPinPhase.Inside).point); assertEquals(1, fake.verified.size)
        model.userCameraMoveStarted(); assertEquals(MapPinPhase.Moving, model.position().phase)
        model.continueToNaming(); assertTrue(model.state.value is DestinationMapState.Positioning)
        model.cameraIdle(MapCamera(other, 14.0)); advanceUntilIdle()
        assertEquals(other, (model.position().phase as MapPinPhase.Inside).point.coordinate)
    }
    @Test fun harmlessGestureAtSameCentreRestoresExactProofWithoutReverification() = test {
        val fake = Fake(); val model = vm(fake); inside(model)
        val proof = (model.position().phase as MapPinPhase.Inside).point
        model.userCameraMoveStarted()
        assertEquals(MapPinPhase.Moving, model.position().phase)
        model.cameraMoving(coordinate)
        model.cameraIdle(MapCamera(coordinate, 14.0)); advanceUntilIdle()
        assertSame(proof, (model.position().phase as MapPinPhase.Inside).point)
        assertEquals(1, fake.verified.size)
        model.userCameraMoveStarted()
        model.cameraMoving(other)
        model.cameraIdle(MapCamera(other, 14.0)); advanceUntilIdle()
        assertEquals(other, (model.position().phase as MapPinPhase.Inside).point.coordinate)
        assertEquals(2, fake.verified.size)
    }
    @Test fun actualProgrammaticCoordinateChangeInvalidatesProof() = test {
        val model = vm(Fake()); inside(model); model.cameraMoving(other)
        assertEquals(MapPinPhase.Moving, model.position().phase)
    }
    @Test fun repeatedIdleForPendingCoordinateKeepsOneVerificationAndLatestCamera() = test {
        for (failRequest in listOf(false, true)) {
            val pending = mutableListOf<Continuation<CoordinateVerification>>()
            val fake = Fake().apply { verify = { suspendCoroutine { pending += it } } }
            val model = vm(fake)
            model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
            // Native initialization/recreation may report the same resting target again.
            model.cameraIdle(MapCamera(coordinate, 14.0)); advanceUntilIdle()
            assertEquals(1, fake.verified.size)
            assertTrue(model.position().phase is MapPinPhase.Checking)
            if (failRequest) pending.single().resumeWithException(IllegalStateException())
            else pending.single().resume(result())
            runCurrent()
            assertEquals(MapCamera(coordinate, 14.0), model.position().camera)
            if (failRequest) assertEquals(MapPinPhase.VerificationUnavailable, model.position().phase)
            else assertTrue(model.position().phase is MapPinPhase.Inside)
            model.close()
        }
    }
    @Test fun staleVerifierSuccessCannotValidateNewMovingPoint() = test {
        val pending = mutableListOf<Continuation<CoordinateVerification>>()
        val fake = Fake().apply { verify = { suspendCoroutine { pending += it } } }; val model = vm(fake)
        model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
        model.userCameraMoveStarted(); pending[0].resume(result()); runCurrent()
        assertEquals(MapPinPhase.Moving, model.position().phase)
        model.cameraIdle(MapCamera(other, 15.0)); advanceUntilIdle()
        pending[1].resume(result(BoundaryOutcome.OUTSIDE_SELECTED_UNIT)); runCurrent()
        assertEquals(MapPinPhase.OutsideSelectedArea, model.position().phase)
    }
    @Test fun staleVerifierErrorCannotOverwriteNewerSuccess() = test {
        val pending = mutableListOf<Continuation<CoordinateVerification>>()
        val fake = Fake().apply { verify = { suspendCoroutine { pending += it } } }; val model = vm(fake)
        model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
        model.userCameraMoveStarted(); model.cameraIdle(MapCamera(other, 15.0)); advanceUntilIdle()
        pending[1].resume(result()); runCurrent(); pending[0].resumeWithException(IllegalStateException()); runCurrent()
        assertEquals(other, (model.position().phase as MapPinPhase.Inside).point.coordinate)
    }
    @Test fun retryHasIndependentGenerationAndDoesNotRestorePreviousProof() = test {
        val pending = mutableListOf<Continuation<CoordinateVerification>>()
        val fake = Fake().apply { verify = { suspendCoroutine { pending += it } } }; val model = vm(fake)
        model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
        model.cameraIdle(MapCamera(other, 15.0)); advanceUntilIdle()
        pending[1].resume(result(BoundaryOutcome.UNAVAILABLE)); runCurrent(); model.retry(); advanceUntilIdle()
        pending[0].resume(result()); runCurrent(); assertTrue(model.position().phase is MapPinPhase.Checking)
        pending[2].resume(result()); runCurrent()
        assertEquals(other, (model.position().phase as MapPinPhase.Inside).point.coordinate)
    }
    @Test fun offlineBeforeVerificationBlocksPointAndReconnectionChecksCurrentPin() = test {
        val fake = Fake(); val model = vm(fake, false)
        model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
        assertEquals(MapPinPhase.OfflineUnverified, model.position().phase); assertTrue(fake.verified.isEmpty())
        model.connectivityChanged(true); advanceUntilIdle(); assertTrue(model.position().phase is MapPinPhase.Inside)
    }
    @Test fun connectivityLossWithoutMovementRetainsInsideAndAllowsOfflineNaming() = test {
        val fake = Fake(); val model = vm(fake); inside(model)
        val proof = (model.position().phase as MapPinPhase.Inside).point
        model.connectivityChanged(false); assertSame(proof, (model.position().phase as MapPinPhase.Inside).point)
        model.continueToNaming(); assertTrue(fake.labelled.isEmpty())
        val draft = model.finish()!!; assertEquals(coordinate.latitude, draft.destination.latitude!!, 0.0)
        assertEquals("Pinned location in Offa", draft.displayLabel)
    }
    @Test fun movingVerifiedPointOfflineBlocksOldProof() = test {
        val fake = Fake(); val model = vm(fake); inside(model); model.connectivityChanged(false)
        model.userCameraMoveStarted(); model.cameraIdle(MapCamera(other, 15.0)); advanceUntilIdle()
        assertEquals(MapPinPhase.OfflineUnverified, model.position().phase); assertEquals(1, fake.verified.size)
        model.continueToNaming(); assertTrue(model.state.value is DestinationMapState.Positioning)
    }
    @Test fun reconnectionDuringMotionDoesNotVerifyOldCamera() = test {
        val fake = Fake(); val model = vm(fake); inside(model); model.userCameraMoveStarted()
        model.connectivityChanged(false); model.connectivityChanged(true); advanceUntilIdle()
        assertEquals(MapPinPhase.Moving, model.position().phase); assertEquals(1, fake.verified.size)
        model.cameraIdle(MapCamera(other, 15.0)); advanceUntilIdle(); assertEquals(2, fake.verified.size)
    }
    @Test fun namingNormalizesBlankAndCustomWithoutMutatingCoordinate() = test {
        for ((input, custom, label) in listOf(Triple("  ", null, "Pinned location in Offa"),
            Triple("  Grandma’s house  ", "Grandma’s house", "Grandma’s house, Offa"))) {
            val fake = Fake(); val model = vm(fake); inside(model); model.continueToNaming(); advanceUntilIdle()
            model.updateName(input); val draft = model.finish()!!
            assertEquals(custom, draft.destination.customPlaceName); assertEquals(label, draft.displayLabel)
            assertEquals(coordinate.latitude, draft.destination.latitude!!, 0.0)
            assertEquals(coordinate.longitude, draft.destination.longitude!!, 0.0)
            assertEquals(request().localUnit.type, draft.destination.localUnitType)
            assertEquals(DestinationSelectionSource.MAP_PIN, draft.destination.selectionSource)
            assertEquals(version, draft.boundaryDatasetVersion); assertEquals(OPERATIONAL_BOUNDARY_DATASET, draft.boundaryDatasetId)
            assertNull(draft.attribution); assertNull(draft.destination.provider); assertNull(draft.destination.providerPlaceId)
            assertEquals(coordinate, fake.labelled.single().coordinate)
        }
    }
    @Test fun namingBoundsUseUnicodeCodePointsAndRejectControls() = test {
        val model = vm(Fake()); inside(model); model.continueToNaming(); advanceUntilIdle()
        model.updateName("😀".repeat(160)); assertNull(model.naming().issue)
        model.updateName("  " + "😀".repeat(160) + "  "); assertNull(model.naming().issue)
        model.updateName("😀".repeat(161)); assertEquals(MapNameIssue.TOO_LONG, model.naming().issue); assertNull(model.finish())
        for (control in listOf("\n", "\u007f", "\u0085")) {
            model.updateName("Place$control"); assertEquals(MapNameIssue.CONTROL_CHARACTERS, model.naming().issue)
            assertNull(model.finish())
        }
    }
    @Test fun specificLabelAndCustomOverrideRetainGenuineProvenance() = test {
        val evidence = MapLabelEvidence("Summit University", "Offa, Nigeria", MapLabelResultType.PLACE,
            "GEOAPIFY", null, 12.0, attribution)
        for (custom in listOf("", "My campus")) {
            val fake = Fake().apply { label = { MapLabelResponse.Verified(MapLabelStatus.SPECIFIC_LABEL, version, "Offa", evidence) } }
            val model = vm(fake); inside(model); model.continueToNaming(); advanceUntilIdle(); model.updateName(custom)
            val draft = model.finish()!!
            assertEquals(if (custom.isEmpty()) "Summit University, Offa" else "My campus, Offa", draft.displayLabel)
            assertEquals(evidence, draft.reverseLabelEvidence); assertEquals(attribution, draft.attribution)
            assertEquals("GEOAPIFY", draft.destination.provider); assertNull(draft.destination.providerPlaceId)
        }
    }
    @Test fun everyNonSpecificLabelStatusUsesTruthfulGenericFallback() = test {
        for (status in listOf(MapLabelStatus.BROAD_ONLY, MapLabelStatus.NO_MEANINGFUL_LABEL, MapLabelStatus.PROVIDER_UNAVAILABLE)) {
            val fake = Fake().apply { label = { MapLabelResponse.Verified(status, version, "Offa") } }
            val model = vm(fake); inside(model); model.continueToNaming(); advanceUntilIdle()
            assertEquals("Pinned location in Offa", model.finish()!!.displayLabel)
        }
    }
    @Test fun labelNetworkErrorDoesNotInvalidateInside() = test {
        val fake = Fake().apply { label = { throw DestinationMapException(DestinationMapFailure.UNAVAILABLE) } }
        val model = vm(fake); inside(model); model.continueToNaming(); advanceUntilIdle()
        assertEquals("Pinned location in Offa", model.finish()!!.displayLabel)
    }
    @Test fun malformedServerLabelContractBlocksFrozenPointRatherThanClaimingProviderFailure() = test {
        val fake = Fake().apply { label = { throw DestinationMapException(DestinationMapFailure.INVALID_RESPONSE) } }
        val model = vm(fake); inside(model); model.continueToNaming(); advanceUntilIdle()
        assertEquals(MapPinPhase.VerificationUnavailable, model.position().phase)
        assertNull(model.finish())
    }
    @Test fun serverLabelReverificationFailureBlocksFrozenPoint() = test {
        for (outcome in BoundaryOutcome.entries.filter { it != BoundaryOutcome.INSIDE }) {
            val fake = Fake().apply { label = { MapLabelResponse.VerificationFailed(result(outcome)) } }
            val model = vm(fake); inside(model); model.continueToNaming(); advanceUntilIdle()
            assertTrue(model.state.value is DestinationMapState.Positioning); assertNull(model.finish())
            assertFalse(model.position().phase is MapPinPhase.Inside); model.close()
        }
    }
    @Test fun backFromNamingPreservesProofAndLateLabelFailureCannotChangeIt() = test {
        lateinit var continuation: Continuation<MapLabelResponse>
        val fake = Fake().apply { label = { suspendCoroutine { continuation = it } } }
        val model = vm(fake); inside(model); model.continueToNaming(); runCurrent(); assertTrue(model.back())
        continuation.resume(MapLabelResponse.VerificationFailed(result(BoundaryOutcome.OUTSIDE_SELECTED_UNIT))); runCurrent()
        assertEquals(coordinate, model.position().camera!!.target)
        assertTrue(model.position().phase is MapPinPhase.Inside); assertFalse(model.back())
    }
    @Test fun localityFallbackHasNoCoordinateAndNeverInventsPointEvidence() = test {
        val fake = Fake().apply { verify = { result(BoundaryOutcome.COVERAGE_UNRESOLVED) } }; val model = vm(fake)
        model.open(request()); model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle()
        val draft = model.locality()!!; assertNull(draft.destination.latitude); assertNull(draft.destination.longitude)
        assertEquals(DestinationSelectionSource.LOCALITY_SELECTION, draft.destination.selectionSource)
        assertEquals(catalogue.metadata.version, draft.destination.adminDatasetVersion)
    }
    @Test fun retainedStoreKeepsNamingAndNoNativeTypesAreStoredInViewModel() = test {
        val fake = Fake(); val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val factory = DestinationMapViewModelFactory(catalogue, fake, true)
        val model = ViewModelProvider(owner, factory)[DestinationMapViewModel::class.java]
        inside(model); model.continueToNaming(); advanceUntilIdle(); model.updateName("My house")
        assertSame(model, ViewModelProvider(owner, factory)[DestinationMapViewModel::class.java])
        assertEquals("My house", model.naming().name); assertEquals(coordinate, model.naming().point.coordinate)
        assertFalse(DestinationMapViewModel::class.java.declaredFields.any {
            it.type.name.startsWith("android.") || it.type.name.startsWith("org.maplibre.")
        })
        owner.viewModelStore.clear()
    }
    @Test fun rendererFailureAndRetryStaySeparateFromVerifierUnavailable() = test {
        val model = vm(Fake()); model.open(request()); model.mapLoadFailed()
        assertEquals(MapPinPhase.MapLoadError, model.position().phase); model.retry()
        assertEquals(1, model.position().renderAttempt); assertFalse(model.position().phase is MapPinPhase.Inside)
        model.cameraIdle(MapCamera(coordinate, 15.0)); advanceUntilIdle(); assertTrue(model.position().phase is MapPinPhase.Inside)
    }
    @Test fun placeSearchDraftStillRequiresItsSearchProvenance() = test {
        val search = request().candidate!!.copy(resultType = SearchResultType.PLACE,
            suitability = SearchSuitability.SELECTABLE_POINT, boundaryOutcome = BoundaryOutcome.INSIDE,
            boundaryDatasetVersion = version)
        val draft = exactPointDraft(request().region, request().localUnit, catalogue.metadata.version, search)
        assertEquals(DestinationSelectionSource.PLACE_SEARCH, draft.destination.selectionSource)
        assertEquals(attribution, draft.attribution)
        for (mutation in listOf<() -> DestinationDraft.ExactPoint>(
            { draft.copy(attribution = null) },
            { draft.copy(destination = draft.destination.copy(providerPlaceId = null)) },
            { draft.copy(destination = draft.destination.copy(provider = null, providerPlaceId = null)) })) {
            try { mutation(); fail("Search provenance must remain mandatory") } catch (_: IllegalArgumentException) { }
        }
    }
}
