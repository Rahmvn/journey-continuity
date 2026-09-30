package com.journeycontinuity.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
private val SecondaryBorder = Color(0xFFDFE3E2)
private val InstrumentSans = FontFamily(Font(R.font.instrument_sans_semibold, FontWeight.SemiBold))
private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)

/** A side-effect-free choice screen; its host owns the account and login flows. */
@Composable
fun IdentityChoiceScreen(
    onCreateAccount: () -> Unit,
    onLogIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Spacer(Modifier.height(126.dp))
        Text(
            text = "Your Journey, your account",
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
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Create an Alabarin account or log in with your verified email. Your session normally stays signed in.",
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            color = Body,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                platformStyle = NoFontPadding,
            ),
        )
        Spacer(Modifier.weight(1f))
        val buttonShape = RoundedCornerShape(18.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(buttonShape)
                .background(Brand)
                .clickable(role = Role.Button, onClick = onCreateAccount),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Create account",
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
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(buttonShape)
                .border(1.dp, SecondaryBorder, buttonShape)
                .clickable(role = Role.Button, onClick = onLogIn),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Log in",
                color = Heading,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    platformStyle = NoFontPadding,
                ),
            )
        }
    }
}

@Preview(name = "Identity choice", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun IdentityChoicePreview() = IdentityChoiceScreen(onCreateAccount = {}, onLogIn = {})
