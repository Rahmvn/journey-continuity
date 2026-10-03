package com.journeycontinuity.app.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.journeycontinuity.app.R
import com.journeycontinuity.app.domain.Journey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ResultInk = Color(0xFF161B20)
private val ResultBody = Color(0xFF676F74)
private val ResultBlue = Color(0xFF1B3250)
private val ResultRed = Color(0xFF992E2E)
private val ArrivalTime = DateTimeFormatter.ofPattern("h:mm a")

@Composable
internal fun TravellerActiveSignOutConfirmation(
    stopping: Boolean,
    onConfirm: () -> Unit,
    onKeepMonitoring: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!stopping) onKeepMonitoring() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0.20f)
        }
        Column(Modifier.width(320.dp).height(338.dp)
            .clip(RoundedCornerShape(26.dp)).background(Color(0xFFFEFEFC))
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp)
            .testTag("active_sign_out_confirmation")) {
            Text("Sign out and stop\nmonitoring?", Modifier.height(58.dp),
                color = ResultInk, style = resultStyle(22, 27, FontWeight.Bold))
            Spacer(Modifier.height(12.dp))
            Text("Signing out will stop monitoring for this Journey. This Journey will end as\nMonitoring stopped.",
                Modifier.fillMaxWidth().height(76.dp), color = ResultBody,
                style = resultStyle(15, 21))
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                .background(ResultRed)
                .clickable(enabled = !stopping, role = Role.Button, onClick = onConfirm)
                .testTag("active_confirm_stop_and_sign_out"), contentAlignment = Alignment.Center) {
                Text(if (stopping) "Stopping monitoring…" else "Stop monitoring and sign out",
                    color = Color.White, style = resultStyle(16, 22, FontWeight.Bold))
            }
            Box(Modifier.fillMaxWidth().height(46.dp)
                .clickable(enabled = !stopping, role = Role.Button, onClick = onKeepMonitoring)
                .testTag("active_keep_monitoring"), contentAlignment = Alignment.Center) {
                Text("Keep monitoring", color = ResultBlue,
                    style = resultStyle(15, 21, FontWeight.Medium))
            }
        }
    }
}

@Composable
internal fun TravellerMonitoringStoppedResult(
    journey: Journey,
    signedOut: Boolean,
    error: String?,
    inProgress: Boolean,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    BackHandler { /* The durable result is acknowledged with Done after LOCAL sign-out. */ }
    Column(Modifier.fillMaxSize().background(Color(0xFFFBFBF8)).safeDrawingPadding()
        .padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 24.dp)
        .testTag("monitoring_stopped_result")) {
        Spacer(Modifier.height(76.dp))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.monitoring_stopped_icon), null,
                Modifier.size(28.dp).testTag("monitoring_stopped_icon"))
        }
        Spacer(Modifier.height(24.dp))
        Text("Monitoring stopped", Modifier.semantics { heading() },
            color = ResultInk, style = resultStyle(30, 36, FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        Text("You stopped monitoring for this Journey.", color = ResultBody,
            style = resultStyle(15, 21))
        Spacer(Modifier.height(34.dp))
        ResultSummaryRow("Destination", journey.destination, "stopped_destination")
        Spacer(Modifier.height(12.dp))
        ResultSummaryRow("Expected arrival",
            ArrivalTime.format(Instant.ofEpochMilli(journey.expectedArrivalAt)
                .atZone(ZoneId.systemDefault())), "stopped_expected_arrival")
        Spacer(Modifier.weight(1f))
        if (error != null) {
            Text(error, Modifier.fillMaxWidth().padding(bottom = 12.dp)
                .testTag("monitoring_stopped_action_error"), color = ResultRed,
                style = resultStyle(13, 18))
        }
        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(ResultBlue)
            .clickable(enabled = !inProgress, role = Role.Button,
                onClick = if (signedOut) onDone else onRetry)
            .testTag(if (signedOut) "monitoring_stopped_done" else "monitoring_stopped_retry"),
            contentAlignment = Alignment.Center) {
            Text(if (inProgress) "Finishing sign out…" else if (signedOut) "Done" else "Retry sign out",
                color = Color.White, style = resultStyle(16, 20, FontWeight.Bold))
        }
    }
}

@Composable
private fun ResultSummaryRow(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = ResultBody, style = resultStyle(15, 21))
        Text(value, Modifier.weight(1f).testTag(tag).padding(start = 12.dp), color = ResultInk,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, style = resultStyle(15, 21, FontWeight.Medium))
    }
}

private fun resultStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
