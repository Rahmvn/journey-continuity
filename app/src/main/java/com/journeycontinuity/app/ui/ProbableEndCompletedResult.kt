package com.journeycontinuity.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeycontinuity.app.R
import com.journeycontinuity.app.data.local.ProbableEndCompletedResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ink = Color(0xFF161B20)
private val body = Color(0xFF676F74)
private val blue = Color(0xFF1B3250)
private val arrivalTime = DateTimeFormatter.ofPattern("h:mm a")

/** Explicit Traveller-completed result; the device inference never claims arrival or safety. */
@Composable
internal fun ProbableEndCompletedResultScreen(result: ProbableEndCompletedResult, onDone: () -> Unit) {
    BackHandler { /* The durable result is acknowledged with Done. */ }
    Column(Modifier.fillMaxSize().background(Color(0xFFFBFBF8)).safeDrawingPadding()
        .padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 24.dp)
        .testTag("probable_end_completed_result")) {
        Spacer(Modifier.height(76.dp))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.probable_end_completed_icon), null, Modifier.size(28.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Journey completed", Modifier.semantics { heading() }.testTag("probable_end_completed_title"),
            color = ink, style = resultStyle(30, 36, FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        Text("Alabarin has stopped monitoring this Journey.", color = body,
            style = resultStyle(15, 21), modifier = Modifier.testTag("probable_end_completed_body"))
        Spacer(Modifier.height(34.dp))
        SummaryRow("Destination", result.destination)
        Spacer(Modifier.height(12.dp))
        SummaryRow("Expected arrival", arrivalTime.format(Instant.ofEpochMilli(result.expectedArrivalAt)
            .atZone(ZoneId.systemDefault())))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(blue).clickable(role = Role.Button, onClick = onDone)
            .testTag("probable_end_completed_done"), contentAlignment = Alignment.Center) {
            Text("Done", color = Color.White, style = resultStyle(16, 20, FontWeight.Bold))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = body, style = resultStyle(15, 21))
        Text(value, Modifier.weight(1f).padding(start = 12.dp), color = ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            style = resultStyle(15, 21, FontWeight.Medium))
    }
}

private fun resultStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
