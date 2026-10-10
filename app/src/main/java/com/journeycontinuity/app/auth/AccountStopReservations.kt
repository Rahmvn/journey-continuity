package com.journeycontinuity.app.auth

data class AccountStopOperation(val id: String, val journeyId: String, val ownerId: String)

/** Exclusion only: this is not an offline Stop or permission to queue account sign-out. */
interface AccountStopReservations {
    suspend fun reserve(journeyId: String, ownerId: String): AccountStopOperation?
    suspend fun markDispatch(operation: AccountStopOperation): Boolean
    suspend fun uncertain(operation: AccountStopOperation)
    suspend fun releaseUndispatched(operation: AccountStopOperation)
    suspend fun acknowledge(operation: AccountStopOperation, endedAt: Long): Boolean
}

object UnavailableAccountStopReservations : AccountStopReservations {
    override suspend fun reserve(journeyId: String, ownerId: String): AccountStopOperation? = null
    override suspend fun markDispatch(operation: AccountStopOperation) = false
    override suspend fun uncertain(operation: AccountStopOperation) = Unit
    override suspend fun releaseUndispatched(operation: AccountStopOperation) = Unit
    override suspend fun acknowledge(operation: AccountStopOperation, endedAt: Long) = false
}
