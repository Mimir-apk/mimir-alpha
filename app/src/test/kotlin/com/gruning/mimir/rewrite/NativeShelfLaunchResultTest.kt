package com.gruning.mimir.rewrite

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast

/**
 * N5 site #4 (`app/NativeShelfPackages.kt:181`):
 * `launchNativeShelfPackage`'s `resolveNativeShelfLaunchIntent(context, clean) ?: return false` had no
 * reporting of its own — its sole caller, `PrimaryMButtonPanelHost.launchPackage`, does toast, but with
 * one undifferentiated message across two distinct failure reasons (no launchable intent vs. an activity
 * that failed to start), and the no-intent reason had zero diagnostic trail at all (the activity-start
 * failure already logs via `startNativeShelfLaunchIntent`). The fix is differentiation, not a new
 * player-facing message: [launchNativeShelfPackageResult] now returns a typed
 * [NativeShelfLaunchResult], reports the no-intent reason from its own call site (log + toast, same
 * verbatim sentence the caller already used), and the caller no longer double-toasts for that arm.
 *
 * A genuinely nonexistent package resolves to `null` under Robolectric with no fakes needed (`manifest =
 * Config.NONE` means nothing is a launchable package here), which is exactly the shape of this site's
 * discard — this is a real behavioral test, not a source characterization. The `LAUNCHED`/
 * `ACTIVITY_NOT_STARTED` arms are unchanged call-for-call from the pre-fix code (only the return type
 * wrapping changed); standing up a fake resolvable launcher activity in this Robolectric configuration
 * is out of proportion to this site and is reasoned-equivalent, not separately behaviorally re-pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class NativeShelfLaunchResultTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @Test
    fun noLaunchableIntentIsLoggedAndToastedAndReturnsTheTypedReason() {
        val result = launchNativeShelfPackageResult(context, "com.example.does.not.exist")

        assertEquals(NativeShelfLaunchResult.NO_LAUNCHABLE_INTENT, result)
        assertTrue(
            "expected a warning naming the package with no launchable intent; logs were: " +
                ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" },
            ShadowLog.getLogs().any {
                it.tag == "MimirNativeShelf" &&
                    it.msg == "No launchable intent for native package com.example.does.not.exist"
            },
        )
        assertEquals(
            "Mimir couldn’t open that app.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun aBlankPackageNameIsAlsoNoLaunchableIntentLoggedAndToasted() {
        val result = launchNativeShelfPackageResult(context, "   ")

        assertEquals(NativeShelfLaunchResult.NO_LAUNCHABLE_INTENT, result)
        assertTrue(ShadowLog.getLogs().any { it.tag == "MimirNativeShelf" })
        assertEquals(
            "Mimir couldn’t open that app.",
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
