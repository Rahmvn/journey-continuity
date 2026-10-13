package com.journeycontinuity.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ripple
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.journeycontinuity.app.data.repository.isPendingAuthority
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.journeycontinuity.app.R
import com.journeycontinuity.app.degraded.SmsFallbackStatus
import com.journeycontinuity.app.domain.Journey
import com.journeycontinuity.app.domain.JourneyStatus
import com.journeycontinuity.app.data.local.ProbableEndPresentationEntity
import com.journeycontinuity.app.data.local.ProbableEndPresentationStatus
import com.journeycontinuity.app.data.repository.ProbableEndResponseRequest
import com.journeycontinuity.app.data.repository.ProbableEndTravellerResponse
import com.journeycontinuity.app.data.repository.responseRequest
import com.journeycontinuity.app.telemetry.ForegroundLocationAccess
import com.journeycontinuity.app.trusted.TrustedContactStatus
import com.journeycontinuity.app.ui.destination.DestinationDraft
import com.journeycontinuity.app.ui.destination.DestinationPickerScreen
import com.journeycontinuity.app.ui.destination.DestinationPickerViewModel
import com.journeycontinuity.app.ui.destination.MapRefinementRequest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal suspend fun pollTrustedContacts(refresh: () -> Unit, intervalMillis: Long = 30_000L) {
    require(intervalMillis > 0)
    while (true) {
        delay(intervalMillis)
        refresh()
    }
}

private val Background = Color(0xFFFBFBF8)
private val SheetBackground = Color(0xFFFEFDFB)
private val Brand = Color(0xFF1B3250)
private val Heading = Color(0xFF161B20)
private val Body = Color(0xFF676F74)
private val Border = Color(0xFFDFE3E2)
private val Warning = Color(0xFFB0822F)
private val ContextBlue = Color(0xFF3E5C74)
private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)
private val InstrumentSans = FontFamily(Font(R.font.instrument_sans_semibold, FontWeight.SemiBold))
private val TimeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val ArrivalFormat = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a")

/** The route lives only in the Activity-scoped ViewModel: recreation retains it, process restart opens Home. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun JourneyProductRoot(
    viewModel: JourneyViewModel,
    locationUiState: LocationUiState,
    smsFallbackStatus: SmsFallbackStatus,
    accountEmail: String?,
    // Compatibility hosts only. MainActivity supplies the structured callback and picker.
    onStartRequested: (String, Long) -> Unit = { _, _ -> },
    onRetryMonitoring: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRequestSmsPermissions: () -> Unit,
    onSelectSmsSubscription: (Int) -> Unit,
    onRequestPhoneStatePermission: () -> Unit = {},
    onRefreshSmsFallback: () -> Unit = {},
    accountViewModel: TravellerAccountViewModel? = null,
    onSignOutRequested: () -> Unit = {},
    onConfirmSignOutRequested: () -> Unit = {},
    onCancelSignOutRequested: () -> Unit = {},
    onConfirmActiveSignOutRequested: () -> Unit = {},
    onCancelActiveSignOutRequested: () -> Unit = {},
    onReauthenticateRequested: () -> Unit = {},
    destinationPickerViewModel: DestinationPickerViewModel? = null,
    onMapRefinementRequested: (MapRefinementRequest) -> Unit = {},
    destinationMapViewModel: com.journeycontinuity.app.ui.destination.DestinationMapViewModel? = null,
    onStructuredStartRequested: (com.journeycontinuity.app.domain.JourneyStartRequest) -> Unit = {},
    probableEndPresentation: ProbableEndPresentationEntity? = null,
    probableEndForegroundEpisodeId: String? = null,
    onProbableEndRendered: (String, String, View) -> Unit = { _, _, _ -> },
    onProbableEndDismissed: () -> Unit = {},
    onProbableEndReopen: (String, String) -> Unit = { _, _ -> },
    onProbableEndResponse: (ProbableEndResponseRequest, ProbableEndTravellerResponse) -> Unit = { _, _ -> },
    probableEndResponseInFlight: Boolean = false,
    probableEndResponseError: String? = null,
    authorityState: TravellerAuthorityUiState = TravellerAuthorityUiState(records = emptyList()),
    onAuthorityCheck: (String) -> Unit = {},
    onAuthorityComplete: (String) -> Unit = {},
    onAuthorityStop: (String) -> Unit = {},
    onAuthorityResume: () -> Unit = {},
    onOpenNetworkSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accountState = accountViewModel?.state?.collectAsStateWithLifecycle()?.value
    val accountCanDismiss = accountState?.let {
        !it.saving && it.signOutStatus !in setOf(
            AccountSignOutStatus.CHECKING, AccountSignOutStatus.CONFIRMING,
            AccountSignOutStatus.SIGNING_OUT, AccountSignOutStatus.CONFIRMING_ACTIVE,
            AccountSignOutStatus.STOPPING_ACTIVE)
    } ?: true
    val currentAccountCanDismiss by rememberUpdatedState(accountCanDismiss)
    val accountSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || currentAccountCanDismiss })
    val journeySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contactsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resilienceSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val checkpointSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    var accountSheetHeightPx by remember { mutableFloatStateOf(0f) }
    var journeySheetHeightPx by remember { mutableFloatStateOf(0f) }
    var contactsSheetHeightPx by remember { mutableFloatStateOf(0f) }
    var resilienceSheetHeightPx by remember { mutableFloatStateOf(0f) }
    var checkpointSheetHeightPx by remember { mutableFloatStateOf(0f) }
    var accountCloseInFlight by remember { mutableStateOf(false) }
    var keepJourneySheetForEnd by remember { mutableStateOf(false) }
    var endPageDrawn by remember { mutableStateOf(false) }
    LaunchedEffect(state.productRoute, endPageDrawn) {
        if (state.productRoute == JourneyProductRoute.END && endPageDrawn) {
            // Release the separate dialog window only after the Activity has drawn its destination.
            withFrameNanos { }
            keepJourneySheetForEnd = false
        } else if (state.productRoute != JourneyProductRoute.END) {
            keepJourneySheetForEnd = false
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val journeySheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.84f
    val snackbar = remember { SnackbarHostState() }
    var accountOpen by remember { mutableStateOf(false) }
    var dismissedProbableEndId by remember { mutableStateOf<String?>(null) }
    val pendingAuthority = authorityState.pending
    var pendingOpen by rememberSaveable(pendingAuthority?.journey?.id) { mutableStateOf(false) }
    val showProbableEnd = probableEndPresentation != null &&
        authorityState.records != null &&
        authorityState.records.none { it.journey.id == state.activeJourney?.id && it.isPendingAuthority() } &&
        probableEndPresentation.journeyId == state.activeJourney?.id &&
        probableEndPresentation.id == probableEndForegroundEpisodeId &&
        dismissedProbableEndId != probableEndPresentation.id &&
        !accountOpen &&
        state.productRoute in setOf(JourneyProductRoute.HOME, JourneyProductRoute.ACTIVE)
    val pickerState = destinationPickerViewModel?.state?.collectAsStateWithLifecycle()?.value
    val pickerOpen = pickerState?.isOpen == true
    val mapState = destinationMapViewModel?.state?.collectAsStateWithLifecycle()?.value
    val mapOpen = mapState != null && mapState != com.journeycontinuity.app.ui.destination.DestinationMapState.Closed
    val selectedDestinationDraft = pickerState?.selectedDraft
    LaunchedEffect(state.activeJourney?.id) {
        if (state.activeJourney != null) destinationPickerViewModel?.clearAdmittedDraft()
    }
    LaunchedEffect(state.productRoute) {
        if (state.productRoute != JourneyProductRoute.START) {
            destinationPickerViewModel?.close()
            destinationMapViewModel?.close()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshTrustedContacts()
                onAuthorityResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.productRoute, lifecycleOwner) {
        if (state.productRoute == JourneyProductRoute.CONTACTS) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pollTrustedContacts(viewModel::refreshTrustedContacts)
            }
        }
    }
    LaunchedEffect(state.productRoute) {
        if (state.productRoute == JourneyProductRoute.RESILIENCE) onRefreshSmsFallback()
    }
    if (pendingAuthority != null && (pendingOpen || state.productRoute == JourneyProductRoute.END &&
            state.endStopPending &&
            pendingAuthority.journey.id == state.endFlowJourneyId ||
            state.productRoute == JourneyProductRoute.ACTIVE && pendingAuthority.journey.id == state.activeJourney?.id)) {
        PendingAuthorityScreen(pendingAuthority, authorityState,
            onBack = { pendingOpen = false; viewModel.backToHome() },
            onCheck = { onAuthorityCheck(pendingAuthority.journey.id) },
            onComplete = { pendingOpen = true; onAuthorityComplete(pendingAuthority.journey.id) },
            onStop = { onAuthorityStop(pendingAuthority.journey.id) },
            onAuthenticate = onReauthenticateRequested,
            onOpenNetworkSettings = onOpenNetworkSettings)
        return
    }
    BackHandler(!pickerOpen && !mapOpen && state.productRoute != JourneyProductRoute.HOME &&
        state.productRoute != JourneyProductRoute.ACTIVE) {
        if (state.productRoute == JourneyProductRoute.END) viewModel.returnToActiveFromEnd()
        else if (state.productRoute == JourneyProductRoute.END_RESULT) viewModel.doneWithEndResult()
        else if (state.productRoute == JourneyProductRoute.CHECKPOINT) viewModel.backToStart()
        else if (state.productRoute == JourneyProductRoute.CONTACTS ||
            state.productRoute == JourneyProductRoute.RESILIENCE) viewModel.closeUtility()
        else viewModel.backToHome()
    }

    Box(Modifier.fillMaxSize().background(Background).drawWithContent {
        drawContent()
        if (keepJourneySheetForEnd && state.productRoute == JourneyProductRoute.END) {
            endPageDrawn = true
        }
        val visibleSheetState = when {
            accountOpen -> accountSheetState
            (state.productRoute == JourneyProductRoute.ACTIVE || showProbableEnd) &&
                authorityState.records != null && state.activeJourney != null -> journeySheetState
            state.productRoute == JourneyProductRoute.CONTACTS -> contactsSheetState
            state.productRoute == JourneyProductRoute.RESILIENCE -> resilienceSheetState
            state.productRoute == JourneyProductRoute.CHECKPOINT -> checkpointSheetState
            else -> null
        }
        val visibleSheetHeightPx = when (visibleSheetState) {
            accountSheetState -> accountSheetHeightPx
            journeySheetState -> journeySheetHeightPx
            contactsSheetState -> contactsSheetHeightPx
            resilienceSheetState -> resilienceSheetHeightPx
            checkpointSheetState -> checkpointSheetHeightPx
            else -> 0f
        }
        if (visibleSheetState != null && visibleSheetHeightPx > 0f) {
            val offset = try { visibleSheetState.requireOffset() }
                catch (_: IllegalStateException) { size.height }
            val fraction = sheetVisibleFraction(size.height, visibleSheetHeightPx, offset)
            val fullAlpha = if (visibleSheetState == accountSheetState) 0.16f else 0.32f
            if (fraction > 0f) drawRect(Color.Black.copy(alpha = fullAlpha * fraction))
        }
    }.safeDrawingPadding().imePadding()) {
        when {
            state.isRestoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
            state.productRoute == JourneyProductRoute.HOME ||
                state.productRoute == JourneyProductRoute.CONTACTS ||
                state.productRoute == JourneyProductRoute.RESILIENCE ||
                state.productRoute == JourneyProductRoute.ACTIVE -> {
                val resilience = resiliencePresentation(smsFallbackStatus,
                    state.trustedContactsAvailability,
                    activeTrustedContacts(state.trustedContacts).count {
                        it.status == TrustedContactStatus.ACCEPTED
                    })
                JourneyHome(
                    state = state,
                    accountEmail = if (accountState?.reauthenticationNeeded == true)
                        "Account connection needs attention"
                    else accountEmail,
                    resilienceSummary = resilience.homeSummary,
                    onPrimary = {
                        val active = state.activeJourney
                        if (active == null) viewModel.openStart()
                        else {
                            if (dismissedProbableEndId != null && probableEndPresentation != null &&
                                probableEndPresentation.journeyId == active.id)
                                onProbableEndReopen(active.id, probableEndPresentation.candidateEpisodeId)
                            dismissedProbableEndId = null
                            viewModel.openActive()
                        }
                    },
                    onContacts = viewModel::openContacts,
                    onResilience = viewModel::openResilience,
                    onAccount = {
                        accountOpen = true
                        accountViewModel?.load()
                    },
                    pendingAuthority = pendingAuthority,
                    onPendingAuthority = { pendingOpen = true },
                )
                if (state.productRoute == JourneyProductRoute.CONTACTS) {
                    val closeContacts = {
                        sheetScope.launch {
                            contactsSheetState.hide()
                            if (viewModel.uiState.value.productRoute == JourneyProductRoute.CONTACTS)
                                viewModel.closeUtility()
                        }
                        Unit
                    }
                    ModalBottomSheet(
                        onDismissRequest = viewModel::closeUtility,
                        modifier = Modifier.testTag("trusted_contacts_sheet")
                            .onSizeChanged { contactsSheetHeightPx = it.height.toFloat() },
                        sheetState = contactsSheetState,
                        containerColor = SheetBackground,
                        scrimColor = Color.Transparent,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        dragHandle = null,
                    ) {
                        PassiveSheetGrabber()
                        TrustedContactsSheet(
                            state = state,
                            onDismiss = closeContacts,
                            onAdd = viewModel::openAddTrustedContact,
                            onCreate = viewModel::createTrustedContact,
                            onDone = viewModel::doneWithInvitation,
                            onRemove = viewModel::revokeTrustedContact,
                            onRetry = viewModel::refreshTrustedContacts,
                            onShare = { url ->
                                context.startActivity(Intent.createChooser(
                                    trustedContactShareIntent(url), "Share invitation"))
                            },
                        )
                    }
                }
                if (state.productRoute == JourneyProductRoute.RESILIENCE) {
                    val closeResilience = {
                        sheetScope.launch {
                            resilienceSheetState.hide()
                            if (viewModel.uiState.value.productRoute == JourneyProductRoute.RESILIENCE)
                                viewModel.closeUtility()
                        }
                        Unit
                    }
                    ModalBottomSheet(
                        onDismissRequest = viewModel::closeUtility,
                        modifier = Modifier.testTag("resilience_sheet")
                            .onSizeChanged { resilienceSheetHeightPx = it.height.toFloat() },
                        sheetState = resilienceSheetState,
                        containerColor = Color(0xFFFEFEFC),
                        scrimColor = Color.Transparent,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        dragHandle = null,
                    ) {
                        PassiveSheetGrabber()
                        ResilienceSheet(
                            presentation = resilience,
                            status = smsFallbackStatus,
                            page = state.resiliencePage,
                            onDismiss = closeResilience,
                            onAddContact = viewModel::openContacts,
                            onAllowSms = onRequestSmsPermissions,
                            onAllowPhoneAccess = onRequestPhoneStatePermission,
                            onChooseSim = viewModel::openChooseSim,
                            onSelectSim = { choice ->
                                onSelectSmsSubscription(choice)
                                viewModel.returnToResilienceStatus()
                            },
                        )
                    }
                }
            }
            state.productRoute == JourneyProductRoute.START ||
                state.productRoute == JourneyProductRoute.CHECKPOINT -> {
                if (mapOpen && destinationMapViewModel != null && destinationPickerViewModel != null) {
                    com.journeycontinuity.app.ui.destination.DestinationMapScreen(destinationMapViewModel,
                        onBackToPicker = {}, onDraft = destinationPickerViewModel::acceptDraft,
                        onChooseAreaAgain = destinationPickerViewModel::showRegions)
                } else if (pickerOpen && destinationPickerViewModel != null) {
                    DestinationPickerScreen(destinationPickerViewModel,
                        onClose = destinationPickerViewModel::close,
                        onDraft = destinationPickerViewModel::acceptDraft,
                        onMapRequest = onMapRefinementRequested)
                } else {
                    JourneyStartScreen(
                        state = state,
                        onDestinationChange = viewModel::updateDestination,
                        onArrivalChange = viewModel::updateExpectedArrival,
                        onBack = viewModel::backToHome,
                        onStart = {
                            if (destinationPickerViewModel != null) viewModel.requestStructuredStart(
                                destinationPickerViewModel.state.value.selectedDraft,
                                smsFallbackStatus.ready, onStructuredStartRequested)
                            else viewModel.requestStart(smsFallbackStatus.ready, onStartRequested)
                        },
                        structuredDraft = selectedDestinationDraft,
                        onChooseDestination = destinationPickerViewModel?.let { picker ->
                            { picker.open() }
                        },
                    )
                }
                if (state.productRoute == JourneyProductRoute.CHECKPOINT) {
                    val accepted = state.trustedContacts.any {
                        it.status == TrustedContactStatus.ACCEPTED
                    }
                    ModalBottomSheet(
                        onDismissRequest = viewModel::backToStart,
                        modifier = Modifier.onSizeChanged { checkpointSheetHeightPx = it.height.toFloat() },
                        sheetState = checkpointSheetState,
                        containerColor = SheetBackground,
                        scrimColor = Color.Transparent,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    ) {
                        JourneyCheckpoint(
                            acceptedContact = accepted,
                            onStartAnyway = {
                                if (destinationPickerViewModel != null)
                                    viewModel.startStructuredAnyway(onStructuredStartRequested)
                                else viewModel.startAnyway(onStartRequested)
                            },
                            onSetup = {
                                if (accepted) viewModel.openResilience()
                                else viewModel.openContacts()
                            },
                        )
                    }
                }
            }
            state.productRoute == JourneyProductRoute.END -> {
                if (state.activeJourney?.id == state.endFlowJourneyId && !state.endStopPending) {
                    OrdinaryEndChoiceScreen(state, viewModel::returnToActiveFromEnd,
                        onComplete = { viewModel.endJourney(requireNotNull(state.endFlowJourneyId)) },
                        onStop = { viewModel.stopJourney(requireNotNull(state.endFlowJourneyId)) })
                } else if (state.endStopPending) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Brand)
                    }
                }
            }
            state.productRoute == JourneyProductRoute.END_RESULT -> {
                state.endResult?.takeIf { it.id == state.endFlowJourneyId }?.let { result ->
                    OrdinaryEndResultScreen(result, viewModel::doneWithEndResult)
                }
            }
        }
        if (authorityState.records != null && (state.productRoute == JourneyProductRoute.ACTIVE || showProbableEnd ||
            keepJourneySheetForEnd && state.productRoute == JourneyProductRoute.END &&
                state.activeJourney?.id == state.endFlowJourneyId)) {
            state.activeJourney?.let { journey ->
                ModalBottomSheet(
                    onDismissRequest = {
                        if (showProbableEnd) {
                            dismissedProbableEndId = probableEndPresentation.id
                            onProbableEndDismissed()
                        }
                        viewModel.backToHome()
                    },
                    modifier = Modifier.testTag(if (showProbableEnd) "probable_end_sheet" else "journey_sheet")
                        .onSizeChanged { journeySheetHeightPx = it.height.toFloat() },
                    sheetState = journeySheetState,
                    containerColor = Background,
                    scrimColor = Color.Transparent,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    dragHandle = { JourneySheetHandle() },
                ) {
                    JourneyActiveScreen(
                        journey = journey,
                        state = state,
                        locationUiState = locationUiState,
                        smsFallbackStatus = smsFallbackStatus,
                        onBack = {
                            if (showProbableEnd) {
                                dismissedProbableEndId = probableEndPresentation.id
                                onProbableEndDismissed()
                            }
                            viewModel.backToHome()
                        },
                        onRetry = onRetryMonitoring,
                        onEndCompleted = {
                            endPageDrawn = false
                            viewModel.openEndJourney(journey.id)
                            keepJourneySheetForEnd = viewModel.uiState.value.productRoute == JourneyProductRoute.END
                        },
                        probableEnd = if (showProbableEnd) probableEndPresentation else null,
                        onProbableEndRendered = onProbableEndRendered,
                        onProbableEndResponse = onProbableEndResponse,
                        probableEndResponseInFlight = probableEndResponseInFlight,
                        probableEndResponseError = probableEndResponseError,
                        // Material 3 measures the handle and bottom inset outside this content.
                        modifier = Modifier.fillMaxWidth().height(journeySheetHeight - 40.dp),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)
            .padding(bottom = if (state.productRoute == JourneyProductRoute.HOME) 96.dp else 0.dp))
    }
    if (accountOpen) {
        val presentedState = accountState ?: TravellerAccountState(
            loadError = "Account profile is unavailable. Try again.",
        )
        val dismissAccountSheet = {
            if (accountCanDismiss && !accountCloseInFlight) {
                accountCloseInFlight = true
                sheetScope.launch {
                    try {
                        accountSheetState.hide()
                        if (!accountSheetState.isVisible) accountOpen = false
                    } finally { accountCloseInFlight = false }
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                if (presentedState.editOpen) {
                    if (!presentedState.saving) {
                        accountViewModel?.cancelEdit()
                        // A drag or system Back can hide the sheet before this callback.
                        // Restore the same host with Account content, never a second sheet.
                        sheetScope.launch {
                            accountSheetState.show()
                            withFrameNanos { }
                            accountSheetState.expand()
                        }
                    }
                } else dismissAccountSheet()
            },
            modifier = Modifier.testTag("account_sheet")
                .onSizeChanged { accountSheetHeightPx = it.height.toFloat() },
            sheetState = accountSheetState,
            containerColor = Color(0xFFFEFEFC),
            // Material fades its scrim at target Hidden, before a dragged sheet reaches the bottom.
            // The host draws the same color from the actual offset while Material retains gestures.
            scrimColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = null,
        ) {
            BackHandler(presentedState.editOpen) {
                // The IME consumes the first Back while visible; the next returns to Account.
                accountViewModel?.cancelEdit()
            }
            if (presentedState.editOpen) {
                TravellerEditIdentitySheet(
                    state = presentedState,
                    onNameChange = { accountViewModel?.updateFullName(it) },
                    onHandleChange = { accountViewModel?.updateHandle(it) },
                    onSave = { accountViewModel?.save() },
                    onClose = { accountViewModel?.cancelEdit() },
                )
            } else TravellerAccountSheet(
                state = presentedState,
                onClose = dismissAccountSheet,
                onEdit = { accountViewModel?.openEdit() },
                onRetry = { accountViewModel?.load() },
                onSignOutRequested = onSignOutRequested,
                onReauthenticate = onReauthenticateRequested,
            )
        }
        LaunchedEffect(presentedState.editOpen) {
            if (!presentedState.editOpen && accountSheetState.isVisible) {
                withFrameNanos { }
                accountSheetState.expand()
            }
        }
        if (presentedState.signOutStatus == AccountSignOutStatus.CONFIRMING ||
            presentedState.signOutStatus == AccountSignOutStatus.SIGNING_OUT) {
            TravellerNoActiveSignOutConfirmation(
                signingOut = presentedState.signOutStatus == AccountSignOutStatus.SIGNING_OUT,
                onConfirm = onConfirmSignOutRequested,
                onStaySignedIn = onCancelSignOutRequested,
            )
        }
        if (presentedState.signOutStatus == AccountSignOutStatus.CONFIRMING_ACTIVE ||
            presentedState.signOutStatus == AccountSignOutStatus.STOPPING_ACTIVE) {
            TravellerActiveSignOutConfirmation(
                stopping = presentedState.signOutStatus == AccountSignOutStatus.STOPPING_ACTIVE,
                onConfirm = onConfirmActiveSignOutRequested,
                onKeepMonitoring = onCancelActiveSignOutRequested,
            )
        }
    }
}

@Composable
private fun JourneySheetHandle() {
    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.padding(top = 10.dp).size(width = 38.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp)).background(Color(0xFFB5BABD)))
    }
}

@Composable
private fun PassiveSheetGrabber() {
    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.padding(top = 12.dp)) { TravellerSheetGrabberPill() }
    }
}

@Composable
internal fun JourneyHome(
    state: JourneyUiState,
    accountEmail: String?,
    resilienceSummary: String,
    onPrimary: () -> Unit,
    onContacts: () -> Unit,
    onResilience: () -> Unit,
    onAccount: () -> Unit,
    pendingAuthority: com.journeycontinuity.app.data.local.TravellerAuthorityRecord? = null,
    onPendingAuthority: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Alabarin", color = Brand, style = textStyle(18, 22, FontWeight.SemiBold,
                InstrumentSans), modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(46.dp))
            UtilityRow(R.drawable.alabarin_contacts, "Trusted Contacts",
                trustedContactsSummary(state), onContacts)
            Spacer(Modifier.height(4.dp))
            UtilityRow(R.drawable.alabarin_resilience, "Resilience",
                resilienceSummary, onResilience)
            Spacer(Modifier.height(10.dp))
            UtilityRow(R.drawable.alabarin_account, "Account",
                accountEmail ?: "Signed in to Alabarin", onAccount)
            if (pendingAuthority != null) {
                Spacer(Modifier.height(28.dp))
                Text("Monitoring paused", color = Heading, style = textStyle(18, 24, FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                Text("${pendingAuthority.journey.destination}: final outcome not confirmed",
                    color = Body, style = textStyle(15, 21))
                Spacer(Modifier.height(12.dp))
                AuthorityAction("View pending status", "home_pending_authority", onPendingAuthority, primary = false)
                Spacer(Modifier.height(24.dp))
            }
        }
        state.activeJourney?.let { journey ->
            Column(Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp)).background(Color(0xFFFAFAF9)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.alabarin_pin), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(journey.destination, color = Heading,
                            style = textStyle(18, 22, FontWeight.Bold), maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text("Expected ${formatTime(journey.expectedArrivalAt)}", color = Body,
                            style = textStyle(15, 21))
                    }
                }
                Spacer(Modifier.height(14.dp))
                PrimaryAction("Open Journey", onPrimary, modifier = Modifier.height(52.dp))
            }
        } ?: PrimaryAction("Start Journey", onPrimary)
    }
}

internal fun trustedContactsSummary(state: JourneyUiState): String {
    if (state.trustedContactsAvailability == TrustedContactsAvailability.LOADING) return "Checking contacts"
    if (state.trustedContactsAvailability == TrustedContactsAvailability.UNAVAILABLE) return "Currently unavailable"
    val activeContacts = activeTrustedContacts(state.trustedContacts)
    val accepted = activeContacts.filter { it.status == TrustedContactStatus.ACCEPTED }
    val pending = activeContacts.count { it.status == TrustedContactStatus.PENDING }
    if (accepted.isEmpty()) return if (pending == 0) "No trusted contacts"
        else "$pending invitation${if (pending == 1) "" else "s"} pending"
    val names = accepted.take(2).joinToString(", ") { it.displayName }
    val more = if (accepted.size > 2) " +${accepted.size - 2}" else ""
    val pendingText = if (pending > 0) " · $pending pending" else ""
    return "$names$more$pendingText"
}

@Composable
private fun UtilityRow(icon: Int, title: String, summary: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().height(70.dp)
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .then(if (focused && inputMode.inputMode == InputMode.Keyboard)
            Modifier.border(2.dp, Brand, shape) else Modifier)
        .clickable(interactionSource = null, indication = null,
            role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), null, Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Heading, style = textStyle(16, 22, FontWeight.Medium))
            Spacer(Modifier.height(2.dp))
            Text(summary, color = Body, style = textStyle(15, 21), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Body, style = textStyle(24, 24))
    }
}

@Composable
internal fun JourneyStartScreen(
    state: JourneyUiState,
    onDestinationChange: (String) -> Unit,
    onArrivalChange: (Long) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
    structuredDraft: DestinationDraft? = null,
    onChooseDestination: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val ready = if (onChooseDestination != null)
        canSubmitStructuredJourneyStart(structuredDraft, state.draftExpectedArrivalAt, now)
    else canSubmitJourneyStart(state.draftDestination, state.draftExpectedArrivalAt, now)
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            JourneyHeader("Start Journey", onBack)
            Spacer(Modifier.height(30.dp))
            Text("Where are you going?", color = Heading,
                style = textStyle(16, 22, FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            if (onChooseDestination == null) {
                JourneyInput(state.draftDestination, "e.g. Ilorin", onDestinationChange)
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 58.dp)
                    .border(1.dp, Color(0xBF676F74), RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !state.isActionInProgress, role = Role.Button, onClick = onChooseDestination)
                    .testTag("choose_structured_destination")
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(structuredDraft?.displayLabel ?: "Choose destination",
                        color = if (structuredDraft == null) Body else Heading,
                        style = textStyle(15, 21), modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("›", color = Body, style = textStyle(24, 24))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("When do you expect to arrive?", color = Heading,
                style = textStyle(16, 22, FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(58.dp)
                .border(1.dp, Color(0xBF676F74), RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = !state.isActionInProgress, role = Role.Button) {
                    val suggested = Instant.ofEpochMilli(state.draftExpectedArrivalAt ?: now + 3_600_000L)
                        .atZone(zone)
                    DatePickerDialog(context, { _, year, month, day ->
                        val selectedDate = LocalDate.of(year, month + 1, day)
                        TimePickerDialog(context, { _, hour, minute ->
                            val timestamp = selectedDate.atTime(LocalTime.of(hour, minute))
                                .atZone(zone).toInstant().toEpochMilli()
                            onArrivalChange(timestamp)
                            now = System.currentTimeMillis()
                        }, suggested.hour, suggested.minute, false).show()
                    }, suggested.year, suggested.monthValue - 1, suggested.dayOfMonth).show()
                }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.draftExpectedArrivalAt?.let { formatArrival(it) } ?: "Choose date and time",
                    color = if (state.draftExpectedArrivalAt == null) Body else Heading,
                    style = textStyle(15, 21), modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Image(painterResource(R.drawable.alabarin_calendar), null, Modifier.size(19.dp))
            }
            if (state.draftExpectedArrivalAt != null && state.draftExpectedArrivalAt <= now) {
                Text("Choose a future date and time.", color = Warning,
                    style = textStyle(13, 18), modifier = Modifier.padding(top = 8.dp))
            }
            if (state.draftExpectedArrivalAt != null &&
                com.journeycontinuity.app.domain.JourneyEtaPolicy.validate(now, state.draftExpectedArrivalAt) ==
                com.journeycontinuity.app.domain.JourneyEtaPolicy.Validation.OUT_OF_RANGE) {
                Text("Choose an arrival within 72 hours of starting this Journey.", color = Warning,
                    style = textStyle(13, 18), modifier = Modifier.padding(top = 8.dp))
            }
        }
        PrimaryAction("Start Journey", onStart, enabled = ready && !state.isActionInProgress,
            loading = state.isActionInProgress)
    }
}

@Composable
private fun JourneyInput(value: String, hint: String, onValueChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(58.dp).testTag("journey_destination"),
        placeholder = { Text(hint, color = Body, style = textStyle(15, 21)) },
        singleLine = true,
        textStyle = textStyle(15, 21).copy(color = Heading),
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Brand,
            unfocusedBorderColor = Color(0xBF676F74),
            cursorColor = Brand,
        ),
    )
}

@Composable
internal fun JourneyCheckpoint(
    acceptedContact: Boolean,
    onStartAnyway: () -> Unit,
    onSetup: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(382.dp)
        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp)) {
        Text("Resilience setup needed", color = Heading,
            style = textStyle(21, 26, FontWeight.Bold), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        Text("You can still start this Journey, but fresh outward information may be harder to preserve if internet becomes limited.",
            color = Body, style = textStyle(15, 21))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (acceptedContact) "SMS fallback" else "Trusted contact", color = Heading,
                    style = textStyle(16, 22, FontWeight.Medium))
                Text(if (acceptedContact) "Unavailable" else "None added", color = Body,
                    style = textStyle(15, 21))
            }
            Text("Setup needed", color = Warning, style = textStyle(14, 20, FontWeight.Medium))
        }
        Spacer(Modifier.weight(1f))
        PrimaryAction("Start anyway", onStartAnyway)
        Box(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onSetup),
            contentAlignment = Alignment.Center) {
            Text(if (acceptedContact) "Set up resilience" else "Set up trusted contact",
                color = Brand, style = textStyle(16, 22, FontWeight.Medium))
        }
    }
}

@Composable
internal fun JourneyActiveScreen(
    journey: Journey,
    state: JourneyUiState,
    locationUiState: LocationUiState,
    smsFallbackStatus: SmsFallbackStatus,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEndCompleted: () -> Unit,
    probableEnd: ProbableEndPresentationEntity? = null,
    onProbableEndRendered: (String, String, View) -> Unit = { _, _, _ -> },
    onProbableEndResponse: (ProbableEndResponseRequest, ProbableEndTravellerResponse) -> Unit = { _, _ -> },
    probableEndResponseInFlight: Boolean = false,
    probableEndResponseError: String? = null,
    modifier: Modifier = Modifier,
) {
    val sheetView = LocalView.current
    val scrollState = rememberScrollState()
    var promptLaidOut by remember(probableEnd?.id) { mutableStateOf(false) }
    DisposableEffect(probableEnd?.id, promptLaidOut, sheetView) {
        val episode = probableEnd
        val observer = sheetView.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && sheetView.isShown && episode != null && promptLaidOut)
                onProbableEndRendered(episode.journeyId, episode.id, sheetView)
        }
        if (episode != null && promptLaidOut) observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (episode != null && promptLaidOut && observer.isAlive)
                observer.removeOnWindowFocusChangeListener(listener)
        }
    }
    LaunchedEffect(probableEnd?.id, scrollState.maxValue) {
        // Preserve the Figma Journey context when it fits. Extra monitoring rows may
        // make the sheet scroll; bring the whole question and both actions into view.
        if (probableEnd != null && scrollState.maxValue in 1 until Int.MAX_VALUE)
            scrollState.scrollTo(scrollState.maxValue)
    }
    LaunchedEffect(probableEnd?.id, promptLaidOut) {
        val episode = probableEnd ?: return@LaunchedEffect
        if (promptLaidOut) {
            withFrameNanos { }
            if (sheetView.isShown && sheetView.hasWindowFocus())
                onProbableEndRendered(episode.journeyId, episode.id, sheetView)
        }
    }
    val monitoringReady = backgroundMonitoringReady(journey, state.monitoringJourneyId) &&
        locationUiState.access != ForegroundLocationAccess.NONE &&
        locationUiState.locationServicesEnabled
    val accepted = state.trustedContacts.any { it.status == TrustedContactStatus.ACCEPTED }
    val connectivity = travellerConnectivityPresentation(
        journey.id, state.degradation, state.currentConnectivity, smsFallbackStatus, accepted,
    )
    Column(modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)) {
            BackIcon(onBack)
            Spacer(Modifier.height(14.dp))
            Text(journey.destination, color = Heading, style = textStyle(40, 46, FontWeight.Bold),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            JourneyTimeRow("Expected arrival", formatTime(journey.expectedArrivalAt))
            Spacer(Modifier.height(12.dp))
            JourneyTimeRow("Started", formatTime(journey.startedAt))
            Spacer(Modifier.height(50.dp))
            Row {
                Image(painterResource(R.drawable.alabarin_monitoring), null,
                    Modifier.size(14.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (monitoringReady) "Monitoring in background" else "Monitoring needs attention",
                        color = Heading, style = textStyle(15, 21, FontWeight.Medium))
                    Spacer(Modifier.height(8.dp))
                    Text(if (monitoringReady)
                        "You can leave the app. Alabarin keeps monitoring this Journey."
                        else "Background monitoring is not running. Check Location Services and retry.",
                        color = Body, style = textStyle(14, 20))
                    if (!monitoringReady) TextButton(onClick = onRetry) {
                        Text("Retry monitoring", color = Brand)
                    }
                }
            }
            if (connectivity != JourneyConnectivityPresentation.NORMAL) {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.testTag("journey_connectivity_status")) {
                    Image(painterResource(when (connectivity) {
                        JourneyConnectivityPresentation.CHECKING_CONNECTION,
                        JourneyConnectivityPresentation.RECOVERING -> R.drawable.alabarin_recovering
                        JourneyConnectivityPresentation.LIMITED_FALLBACK_AVAILABLE ->
                            R.drawable.alabarin_limited
                        JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE ->
                            R.drawable.alabarin_unavailable
                        JourneyConnectivityPresentation.NORMAL -> error("Normal has no context row")
                    }), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(requireNotNull(connectivity.title),
                            color = if (connectivity == JourneyConnectivityPresentation.LIMITED_FALLBACK_UNAVAILABLE)
                                Warning else ContextBlue,
                            style = textStyle(15, 21, FontWeight.Medium))
                        Spacer(Modifier.height(6.dp))
                        Text(requireNotNull(connectivity.detail), color = Body, style = textStyle(14, 20))
                    }
                }
            }
            if (probableEnd != null) {
                Spacer(Modifier.height(38.dp))
                Column(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                    val rect = coordinates.boundsInRoot()
                    val rootHeight = coordinates.findRootCoordinates().size.height
                    promptLaidOut = rootHeight > 0 && rect.width > 0f && rect.height > 0f &&
                        rect.top >= 0f && rect.bottom <= rootHeight.toFloat()
                }.testTag("probable_end_prompt")) {
                    Text("Has this Journey ended?", color = Heading,
                        style = textStyle(24, 30, FontWeight.Bold),
                        modifier = Modifier.semantics { heading() }.testTag("probable_end_title"))
                    Spacer(Modifier.height(30.dp))
                    Text("Alabarin detected signs that this Journey may have ended. If you don’t respond, monitoring may stop automatically after 30 minutes.",
                        color = Body, style = textStyle(15, 21), modifier = Modifier.testTag("probable_end_body"))
                    Spacer(Modifier.height(57.dp))
                    val actionsEnabled = probableEnd.status == ProbableEndPresentationStatus.PRESENTED.name &&
                        !probableEndResponseInFlight
                    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
                        // Keep the layer present when enabled changes; alpha(1f) removes it.
                        // Paint inside it so fill, label and border invalidate together.
                        .graphicsLayer { alpha = if (actionsEnabled) 1f else 0.55f }
                        .background(Brand)
                        .clickable(enabled = actionsEnabled, role = Role.Button) {
                            onProbableEndResponse(probableEnd.responseRequest(),
                                ProbableEndTravellerResponse.JOURNEY_COMPLETED)
                        }.testTag("probable_end_completed_action"), contentAlignment = Alignment.Center) {
                        Text("Journey completed", color = Color.White, style = textStyle(16, 22, FontWeight.Bold))
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                        .graphicsLayer { alpha = if (actionsEnabled) 1f else 0.55f }
                        .border(1.dp, Border, RoundedCornerShape(16.dp))
                        .clickable(enabled = actionsEnabled, role = Role.Button) {
                            onProbableEndResponse(probableEnd.responseRequest(),
                                ProbableEndTravellerResponse.KEEP_MONITORING)
                        }.testTag("probable_end_keep_monitoring_action"), contentAlignment = Alignment.Center) {
                        Text("Keep monitoring", color = Heading, style = textStyle(16, 22, FontWeight.Medium))
                    }
                    if (probableEndResponseError != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(probableEndResponseError, color = Warning, style = textStyle(14, 20),
                            modifier = Modifier.testTag("probable_end_response_error"))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (probableEnd == null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { onEndCompleted() },
                contentAlignment = Alignment.Center) {
                Text("End Journey", color = Heading, style = textStyle(16, 22, FontWeight.Medium))
            }
        }
    }
}

internal fun sheetVisibleFraction(viewportHeightPx: Float, sheetHeightPx: Float, offsetPx: Float): Float {
    if (viewportHeightPx <= 0f || sheetHeightPx <= 0f || !offsetPx.isFinite()) return 0f
    return ((viewportHeightPx - offsetPx) / sheetHeightPx).coerceIn(0f, 1f)
}

@Composable
internal fun OrdinaryEndChoiceScreen(state: JourneyUiState, onBack: () -> Unit,
    onComplete: () -> Unit, onStop: () -> Unit) {
    var confirmation by rememberSaveable(state.endFlowJourneyId) { mutableStateOf<String?>(null) }
    val busy = state.isActionInProgress || state.endStopPending
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 28.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.size(48.dp).clip(CircleShape)
                .clickable(interactionSource = null,
                    indication = ripple(bounded = false, radius = TravellerNavigationRippleRadius,
                        color = TravellerNavigationRippleColor),
                    enabled = !busy, role = Role.Button, onClick = onBack)
                .testTag("ordinary_end_back")
                .semantics { contentDescription = "Back to Journey" },
                contentAlignment = Alignment.Center) {
                TravellerBackVisual()
            }
            Spacer(Modifier.height(40.dp))
            Text("End Journey", color = Heading, style = textStyle(30, 36, FontWeight.Bold),
                modifier = Modifier.semantics { heading() }.testTag("ordinary_end_title"))
            Spacer(Modifier.height(12.dp))
            Text("Choose what happened with this Journey.", color = Body, style = textStyle(15, 21))
            Spacer(Modifier.height(34.dp))
            OrdinaryEndChoiceRow("Journey completed",
                "You completed this Journey and want Alabarin to stop monitoring it.",
                !busy, "ordinary_end_complete_choice") { confirmation = "COMPLETE" }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            OrdinaryEndChoiceRow("Stop monitoring",
                "Stop monitoring without marking the Journey completed.",
                !busy, "ordinary_end_stop_choice") { confirmation = "STOP" }
            state.endError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = Warning, style = textStyle(14, 20),
                    modifier = Modifier.testTag("ordinary_end_error"))
            }
        }
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !busy, role = Role.Button, onClick = onBack)
            .testTag("ordinary_end_keep_running"), contentAlignment = Alignment.Center) {
            Text("Keep Journey running", color = Brand,
                style = textStyle(16, 22, FontWeight.Medium))
        }
    }
    if (confirmation != null) {
        val complete = confirmation == "COMPLETE"
        Dialog(onDismissRequest = { if (!busy) confirmation = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setDimAmount(0.22f) }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(Color(0xFFFEFEFC)).verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp)
                .testTag("ordinary_end_confirmation")) {
                Text(if (complete) "Complete Journey?" else "Stop monitoring?",
                    color = Heading, style = textStyle(22, 27, FontWeight.Bold),
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text(if (complete)
                    "Alabarin will stop monitoring this Journey and record it as completed. This does not independently confirm safe arrival."
                    else "Alabarin will stop monitoring this Journey without marking it completed.",
                    color = Body, style = textStyle(15, 21),
                    modifier = Modifier.heightIn(min = 76.dp))
                state.endError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Warning, style = textStyle(14, 20),
                        modifier = Modifier.testTag("ordinary_end_confirm_error"))
                }
                Spacer(Modifier.height(31.dp))
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (busy) Color(0xFFD5D7D7) else Brand)
                    .clickable(enabled = !busy, role = Role.Button,
                        onClick = if (complete) onComplete else onStop)
                    .testTag("ordinary_end_confirm_action"), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White,
                        strokeWidth = 2.dp)
                    else Text(if (complete) "Complete Journey" else "Stop monitoring",
                        color = Color.White, style = textStyle(16, 22, FontWeight.Bold))
                }
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy, role = Role.Button) { confirmation = null }
                    .testTag("ordinary_end_confirm_cancel"), contentAlignment = Alignment.Center) {
                    Text("Keep monitoring", color = Brand,
                        style = textStyle(15, 21, FontWeight.Medium))
                }
            }
            }
        }
    }
}

@Composable
private fun OrdinaryEndChoiceRow(title: String, description: String, enabled: Boolean,
    tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 92.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Heading, style = textStyle(16, 21, FontWeight.Medium))
            Spacer(Modifier.height(4.dp))
            Text(description, color = Body, style = textStyle(14, 20))
        }
        Spacer(Modifier.width(12.dp))
        Text("›", color = Body, style = textStyle(24, 24))
    }
}

@Composable
internal fun OrdinaryEndResultScreen(journey: Journey, onDone: () -> Unit) {
    val completed = journey.status == JourneyStatus.COMPLETED
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 34.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(76.dp))
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(if (completed) R.drawable.probable_end_completed_icon
                    else R.drawable.ordinary_end_stopped_icon), null, Modifier.size(28.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(if (completed) "Journey completed" else "Monitoring stopped",
                color = Heading, style = textStyle(30, 36, FontWeight.Bold),
                modifier = Modifier.semantics { heading() }.testTag("ordinary_end_result_title"))
            Spacer(Modifier.height(10.dp))
            Text(if (completed) "Alabarin has stopped monitoring this Journey."
                else "You stopped monitoring for this Journey.", color = Body,
                style = textStyle(15, 21))
            Spacer(Modifier.height(34.dp))
            OrdinaryEndSummaryRow("Destination", journey.destination)
            Spacer(Modifier.height(12.dp))
            OrdinaryEndSummaryRow("Expected arrival", formatTime(journey.expectedArrivalAt))
        }
        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(Brand).clickable(role = Role.Button, onClick = onDone)
            .testTag("ordinary_end_done"), contentAlignment = Alignment.Center) {
            Text("Done", color = Color.White, style = textStyle(16, 20, FontWeight.Bold))
        }
    }
}

@Composable
private fun OrdinaryEndSummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Body, style = textStyle(15, 21))
        Spacer(Modifier.width(16.dp).weight(1f))
        Text(value, color = Heading, style = textStyle(15, 21, FontWeight.Medium),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun JourneyTimeRow(label: String, value: String) {
    Row {
        Text(label, color = Body, style = textStyle(15, 21))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Heading, style = textStyle(15, 21, FontWeight.Medium))
    }
}

@Composable
private fun JourneyUtilityScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp)) {
        JourneyHeader(title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun JourneyHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackIcon(onBack)
        Spacer(Modifier.width(8.dp))
        Text(title, color = Heading, style = textStyle(22, 27, FontWeight.Bold),
            modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun BackIcon(onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape)
        .clickable(interactionSource = null,
            indication = ripple(bounded = false, radius = TravellerNavigationRippleRadius,
                color = TravellerNavigationRippleColor),
            role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center) {
        TravellerBackVisual()
    }
}

@Composable
private fun PrimaryAction(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, loading: Boolean = false,
) {
    Box(modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp))
        .background(if (enabled) Brand else Color(0xFFD5D7D7))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag("journey_primary_action"), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White,
            strokeWidth = 2.dp)
        else Text(title, color = if (enabled) Color.White else Color(0xFF808587),
            style = textStyle(16, 20, FontWeight.Bold))
    }
}

private fun textStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif) = TextStyle(
    fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    platformStyle = NoFontPadding,
)

private fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(TimeFormat)

private fun formatArrival(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(ArrivalFormat)
