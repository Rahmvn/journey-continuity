package com.journeycontinuity.app.data.local

import androidx.room.TypeConverter
import com.journeycontinuity.app.domain.SyncPhase

class SyncPhaseConverter {
    @TypeConverter
    fun fromSyncPhase(value: SyncPhase): String = value.name

    @TypeConverter
    fun toSyncPhase(value: String): SyncPhase = SyncPhase.valueOf(value)
}
