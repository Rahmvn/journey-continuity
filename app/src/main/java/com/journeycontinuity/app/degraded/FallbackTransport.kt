package com.journeycontinuity.app.degraded

data class SmsFallbackRoute(val destination: String) {
    init {
        require(E164.matches(destination)) { "SMS fallback destination must be E.164." }
    }

    private companion object {
        val E164 = Regex("^\\+[1-9][0-9]{7,14}$")
    }
}

fun interface SmsFallbackRouteProvider {
    fun currentRoute(): SmsFallbackRoute?
}

object UnconfiguredSmsFallbackRouteProvider : SmsFallbackRouteProvider {
    override fun currentRoute(): SmsFallbackRoute? = null
}

data class SmsSubscriptionChoice(val subscriptionId: Int, val safeDisplayName: String)

data class SmsFallbackStatus(
    val sendPermissionGranted: Boolean,
    val phoneStatePermissionGranted: Boolean,
    val telephonyMessagingSupported: Boolean,
    val activeSubscriptions: List<SmsSubscriptionChoice>,
    val selectedSubscriptionId: Int?,
    val destinationConfigured: Boolean,
    val ready: Boolean,
    val unavailableReason: String?,
)

fun evaluateSmsFallbackStatus(
    sendPermissionGranted: Boolean,
    phoneStatePermissionGranted: Boolean,
    telephonyMessagingSupported: Boolean,
    activeSubscriptions: List<SmsSubscriptionChoice>,
    selectedSubscriptionId: Int?,
    destinationConfigured: Boolean,
): SmsFallbackStatus {
    val reason = when {
        !telephonyMessagingSupported -> "SMS messaging is not supported on this device."
        !sendPermissionGranted -> "SMS permission is not granted."
        !phoneStatePermissionGranted -> "Phone state permission is not granted."
        activeSubscriptions.isEmpty() -> "No active SIM subscription is available."
        selectedSubscriptionId == null -> "Select the SIM used for fallback SMS."
        activeSubscriptions.none { it.subscriptionId == selectedSubscriptionId } ->
            "The selected SIM is no longer active; reselect it."
        !destinationConfigured -> "No SMS fallback destination is configured."
        else -> null
    }
    return SmsFallbackStatus(
        sendPermissionGranted,
        phoneStatePermissionGranted,
        telephonyMessagingSupported,
        activeSubscriptions,
        selectedSubscriptionId,
        destinationConfigured,
        reason == null,
        reason,
    )
}

sealed interface SmsTransportResolution {
    data class Available(val subscriptionId: Int, val route: SmsFallbackRoute) : SmsTransportResolution
    data class Unavailable(val safeReason: String) : SmsTransportResolution
}

interface SmsFallbackConfiguration {
    fun status(): SmsFallbackStatus
    fun resolveForSend(): SmsTransportResolution
    fun selectSubscription(subscriptionId: Int): Boolean
}

data class SmsHandoffRequest(
    val localAttemptId: Long,
    val handoffGeneration: Int,
    val subscriptionId: Int,
    val destination: String,
    val exactPersistedText: String,
)

interface SmsTelephonyGateway {
    fun divideMessage(subscriptionId: Int, text: String): List<String>
    fun send(request: SmsHandoffRequest)
}

interface FallbackHandoffScheduler {
    fun scheduleUncertainCheck(localAttemptId: Long, generation: Int, delayMillis: Long)
    fun scheduleRetry(localAttemptId: Long, delayMillis: Long)
}

object NoOpFallbackHandoffScheduler : FallbackHandoffScheduler {
    override fun scheduleUncertainCheck(localAttemptId: Long, generation: Int, delayMillis: Long) = Unit
    override fun scheduleRetry(localAttemptId: Long, delayMillis: Long) = Unit
}

sealed interface FallbackHandoffResult {
    data object SubmittedAwaitingCallback : FallbackHandoffResult
    data object NotReady : FallbackHandoffResult
    data object AlreadyHandled : FallbackHandoffResult
    data class Unavailable(val safeReason: String) : FallbackHandoffResult
    data class Rejected(val safeReason: String) : FallbackHandoffResult
}

const val ANDROID_SMS_HANDED_OFF_MEANING =
    "Android telephony reported successful SMS send handoff."
