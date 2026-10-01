package com.journeycontinuity.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeycontinuity.app.R

private val LoginBackground = Color(0xFFFBFBF8)
private val LoginBrand = Color(0xFF1B3250)
private val LoginHeading = Color(0xFF161B20)
private val LoginBody = Color(0xFF676F74)
private val LoginFieldBorder = Color(0xBF676F74)
private val LoginOtpBorder = Color(0xFFDFE3E2)
private val LoginOtpError = Color(0xFF992E2E)
private val LoginMarkerBackground = Color(0xFFF1F4F6)
private val LoginNoFontPadding = PlatformTextStyle(includeFontPadding = false)

/** Visual feedback only. The future host decides when this state applies. */
enum class ReturningLoginOtpFeedback { NEUTRAL, INVALID_OR_EXPIRED }

/** Host-owned email and callbacks; rendering does not request a code. */
@Composable
fun ReturningLoginEmailScreen(
    email: String,
    submissionEnabled: Boolean,
    onEmailChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCreateAccount: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    serviceNotice: String? = null,
    requestInProgress: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxSize().background(LoginBackground)
            .padding(start = 24.dp, top = 34.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            LoginBackAction(onBack)
            Spacer(Modifier.height(42.dp))
            LoginHeadingText("Log in")
            Spacer(Modifier.height(10.dp))
            LoginBodyText(
                "Enter the email linked to your Alabarin account. We’ll send a one-time code if this email can be used to log in.",
                minimumHeight = 76,
            )
            Spacer(Modifier.height(24.dp))
            Text("Email address", color = LoginHeading, style = TextStyle(
                fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                fontSize = 14.sp, lineHeight = 18.sp, platformStyle = LoginNoFontPadding,
            ))
            Spacer(Modifier.height(7.dp))
            BasicTextField(
                value = email,
                onValueChange = onEmailChange,
                modifier = Modifier.fillMaxWidth().height(58.dp)
                    .border(1.dp, LoginFieldBorder, RoundedCornerShape(4.dp))
                    .semantics { contentDescription = "Email address" },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                cursorBrush = SolidColor(LoginBrand),
                textStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
                    lineHeight = 19.sp, color = LoginBody, platformStyle = LoginNoFontPadding),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart) {
                        if (email.isEmpty()) Text("you@example.com", color = LoginBody,
                            style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
                                lineHeight = 19.sp, platformStyle = LoginNoFontPadding))
                        innerTextField()
                    }
                },
            )
            if (serviceNotice != null) {
                AuthInlineNotice(serviceNotice)
                Spacer(Modifier.height(16.dp))
                LoginPrimaryAction("Continue", submissionEnabled, onContinue)
                LoginTextAction("Need an Alabarin account? Create one", LoginBrand, onCreateAccount)
            }
        }
        if (serviceNotice == null) {
            LoginPrimaryAction(if (requestInProgress) "Sending code…" else "Continue",
                submissionEnabled, onContinue)
            LoginTextAction("Need an Alabarin account? Create one", LoginBrand, onCreateAccount)
        }
    }
}

/** The host owns code, feedback, submission, and all navigation/auth handoffs. */
@Composable
fun ReturningLoginCheckEmailScreen(
    code: String,
    submissionEnabled: Boolean,
    feedback: ReturningLoginOtpFeedback,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onSendNewCode: () -> Unit,
    onUseDifferentEmail: () -> Unit,
    onCreateAccount: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    serviceNotice: String? = null,
    requestInProgress: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxSize().background(LoginBackground)
            .padding(start = 24.dp, top = 34.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            LoginBackAction(onBack)
            Spacer(Modifier.height(42.dp))
            LoginHeadingText("Check your email")
            Spacer(Modifier.height(10.dp))
            LoginBodyText(
                "If an Alabarin account is linked to this email, a one-time code will arrive shortly.",
                minimumHeight = 64,
            )
            Spacer(Modifier.height(28.dp))
            LoginSixDigitCodeField(code, feedback, onCodeChange)
            if (feedback == ReturningLoginOtpFeedback.INVALID_OR_EXPIRED) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "That code is invalid or has expired. Request a new code and try again.",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    color = LoginOtpError,
                    style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp,
                        lineHeight = 17.sp, platformStyle = LoginNoFontPadding),
                )
            } else {
                Spacer(Modifier.height(14.dp))
            }
            if (serviceNotice != null) {
                AuthInlineNotice(serviceNotice)
                Spacer(Modifier.height(16.dp))
                LoginPrimaryAction("Verify and continue", submissionEnabled, onVerify)
            }
            LoginTextAction("Send a new code", LoginBrand, onSendNewCode)
            LoginTextAction("No account yet? Create one", LoginBody, onCreateAccount)
            LoginTextAction("Use a different email", LoginBody, onUseDifferentEmail)
        }
        if (serviceNotice == null) LoginPrimaryAction(
            if (requestInProgress) "Checking code…" else "Verify and continue",
            submissionEnabled, onVerify)
    }
}

/** Host-selected support surface; no failure is classified here. */
@Composable
fun AuthRateLimitScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().background(LoginBackground)
            .padding(start = 24.dp, top = 34.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            LoginBackAction(onBack)
            Spacer(Modifier.height(72.dp))
            LoginHeadingText("Try again later")
            Spacer(Modifier.height(10.dp))
            LoginBodyText(
                "Too many code requests or verification attempts were made. Wait a little before trying again.",
                minimumHeight = 68,
            )
        }
        LoginSecondaryAction("Back", 54, onBack)
    }
}

/** Host-selected support surface; it does not represent a local owner mismatch. */
@Composable
fun AuthVerifiedDeviceConflictScreen(
    onBackToLogIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(LoginBackground)
            .padding(start = 24.dp, top = 34.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Alabarin", color = LoginBrand, style = TextStyle(
                fontFamily = FontFamily(Font(R.font.instrument_sans_semibold, FontWeight.SemiBold)),
                fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp,
                platformStyle = LoginNoFontPadding,
            ))
            Spacer(Modifier.height(74.dp))
            Box(
                Modifier.height(52.dp).fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier.size(52.dp)
                        .clip(RoundedCornerShape(26.dp)).background(LoginMarkerBackground)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = LoginBrand, style = TextStyle(
                        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
                        fontSize = 22.sp, lineHeight = 24.sp, platformStyle = LoginNoFontPadding,
                    ))
                }
            }
            Spacer(Modifier.height(22.dp))
            LoginHeadingText("Signed in on another device", Modifier.widthIn(max = 310.dp))
            Spacer(Modifier.height(12.dp))
            LoginBodyText(
                "Your email is verified, but Alabarin allows one Traveller device at a time. This phone cannot sign in while the other device remains active.",
                minimumHeight = 86,
                lineHeight = 21,
            )
            Spacer(Modifier.height(30.dp))
            Text("If a Journey is active on the other device", color = LoginHeading,
                style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                    fontSize = 14.sp, lineHeight = 19.sp, platformStyle = LoginNoFontPadding))
            Spacer(Modifier.height(6.dp))
            Text(
                "End that Journey and sign out there first. Active Journey monitoring never transfers to this phone.",
                modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                color = LoginBody,
                style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp,
                    lineHeight = 19.sp, platformStyle = LoginNoFontPadding),
            )
        }
        LoginSecondaryAction("Back to log in", 56, onBackToLogIn)
    }
}

@Composable
private fun LoginBackAction(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.TopStart,
    ) {
        Text("‹", color = LoginBrand, style = TextStyle(fontFamily = FontFamily.SansSerif,
            fontSize = 28.sp, lineHeight = 30.sp, platformStyle = LoginNoFontPadding))
    }
}

@Composable
private fun LoginHeadingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.semantics { heading() }, color = LoginHeading,
        style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
            fontSize = 30.sp, lineHeight = 36.sp, platformStyle = LoginNoFontPadding))
}

@Composable
private fun LoginBodyText(text: String, minimumHeight: Int, lineHeight: Int = 20) {
    Text(text, modifier = Modifier.fillMaxWidth().heightIn(min = minimumHeight.dp), color = LoginBody,
        style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
            lineHeight = lineHeight.sp, platformStyle = LoginNoFontPadding))
}

@Composable
private fun LoginSixDigitCodeField(
    code: String,
    feedback: ReturningLoginOtpFeedback,
    onCodeChange: (String) -> Unit,
) {
    val visibleCode = code.filter { it in '0'..'9' }.take(6)
    val border = if (feedback == ReturningLoginOtpFeedback.INVALID_OR_EXPIRED)
        LoginOtpError else LoginOtpBorder
    BasicTextField(
        value = visibleCode,
        onValueChange = { changed -> onCodeChange(changed.filter { it in '0'..'9' }.take(6)) },
        modifier = Modifier.fillMaxWidth().height(58.dp)
            .semantics { contentDescription = "Six-digit code" },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = TextStyle(color = Color.Transparent),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(6) { index ->
                        Box(
                            Modifier.weight(1f).height(56.dp)
                                .border(1.dp, border, RoundedCornerShape(8.dp))
                                .clearAndSetSemantics { testTag = "loginOtpCell" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (index < visibleCode.length) "•" else "", color = LoginHeading,
                                style = TextStyle(fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Medium, fontSize = 20.sp,
                                    lineHeight = 24.sp, platformStyle = LoginNoFontPadding))
                        }
                    }
                }
                Box(Modifier.fillMaxSize().alpha(0f)) { innerTextField() }
            }
        },
    )
}

@Composable
private fun LoginPrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp))
            .alpha(if (enabled) 1f else 0.45f).background(LoginBrand)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = TextStyle(fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp,
            platformStyle = LoginNoFontPadding))
    }
}

@Composable
private fun LoginTextAction(label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = color, style = TextStyle(fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp,
            platformStyle = LoginNoFontPadding))
    }
}

@Composable
private fun LoginSecondaryAction(label: String, height: Int, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(height.dp)
            .border(1.dp, LoginOtpBorder, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = LoginHeading, style = TextStyle(fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium, fontSize = if (height == 54) 15.sp else 16.sp,
            lineHeight = if (height == 54) 19.sp else 20.sp,
            platformStyle = LoginNoFontPadding))
    }
}
