package com.journeycontinuity.app.heartbeat

import com.journeycontinuity.app.domain.ConnectivityState
import com.journeycontinuity.app.domain.DeviceHeartbeat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SupabaseHeartbeatGatewayTest {
    @Test
    fun rpcPayloadPreservesAvailableBatteryContext() {
        val payload = heartbeat(batteryPercent = 29, isCharging = true).toRpcParameters()

        assertEquals(29, payload.getValue("p_battery_percent").jsonPrimitive.int)
        assertEquals(true, payload.getValue("p_charging").jsonPrimitive.boolean)
    }

    @Test
    fun rpcPayloadPreservesUnavailableBatteryContextAsNull() {
        val payload = heartbeat(batteryPercent = null, isCharging = null).toRpcParameters()

        assertSame(JsonNull, payload.getValue("p_battery_percent"))
        assertSame(JsonNull, payload.getValue("p_charging"))
    }

    private fun heartbeat(batteryPercent: Int?, isCharging: Boolean?) = DeviceHeartbeat(
        journeyId = "20000000-0000-0000-0000-000000000001",
        sequence = 4,
        clientSentAt = 1_789_000_000_000,
        batteryPercent = batteryPercent,
        isCharging = isCharging,
        connectivity = ConnectivityState.CELLULAR,
        latestTelemetrySequence = 12,
    )
}
