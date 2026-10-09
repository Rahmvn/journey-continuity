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
    data object ExpectedArrivalOutOfRange : StartJourneyResult
    data object ActiveJourneyAlreadyExists : StartJourneyResult
    data object InvalidStructuredDestination : StartJourneyResult
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
    data object ExpectedArrivalOutOfRange : JourneyInputValidation
    data object InvalidStructuredDestination : JourneyInputValidation
}

class JourneyLifecycle(
    private val repository: JourneyRepository,
    private val clock: JourneyClock = JourneyClock(System::currentTimeMillis),
    private val idGenerator: JourneyIdGenerator = JourneyIdGenerator { UUID.randomUUID().toString() },
    private val catalogue: AdminCatalogue? = null,
) {
    fun validate(request: JourneyStartRequest): JourneyInputValidation {
        if (runCatching { request.validateDestination(catalogue) }.isFailure) {
            return JourneyInputValidation.InvalidStructuredDestination
        }
        return validate(request.displayLabel, request.expectedArrivalAt)
    }

    suspend fun start(request: JourneyStartRequest): StartJourneyResult {
        if (validate(request) == JourneyInputValidation.InvalidStructuredDestination) {
            return StartJourneyResult.InvalidStructuredDestination
        }
        return start(request.displayLabel, request.expectedArrivalAt, request.destination)
    }

    fun validate(destination: String, expectedArrivalAt: Long): JourneyInputValidation {
        if (destination.trim().isEmpty()) return JourneyInputValidation.BlankDestination
        when (JourneyEtaPolicy.validate(clock.nowMillis(), expectedArrivalAt)) {
            JourneyEtaPolicy.Validation.NOT_FUTURE -> return JourneyInputValidation.ExpectedArrivalNotFuture
            JourneyEtaPolicy.Validation.OUT_OF_RANGE -> return JourneyInputValidation.ExpectedArrivalOutOfRange
            JourneyEtaPolicy.Validation.VALID -> Unit
        }
        return JourneyInputValidation.Valid
    }

    suspend fun start(
        destination: String,
        expectedArrivalAt: Long,
        structuredDestination: StructuredDestination? = null,
    ): StartJourneyResult {
        if (structuredDestination != null && runCatching {
                JourneyStartRequest(destination, structuredDestination, expectedArrivalAt)
                    .validateDestination(catalogue)
            }.isFailure) return StartJourneyResult.InvalidStructuredDestination
        val normalizedDestination = destination.trim()
        when (validate(normalizedDestination, expectedArrivalAt)) {
            JourneyInputValidation.BlankDestination -> return StartJourneyResult.BlankDestination
            JourneyInputValidation.ExpectedArrivalNotFuture ->
                return StartJourneyResult.ExpectedArrivalNotFuture
            JourneyInputValidation.ExpectedArrivalOutOfRange -> return StartJourneyResult.ExpectedArrivalOutOfRange
            JourneyInputValidation.Valid -> Unit
            JourneyInputValidation.InvalidStructuredDestination -> return StartJourneyResult.InvalidStructuredDestination
        }

        val startedAt = clock.nowMillis()
        when (JourneyEtaPolicy.validate(startedAt, expectedArrivalAt)) {
            JourneyEtaPolicy.Validation.NOT_FUTURE -> return StartJourneyResult.ExpectedArrivalNotFuture
            JourneyEtaPolicy.Validation.OUT_OF_RANGE -> return StartJourneyResult.ExpectedArrivalOutOfRange
            JourneyEtaPolicy.Validation.VALID -> Unit
        }

        val journey = Journey(
            id = idGenerator.newId(),
            destination = normalizedDestination,
            expectedArrivalAt = expectedArrivalAt,
            startedAt = startedAt,
            status = JourneyStatus.ACTIVE,
            completedAt = null,
            structuredDestination = structuredDestination,
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
