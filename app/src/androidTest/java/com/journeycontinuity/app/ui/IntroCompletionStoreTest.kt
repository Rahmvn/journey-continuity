package com.journeycontinuity.app.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntroCompletionStoreTest {
    @SuppressLint("UseKtx") // Restore the exact test-app preference value before returning.
    @Test fun introCompletionWritesOnlyItsDedicatedPreferenceFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intro = context.getSharedPreferences("alabarin-introduction-only", Context.MODE_PRIVATE)
        val filesReadByOwnerSnapshot = listOf(
            "journey-continuity-identity", "journey-continuity-installation",
            "journey-continuity-sms-fallback", "${context.packageName}_preferences",
        )
        val before = filesReadByOwnerSnapshot.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        val hadFlag = intro.contains("introductionCompleted")
        val oldFlag = intro.getBoolean("introductionCompleted", false)
        try {
            assertTrue(IntroCompletionStore(context).complete())
            assertTrue(IntroCompletionStore(context).isCompleted())
            val after = filesReadByOwnerSnapshot.associateWith { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
            }
            assertEquals(before, after)
        } finally {
            val editor = intro.edit()
            if (hadFlag) editor.putBoolean("introductionCompleted", oldFlag)
            else editor.remove("introductionCompleted")
            check(editor.commit())
        }
    }
}
