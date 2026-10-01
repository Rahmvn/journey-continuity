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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFFFBFBF8)
private val Brand = Color(0xFF1B3250)
private val Heading = Color(0xFF161B20)
private val Body = Color(0xFF676F74)
private val Helper = Color(0xFF636B73)
private val FieldBorder = Color(0xBF676F74)
private val OtpBorder = Color(0xFFDFE3E2)
private val Available = Color(0xFF2E6E42)
private val Taken = Color(0xFFA82B2B)
private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)

/** Feedback applies only while [handle] exactly matches the current input. */
data class CreateAccountHandleFeedback(
    val handle: String,
    val status: CreateAccountHandleStatus,
)

enum class CreateAccountHandleStatus { CHECKING, AVAILABLE, TAKEN }

/** UI-only form. The host owns validation, availability requests and submission. */
@Composable
fun CreateAccountDetailsScreen(
    fullName: String,
    handle: String,
    email: String,
    handleFeedback: CreateAccountHandleFeedback?,
    submissionEnabled: Boolean,
    onFullNameChange: (String) -> Unit,
    onHandleChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onLogIn: () -> Unit,
    modifier: Modifier = Modifier,
    allowUncheckedHandle: Boolean = false,
    serviceNotice: String? = null,
    requestInProgress: Boolean = false,
) {
    val feedback = handleFeedback?.takeIf { it.handle == handle }?.status
    val canContinue = submissionEnabled && (feedback == CreateAccountHandleStatus.AVAILABLE ||
        (allowUncheckedHandle && feedback == null))

    Column(
        modifier = modifier.fillMaxSize().background(Background)
            .padding(start = 24.dp, top = 34.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            BackAction(onBack)
            Spacer(Modifier.height(42.dp))
            ScreenHeading("Create your account")
            Spacer(Modifier.height(10.dp))
            SupportingCopy("Create your Alabarin identity, then verify an email you can access.", 44)
            Spacer(Modifier.height(24.dp))

            LabeledField("Full name", fullName, "Abdulrahman Saheed", onFullNameChange)
            Text(
                "Trusted contacts will see your full name.",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 17.dp),
                color = Helper, style = HelperStyle,
            )

            val handleBorder = when (feedback) {
                CreateAccountHandleStatus.AVAILABLE -> Available
                CreateAccountHandleStatus.TAKEN -> Taken
                CreateAccountHandleStatus.CHECKING -> null
                null -> FieldBorder
            }
            LabeledField("Alabarin handle", handle, "@rahmvn", onHandleChange,
                borderColor = handleBorder)
            val feedbackCopy = when (feedback) {
                CreateAccountHandleStatus.CHECKING -> "Checking availability…"
                CreateAccountHandleStatus.AVAILABLE -> "Available"
                CreateAccountHandleStatus.TAKEN -> "That handle is taken. Try another."
                null -> ""
            }
            val feedbackColor = when (feedback) {
                CreateAccountHandleStatus.AVAILABLE -> Available
                CreateAccountHandleStatus.TAKEN -> Taken
                else -> Helper
            }
            Text(
                feedbackCopy,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 21.dp),
                color = feedbackColor,
                style = HelperStyle.copy(fontWeight = if (feedback == CreateAccountHandleStatus.CHECKING)
                    FontWeight.Normal else FontWeight.Medium),
            )

            LabeledField("Email address", email, "you@example.com", onEmailChange,
                keyboardType = KeyboardType.Email)
            if (serviceNotice != null) {
                AuthInlineNotice(serviceNotice)
                Spacer(Modifier.height(16.dp))
                PrimaryAction("Continue", canContinue, onContinue)
                TextAction("Already have an account? Log in", Brand, onLogIn)
            }
        }
        if (serviceNotice == null) {
            PrimaryAction(if (requestInProgress) "Checking details…" else "Continue",
                canContinue, onContinue)
            TextAction("Already have an account? Log in", Brand, onLogIn)
        }
    }
}

/** UI-only OTP surface. Resend and email change are host handoffs. */
@Composable
fun CreateAccountCheckEmailScreen(
    code: String,
    submissionEnabled: Boolean,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onSendNewCode: () -> Unit,
    onUseDifferentEmail: () -> Unit,
    onLogIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    invalidOrExpired: Boolean = false,
    serviceNotice: String? = null,
    requestInProgress: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxSize().background(Background)
            .padding(start = 24.dp, top = 34.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            BackAction(onBack)
            Spacer(Modifier.height(42.dp))
            ScreenHeading("Check your email")
            Spacer(Modifier.height(10.dp))
            SupportingCopy("If this email can be used to create an Alabarin account, a one-time code will arrive shortly.", 64)
            Spacer(Modifier.height(28.dp))
            SixDigitCodeField(code, onCodeChange, if (invalidOrExpired) Color(0xFF992E2E) else OtpBorder)
            Spacer(Modifier.height(if (invalidOrExpired) 10.dp else 14.dp))
            if (invalidOrExpired) {
                Text("That code is invalid or has expired. Request a new code and try again.",
                    modifier = Modifier.widthIn(max = 312.dp).fillMaxWidth().heightIn(min = 38.dp),
                    color = Color(0xFF992E2E),
                    style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp,
                        lineHeight = 17.sp, platformStyle = NoFontPadding))
            }
            if (serviceNotice != null) AuthInlineNotice(serviceNotice)
            if (serviceNotice != null) {
                Spacer(Modifier.height(16.dp))
                PrimaryAction("Verify and continue", submissionEnabled, onVerify)
            }
            TextAction("Send a new code", Brand, onSendNewCode)
            TextAction("Already have an account? Log in", Body, onLogIn)
            TextAction("Use a different email", Body, onUseDifferentEmail)
        }
        if (serviceNotice == null) PrimaryAction(
            if (requestInProgress) "Checking code…" else "Verify and continue",
            submissionEnabled, onVerify)
    }
}

@Composable
private fun BackAction(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.TopStart,
    ) {
        Text("‹", color = Brand, style = TextStyle(fontFamily = FontFamily.SansSerif,
            fontSize = 28.sp, lineHeight = 30.sp, platformStyle = NoFontPadding))
    }
}

@Composable
private fun ScreenHeading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, color = Heading,
        style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
            fontSize = 30.sp, lineHeight = 36.sp, platformStyle = NoFontPadding))
}

@Composable
private fun SupportingCopy(text: String, minimumHeight: Int) {
    Text(text, modifier = Modifier.fillMaxWidth().heightIn(min = minimumHeight.dp), color = Body,
        style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
            lineHeight = 20.sp, platformStyle = NoFontPadding))
}

private val HelperStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp,
    lineHeight = 15.sp, platformStyle = NoFontPadding)

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    borderColor: Color? = FieldBorder,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Text(label, color = Heading,
        style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
            fontSize = 14.sp, lineHeight = 18.sp, platformStyle = NoFontPadding))
    Spacer(Modifier.height(7.dp))
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(58.dp)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, RoundedCornerShape(4.dp))
                else Modifier)
            .semantics { contentDescription = label },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        cursorBrush = SolidColor(Brand),
        textStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
            lineHeight = 19.sp, color = Body, platformStyle = NoFontPadding),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = Body,
                    style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
                        lineHeight = 19.sp, platformStyle = NoFontPadding))
                innerTextField()
            }
        },
    )
}

@Composable
private fun SixDigitCodeField(code: String, onCodeChange: (String) -> Unit, borderColor: Color) {
    BasicTextField(
        value = code,
        onValueChange = { changed -> onCodeChange(changed.filter { it in '0'..'9' }.take(6)) },
        modifier = Modifier.fillMaxWidth().height(58.dp)
            .semantics { contentDescription = "Six-digit code" },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = TextStyle(color = Color.Transparent),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(6) { index ->
                        Box(
                            Modifier.weight(1f).height(56.dp)
                                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                .clearAndSetSemantics { testTag = "otpCell" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (index < code.length) "•" else "", color = Heading,
                                style = TextStyle(fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Medium, fontSize = 20.sp,
                                    lineHeight = 24.sp, platformStyle = NoFontPadding))
                        }
                    }
                }
                Box(Modifier.fillMaxSize().alpha(0f)) { innerTextField() }
            }
        },
    )
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp))
            .alpha(if (enabled) 1f else 0.45f).background(Brand)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = TextStyle(fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp,
            platformStyle = NoFontPadding))
    }
}

@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, style = TextStyle(fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp,
            platformStyle = NoFontPadding))
    }
}

@Preview(name = "Create account available", widthDp = 390, heightDp = 844)
@Composable
private fun DetailsPreview() = CreateAccountDetailsScreen(
    "", "", "", CreateAccountHandleFeedback("", CreateAccountHandleStatus.AVAILABLE), true,
    {}, {}, {}, {}, {}, {},
)

@Preview(name = "Check email", widthDp = 390, heightDp = 844)
@Composable
private fun CheckEmailPreview() = CreateAccountCheckEmailScreen("12", true,
    {}, {}, {}, {}, {}, {})
