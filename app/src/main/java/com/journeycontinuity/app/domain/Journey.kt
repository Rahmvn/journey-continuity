package com.journeycontinuity.app.domain

data class Journey(
    val id: String,
    val destination: String,
    val expectedArrivalAt: Long,
    val startedAt: Long,
    val status: JourneyStatus,
    val completedAt: Long?,
    val endedAt: Long? = null,
)

enum class JourneyStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
}
