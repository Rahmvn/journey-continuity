package com.journeycontinuity.app.domain

import com.journeycontinuity.app.data.repository.JourneyRepository
import java.util.UUID

fun interface JourneyClock {
    fun nowMillis(): Long
}

fun interface JourneyIdGenerator {
    fun newId(): String
}

sealed interface StartJourneyResult {
    data class Started(val journey: Journey) : StartJourneyResult
    data object BlankDestination : StartJourneyResult
    data object ExpectedArrivalNotFuture : StartJourneyResult
    data object ActiveJourneyAlreadyExists : StartJourneyResult
}

sealed interface CompleteJourneyResult {
    data class Completed(val journey: Journey) : CompleteJourneyResult
    data object NoActiveJourney : CompleteJourneyResult
}

sealed interface StopMonitoringResult {
    data class Stopped(val journey: Journey) : StopMonitoringResult
    data object NoActiveJourney : StopMonitoringResult
}

sealed interface JourneyInputValidation {
    data object Valid : JourneyInputValidation
    data object BlankDestination : JourneyInputValidation
    data object ExpectedArrivalNotFuture : JourneyInputValidation
}

class JourneyLifecycle(
    private val repository: JourneyRepository,
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
    private val idGenerator: JourneyIdGenerator = JourneyIdGenerator { UUID.randomUUID().toString() },
) {
    fun validate(destination: String, expectedArrivalAt: Long): JourneyInputValidation {
        if (destination.trim().isEmpty()) return JourneyInputValidation.BlankDestination
        if (expectedArrivalAt <= clock.nowMillis()) {
            return JourneyInputValidation.ExpectedArrivalNotFuture
        }
        return JourneyInputValidation.Valid
    }

    suspend fun start(destination: String, expectedArrivalAt: Long): StartJourneyResult {
        val normalizedDestination = destination.trim()
        when (validate(normalizedDestination, expectedArrivalAt)) {
            JourneyInputValidation.BlankDestination -> return StartJourneyResult.BlankDestination
            JourneyInputValidation.ExpectedArrivalNotFuture ->
                return StartJourneyResult.ExpectedArrivalNotFuture
            JourneyInputValidation.Valid -> Unit
        }

        val startedAt = clock.nowMillis()

        val journey = Journey(
            id = idGenerator.newId(),
            destination = normalizedDestination,
            expectedArrivalAt = expectedArrivalAt,
            startedAt = startedAt,
            status = JourneyStatus.ACTIVE,
            completedAt = null,
        )
        return if (repository.createIfNoActive(journey)) {
            StartJourneyResult.Started(journey)
        } else {
            StartJourneyResult.ActiveJourneyAlreadyExists
        }
    }

    suspend fun complete(): CompleteJourneyResult {
        val completed = repository.completeActive(clock.nowMillis())
            ?: return CompleteJourneyResult.NoActiveJourney
        return CompleteJourneyResult.Completed(completed)
    }

    /**
     * Local persistence step only. Account must first receive the authenticated cloud
     * closure acknowledgement and pass its ended_at, in epoch milliseconds, here.
     */
    suspend fun stopMonitoring(journeyId: String, confirmedEndedAt: Long): StopMonitoringResult {
        require(journeyId.isNotBlank())
        require(confirmedEndedAt > 0L)
        val stopped = repository.stopMonitoringActive(journeyId, confirmedEndedAt)
            ?: return StopMonitoringResult.NoActiveJourney
        return StopMonitoringResult.Stopped(stopped)
    }
}
