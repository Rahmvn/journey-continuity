package com.journeycontinuity.app.domain

import com.journeycontinuity.app.data.repository.JourneyRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyLifecycleTest {
    private val repository = FakeJourneyRepository()
    private val clock = MutableJourneyClock(1_000L)
    private var nextId = 0
    private val lifecycle = JourneyLifecycle(
        repository = repository,
        clock = clock,
        idGenerator = JourneyIdGenerator { "journey-${++nextId}" },
    )

    @Test
    fun blankDestinationIsRejected() = runBlocking {
        assertEquals(StartJourneyResult.BlankDestination, lifecycle.start("   ", 2_000L))
        assertNull(repository.currentActive())
    }

    @Test
    fun pastOrPresentEtaIsRejected() = runBlocking {
        assertEquals(
            StartJourneyResult.ExpectedArrivalNotFuture,
            lifecycle.start("Abuja", 1_000L),
        )
        assertEquals(
            StartJourneyResult.ExpectedArrivalNotFuture,
            lifecycle.start("Abuja", 999L),
        )
    }

    @Test
    fun validStartCreatesActiveJourneyWithMachineTimestamps() = runBlocking {
        val result = lifecycle.start("  Abuja  ", 5_000L)

        assertTrue(result is StartJourneyResult.Started)
        val journey = (result as StartJourneyResult.Started).journey
        assertEquals("journey-1", journey.id)
        assertEquals("Abuja", journey.destination)
        assertEquals(5_000L, journey.expectedArrivalAt)
        assertEquals(1_000L, journey.startedAt)
        assertEquals(JourneyStatus.ACTIVE, journey.status)
        assertNull(journey.completedAt)
    }

    @Test
    fun simultaneousStartsCreateOnlyOneActiveJourney() = runBlocking {
        val results = listOf("Abuja", "Lagos").map { destination ->
            async { lifecycle.start(destination, 5_000L) }
        }.awaitAll()

        assertEquals(1, results.count { it is StartJourneyResult.Started })
        assertEquals(1, results.count { it is StartJourneyResult.ActiveJourneyAlreadyExists })
        assertEquals(1, repository.allJourneys.size)
    }

    @Test
    fun completionTransitionsActiveToCompletedAndRecordsTimestamp() = runBlocking {
        lifecycle.start("Abuja", 5_000L)
        clock.now = 3_000L

        val result = lifecycle.complete()

        assertTrue(result is CompleteJourneyResult.Completed)
        val completed = (result as CompleteJourneyResult.Completed).journey
        assertEquals(JourneyStatus.COMPLETED, completed.status)
        assertEquals(3_000L, completed.completedAt)
        assertNull(repository.currentActive())
        assertEquals(completed, repository.allJourneys.single())
    }

    private class MutableJourneyClock(var now: Long) : JourneyClock {
        override fun nowMillis(): Long = now
    }

    private class FakeJourneyRepository : JourneyRepository {
        private val mutex = Mutex()
        private val active = MutableStateFlow<Journey?>(null)
        val allJourneys = mutableListOf<Journey>()

        override val activeJourney: Flow<Journey?> = active

        override suspend fun createIfNoActive(journey: Journey): Boolean = mutex.withLock {
            if (active.value != null) return@withLock false
            allJourneys += journey
            active.value = journey
            true
        }

        override suspend fun completeActive(completedAt: Long): Journey? = mutex.withLock {
            val existing = active.value ?: return@withLock null
            val completed = existing.copy(
                status = JourneyStatus.COMPLETED,
                completedAt = completedAt,
            )
            allJourneys[allJourneys.indexOfFirst { it.id == existing.id }] = completed
            active.value = null
            completed
        }

        fun currentActive(): Journey? = active.value
    }
}
