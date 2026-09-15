package com.journeycontinuity.app.data.local

import androidx.room.TypeConverter
import com.journeycontinuity.app.domain.ConnectivityState

class ConnectivityStateConverter {
    @TypeConverter
    fun fromConnectivityState(value: ConnectivityState): String = value.name

    @TypeConverter
    fun toConnectivityState(value: String): ConnectivityState = ConnectivityState.valueOf(value)
}
