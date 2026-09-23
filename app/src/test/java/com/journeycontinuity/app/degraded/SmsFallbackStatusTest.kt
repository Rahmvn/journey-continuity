package com.journeycontinuity.app.degraded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsFallbackStatusTest {
    private val active = listOf(SmsSubscriptionChoice(7, "SIM 1"))

    @Test fun missingSendPermissionIsUnavailable() = unavailable(send = false)
    @Test fun missingPhoneStatePermissionIsUnavailable() = unavailable(phone = false)
    @Test fun missingTelephonyMessagingFeatureIsUnavailable() = unavailable(feature = false)
    @Test fun noActiveSubscriptionIsUnavailable() = unavailable(subscriptions = emptyList())
    @Test fun noSelectedSubscriptionIsUnavailable() = unavailable(selected = null)
    @Test fun staleSelectedSubscriptionIsUnavailable() = unavailable(selected = 9)
    @Test fun noDestinationIsUnavailable() = unavailable(route = false)

    @Test
    fun completeExplicitConfigurationIsAvailable() {
        assertTrue(status().ready)
    }

    @Test
    fun multipleSubscriptionsStillRequireExplicitSelection() {
        assertFalse(status(subscriptions = active + SmsSubscriptionChoice(8, "SIM 2"), selected = null).ready)
    }

    private fun unavailable(
        send: Boolean = true,
        phone: Boolean = true,
        feature: Boolean = true,
        subscriptions: List<SmsSubscriptionChoice> = active,
        selected: Int? = 7,
        route: Boolean = true,
    ) = assertFalse(status(send, phone, feature, subscriptions, selected, route).ready)

    private fun status(
        send: Boolean = true,
        phone: Boolean = true,
        feature: Boolean = true,
        subscriptions: List<SmsSubscriptionChoice> = active,
        selected: Int? = 7,
        route: Boolean = true,
    ) = evaluateSmsFallbackStatus(send, phone, feature, subscriptions, selected, route)
}
