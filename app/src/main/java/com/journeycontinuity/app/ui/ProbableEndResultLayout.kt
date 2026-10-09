package com.journeycontinuity.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Normal-size footer placement, with the entire result scrollable when content needs more space. */
@Composable
internal fun ProbableEndResultLayout(
    episodeId: String,
    tag: String,
    doneTag: String,
    doneStyle: TextStyle,
    onDone: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberSaveable(episodeId, saver = ScrollState.Saver) { ScrollState(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFFBFBF8))
        .testTag(tag).safeDrawingPadding()
        .padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 24.dp)) {
        // Scroll measures content without a maximum height. Its minimum retains the
        // Figma footer position when everything fits; overflow has no weighted compression.
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight)
            .testTag("${tag}_scroll")) {
            content()
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1B3250)).clickable(role = Role.Button, onClick = onDone)
                .testTag(doneTag).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("Done", color = Color.White, style = doneStyle)
            }
        }
    }
}

/** Large fonts/compact width stack the summary; full dynamic values never need ellipsis. */
@Composable
internal fun ProbableEndResultSummaryRow(label: String, value: String, labelStyle: TextStyle, valueStyle: TextStyle) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp || fontScale > 1.2f) {
            Column {
                Text(label, color = Color(0xFF676F74), style = labelStyle)
                Spacer(Modifier.height(4.dp))
                Text(value, color = Color(0xFF161B20), style = valueStyle)
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = Color(0xFF676F74), style = labelStyle)
                Text(value, Modifier.weight(1f).padding(start = 12.dp), color = Color(0xFF161B20),
                    textAlign = TextAlign.End, style = valueStyle)
            }
        }
    }
}
