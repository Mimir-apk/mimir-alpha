package com.gruning.mimir.rewrite.feature.library

import android.animation.ValueAnimator
import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.mimirSwapExitSpec
import com.gruning.mimir.rewrite.core.ui.rememberMimirHeldGlassProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The progress timeline of the Bottom launch card's two clocks (audit 2026-10-14, RC-05: `CompanionPlatformShelf` and `CompanionLaunchCardLayer`,
 * pinned by source in `:app`'s AuditW2L7SourcePinTest): the scene's glass through core-ui's `rememberMimirHeldGlassProgress` (the function
 * both sites call) and the card's own alpha on `companionLaunchCardSpec` (the spec both sites share), the swap tier's enter and exit.
 *
 *  - Open, soften first: the glass is well on its way before the card's lead ends and the card begins to arrive.
 *  - Close, dissolve then clear: no frame has the card mid-dissolve over a glass below its peak, and the frame the glass starts to clear finds
 *    the card gone.
 *
 * The one-value shape this replaced (blur, scrim and card alpha on one OverlayMs tween) is run beside it and breaks the close law, so the
 * harness is known to bite. Robolectric does not draw a blur; the pixels are device-owed (SWEEP TH4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionLaunchCardGlassTimelineTest {

    @get:Rule
    val compose = createComposeRule()

    private var open by mutableStateOf(false)

    /** Compose reads the animator duration scale as its motion scale; a test earlier in this JVM may have left it raised. Pinned at 1 here. */
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Before
    fun pinAnimatorScale() = setAnimatorScale(1f)

    @After
    fun restoreAnimatorScale() = setAnimatorScale(1f)

    private class Sample(val glass: Float, val oneValue: Float, val card: Float)

    private val latest = mutableStateOf<Sample?>(null)

    @Composable
    private fun site() {
        // The production functions themselves (W2L7 review #4): the held glass both cards' blur rides, and the cards' one shared spec.
        val glass = rememberMimirHeldGlassProgress(visible = open)()
        val card by animateFloatAsState(if (open) 1f else 0f, companionLaunchCardSpec(up = open, reducedMotion = false), label = "card")
        // The shape replaced: one OverlayMs tween carrying the blur and the card together.
        val oneValue by animateFloatAsState(if (open) 1f else 0f, mimirSwapExitSpec(false), label = "one-value")
        // Read in composition (a read inside the effect alone would not recompose this scope once the glass settles).
        val cardNow = card
        val oneValueNow = oneValue
        SideEffect { latest.value = Sample(glass, oneValueNow, cardNow) }
    }

    private fun frame() {
        compose.mainClock.advanceTimeBy(16L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun midDissolve(s: Sample) = s.card > 0.05f && s.card < 0.95f

    @Test
    fun `the card's glass softens the scene first, holds through the card's dissolve and clears after it`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { site() }
        repeat(10) { frame() }
        Snapshot.withMutableSnapshot { open = true }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        val opening = mutableListOf<Sample>()
        repeat(60) {
            frame()
            latest.value?.let(opening::add)
        }
        val openingTrace = opening.joinToString(" ") { "%.2f/%.2f".format(it.card, it.glass) }
        assertEquals("fixture: the card is up: $openingTrace", 1f, latest.value!!.card, 0.001f)
        assertEquals("fixture: the glass is at its peak: $openingTrace", 1f, latest.value!!.glass, 0.001f)
        val cardBegins = opening.indexOfFirst { it.card > 0.05f }
        val glassBegins = opening.indexOfFirst { it.glass > 0.05f }
        assertTrue("fixture: both ran ($glassBegins, $cardBegins)", cardBegins > 0 && glassBegins > 0)
        assertTrue("the glass softens the scene before the card begins to arrive (glass frame $glassBegins, card frame $cardBegins)", glassBegins < cardBegins)
        assertTrue("the glass is well on its way when the card's lead ends", opening[cardBegins].glass > 0.5f)

        Snapshot.withMutableSnapshot { open = false }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        val closing = mutableListOf<Sample>()
        repeat(40) {
            frame()
            latest.value?.let(closing::add)
        }
        assertEquals("fixture: closed", 0f, latest.value!!.card, 0.001f)
        assertEquals("fixture: cleared", 0f, latest.value!!.glass, 0.001f)
        val violations = closing.filter { midDissolve(it) && it.glass < 0.999f }
        assertTrue("no frame has the card mid-dissolve over a glass below its peak: ${violations.map { "%.2f/%.2f".format(it.card, it.glass) }}", violations.isEmpty())
        val firstClear = closing.first { it.glass < 0.999f }
        assertTrue("the frame the glass starts to clear finds the card gone (card %.3f)".format(firstClear.card), firstClear.card <= 0.05f)
        assertTrue("the one-value shape replaced breaks the law, so the harness bites", closing.count { midDissolve(it) && it.oneValue < 0.999f } > 0)
    }
}
