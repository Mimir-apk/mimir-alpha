package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-11 (vqa11), P2-1: the Customize media page's "ᛗ · TYPE" panel lists seven shapes (All, Square, Front, 3D, Article, Texture, Back) and could not show them all: with All
 * lit, Back was half cut at the bottom; with Back lit, All and its tick were half cut under the eyebrow. The lit row was always whole, the rest were not.
 *
 * The real [HomeCustomEnrichPickerOverlay] is mounted with the Type panel open and each end of the list lit. At the Thor's Main (832.5 x 468.3 dp), at font 1.0 and 1.3, every row is whole at
 * rest, whichever is lit, and the lit row's ring is whole too (a ring's reach clear of the list's clip). Where the list must scroll (the compact 360 x 360 window, the RP5's 700 x 350),
 * no row rests half cut at either edge: the list stops on whole rows, whichever row is lit. The frames are written to `build/test-captures/sweepfix32/`.
 *
 * The class runs as API 30, the level at which the platform cannot blur, because a software canvas cannot draw the glass sheet's RenderNode layers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w900dp-h560dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMediaPanelRowsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f
    private val authority = DefaultFocusAuthority()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

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

    /** The merged Icon art page with its Type filter open: no art, no results, so only the panel is under test. */
    private fun typePanelState(): HomeCustomEnrichState {
        val type = types.first { it.label == "Icon art" }
        return HomeCustomEnrichState(
            sessionId = "panel-rows",
            kind = type.kind,
            filters = homeCustomEnrichIconArtFilters(),
            shelfIconArt = true,
            cacheKey = mediaStorageKey("gba/metroid-fusion", type.mediaState),
            gameTitle = "Metroid Fusion",
            queryDraft = "Metroid Fusion",
            saved = emptyList(),
            results = emptyList(),
            searchStatus = HomeCustomEnrichSearchStatus.Idle,
            defaultArt = HomeCustomEnrichDefaultArt.ConsoleLineArt("gba"),
            mediaState = type.mediaState,
            mediaTypes = types,
        ).let { base -> base.copy(openFilterKey = IconArtTypeFilterKey) }
    }

    private fun settle() {
        repeat(2) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(1_000L)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
        }
    }

    private val labels = listOf("All", "Square", "Front", "3D", "Article", "Texture", "Back")

    private fun mount(widthDp: Float, heightDp: Float, font: Float, lit: Int) {
        val state = typePanelState()
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        HomeCustomEnrichPickerOverlay(
                            authority = authority,
                            state = state,
                            onAction = {},
                            onQueryChange = {},
                            onKeyboardDone = {},
                        )
                    }
                }
            }
        }
        settle()
        light(lit)
    }

    private fun light(index: Int) {
        val state = typePanelState()
        val typeFilter = state.filters.first { it.key == IconArtTypeFilterKey }
        authority.requestFocus(homeCustomEnrichFilterOptionKey(state, typeFilter, index))
        settle()
    }

    private fun node(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first()

    private fun rowBounds(index: Int): Rect = node("custom-media-filter-option-$index").boundsInWindow

    /**
     * Every composed row: its own size and how much of it the list's clip lets through, in px (a node's `size` is its own; its `boundsInWindow` is clipped to the list), keyed by
     * index. A row that is whole shows exactly its size; one the list has scrolled half away shows less; one that is not composed is not here.
     */
    private fun describe(what: String): List<Triple<Int, Float, Float>> {
        val list = node("custom-media-filter-rows").boundsInWindow
        val panel = node(CustomMediaFilterPanelTag).boundsInWindow
        println("PANELROWS $what: panel ${panel.top}..${panel.bottom} (${panel.height / density} dp), list ${list.top}..${list.bottom} (${list.height / density} dp)")
        return labels.indices.mapNotNull { index ->
            val found = compose.onAllNodesWithTag("custom-media-filter-option-$index", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            found?.let {
                val size = it.size.height.toFloat()
                val showing = it.boundsInWindow.height
                println("PANELROWS $what: ${labels[index]} ${it.boundsInWindow.top}..${it.boundsInWindow.bottom} (own $size px), showing $showing px")
                Triple(index, size, showing)
            }
        }
    }

    private fun write(name: String) {
        val decor = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/test-captures/sweepfix32").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("SWEEPFIX32 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: $out", out.isFile && out.length() > 1_000L)
    }

    private fun assertRingClear(lit: Int, name: String) {
        val list = node("custom-media-filter-rows").boundsInWindow
        val reach = MimirFocusTileRingReach.value * density
        val litBounds = rowBounds(lit)
        assertTrue("$name: the lit row's ring is clear of the list's clip above (${litBounds.top - reach} vs ${list.top})", litBounds.top - reach >= list.top - 0.6f)
        assertTrue("$name: the lit row's ring is clear of the list's clip below (${litBounds.bottom + reach} vs ${list.bottom})", litBounds.bottom + reach <= list.bottom + 0.6f)
    }

    // ── Main: seven whole rows ──────────────────────────────────────────────────────────────────

    private fun main(font: Float, lit: Int, name: String) {
        mount(832.5f, 468.3f, font, lit = lit)
        val rows = describe(name)
        assertEquals("$name: all seven rows are in the list", 7, rows.size)
        rows.forEach { (index, size, showing) ->
            assertEquals("$name: ${labels[index]} is whole: $showing of $size px", size, showing, 0.6f)
        }
        assertRingClear(lit, name)
        write("type-panel-$name.png")
    }

    @Test
    fun `all seven Type rows are whole on Main at font 1_0 with All lit`() = main(1.0f, 0, "main-1.0-all-lit")

    @Test
    fun `all seven Type rows are whole on Main at font 1_0 with Back lit`() = main(1.0f, 6, "main-1.0-back-lit")

    @Test
    fun `all seven Type rows are whole on Main at font 1_3 with All lit`() = main(1.3f, 0, "main-1.3-all-lit")

    @Test
    fun `all seven Type rows are whole on Main at font 1_3 with Back lit`() = main(1.3f, 6, "main-1.3-back-lit")

    // ── Windows too small for seven rows: the list scrolls and stops on whole rows ───────────────

    private fun scrolling(widthDp: Float, heightDp: Float, font: Float, name: String) {
        mount(widthDp, heightDp, font, lit = 0)
        for (lit in listOf(0, 3, 6, 2, 0)) {
            light(lit)
            val what = "$name, ${labels[lit]} lit"
            val rows = describe(what)
            assertTrue("$what: the lit row is composed", rows.any { it.first == lit })
            rows.forEach { (index, size, showing) ->
                assertTrue(
                    "$what: ${labels[index]} rests whole or not at all, not half cut ($showing of $size px)",
                    showing < 0.6f || kotlin.math.abs(showing - size) <= 0.6f,
                )
            }
            val whole = rows.count { kotlin.math.abs(it.third - it.second) <= 0.6f }
            println("PANELROWS $what: $whole whole rows stand")
            assertRingClear(lit, what)
            if (lit == 0 || lit == 6) write("type-panel-$name-${labels[lit].lowercase()}-lit.png")
        }
        // A touch drag and release lands on the row grid too, whether the rows divide the list evenly or not: the list snaps an item to its first row, not to its middle (which is a whole
        // row only for an odd number of rows). Short, slow drags, so the release picks a resting place in the middle of the list rather than flinging to an end (where the rows are whole anyway).
        val list = compose.onNodeWithTag("custom-media-filter-rows", useUnmergedTree = true)
        val rowPx = describe("$name, row size").first().second
        for ((up, rowsDragged) in listOf(true to 1.2f, true to 0.7f, false to 0.6f, true to 1.4f, false to 1.3f)) {
            list.performTouchInput {
                val travel = rowPx * rowsDragged
                val from = androidx.compose.ui.geometry.Offset(center.x, center.y + (if (up) travel / 2f else -travel / 2f))
                val to = androidx.compose.ui.geometry.Offset(center.x, center.y + (if (up) -travel / 2f else travel / 2f))
                swipe(start = from, end = to, durationMillis = 600)
            }
            repeat(5) {
                Snapshot.sendApplyNotifications()
                compose.mainClock.advanceTimeBy(1_000L)
                compose.waitForIdle()
            }
            val what = "$name, after a drag ${if (up) "up" else "down"} of $rowsDragged rows"
            val rows = describe(what)
            val rowRoom = node("custom-media-filter-rows").boundsInWindow.height / rowPx
            println("PANELROWS $what: the list holds $rowRoom rows; first row shown is ${labels[rows.first().first]}")
            rows.forEach { (index, size, showing) ->
                assertTrue(
                    "$what: ${labels[index]} rests whole or not at all, not half cut ($showing of $size px; the list holds $rowRoom rows)",
                    showing < 0.6f || kotlin.math.abs(showing - size) <= 0.6f,
                )
            }
        }
    }

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the Type list scrolls on whole rows in the compact window at font 1_3`() = scrolling(360f, 360f, 1.3f, "compact-1.3")

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the Type list scrolls on whole rows in the compact window at font 1_0`() = scrolling(360f, 360f, 1.0f, "compact-1.0")

    @Test
    fun `the Type list scrolls on whole rows on the RP5 at font 1_3`() = scrolling(700f, 350f, 1.3f, "rp5-1.3")

    // ── The list's layout, in a window of any size ──────────────────────────────────────────────

    @Test
    fun `a list that fits keeps its ring air and one that does not is a whole number of rows with a whole row of air`() {
        val row = 96
        val ring = 28
        // Seven rows and the ring's air at each end: 728 px. A room of that or more is the list as it is.
        assertEquals(ActivityPickerListFit(ring, null), activityPickerListFit(800, row, ring, 7))
        assertEquals(ActivityPickerListFit(ring, null), activityPickerListFit(728, row, ring, 7))
        // One pixel less: the list scrolls, seven rows tall (727 px hold seven rows), with a whole row of air at each end instead of the ring's.
        assertEquals(ActivityPickerListFit(row, 7 * row), activityPickerListFit(727, row, ring, 7))
        assertEquals(ActivityPickerListFit(row, 6 * row), activityPickerListFit(671, row, ring, 7))
        assertEquals(ActivityPickerListFit(row, 4 * row), activityPickerListFit(450, row, ring, 7))
        // A room under three rows keeps the ring's air and the room.
        assertEquals(ActivityPickerListFit(ring, null), activityPickerListFit(250, row, ring, 7))
        // A degenerate row or an empty list leaves the room alone.
        assertEquals(ActivityPickerListFit(ring, null), activityPickerListFit(500, 0, ring, 7))
        assertEquals(ActivityPickerListFit(ring, null), activityPickerListFit(500, row, ring, 0))
    }
}
