package com.journeycontinuity.app.ui.destination

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeycontinuity.app.R
import com.journeycontinuity.app.ui.TravellerNavigationRippleRadius
import com.journeycontinuity.app.ui.TravellerNavigationRippleColor
import com.journeycontinuity.app.ui.TravellerBackVisual

private val mapBackground = Color(0xFFFBFBF8)
private val mapInk = Color(0xFF161B20)
private val mapMuted = Color(0xFF676F74)
private val mapBrand = Color(0xFF1B3250)

/** Parent owns safe drawing/IME insets. This screen never owns Journey admission. */
@Composable
fun DestinationMapScreen(
    viewModel: DestinationMapViewModel,
    onBackToPicker: () -> Unit,
    onDraft: (DestinationDraft) -> Unit,
    onChooseAreaAgain: () -> Unit,
    observeConnectivity: Boolean = true,
    mapContent: @Composable (Modifier, DestinationMapState) -> Unit = { modifier, state ->
        NativeDestinationMap(modifier, state, viewModel)
    },
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val positioning = state as? DestinationMapState.Positioning
    val naming = state as? DestinationMapState.Naming
    val area = positioning?.area ?: naming?.point?.area ?: return
    val phase = positioning?.phase
    val unit = area.unit.canonicalName
    val back = { if (!viewModel.back()) { viewModel.close(); onBackToPicker() } }
    BackHandler(onBack = back)
    if (observeConnectivity) ObserveDestinationMapConnectivity(viewModel)
    val copy = when (phase) {
        MapPinPhase.Initializing -> Triple("Loading your selected area…", "Loading map", "Wait while Alabarin loads this area.")
        is MapPinPhase.Checking -> Triple("Checking this location…", "Checking location", "Wait while Alabarin checks this point.")
        MapPinPhase.OutsideSelectedArea -> Triple("This pin is outside $unit. Move it inside $unit.", "Outside selected area", "Move the pin before you can use this location.")
        MapPinPhase.BoundaryAmbiguous -> Triple("This pin is at an area boundary. Move it further inside $unit.", "Area boundary", "Move the pin further inside $unit to continue.")
        MapPinPhase.CoverageUnresolved -> Triple("We can’t verify this exact point in $unit.", "Can’t verify this point", "Move the pin or use $unit as your destination.")
        MapPinPhase.VerificationUnavailable -> Triple("Exact locations can’t be verified right now.", "Verification unavailable", "Try again or use $unit as your destination.")
        MapPinPhase.OfflineUnverified -> Triple("Exact locations need an internet connection.", "You’re offline", "Connect to verify this point, or use $unit instead.")
        MapPinPhase.InvalidContext -> Triple("We couldn’t load your selected area. Go back and choose it again.", "Selected area unavailable", "Choose the area again before selecting a point.")
        MapPinPhase.MapLoadError -> Triple("The map can’t be loaded right now.", "Map unavailable", "Try again or use $unit as your destination.")
        else -> Triple(if (naming != null && trimmedMapName(naming.name) != null)
            "Pin set. Add a name if it helps you recognize this place."
            else if (naming != null) "Pin set. You can name this place or continue without a name."
            else "Move the map until the pin is on your destination.",
            "$unit, ${area.region.canonicalName}", "You can name this place after selecting it.")
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(mapBackground)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = if (maxHeight >= 700.dp) 60.dp else 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .clickable(interactionSource = null,
                        indication = ripple(bounded = false, radius = TravellerNavigationRippleRadius,
                            color = TravellerNavigationRippleColor),
                        role = Role.Button, onClick = back)
                    .testTag("map_back").semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center) {
                    TravellerBackVisual()
                }
                Spacer(Modifier.width(8.dp))
                Text("Choose on map", color = mapInk, fontSize = 22.sp, lineHeight = 27.sp,
                    fontWeight = FontWeight.Bold)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compact = maxHeight < 400.dp || LocalDensity.current.fontScale > 1.5f
                val mapHeight = when {
                    maxHeight >= 600.dp && !compact -> 454.dp
                    compact && maxHeight >= 300.dp -> (maxHeight * 0.5f).coerceAtLeast(160.dp)
                    else -> (maxHeight * if (compact) 0.4f else 0.65f).coerceAtMost(454.dp)
                }
                Column(Modifier.fillMaxSize()) {
                    if (!compact) {
                        Spacer(Modifier.height(16.dp))
                        Text(copy.first, color = mapMuted, fontSize = 14.sp, lineHeight = 20.sp,
                            modifier = Modifier.testTag("map_helper"))
                        Spacer(Modifier.height(24.dp))
                    }
                    // The entire viewport stays visible; only the supporting copy/name pane scrolls.
                    // Native attribution must not disappear beneath a clipped scrolling map.
                    Box(Modifier.fillMaxWidth().height(mapHeight).clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFF0F1F0)).testTag("map_viewport")) {
                        mapContent(Modifier.fillMaxSize(), state)
                        // The circle's centre, not the label or a marker's tip, is the camera target.
                        Box(Modifier.align(Alignment.Center).size(50.dp).background(mapBrand, CircleShape)
                            .testTag("map_fixed_pin").semantics { contentDescription = "Destination pin" },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(16.dp).background(Color.White, CircleShape))
                        }
                        // On short/large-font viewports the area is already in the summary; leave controls clear.
                        if (phase != MapPinPhase.InvalidContext && mapHeight >= 260.dp && LocalDensity.current.fontScale <= 1.5f) {
                            Text("$unit, ${area.region.canonicalName}", color = mapInk, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.align(Alignment.Center).offset(y = 52.dp)
                                    .background(mapBackground.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp))
                        }
                    }
                        Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .testTag("map_content")) {
                        if (compact) {
                            Text(copy.first, color = mapMuted, fontSize = 14.sp, lineHeight = 20.sp,
                                modifier = Modifier.testTag("map_helper"))
                            Spacer(Modifier.height(12.dp))
                        }
                        if (naming == null) {
                            Column(Modifier.fillMaxWidth().background(Color(0xFFF4F3F0), RoundedCornerShape(14.dp))
                                .padding(14.dp).testTag("map_summary")) {
                                Text("Pinned location", color = mapInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                Text(copy.second, color = mapMuted, fontSize = 13.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(copy.third, color = mapMuted, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        } else {
                            Text("Name this place (optional)", color = mapInk, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            DestinationInput(naming.name, viewModel::updateName,
                                "e.g. Grandma’s house", "map_optional_name", 54.dp, 4.dp,
                                isError = naming.issue != null, imeAction = ImeAction.Done,
                                hintFontSize = 13.sp, label = "Name this place (optional)",
                                capitalization = KeyboardCapitalization.Words,
                                autoCorrectEnabled = true)
                            Spacer(Modifier.height(8.dp))
                            Text(when (naming.issue) {
                                MapNameIssue.TOO_LONG -> "Use 160 characters or fewer."
                                MapNameIssue.CONTROL_CHARACTERS -> "Remove control characters from the name."
                                null -> if (naming.label == MapNamingLabel.Resolving) "Finding a location label…"
                                    else "Optional. If left blank, Alabarin will use a simple location label."
                            }, color = if (naming.issue == null) mapMuted else Color(0xFF992E2E),
                                fontSize = 12.sp, lineHeight = 17.sp,
                                modifier = Modifier.testTag("map_name_helper"))
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                naming != null -> MapAction("Use this location", "map_use_location",
                    naming.issue == null && naming.label is MapNamingLabel.Ready) {
                    viewModel.finish()?.let(onDraft)
                }
                phase == MapPinPhase.InvalidContext -> MapAction("Choose area again", "map_choose_area") {
                    viewModel.close(); onChooseAreaAgain()
                }
                phase == MapPinPhase.VerificationUnavailable || phase == MapPinPhase.MapLoadError -> {
                    TextButton(onClick = { viewModel.locality()?.let(onDraft) }, modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp).testTag("map_use_locality")) {
                        Text("Use $unit as destination", color = mapBrand)
                    }
                    MapAction("Try again", "map_retry", onClick = viewModel::retry)
                }
                phase == MapPinPhase.CoverageUnresolved || phase == MapPinPhase.OfflineUnverified ->
                    MapAction("Use $unit as destination", "map_use_locality") { viewModel.locality()?.let(onDraft) }
                else -> MapAction("Continue", "map_continue", phase is MapPinPhase.Inside,
                    viewModel::continueToNaming)
            }
        }
    }
}

@Composable private fun MapAction(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()
        .heightIn(min = if (tag == "map_use_location") 58.dp else 64.dp).testTag(tag),
        shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = mapBrand,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFD5D7D7), disabledContentColor = mapMuted)) {
        Text(text, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    }
}
