package com.journeycontinuity.app.degraded

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSelectionPersistenceTest {
    @Test fun explicitChoicePersistsIdAndLabelAndRetainsBothAfterSimDisappears() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("resilience-selection-test", Context.MODE_PRIVATE)
        try {
            assertTrue(preferences.edit().clear().commit())
            val choices = listOf(SmsSubscriptionChoice(7, "SIM 1 - MTN"),
                SmsSubscriptionChoice(8, "SIM 2 - Airtel"))
            assertFalse(persistExplicitSmsSelection(preferences, choices, 9))
            assertFalse(preferences.contains(KEY_SELECTED_SUBSCRIPTION))
            assertTrue(persistExplicitSmsSelection(preferences, choices, 7))
            assertEquals(7, preferences.getInt(KEY_SELECTED_SUBSCRIPTION, -1))
            assertEquals("SIM 1 - MTN", preferences.getString(KEY_SELECTED_SUBSCRIPTION_LABEL, null))

            val isolated = object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int) = preferences
            }
            val afterRemoval = AndroidSmsFallbackConfiguration(isolated, UnconfiguredSmsFallbackRouteProvider)
                .status()
            assertEquals(7, afterRemoval.selectedSubscriptionId)
            assertEquals("SIM 1 - MTN", afterRemoval.selectedSubscriptionLastKnownLabel)
            assertTrue(persistExplicitSmsSelection(preferences, choices.drop(1), 8))
            assertEquals(8, preferences.getInt(KEY_SELECTED_SUBSCRIPTION, -1))
            assertEquals("SIM 2 - Airtel", preferences.getString(KEY_SELECTED_SUBSCRIPTION_LABEL, null))
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun oneOrManySimsDoNotSelectWithoutExplicitChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("resilience-no-default-test", Context.MODE_PRIVATE)
        try {
            assertTrue(preferences.edit().clear().commit())
            val one = listOf(SmsSubscriptionChoice(7, "SIM 1 - MTN"))
            assertFalse(preferences.contains(KEY_SELECTED_SUBSCRIPTION))
            assertFalse(evaluateSmsFallbackStatus(true, true, true, one, null, true).ready)
            assertFalse(evaluateSmsFallbackStatus(true, true, true,
                one + SmsSubscriptionChoice(8, "SIM 2 - Airtel"), null, true).ready)
            assertFalse(preferences.contains(KEY_SELECTED_SUBSCRIPTION))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
