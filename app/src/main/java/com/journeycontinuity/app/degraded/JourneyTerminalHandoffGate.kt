package com.journeycontinuity.app.degraded

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes local terminalization with the final telephony handoff in this process. */
class JourneyTerminalHandoffGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
