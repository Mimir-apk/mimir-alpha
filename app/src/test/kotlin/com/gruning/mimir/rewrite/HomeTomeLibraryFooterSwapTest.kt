package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Observed on Thor: on the Tome library's footer, moving focus between the Home Shelf tome ("A OPEN · B BACK") and a custom one ("A OPEN · X UNPIN
 * · Y EDIT · B BACK") printed mush mid-swap: "A OPEN · A OPEN · X UNPI · B BACK", "A OPEN · B BACK · B BACK", "X UNPIN" over "Y EDIT". The words both lines
 * share stood at different x in the two lines and the whole line cross-faded.
 *
 * The REAL footer is mounted and its swap stepped a frame at a time on a paused clock, each frame rasterised the way this repo rasterises (the view hierarchy
 * drawn into a software bitmap). Every word the two lines share is looked for in the frame as its own pixels, taken from a reference of that word alone: at
 * every frame it must stand EXACTLY ONCE, at full strength (a faded or doubled copy is no match). When the swap has settled, every word of the new line stands
 * once, no word of the old line is left, and the line is centred. Pixel values are the JVM's; a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w900dp-h400dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTomeLibraryFooterSwapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 369f / 160f
    private val frameMs = 16L

    private val customs = (1..2).map { TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-00000000000$it")) }
    private val entries: List<HomeTomeLibraryEntry> = homeTomeLibraryEntries(
        HomeTomeDocument(
            pinOrder = customs.take(1),
            customTomes = customs.mapIndexed { i, id -> CustomHomeTome(id, "Tome ${i + 1}", emptyList(), createdAtMillis = 100L + i) },
        ),
        customs.mapIndexed { i, id -> id to HomeShelfTome(id, "Tome ${i + 1}", emptyList(), 0) }.toMap(),
        homeOverview = HomeShelfTome(TomeId.HomeOverview, "HOME SHELF", emptyList(), 0),
    )
    private val home = entries.first { it.isHomeShelf }
    private val pinned = entries.first { it is HomeTomeLibraryEntry.Tome && !it.isHomeShelf && it.pinned }
    private val unpinned = entries.first { it is HomeTomeLibraryEntry.Tome && !it.isHomeShelf && !it.pinned }
    private val create = entries.first { it is HomeTomeLibraryEntry.CreateCustom }

    private val homeLine = listOf("A OPEN", "B BACK")
    private val pinnedLine = listOf("A OPEN", "X UNPIN", "Y EDIT", "B BACK")
    private val unpinnedLine = listOf("A OPEN", "X PIN", "Y EDIT", "B BACK")
    private val createLine = listOf("A CREATE", "B BACK")
    private val everyWord = (homeLine + pinnedLine + unpinnedLine + createLine).distinct()

    private var focused by mutableStateOf<HomeTomeLibraryEntry>(home)

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MimirTheme {
                    Column(Modifier.requiredSize(800.dp, 400.dp).background(Color.Black)) {
                        Box(Modifier.width(500.dp).testTag(Stage)) {
                            HomeTomeLibraryFooter(HomeTomeLibraryPosition(current = 1, total = 7, canMoveUp = false, canMoveDown = true), focused)
                        }
                        everyWord.forEach { words ->
                            Box(Modifier.testTag("ref-$words")) { HintInk(words) }
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_500L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** With the clock paused a state write reaches the recomposer only once the snapshot's apply notifications go out. */
    private fun focus(entry: HomeTomeLibraryEntry) = compose.runOnUiThread {
        Snapshot.withMutableSnapshot { focused = entry }
        Snapshot.sendApplyNotifications()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    /** A word's pixels alone: the tight box round everything that is not the ground, as grey levels. */
    private class Patch(val words: String, val width: Int, val height: Int, val gray: FloatArray)

    private fun patchOf(words: String): Patch {
        val shot = compose.activity.shootWindow()
        val box = bounds("ref-$words")
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in box.top.toInt() until box.bottom.toInt()) {
            for (x in box.left.toInt() until box.right.toInt()) {
                if (shot.gray(x, y) > InkFloor) {
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("$words reference has ink", right >= left)
        val width = right - left + 1
        val height = bottom - top + 1
        return Patch(words, width, height, FloatArray(width * height) { shot.gray(left + it % width, top + it / width) })
    }

    /** The hint row's grey levels (a few rows more above and below), read once per frame. */
    private class Band(val width: Int, val height: Int, val gray: FloatArray)

    private fun bandOf(shot: Bitmap, row: Rect): Band {
        val x0 = row.left.toInt()
        val y0 = row.top.toInt() - 3
        val width = row.width.toInt()
        val height = row.height.toInt() + 6
        return Band(width, height, FloatArray(width * height) { shot.gray(x0 + it % width, y0 + it / width) })
    }

    /** The x of every place the word stands, whole, in this frame's hint row: a faded, a doubled or a half-covered copy does not count. */
    private fun standsAt(band: Band, patch: Patch): List<Int> {
        val found = ArrayList<Int>()
        val limit = MatchTolerance * patch.width * patch.height
        for (x in 0..band.width - patch.width) {
            for (y in 0..band.height - patch.height) {
                var sum = 0f
                var i = 0
                while (i < patch.gray.size && sum <= limit) {
                    sum += abs(band.gray[(y + i / patch.width) * band.width + x + i % patch.width] - patch.gray[i])
                    i++
                }
                if (sum <= limit) found += x
            }
        }
        // One word seen at several neighbouring offsets is still one word.
        val places = ArrayList<Int>()
        found.sorted().forEach { x -> if (places.isEmpty() || x - places.last() > patch.width / 2) places += x }
        return places
    }

    private lateinit var patches: Map<String, Patch>

    /** Asserts the frame, and returns how much ink the hint row holds in it. */
    private fun frame(label: String, shared: List<String>, save: String? = null): Int {
        val shot = compose.activity.shootWindow()
        val row = bounds(HomeTomeLibraryFooterHintTestTag)
        val band = bandOf(shot, row)
        // How much ink the row holds, and where each shared word stands: the swap's whole story in one line a frame.
        val ink = band.gray.sum().toInt()
        val stands = shared.associateWith { standsAt(band, patches.getValue(it)) }
        println("FOOTER-SWAP $label ink=$ink $stands")
        stands.forEach { (words, at) ->
            assertEquals("$label: '$words' stands exactly once at full strength (found at $at)", 1, at.size)
        }
        if (save != null) save(shot, bounds(HomeTomeLibraryFooterTestTag), save)
        return ink
    }

    private fun save(shot: Bitmap, box: Rect, name: String) {
        val pad = 6
        val crop = Bitmap.createBitmap(
            shot,
            (box.left.toInt() - pad).coerceAtLeast(0),
            (box.top.toInt() - pad).coerceAtLeast(0),
            box.width.toInt() + pad * 2,
            box.height.toInt() + pad * 2,
        )
        val out = File(File("build/test-captures/hintring").apply { mkdirs() }, name)
        out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("FOOTER-SWAP wrote ${out.absolutePath}")
    }

    /** What stands in the settled line: every word of [line] once, no other word at all, and the whole centred in the footer. */
    private fun assertSettled(label: String, line: List<String>) {
        val shot = compose.activity.shootWindow()
        val row = bounds(HomeTomeLibraryFooterHintTestTag)
        val band = bandOf(shot, row)
        everyWord.forEach { words ->
            val at = standsAt(band, patches.getValue(words))
            assertEquals("$label: '$words' settled ${if (words in line) "once" else "gone"} (found at $at)", if (words in line) 1 else 0, at.size)
        }
        val stage = bounds(Stage)
        var left = Int.MAX_VALUE
        var right = -1
        for (x in row.left.toInt() until row.right.toInt()) {
            for (y in row.top.toInt() until row.bottom.toInt()) {
                if (shot.gray(x, y) > InkFloor) { left = minOf(left, x); right = maxOf(right, x) }
            }
        }
        val centre = (left + right) / 2f
        assertTrue("$label: the line is centred: ink centre $centre vs the footer's ${stage.center.x}", abs(centre - stage.center.x) <= 2f)
    }

    /** Steps the swap from the first frame to past its end, asserting at every frame that the words both lines share stand once. */
    private fun swap(to: HomeTomeLibraryEntry, from: List<String>, target: List<String>, name: String? = null): List<Int> {
        val shared = from.filter { it in target }
        val inks = ArrayList<Int>()
        focus(to)
        val frames = (SwapBudgetMs / frameMs).toInt()
        for (i in 0..frames) {
            compose.mainClock.advanceTimeByFrame()
            // The start, the middle of the first and of the second step (96 ms and 272 ms in) and the end of the swap are kept for the lead to look at.
            val keep = when (i) {
                0 -> name?.let { "$it-start.png" }
                6 -> name?.let { "$it-mid1.png" }
                17 -> name?.let { "$it-mid2.png" }
                else -> null
            }
            inks += frame("$name frame $i (${i * frameMs} ms)", shared, keep)
        }
        assertSettled("$name settled", target)
        name?.let { save(compose.activity.shootWindow(), bounds(HomeTomeLibraryFooterTestTag), "$it-end.png") }
        return inks
    }

    @Test
    fun `the Home Shelf line and a custom tome's line swap with every shared word standing once at full strength`() {
        mount()
        patches = everyWord.associateWith(::patchOf)
        assertSettled("at rest on the Home Shelf", homeLine)
        val arriving = swap(pinned, homeLine, pinnedLine, "footer-home-to-custom")
        // The words that arrive FADE in: several frames hold part of their ink, none jumps from nothing to all of it.
        assertTrue("arriving words fade in over several frames: $arriving", arriving.count { it > arriving.first() && it < arriving.last() } >= 4)
        val leaving = swap(home, pinnedLine, homeLine, "footer-custom-to-home")
        assertTrue("leaving words fade out over several frames: $leaving", leaving.count { it < leaving.first() && it > leaving.last() } >= 4)
    }

    @Test
    fun `a pin that flips the word beside the shared ones swaps without a doubled word`() {
        mount()
        patches = everyWord.associateWith(::patchOf)
        focus(pinned)
        settle()
        assertSettled("at rest on a pinned custom tome", pinnedLine)
        swap(unpinned, pinnedLine, unpinnedLine, "footer-unpin-to-pin")
        swap(create, unpinnedLine, createLine, "footer-custom-to-create")
    }

    @Test
    fun `a change in the middle of a swap leaves from the words on screen and settles on the latest line`() {
        mount()
        patches = everyWord.associateWith(::patchOf)
        focus(pinned)
        // 'B BACK' stands in all three lines this goes through, so it must stand once on every frame of all of them.
        val always = listOf("B BACK")
        compose.mainClock.advanceTimeBy(100L)
        frame("mid-way to custom", always)
        focus(home)
        repeat(8) { compose.mainClock.advanceTimeByFrame(); frame("turning back to Home", always) }
        focus(create)
        repeat((SwapBudgetMs / frameMs).toInt()) { compose.mainClock.advanceTimeByFrame(); frame("on to Create", always) }
        assertSettled("settled on Create", createLine)
    }

    /** The matcher itself: a whole word is one place, a faded or half-covered copy none, a doubled word two. */
    @Test
    fun `the matcher counts a whole word once and neither a faded copy nor a doubled one as it`() {
        mount()
        val word = patchOf("A OPEN")
        val width = word.width * 3
        val height = word.height + 6
        fun band(vararg placements: Pair<Int, Float>): Band {
            val gray = FloatArray(width * height)
            placements.forEach { (x, alpha) ->
                for (i in word.gray.indices) {
                    val at = (3 + i / word.width) * width + x + i % word.width
                    gray[at] = maxOf(gray[at], word.gray[i] * alpha)
                }
            }
            return Band(width, height, gray)
        }
        assertEquals("found where it was put", listOf(word.width / 2), standsAt(band(word.width / 2 to 1f), word))
        assertEquals("one whole copy", 1, standsAt(band(5 to 1f), word).size)
        assertEquals("a copy at half strength is not the word", 0, standsAt(band(5 to 0.5f), word).size)
        assertEquals("a whole copy with a half-strength ghost over it is not the word", 0, standsAt(band(5 to 1f, 9 to 0.5f), word).size)
        assertEquals("two whole copies are two places", 2, standsAt(band(0 to 1f, word.width + 10 to 1f), word).size)
        assertEquals("nothing", 0, standsAt(band(), word).size)
    }

    @androidx.compose.runtime.Composable
    private fun HintInk(words: String) {
        val hero = LocalMimirPalette.current.materials.hero
        MimirChromeText(
            text = words,
            style = MimirTypeScale.ControlHintCompact,
            color = hero.onCover.copy(alpha = 0.60f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    private companion object {
        const val Stage = "tome_footer_stage"

        /** A pixel brighter than this is ink on the black ground. */
        const val InkFloor = 10f

        /** Mean grey-level difference, over a word's whole box, under which the frame holds that word and nothing in front of or over it. */
        const val MatchTolerance = 2.5f

        /** Out + glide + in is three 180 ms steps; the frames run a little past it. */
        const val SwapBudgetMs = 3 * 180L + 160L
    }
}
