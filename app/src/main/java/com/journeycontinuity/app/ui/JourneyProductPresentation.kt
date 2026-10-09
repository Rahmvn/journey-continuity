package com.journeycontinuity.app.ui

import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.service.CurrentJourneyConnectivity
import com.journeycontinuity.app.ui.destination.DestinationDraft
import com.journeycontinuity.app.ui.destination.toJourneyStartRequest

internal fun canSubmitStructuredJourneyStart(draft: DestinationDraft?, arrivalAt: Long?, now: Long): Boolean =
    arrivalAt != null && com.journeycontinuity.app.domain.JourneyEtaPolicy.validate(now, arrivalAt) ==
        com.journeycontinuity.app.domain.JourneyEtaPolicy.Validation.VALID && draft != null &&
        runCatching { draft.toJourneyStartRequest(arrivalAt) }.isSuccess

internal fun canSubmitJourneyStart(destination: String, arrivalAt: Long?, now: Long): Boolean =
    destination.isNotBlank() && arrivalAt != null &&
        com.journeycontinuity.app.domain.JourneyEtaPolicy.validate(now, arrivalAt) ==
        com.journeycontinuity.app.domain.JourneyEtaPolicy.Validation.VALID

internal fun homeJourneyAction(journey: Journey?): String =
    if (journey == null) "Start Journey" else "Open Journey"

internal fun needsResilienceCheckpoint(acceptedContact: Boolean, smsRouteReady: Boolean): Boolean =
    !acceptedContact || !smsRouteReady

internal fun routeAfterJourneyChange(route: JourneyProductRoute, active: Journey?): JourneyProductRoute =
    when {
        active == null && route == JourneyProductRoute.ACTIVE -> JourneyProductRoute.HOME
        active != null && route in setOf(JourneyProductRoute.START, JourneyProductRoute.CHECKPOINT) ->
            JourneyProductRoute.HOME
        else -> route
    }

internal enum class JourneyConnectivityPresentation(val title: String?, val detail: String?) {
    NORMAL(null, null),
    CHECKING_CONNECTION(
        "Checking connection",
        "Alabarin is checking this Journey's current connection.",
    ),
    LIMITED_FALLBACK_AVAILABLE(
        "Limited connectivity",
        "SMS fallback is available if normal internet communication is limited.",
    ),
    LIMITED_FALLBACK_UNAVAILABLE(
        "Limited connectivity",
        "Fresh information may not reach trusted contacts until communication is restored.",
    ),
    RECOVERING(
        "Connection restored",
        "Alabarin is checking cloud communication while the connection stabilizes.",
    ),
}

internal fun travellerConnectivityPresentation(
    journeyId: String,
    state: DegradedConnectivityState?,
    current: CurrentJourneyConnectivity?,
    smsFallbackStatus: SmsFallbackStatus,
    hasAcceptedContact: Boolean,
): JourneyConnectivityPresentation {
    val persisted = state?.takeIf { it.journeyId == journeyId && it.journeyActive }
    val reconciled = current?.takeIf { it.journeyId == journeyId }
        ?: return JourneyConnectivityPresentation.CHECKING_CONNECTION
    val fallbackAvailable = smsFallbackStatus.destinationConfigured &&
        smsFallbackStatus.ready && hasAcceptedContact &&
        persisted?.fallbackBindingProvisioned == true
    val limited = if (fallbackAvailable) JourneyConnectivityPresentation.LIMITED_FALLBACK_AVAILABLE
        else JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE
    return when {
        !reconciled.validatedInternetAvailable -> limited
        persisted?.connectivityPhase == ConnectivityPhase.HEALTHY ->
            JourneyConnectivityPresentation.NORMAL
        persisted?.connectivityPhase == ConnectivityPhase.RECOVERING ->
            JourneyConnectivityPresentation.RECOVERING
        persisted == null -> JourneyConnectivityPresentation.CHECKING_CONNECTION
        else -> limited
    }
}

internal fun backgroundMonitoringReady(journey: Journey?, monitoringJourneyId: String?): Boolean =
    journey != null && journey.id == monitoringJourneyId
