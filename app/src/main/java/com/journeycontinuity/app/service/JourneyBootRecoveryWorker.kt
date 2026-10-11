package com.journeycontinuity.app.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.journeycontinuity.app.JourneyContinuityApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** A durable local recovery entry. Room decides privacy; the existing authority scheduler wakes later. */
class JourneyBootRecoveryWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext.applicationContext as JourneyContinuityApplication
        app.recoverAfterBoot()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e("JourneyRecovery", "Durable local recovery failed", error)
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "journey-operational-recovery"

        /** KEEP deduplicates boot, process and retry entries without replacing running work. */
        fun enqueue(context: Context): Operation {
            val request = OneTimeWorkRequestBuilder<JourneyBootRecoveryWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
