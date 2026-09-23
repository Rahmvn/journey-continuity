package com.journeycontinuity.app.degraded

import android.app.Activity
import android.telephony.SmsManager
import com.journeycontinuity.app.data.local.FallbackAttemptEntity
import java.security.MessageDigest

class FallbackHandoffCoordinator(
    private val attempts: FallbackHandoffAttemptStore,
    private val configuration: SmsFallbackConfiguration,
    private val telephony: SmsTelephonyGateway,
    private val scheduler: FallbackHandoffScheduler = NoOpFallbackHandoffScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uncertaintyWindowMillis: Long = DEFAULT_UNCERTAINTY_WINDOW_MILLIS,
) {
    suspend fun processNextReady(journeyId: String): FallbackHandoffResult {
        val attempt = attempts.nextReady(journeyId, clock()) ?: return FallbackHandoffResult.NotReady
        return handoff(attempt.localAttemptId)
    }

    suspend fun handoff(localAttemptId: Long): FallbackHandoffResult {
        val now = clock()
        val attempt = attempts.get(localAttemptId)
            ?: return FallbackHandoffResult.AlreadyHandled
        if (!attempt.isReady(now)) return FallbackHandoffResult.NotReady

        validatePersistedAttempt(attempt)?.let { safeReason ->
            attempts.preflightPermanent(localAttemptId, now)
            return FallbackHandoffResult.Rejected(safeReason)
        }
        val resolved = configuration.resolveForSend()
        if (resolved is SmsTransportResolution.Unavailable) {
            return FallbackHandoffResult.Unavailable(resolved.safeReason)
        }
        resolved as SmsTransportResolution.Available
        val parts = try {
            telephony.divideMessage(resolved.subscriptionId, attempt.protectedPayloadText)
        } catch (_: SecurityException) {
            return FallbackHandoffResult.Unavailable("SMS permission is unavailable.")
        } catch (_: RuntimeException) {
            return FallbackHandoffResult.Unavailable("Android telephony is unavailable.")
        }
        if (parts.size != 1) {
            attempts.preflightPermanent(localAttemptId, now)
            return FallbackHandoffResult.Rejected("The persisted JC1 payload is not one SMS segment.")
        }
        if (!attempts.claim(localAttemptId, now)) {
            return FallbackHandoffResult.AlreadyHandled
        }
        val claimed = checkNotNull(attempts.get(localAttemptId))
        val request = SmsHandoffRequest(
            localAttemptId = claimed.localAttemptId,
            handoffGeneration = claimed.handoffGeneration,
            subscriptionId = resolved.subscriptionId,
            destination = resolved.route.destination,
            exactPersistedText = claimed.protectedPayloadText,
        )
        scheduler.scheduleUncertainCheck(localAttemptId, claimed.handoffGeneration, uncertaintyWindowMillis)
        return try {
            telephony.send(request)
            FallbackHandoffResult.SubmittedAwaitingCallback
        } catch (_: SecurityException) {
            recordUnavailableRace(claimed, RESULT_PERMISSION_LOST)
            FallbackHandoffResult.Unavailable("SMS permission was revoked before handoff.")
        } catch (_: IllegalArgumentException) {
            attempts.permanentFailure(
                localAttemptId,
                claimed.handoffGeneration,
                clock(),
                RESULT_INVALID_ARGUMENT,
            )
            FallbackHandoffResult.Rejected("Android rejected the SMS handoff configuration.")
        } catch (_: Throwable) {
            // An unexpected exception is not proof that the modem did not accept the request.
            FallbackHandoffResult.SubmittedAwaitingCallback
        }
    }

    suspend fun sentResult(localAttemptId: Long, generation: Int, resultCode: Int): Boolean {
        val now = clock()
        return when (classifyAndroidSmsResult(resultCode)) {
            AndroidSmsResult.HANDED_OFF ->
                attempts.handedOff(localAttemptId, generation, now, resultCode)
            AndroidSmsResult.RETRYABLE -> markRetry(localAttemptId, generation, now, resultCode,
                FallbackTransportOutcome.RETRYABLE_FAILURE)
            AndroidSmsResult.UNAVAILABLE -> markRetry(localAttemptId, generation, now, resultCode,
                FallbackTransportOutcome.TRANSPORT_UNAVAILABLE)
            AndroidSmsResult.PERMANENT ->
                attempts.permanentFailure(localAttemptId, generation, now, resultCode)
            AndroidSmsResult.AMBIGUOUS -> false
        }
    }

    suspend fun recoverUncertain(localAttemptId: Long, generation: Int): Boolean {
        val now = clock()
        val delay = retryDelay(generation)
        val changed = attempts.unknownForRetry(
            localAttemptId = localAttemptId,
            generation = generation,
            staleBefore = now - uncertaintyWindowMillis,
            atMillis = now,
            nextRetryAt = now + delay,
        )
        if (changed) scheduler.scheduleRetry(localAttemptId, delay)
        return changed
    }

    private suspend fun markRetry(
        localAttemptId: Long,
        generation: Int,
        now: Long,
        resultCode: Int,
        outcome: FallbackTransportOutcome,
    ): Boolean {
        val delay = retryDelay(generation)
        val changed = attempts.retryPending(
            localAttemptId, generation, now + delay, outcome, resultCode,
        )
        if (changed) scheduler.scheduleRetry(localAttemptId, delay)
        return changed
    }

    private suspend fun recordUnavailableRace(attempt: FallbackAttemptEntity, resultCode: Int) {
        markRetry(
            attempt.localAttemptId,
            attempt.handoffGeneration,
            clock(),
            resultCode,
            FallbackTransportOutcome.TRANSPORT_UNAVAILABLE,
        )
    }

    private fun validatePersistedAttempt(attempt: FallbackAttemptEntity): String? {
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(attempt.protectedPayloadText.toByteArray(Charsets.UTF_8))
        if (!MessageDigest.isEqual(attempt.payloadSha256, actualDigest)) return "Persisted JC1 digest mismatch."
        val frame = runCatching { FallbackEnvelopeV1.decode(attempt.protectedPayloadText) }.getOrNull()
            ?: return "Persisted JC1 payload is malformed."
        if (frame.header.envelopeSequence != attempt.envelopeSequence) return "Persisted JC1 sequence mismatch."
        if (!frame.header.nonce.contentEquals(attempt.nonce)) return "Persisted JC1 nonce mismatch."
        if (frame.header.eventType != attempt.eventType) return "Persisted JC1 event type mismatch."
        if (FallbackEnvelopeV1.encode(frame).value != attempt.protectedPayloadText) {
            return "Persisted JC1 payload is not canonical."
        }
        return null
    }

    private fun FallbackAttemptEntity.isReady(now: Long): Boolean = when (transportState) {
        FallbackTransportState.ALLOCATED -> true
        FallbackTransportState.RETRY_PENDING -> nextRetryAt == null || nextRetryAt <= now
        else -> false
    }

    private fun retryDelay(generation: Int): Long =
        (BASE_RETRY_MILLIS * (1L shl (generation - 1).coerceIn(0, 2))).coerceAtMost(MAX_RETRY_MILLIS)

    companion object {
        const val DEFAULT_UNCERTAINTY_WINDOW_MILLIS = 15 * 60_000L
        const val BASE_RETRY_MILLIS = 15 * 60_000L
        const val MAX_RETRY_MILLIS = 60 * 60_000L
        const val RESULT_PERMISSION_LOST = -10_001
        const val RESULT_INVALID_ARGUMENT = -10_002
    }
}

interface FallbackHandoffAttemptStore {
    suspend fun nextReady(journeyId: String, atMillis: Long): FallbackAttemptEntity?
    suspend fun get(localAttemptId: Long): FallbackAttemptEntity?
    suspend fun claim(localAttemptId: Long, atMillis: Long): Boolean
    suspend fun preflightPermanent(localAttemptId: Long, atMillis: Long): Boolean
    suspend fun handedOff(localAttemptId: Long, generation: Int, atMillis: Long, resultCode: Int): Boolean
    suspend fun retryPending(
        localAttemptId: Long,
        generation: Int,
        nextRetryAt: Long,
        outcome: FallbackTransportOutcome,
        resultCode: Int?,
    ): Boolean
    suspend fun permanentFailure(
        localAttemptId: Long,
        generation: Int,
        atMillis: Long,
        resultCode: Int?,
    ): Boolean
    suspend fun unknownForRetry(
        localAttemptId: Long,
        generation: Int,
        staleBefore: Long,
        atMillis: Long,
        nextRetryAt: Long,
    ): Boolean
}

enum class AndroidSmsResult { HANDED_OFF, RETRYABLE, UNAVAILABLE, PERMANENT, AMBIGUOUS }

fun classifyAndroidSmsResult(resultCode: Int): AndroidSmsResult = when (resultCode) {
    Activity.RESULT_OK -> AndroidSmsResult.HANDED_OFF
    SmsManager.RESULT_ERROR_RADIO_OFF,
    SmsManager.RESULT_ERROR_NO_SERVICE,
    SmsManager.RESULT_NO_DEFAULT_SMS_APP,
    -> AndroidSmsResult.UNAVAILABLE
    SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
    SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE,
    SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
    -> AndroidSmsResult.PERMANENT
    SmsManager.RESULT_ERROR_GENERIC_FAILURE,
    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
    SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY,
    -> AndroidSmsResult.RETRYABLE
    else -> AndroidSmsResult.AMBIGUOUS
}
