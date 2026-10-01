package com.journeycontinuity.app.ui

import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.DegradedConnectivityState
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.service.CurrentJourneyConnectivity

internal fun canSubmitJourneyStart(destination: String, arrivalAt: Long?, now: Long): Boolean =
    destination.isNotBlank() && arrivalAt != null && arrivalAt > now

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

internal data class ConnectivityCopy(val title: String, val detail: String)

internal fun travellerConnectivityCopy(
    state: DegradedConnectivityState?,
    current: CurrentJourneyConnectivity?,
    smsRouteReady: Boolean,
    hasAcceptedContact: Boolean,
): ConnectivityCopy? {
    val persisted = state ?: return null
    val reconciled = current?.takeIf { it.journeyId == persisted.journeyId }
    val fallbackAvailable = smsRouteReady && hasAcceptedContact &&
        persisted.fallbackBindingProvisioned && persisted.transportAvailable
    val limited = ConnectivityCopy(
        "Limited connectivity",
        if (fallbackAvailable) {
            "SMS fallback is available if normal internet communication is limited."
        } else {
            "Fresh information may not reach trusted contacts until communication is restored."
        },
    )
    return when {
        reconciled?.validatedInternetAvailable == false -> limited
        persisted.connectivityPhase == ConnectivityPhase.HEALTHY -> null
        persisted.connectivityPhase == ConnectivityPhase.RECOVERING && reconciled == null ->
            ConnectivityCopy(
                "Checking connection",
                "Recent Journey information may still need to sync.",
            )
        persisted.connectivityPhase == ConnectivityPhase.RECOVERING -> ConnectivityCopy(
            "Connection restored",
            "Alabarin is syncing recent information while communication stabilizes.",
        )
        else -> limited
    }
}

internal fun backgroundMonitoringReady(journey: Journey?, monitoringJourneyId: String?): Boolean =
    journey != null && journey.id == monitoringJourneyId
