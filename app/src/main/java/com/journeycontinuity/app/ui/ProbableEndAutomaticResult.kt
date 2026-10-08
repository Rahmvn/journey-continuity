package com.journeycontinuity.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import com.journeycontinuity.app.data.local.ProbableEndCompletedResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** System-inferred monitoring stop. The visual mark follows current Figma 78:4796. */
@Composable
internal fun ProbableEndAutomaticResultScreen(result: ProbableEndCompletedResult, onDone: () -> Unit) {
    BackHandler { /* Acknowledgement uses Done and never changes Journey history. */ }
    Column(Modifier.fillMaxSize().background(Color(0xFFFBFBF8)).safeDrawingPadding()
        .padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 24.dp)
        .testTag("probable_end_automatic_result")) {
        Spacer(Modifier.height(64.dp))
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFF1F4F6)), contentAlignment = Alignment.Center) {
            Text("✓", color = Color(0xFF1B3250), style = autoResultStyle(22, 24, FontWeight.Bold))
        }
        Spacer(Modifier.height(24.dp))
        Text("Monitoring ended automatically", Modifier.heightIn(min = 78.dp).semantics { heading() }
            .testTag("probable_end_automatic_title"), color = Color(0xFF161B20),
            style = autoResultStyle(30, 36, FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        Text("Alabarin stopped monitoring after detecting probable Journey-end evidence and receiving no response during the 30-minute confirmation window.",
            color = Color(0xFF676F74), style = autoResultStyle(15, 21),
            modifier = Modifier.heightIn(min = 86.dp).testTag("probable_end_automatic_body"))
        Spacer(Modifier.height(20.dp))
        Text("This does not confirm arrival or safety.", color = Color(0xFF161B20),
            style = autoResultStyle(15, 21, FontWeight.Medium),
            modifier = Modifier.heightIn(min = 58.dp).testTag("probable_end_automatic_caveat"))
        Spacer(Modifier.height(28.dp))
        AutomaticSummaryRow("Destination", result.destination)
        Spacer(Modifier.height(12.dp))
        AutomaticSummaryRow("Expected arrival", DateTimeFormatter.ofPattern("h:mm a")
            .format(Instant.ofEpochMilli(result.expectedArrivalAt).atZone(ZoneId.systemDefault())))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1B3250)).clickable(role = Role.Button, onClick = onDone)
            .testTag("probable_end_automatic_done"), contentAlignment = Alignment.Center) {
            Text("Done", color = Color.White, style = autoResultStyle(16, 22, FontWeight.Bold))
        }
    }
}

@Composable
private fun AutomaticSummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF676F74), style = autoResultStyle(15, 21))
        Text(value, Modifier.weight(1f).padding(start = 12.dp), color = Color(0xFF161B20),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            style = autoResultStyle(15, 21, FontWeight.Medium))
    }
}

private fun autoResultStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
