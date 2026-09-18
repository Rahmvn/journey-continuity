package com.journeycontinuity.app.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.journeycontinuity.app.domain.ConnectivityState
import kotlin.math.roundToInt

data class BatterySnapshot(
    val percent: Int?,
    val isCharging: Boolean?,
)

class DeviceContextReader(private val context: Context) {
    fun battery(): BatterySnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatterySnapshot(percent = null, isCharging = null)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            (level * 100.0 / scale).roundToInt().coerceIn(0, 100)
        } else {
            null
        }
        val charging = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            -> false
            else -> null
        }
        return BatterySnapshot(percent = percent, isCharging = charging)
    }

    fun connectivity(): ConnectivityState {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork ?: return ConnectivityState.NONE
        val capabilities = manager.getNetworkCapabilities(active) ?: return ConnectivityState.UNKNOWN
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectivityState.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectivityState.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectivityState.OTHER
            else -> ConnectivityState.UNKNOWN
        }
    }

    fun usableInternet(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
