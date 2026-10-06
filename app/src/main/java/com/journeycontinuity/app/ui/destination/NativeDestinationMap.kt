package com.journeycontinuity.app.ui.destination

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.doOnLayout
import com.journeycontinuity.app.BuildConfig
import com.journeycontinuity.app.telemetry.DeviceContextReader
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable internal fun ObserveDestinationMapConnectivity(viewModel: DestinationMapViewModel) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(viewModel, context, lifecycle) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val reader = DeviceContextReader(context)
        val handler = Handler(Looper.getMainLooper())
        var active = true
        val update = Runnable { if (active) viewModel.connectivityChanged(reader.usableInternet()) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { handler.post(update) }
            override fun onLost(network: Network) { handler.post(update) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { handler.post(update) }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) update.run() }
        lifecycle.addObserver(observer)
        update.run()
        onDispose {
            active = false
            handler.removeCallbacks(update)
            lifecycle.removeObserver(observer)
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}

@Composable internal fun NativeDestinationMap(
    modifier: Modifier, state: DestinationMapState, viewModel: DestinationMapViewModel,
) {
    val positioning = state as? DestinationMapState.Positioning
    val naming = state as? DestinationMapState.Naming
    val plan = positioning?.initialCamera ?: naming?.initialCamera
    val attempt = positioning?.renderAttempt ?: naming?.renderAttempt ?: 0
    if (plan == null || positioning?.phase == MapPinPhase.InvalidContext) return
    if (BuildConfig.GEOAPIFY_MAP_TILE_KEY.isBlank()) {
        LaunchedEffect(attempt) { viewModel.mapLoadFailed() }
        Text("Map unavailable", modifier)
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    key(attempt) {
        // Only the composition owns native objects. No Bundle/SavedState/disk camera restoration.
        val host = remember { DestinationNativeMapHost(viewModel) }
        DisposableEffect(host, lifecycle) {
            val observer = LifecycleEventObserver { _, event -> host.lifecycle(event) }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); host.destroy() }
        }
        LaunchedEffect(host) {
            delay(15_000)
            if (host.stage == NativeMapStage.LOADING || host.stage == NativeMapStage.POSITIONING) viewModel.mapLoadFailed()
        }
        AndroidView(modifier = modifier,
            factory = {
                val container = DestinationMapTouchContainer(context)
                try {
                    MapLibre.getInstance(context.applicationContext)
                    // Native transport errors can contain credential-bearing tile URLs.
                    Logger.setVerbosity(Logger.NONE)
                    host.create(context, container, lifecycle.currentState, state)
                } catch (_: Exception) { container.post { viewModel.mapLoadFailed() } }
                container
            }, update = { host.update(state) })
    }
}

internal enum class NativeMapStage { LOADING, POSITIONING, READY, DESTROYED }

internal class DestinationMapTouchContainer(context: android.content.Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Pan/zoom belongs to the native viewport, even inside the short-screen scroller.
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP &&
            event.actionMasked != MotionEvent.ACTION_CANCEL)
        return super.dispatchTouchEvent(event)
    }
}

private fun geoapifyDestinationStyle(): Style.Builder {
    val key = java.net.URLEncoder.encode(BuildConfig.GEOAPIFY_MAP_TILE_KEY, "UTF-8")
    return Style.Builder().fromUri("https://maps.geoapify.com/v1/styles/positron/style.json?apiKey=$key")
}

/** Screen-owned MapView and listeners; lifetime ends with the composition. */
internal class DestinationNativeMapHost(
    private val viewModel: DestinationMapViewModel,
    // Internal renderer seam permits a no-network background-style device test; production uses Geoapify only.
    private val styleBuilder: () -> Style.Builder = ::geoapifyDestinationStyle,
) {
    var stage = NativeMapStage.LOADING
        private set
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var current: DestinationMapState = DestinationMapState.Closed
    private var started = false
    private var resumed = false
    private var context: android.content.Context? = null
    private var memoryRegistered = false
    private val moveStarted = MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) viewModel.userCameraMoveStarted()
    }
    private val moving = MapLibreMap.OnCameraMoveListener {
        map?.cameraPosition?.target?.let { viewModel.cameraMoving(MapCoordinate(it.latitude, it.longitude)) }
    }
    private val idle = MapLibreMap.OnCameraIdleListener {
        if (stage == NativeMapStage.READY) map?.cameraPosition?.let { camera -> camera.target?.let {
            viewModel.cameraIdle(MapCamera(MapCoordinate(it.latitude, it.longitude), camera.zoom))
        } }
    }
    private val failure = MapView.OnDidFailLoadingMapListener { _ ->
        if (stage != NativeMapStage.DESTROYED) viewModel.mapLoadFailed()
    }
    private var cameraInitialized = false
    private val rendered = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
        // A loaded style alone does not prove that the initial tiles/renderer are usable.
        if (fully && cameraInitialized && stage == NativeMapStage.POSITIONING) {
            stage = NativeMapStage.READY
            idle.onCameraIdle()
        }
    }
    private val memory = object : ComponentCallbacks {
        override fun onConfigurationChanged(configuration: Configuration) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() { view?.onLowMemory() }
    }

    fun create(context: android.content.Context, container: FrameLayout, lifecycle: Lifecycle.State,
        state: DestinationMapState) {
        this.context = context
        current = state
        val mapView = MapView(context)
        view = mapView
        container.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        mapView.onCreate(null)
        mapView.addOnDidFailLoadingMapListener(failure)
        mapView.addOnDidFinishRenderingFrameListener(rendered)
        context.registerComponentCallbacks(memory)
        memoryRegistered = true
        if (lifecycle.isAtLeast(Lifecycle.State.STARTED)) lifecycle(Lifecycle.Event.ON_START)
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) lifecycle(Lifecycle.Event.ON_RESUME)
        mapView.getMapAsync { native ->
            if (stage == NativeMapStage.DESTROYED) return@getMapAsync
            try {
                map = native
                native.setMinZoomPreference(0.0)
                native.setMaxZoomPreference(22.0)
                native.uiSettings.apply {
                    isAttributionEnabled = true
                    attributionGravity = Gravity.BOTTOM or Gravity.RIGHT
                    isCompassEnabled = false
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                }
                native.addOnCameraMoveStartedListener(moveStarted)
                native.addOnCameraMoveListener(moving)
                native.addOnCameraIdleListener(idle)
                native.setStyle(styleBuilder()) {
                    if (stage == NativeMapStage.DESTROYED) return@setStyle
                    stage = NativeMapStage.POSITIONING
                    applyCamera()
                    update(current)
                }
            } catch (_: Exception) { viewModel.mapLoadFailed() }
        }
    }

    fun update(state: DestinationMapState) {
        val wasNaming = current is DestinationMapState.Naming
        current = state
        map?.uiSettings?.setAllGesturesEnabled(state !is DestinationMapState.Naming)
        // setAllGesturesEnabled includes rotation/tilt; always keep those disabled.
        map?.uiSettings?.isRotateGesturesEnabled = false
        map?.uiSettings?.isTiltGesturesEnabled = false
        if (state is DestinationMapState.Naming && !wasNaming && stage == NativeMapStage.READY) applyCamera()
    }
    private fun applyCamera() {
        val native = map ?: return
        val positioning = current as? DestinationMapState.Positioning
        val naming = current as? DestinationMapState.Naming
        val camera = positioning?.camera ?: naming?.camera
        val initial = positioning?.initialCamera ?: naming?.initialCamera
        val remembered = camera ?: (initial as? InitialMapCamera.Seed)?.camera
        view?.doOnLayout {
            if (stage == NativeMapStage.DESTROYED) return@doOnLayout
            try {
                if (remembered != null) {
                    native.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                        .target(LatLng(remembered.target.latitude, remembered.target.longitude))
                        .zoom(remembered.zoom).bearing(0.0).tilt(0.0).build()))
                } else if (initial is InitialMapCamera.Bounds) {
                    val bounds = initial.viewport
                    native.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder()
                        .include(LatLng(bounds.north, bounds.west)).include(LatLng(bounds.south, bounds.east)).build(), 24))
                }
                cameraInitialized = true
                if (stage == NativeMapStage.READY) idle.onCameraIdle()
            } catch (_: Exception) { viewModel.mapLoadFailed() }
        }
    }
    fun lifecycle(event: Lifecycle.Event) {
        val native = view ?: return
        when (event) {
            Lifecycle.Event.ON_START -> if (!started) { native.onStart(); started = true }
            Lifecycle.Event.ON_RESUME -> if (!resumed) { native.onResume(); resumed = true }
            Lifecycle.Event.ON_PAUSE -> if (resumed) { native.onPause(); resumed = false }
            Lifecycle.Event.ON_STOP -> if (started) { native.onStop(); started = false }
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }
    fun destroy() {
        if (stage == NativeMapStage.DESTROYED) return
        stage = NativeMapStage.DESTROYED
        map?.removeOnCameraMoveStartedListener(moveStarted)
        map?.removeOnCameraMoveListener(moving)
        map?.removeOnCameraIdleListener(idle)
        view?.removeOnDidFailLoadingMapListener(failure)
        view?.removeOnDidFinishRenderingFrameListener(rendered)
        if (memoryRegistered) context?.unregisterComponentCallbacks(memory)
        memoryRegistered = false
        lifecycle(Lifecycle.Event.ON_PAUSE)
        lifecycle(Lifecycle.Event.ON_STOP)
        view?.onDestroy()
        view = null; map = null; context = null
    }
}
