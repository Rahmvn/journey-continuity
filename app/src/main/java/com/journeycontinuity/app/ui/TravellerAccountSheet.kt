package com.journeycontinuity.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val AccountBrand = Color(0xFF1B3250)
private val AccountHeading = Color(0xFF141A1F)
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
) {
    Column(Modifier.fillMaxWidth().height(390.dp).background(Color(0xFFFEFEFC))
        .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                .background(Color(0xFFDFE3E2)))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Account", color = AccountHeading, style = accountStyle(22, 27, FontWeight.Bold))
            Box(Modifier.size(32.dp).clickable(role = Role.Button, onClick = onClose)
                .testTag("account_close").semantics { contentDescription = "Close Account" },
                contentAlignment = Alignment.Center) {
                Text("×", color = AccountBrand, style = accountStyle(22, 22))
            }
        }
        Spacer(Modifier.height(16.dp))
        val profile = state.profile
        if (profile != null) {
            Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Full name", color = AccountBody, style = accountStyle(11, 14))
                    Spacer(Modifier.height(4.dp))
                    Text(profile.fullName, color = AccountHeading,
                        style = accountStyle(15, 20, FontWeight.Medium), maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Text("@${profile.handle}", color = AccountBody, style = accountStyle(12, 16),
                        maxLines = 1)
                }
                Text("Edit", Modifier.clickable(role = Role.Button, onClick = onEdit)
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp).testTag("account_edit"),
                    color = AccountBrand, style = accountStyle(12, 15, FontWeight.Medium))
            }
            Column {
                Text("Verified email", color = Color(0xFF676F74),
                    style = accountStyle(13, 17, FontWeight.Medium))
                Spacer(Modifier.height(5.dp))
                Text(profile.verifiedEmail, Modifier.testTag("account_verified_email"),
                    color = AccountHeading, style = accountStyle(16, 21, FontWeight.Medium),
                    maxLines = 1)
            }
        } else {
            Column(Modifier.fillMaxWidth().height(121.dp),
                verticalArrangement = Arrangement.Center) {
                Text(state.loadError ?: "Loading Account…", color = AccountBody,
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
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(48.dp)
            .clickable(role = Role.Button, onClick = onSignOutRequested)
            .testTag("account_sign_out"), contentAlignment = Alignment.Center) {
            Text("Sign out", color = AccountError,
                style = accountStyle(15, 19, FontWeight.Medium))
        }
    }
}

@Composable
internal fun TravellerEditIdentityOverlay(
    state: TravellerAccountState,
    onNameChange: (String) -> Unit,
    onHandleChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().widthIn(max = 390.dp).height(520.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color.White)
            .border(1.dp, AccountBorder, RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 22.dp)
            .testTag("account_edit_overlay")) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Edit identity", color = AccountHeading,
                    style = accountStyle(20, 27, FontWeight.Bold))
                Box(Modifier.size(32.dp).clickable(enabled = !state.saving, role = Role.Button,
                    onClick = onCancel).testTag("account_edit_close")
                    .semantics { contentDescription = "Close Edit identity" },
                    contentAlignment = Alignment.Center) {
                    Text("×", color = AccountBody, style = accountStyle(19, 19))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Full name", color = AccountHeading,
                style = accountStyle(11, 15, FontWeight.Medium))
            Spacer(Modifier.height(7.dp))
            AccountInput(state.fullNameInput, onNameChange, "account_full_name_input",
                !state.saving, KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            if (state.nameError != null) {
                Text(state.nameError, Modifier.padding(top = 5.dp), color = AccountError,
                    style = accountStyle(11, 15))
            }
            Spacer(Modifier.height(14.dp))
            Text("Alabarin handle", color = AccountHeading,
                style = accountStyle(11, 15, FontWeight.Medium))
            Spacer(Modifier.height(7.dp))
            AccountInput(state.handleInput, onHandleChange, "account_handle_input", !state.saving)
            Spacer(Modifier.height(8.dp))
            Text(handleGuidance(state.handleStatus),
                Modifier.fillMaxWidth().height(34.dp).testTag("account_handle_feedback"),
                color = if (state.handleStatus == AccountHandleStatus.UNAVAILABLE ||
                    state.handleStatus == AccountHandleStatus.INVALID) AccountError else AccountBody,
                style = accountStyle(11, 15))
            if (state.saveError != null) {
                Text(state.saveError, Modifier.padding(top = 4.dp).testTag("account_save_error"),
                    color = AccountError, style = accountStyle(11, 15))
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(9.dp))
                .background(AccountBrand).clickable(enabled = !state.saving, role = Role.Button,
                    onClick = onSave).testTag("account_save"), contentAlignment = Alignment.Center) {
                Text(if (state.saving) "Saving…" else "Save changes", color = Color.White,
                    style = accountStyle(13, 18, FontWeight.Medium))
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(9.dp))
                .border(1.dp, AccountBorder, RoundedCornerShape(9.dp))
                .clickable(enabled = !state.saving, role = Role.Button, onClick = onCancel)
                .testTag("account_cancel"), contentAlignment = Alignment.Center) {
                Text("Cancel", color = AccountBrand,
                    style = accountStyle(12, 16, FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun AccountInput(value: String, onChange: (String) -> Unit, tag: String,
    enabled: Boolean, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    BasicTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        keyboardOptions = keyboardOptions, cursorBrush = SolidColor(AccountBrand),
        textStyle = accountStyle(13, 18).copy(color = AccountHeading),
        modifier = Modifier.fillMaxWidth().height(54.dp).testTag(tag),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(9.dp))
                .background(Color.White).border(1.dp, AccountBorder, RoundedCornerShape(9.dp))
                .padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) { inner() }
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
