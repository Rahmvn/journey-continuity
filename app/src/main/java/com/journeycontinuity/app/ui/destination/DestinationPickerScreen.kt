package com.journeycontinuity.app.ui.destination

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeycontinuity.app.R
import com.journeycontinuity.app.ui.TravellerNavigationRippleRadius
import com.journeycontinuity.app.ui.TravellerNavigationRippleColor
import com.journeycontinuity.app.ui.TravellerBackVisual
import com.journeycontinuity.app.domain.AdminRegion
import com.journeycontinuity.app.domain.AdminRegionType

private val background = Color(0xFFFBFBF8)
private val ink = Color(0xFF161B20)
private val muted = Color(0xFF676F74)
private val navy = Color(0xFF1B3250)
private val disabled = Color(0xFF808587)
private val divider = Color(0xFFD1D2CF)

/** The host owns safe-area and IME insets. This screen is a draft picker, never a Journey form. */
@Composable
fun DestinationPickerScreen(
    viewModel: DestinationPickerViewModel,
    onClose: () -> Unit,
    onDraft: (DestinationDraft) -> Unit,
    onMapRequest: (MapRefinementRequest) -> Unit,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val back = { if (!viewModel.back()) onClose() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(background).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(28.dp))
        DestinationHeader(when (state.page) {
            DestinationPickerPage.SETUP -> if (state.selectedDraft == null) "Choose destination" else "Change destination"
            DestinationPickerPage.REGIONS -> "State / FCT"
            DestinationPickerPage.LOCAL_UNITS -> state.unitHeading
            DestinationPickerPage.SEARCH -> "Choose destination"
        }, back)
        when (state.page) {
            DestinationPickerPage.SETUP -> {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(24.dp))
                    Detail(if (state.selectedDraft == null)
                        "Choose the area, then the exact place or landmark."
                        else "Update the destination before this Journey starts.")
                    Spacer(Modifier.height(28.dp))
                    SelectionRow("State / FCT", state.region?.destinationUiName(), "Choose state",
                        "destination_region") { viewModel.showRegions() }
                    Spacer(Modifier.height(22.dp))
                    SelectionRow(state.unitHeading, state.unit?.canonicalName,
                        "Choose ${state.unitHeading.lowercase()}", "destination_local_unit",
                        enabled = state.region != null) { viewModel.showUnits() }
                    Spacer(Modifier.height(22.dp))
                    SelectionRow("Place or landmark", state.selectedDraft?.displayLabel,
                        "Search place or landmark", "destination_search_open",
                        enabled = state.unit != null) { viewModel.showSearch() }
                    Spacer(Modifier.height(10.dp))
                    Text(when {
                        state.selectedDraft != null -> "Or choose a different exact location on the map."
                        state.unit != null -> "Search for a place or choose a point on the map."
                        else -> "Choose State and LGA first."
                    },
                        color = muted, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(22.dp))
                    SecondaryAction(if (state.selectedDraft == null) "Choose on map"
                        else "Choose another place on map", "destination_map_request",
                        enabled = state.unit != null) {
                        viewModel.requestMap()?.let { onMapRequest(it.value) }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            DestinationPickerPage.REGIONS -> {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("destination_regions")) {
                    item {
                        Spacer(Modifier.height(24.dp))
                        CatalogueSearch(state.regionFilter, "Search state or FCT", "destination_region_filter",
                            viewModel::updateRegionFilter)
                        Spacer(Modifier.height(26.dp))
                    }
                    items(state.filteredRegions, key = { it.id }) { region ->
                        ListRow(region.destinationUiName(), null, "region_${region.id}", state.region?.id == region.id) {
                            viewModel.selectRegion(region.id)
                        }
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text("36 states + FCT", color = muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
            DestinationPickerPage.LOCAL_UNITS -> {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("destination_units")) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        Text(state.region?.destinationUiName().orEmpty(), color = muted, fontSize = 14.sp,
                            lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(14.dp))
                        CatalogueSearch(state.unitFilter, "Search ${state.unitHeading.lowercase()}",
                            "destination_unit_filter", viewModel::updateUnitFilter)
                        Spacer(Modifier.height(26.dp))
                    }
                    items(state.filteredUnits, key = { it.id }) { unit ->
                        ListRow(unit.canonicalName, null, "unit_${unit.id}", state.unit?.id == unit.id) {
                            viewModel.selectUnit(unit.id)
                        }
                    }
                }
            }
            DestinationPickerPage.SEARCH -> {
                // One scrolling region includes context, editing and actions. The root owns IME insets.
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("destination_search_content")) {
                item(key = "query") {
                    Spacer(Modifier.height(20.dp))
                    Text(listOfNotNull(state.region?.destinationUiName(), state.unit?.canonicalName)
                        .joinToString("  ·  "), color = muted, fontSize = 14.sp, lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(14.dp))
                    DestinationInput(state.query, viewModel::updateQuery,
                        "Search place or landmark", "destination_query", 54.dp, 14.dp,
                        isError = state.queryIssue in setOf(DestinationQueryIssue.TOO_LONG,
                            DestinationQueryIssue.CONTROL_CHARACTERS),
                        onImeAction = viewModel::retry)
                    Spacer(Modifier.height(28.dp))
                }
                when (state.searchState) {
                    DestinationSearchState.IDLE -> item {
                        Status(when (state.queryIssue) {
                            DestinationQueryIssue.TOO_LONG -> "Use no more than 120 characters."
                            DestinationQueryIssue.CONTROL_CHARACTERS -> "Use a single line without control characters."
                            else -> "Enter at least 3 characters to search."
                        })
                    }
                    DestinationSearchState.LOADING -> item { Status("Searching places…") }
                    DestinationSearchState.RESULTS -> {
                        val needsExactPin = state.results.size == 1 &&
                            state.results.first().suitability == SearchSuitability.NEEDS_MAP_PIN
                        item {
                            Status(if (needsExactPin) "Road found" else "Places and landmarks")
                            Spacer(Modifier.height(12.dp))
                        }
                        itemsIndexed(state.results,
                            key = { index, result -> "$index:${result.provider}:${result.providerPlaceId}" }) { index, result ->
                            ListRow(result.primaryLabel,
                                if (result.suitability == SearchSuitability.NEEDS_MAP_PIN)
                                    "Road result · exact pin required" else result.secondaryLabel,
                                "result_${result.providerPlaceId ?: result.primaryLabel}",
                                highlight = index == 0, showDivider = false) {
                                when (val action = viewModel.selectResult(result)) {
                                    is DestinationPickerAction.Draft -> onDraft(action.value)
                                    is DestinationPickerAction.Map -> onMapRequest(action.value)
                                    null -> Unit
                                }
                            }
                        }
                        item {
                            // The approved footer starts below two 72dp result rows at y418.
                            // Keep that same anchor when only one result is returned.
                            Spacer(Modifier.height((180 - 84 * state.results.size).coerceAtLeast(12).dp))
                            HorizontalDivider(color = divider)
                            Spacer(Modifier.height(30.dp))
                            Text(if (needsExactPin) "This road needs an exact point on the map."
                                else "Can't find the place?", color = muted, fontSize = 13.sp,
                                lineHeight = 18.sp)
                            Spacer(Modifier.height(10.dp))
                            SecondaryAction(if (needsExactPin) "Set exact point on map"
                                else "Choose on map instead", "destination_map_request") {
                                if (needsExactPin) {
                                    val action = viewModel.selectResult(state.results.first())
                                    if (action is DestinationPickerAction.Map) onMapRequest(action.value)
                                } else viewModel.requestMap()?.let { onMapRequest(it.value) }
                            }
                        }
                    }
                    DestinationSearchState.NO_RESULTS -> item {
                        Status("No places found")
                        Spacer(Modifier.height(192.dp))
                        SearchFooter("Try another search or choose on map.",
                            "Choose on map", "destination_map_request") {
                            viewModel.requestMap()?.let { onMapRequest(it.value) }
                        }
                    }
                    DestinationSearchState.SEARCH_UNAVAILABLE -> item {
                        Status("Place search is unavailable right now")
                        Spacer(Modifier.height(12.dp))
                        ListRow("Try again", "State and LGA are kept", "destination_retry",
                            highlight = true, trailing = "↻", showDivider = false,
                            onClick = viewModel::retry)
                        Spacer(Modifier.height(96.dp))
                        SearchFooter("You can still choose the destination on the map.",
                            "Choose on map", "destination_map_request") {
                            viewModel.requestMap()?.let { onMapRequest(it.value) }
                        }
                    }
                    DestinationSearchState.OFFLINE -> item {
                        Status("Place search needs an internet connection")
                        Spacer(Modifier.height(192.dp))
                        state.unit?.let { unit ->
                            SearchFooter("${state.region?.destinationUiName()} · ${unit.canonicalName} is kept.",
                                "Use ${unit.canonicalName} as destination", "destination_locality") {
                                viewModel.selectLocality()?.let { onDraft(it.value) }
                            }
                        }
                    }
                    DestinationSearchState.VERIFICATION_UNAVAILABLE -> item {
                        Status("We can't verify exact places right now")
                        Spacer(Modifier.height(12.dp))
                        ListRow("Try again", "Exact places need verification", "destination_retry",
                            highlight = true, trailing = "↻", showDivider = false,
                            onClick = viewModel::retry)
                        Spacer(Modifier.height(96.dp))
                        state.unit?.let { unit ->
                            SearchFooter("You can still use the selected area as the destination.",
                                "Use ${unit.canonicalName}", "destination_locality") {
                                viewModel.selectLocality()?.let { onDraft(it.value) }
                            }
                        }
                    }
                }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** UI label only. The catalogue's canonical name and stable ID remain in DestinationDraft. */
private fun AdminRegion.destinationUiName(): String =
    if (type == AdminRegionType.FCT) "FCT - Abuja" else canonicalName

@Composable private fun DestinationHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
            .clickable(interactionSource = null,
                indication = ripple(bounded = false, radius = TravellerNavigationRippleRadius,
                    color = TravellerNavigationRippleColor),
                role = Role.Button, onClick = onBack)
            .testTag("destination_back").semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center) {
            TravellerBackVisual()
        }
        Spacer(Modifier.width(8.dp))
        Text(title, color = ink, fontSize = 22.sp, lineHeight = 27.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun Detail(value: String) {
    Text(value, color = muted, fontSize = 15.sp, lineHeight = 21.sp)
}
@Composable private fun CatalogueSearch(value: String, placeholder: String, tag: String,
    onValueChange: (String) -> Unit) {
    DestinationInput(value, onValueChange, placeholder, tag, 52.dp, 14.dp,
        imeAction = ImeAction.Done)
}
@Composable private fun SelectionRow(label: String, value: String?, hint: String, tag: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Text(label, color = if (enabled) ink else disabled, fontSize = 16.sp,
        lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().height(58.dp)
        .border(1.dp, if (enabled) muted.copy(alpha = 0.75f)
            else divider.copy(alpha = 0.55f), shape)
        .clip(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(value ?: hint, color = if (value != null) ink else muted,
            fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("›", color = if (enabled) muted else disabled, fontSize = 20.sp, lineHeight = 24.sp)
    }
}
@Composable private fun ListRow(title: String, subtitle: String?, tag: String,
    isSelected: Boolean? = null, highlight: Boolean = isSelected == true,
    trailing: String? = null, showDivider: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 62.dp else 72.dp)
            .clip(RoundedCornerShape(if (subtitle == null) 10.dp else 12.dp))
            .background(if (highlight) Color(0xFFEEF1F4) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (isSelected != null) selected = isSelected }
            .testTag(tag).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = ink, fontSize = 16.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = muted, fontSize = 13.sp, lineHeight = 18.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isSelected == true || trailing != null || subtitle != null) {
                Text(if (isSelected == true) "✓" else trailing ?: "›",
                    color = if (isSelected == true) navy else muted,
                    fontSize = 20.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (showDivider) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = divider)
            Spacer(Modifier.height(2.dp))
        } else Spacer(Modifier.height(12.dp))
    }
}
@Composable private fun Status(value: String) {
    Text(value, color = muted, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
}
@Composable private fun SearchFooter(message: String, title: String, tag: String, onClick: () -> Unit) {
    HorizontalDivider(color = divider)
    Spacer(Modifier.height(30.dp))
    Text(message, color = muted, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(10.dp))
    SecondaryAction(title, tag, onClick = onClick)
}
@Composable private fun SecondaryAction(title: String, tag: String, enabled: Boolean = true,
    onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag), contentAlignment = Alignment.Center) {
        Text(title, color = if (enabled) navy else disabled, fontSize = 15.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Exact-height field shared by catalogue, place search and optional map naming. */
@Composable internal fun DestinationInput(value: String, onValueChange: (String) -> Unit,
    hint: String, tag: String, height: Dp, radius: Dp, isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Search, hintFontSize: TextUnit = 15.sp,
    label: String = hint, onImeAction: (() -> Unit)? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    autoCorrectEnabled: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val finishEditing = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onImeAction?.invoke()
    }
    val shape = RoundedCornerShape(radius)
    val border = when {
        isError -> Color(0xFF992E2E)
        focused -> navy
        else -> muted.copy(alpha = 0.6f)
    }
    BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(color = ink, fontSize = 16.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(navy),
        keyboardOptions = KeyboardOptions(imeAction = imeAction,
            capitalization = capitalization, autoCorrectEnabled = autoCorrectEnabled),
        keyboardActions = KeyboardActions(onSearch = { finishEditing() },
            onDone = { finishEditing() }),
        modifier = Modifier.fillMaxWidth().height(height).onFocusChanged { focused = it.isFocused }
            .border(1.dp, border, shape).clip(shape).testTag(tag)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(horizontal = 15.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = muted, fontSize = hintFontSize, lineHeight = 21.sp)
                inner()
            }
        })
}
