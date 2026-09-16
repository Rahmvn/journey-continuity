package com.journeycontinuity.app.sync

import android.util.Log
import com.journeycontinuity.app.BuildConfig

const val CLOUD_SYNC_LOG_TAG = "JourneyCloudSync"

interface SyncDiagnosticLogger {
    fun info(message: String)
    fun warning(message: String)
}

data object NoOpSyncDiagnosticLogger : SyncDiagnosticLogger {
    override fun info(message: String) = Unit
    override fun warning(message: String) = Unit
}

data object AndroidSyncDiagnosticLogger : SyncDiagnosticLogger {
    override fun info(message: String) {
        if (BuildConfig.DEBUG) Log.i(CLOUD_SYNC_LOG_TAG, message)
    }

    override fun warning(message: String) {
        if (BuildConfig.DEBUG) Log.w(CLOUD_SYNC_LOG_TAG, message)
    }
}
