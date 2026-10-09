package com.journeycontinuity.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeycontinuity.app.R
import com.journeycontinuity.app.data.local.ProbableEndCompletedResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ink = Color(0xFF161B20)
private val body = Color(0xFF676F74)
private val arrivalTime = DateTimeFormatter.ofPattern("h:mm a")

/** Explicit Traveller-completed result; the device inference never claims arrival or safety. */
@Composable
internal fun ProbableEndCompletedResultScreen(result: ProbableEndCompletedResult, onDone: () -> Unit) {
    BackHandler { /* The durable result is acknowledged with Done. */ }
    ProbableEndResultLayout(result.episodeId, "probable_end_completed_result", "probable_end_completed_done",
        resultStyle(16, 20, FontWeight.Bold), onDone) {
        Spacer(Modifier.height(76.dp))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.probable_end_completed_icon), null, Modifier.size(28.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Journey completed", Modifier.fillMaxWidth().semantics { heading() }.testTag("probable_end_completed_title"),
            color = ink, style = resultStyle(30, 36, FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        Text("Alabarin has stopped monitoring this Journey.", color = body,
            style = resultStyle(15, 21), modifier = Modifier.fillMaxWidth().testTag("probable_end_completed_body"))
        Spacer(Modifier.height(34.dp))
        ProbableEndResultSummaryRow("Destination", result.destination,
            resultStyle(15, 21), resultStyle(15, 21, FontWeight.Medium))
        Spacer(Modifier.height(12.dp))
        ProbableEndResultSummaryRow("Expected arrival", arrivalTime.format(Instant.ofEpochMilli(result.expectedArrivalAt)
            .atZone(ZoneId.systemDefault())), resultStyle(15, 21), resultStyle(15, 21, FontWeight.Medium))
    }
}

private fun resultStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
