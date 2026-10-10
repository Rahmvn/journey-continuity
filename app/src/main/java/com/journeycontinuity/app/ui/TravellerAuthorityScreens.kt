package com.journeycontinuity.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeycontinuity.app.data.local.TravellerAuthorityRecord
import com.journeycontinuity.app.data.repository.*
import com.journeycontinuity.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val AuthorityAmber = Color(0xFF805A16)
private val AuthorityBackground = Color(0xFFFBFBF8)
private val AuthorityHeading = Color(0xFF161B20)
private val AuthorityBody = Color(0xFF676F74)
private val AuthorityBrand = Color(0xFF1B3250)
private val AuthorityCard = Color(0xFFF3F4F0)
private val AuthorityDivider = Color(0xFFDFE1DE)
private val AuthorityInstrument = FontFamily(Font(R.font.instrument_sans_semibold, FontWeight.SemiBold))
@OptIn(ExperimentalTextApi::class)
private val AuthorityResultFont = FontFamily(
    Font(R.font.roboto, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.roboto, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.roboto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))))
private fun authorityStyle(size: Int = 15, line: Int = 21, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
        platformStyle = PlatformTextStyle(includeFontPadding = false))

private fun authorityResultStyle(size: Int = 15, line: Int = 21, weight: FontWeight = FontWeight.Normal) =
    authorityStyle(size, line, weight).copy(fontFamily = AuthorityResultFont, letterSpacing = 0.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))

@Composable
internal fun UnresolvedJourneyResultScreen(result: UnresolvedJourneyResult, busy: Boolean,
    error: String?, onDone: () -> Unit) {
    // Back and Done enter the same exact-receipt persistence operation.
    BackHandler { if (!busy) onDone() }
    ProbableEndResultLayout(result.journeyId, "unresolved_result", "unresolved_done",
        authorityResultStyle(16, 22, FontWeight.Bold), { if (!busy) onDone() }) {
        Spacer(Modifier.height(64.dp))
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFFBF6E9))
            .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Text("!", color = Color(0xFFA3741F), style = authorityResultStyle(22, 24, FontWeight.Bold))
        }
        Spacer(Modifier.height(24.dp))
        Text("Monitoring window\nended", Modifier.fillMaxWidth().heightIn(min = 78.dp)
            .semantics { heading() }.testTag("unresolved_title"), color = AuthorityHeading,
            style = authorityResultStyle(30, 36, FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        Text(result.boundaryExplanation(),
            Modifier.fillMaxWidth().heightIn(min = 86.dp).testTag("unresolved_body"),
            color = AuthorityBody, style = authorityResultStyle())
        Spacer(Modifier.height(20.dp))
        Text("Uncertainty remains. This does not mean the traveller is safe, missing, or in danger.",
            Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("unresolved_caveat"),
            color = Color(0xFFA3741F), style = authorityResultStyle(weight = FontWeight.Medium))
        Spacer(Modifier.height(28.dp))
        ProbableEndResultSummaryRow("Destination", result.destination,
            authorityResultStyle(), authorityResultStyle(weight = FontWeight.Medium))
        Spacer(Modifier.height(12.dp))
        ProbableEndResultSummaryRow("Expected arrival", authorityTime(Instant.ofEpochMilli(result.expectedArrivalAt)),
            authorityResultStyle(), authorityResultStyle(weight = FontWeight.Medium))
        if (busy || error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error ?: "Saving acknowledgement…", color = AuthorityAmber, style = authorityResultStyle(),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
internal fun PendingAuthorityScreen(record: TravellerAuthorityRecord, state: TravellerAuthorityUiState,
    onBack: () -> Unit, onCheck: () -> Unit, onComplete: () -> Unit, onStop: () -> Unit,
    onAuthenticate: () -> Unit, onOpenNetworkSettings: () -> Unit) {
    BackHandler(onBack = onBack)
    var confirming by rememberSaveable(record.journey.id) { mutableStateOf<String?>(null) }
    val busy = state.busyJourneyId == record.journey.id
    val fontScale = LocalDensity.current.fontScale
    val compact = fontScale >= 1.5f || LocalConfiguration.current.screenHeightDp <= 600
    val titleSize = when {
        fontScale >= 1.8f -> 16
        fontScale >= 1.3f -> 20
        else -> 30
    }
    val checked = state.outcome.takeIf { state.outcomeJourneyId == record.journey.id }
    val copy = pendingAuthorityCopy(record, busy && state.checking, state.offline, checked)
    val outcome = checked ?: record.reconciliation?.lastOutcome
    val pendingStop = record.reconciliation?.explicitAction == "STOP" &&
        record.reconciliation.explicitState == "PENDING"
    val pendingComplete = record.reconciliation?.explicitAction == "COMPLETE" &&
        record.reconciliation.explicitState == "PENDING"
    val unavailable = outcome == "UNAVAILABLE"
    val authNeeded = outcome == "AUTHENTICATION_REQUIRED"
    val noTerminal = outcome in setOf("TOO_EARLY", "NO_CURRENT_VERIFICATION", "INELIGIBLE",
        "SUPERSEDED_EPISODE", "FINAL_GUARD_BLOCKED", "COMPETING_TERMINAL", "ELIGIBLE")
    val retryAvailable = !state.offline && !busy &&
        record.reconciliation?.lastOutcome == "UNAVAILABLE" &&
        record.reconciliation.retryAtMillis?.let { System.currentTimeMillis() >= it } != false
    val specificAuthority = record.reconciliation?.claimState == "PENDING" ||
        outcome in setOf("TOO_EARLY", "NO_CURRENT_VERIFICATION", "INELIGIBLE",
            "SUPERSEDED_EPISODE", "FINAL_GUARD_BLOCKED", "COMPETING_TERMINAL",
            "EXPLICIT_PENDING", "CLAIM_PENDING", "INCONSISTENT", "ELIGIBLE")
    val explanation = when {
        busy && state.checking -> "Continuous monitoring remains paused while Alabarin checks this Journey's status."
        pendingStop -> "Continuous monitoring has stopped. Your Stop request was saved on this device."
        pendingComplete -> "Continuous monitoring has stopped. Your Complete request was saved on this device."
        unavailable -> "Continuous monitoring remains paused. Alabarin could not confirm the server's current Journey status."
        authNeeded -> "Continuous monitoring remains paused. Alabarin needs authentication to check this Journey's final status."
        state.returnedFromNetworkSettings -> "You're back in Alabarin. Continuous monitoring remains paused."
        noTerminal -> "Alabarin checked this Journey, but no authoritative final outcome was confirmed."
        state.offline -> "Continuous monitoring has stopped. Alabarin has not confirmed a final Journey outcome."
        else -> "Continuous monitoring has stopped. The Journey's final outcome has not been confirmed."
    }
    val caveat = when {
        pendingStop -> "The server has not acknowledged the Stop request. The final Journey outcome remains unconfirmed."
        pendingComplete -> "The server has not acknowledged completion. Do not assume trusted contacts have received an update."
        busy && state.checking -> "Checking the server does not restart monitoring or confirm a safe arrival."
        state.returnedFromNetworkSettings && state.offline ->
            "Alabarin still cannot verify the server's current Journey status without a connection."
        unavailable -> "A failed status check does not mean the Journey ended or the traveller arrived."
        authNeeded -> "Signing in will not automatically restart monitoring."
        state.returnedFromNetworkSettings -> "Connection alone does not finalize this Journey or resume monitoring."
        noTerminal -> "Continuous monitoring remains paused. This check is not proof of arrival, safety, or danger."
        state.offline && record.operational?.pauseReason == "LOST_OR_INVALID_BOOT_ANCHOR" ->
            "We couldn't verify the timing while offline, so collection was paused to protect your privacy."
        else -> "A monitoring limit is not proof of arrival, safety, or danger."
    }
    val statusTitle = when {
        busy && state.checking -> "Checking status"
        pendingStop -> "Stop requested"
        pendingComplete -> "Complete requested"
        state.offline && state.returnedFromNetworkSettings -> "Still offline"
        state.offline -> "No connection"
        unavailable -> "Check unavailable"
        authNeeded -> "Authentication required"
        state.returnedFromNetworkSettings -> "Connection restored"
        record.reconciliation?.claimState == "PENDING" -> "Authority pending"
        noTerminal -> "No final outcome confirmed"
        specificAuthority -> "No final outcome confirmed"
        else -> "Awaiting confirmation"
    }
    val statusDetail = when {
        busy && state.checking -> "Contacting Alabarin. Please wait for the current result."
        pendingStop || pendingComplete -> "Waiting for an authoritative acknowledgement."
        state.offline && state.returnedFromNetworkSettings -> "Check your connection and try Network settings again."
        state.offline -> "Reconnect to check the Journey's current status."
        unavailable -> "The latest status check failed. Retry manually, or wait for a later automatic opportunity."
        authNeeded -> "Sign in to request authoritative Journey status."
        state.returnedFromNetworkSettings -> "Alabarin will check automatically when authentication and network access permit."
        noTerminal -> copy.authority
        specificAuthority -> copy.authority
        else -> "Alabarin checks the server automatically when a connection and valid session permit it."
    }
    Column(Modifier.fillMaxSize().background(AuthorityBackground).safeDrawingPadding()
        .padding(horizontal = 24.dp).testTag("pending_authority")
        .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Column((if (compact) Modifier.fillMaxWidth() else
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
            .padding(top = 34.dp)) {
            Text("Alabarin", color = AuthorityBrand,
                style = authorityStyle(18, 24, FontWeight.SemiBold).copy(fontFamily = AuthorityInstrument))
            Spacer(Modifier.height(36.dp))
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFFCF5E8)).clearAndSetSemantics {},
                contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(painterResource(R.drawable.alabarin_monitoring_clock), null,
                    Modifier.size(24.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(if (fontScale >= 1.3f) "Monitoring\npaused" else "Monitoring paused",
                Modifier.fillMaxWidth().semantics { heading() }.testTag("pending_title"),
                color = AuthorityHeading, style = authorityStyle(titleSize, titleSize + 6, FontWeight.Bold))
            Spacer(Modifier.height(12.dp))
            Text(explanation, color = AuthorityBody, style = authorityStyle(15, 22),
                modifier = Modifier.testTag("pending_explanation"))
            Spacer(Modifier.height(16.dp))
            Text(caveat, color = AuthorityAmber, style = authorityStyle(15, 22, FontWeight.Medium),
                modifier = Modifier.testTag("pending_timing"))
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(AuthorityDivider))
            Spacer(Modifier.height(18.dp))
            AuthoritySummary("Destination", record.journey.destination)
            Spacer(Modifier.height(8.dp))
            AuthoritySummary("Expected arrival", authorityTime(Instant.ofEpochMilli(record.journey.expectedArrivalAt)))
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AuthorityCard)
                .padding(horizontal = 16.dp, vertical = 14.dp).testTag("pending_status")
                .semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(statusTitle, color = AuthorityHeading, style = authorityStyle(weight = FontWeight.Medium))
                Spacer(Modifier.height(6.dp))
                Text(statusDetail, color = AuthorityBody, style = authorityStyle(14, 20))
            }
            if (record.operational?.pauseReason in setOf("APPROXIMATE_MONOTONIC_CAP",
                    "AUTHENTICATED_BOUNDARY_PROOF") ||
                record.operational?.lastAssessment == "BOUNDARY_REACHED_WITH_PROOF") {
                Spacer(Modifier.height(14.dp))
                Text(copy.timing, color = AuthorityBody, style = authorityStyle(14, 20),
                    modifier = Modifier.testTag("pending_boundary_evidence"))
            }
            if (copy.intent != null && !pendingStop && !pendingComplete) {
                Spacer(Modifier.height(16.dp))
                Text(copy.intent, color = AuthorityHeading, style = authorityStyle(),
                    modifier = Modifier.testTag("pending_intent"))
            }
            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                Text(state.error, color = AuthorityAmber, style = authorityStyle(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (record.canRecordExplicitAction()) {
                Spacer(Modifier.height(if (compact) 32.dp else 400.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(AuthorityDivider))
                Spacer(Modifier.height(16.dp))
                Text("You can record that this Journey is complete, or request Stop. Server acknowledgement may still be pending.",
                    color = AuthorityBody, style = authorityStyle())
                Spacer(Modifier.height(12.dp))
                AuthorityAction("Complete Journey", "pending_complete", { confirming = "COMPLETE" }, !busy, false)
                Spacer(Modifier.height(12.dp))
                AuthorityAction("Stop Journey", "pending_stop", { confirming = "STOP" }, !busy, false)
                if (confirming != null) {
                    Spacer(Modifier.height(20.dp))
                    Text(if (confirming == "COMPLETE") "Record this Journey as complete? This does not independently confirm safe arrival."
                        else "Request Stop for this Journey? Monitoring stays stopped. Cancellation is confirmed only after the server acknowledges it.",
                        color = AuthorityHeading, style = authorityStyle())
                    Spacer(Modifier.height(12.dp))
                    AuthorityAction(if (confirming == "COMPLETE") "Confirm Complete" else "Confirm Stop", "pending_confirm", {
                        val action = confirming; confirming = null
                        if (action == "COMPLETE") onComplete() else onStop()
                    }, !busy)
                    Spacer(Modifier.height(8.dp))
                    AuthorityAction("Go back", "pending_cancel", { confirming = null }, !busy, false)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        when {
            state.offline -> AuthorityAction("Network settings", "pending_network_settings",
                onOpenNetworkSettings, enabled = !busy)
            authNeeded -> AuthorityAction("Sign in", "pending_auth", onAuthenticate, enabled = !busy)
            retryAvailable -> {
                AuthorityAction("Try again", "pending_retry", onCheck, enabled = !busy)
                Spacer(Modifier.height(10.dp))
                AuthorityAction("Network settings", "pending_network_settings", onOpenNetworkSettings,
                    enabled = !busy, primary = false)
            }
            unavailable -> AuthorityAction("Network settings", "pending_network_settings",
                onOpenNetworkSettings, enabled = !busy, primary = false)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun AuthorityAction(label: String, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, primary: Boolean = true) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
        .background(if (primary) AuthorityBrand else AuthorityBackground)
        .border(1.dp, if (primary) AuthorityBrand else Color(0xFFDFE3E2), RoundedCornerShape(18.dp))
        .onPreviewKeyEvent { event ->
            if (enabled && event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                onClick()
                true
            } else false
        }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .focusable(enabled)
        .testTag(tag).padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) Color.White else AuthorityBrand,
            style = authorityStyle(16, 22, FontWeight.Bold))
    }
}

@Composable
private fun AuthoritySummary(label: String, value: String) = ProbableEndResultSummaryRow(label, value,
    authorityStyle(), authorityStyle(weight = FontWeight.Medium))

private fun authorityTime(at: Instant): String = DateTimeFormatter.ofPattern("h:mm a")
    .format(at.atZone(ZoneId.systemDefault()))
