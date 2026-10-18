package com.gruning.mimir.rewrite

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.gruning.mimir.rewrite.feature.library.WarmupLedger
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The welcome hold's input-gate backstop against the splash it backs up .
 *
 * MainActivity posts its backstop on the decor view before the view is attached. `View.postDelayed` replays it at attach
 * with the full delay (android13 View.java `postDelayed`, HandlerActionQueue.executeActions), so its clock starts at
 * attach. The hold's own clock starts in the splash layer's LaunchedEffect, after the first frame. On the Thor that frame
 * took about 1 s, so a hold that ran to its cap could still be on screen when the backstop fired: 11,880 ms after
 * attach, against a splash that can stay until 1,000 + 11,380 = 12,380 ms.
 *
 * Here the backstop is posted on the main looper at "attach" and the looper clock is driven by hand; the splash is the
 * production [MimirColdStartWelcome]. The rule: the backstop never opens the gate while a splash surface is composed, the
 * splash's own layer opens it when the splash goes, and with no splash at all the backstop still opens it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WelcomeHoldBackstopTest {

    @get:Rule
    val compose = createComposeRule()

    private var holdBefore = false
    private var settledBefore = false

    @Before
    fun setUp() {
        holdBefore = WelcomeHold.active
        settledBefore = WarmupLedger.settled.value
        WarmupLedger.settled.value = false
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        WelcomeHold.active = holdBefore
        WarmupLedger.settled.value = settledBefore
    }

    /** MainActivity's backstop as it arms at attach (MainActivity.kt:872). Its body is pinned in the last test. */
    private fun armBackstopAtAttach() {
        Handler(Looper.getMainLooper()).postDelayed({ WelcomeHold.releaseFromBackstop() }, WelcomeHoldBackstopMs)
    }

    private fun advanceLooper(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun splashComposed() =
        compose.onAllNodesWithText("M I M I R", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `attach 1 s before the first frame - the backstop keeps the gate closed until the splash is disposed`() {
        val firstFrameMs = 1_000L
        // On this clock the backstop fires at 11,880 ms and the splash can stay until 12,380 ms.
        assertTrue(
            "premise: the backstop fires inside the splash's maximum hold",
            WelcomeHoldBackstopMs < firstFrameMs + WelcomeHoldMaxMs,
        )
        WelcomeHold.active = true // MainActivity.kt:870, before the first frame
        armBackstopAtAttach()
        advanceLooper(firstFrameMs) // the first frame; the hold's clock starts after it
        compose.setContent { MimirColdStartWelcome(returningUser = true) { } }
        assertEquals(1, WelcomeHold.splashSurfacesComposed)

        advanceLooper(WelcomeHoldBackstopMs - firstFrameMs + 1) // just past the backstop
        assertTrue("the splash is still composed", splashComposed())
        assertTrue("the backstop must not open the gate under the splash", WelcomeHold.active)

        // The splash ends (ledger settled, beat over, fade done), and its own layer opens the gate.
        WarmupLedger.settled.value = true
        compose.mainClock.advanceTimeBy(3_500)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(splashComposed())
        assertFalse("the splash's dispose opens the gate", WelcomeHold.active)
        assertEquals(0, WelcomeHold.splashSurfacesComposed)
    }

    @Test
    fun `with no splash composed the backstop still opens the gate at its deadline`() {
        assertEquals(0, WelcomeHold.splashSurfacesComposed)
        WelcomeHold.active = true
        armBackstopAtAttach()

        advanceLooper(WelcomeHoldBackstopMs - 1)
        assertTrue(WelcomeHold.active)

        advanceLooper(2)
        assertFalse("the last resort still fires when no splash ever composes", WelcomeHold.active)
    }

    @Test
    fun `only the splash path composes a splash surface, and it unregisters when it leaves`() {
        var splashPath by mutableStateOf(true)
        WelcomeHold.active = true
        compose.setContent {
            if (splashPath) MimirColdStartWelcome(returningUser = true) { } else MimirColdStartWelcome(returningUser = false) { }
        }
        assertEquals(1, WelcomeHold.splashSurfacesComposed)

        compose.runOnUiThread { splashPath = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertEquals("a first-run welcome composes no splash surface", 0, WelcomeHold.splashSurfacesComposed)
        assertFalse("the leaving layer's dispose still opens the gate", WelcomeHold.active)
    }

    @Test
    fun `MainActivity's backstop releases through the guarded release, armed at attach and cancelled in onDestroy`() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt")
        assertTrue("MainActivity.kt must be readable (${file.absolutePath})", file.isFile)
        val lines = file.readLines().map(String::trim)

        assertTrue(
            "the backstop runnable must call WelcomeHold.releaseFromBackstop()",
            "private val welcomeHoldBackstop = Runnable { WelcomeHold.releaseFromBackstop() }" in lines,
        )
        assertTrue(
            "armed on the decor view",
            "window.decorView.postDelayed(welcomeHoldBackstop, WelcomeHoldBackstopMs)" in lines,
        )
        assertTrue(
            "cancelled in onDestroy",
            "window.decorView.removeCallbacks(welcomeHoldBackstop)" in lines,
        )
    }
}
