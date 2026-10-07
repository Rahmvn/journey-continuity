package com.journeycontinuity.app.ui

import com.journeycontinuity.app.domain.JourneyStartRequest

/** Retained in the ViewModel only. A dialog owns the exact request that launched it. */
class JourneyStartPermissionState {
    enum class Permission { LOCATION, NOTIFICATIONS }
    class Ticket internal constructor(val permission: Permission, val request: JourneyStartRequest?) {
        override fun toString() = "JourneyStartPermissionTicket(redacted)"
    }

    private var ticket: Ticket? = null
    private var decisionRequest: JourneyStartRequest? = null
    private var notificationHandled = false
    val inFlight: Boolean get() = ticket != null

    fun begin(request: JourneyStartRequest): Boolean {
        if (inFlight) return decisionRequest === request && ticket?.request === request
        if (decisionRequest === request) return true
        decisionRequest = request
        notificationHandled = false
        return true
    }

    fun launch(permission: Permission, request: JourneyStartRequest?): Ticket? {
        if (inFlight) return null
        return Ticket(permission, request).also { ticket = it }
    }

    fun consume(permission: Permission): Ticket? {
        val current = ticket ?: return null
        if (current.permission != permission) return null
        ticket = null
        return current
    }

    fun notificationDecided(request: JourneyStartRequest) {
        if (decisionRequest === request) notificationHandled = true
    }

    fun needsNotificationDecision(request: JourneyStartRequest): Boolean =
        decisionRequest !== request || !notificationHandled
}
