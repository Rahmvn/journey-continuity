package com.journeycontinuity.app.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeycontinuity.app.R

private val Background = Color(0xFFFBFBF8)
private val Brand = Color(0xFF1B3250)
private val Heading = Color(0xFF161B20)
private val Body = Color(0xFF676F74)
private val Marker = Color(0xFFF1F4F6)
private val InactiveProgress = Color(0xFFDFE3E2)
private val InstrumentSans = FontFamily(Font(R.font.instrument_sans_semibold, FontWeight.SemiBold))
private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)

/** An isolated first-use flow. Its host will decide when to show it and where completion leads. */
@Composable
fun FirstUseIntroFlow(
    onCompleted: (FirstUseCompletion) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(FirstUseStep.initial) }
    var completionDispatched by rememberSaveable { mutableStateOf(false) }

    FirstUseIntroPage(
        step = step,
        modifier = modifier,
        actionEnabled = !completionDispatched,
        onAction = {
            val next = step.nextOrNull()
            if (next != null) {
                step = next
            } else if (!completionDispatched) {
                completionDispatched = true
                onCompleted(FirstUseCompletion.INTRODUCTION_FINISHED)
            }
        },
    )
}

@Composable
private fun FirstUseIntroPage(
    step: FirstUseStep,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
) {
    val heading = when (step) {
        FirstUseStep.PURPOSE -> "Stay connected through the Journey"
        FirstUseStep.JOURNEY_SCOPED_MONITORING -> "Only while a Journey is running"
        FirstUseStep.PRIVACY_CONTROL -> "You stay in control"
    }
    val body = when (step) {
        FirstUseStep.PURPOSE ->
            "Alabarin helps preserve verified Journey information when normal communication becomes unreliable."
        FirstUseStep.JOURNEY_SCOPED_MONITORING ->
            "Monitoring starts when you start a Journey and stops when the Journey ends. Opening the app alone never starts monitoring."
        FirstUseStep.PRIVACY_CONTROL ->
            "Trusted people only get authorized Journey information. Alabarin does not decide that you are in danger or independently confirm safe arrival."
    }
    val action = if (step == FirstUseStep.PRIVACY_CONTROL) "Continue" else "Next"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(start = 24.dp, top = 34.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Text(
            text = "Alabarin",
            color = Brand,
            style = TextStyle(
                fontFamily = InstrumentSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.sp,
                platformStyle = NoFontPadding,
            ),
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(62.dp).background(Marker, CircleShape))
        Spacer(Modifier.height(26.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
            Text(
                text = heading,
                modifier = Modifier.semantics { heading() },
                color = Heading,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    platformStyle = NoFontPadding,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 90.dp)) {
            Text(
                text = body,
                color = Body,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Normal,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    platformStyle = NoFontPadding,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.width(48.dp).height(6.dp).semantics {
                stateDescription = "Step ${step.ordinal + 1} of 3"
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FirstUseStep.entries.forEach { progressStep ->
                val selected = step == progressStep
                Box(
                    Modifier
                        .size(width = if (selected) 20.dp else 6.dp, height = 6.dp)
                        .background(
                            if (selected) Brand else InactiveProgress,
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brand)
                .clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = action,
                color = Color.White,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    platformStyle = NoFontPadding,
                ),
            )
        }
    }
}

@Preview(name = "Purpose", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun PurposePreview() = FirstUseIntroPage(FirstUseStep.PURPOSE, onAction = {})

@Preview(name = "Journey-scoped monitoring", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun JourneyScopedPreview() = FirstUseIntroPage(FirstUseStep.JOURNEY_SCOPED_MONITORING, onAction = {})

@Preview(name = "Privacy and control", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun PrivacyPreview() = FirstUseIntroPage(FirstUseStep.PRIVACY_CONTROL, onAction = {})
