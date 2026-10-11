package com.journeycontinuity.app.telemetry

import java.util.concurrent.CancellationException

/** A removal success only proves release after the matching registration request resolved. */
data class LocationRegistrationOwner(val journeyId: String, val generation: Long)

sealed interface LocationReleaseEvidence {
    val owner: LocationRegistrationOwner

    data class Removed(override val owner: LocationRegistrationOwner) : LocationReleaseEvidence
    data class RequestFailed(override val owner: LocationRegistrationOwner) : LocationReleaseEvidence
    data class RemovalFailed(
        override val owner: LocationRegistrationOwner,
        val attempt: Int,
        val terminal: Boolean,
    ) : LocationReleaseEvidence
}

/** Callbacks are serialized on the Android main thread by the fused-location adapter. */
internal class LocationRegistrationLifecycle<C : Any>(
    private val request: (C, () -> Unit, (Exception) -> Unit) -> Unit,
    private val remove: (C, () -> Unit, (Exception) -> Unit) -> Unit,
    private val scheduleRetry: (Long, () -> Unit) -> Unit,
    private val onRelease: (LocationReleaseEvidence) -> Unit,
) {
    private class Entry<C : Any>(
        val owner: LocationRegistrationOwner,
        val callback: C,
        val onReady: () -> Unit,
        val onFailure: (Exception) -> Unit,
    ) {
        var stopped = false
        var requestResolved = false
        var requestSucceeded = false
        var preRemovalStarted = false
        var postRemovalInFlight = false
        var postRemovalAttempts = 0
        var released = false
    }

    private var current: Entry<C>? = null

    fun isCurrent(owner: LocationRegistrationOwner, callback: C): Boolean =
        current?.let { !it.stopped && it.owner == owner && it.callback === callback } == true

    fun start(owner: LocationRegistrationOwner, callback: C, onReady: () -> Unit,
              onFailure: (Exception) -> Unit) {
        stop()
        val entry = Entry(owner, callback, onReady, onFailure)
        current = entry
        try {
            request(callback, { registrationSucceeded(entry) }, { registrationFailed(entry, it) })
        } catch (error: Exception) {
            registrationFailed(entry, error)
        }
    }

    /** Idempotent. Old entries retain their own bounded removal work when a new owner starts. */
    fun stop(owner: LocationRegistrationOwner? = null) {
        val entry = current ?: return
        if (owner != null && entry.owner != owner) return
        current = null
        entry.stopped = true
        if (entry.requestResolved) {
            if (entry.requestSucceeded) removeAfterRegistration(entry)
            else releaseWithoutRegistration(entry)
        } else if (!entry.preRemovalStarted) {
            // Request and removal can cross in Play services. Issue an immediate removal, then
            // issue another after request success; the first result is never treated as proof.
            entry.preRemovalStarted = true
            try {
                remove(entry.callback, {}, {
                    onRelease(LocationReleaseEvidence.RemovalFailed(entry.owner, 0, false))
                })
            } catch (_: Exception) {
                onRelease(LocationReleaseEvidence.RemovalFailed(entry.owner, 0, false))
            }
        }
    }

    private fun registrationSucceeded(entry: Entry<C>) {
        if (entry.requestResolved) return
        entry.requestResolved = true
        entry.requestSucceeded = true
        if (entry.stopped || current !== entry) removeAfterRegistration(entry)
        else entry.onReady()
    }

    private fun registrationFailed(entry: Entry<C>, error: Exception) {
        if (entry.requestResolved) return
        entry.requestResolved = true
        if (current === entry) current = null
        // Cancellation does not prove whether Play services registered the callback. Its
        // exact callback must be removed and acknowledged before claiming physical release.
        if (error is CancellationException) removeAfterRegistration(entry)
        else releaseWithoutRegistration(entry)
        if (!entry.stopped) entry.onFailure(error)
    }

    private fun releaseWithoutRegistration(entry: Entry<C>) {
        if (!entry.released) {
            entry.released = true
            onRelease(LocationReleaseEvidence.RequestFailed(entry.owner))
        }
    }

    private fun removeAfterRegistration(entry: Entry<C>) {
        if (entry.released || entry.postRemovalInFlight || entry.postRemovalAttempts >= MAX_REMOVAL_ATTEMPTS) return
        entry.postRemovalInFlight = true
        val attempt = ++entry.postRemovalAttempts
        try {
            remove(entry.callback, {
                entry.postRemovalInFlight = false
                if (!entry.released) {
                    entry.released = true
                    onRelease(LocationReleaseEvidence.Removed(entry.owner))
                }
            }, { removalFailed(entry, attempt) })
        } catch (_: Exception) {
            removalFailed(entry, attempt)
        }
    }

    private fun removalFailed(entry: Entry<C>, attempt: Int) {
        if (entry.released || !entry.postRemovalInFlight) return
        entry.postRemovalInFlight = false
        val terminal = attempt >= MAX_REMOVAL_ATTEMPTS
        onRelease(LocationReleaseEvidence.RemovalFailed(entry.owner, attempt, terminal))
        if (!terminal) scheduleRetry(RETRY_DELAY_MILLIS) { removeAfterRegistration(entry) }
        // Even after a final provider failure, the old callback stays disabled forever.
    }

    private companion object {
        const val MAX_REMOVAL_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 1_000L
    }
}
