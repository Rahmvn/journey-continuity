package com.journeycontinuity.app.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
internal fun TravellerNoActiveSignOutConfirmation(
    signingOut: Boolean,
    onConfirm: () -> Unit,
    onStaySignedIn: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!signingOut) onStaySignedIn() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0.20f)
        }
        Column(Modifier.width(320.dp).height(250.dp)
            .clip(RoundedCornerShape(26.dp)).background(Color(0xFFFEFEFC))
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp)
            .testTag("account_sign_out_confirmation")) {
            Text("Sign out?", color = Color(0xFF161B20),
                style = signOutStyle(22, 27, FontWeight.Bold))
            Spacer(Modifier.height(12.dp))
            Text("You’ll need a new one-time code the next time you log in on this device.",
                Modifier.fillMaxWidth().height(58.dp), color = Color(0xFF676F74),
                style = signOutStyle(15, 21))
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF992E2E))
                .clickable(enabled = !signingOut, role = Role.Button, onClick = onConfirm)
                .testTag("account_confirm_sign_out"), contentAlignment = Alignment.Center) {
                Text(if (signingOut) "Signing out…" else "Sign out", color = Color.White,
                    style = signOutStyle(16, 22, FontWeight.Bold))
            }
            Box(Modifier.fillMaxWidth().height(46.dp)
                .clickable(enabled = !signingOut, role = Role.Button, onClick = onStaySignedIn)
                .testTag("account_stay_signed_in"), contentAlignment = Alignment.Center) {
                Text("Stay signed in", color = Color(0xFF1B3250),
                    style = signOutStyle(15, 21, FontWeight.Medium))
            }
        }
    }
}

private fun signOutStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
