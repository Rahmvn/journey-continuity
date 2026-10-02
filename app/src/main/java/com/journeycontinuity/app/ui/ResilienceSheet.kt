package com.journeycontinuity.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeycontinuity.app.degraded.SmsFallbackStatus

private val Ink = Color(0xFF161B20)
private val Muted = Color(0xFF676F74)
private val Navy = Color(0xFF1B3250)
private val Amber = Color(0xFFB0822F)
private val Rule = Color(0xFFDFE3E2)
private val NoteBackground = Color(0xFFFBF5E8)

@Composable
internal fun ResilienceSheet(
    presentation: ResiliencePresentation,
    status: SmsFallbackStatus,
    page: ResiliencePage,
    onDismiss: () -> Unit,
    onAddContact: () -> Unit,
    onAllowSms: () -> Unit,
    onAllowPhoneAccess: () -> Unit,
    onChooseSim: () -> Unit,
    onSelectSim: (Int) -> Unit,
) {
    val targetHeight = if (page == ResiliencePage.CHOOSE_SIM) 450.dp else presentation.heightDp.dp
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
        .height((targetHeight - 40.dp).coerceAtMost(maxHeight))
        .padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (page == ResiliencePage.CHOOSE_SIM) "Choose SIM" else "Resilience",
                color = Ink, style = resilienceStyle(22, 27, FontWeight.Bold),
                modifier = Modifier.weight(1f).semantics { heading() })
            Box(Modifier.size(40.dp).clickable(role = Role.Button, onClick = onDismiss)
                .testTag("resilience_close"), contentAlignment = Alignment.Center) {
                Text("×", color = Navy, style = resilienceStyle(22, 22))
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (page == ResiliencePage.CHOOSE_SIM) {
                Text("Select the SIM Alabarin may use for device SMS fallback.", color = Muted,
                    style = resilienceStyle(15, 21))
                Spacer(Modifier.height(18.dp))
                status.activeSubscriptions.forEachIndexed { index, choice ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
                    val selected = choice.subscriptionId == status.selectedSubscriptionId
                    Row(Modifier.fillMaxWidth().height(66.dp)
                        .clickable(role = Role.RadioButton) { onSelectSim(choice.subscriptionId) }
                        .testTag("resilience_sim_choice"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected,
                            onClick = { onSelectSim(choice.subscriptionId) })
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(choice.safeDisplayName, color = Ink,
                                style = resilienceStyle(16, 22, FontWeight.Medium))
                            Text(if (selected) "Selected for fallback" else "Available", color = Muted,
                                style = resilienceStyle(14, 20))
                        }
                    }
                }
            } else {
                val routeUnavailable = presentation.condition == ResilienceCondition.UNAVAILABLE
                val simNeedsSetup = !status.phoneStatePermissionGranted ||
                    status.activeSubscriptions.isEmpty() || status.selectedSubscriptionId == null ||
                    status.activeSubscriptions.none { it.subscriptionId == status.selectedSubscriptionId }
                Text(presentation.overall, color = if (presentation.condition == ResilienceCondition.READY)
                    Ink else Amber, style = resilienceStyle(16, 20, FontWeight.Bold))
                Spacer(Modifier.height(4.dp))
                Text(presentation.body, color = Muted, style = resilienceStyle(14, 20))
                Spacer(Modifier.height(12.dp))
                ResilienceRow("Trusted contacts", presentation.contactsValue,
                    presentation.contactsAction, onAddContact,
                    highlightValue = !routeUnavailable && presentation.contactsAction != null)
                ResilienceRow("Device SMS fallback", presentation.fallbackValue,
                    highlightValue = !status.ready)
                ResilienceRow("SIM", presentation.simValue, presentation.simAction, onAction = {
                    if (presentation.simAction == "Allow access") onAllowPhoneAccess() else onChooseSim()
                }, highlightValue = !routeUnavailable && simNeedsSetup)
                ResilienceRow("SMS permission", presentation.permissionValue,
                    presentation.permissionAction, onAllowSms, divider = false,
                    highlightValue = !routeUnavailable && presentation.permissionAction != null)
            }
        }
        Spacer(Modifier.height(18.dp))
        if (page == ResiliencePage.CHOOSE_SIM) {
            Text("Alabarin uses only the SIM you choose for fallback. It will not switch silently.",
                color = Muted, style = resilienceStyle(14, 20))
        } else if (presentation.noteTitle != null) {
            Column(Modifier.fillMaxWidth().background(NoteBackground, RoundedCornerShape(14.dp))
                .padding(14.dp)) {
                Text(presentation.noteTitle, color = Amber,
                    style = resilienceStyle(15, 21, FontWeight.Bold))
                Spacer(Modifier.height(4.dp))
                Text(presentation.note, color = Muted, style = resilienceStyle(14, 20))
            }
        } else Text(presentation.note, color = Muted, style = resilienceStyle(14, 20))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResilienceRow(
    label: String,
    value: String,
    action: String? = null,
    onAction: () -> Unit = {},
    divider: Boolean = true,
    highlightValue: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, color = Ink, style = resilienceStyle(16, 22, FontWeight.Medium))
            Spacer(Modifier.height(2.dp))
            Text(value, color = if (highlightValue) Amber else Muted,
                style = resilienceStyle(14, 20))
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            Text(action, color = Navy, style = resilienceStyle(14, 20, FontWeight.Medium),
                modifier = Modifier.clickable(role = Role.Button, onClick = onAction)
                    .padding(vertical = 12.dp).testTag("resilience_row_action"))
        }
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
}

private fun resilienceStyle(size: Int, lineHeight: Int,
    weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = size.sp,
    lineHeight = lineHeight.sp, fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false))
