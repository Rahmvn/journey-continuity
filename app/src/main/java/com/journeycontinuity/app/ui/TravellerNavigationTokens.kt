package com.journeycontinuity.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal val TravellerNavigationRippleRadius = 20.dp
internal val TravellerNavigationRippleColor = Color(0xFF1B3250)
private val TravellerNavigationInk = Color(0xFF1B3250)

@Composable
internal fun TravellerNavigationVisual(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(36.dp),
        contentAlignment = Alignment.Center, content = content)
}

@Composable
internal fun TravellerBackVisual() {
    TravellerNavigationVisual {
        Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24f
            val chevron = Path().apply {
                moveTo(15f * unit, 4f * unit)
                lineTo(7f * unit, 12f * unit)
                lineTo(15f * unit, 20f * unit)
            }
            drawPath(chevron, TravellerNavigationInk,
                style = Stroke(width = 2.2f * unit, cap = StrokeCap.Round,
                    join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun TravellerCloseVisual() {
    TravellerNavigationVisual {
        Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24f
            val stroke = 2.25f * unit
            drawLine(TravellerNavigationInk, Offset(6f * unit, 6f * unit),
                Offset(18f * unit, 18f * unit), stroke, cap = StrokeCap.Round)
            drawLine(TravellerNavigationInk, Offset(18f * unit, 6f * unit),
                Offset(6f * unit, 18f * unit), stroke, cap = StrokeCap.Round)
        }
    }
}
