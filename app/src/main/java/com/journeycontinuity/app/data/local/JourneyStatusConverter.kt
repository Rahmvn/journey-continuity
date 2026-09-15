package com.journeycontinuity.app.data.local

import androidx.room.TypeConverter
import com.journeycontinuity.app.domain.JourneyStatus

class JourneyStatusConverter {
    @TypeConverter
    fun fromStatus(status: JourneyStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): JourneyStatus = JourneyStatus.valueOf(value)
}
