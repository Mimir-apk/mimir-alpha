package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
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
 * The Bottom's art reveal rides the product's one cold-art rule (audit wave 2, Q13): a cold ART arrival fades in from nothing on the shared spec ([com.gruning.mimir.rewrite.core.ui.MimirColdArtRevealSpec],
 * FocusMs on the house easing), where it used to carry a clock of its own (`MimirLedgeCoverRevealMs`). A warm bitmap and a fallback snap, and HIDING snaps, so a plate or ground never lingers while a
 * mounted site changes to a cold source. The proof is the alpha frame by frame at 16 ms with the clock paused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionArtRevealSpecTest {

    @get:Rule
    val compose = createComposeRule()

    private var visible by mutableStateOf(false)
    private var fadeIn by mutableStateOf(true)
    private var reveal: State<Float>? = null

    private val alpha: Float get() = reveal!!.value

    @Composable
    private fun Host() {
        reveal = rememberCompanionArtReveal(visible = visible, fadeIn = fadeIn)
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Host() }
        step(0L)
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun frames(count: Int): List<Float> = List(count) { step(16L); alpha }

    private fun write(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    @Test
    fun `a cold art arrival rises from nothing to whole over FocusMs`() {
        mount()
        assertEquals("nothing while it loads", 0f, alpha, 0f)
        write { visible = true }
        step(0L)
        val rising = frames(MimirMotion.FocusMs / 16 + 3)
        println("COMPANION-ART-REVEAL cold alpha per 16 ms: $rising")
        assertTrue("it starts from nothing, not at whole: ${rising.first()}", rising.first() < 0.5f)
        assertTrue("it is mid-fade partway through: $rising", rising.any { it > 0.05f && it < 0.95f })
        assertTrue("it never goes backwards: $rising", rising.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("and is whole after FocusMs: ${rising.last()}", 1f, rising.last(), 0.001f)
    }

    @Test
    fun `a warm bitmap or a fallback snaps in, and hiding snaps out`() {
        write { fadeIn = false }
        mount()
        write { visible = true }
        step(0L)
        val arriving = frames(4)
        println("COMPANION-ART-REVEAL warm alpha per 16 ms: $arriving")
        assertTrue("a snap has no intermediate frame: $arriving", arriving.all { it == 0f || it == 1f })
        assertEquals("and is whole within a few frames of being asked for", 1f, arriving.last(), 0f)
        write { visible = false }
        step(0L)
        val leaving = frames(4)
        assertTrue("hiding is a snap too: a plate never lingers over a cold source: $leaving", leaving.all { it == 0f || it == 1f })
        assertEquals(0f, leaving.last(), 0f)
    }

    @Test
    fun `the reveal takes its spec from the one cold-art rule and names no clock of its own`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionArtDraw.kt").takeIf(File::isFile)
            ?: File("feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionArtDraw.kt")
        val text = source.readText()
        assertTrue("it rides MimirColdArtRevealSpec", text.contains("if (companionArtRevealFades(visible, fadeIn, reduced)) MimirColdArtRevealSpec else snap()"))
        assertFalse("and the retired clock is gone", text.contains("MimirLedgeCoverRevealMs"))
    }
}
