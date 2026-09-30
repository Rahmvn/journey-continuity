package com.journeycontinuity.app.ui

/** The three conceptual steps in the frozen first-use introduction. */
internal enum class FirstUseStep {
    PURPOSE,
    JOURNEY_SCOPED_MONITORING,
    PRIVACY_CONTROL;

    fun nextOrNull(): FirstUseStep? = when (this) {
        PURPOSE -> JOURNEY_SCOPED_MONITORING
        JOURNEY_SCOPED_MONITORING -> PRIVACY_CONTROL
        PRIVACY_CONTROL -> null
    }

    companion object {
        val initial: FirstUseStep = PURPOSE
    }
}

/** A typed handoff to the future root host; it does not perform navigation or authentication. */
enum class FirstUseCompletion {
    INTRODUCTION_FINISHED,
}
