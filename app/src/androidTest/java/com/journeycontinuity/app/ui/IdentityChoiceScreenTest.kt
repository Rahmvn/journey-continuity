package com.journeycontinuity.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IdentityChoiceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun createAccountInvokesOnlyItsCallbackAndRenderingHasNoEffect() {
        var createCount = 0
        var loginCount = 0
        compose.setContent {
            IdentityChoiceScreen(onCreateAccount = { createCount++ }, onLogIn = { loginCount++ })
        }

        compose.onNodeWithText("Your Journey, your account")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithText("Create account").assertIsDisplayed()
        compose.onNodeWithText("Log in").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, createCount)
            assertEquals(0, loginCount)
        }

        compose.onNodeWithText("Create account").performClick()
        compose.runOnIdle {
            assertEquals(1, createCount)
            assertEquals(0, loginCount)
        }
    }

    @Test
    fun loginInvokesOnlyItsCallbackAtNarrowPhoneWidth() {
        var createCount = 0
        var loginCount = 0
        compose.setContent {
            Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                IdentityChoiceScreen(onCreateAccount = { createCount++ }, onLogIn = { loginCount++ })
            }
        }

        compose.onNodeWithText("Create account").assertIsDisplayed()
        compose.onNodeWithText("Log in").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, createCount)
            assertEquals(1, loginCount)
        }
    }

    @Test
    fun captureReferenceFrameForVisualReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "identity-choice")
        check(output.mkdirs() || output.isDirectory)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 390.dp, height = 844.dp).testTag("identityChoiceFrame")) {
                    IdentityChoiceScreen(onCreateAccount = {}, onLogIn = {})
                }
            }
        }

        val bitmap = compose.onNodeWithTag("identityChoiceFrame").captureToImage().asAndroidBitmap()
        File(output, "identity-choice.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
