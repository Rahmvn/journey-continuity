package com.journeycontinuity.app.ui.destination

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val background = Color(0xFFFBFBF8)
private val ink = Color(0xFF161B20)
private val muted = Color(0xFF676F74)
private val navy = Color(0xFF1B3250)
private val divider = Color(0xFFDFE3E2)

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
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = back)
                .testTag("destination_back"), contentAlignment = Alignment.CenterStart) {
                Text("‹", color = ink, fontSize = 32.sp)
            }
            Text("Destination", color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(25.dp))
        when (state.page) {
            DestinationPickerPage.SETUP -> {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Heading("Choose destination")
                Spacer(Modifier.height(8.dp))
                Detail("Choose an area first, then search for an exact place.")
                Spacer(Modifier.height(24.dp))
                SelectionRow("State / FCT", state.region?.canonicalName ?: "Choose State or FCT",
                    "destination_region") { viewModel.showRegions() }
                Spacer(Modifier.height(12.dp))
                SelectionRow(state.unitHeading, state.unit?.canonicalName ?: "Choose ${state.unitHeading.lowercase()}",
                    "destination_local_unit", enabled = state.region != null) { viewModel.showUnits() }
                Spacer(Modifier.height(24.dp))
                if (state.unit != null) {
                    Action("Search places", "destination_search_open") { viewModel.showSearch() }
                    Spacer(Modifier.height(12.dp))
                    SecondaryAction("Use ${state.unit.canonicalName} as destination", "destination_locality") {
                        viewModel.selectLocality()?.let { onDraft(it.value) }
                    }
                }
                }
            }
            DestinationPickerPage.REGIONS -> {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("destination_regions")) {
                    item {
                        Heading("State / FCT")
                        Spacer(Modifier.height(16.dp))
                        CatalogueSearch(state.regionFilter, "Search state or FCT", "destination_region_filter",
                            viewModel::updateRegionFilter)
                        Spacer(Modifier.height(16.dp))
                    }
                    items(state.filteredRegions, key = { it.id }) { region ->
                        ListRow(region.canonicalName, null, "region_${region.id}", state.region?.id == region.id) {
                            viewModel.selectRegion(region.id)
                        }
                    }
                }
            }
            DestinationPickerPage.LOCAL_UNITS -> {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("destination_units")) {
                    item {
                        Heading(state.unitHeading)
                        Detail(state.region?.canonicalName.orEmpty())
                        Spacer(Modifier.height(16.dp))
                        CatalogueSearch(state.unitFilter, "Search ${state.unitHeading.lowercase()}",
                            "destination_unit_filter", viewModel::updateUnitFilter)
                        Spacer(Modifier.height(16.dp))
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
                    Heading("Search places")
                    Detail(listOfNotNull(state.region?.canonicalName, state.unit?.canonicalName)
                        .joinToString(" · "))
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = state.query, onValueChange = viewModel::updateQuery,
                        modifier = Modifier.fillMaxWidth().testTag("destination_query"),
                        placeholder = { Text("Search for a place or road") }, singleLine = true,
                        isError = state.queryIssue in setOf(DestinationQueryIssue.TOO_LONG,
                            DestinationQueryIssue.CONTROL_CHARACTERS))
                    Spacer(Modifier.height(16.dp))
                }
                when (state.searchState) {
                    DestinationSearchState.IDLE -> item {
                        Detail(when (state.queryIssue) {
                            DestinationQueryIssue.TOO_LONG -> "Use no more than 120 characters."
                            DestinationQueryIssue.CONTROL_CHARACTERS -> "Use a single line without control characters."
                            else -> "Enter at least 3 characters to search."
                        })
                    }
                    DestinationSearchState.LOADING -> item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = navy, strokeWidth = 2.dp)
                        Text("Searching places…", Modifier.padding(start = 12.dp), color = ink)
                        }
                    }
                    DestinationSearchState.RESULTS -> {
                        itemsIndexed(state.results,
                            key = { index, result -> "$index:${result.provider}:${result.providerPlaceId}" }) { _, result ->
                            ListRow(result.primaryLabel, result.secondaryLabel,
                                "result_${result.providerPlaceId ?: result.primaryLabel}") {
                                when (val action = viewModel.selectResult(result)) {
                                    is DestinationPickerAction.Draft -> onDraft(action.value)
                                    is DestinationPickerAction.Map -> onMapRequest(action.value)
                                    null -> Unit
                                }
                            }
                            if (result.suitability == SearchSuitability.NEEDS_MAP_PIN) {
                                Text("Choose exact point on map", color = muted, fontSize = 13.sp,
                                    modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                        item {
                            Spacer(Modifier.height(20.dp))
                            Detail("Can't find the place?")
                            SecondaryAction("Choose on map instead", "destination_map_request") {
                                viewModel.requestMap()?.let { onMapRequest(it.value) }
                            }
                        }
                    }
                    DestinationSearchState.NO_RESULTS -> item {
                        Heading("No places found")
                        Detail("Try another search, or use the selected area as your destination.")
                        Spacer(Modifier.height(20.dp))
                        state.unit?.let { unit ->
                            SecondaryAction("Use ${unit.canonicalName} as destination", "destination_locality") {
                                viewModel.selectLocality()?.let { onDraft(it.value) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SecondaryAction("Choose on map", "destination_map_request") {
                            viewModel.requestMap()?.let { onMapRequest(it.value) }
                        }
                    }
                    DestinationSearchState.SEARCH_UNAVAILABLE -> item {
                        Heading("Place search is unavailable right now")
                        Detail("Your selected area is kept. Try again when search is available.")
                        Spacer(Modifier.height(20.dp))
                        Action("Try again", "destination_retry", viewModel::retry)
                        Spacer(Modifier.height(12.dp))
                        SecondaryAction("Choose on map", "destination_map_request") {
                            viewModel.requestMap()?.let { onMapRequest(it.value) }
                        }
                    }
                    DestinationSearchState.OFFLINE -> item {
                        Heading("Place search needs an internet connection")
                        Detail("${state.region?.canonicalName} · ${state.unit?.canonicalName} is kept.")
                        Spacer(Modifier.height(20.dp))
                        Action("Try again", "destination_retry", viewModel::retry)
                        Spacer(Modifier.height(12.dp))
                        state.unit?.let { unit ->
                            SecondaryAction("Use ${unit.canonicalName} as destination", "destination_locality") {
                                viewModel.selectLocality()?.let { onDraft(it.value) }
                            }
                        }
                    }
                    DestinationSearchState.VERIFICATION_UNAVAILABLE -> item {
                        Heading("We can’t verify exact places right now")
                        Detail("Exact places need verification. You can still use the selected area as the destination.")
                        Spacer(Modifier.height(20.dp))
                        Action("Try again", "destination_retry", viewModel::retry)
                        Spacer(Modifier.height(12.dp))
                        state.unit?.let { unit ->
                            SecondaryAction("Use ${unit.canonicalName}", "destination_locality") {
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

@Composable private fun Heading(value: String) {
    Text(value, color = ink, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
}
@Composable private fun Detail(value: String) {
    Text(value, color = muted, fontSize = 15.sp, lineHeight = 22.sp)
}
@Composable private fun CatalogueSearch(value: String, placeholder: String, tag: String,
    onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(tag), singleLine = true,
        placeholder = { Text(placeholder) })
}
@Composable private fun SelectionRow(label: String, value: String, tag: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 74.dp).background(Color.White, RoundedCornerShape(6.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center) {
        Text(label, color = muted, fontSize = 13.sp)
        Text(value, color = if (enabled) ink else muted, fontSize = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun ListRow(title: String, subtitle: String?, tag: String,
    isSelected: Boolean? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()
        .background(if (isSelected == true) Color(0xFFEEF1F4) else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { if (isSelected != null) selected = isSelected }
        .testTag(tag).padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (isSelected == true) Text("✓", color = navy, modifier = Modifier.padding(start = 12.dp))
        }
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = muted, fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = divider)
    }
}
@Composable private fun Action(title: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(navy, RoundedCornerShape(6.dp))
        .clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun SecondaryAction(title: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
        .testTag(tag).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(title, color = navy, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
