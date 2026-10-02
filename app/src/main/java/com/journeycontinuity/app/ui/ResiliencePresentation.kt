package com.journeycontinuity.app.ui

import com.journeycontinuity.app.degraded.SmsFallbackStatus

enum class ResiliencePage { STATUS, CHOOSE_SIM }

internal enum class ResilienceCondition {
    READY, NO_CONTACTS, SMS_PERMISSION, PHONE_ACCESS, NO_ACTIVE_SIM,
    NO_SIM_SELECTED, SELECTED_SIM_UNAVAILABLE, UNAVAILABLE, CONTACTS_UNAVAILABLE,
}

internal data class ResiliencePresentation(
    val condition: ResilienceCondition,
    val overall: String,
    val body: String,
    val contactsValue: String,
    val contactsAction: String?,
    val fallbackValue: String,
    val simValue: String,
    val simAction: String?,
    val permissionValue: String,
    val permissionAction: String?,
    val noteTitle: String? = null,
    val note: String,
    val heightDp: Int,
) {
    val homeSummary: String get() = overall
}

internal fun resiliencePresentation(
    status: SmsFallbackStatus,
    contactsAvailability: TrustedContactsAvailability,
    acceptedCount: Int,
): ResiliencePresentation {
    val contactsKnown = contactsAvailability == TrustedContactsAvailability.AVAILABLE
    val contactsValue = when {
        !contactsKnown && contactsAvailability == TrustedContactsAvailability.LOADING -> "Checking contacts"
        !contactsKnown -> "Currently unavailable"
        acceptedCount == 0 -> "None accepted"
        else -> "$acceptedCount available"
    }
    val selected = status.activeSubscriptions.firstOrNull {
        it.subscriptionId == status.selectedSubscriptionId
    }
    val savedLabel = status.selectedSubscriptionLastKnownLabel?.trim()?.takeIf { it.isNotEmpty() }
    val selectedUnavailable = status.selectedSubscriptionId != null && selected == null &&
        status.phoneStatePermissionGranted
    val simValue = when {
        !status.phoneStatePermissionGranted -> "Phone access needed"
        selectedUnavailable -> savedLabel?.let { "$it unavailable" } ?: "Selected SIM unavailable"
        status.activeSubscriptions.isEmpty() -> "No active SIM"
        status.selectedSubscriptionId == null -> "Not selected"
        else -> selected?.safeDisplayName ?: "Selected SIM unavailable"
    }
    val condition = when {
        !status.destinationConfigured || !status.telephonyMessagingSupported -> ResilienceCondition.UNAVAILABLE
        !contactsKnown -> ResilienceCondition.CONTACTS_UNAVAILABLE
        acceptedCount == 0 -> ResilienceCondition.NO_CONTACTS
        !status.sendPermissionGranted -> ResilienceCondition.SMS_PERMISSION
        !status.phoneStatePermissionGranted -> ResilienceCondition.PHONE_ACCESS
        selectedUnavailable -> ResilienceCondition.SELECTED_SIM_UNAVAILABLE
        status.activeSubscriptions.isEmpty() -> ResilienceCondition.NO_ACTIVE_SIM
        status.selectedSubscriptionId == null -> ResilienceCondition.NO_SIM_SELECTED
        status.ready -> ResilienceCondition.READY
        else -> ResilienceCondition.NO_SIM_SELECTED
    }
    val unavailable = condition == ResilienceCondition.UNAVAILABLE
    val fallbackValue = when {
        unavailable -> "Unavailable"
        status.ready -> "Ready"
        else -> "Setup needed"
    }
    val contactsAction = if (!unavailable && contactsKnown && acceptedCount == 0) "Add contact" else null
    val simAction = when {
        unavailable -> null
        !status.phoneStatePermissionGranted -> "Allow access"
        selectedUnavailable -> if (status.activeSubscriptions.isNotEmpty()) "Choose another" else null
        status.activeSubscriptions.isEmpty() -> null
        status.selectedSubscriptionId == null -> "Choose SIM"
        else -> null
    }
    val permissionAction = if (!unavailable && !status.sendPermissionGranted) "Allow SMS" else null
    val permissionValue = if (status.sendPermissionGranted) "Allowed" else "Not allowed"
    val (overall, body, note, noteTitle, heightDp) = when (condition) {
        ResilienceCondition.READY -> Summary("Ready",
            "Your current setup supports Alabarin's resilience path.",
            "These settings support outward continuity when normal internet communication is limited.",
            heightDp = 500)
        ResilienceCondition.NO_CONTACTS -> Summary("Setup needed",
            "Alabarin can still monitor Journeys, but your current setup has reduced outward resilience.",
            "A Journey can still start without an accepted trusted contact.", heightDp = 530)
        ResilienceCondition.SMS_PERMISSION -> Summary("Setup needed",
            "Alabarin can still monitor Journeys, but device SMS fallback cannot be used with the current permission.",
            "Allowing SMS enables Alabarin's device fallback path. It does not change when Journey monitoring starts.",
            heightDp = 560)
        ResilienceCondition.PHONE_ACCESS -> Summary("Setup needed",
            "Allow phone access so Alabarin can inspect the available SIMs for device SMS fallback.",
            "This access does not start Journey monitoring or select a SIM.", heightDp = 560)
        ResilienceCondition.NO_ACTIVE_SIM -> Summary("Setup needed",
            "No active SIM is available for device SMS fallback.",
            "You can still start a Journey without device SMS fallback.", heightDp = 560)
        ResilienceCondition.NO_SIM_SELECTED -> Summary("Setup needed",
            "Choose which SIM Alabarin may use for device SMS fallback.",
            "Alabarin will not silently use a default SIM or switch to another SIM.", heightDp = 560)
        ResilienceCondition.SELECTED_SIM_UNAVAILABLE -> Summary("Setup needed",
            "Your selected SIM is not currently available for fallback.",
            "The saved SIM choice is kept. Alabarin will not automatically switch to another SIM.",
            heightDp = 580)
        ResilienceCondition.UNAVAILABLE -> Summary("Unavailable",
            if (!status.destinationConfigured)
                "Device SMS fallback is not available in the current Alabarin configuration."
            else "Device SMS fallback is not available on this device.",
            "There is no setting you can fix on this screen. You can still start a Journey, and Alabarin can monitor it locally.",
            noteTitle = "No action needed here", heightDp = 580)
        ResilienceCondition.CONTACTS_UNAVAILABLE -> Summary("Setup needed",
            "Trusted-contact readiness cannot currently be confirmed.",
            "You can still start a Journey while this information is unavailable.", heightDp = 530)
    }
    return ResiliencePresentation(condition, overall, body, contactsValue, contactsAction,
        fallbackValue, simValue, simAction, permissionValue, permissionAction,
        noteTitle, note, heightDp)
}

private data class Summary(
    val overall: String,
    val body: String,
    val note: String,
    val noteTitle: String? = null,
    val heightDp: Int,
)
