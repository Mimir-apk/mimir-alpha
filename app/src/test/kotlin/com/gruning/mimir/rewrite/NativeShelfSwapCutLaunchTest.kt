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

/**
 * the two-app exchange starts the former Bottom app on Main through the native start
 * seam and asks it for no window animation. Every other caller of the seam passes nothing and keeps the animation.
 * The flag values are the ones the Thor's `START … flg=` line prints for this start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class NativeShelfSwapCutLaunchTest {

    @Test
    fun `a start asked for no animation carries NO_ANIMATION and NO_USER_ACTION beside the shipping task flags on Main`() {
        val context = CapturingContext(ApplicationProvider.getApplicationContext())

        assertTrue(
            startNativeShelfLaunchIntent(
                context = context,
                packageName = "com.example.video",
                target = launcherTarget(),
                launchDisplayId = Display.DEFAULT_DISPLAY,
                noAnimation = true,
            ),
        )

        assertNull("the targeted path must not use the unqualified startActivity overload", context.oneArg)
        assertEquals(0x30270000, requireNotNull(context.twoArg).flags)
        assertEquals(
            Display.DEFAULT_DISPLAY,
            requireNotNull(context.options).getInt(LaunchDisplayIdBundleKey, Display.INVALID_DISPLAY),
        )
    }

    @Test
    fun `a start that does not ask keeps the animation`() {
        val context = CapturingContext(ApplicationProvider.getApplicationContext())

        assertTrue(
            startNativeShelfLaunchIntent(
                context = context,
                packageName = "com.example.video",
                target = launcherTarget(),
                launchDisplayId = Display.DEFAULT_DISPLAY,
            ),
        )

        val started = requireNotNull(context.twoArg)
        assertEquals(0x30220000, started.flags)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    @Test
    fun `the exchange's flag set is the shipping set plus NO_ANIMATION and NO_USER_ACTION`() {
        assertEquals(0x30220000, nativeShelfLaunchFlags())
        assertEquals(0x30270000, nativeShelfLaunchFlags(noAnimation = true))
    }

    private fun launcherTarget(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName("com.example.video", "com.example.video.HomeActivity")

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
        // Android framework ActivityOptions.KEY_LAUNCH_DISPLAY_ID, as NativeShelfPrimaryDisplayLaunchTest reads it.
        const val LaunchDisplayIdBundleKey = "android.activity.launchDisplayId"
    }
}
