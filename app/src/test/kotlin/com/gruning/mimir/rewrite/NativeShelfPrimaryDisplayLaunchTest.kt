package com.gruning.mimir.rewrite

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression for resident native tasks being reused on the last active (secondary) display. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class NativeShelfPrimaryDisplayLaunchTest {

    @Test
    fun explicitPrimaryDisplayCarriesOptionsAndShippingTaskFlags() {
        val context = CapturingContext(ApplicationProvider.getApplicationContext())
        val target = Intent().setClassName("com.example.calendar", "com.example.calendar.MainActivity")

        assertTrue(
            startNativeShelfLaunchIntent(
                context = context,
                packageName = "com.example.calendar",
                target = target,
                launchDisplayId = Display.DEFAULT_DISPLAY,
            ),
        )

        assertNull("the targeted path must not use the unqualified startActivity overload", context.oneArg)
        val started = requireNotNull(context.twoArg)
        assertEquals(
            Display.DEFAULT_DISPLAY,
            requireNotNull(context.options).getInt(LaunchDisplayIdBundleKey, Display.INVALID_DISPLAY),
        )
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    private class CapturingContext(base: Context) : ContextWrapper(base) {
        var oneArg: Intent? = null
        var twoArg: Intent? = null
        var options: Bundle? = null

        override fun startActivity(intent: Intent) {
            oneArg = intent
        }

        override fun startActivity(intent: Intent, options: Bundle?) {
            twoArg = intent
            this.options = options
        }
    }

    private companion object {
        // Android framework ActivityOptions.KEY_LAUNCH_DISPLAY_ID, verified in AOSP
        // frameworks/base/core/java/android/app/ActivityOptions.java.
        const val LaunchDisplayIdBundleKey = "android.activity.launchDisplayId"
    }
}
