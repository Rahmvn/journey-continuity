package com.journeycontinuity.app.degraded

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

class AndroidSmsFallbackConfiguration(
    private val context: Context,
    private val routeProvider: SmsFallbackRouteProvider,
) : SmsFallbackConfiguration {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun status(): SmsFallbackStatus {
        val sendGranted = granted(Manifest.permission.SEND_SMS)
        val phoneGranted = granted(Manifest.permission.READ_PHONE_STATE)
        val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        val subscriptions = if (phoneGranted && supported) activeSubscriptions() else emptyList()
        val selected = preferences.takeIf { it.contains(KEY_SELECTED_SUBSCRIPTION) }
            ?.getInt(KEY_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val routeConfigured = routeProvider.currentRoute() != null
        return evaluateSmsFallbackStatus(
            sendGranted, phoneGranted, supported, subscriptions, selected, routeConfigured,
        )
    }

    override fun resolveForSend(): SmsTransportResolution {
        val snapshot = status()
        if (!snapshot.ready) return SmsTransportResolution.Unavailable(
            snapshot.unavailableReason ?: "SMS fallback transport is unavailable.",
        )
        val route = routeProvider.currentRoute()
            ?: return SmsTransportResolution.Unavailable("No SMS fallback destination is configured.")
        return SmsTransportResolution.Available(checkNotNull(snapshot.selectedSubscriptionId), route)
    }

    override fun selectSubscription(subscriptionId: Int): Boolean {
        val snapshot = status()
        if (!snapshot.phoneStatePermissionGranted || !snapshot.telephonyMessagingSupported) return false
        if (snapshot.activeSubscriptions.none { it.subscriptionId == subscriptionId }) return false
        return preferences.edit().putInt(KEY_SELECTED_SUBSCRIPTION, subscriptionId).commit()
    }

    private fun activeSubscriptions(): List<SmsSubscriptionChoice> = try {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        manager.activeSubscriptionInfoList.orEmpty().map { info ->
            val slot = info.simSlotIndex.takeIf { it >= 0 }?.plus(1)
            val carrier = info.carrierName?.toString()?.trim().orEmpty().take(40)
            SmsSubscriptionChoice(
                subscriptionId = info.subscriptionId,
                safeDisplayName = listOfNotNull(
                    slot?.let { "SIM $it" },
                    carrier.takeIf(String::isNotEmpty),
                ).joinToString(" - ").ifEmpty { "Active SIM" },
            )
        }
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFERENCES_NAME = "journey-continuity-sms-fallback"
        const val KEY_SELECTED_SUBSCRIPTION = "selected-subscription-id"
    }
}

class AndroidSmsTelephonyGateway(private val context: Context) : SmsTelephonyGateway {
    override fun divideMessage(subscriptionId: Int, text: String): List<String> =
        manager(subscriptionId).divideMessage(text)

    override fun send(request: SmsHandoffRequest) {
        manager(request.subscriptionId).sendTextMessage(
            request.destination,
            null,
            request.exactPersistedText,
            sentPendingIntent(context, request.localAttemptId, request.handoffGeneration),
            null,
        )
    }

    @Suppress("DEPRECATION")
    private fun manager(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
}

internal fun sentPendingIntent(context: Context, attemptId: Long, generation: Int): PendingIntent {
    val intent = Intent(context, SmsSentResultReceiver::class.java).apply {
        action = ACTION_SMS_SENT
        data = Uri.Builder().scheme("journey-sms").authority("sent")
            .appendPath(attemptId.toString()).appendPath(generation.toString()).build()
        putExtra(EXTRA_ATTEMPT_ID, attemptId)
        putExtra(EXTRA_HANDOFF_GENERATION, generation)
    }
    return PendingIntent.getBroadcast(
        context,
        31 * attemptId.hashCode() + generation,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

internal const val ACTION_SMS_SENT = "com.journeycontinuity.app.action.SMS_SENT"
internal const val EXTRA_ATTEMPT_ID = "local_attempt_id"
internal const val EXTRA_HANDOFF_GENERATION = "handoff_generation"
