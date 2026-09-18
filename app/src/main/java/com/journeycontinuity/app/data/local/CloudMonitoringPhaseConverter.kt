package com.journeycontinuity.app.data.local

import androidx.room.TypeConverter
import com.journeycontinuity.app.domain.CloudMonitoringPhase

class CloudMonitoringPhaseConverter {
    @TypeConverter
    fun fromPhase(value: CloudMonitoringPhase?): String? = value?.name

    @TypeConverter
    fun toPhase(value: String?): CloudMonitoringPhase? = value?.let(CloudMonitoringPhase::valueOf)
}
