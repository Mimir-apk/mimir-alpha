package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gruning.mimir.rewrite.core.tweaker.LaunchStatusPresentation
import com.gruning.mimir.rewrite.core.tweaker.TweakerLaunchOverlayMessage
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-38 (audit 2026-10-14, U-05 / X-05 / X-57): the cross-app launch-status card cut in when its window mounted and cut out when
 * the dismiss timer cleared it and detached the window on the same frame. The card is a fade on the house clock both ways now, and
 * the window is told to go only when the leave has settled. Frames over a black window: the card brightens across its arrival, dims
 * across its leave, is still drawn mid-leave, and the end of the leave is reported once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrossAppLaunchStatusCardFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var presentation by mutableStateOf<LaunchStatusPresentation?>(null)
    private var left = 0

    private val card = LaunchStatusPresentation(
        eventId = 1L,
        message = TweakerLaunchOverlayMessage.Confirmation(headline = "TWEAKER ACTIVE", supportingText = "READY FOR THE GAME"),
        expiresAtMs = 2_200L,
    )

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** The card's total light on a black window: every sampled pixel's channels summed. */
    private fun brightness(): Long {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        var sum = 0L
        for (x in 0 until bitmap.width step 3) for (y in 0 until bitmap.height step 3) {
            val p = bitmap.getPixel(x, y)
            sum += android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)
        }
        return sum
    }

    @Test
    fun `the card fades in, is still drawn mid-leave, fades out and reports the end of the leave once`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomEnd) {
                CrossAppLaunchStatusCardHost(presentation = presentation, onLeft = { left++ })
            }
        }
        step(0L)
        assertEquals("nothing is drawn before an event", 0L, brightness())

        Snapshot.withMutableSnapshot { presentation = card }
        step(32L)
        val arriving = brightness()
        step(90L)
        val arrived = brightness()
        step(MimirMotion.OverlayMs.toLong() + 32L)
        val settled = brightness()
        assertTrue("the card is drawn", compose.shows("TWEAKER ACTIVE", exact = false))
        assertTrue("the card is brightening across its arrival ($arriving, $arrived, $settled)", arriving < arrived && arrived < settled)
        assertTrue("it did not cut in at full strength on its first frame", arriving < settled)

        Snapshot.withMutableSnapshot { presentation = null }
        step(16L)
        assertTrue("the card is drawn", compose.shows("TWEAKER ACTIVE", exact = false))
        step(32L)
        val leaving = brightness()
        step(42L)
        val half = brightness()
        assertTrue("it dims across its leave ($settled, $leaving, $half)", settled > leaving && leaving > half && half > 0L)
        assertEquals("the window is not told to go while the card is still fading", 0, left)

        step(LeaveSettleMs)
        assertEquals("the end of the leave is reported once", 1, left)
        assertEquals("and nothing is left on the black window", 0L, brightness())
    }

    @Test
    fun `the presenter cuts nothing, it mounts the card host and detaches only from the host report`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/CrossAppLaunchStatusPresenter.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/CrossAppLaunchStatusPresenter.kt"),
        ).first(File::isFile).readText()
        val code = source.lineSequence().joinToString("\n") { it.substringBefore("//") }
        val dismiss = code.substringAfter("private fun scheduleDismiss(").substringBefore("private fun detachIfIdle(")
        assertTrue("the dismiss clears the presentation", dismiss.contains("activePresentation = null"))
        assertFalse("and no longer detaches the window on that same frame", Regex("""activePresentation = null\s*detachIfIdle\(\)""").containsMatchIn(dismiss))
        assertTrue("the window is told to go from the host's report", code.contains("onLeft = { mainHandler.post(::detachIfIdle) }"))
        assertTrue("with a guard for a display that draws no frame", dismiss.contains("NativePanelExitBackstopMs"))
    }

    private companion object {
        /** Past the 180 ms leave and the frame the report needs. */
        const val LeaveSettleMs = 160L
    }
}
