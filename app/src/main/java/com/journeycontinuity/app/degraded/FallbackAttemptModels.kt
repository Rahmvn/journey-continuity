package com.journeycontinuity.app.degraded

enum class FallbackBindingStatus {
    PROVISIONED,
    REVOKED,
}

enum class FallbackTransportState {
    ALLOCATED,
    HANDOFF_IN_PROGRESS,
    UNKNOWN_OUTCOME,
    HANDED_OFF,
    RETRY_PENDING,
    PERMANENT_FAILURE,
    SUPERSEDED,
}

enum class FallbackTransportOutcome {
    ANDROID_HANDOFF_SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    TRANSPORT_UNAVAILABLE,
    UNKNOWN_OUTCOME,
    RETRY_EXHAUSTED,
}

/** A claim is counted before invoking SmsManager. Keep this equal to the DAO's SQL limit of 2. */
const val MAX_TRANSPORT_INVOCATIONS = 2
