package com.journeycontinuity.app.ui

import com.journeycontinuity.app.degraded.SmsSubscriptionChoice
import com.journeycontinuity.app.degraded.evaluateSmsFallbackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResiliencePresentationTest {
    private val sim = SmsSubscriptionChoice(7, "SIM 1 - MTN")

    @Test fun productionRouteUnavailabilityDominatesOtherSetupGapsAndHome() {
        val result = present(route = false, contacts = 0, send = false, selected = null)
        assertEquals(ResilienceCondition.UNAVAILABLE, result.condition)
        assertEquals("Unavailable", result.homeSummary)
        assertEquals("Unavailable", result.fallbackValue)
        assertEquals("Device SMS fallback is not available in the current Alabarin configuration.", result.body)
        assertNull(result.contactsAction)
        assertNull(result.permissionAction)
        assertNull(result.simAction)
    }

    @Test fun noAcceptedContactsNeedsSetupButDoesNotBlockJourney() {
        val result = present(contacts = 0)
        assertEquals(ResilienceCondition.NO_CONTACTS, result.condition)
        assertEquals("Setup needed", result.homeSummary)
        assertEquals("None accepted", result.contactsValue)
        assertEquals("Add contact", result.contactsAction)
        assertEquals("Ready", result.fallbackValue)
        assertEquals("A Journey can still start without an accepted trusted contact.", result.note)
    }

    @Test fun smsPermissionIsSeparateFromPhoneAccess() {
        val sms = present(send = false, phone = false)
        assertEquals(ResilienceCondition.SMS_PERMISSION, sms.condition)
        assertEquals("Not allowed", sms.permissionValue)
        assertEquals("Allow SMS", sms.permissionAction)
        assertEquals("Phone access needed", sms.simValue)
        val phone = present(phone = false)
        assertEquals(ResilienceCondition.PHONE_ACCESS, phone.condition)
        assertEquals("Allowed", phone.permissionValue)
        assertNull(phone.permissionAction)
        assertEquals("Phone access needed", phone.simValue)
        assertEquals("Allow access", phone.simAction)
    }

    @Test fun noSimChoiceNeverUsesSystemDefault() {
        val result = present(selected = null,
            subscriptions = listOf(sim, SmsSubscriptionChoice(8, "SIM 2 - Airtel")))
        assertEquals(ResilienceCondition.NO_SIM_SELECTED, result.condition)
        assertEquals("Not selected", result.simValue)
        assertEquals("Choose SIM", result.simAction)
    }

    @Test fun staleSelectedSimUsesLastKnownLabelOrSafeGenericValue() {
        val labeled = present(selected = 9, savedLabel = "SIM 2 - Airtel")
        assertEquals(ResilienceCondition.SELECTED_SIM_UNAVAILABLE, labeled.condition)
        assertEquals("SIM 2 - Airtel unavailable", labeled.simValue)
        assertEquals("Choose another", labeled.simAction)
        val older = present(selected = 9)
        assertEquals("Selected SIM unavailable", older.simValue)
    }

    @Test fun savedSimWithNoActiveSubscriptionsIsStillUnavailableRatherThanUnselected() {
        val result = present(selected = 7, savedLabel = "SIM 1 - MTN", subscriptions = emptyList())
        assertEquals(ResilienceCondition.SELECTED_SIM_UNAVAILABLE, result.condition)
        assertEquals("SIM 1 - MTN unavailable", result.simValue)
        assertNull(result.simAction)
    }

    @Test fun completeRouteAndAcceptedContactAreReadyWithoutDeliveryClaim() {
        val result = present()
        assertEquals(ResilienceCondition.READY, result.condition)
        assertEquals("Ready", result.homeSummary)
        assertEquals("Ready", result.fallbackValue)
        assertEquals("SIM 1 - MTN", result.simValue)
        assertEquals("Allowed", result.permissionValue)
    }

    @Test fun unconfirmedContactsNeverAppearAsReady() {
        val result = present(availability = TrustedContactsAvailability.UNAVAILABLE)
        assertEquals(ResilienceCondition.CONTACTS_UNAVAILABLE, result.condition)
        assertEquals("Setup needed", result.homeSummary)
        assertEquals("Currently unavailable", result.contactsValue)
    }

    private fun present(
        route: Boolean = true,
        contacts: Int = 1,
        availability: TrustedContactsAvailability = TrustedContactsAvailability.AVAILABLE,
        send: Boolean = true,
        phone: Boolean = true,
        subscriptions: List<SmsSubscriptionChoice> = listOf(sim),
        selected: Int? = 7,
        savedLabel: String? = null,
    ) = resiliencePresentation(evaluateSmsFallbackStatus(send, phone, true,
        subscriptions, selected, route, savedLabel), availability, contacts)
}
