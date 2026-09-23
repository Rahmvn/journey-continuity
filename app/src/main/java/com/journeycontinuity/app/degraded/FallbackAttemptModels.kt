package com.journeycontinuity.app.degraded

enum class FallbackBindingStatus {
    PROVISIONED,
    REVOKED,
}

enum class FallbackTransportState {
    ALLOCATED,
    HANDOFF_IN_PROGRESS,
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
}
