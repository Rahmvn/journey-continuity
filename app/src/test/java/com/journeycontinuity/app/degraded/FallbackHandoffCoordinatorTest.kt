package com.journeycontinuity.app.degraded

import com.journeycontinuity.app.data.local.FallbackAttemptEntity
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackHandoffCoordinatorTest {
    private var now = 1_000L

    @Test
    fun handsOnlyExactPersistedSinglePartTextToTelephonyAfterAtomicClaim() = runBlocking {
        val store = FakeStore(validAttempt())
        val gateway = FakeGateway()
        val result = coordinator(store, gateway).handoff(ATTEMPT_ID)

        assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback, result)
        assertEquals(FallbackTransportState.HANDOFF_IN_PROGRESS, store.attempt.transportState)
        assertEquals(1, store.attempt.handoffGeneration)
        assertEquals(store.attempt.protectedPayloadText, gateway.sent.single().exactPersistedText)
    }

    @Test
    fun unavailableTransportRetainsPendingAttemptAndLaterSubmitsSamePersistedEnvelope() = runBlocking {
        val original = validAttempt()
        val store = FakeStore(original)
        val gateway = FakeGateway()
        var transport: SmsTransportResolution = SmsTransportResolution.Unavailable("No active SIM")
        fun handoffCoordinator() = FallbackHandoffCoordinator(
            attempts = store,
            configuration = object : SmsFallbackConfiguration {
                override fun status() = error("not needed")
                override fun resolveForSend() = transport
                override fun selectSubscription(subscriptionId: Int) = false
            },
            telephony = gateway,
            clock = { now },
        )

        assertTrue(handoffCoordinator().processNextReady(original.journeyId) is FallbackHandoffResult.Unavailable)
        assertEquals(original.localAttemptId, store.attempt.localAttemptId)
        assertEquals(FallbackTransportState.ALLOCATED, store.attempt.transportState)
        assertEquals(0, store.claims)
        assertEquals(0, store.attempt.transportAttemptCount)
        assertTrue(gateway.sent.isEmpty())

        transport = SmsTransportResolution.Available(7, SmsFallbackRoute("+15555550123"))
        assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback,
            handoffCoordinator().processNextReady(original.journeyId))
        assertEquals(original.localAttemptId, store.attempt.localAttemptId)
        assertEquals(original.envelopeSequence, store.attempt.envelopeSequence)
        assertEquals(original.telemetrySequence, store.attempt.telemetrySequence)
        assertEquals(original.protectedPayloadText, store.attempt.protectedPayloadText)
        assertArrayEquals(original.payloadSha256, store.attempt.payloadSha256)
        assertEquals(original.protectedPayloadText, gateway.sent.single().exactPersistedText)
        assertEquals(1, store.claims)
    }

    @Test
    fun digestMismatchBlocksHandoff() = runBlocking {
        val store = FakeStore(validAttempt().copy(payloadSha256 = ByteArray(32)))
        val gateway = FakeGateway()
        assertTrue(coordinator(store, gateway).handoff(ATTEMPT_ID) is FallbackHandoffResult.Rejected)
        assertTrue(gateway.sent.isEmpty())
        assertEquals(FallbackTransportState.PERMANENT_FAILURE, store.attempt.transportState)
    }

    @Test
    fun malformedJc1BlocksHandoff() = runBlocking {
        val text = "JC1.invalid"
        val store = FakeStore(validAttempt().copy(
            protectedPayloadText = text,
            payloadSha256 = sha256(text),
        ))
        assertTrue(coordinator(store, FakeGateway()).handoff(ATTEMPT_ID) is FallbackHandoffResult.Rejected)
    }

    @Test
    fun multipartResultBlocksHandoffBeforeClaim() = runBlocking {
        val store = FakeStore(validAttempt())
        val gateway = FakeGateway(parts = listOf("one", "two"))
        assertTrue(coordinator(store, gateway).handoff(ATTEMPT_ID) is FallbackHandoffResult.Rejected)
        assertEquals(0, store.claims)
    }

    @Test
    fun callbackSuccessReplayAndPreviousGenerationAreSafe() = runBlocking {
        val store = FakeStore(validAttempt())
        val coordinator = coordinator(store, FakeGateway())
        coordinator.handoff(ATTEMPT_ID)

        assertFalse(coordinator.sentResult(ATTEMPT_ID, 0, -1))
        assertFalse(coordinator.sentResult(999, 1, -1))
        assertTrue(coordinator.sentResult(ATTEMPT_ID, 1, -1))
        assertFalse(coordinator.sentResult(ATTEMPT_ID, 1, -1))
        assertEquals(FallbackTransportState.HANDED_OFF, store.attempt.transportState)
    }

    @Test
    fun transientAndPermanentCallbacksUseGenerationCheckedTransitions() = runBlocking {
        val retryStore = FakeStore(validAttempt())
        val retryCoordinator = coordinator(retryStore, FakeGateway())
        retryCoordinator.handoff(ATTEMPT_ID)
        assertTrue(retryCoordinator.sentResult(ATTEMPT_ID, 1, 1))
        assertEquals(FallbackTransportState.RETRY_PENDING, retryStore.attempt.transportState)

        val permanentStore = FakeStore(validAttempt())
        val permanentCoordinator = coordinator(permanentStore, FakeGateway())
        permanentCoordinator.handoff(ATTEMPT_ID)
        assertTrue(permanentCoordinator.sentResult(ATTEMPT_ID, 1, 6))
        assertEquals(FallbackTransportState.PERMANENT_FAILURE, permanentStore.attempt.transportState)
    }

    @Test
    fun missingCallbackBecomesDurableUnknownAndNeverResendsOnRepeatedChecks() = runBlocking {
        val original = validAttempt()
        val store = FakeStore(original)
        val gateway = FakeGateway()
        val scheduler = CountingScheduler()
        val coordinator = coordinator(store, gateway, uncertaintyWindow = 100, scheduler = scheduler)
        coordinator.handoff(ATTEMPT_ID)
        now = 1_050
        assertFalse(coordinator.recoverUncertain(ATTEMPT_ID, 1))
        now = 1_101
        assertTrue(coordinator.recoverUncertain(ATTEMPT_ID, 1))
        assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, store.attempt.transportState)
        assertEquals(FallbackTransportOutcome.UNKNOWN_OUTCOME, store.attempt.lastTransportOutcome)
        assertEquals(null, store.attempt.lastTransportResultCode)
        assertEquals(null, store.attempt.terminalAt)
        assertEquals(1, scheduler.uncertainChecks)
        assertEquals(0, scheduler.retries)
        assertEquals(1, gateway.sent.size)
        assertEquals(null, store.attempt.nextRetryAt)
        repeat(3) {
            now += 1_000_000
            assertFalse(coordinator.recoverUncertain(ATTEMPT_ID, 1))
            assertTrue(coordinator.processNextReady(original.journeyId) is FallbackHandoffResult.NotReady)
        }
        assertEquals(1, gateway.sent.size)
        assertEquals(1, store.attempt.transportAttemptCount)
        assertEquals(0, scheduler.retries)
        assertEquals(original.envelopeSequence, store.attempt.envelopeSequence)
        assertEquals(original.telemetrySequence, store.attempt.telemetrySequence)
        assertArrayEquals(original.payloadSha256, store.attempt.payloadSha256)
    }

    @Test
    fun lateMatchingSuccessReconcilesUnknownAndDuplicateOrStaleCallbacksDoNothing() = runBlocking {
        val store = FakeStore(validAttempt())
        val coordinator = coordinator(store, FakeGateway(), uncertaintyWindow = 100)
        coordinator.handoff(ATTEMPT_ID)
        now = 1_101
        assertTrue(coordinator.recoverUncertain(ATTEMPT_ID, 1))
        assertFalse(coordinator.sentResult(ATTEMPT_ID, 0, -1))
        assertTrue(coordinator.sentResult(ATTEMPT_ID, 1, -1))
        assertEquals(FallbackTransportState.HANDED_OFF, store.attempt.transportState)
        assertFalse(coordinator.sentResult(ATTEMPT_ID, 1, -1))
        assertFalse(coordinator.sentResult(ATTEMPT_ID, 1, 1))
    }

    @Test
    fun lateConfirmedFailureAllowsOneRetryThenExhaustsDurableBudget() = runBlocking {
        val original = validAttempt()
        val store = FakeStore(original)
        val gateway = FakeGateway()
        val scheduler = CountingScheduler()
        val coordinator = coordinator(store, gateway, uncertaintyWindow = 100, scheduler = scheduler)
        coordinator.handoff(ATTEMPT_ID)
        now = 1_101
        assertTrue(coordinator.recoverUncertain(ATTEMPT_ID, 1))
        assertTrue(coordinator.sentResult(ATTEMPT_ID, 1, 1))
        assertEquals(FallbackTransportState.RETRY_PENDING, store.attempt.transportState)
        assertEquals(1, scheduler.retries)
        assertEquals(now + FallbackHandoffCoordinator.BASE_RETRY_MILLIS, store.attempt.nextRetryAt)
        now = checkNotNull(store.attempt.nextRetryAt)
        assertEquals(FallbackHandoffResult.SubmittedAwaitingCallback, coordinator.handoff(ATTEMPT_ID))
        assertEquals(2, gateway.sent.size)
        assertEquals(original.protectedPayloadText, gateway.sent[1].exactPersistedText)
        assertEquals(2, store.attempt.transportAttemptCount)
        assertEquals(2, store.attempt.handoffGeneration)
        assertFalse(coordinator.sentResult(ATTEMPT_ID, 1, -1))
        assertTrue(coordinator.sentResult(ATTEMPT_ID, 2, 1))
        assertEquals(FallbackTransportState.PERMANENT_FAILURE, store.attempt.transportState)
        assertEquals(FallbackTransportOutcome.RETRY_EXHAUSTED, store.attempt.lastTransportOutcome)
        assertEquals(1, scheduler.retries)
        now += 10_000_000
        assertTrue(coordinator.processNextReady(original.journeyId) is FallbackHandoffResult.NotReady)
        assertEquals(2, gateway.sent.size)
    }

    @Test
    fun latePermanentFailureFromUnknownIsTerminalWithoutRetry() = runBlocking {
        val store = FakeStore(validAttempt())
        val gateway = FakeGateway()
        val coordinator = coordinator(store, gateway, uncertaintyWindow = 100)
        coordinator.handoff(ATTEMPT_ID)
        now = 1_101
        assertTrue(coordinator.recoverUncertain(ATTEMPT_ID, 1))
        assertTrue(coordinator.sentResult(ATTEMPT_ID, 1, 6))
        assertEquals(FallbackTransportState.PERMANENT_FAILURE, store.attempt.transportState)
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun secondInvocationWithoutCallbackStaysUnknownAndLateFailureCannotAuthorizeThirdSend() = runBlocking {
        val store = FakeStore(validAttempt())
        val gateway = FakeGateway()
        val scheduler = CountingScheduler()
        val coordinator = coordinator(store, gateway, uncertaintyWindow = 100, scheduler = scheduler)
        coordinator.handoff(ATTEMPT_ID)
        assertTrue(coordinator.sentResult(ATTEMPT_ID, 1, 1))
        now = checkNotNull(store.attempt.nextRetryAt)
        coordinator.handoff(ATTEMPT_ID)
        assertEquals(2, gateway.sent.size)
        now += 101
        assertTrue(coordinator.recoverUncertain(ATTEMPT_ID, 2))
        assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, store.attempt.transportState)
        assertEquals(1, scheduler.retries)
        assertFalse(coordinator.recoverUncertain(ATTEMPT_ID, 2))
        assertTrue(coordinator.processNextReady(store.attempt.journeyId) is FallbackHandoffResult.NotReady)
        assertEquals(2, gateway.sent.size)
        assertTrue(coordinator.sentResult(ATTEMPT_ID, 2, 1))
        assertEquals(FallbackTransportState.PERMANENT_FAILURE, store.attempt.transportState)
        assertEquals(FallbackTransportOutcome.RETRY_EXHAUSTED, store.attempt.lastTransportOutcome)
        assertEquals(2, gateway.sent.size)
    }

    @Test
    fun postClaimPermissionExceptionDoesNotCountAsConfirmedCallbackOrScheduleRetry() = runBlocking {
        val store = FakeStore(validAttempt())
        val gateway = FakeGateway(sendException = SecurityException("Permission revoked"))
        val scheduler = CountingScheduler()
        val coordinator = coordinator(store, gateway, uncertaintyWindow = 100, scheduler = scheduler)
        assertTrue(coordinator.handoff(ATTEMPT_ID) is FallbackHandoffResult.Unavailable)
        assertEquals(FallbackTransportState.HANDOFF_IN_PROGRESS, store.attempt.transportState)
        assertEquals(0, scheduler.retries)
        now += 101
        assertTrue(coordinator.recoverUncertain(ATTEMPT_ID, 1))
        assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, store.attempt.transportState)
        assertEquals(0, scheduler.retries)
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun postClaimArgumentExceptionAlsoRemainsUnresolvedWithoutRetry() = runBlocking {
        val store = FakeStore(validAttempt())
        val gateway = FakeGateway(sendException = IllegalArgumentException("Rejected"))
        val scheduler = CountingScheduler()
        val coordinator = coordinator(store, gateway, uncertaintyWindow = 100, scheduler = scheduler)
        assertTrue(coordinator.handoff(ATTEMPT_ID) is FallbackHandoffResult.Unavailable)
        assertEquals(FallbackTransportState.HANDOFF_IN_PROGRESS, store.attempt.transportState)
        now += 101
        assertTrue(coordinator.recoverUncertain(ATTEMPT_ID, 1))
        assertEquals(FallbackTransportState.UNKNOWN_OUTCOME, store.attempt.transportState)
        assertEquals(0, scheduler.retries)
    }

    private fun coordinator(
        store: FakeStore,
        gateway: FakeGateway,
        uncertaintyWindow: Long = 100,
        scheduler: FallbackHandoffScheduler = NoOpFallbackHandoffScheduler,
    ) = FallbackHandoffCoordinator(
        attempts = store,
        configuration = object : SmsFallbackConfiguration {
            override fun status() = error("not needed")
            override fun resolveForSend() = SmsTransportResolution.Available(7, SmsFallbackRoute("+15555550123"))
            override fun selectSubscription(subscriptionId: Int) = false
        },
        telephony = gateway,
        clock = { now },
        scheduler = scheduler,
        uncertaintyWindowMillis = uncertaintyWindow,
    )

    private fun validAttempt(): FallbackAttemptEntity {
        val nonce = ByteArray(FallbackEnvelopeV1.NONCE_BYTES) { 3 }
        val encoded = FallbackEnvelopeV1.encode(
            FallbackEnvelopeV1.ProtectedFrame(
                FallbackEnvelopeV1.Header(
                    FallbackEnvelopeV1.EventType.OBSERVATION,
                    7,
                    ByteArray(FallbackEnvelopeV1.JOURNEY_HANDLE_BYTES) { 2 },
                    4,
                    nonce,
                ),
                ByteArray(FallbackEnvelopeV1.BODY_BYTES) { 5 },
                ByteArray(FallbackEnvelopeV1.AUTHENTICATION_TAG_BYTES) { 6 },
            ),
        ).value
        return FallbackAttemptEntity(
            localAttemptId = ATTEMPT_ID,
            journeyId = "journey",
            degradationEpisodeId = 2,
            envelopeSequence = 4,
            telemetrySequence = 11,
            observationEventTime = 900,
            eventType = FallbackEnvelopeV1.EventType.OBSERVATION,
            protectedPayloadText = encoded,
            nonce = nonce,
            payloadSha256 = sha256(encoded),
            allocatedAt = 950,
        )
    }

    private class FakeGateway(
        private val parts: List<String>? = null,
        private val sendException: Throwable? = null,
    ) : SmsTelephonyGateway {
        val sent = mutableListOf<SmsHandoffRequest>()
        override fun divideMessage(subscriptionId: Int, text: String) = parts ?: listOf(text)
        override fun send(request: SmsHandoffRequest) {
            sent += request
            sendException?.let { throw it }
        }
    }

    private class CountingScheduler : FallbackHandoffScheduler {
        var uncertainChecks = 0
        var retries = 0
        override fun scheduleUncertainCheck(localAttemptId: Long, generation: Int, delayMillis: Long) {
            uncertainChecks++
        }
        override fun scheduleRetry(localAttemptId: Long, delayMillis: Long) {
            retries++
        }
    }

    private class FakeStore(var attempt: FallbackAttemptEntity) : FallbackHandoffAttemptStore {
        var claims = 0
        override suspend fun nextReady(journeyId: String, atMillis: Long) = attempt
        override suspend fun get(localAttemptId: Long) = attempt.takeIf { it.localAttemptId == localAttemptId }
        override suspend fun claim(localAttemptId: Long, atMillis: Long): Boolean {
            if (get(localAttemptId) == null || attempt.transportState !in listOf(
                    FallbackTransportState.ALLOCATED, FallbackTransportState.RETRY_PENDING,
                ) || attempt.transportAttemptCount >= MAX_TRANSPORT_INVOCATIONS ||
                (attempt.nextRetryAt ?: Long.MIN_VALUE) > atMillis
            ) return false
            claims++
            attempt = attempt.copy(
                transportState = FallbackTransportState.HANDOFF_IN_PROGRESS,
                transportAttemptCount = attempt.transportAttemptCount + 1,
                handoffGeneration = attempt.handoffGeneration + 1,
                handoffStartedAt = atMillis,
                nextRetryAt = null,
                lastTransportOutcome = null,
            )
            return true
        }
        override suspend fun preflightPermanent(localAttemptId: Long, atMillis: Long): Boolean {
            attempt = attempt.copy(transportState = FallbackTransportState.PERMANENT_FAILURE, terminalAt = atMillis)
            return true
        }
        override suspend fun handedOff(localAttemptId: Long, generation: Int, atMillis: Long, resultCode: Int) =
            mutateCallbackEligible(localAttemptId, generation) {
                it.copy(transportState = FallbackTransportState.HANDED_OFF, terminalAt = atMillis)
            }
        override suspend fun retryPending(localAttemptId: Long, generation: Int, nextRetryAt: Long,
            outcome: FallbackTransportOutcome, resultCode: Int) = mutateCallbackEligible(localAttemptId, generation) {
            if (it.transportAttemptCount >= MAX_TRANSPORT_INVOCATIONS ||
                outcome !in listOf(FallbackTransportOutcome.RETRYABLE_FAILURE,
                    FallbackTransportOutcome.TRANSPORT_UNAVAILABLE)) return@mutateCallbackEligible it
            it.copy(transportState = FallbackTransportState.RETRY_PENDING, nextRetryAt = nextRetryAt,
                lastTransportOutcome = outcome, lastTransportResultCode = resultCode)
        }
        override suspend fun retryExhausted(localAttemptId: Long, generation: Int, atMillis: Long,
            resultCode: Int): Boolean {
            if (attempt.transportAttemptCount < MAX_TRANSPORT_INVOCATIONS) return false
            return mutateCallbackEligible(localAttemptId, generation) {
                it.copy(transportState = FallbackTransportState.PERMANENT_FAILURE,
                    terminalAt = atMillis, nextRetryAt = null,
                    lastTransportOutcome = FallbackTransportOutcome.RETRY_EXHAUSTED,
                    lastTransportResultCode = resultCode)
            }
        }
        override suspend fun permanentFailure(localAttemptId: Long, generation: Int, atMillis: Long,
            resultCode: Int?) = mutateCallbackEligible(localAttemptId, generation) {
            it.copy(transportState = FallbackTransportState.PERMANENT_FAILURE, terminalAt = atMillis)
        }
        override suspend fun markUnknownOutcome(localAttemptId: Long, generation: Int, staleBefore: Long,
            atMillis: Long): Boolean {
            if ((attempt.handoffStartedAt ?: Long.MAX_VALUE) > staleBefore) return false
            return mutateInProgress(localAttemptId, generation) {
                it.copy(transportState = FallbackTransportState.UNKNOWN_OUTCOME, nextRetryAt = null,
                    uncertainSince = atMillis, lastTransportOutcome = FallbackTransportOutcome.UNKNOWN_OUTCOME)
            }
        }
        private fun mutateInProgress(id: Long, generation: Int,
            transform: (FallbackAttemptEntity) -> FallbackAttemptEntity): Boolean {
            if (attempt.localAttemptId != id || attempt.handoffGeneration != generation ||
                attempt.transportState != FallbackTransportState.HANDOFF_IN_PROGRESS) return false
            attempt = transform(attempt)
            return true
        }
        private fun mutateCallbackEligible(id: Long, generation: Int,
            transform: (FallbackAttemptEntity) -> FallbackAttemptEntity): Boolean {
            if (attempt.localAttemptId != id || attempt.handoffGeneration != generation ||
                attempt.transportState !in listOf(FallbackTransportState.HANDOFF_IN_PROGRESS,
                    FallbackTransportState.UNKNOWN_OUTCOME)) return false
            val updated = transform(attempt)
            if (updated === attempt) return false
            attempt = updated
            return true
        }
    }

    companion object {
        const val ATTEMPT_ID = 42L
        fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
    }
}
