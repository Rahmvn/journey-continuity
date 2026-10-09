package com.journeycontinuity.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeycontinuity.app.data.local.ProbableEndCompletedResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** System-inferred monitoring stop. The visual mark follows current Figma 78:4796. */
@Composable
internal fun ProbableEndAutomaticResultScreen(result: ProbableEndCompletedResult, onDone: () -> Unit) {
    BackHandler { /* Acknowledgement uses Done and never changes Journey history. */ }
    ProbableEndResultLayout(result.episodeId, "probable_end_automatic_result", "probable_end_automatic_done",
        autoResultStyle(16, 22, FontWeight.Bold), onDone) {
        Spacer(Modifier.height(64.dp))
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFF1F4F6)), contentAlignment = Alignment.Center) {
            Text("✓", color = Color(0xFF1B3250), style = autoResultStyle(22, 24, FontWeight.Bold))
        }
        Spacer(Modifier.height(24.dp))
        Text("Monitoring ended automatically", Modifier.fillMaxWidth().heightIn(min = 78.dp).semantics { heading() }
            .testTag("probable_end_automatic_title"), color = Color(0xFF161B20),
            style = autoResultStyle(30, 36, FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        Text("Alabarin stopped monitoring after detecting probable Journey-end evidence and receiving no response during the 30-minute confirmation window.",
            color = Color(0xFF676F74), style = autoResultStyle(15, 21),
            modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp).testTag("probable_end_automatic_body"))
        Spacer(Modifier.height(20.dp))
        Text("This does not confirm arrival or safety.", color = Color(0xFF161B20),
            style = autoResultStyle(15, 21, FontWeight.Medium),
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("probable_end_automatic_caveat"))
        Spacer(Modifier.height(28.dp))
        ProbableEndResultSummaryRow("Destination", result.destination,
            autoResultStyle(15, 21), autoResultStyle(15, 21, FontWeight.Medium))
        Spacer(Modifier.height(12.dp))
        ProbableEndResultSummaryRow("Expected arrival", DateTimeFormatter.ofPattern("h:mm a")
            .format(Instant.ofEpochMilli(result.expectedArrivalAt).atZone(ZoneId.systemDefault())),
            autoResultStyle(15, 21), autoResultStyle(15, 21, FontWeight.Medium))
    }
}

private fun autoResultStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
