package com.journeycontinuity.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TravellerRootHost(
    controller: TravellerRootViewModel,
    admittedContent: @Composable () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val authSurface = state.route != TravellerRootRoute.ADMITTED_EXISTING &&
        state.route != TravellerRootRoute.ADMITTED_NEW

    BackHandler(enabled = state.route in setOf(
        TravellerRootRoute.CREATE_DETAILS, TravellerRootRoute.CREATE_OTP,
        TravellerRootRoute.LOGIN_EMAIL, TravellerRootRoute.LOGIN_OTP,
    )) { controller.back() }

    if (!authSurface) {
        // JourneyScreen owns its Scaffold and its own insets.
        admittedContent()
        return
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().background(Color(0xFFFBFBF8))) {
        when (state.route) {
            TravellerRootRoute.CHECKING -> SupportContent("Checking this Traveller", "Please wait.", null, null)
            TravellerRootRoute.INTRO -> FirstUseIntroFlow(onCompleted = {
                if (it == FirstUseCompletion.INTRODUCTION_FINISHED) controller.completeIntro()
            })
            TravellerRootRoute.IDENTITY_CHOICE -> IdentityChoiceScreen(
                onCreateAccount = controller::chooseCreateAccount,
                onLogIn = controller::chooseLogIn,
            )
            TravellerRootRoute.CREATE_DETAILS -> CreateAccountDetailsScreen(
                fullName = state.fullName, handle = state.handle, email = state.createEmail,
                handleFeedback = state.handleFeedback,
                submissionEnabled = !state.busy && state.fullName.isNotBlank() &&
                    state.handle.isNotBlank() && state.createEmail.isNotBlank(),
                allowUncheckedHandle = true,
                onFullNameChange = controller::onFullNameChange,
                onHandleChange = controller::onHandleChange,
                onEmailChange = controller::onEmailChange,
                onContinue = controller::continueCreateAccount,
                onBack = controller::back, onLogIn = controller::chooseLogIn,
                serviceNotice = state.notice,
                requestInProgress = state.busy,
            )
            TravellerRootRoute.CREATE_OTP -> CreateAccountCheckEmailScreen(
                code = state.code, submissionEnabled = !state.busy && state.code.length == 6,
                invalidOrExpired = state.createCodeInvalid,
                onCodeChange = controller::onCodeChange, onVerify = controller::verifyCreateCode,
                onSendNewCode = controller::resendCreateCode,
                onUseDifferentEmail = controller::differentCreateEmail,
                onLogIn = controller::chooseLogIn, onBack = controller::back,
                serviceNotice = state.notice,
                requestInProgress = state.busy,
            )
            TravellerRootRoute.LOGIN_EMAIL -> ReturningLoginEmailScreen(
                email = state.loginEmail, submissionEnabled = !state.busy && state.loginEmail.isNotBlank(),
                onEmailChange = controller::onLoginEmailChange, onContinue = controller::requestLoginCode,
                onCreateAccount = controller::chooseCreateAccount, onBack = controller::back,
                serviceNotice = state.notice,
                requestInProgress = state.busy,
            )
            TravellerRootRoute.LOGIN_OTP -> ReturningLoginCheckEmailScreen(
                code = state.code,
                submissionEnabled = !state.busy && state.loginAttemptActive && state.code.length == 6,
                feedback = state.loginFeedback, onCodeChange = controller::onCodeChange,
                onVerify = controller::verifyLoginCode, onSendNewCode = controller::resendLoginCode,
                onUseDifferentEmail = controller::differentLoginEmail,
                onCreateAccount = controller::chooseCreateAccount, onBack = controller::back,
                serviceNotice = state.notice,
                requestInProgress = state.busy,
            )
            TravellerRootRoute.RECOVERY -> SupportContent("Account recovery needed",
                state.notice ?: "This Traveller cannot be safely opened right now.",
                "Try again", controller::assessStartup,
                if (state.recoveryLoginAllowed) "Recover with email" else null,
                if (state.recoveryLoginAllowed) controller::recoverSameOwnerWithEmail else null)
            TravellerRootRoute.ADMITTED_EXISTING, TravellerRootRoute.ADMITTED_NEW -> Unit
        }
    }
}

@Composable
internal fun AuthInlineNotice(message: String) {
    Text(message, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        color = Color(0xFF992E2E))
}

@Composable
private fun SupportContent(title: String, body: String, action: String?, onAction: (() -> Unit)?,
    secondaryAction: String? = null, onSecondaryAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, modifier = Modifier.semantics { heading() }, color = Color(0xFF161B20))
        Text(body, modifier = Modifier.padding(top = 12.dp), color = Color(0xFF676F74))
        if (action != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) { Text(action) }
        }
        if (secondaryAction != null && onSecondaryAction != null) {
            Button(onClick = onSecondaryAction, modifier = Modifier.padding(top = 12.dp)) {
                Text(secondaryAction)
            }
        }
    }
}
