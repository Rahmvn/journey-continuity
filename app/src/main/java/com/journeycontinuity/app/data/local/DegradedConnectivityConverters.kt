package com.journeycontinuity.app.data.local

import androidx.room.TypeConverter
import com.journeycontinuity.app.degraded.ConnectivityPhase
import com.journeycontinuity.app.degraded.FallbackDisposition
import com.journeycontinuity.app.degraded.FallbackBindingStatus
import com.journeycontinuity.app.degraded.FallbackEnvelopeV1
import com.journeycontinuity.app.degraded.FallbackTransportState
import com.journeycontinuity.app.degraded.FallbackTransportOutcome

class DegradedConnectivityConverters {
    @TypeConverter
    fun connectivityPhaseToString(value: ConnectivityPhase): String = value.name

    @TypeConverter
    fun stringToConnectivityPhase(value: String): ConnectivityPhase =
        ConnectivityPhase.valueOf(value)

    @TypeConverter
    fun fallbackDispositionToString(value: FallbackDisposition): String = value.name

    @TypeConverter
    fun stringToFallbackDisposition(value: String): FallbackDisposition =
        FallbackDisposition.valueOf(value)

    @TypeConverter
    fun fallbackBindingStatusToString(value: FallbackBindingStatus): String = value.name

    @TypeConverter
    fun stringToFallbackBindingStatus(value: String): FallbackBindingStatus =
        FallbackBindingStatus.valueOf(value)

    @TypeConverter
    fun fallbackTransportStateToString(value: FallbackTransportState): String = value.name

    @TypeConverter
    fun stringToFallbackTransportState(value: String): FallbackTransportState =
        FallbackTransportState.valueOf(value)

    @TypeConverter
    fun fallbackTransportOutcomeToString(value: FallbackTransportOutcome?): String? = value?.name

    @TypeConverter
    fun stringToFallbackTransportOutcome(value: String?): FallbackTransportOutcome? =
        value?.let(FallbackTransportOutcome::valueOf)

    @TypeConverter
    fun fallbackEventTypeToString(value: FallbackEnvelopeV1.EventType): String = value.name

    @TypeConverter
    fun stringToFallbackEventType(value: String): FallbackEnvelopeV1.EventType =
        FallbackEnvelopeV1.EventType.valueOf(value)
}
