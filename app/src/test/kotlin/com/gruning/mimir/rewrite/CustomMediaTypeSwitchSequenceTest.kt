package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Customize media TYPE changes previously flashed old content. Frame by frame: the menu
 * starts to fade and the frost lifts; the page under it still shows the OLD type's tiles; the page then fades out to an empty, blurred page; then the new type's tiles fade in.
 *
 * Two causes, found by stepping the real page (the REAL [HomeCustomEnrichPickerOverlay], through [CustomMediaMotionProbe]):
 *  1. The body swapped on the swap tier: the page left fades over [MimirMotion.OverlayMs] and the page arriving waits the 130 ms lead, so the old tiles stand for the menu's whole dissolve and the pane is
 *     near-empty at 130 ms. L1/R1 took the same swap, and dipped the same.
 *  2. A type change builds a new picker with a new session id, and the glass under the Media panel was keyed on it: the frost was reborn at 0 on the frame of the pick ("the frost lifts"), then ramped
 *     back up for the 130 ms the hold keeps it ("blurred"), then cleared.
 *
 * What the page does now, as the Library's OPEN WITH pick does (the row names the new emulator at once; the menu's dissolve is the only motion): a pick from the Media panel is committed UNDER the frost.
 * The page holds the new type before the frost or the menu begin to clear, the frost holds steady for the hold and clears once, and no frame of the clearing shows the old type or an empty page. L1/R1 has
 * no panel to hide behind, so it is the swap tier's one-clock cross-fade: the two pages complementary at every frame.
 *
 * The alphas are the states the draw blocks read (ring-safe pages cannot be read from pixels); the paused clock steps 16 ms at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w900dp-h560dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMediaTypeSwitchSequenceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val probe = CustomMediaMotionProbe()

    private val menu = HomeTileMenuState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("gba/metroid-fusion")),
            rank = 1L,
            addedAtMillis = 10L,
            title = "Metroid Fusion",
        ),
        removable = true,
    )
    private val types = homeTileCustomMediaTypes(menu)
    private val typeFilters = customMediaTypeFilters(menu, types)
    private val hero = types.first { it.kind == EnrichMediaKind.HERO }
    private val motion = types.first { it.kind == EnrichMediaKind.CLIP }

    /** The page as the builder makes it for [type]; a type change gives it a session of its own, as `buildHomeCustomEnrichPicker` does. */
    private fun stateFor(type: CustomMediaType, panelOpen: Boolean) = HomeCustomEnrichState(
        sessionId = "switch-${type.label}-${if (panelOpen) "open" else "shut"}",
        kind = type.kind,
        filters = typeFilters.getValue(type.selection),
        shelfIconArt = type.isMergedIconArt,
        cacheKey = mediaStorageKey("gba/metroid-fusion", type.mediaState),
        gameTitle = "Metroid Fusion",
        queryDraft = "Metroid Fusion",
        defaultArt = HomeCustomEnrichDefaultArt.ConsoleLineArt("gba"),
        mediaState = type.mediaState,
        mediaTypes = types,
        mediaTypeFilters = typeFilters,
        mediaPanelOpen = panelOpen,
    )

    private var shown by mutableStateOf(stateFor(hero, panelOpen = false))

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalCustomMediaMotionProbe provides probe,
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(832.dp, 468.dp)) {
                        HomeCustomEnrichPickerOverlay(
                            authority = authority,
                            state = shown,
                            onAction = {},
                            onQueryChange = {},
                            onKeyboardDone = {},
                        )
                    }
                }
            }
        }
    }

    private fun frame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(16L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** One frame after a press: the glass, the alpha of the page left (old) and of the page arriving (new). */
    private data class Frame(val atMs: Int, val glass: Float, val old: Float, val new: Float)

    private fun alpha(type: CustomMediaType): Float = probe.pageAlphas[type.selection]?.value ?: 0f

    private fun settle() = repeat(75) { frame() }

    /** The frames of 16 ms from the press that writes [next], for [count] frames. */
    private fun press(next: HomeCustomEnrichState, old: CustomMediaType, new: CustomMediaType, count: Int): List<Frame> {
        Snapshot.withMutableSnapshot { shown = next }
        Snapshot.sendApplyNotifications()
        return (0 until count).map { index ->
            frame()
            Frame((index + 1) * 16, probe.glass!!.invoke(), alpha(old), alpha(new))
        }
    }

    private fun describe(frames: List<Frame>) =
        frames.joinToString("\n") { "%3d ms  glass %.2f  old %.2f  new %.2f".format(it.atMs, it.glass, it.old, it.new) }

    @Test
    fun `a pick from the Media panel is made under the frost, which holds and then clears once, and no clearing frame shows the old type or an empty page`() {
        shown = stateFor(hero, panelOpen = true)
        mount()
        settle()
        assertEquals("the frost is whole behind the open panel", 1f, probe.glass!!.invoke(), 0.001f)
        assertEquals("the page shows Hero art", 1f, alpha(hero), 0.001f)

        val frames = press(stateFor(motion, panelOpen = false), old = hero, new = motion, count = 45)
        val why = "\n" + describe(frames)
        println("TYPESWITCH pick$why")

        // The frost: whole for the hold (the glass the menu's dissolve plays out over), never reborn, then cleared on the glass clock.
        frames.filter { it.atMs <= MimirMotion.OverlayDissolveCoverMs - 32 }.forEach {
            assertTrue("the frost lifts at ${it.atMs} ms, while the menu is still dissolving$why", it.glass >= 0.99f)
        }
        frames.zipWithNext().forEach { (a, b) ->
            assertTrue("the frost rises again between ${a.atMs} and ${b.atMs} ms$why", b.glass <= a.glass + 0.001f)
        }
        assertTrue("and it clears$why", frames.last().glass <= 0.01f)

        // The page: the new type is whole and the old one gone from the second frame after the pick (the transition's clock takes one frame to start, as in the cross-swap below), far inside the
        // hold. The one frame the old page is still drawn, the frost is whole over it, so wherever the frost has begun to clear the page is the new type's.
        frames.filter { it.old > 0f }.forEach {
            assertTrue("the old type is drawn at ${it.atMs} ms with the frost at ${it.glass}$why", it.glass >= 0.99f && it.atMs <= 16)
        }
        frames.filter { it.atMs >= 32 }.forEach {
            assertEquals("the new type is not whole at ${it.atMs} ms$why", 1f, it.new, 0.001f)
            assertEquals("the old type is still drawn at ${it.atMs} ms$why", 0f, it.old, 0.001f)
        }
        frames.filter { it.glass < 0.99f }.forEach {
            assertEquals("the frost is clearing over the old type at ${it.atMs} ms$why", 0f, it.old, 0.001f)
            assertTrue("the frost is clearing over an empty page at ${it.atMs} ms (new ${it.new})$why", it.new >= 0.999f)
        }
    }

    @Test
    fun `L1 and R1 cross-swap the types, the two pages complementary at every frame, with no dip through an empty page`() {
        shown = stateFor(hero, panelOpen = false)
        mount()
        settle()
        assertEquals("the page shows Hero art", 1f, alpha(hero), 0.001f)

        val frames = press(stateFor(motion, panelOpen = false), old = hero, new = motion, count = 30)
        val why = "\n" + describe(frames)
        println("TYPESWITCH L1/R1$why")
        frames.forEach {
            assertTrue("the pane is emptier than a cross-fade's overlap at ${it.atMs} ms (old ${it.old} + new ${it.new})$why", it.old + it.new >= 0.95f)
        }
        frames.zipWithNext().forEach { (a, b) ->
            assertTrue("the old type comes back between ${a.atMs} and ${b.atMs} ms$why", b.old <= a.old + 0.001f)
            assertTrue("the new type fades out between ${a.atMs} and ${b.atMs} ms$why", b.new >= a.new - 0.001f)
        }
        assertTrue("the new type begins by 48 ms (the clock takes two frames to start), not after a 130 ms lead (${frames[2].new})$why", frames[2].new > 0.01f)
        assertTrue("and is whole on the overlay clock$why", frames.first { it.new >= 0.999f }.atMs <= MimirMotion.OverlayMs + 48)
        assertEquals("the old type is gone$why", 0f, frames.last().old, 0.001f)
    }
}
