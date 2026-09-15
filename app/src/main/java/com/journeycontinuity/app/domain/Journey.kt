package com.journeycontinuity.app.domain

data class Journey(
    val id: String,
    val destination: String,
    val expectedArrivalAt: Long,
    val startedAt: Long,
    val status: JourneyStatus,
    val completedAt: Long?,
)

enum class JourneyStatus {
    ACTIVE,
    COMPLETED,
}
