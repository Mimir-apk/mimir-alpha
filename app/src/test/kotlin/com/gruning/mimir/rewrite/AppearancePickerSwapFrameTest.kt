package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirArrivalFrameCapMs
import com.gruning.mimir.rewrite.core.ui.MimirCrossSwapMs
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.withMimirStallGuard
import kotlin.math.abs
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Audit 2026-10-15, Thor check 7 (combined43, `st3_theme_open.jpg`): APPEARANCE to THEME opened as a 0.5 s cross-fade with the Settings pane and the theme grid both readable for about 0.2 s (the sharp pane
 * stands under the opaque page: none of the three has scene glass). The shared swap policy uses one clock for sibling sub-pages, and the Theme gallery, the Background picker and the Mimir-button
 * picker are siblings under APPEARANCE, so all three take the sub-pages' swap clock: one [MimirCrossSwapMs], no lead, arriving and leaving alike, instead of the page tier's
 * [MimirMotion.OverlayDissolveCoverMs] lead and [MimirMotion.ShineMs] arrival.
 *
 * The host's pixel fade records its layers in `GraphicsLayer`s, which a JVM software canvas cannot draw, so the timeline is the one the host runs, built from the host's own policy functions: the arriving layer's alpha
 * is `tween(arriveMs, delayMillis = lead, MimirMotion.Easing)` ([MimirPixelFadeHost]'s own expression, pinned by `UtilityArrivalNotSquaredTest`), the departing one leaves on [mimirDirectOverlayDepartMs]. They are
 * drawn over a static Settings pane (a solid red), the page a solid blue over it, and sampled frame by frame off the composed pixels: red is how much of the pane still reaches the eye, blue how much of the page.
 * A frame on which both are above the readable 15% is a frame of overprint. [AppearancePickerArrivalContentTest] runs the real pickers under the same clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearancePickerSwapFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f

    private class Frame(val ms: Int, val pane: Float, val page: Float) {
        val bothShown: Boolean get() = pane > 0.15f && page > 0.15f
    }

    /** The frame time the animation reads, with a jump of [stallNanos] added at tick [atTick] and kept: what a long frame does to the Choreographer's clock. */
    private class StalledFrameClock(private val base: MonotonicFrameClock, private val atTick: Int, private val stallNanos: Long) : MonotonicFrameClock {
        private var tick = 0
        private var offset = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = base.withFrameNanos { real ->
            tick += 1
            if (tick == atTick) offset += stallNanos
            onFrame(real + offset)
        }
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * Runs the host's arrival ([arriveMs] after [leadMs], on the frame clock [capMs] caps: the host's own `withMimirStallGuard`) and then its departure ([departMs]) over a static pane, 16 ms a frame, and reads
     * the pane and the page at each. [stallAtTick] (1-based; the first tick is the clock's start) is a long frame of [stallMs]: the Choreographer's frame time jumps by that much at that tick, which is what the device's
     * frame stats show once the page's first frames have run. A Compose test's own clock cannot do it (it ticks every 16 ms whatever it is asked to skip), so the jump is in the frame time the animation reads, under the guard.
     */
    private fun run(
        arriveMs: Int,
        leadMs: Int,
        departMs: Int,
        frames: Int,
        capMs: Int = 0,
        stallAtTick: Int = -1,
        stallMs: Long = 0,
    ): Pair<List<Frame>, List<Frame>> {
        var open by mutableStateOf(false)
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            val alpha = remember { Animatable(0f) }
            LaunchedEffect(open) {
                if (open) {
                    val arrival: suspend () -> Unit = {
                        withMimirStallGuard(capMs) { alpha.animateTo(1f, tween(arriveMs, delayMillis = leadMs, easing = MimirMotion.Easing)) }
                    }
                    val ambient = coroutineContext[MonotonicFrameClock]
                    if (stallAtTick > 0 && ambient != null) {
                        withContext(StalledFrameClock(ambient, stallAtTick, stallMs * 1_000_000L)) { arrival() }
                    } else {
                        arrival()
                    }
                } else if (alpha.value > 0f) {
                    alpha.animateTo(0f, tween(departMs, easing = MimirMotion.Easing))
                }
            }
            Box(Modifier.requiredSize(200.dp, 200.dp).background(Color.Black)) {
                Box(Modifier.fillMaxSize().background(Color(255, 0, 0)))
                Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color(0, 0, 255)))
            }
        }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        fun sample(ms: Int): Frame {
            val p = bitmap().getPixel((100 * density).toInt(), (100 * density).toInt())
            return Frame(ms, ((p shr 16) and 0xFF) / 255f, (p and 0xFF) / 255f)
        }
        compose.mainClock.autoAdvance = false
        write { open = true }
        val arriving = ArrayList<Frame>()
        for (frame in 1..frames) {
            compose.mainClock.advanceTimeBy(16)
            arriving += sample(frame * 16)
        }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        write { open = false }
        val leaving = ArrayList<Frame>()
        for (frame in 1..frames) {
            compose.mainClock.advanceTimeBy(16)
            leaving += sample(frame * 16)
        }
        return arriving to leaving
    }

    private fun write(change: () -> Unit) {
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { change() }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
    }

    private fun settledAt(frames: List<Frame>): Int {
        val lastShort = frames.indexOfLast { it.page < 0.98f }
        return if (lastShort < 0) 0 else frames[lastShort].ms + 16
    }

    private val siblings = listOf(MimirOverlayKind.THEME_PICKER, MimirOverlayKind.BACKGROUND_PICKER, MimirOverlayKind.MBUTTON_PICKER)

    @Test
    fun `the three siblings are given one clock, frame for frame`() {
        siblings.forEach { kind ->
            assertEquals("$kind arrives over the sub-pages' swap duration", MimirCrossSwapMs, mimirIncomingOverlayArriveMs(kind, null, reducedMotion = false))
            assertEquals("$kind takes no lead", 0, mimirIncomingOverlayLeadMs(reducedMotion = false, kind = kind))
            assertEquals("$kind leaves on the same clock", MimirCrossSwapMs, mimirDirectOverlayDepartMs(kind, null))
            assertEquals("$kind snaps under reduced motion", 0, mimirIncomingOverlayArriveMs(kind, null, reducedMotion = true))
            // The device's entry is a state flip over the bare scene (`onRowActivate` -> `openThemePicker` / `openBackgroundPicker` / `openMimirButtonPicker`: the picker's state and its scope, nothing else), which the host
            // presents as a cold arrival: the page starts at 0 and fades in. It is not mounted at 1 (no enter to skip), and no AnimatedContent is involved.
            assertEquals(
                "$kind opens from 0",
                0f,
                mimirIncomingLayerStartAlpha(MimirDirectOverlayTarget(kind), cachedLayerTarget = null, cachedLayerAlpha = 0f, hasOutgoing = true, replacedKind = null),
                0f,
            )
            assertEquals("$kind's clock is guarded against a long frame", MimirArrivalFrameCapMs, mimirIncomingOverlayStallCapMs(kind, reducedMotion = false))
            assertEquals("and not under reduced motion", 0, mimirIncomingOverlayStallCapMs(kind, reducedMotion = true))
        }
        (MimirOverlayKind.entries - siblings.toSet()).forEach { kind ->
            assertEquals("$kind keeps the ambient clock", 0, mimirIncomingOverlayStallCapMs(kind, reducedMotion = false))
        }
    }

    /** One sibling's arrival and departure over the pane: whole on the one clock, complementary both ways, the pane and the page readable together for a handful of frames only. */
    private fun assertCrossSwap(kind: MimirOverlayKind) {
        val arrive = mimirIncomingOverlayArriveMs(kind, null, reducedMotion = false)
        val lead = mimirIncomingOverlayLeadMs(reducedMotion = false, kind = kind)
        val depart = mimirDirectOverlayDepartMs(kind, null)
        assertEquals("the sub-pages' swap duration", MimirCrossSwapMs, arrive)
        assertEquals("no lead: the scene has no glass to soften first", 0, lead)
        assertEquals("and the same clock out", MimirCrossSwapMs, depart)

        val (arriving, leaving) = run(arrive, lead, depart, frames = 30, capMs = mimirIncomingOverlayStallCapMs(kind, reducedMotion = false))
        println("APPEARANCEFRAME $kind arriving page " + arriving.joinToString(" ") { "%.2f".format(it.page) })
        println("APPEARANCEFRAME $kind arriving pane " + arriving.joinToString(" ") { "%.2f".format(it.pane) })
        println("APPEARANCEFRAME $kind leaving page  " + leaving.joinToString(" ") { "%.2f".format(it.page) })
        assertTrue("the harness reads the pane before and the page after", arriving.last().page > 0.98f && leaving.last().page < 0.02f)

        // No lead: the page has begun within three frames (the first two are the effect's own launch, as for the sub-pages), and is whole by the swap clock (OverlayMs) and a frame or two.
        assertTrue("the page begins within three frames (${arriving[2].page})", arriving[2].page > 0.01f)
        val whole = settledAt(arriving)
        println("APPEARANCEFRAME $kind arrival whole at $whole ms; both readable on ${arriving.count { it.bothShown }} frames arriving, ${leaving.count { it.bothShown }} leaving")
        assertTrue("whole by the one clock ($whole ms)", whole <= MimirCrossSwapMs + 32)
        // The sharp pane under the page: readable with it for a few frames, not half a second (was about 12 frames on 130 + 540 ms).
        assertTrue("pane and page readable together on at most five frames arriving (${arriving.count { it.bothShown }})", arriving.count { it.bothShown } <= 5)
        assertTrue("and leaving (${leaving.count { it.bothShown }})", leaving.count { it.bothShown } <= 5)
        // Both ways one swap: the page arriving and the page leaving are mirror images, frame for frame.
        arriving.indices.forEach { i ->
            assertTrue(
                "frame $i: the page's arrival (${arriving[i].page}) and departure (${leaving[i].page}) are complementary",
                abs(arriving[i].page + leaving[i].page - 1f) <= 0.04f,
            )
        }
    }

    @Test
    fun `the Theme gallery arrives and leaves on the sub-pages' one clock, the pane and the grid complementary`() = assertCrossSwap(MimirOverlayKind.THEME_PICKER)

    @Test
    fun `the Background picker arrives and leaves on the sub-pages' one clock, the pane and the page complementary`() = assertCrossSwap(MimirOverlayKind.BACKGROUND_PICKER)

    @Test
    fun `the Mimir-button picker arrives and leaves on the sub-pages' one clock, the pane and the page complementary`() = assertCrossSwap(MimirOverlayKind.MBUTTON_PICKER)

    @Test
    fun `the page tier they left would have held the sharp pane under the page for about a dozen frames`() {
        // The measured contrast the change is judged by: the page tier's arrival, which a Settings picker over scene glass (the Backdrop picker) still takes.
        val arrive = mimirIncomingOverlayArriveMs(MimirOverlayKind.BACKDROP_PICKER, null, reducedMotion = false)
        val lead = mimirIncomingOverlayLeadMs(reducedMotion = false, kind = MimirOverlayKind.BACKDROP_PICKER)
        assertEquals(MimirMotion.ShineMs, arrive)
        assertEquals(MimirMotion.OverlayDissolveCoverMs, lead)
        val (arriving, _) = run(arrive, lead, MimirMotion.OverlayMs, frames = 60)
        val both = arriving.count { it.bothShown }
        println("APPEARANCEFRAME page tier: both readable on $both frames, whole at ${settledAt(arriving)} ms")
        assertTrue("the page tier's arrival leaves both readable for many more frames than the swap clock's five ($both)", both >= 8)
    }

    /**
     * The device's cold open (Thor drive 3, combined45): the clock starts on the first frame after the state flip, and a long frame follows (the page's first draws, a cold process's JIT). The Background picker lost
     * the whole fade to one 125 ms frame; the Theme gallery and the Mimir-button picker kept 30 and 50 ms of it. A Compose test ticks every 16 ms, so the long frame is a jump in the frame time the animation reads: [stallMs] at its second tick.
     */
    private fun assertSurvivesStall(kind: MimirOverlayKind, stallMs: Long) {
        val arrive = mimirIncomingOverlayArriveMs(kind, null, reducedMotion = false)
        val lead = mimirIncomingOverlayLeadMs(reducedMotion = false, kind = kind)
        val depart = mimirDirectOverlayDepartMs(kind, null)
        val (arriving, _) = run(arrive, lead, depart, frames = 60, capMs = mimirIncomingOverlayStallCapMs(kind, reducedMotion = false), stallAtTick = 2, stallMs = stallMs)
        println("APPEARANCEFRAME $kind with a $stallMs ms frame at tick 2: page " + arriving.joinToString(" ") { "%.2f".format(it.page) })
        val steps = arriving.zipWithNext { a, b -> b.page - a.page }
        assertTrue("$kind: no frame moves the page by more than a quarter (${steps.max()})", steps.max() <= 0.25f)
        val mid = arriving.count { it.page > 0.05f && it.page < 0.95f }
        assertTrue("$kind: the swap is drawn across at least six frames, as a healthy 16 ms run draws it (seven; unguarded, none) ($mid)", mid >= 6)
        assertTrue("$kind: and arrives whole (${arriving.last().page})", arriving.last().page > 0.98f)
    }

    @Test
    fun `the Theme gallery opens across its frames when a 125 ms frame follows the clock's start`() = assertSurvivesStall(MimirOverlayKind.THEME_PICKER, 125)

    @Test
    fun `the Background picker opens across its frames when a 125 ms frame follows the clock's start - it was one cut on the device`() = assertSurvivesStall(MimirOverlayKind.BACKGROUND_PICKER, 125)

    @Test
    fun `the Mimir-button picker opens across its frames when a 90 ms frame follows the clock's start`() = assertSurvivesStall(MimirOverlayKind.MBUTTON_PICKER, 90)

    @Test
    fun `without the guard the same long frame is a cut - the device's failure, reproduced`() {
        val kind = MimirOverlayKind.BACKGROUND_PICKER
        val (arriving, _) = run(
            mimirIncomingOverlayArriveMs(kind, null, reducedMotion = false),
            mimirIncomingOverlayLeadMs(reducedMotion = false, kind = kind),
            mimirDirectOverlayDepartMs(kind, null),
            frames = 60,
            capMs = 0,
            stallAtTick = 2,
            stallMs = 125,
        )
        println("APPEARANCEFRAME unguarded 125 ms frame at tick 2: page " + arriving.take(14).joinToString(" ") { "%.2f".format(it.page) })
        val steps = arriving.zipWithNext { a, b -> b.page - a.page }
        assertTrue("one frame moves the page by more than half (${steps.max()})", steps.max() > 0.5f)
        assertTrue("so the swap is drawn across fewer than four frames (${arriving.count { it.page > 0.05f && it.page < 0.95f }})", arriving.count { it.page > 0.05f && it.page < 0.95f } < 4)
    }
}
