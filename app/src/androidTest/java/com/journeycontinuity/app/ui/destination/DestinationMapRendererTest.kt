package com.journeycontinuity.app.ui.destination

import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.data.catalogue.BundledAdminCatalogue
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraUpdateFactory

/** Exercises the real OpenGL host with a local background style, never live Geoapify/hosted RPCs. */
class DestinationMapRendererTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val coordinate = MapCoordinate(8.1560008, 4.7227435)
    private val version = "alabarin-2026-10-03-v1"
    private val gateway = object : DestinationMapGateway {
        override suspend fun verify(request: MapPointRequest) = CoordinateVerification(
            BoundaryOutcome.INSIDE, OPERATIONAL_BOUNDARY_DATASET, version)
        override suspend fun viewport(regionId: String, localUnitId: String): LocalUnitViewport =
            LocalUnitViewport.Available(4.6, 8.0, 4.8, 8.3, OPERATIONAL_BOUNDARY_DATASET, version)
        override suspend fun label(request: MapPointRequest) = MapLabelResponse.Verified(
            MapLabelStatus.NO_MEANINGFUL_LABEL, version, "Offa")
    }
    private fun model(candidate: Boolean = true): DestinationMapViewModel {
        val catalogue = BundledAdminCatalogue(compose.activity).snapshot()
        return DestinationMapViewModel(catalogue, gateway, gateway, gateway, true).apply {
            open(MapRefinementRequest(catalogue.region("ng-kwara")!!, catalogue.localUnit("ng-kwara-offa")!!,
                if (candidate) PlaceSearchResult("GEOAPIFY", "seed", "Olofa Way", null, coordinate.latitude, coordinate.longitude,
                    SearchResultType.ROAD, "Kwara", "Offa", AdminTextMatch.MATCH, AdminTextMatch.MATCH,
                    SearchSuitability.NEEDS_MAP_PIN, null, null, null) else null))
        }
    }
    @Test fun realOpenGlHostLoadsPansAndKeepsNativeAttributionEnabled() {
        val vm = model()
        lateinit var container: FrameLayout
        lateinit var host: DestinationNativeMapHost
        var controlsChecked = false
        var frameRendered = false
        compose.setContent {
            val context = LocalContext.current
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            host = remember { DestinationNativeMapHost(vm) {
                Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#edf0eb"}}]}""")
            } }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event -> host.lifecycle(event) }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer); host.destroy() }
            }
            DestinationMapScreen(vm, {}, {}, {}, observeConnectivity = false, mapContent = { modifier, state ->
                AndroidView(modifier = modifier, factory = {
                    MapLibre.getInstance(context.applicationContext); Logger.setVerbosity(Logger.NONE)
                    DestinationMapTouchContainer(context).also {
                        container = it
                        host.create(context, it, lifecycle.currentState, state)
                        val view = it.getChildAt(0) as MapView
                        view.addOnDidFinishRenderingFrameListener { fully, _, _ -> if (fully) frameRendered = true }
                        view.getMapAsync { native ->
                            assertTrue(native.uiSettings.isAttributionEnabled)
                            assertFalse(native.uiSettings.isRotateGesturesEnabled)
                            assertFalse(native.uiSettings.isTiltGesturesEnabled)
                            controlsChecked = true
                        }
                    }
                }, update = { host.update(state) })
            })
        }
        compose.waitUntil(15_000) { controlsChecked && frameRendered && host.stage == NativeMapStage.READY }
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Positioning)?.phase is MapPinPhase.Inside }
        val original = (vm.state.value as DestinationMapState.Positioning).camera!!.target
        // Espresso injects native MotionEvents into AndroidView rather than Compose-only touch events.
        onView(isAssignableFrom(MapView::class.java)).perform(swipeLeft())
        compose.waitUntil(5_000) {
            val state = vm.state.value as? DestinationMapState.Positioning
            state?.phase is MapPinPhase.Inside && state.camera?.target != original
        }
        compose.runOnIdle {
            assertEquals(1, container.childCount)
            assertTrue((vm.state.value as DestinationMapState.Positioning).phase is MapPinPhase.Inside)
            val map = container.getChildAt(0) as MapView
            val description = map.context.getString(org.maplibre.android.R.string.maplibre_attributionsIconContentDescription)
            fun find(view: View): View? {
                if (view.contentDescription?.toString() == description) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            val attribution = requireNotNull(find(map))
            assertTrue(attribution.isShown); assertTrue(attribution.hasOnClickListeners())
            val origin = IntArray(2); val control = IntArray(2)
            map.getLocationOnScreen(origin); attribution.getLocationOnScreen(control)
            val rect = android.graphics.Rect(control[0] - origin[0], control[1] - origin[1],
                control[0] - origin[0] + attribution.width, control[1] - origin[1] + attribution.height)
            assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= map.width && rect.bottom <= map.height)
            val radius = (25 * map.resources.displayMetrics.density).toInt()
            assertFalse(android.graphics.Rect.intersects(rect, android.graphics.Rect(map.width / 2 - radius,
                map.height / 2 - radius, map.width / 2 + radius, map.height / 2 + radius)))
        }
        compose.onNodeWithTag("map_continue").performClick()
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Naming)?.label is MapNamingLabel.Ready }
        val frozen = (vm.state.value as DestinationMapState.Naming).point.coordinate
        onView(isAssignableFrom(MapView::class.java)).perform(swipeLeft())
        compose.runOnIdle { assertEquals(frozen, (vm.state.value as DestinationMapState.Naming).point.coordinate) }
        compose.onNodeWithTag("map_back").performClick()
        compose.runOnIdle { vm.connectivityChanged(false) }
        compose.onNodeWithTag("map_continue").assertIsEnabled()
        compose.onNodeWithTag("map_continue").performClick()
        compose.onNodeWithTag("map_use_location").assertIsEnabled()
        compose.onNodeWithTag("map_back").performClick()
        onView(isAssignableFrom(MapView::class.java)).perform(swipeLeft())
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Positioning)?.phase == MapPinPhase.OfflineUnverified }
        compose.onNodeWithTag("map_use_locality").assertIsDisplayed()
        compose.runOnIdle { vm.connectivityChanged(true) }
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Positioning)?.phase is MapPinPhase.Inside }
    }
    @Test fun missingPublicTileKeyShowsTruthfulMapErrorWithoutNativeCrash() {
        assumeTrue(BuildConfig.GEOAPIFY_MAP_TILE_KEY.isBlank())
        val vm = model()
        compose.setContent { DestinationMapScreen(vm, {}, {}, {}, observeConnectivity = false) }
        compose.waitUntil(5_000) { (vm.state.value as? DestinationMapState.Positioning)?.phase == MapPinPhase.MapLoadError }
        compose.onNodeWithTag("map_retry").assertIsDisplayed()
        compose.onNodeWithTag("map_use_locality").assertIsDisplayed()
        compose.onNodeWithTag("map_back").assertIsDisplayed()
    }
    @Test fun livePositronStyleAndAttributionWithFakeBoundaryGateway() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMapTiles") == "true" &&
            BuildConfig.GEOAPIFY_MAP_TILE_KEY.isNotBlank())
        if (InstrumentationRegistry.getArguments().getString("stylePreflight") == "true") {
            // Opt-in transport diagnosis, never emit the URL/key or provider response body.
            val response = try {
                val key = java.net.URLEncoder.encode(BuildConfig.GEOAPIFY_MAP_TILE_KEY, "UTF-8")
                val connection = java.net.URL("https://maps.geoapify.com/v1/styles/positron/style.json?apiKey=$key")
                    .openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 8_000; connection.readTimeout = 8_000
                    connection.instanceFollowRedirects = false
                    "HTTP_${connection.responseCode}"
                } finally { connection.disconnect() }
            } catch (failure: Exception) { failure.javaClass.simpleName }
            assertEquals("Device style transport", "HTTP_200", response)
        }
        val vm = model()
        compose.setContent { DestinationMapScreen(vm, {}, {}, {}, observeConnectivity = false) }
        var loadFailure = "NOT_REPORTED"
        fun find(view: View): MapView? {
            if (view is MapView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        compose.runOnIdle {
            find(compose.activity.window.decorView)?.addOnDidFailLoadingMapListener { message ->
                loadFailure = when {
                    Regex("\\b[45][0-9]{2}\\b").containsMatchIn(message) -> "HTTP_REJECTED"
                    message.contains("SSL", true) || message.contains("TLS", true) -> "TLS_FAILURE"
                    message.contains("parse", true) -> "STYLE_PARSE_FAILURE"
                    message.contains("resolve", true) -> "DNS_FAILURE"
                    else -> "STYLE_LOAD_FAILURE"
                }
            }
        }
        try {
            compose.waitUntil(25_000) { (vm.state.value as? DestinationMapState.Positioning)?.phase is MapPinPhase.Inside }
        } catch (failure: Exception) {
            throw AssertionError("Style initialization: $loadFailure; phase=" +
                (vm.state.value as? DestinationMapState.Positioning)?.phase?.javaClass?.simpleName, failure)
        }
        var rendered = false
        compose.runOnIdle {
            val map = requireNotNull(find(compose.activity.window.decorView))
            map.addOnDidFinishRenderingFrameListener { fully, _, _ -> if (fully) rendered = true }
            map.getMapAsync { native ->
                assertTrue(native.uiSettings.isAttributionEnabled)
                val credit = native.style!!.sources.joinToString(" ") { it.attribution.orEmpty() }
                assertTrue("Geoapify source credit absent", credit.contains("Geoapify", true))
                assertTrue("OpenStreetMap source credit absent", credit.contains("OpenStreetMap", true))
                assertTrue("OpenMapTiles source credit absent", credit.contains("OpenMapTiles", true))
                native.moveCamera(CameraUpdateFactory.zoomTo(native.cameraPosition.zoom + 0.1))
            }
        }
        compose.waitUntil(25_000) { rendered }
        val description = compose.activity.getString(org.maplibre.android.R.string.maplibre_attributionsIconContentDescription)
        onView(withContentDescription(description)).check(matches(isDisplayed())).perform(click())
        for (source in listOf("Geoapify", "OpenStreetMap", "OpenMapTiles"))
            onView(withText(containsString(source))).check(matches(isDisplayed()))
        pressBack()
        if (InstrumentationRegistry.getArguments().getString("captureMap") == "true") {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val target = java.io.File(compose.activity.cacheDir, "map-pin-live.png")
            target.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        val general = model(false)
        compose.runOnIdle { compose.activity.setContent {
            DestinationMapScreen(general, {}, {}, {}, observeConnectivity = false)
        } }
        compose.waitUntil(25_000) { (general.state.value as? DestinationMapState.Positioning)?.camera != null }
        compose.onNodeWithTag("map_continue").assertIsNotEnabled()
        onView(isAssignableFrom(MapView::class.java)).perform(swipeLeft())
        compose.waitUntil(5_000) { (general.state.value as? DestinationMapState.Positioning)?.phase is MapPinPhase.Inside }
        compose.onNodeWithTag("map_continue").assertIsEnabled()
        compose.onNodeWithTag("map_continue").performClick()
        compose.waitUntil(5_000) { (general.state.value as? DestinationMapState.Naming)?.label is MapNamingLabel.Ready }
        compose.runOnIdle { general.updateName("My map pin") }
        val frozen = (general.state.value as DestinationMapState.Naming).point.coordinate
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DestinationMapScreen(general, {}, {}, {}, observeConnectivity = false)
        } }
        var restoredMap: org.maplibre.android.maps.MapLibreMap? = null
        var styleLoaded = false
        var targetMatches = false
        try {
            compose.waitUntil(25_000) {
                compose.runOnIdle {
                    if (restoredMap == null) find(compose.activity.window.decorView)?.getMapAsync { restoredMap = it }
                    restoredMap?.let { native ->
                        val target = native.cameraPosition.target
                        styleLoaded = native.style?.isFullyLoaded == true
                        targetMatches = target != null && MapCoordinate(target.latitude, target.longitude) == frozen
                    }
                }
                styleLoaded && targetMatches
            }
        } catch (failure: Exception) {
            throw AssertionError("Native restoration: styleLoaded=$styleLoaded; targetMatches=$targetMatches; stage=" +
                general.state.value.javaClass.simpleName, failure)
        }
        compose.runOnIdle {
            assertEquals("My map pin", (general.state.value as DestinationMapState.Naming).name)
            assertEquals(frozen, (general.state.value as DestinationMapState.Naming).point.coordinate)
        }
        compose.onNodeWithTag("map_back").performClick()
        compose.onNodeWithTag("map_continue").assertIsEnabled()
    }
}
