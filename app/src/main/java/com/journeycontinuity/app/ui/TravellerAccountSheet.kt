package com.journeycontinuity.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccountBrand = Color(0xFF1B3250)
private val AccountHeading = Color(0xFF141A1F)
private val AccountInk = Color(0xFF161B20)
private val AccountBody = Color(0xFF636B73)
private val AccountBorder = Color(0xFFDFE2E0)
private val AccountError = Color(0xFF992E2E)

@Composable
internal fun TravellerAccountSheet(
    state: TravellerAccountState,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onSignOutRequested: () -> Unit,
    onReauthenticate: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().height(if (state.reauthenticationNeeded) 500.dp else 390.dp)
        .background(Color(0xFFFEFEFC))
        .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp), contentAlignment = Alignment.Center) {
            TravellerSheetGrabberPill()
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Account", color = AccountInk, style = accountStyle(22, 27, FontWeight.Bold))
            Box(Modifier.size(48.dp).clip(CircleShape)
                .clickable(interactionSource = null,
                    indication = ripple(bounded = false, radius = TravellerNavigationRippleRadius,
                        color = TravellerNavigationRippleColor),
                    role = Role.Button, onClick = onClose)
                .testTag("account_close").semantics { contentDescription = "Close Account" },
                contentAlignment = Alignment.Center) {
                TravellerCloseVisual()
            }
        }
        Spacer(Modifier.height(8.dp))
        val profile = state.profile
        if (profile != null) {
            Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(profile.fullName, color = AccountHeading,
                        style = accountStyle(18, 24, FontWeight.SemiBold), maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text("@${profile.handle}", color = AccountBody, style = accountStyle(14, 20),
                        maxLines = 1)
                }
                if (!state.reauthenticationNeeded) Text("Edit",
                    Modifier.clickable(role = Role.Button, onClick = onEdit)
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp).testTag("account_edit"),
                    color = AccountBrand, style = accountStyle(14, 20, FontWeight.Medium))
            }
            Column {
                Text(if (state.reauthenticationNeeded) "Previously verified email" else "Verified email",
                    color = Color(0xFF676F74),
                    style = accountStyle(15, 21, FontWeight.Medium))
                Spacer(Modifier.height(5.dp))
                Text(profile.verifiedEmail, Modifier.testTag("account_verified_email"),
                    color = AccountInk, style = accountStyle(16, 21, FontWeight.Medium),
                    maxLines = 1)
            }
        } else {
            Column(Modifier.fillMaxWidth().height(121.dp),
                verticalArrangement = Arrangement.Center) {
                Text(if (state.reauthenticationNeeded)
                    "This device remains bound to its Traveller account. Reauthenticate to view identity details."
                    else state.loadError ?: "Loading Account…", color = AccountBody,
                    style = accountStyle(14, 20))
                if (state.loadError != null) {
                    Text("Try again", Modifier.clickable(role = Role.Button, onClick = onRetry)
                        .padding(top = 10.dp).testTag("account_retry"),
                        color = AccountBrand, style = accountStyle(13, 18, FontWeight.Medium))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFDFE3E2)))
        if (state.reauthenticationNeeded) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFFBF6E9)).padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("account_reauthentication_attention")) {
                Text("Account connection needs attention", color = Color(0xFFA3741F),
                    style = accountStyle(15, 21, FontWeight.Bold))
                Spacer(Modifier.height(5.dp))
                Text("Local Journey monitoring continues on this device. Reauthenticate only to restore owner-scoped cloud communication.",
                    color = Color(0xFF676F74), style = accountStyle(14, 20))
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.signOutError != null) {
            Text(state.signOutError, Modifier.fillMaxWidth().testTag("account_sign_out_error"),
                color = AccountError, style = accountStyle(13, 18))
        }
        if (state.reauthenticationNeeded) {
            Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onReauthenticate)
                .testTag("account_reauthenticate"), contentAlignment = Alignment.Center) {
                Text("Reauthenticate", color = AccountBrand,
                    style = accountStyle(16, 22, FontWeight.Medium))
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = state.signOutStatus != AccountSignOutStatus.CHECKING &&
                state.signOutStatus != AccountSignOutStatus.SIGNING_OUT &&
                state.signOutStatus != AccountSignOutStatus.CONFIRMING_ACTIVE &&
                state.signOutStatus != AccountSignOutStatus.STOPPING_ACTIVE,
                role = Role.Button, onClick = onSignOutRequested)
            .testTag("account_sign_out"), contentAlignment = Alignment.Center) {
            Text("Sign out", color = AccountError,
                style = accountStyle(16, 22, FontWeight.Medium))
        }
    }
}

@Composable
internal fun TravellerSheetGrabberPill() {
    Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
        .background(Color(0xFFDFE3E2)))
}

@Composable
internal fun TravellerEditIdentitySheet(
    state: TravellerAccountState,
    onNameChange: (String) -> Unit,
    onHandleChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val handleFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().height(minOf(610.dp, maxHeight))
            .background(Color(0xFFFEFEFC))
            .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp)
            .testTag("account_edit_overlay")) {
            Box(Modifier.fillMaxWidth().height(8.dp), contentAlignment = Alignment.Center) {
                TravellerSheetGrabberPill()
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Edit identity", color = AccountInk,
                    style = accountStyle(22, 27, FontWeight.Bold))
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .clickable(interactionSource = null,
                        indication = ripple(bounded = false,
                            radius = TravellerNavigationRippleRadius,
                            color = TravellerNavigationRippleColor),
                        enabled = !state.saving, role = Role.Button, onClick = onClose)
                    .testTag("account_edit_close")
                    .semantics { contentDescription = "Close Edit identity" },
                    contentAlignment = Alignment.Center) {
                    TravellerCloseVisual()
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("Edit the name and handle shown on your Alabarin profile.",
                    color = Color(0xFF676F74), style = accountStyle(15, 21))
                Spacer(Modifier.height(20.dp))
                Text("Full name", color = AccountInk,
                    style = accountStyle(15, 21, FontWeight.Medium))
                Spacer(Modifier.height(7.dp))
                AccountInput(state.fullNameInput, onNameChange, "account_full_name_input",
                    "Full name", !state.saving,
                    KeyboardOptions(capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next),
                    KeyboardActions(onNext = { handleFocus.requestFocus() }),
                    errorMessage = state.nameError)
                if (state.nameError != null) {
                    Text(state.nameError, Modifier.padding(top = 5.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }, color = AccountError,
                        style = accountStyle(13, 18))
                }
                Spacer(Modifier.height(18.dp))
                Text("Alabarin handle", color = AccountInk,
                    style = accountStyle(15, 21, FontWeight.Medium))
                Spacer(Modifier.height(7.dp))
                AccountInput(state.handleInput.removePrefix("@"),
                    { onHandleChange("@" + it.replace("@", "")) }, "account_handle_input",
                    "Alabarin handle", !state.saving,
                    KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    KeyboardActions(onDone = {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                    }),
                    modifier = Modifier.focusRequester(handleFocus),
                    prefix = "@",
                    errorMessage = if (state.handleStatus == AccountHandleStatus.INVALID ||
                        state.handleStatus == AccountHandleStatus.UNAVAILABLE)
                        handleGuidance(state.handleStatus) else null)
                Spacer(Modifier.height(8.dp))
                Text(handleGuidance(state.handleStatus),
                    Modifier.fillMaxWidth().testTag("account_handle_feedback")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (state.handleStatus == AccountHandleStatus.UNAVAILABLE ||
                        state.handleStatus == AccountHandleStatus.INVALID) AccountError else AccountBody,
                    style = accountStyle(13, 18))
                if (state.saveError != null) {
                    Text(state.saveError, Modifier.padding(top = 8.dp)
                        .testTag("account_save_error")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                        color = AccountError, style = accountStyle(13, 18))
                }
                Spacer(Modifier.height(12.dp))
            }
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
                .background(AccountBrand)
                .clickable(enabled = !state.saving, role = Role.Button, onClick = onSave)
                .testTag("account_save"), contentAlignment = Alignment.Center) {
                Text(if (state.saving) "Saving…" else "Save changes", color = Color.White,
                    style = accountStyle(16, 22, FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun AccountInput(value: String, onChange: (String) -> Unit, tag: String,
    label: String, enabled: Boolean, keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions, modifier: Modifier = Modifier,
    errorMessage: String? = null, prefix: String? = null) {
    BasicTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        cursorBrush = SolidColor(AccountBrand),
        textStyle = accountStyle(16, 22, FontWeight.Medium).copy(color = AccountHeading),
        modifier = Modifier.fillMaxWidth().height(56.dp).then(modifier).testTag(tag)
            .semantics {
                contentDescription = label
                if (errorMessage != null) error(errorMessage)
            },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFFEFEFC))
                .border(1.dp, Color(0xFF8B9092), RoundedCornerShape(4.dp))
                .padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prefix != null) {
                        Text(prefix, Modifier.clearAndSetSemantics {},
                            color = AccountHeading,
                            style = accountStyle(16, 22, FontWeight.Medium))
                    }
                    Box(Modifier.weight(1f)) { inner() }
                }
            }
        })
}

private fun handleGuidance(status: AccountHandleStatus): String = when (status) {
    AccountHandleStatus.IDLE -> "Your handle must be unique. Alabarin checks availability before saving."
    AccountHandleStatus.CHECKING -> "Checking handle availability…"
    AccountHandleStatus.AVAILABLE -> "Handle available. Final availability is confirmed when you save."
    AccountHandleStatus.UNAVAILABLE -> "That handle is unavailable. Try another."
    AccountHandleStatus.INVALID -> "Use 3–24 characters; start with a letter and end with a letter or digit."
    AccountHandleStatus.UNKNOWN -> "Could not check availability. Saving will confirm your handle."
}

private fun accountStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
