package com.journeycontinuity.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.journeycontinuity.app.trusted.TrustedContactSubjectKind
import com.journeycontinuity.app.trusted.TrustedContactStatus
import com.journeycontinuity.app.trusted.TrustedContactSummary

private val Ink = Color(0xFF161B20)
private val Muted = Color(0xFF676F74)
private val Navy = Color(0xFF1B3250)
private val Amber = Color(0xFFB0822F)
private val Rule = Color(0xFFDFE3E2)
private val SheetWhite = Color(0xFFFEFEFC)
private val RemoveRed = Color(0xFF9E3030)

internal fun activeTrustedContacts(contacts: List<TrustedContactSummary>): List<TrustedContactSummary> =
    contacts.filter {
        (it.kind == TrustedContactSubjectKind.RELATIONSHIP && it.status == TrustedContactStatus.ACCEPTED) ||
            (it.kind == TrustedContactSubjectKind.INVITATION && it.status == TrustedContactStatus.PENDING)
    }

internal fun trustedContactRemovalCopy(name: String): String =
    "$name will no longer be a trusted contact and will lose trusted access to your Journeys. " +
        "You can invite this person again later."

internal fun trustedContactShareIntent(url: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT,
        "You are invited to be an Alabarin trusted contact. Open this invitation to verify your email and accept: $url")
}

@Composable
internal fun TrustedContactsSheet(
    state: JourneyUiState,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onCreate: (String, String) -> Unit,
    onDone: () -> Unit,
    onRemove: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var removalCandidate by remember { mutableStateOf<TrustedContactSummary?>(null) }
    LaunchedEffect(state.trustedContactsPage) {
        if (state.trustedContactsPage == TrustedContactsPage.LIST) {
            name = ""
            email = ""
        }
    }
    LaunchedEffect(removalCandidate, state.trustedContacts) {
        val candidate = removalCandidate
        if (candidate != null && activeTrustedContacts(state.trustedContacts).none {
                it.id == candidate.id && it.status == TrustedContactStatus.ACCEPTED
            }) removalCandidate = null
    }
    val page = state.trustedContactsPage
    val targetHeight = when (page) {
        TrustedContactsPage.LIST -> if (state.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE &&
            activeTrustedContacts(state.trustedContacts).isEmpty()) 430.dp else 520.dp
        TrustedContactsPage.ADD -> 610.dp
        TrustedContactsPage.READY -> 500.dp
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
        .height((targetHeight - 40.dp).coerceAtMost(maxHeight)).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (page) {
                TrustedContactsPage.LIST -> "Trusted Contacts"
                TrustedContactsPage.ADD -> "Add trusted contact"
                TrustedContactsPage.READY -> "Invitation ready"
            }, color = Ink, style = contactStyle(22, 27, FontWeight.Bold),
                modifier = Modifier.weight(1f).semantics { heading() })
            Box(Modifier.size(40.dp).clickable(role = Role.Button, onClick = onDismiss)
                .testTag("trusted_contacts_close"), contentAlignment = Alignment.Center) {
                Text("×", color = Navy, style = contactStyle(22, 22))
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (page) {
                TrustedContactsPage.LIST -> TrustedContactsList(state) { removalCandidate = it }
                TrustedContactsPage.ADD -> {
                    Text("Invite a person using the email they will verify with Alabarin.",
                        color = Muted, style = contactStyle(15, 21))
                    Spacer(Modifier.height(20.dp))
                    ContactInput("Name", "e.g. Aisha", name, { name = it }, KeyboardType.Text)
                    Spacer(Modifier.height(18.dp))
                    ContactInput("Email address", "aisha@example.com", email,
                        { email = it }, KeyboardType.Email)
                }
                TrustedContactsPage.READY -> {
                    val invitedName = state.invitationReadyName.orEmpty()
                    val invitedEmail = state.invitationReadyEmail.orEmpty()
                    Text("$invitedName has not been added yet", color = Ink,
                        style = contactStyle(17, 22, FontWeight.Bold))
                    Spacer(Modifier.height(8.dp))
                    Text("Share the invitation with $invitedName. The invitation is bound to " +
                        "$invitedEmail, and access begins only after that email is verified and " +
                        "the invitation is accepted.", color = Muted,
                        style = contactStyle(15, 21))
                    Spacer(Modifier.height(22.dp))
                    ContactIdentityRow(invitedName, invitedEmail, "Pending", false, null)
                }
            }
        }
        when (page) {
            TrustedContactsPage.LIST -> ContactPrimaryAction("Add trusted contact", onAdd,
                enabled = state.trustedContactsAvailability == TrustedContactsAvailability.AVAILABLE)
            TrustedContactsPage.ADD -> ContactPrimaryAction("Create invitation",
                { onCreate(name, email) }, enabled = !state.trustedContactActionInProgress)
            TrustedContactsPage.READY -> {
                ContactPrimaryAction("Share invitation",
                    { state.invitationShareUrl?.let(onShare) },
                    enabled = state.invitationShareUrl != null)
                Box(Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Button,
                    onClick = onDone), contentAlignment = Alignment.Center) {
                    Text("Done", color = Navy, style = contactStyle(15, 21, FontWeight.Medium))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    removalCandidate?.let { candidate ->
        val stillAccepted = activeTrustedContacts(state.trustedContacts).any {
            it.id == candidate.id && it.status == TrustedContactStatus.ACCEPTED
        }
        if (stillAccepted) ContactRemovalDialog(candidate.displayName,
            onKeep = { removalCandidate = null },
            onRemove = {
                removalCandidate = null
                onRemove(candidate.id)
            }, enabled = !state.trustedContactActionInProgress)
    }
}

@Composable
private fun TrustedContactsList(
    state: JourneyUiState,
    onAcceptedClick: (TrustedContactSummary) -> Unit,
) {
    when (state.trustedContactsAvailability) {
        TrustedContactsAvailability.LOADING -> Text("Checking trusted contacts…", color = Muted,
            style = contactStyle(15, 21))
        TrustedContactsAvailability.UNAVAILABLE -> Text(
            state.trustedContactsUnavailableMessage ?: "Trusted contacts are currently unavailable.",
            color = Muted, style = contactStyle(15, 21))
        TrustedContactsAvailability.AVAILABLE -> {
            val contacts = activeTrustedContacts(state.trustedContacts)
            if (contacts.isEmpty()) {
                Text("No trusted contacts yet", color = Ink,
                    style = contactStyle(17, 22, FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                Text("Add someone you trust. They must verify the invited email and accept " +
                    "before they receive access to any Journey.", color = Muted,
                    style = contactStyle(15, 21))
            } else {
                Text("Accepted contacts are automatically authorized for each Journey you start.",
                    color = Muted, style = contactStyle(14, 20))
                Spacer(Modifier.height(10.dp))
                contacts.forEachIndexed { index, contact ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
                    ContactIdentityRow(contact.displayName, contact.email,
                        if (contact.status == TrustedContactStatus.ACCEPTED) "Accepted" else "Pending",
                        contact.status == TrustedContactStatus.ACCEPTED,
                        if (contact.status == TrustedContactStatus.ACCEPTED) {
                            { onAcceptedClick(contact) }
                        } else null)
                }
            }
        }
    }
}

@Composable
private fun ContactIdentityRow(name: String, email: String, status: String,
    interactive: Boolean, onClick: (() -> Unit)?) {
    val modifier = if (interactive && onClick != null)
        Modifier.fillMaxWidth().height(70.dp).clickable(role = Role.Button, onClick = onClick)
            .testTag("trusted_contact_accepted_row")
    else Modifier.fillMaxWidth().height(70.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink, style = contactStyle(16, 22, FontWeight.Medium))
            Spacer(Modifier.height(2.dp))
            Text(email, color = Muted, style = contactStyle(14, 20))
        }
        Spacer(Modifier.width(8.dp))
        Text(status, color = if (status == "Pending") Amber else Muted,
            style = contactStyle(14, 20, FontWeight.Medium))
    }
}

@Composable
private fun ContactInput(label: String, hint: String, value: String,
    onValueChange: (String) -> Unit, keyboardType: KeyboardType) {
    Text(label, color = Ink, style = contactStyle(15, 21, FontWeight.Medium))
    Spacer(Modifier.height(7.dp))
    BasicTextField(value, onValueChange, singleLine = true,
        textStyle = contactStyle(15, 21).copy(color = Ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().testTag(
            if (keyboardType == KeyboardType.Email) "trusted_contact_email" else "trusted_contact_name"),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().height(56.dp)
                .border(1.dp, Muted, RoundedCornerShape(4.dp)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = Muted, style = contactStyle(15, 21))
                inner()
            }
        })
}

@Composable
private fun ContactPrimaryAction(title: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
        .background(if (enabled) Navy else Navy.copy(alpha = 0.5f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag("trusted_contact_primary"), contentAlignment = Alignment.Center) {
        Text(title, color = Color.White, style = contactStyle(16, 22, FontWeight.Bold))
    }
}

@Composable
private fun ContactRemovalDialog(name: String, onKeep: () -> Unit,
    onRemove: () -> Unit, enabled: Boolean) {
    Dialog(onDismissRequest = onKeep) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(SheetWhite).padding(24.dp)) {
            Text("Remove $name?", color = Ink, style = contactStyle(22, 27, FontWeight.Bold))
            Spacer(Modifier.height(14.dp))
            Text(trustedContactRemovalCopy(name), color = Muted,
                style = contactStyle(15, 21))
            Spacer(Modifier.height(32.dp))
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
                .background(RemoveRed)
                .clickable(enabled = enabled, role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center) {
                Text("Remove trusted contact", color = Color.White,
                    style = contactStyle(16, 22, FontWeight.Bold))
            }
            Box(Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Button,
                onClick = onKeep), contentAlignment = Alignment.Center) {
                Text("Keep trusted contact", color = Navy,
                    style = contactStyle(15, 21, FontWeight.Medium))
            }
        }
    }
}

private fun contactStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight,
        platformStyle = PlatformTextStyle(includeFontPadding = false))
