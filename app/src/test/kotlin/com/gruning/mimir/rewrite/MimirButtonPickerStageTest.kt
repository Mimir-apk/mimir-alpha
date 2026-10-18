package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.companionnative.isMimirButtonMarkResident
import com.gruning.mimir.rewrite.core.companionnative.warmMimirButtonMark
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Mimir-button picker's stage with the real button on it (concept A, brief §D "Preview" and §G.3): the stage draws the native overlay's
 * own coin at its three seats from the same view the live button is built from, and follows the previewed mark as a PARAMETER -- the rune
 * for none, the player's mask for a mark -- without anything reaching the overlay.
 *
 * **The coins are pixels, not views (audit 2026-10-14, RC-13).** The host records the picker into a graphics layer and keeps the recording
 * on screen while it dissolves; a native view in that recording is drawn from its own display list, which is discarded the moment the view
 * leaves composition, so the three coins went blank at the start of the exit under an intact tile row. A bitmap in the recording is pixels
 * the recording owns. What this proves: no native coin view is ever hosted, each of the three seats draws a coin on every frame it is
 * composed -- the rune's, then the previewed mask's -- and a mask that still has to decode is warmed off the calling thread first. The
 * disappearance itself needs the host's recorded layer, which Robolectric cannot draw: that proof is the emulator sweep's (E5, scale 5).
 * What the coin paints is pinned by [CompanionButtonMarkDrawTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirButtonPickerStageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("stage", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun mask(name: String): MimirButtonMark {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 256) for (x in 0 until 128) setPixel(x, y, Color.WHITE)
        }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return MimirButtonMark(id = name, path = file.absolutePath)
    }

    /** Every native view of a coin's class under the window: there must be none. */
    private fun nativeCoins(): List<View> {
        val found = mutableListOf<View>()
        fun walk(view: View) {
            if (view.javaClass.simpleName == "CompanionMenuCoinView") found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(compose.activity.window.decorView)
        return found
    }

    /** The window in software, cropped to a coin's bounds. */
    private fun coinPixels(index: Int): Bitmap {
        val node = compose.onAllNodesWithTag(MimirButtonStageCoinTag).get(index)
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val view = compose.activity.window.decorView
        val window = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(window))
        return Bitmap.createBitmap(
            window,
            bounds.left.toInt().coerceAtLeast(0),
            bounds.top.toInt().coerceAtLeast(0),
            bounds.width.toInt().coerceAtLeast(1),
            bounds.height.toInt().coerceAtLeast(1),
        )
    }

    /** Pixels that differ from the stage ground at the coin's corner: the disc, its rim and its mark. */
    private fun inked(bitmap: Bitmap): Int {
        val ground = bitmap.getPixel(0, 0)
        var count = 0
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) if (bitmap.getPixel(x, y) != ground) count++
        return count
    }

    private fun mount(preview: androidx.compose.runtime.MutableState<MimirButtonMark?>) {
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(600.dp, 320.dp)) { MimirButtonPickerLiveStage(preview.value) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the stage draws the real button at three seats and hosts no native view`() {
        val preview = mutableStateOf<MimirButtonMark?>(null)
        mount(preview)

        assertEquals("the button at 56 dp, its 2x copy and the copy on the light panel", 3, compose.onAllNodesWithTag(MimirButtonStageCoinTag).fetchSemanticsNodes().size)
        assertTrue(
            "no native coin is ever hosted: its display list would be discarded under a dissolving picker's recording",
            nativeCoins().isEmpty(),
        )
        for (seat in 0 until 3) {
            assertTrue("seat $seat draws a coin on its first settled frame", inked(coinPixels(seat)) > 20)
        }
        val actual = coinPixels(0)
        val detail = coinPixels(1)
        assertEquals("the 2x copy is twice the size", actual.width * 2, detail.width)
    }

    @Test
    fun `the stage follows the previewed mark, and the rune comes back with it`() {
        val preview = mutableStateOf<MimirButtonMark?>(null)
        mount(preview)
        val rune = (0 until 3).map { coinPixels(it) }

        Snapshot.withMutableSnapshot { preview.value = mask("aaaaaaaa") }
        // The mask is warmed off the calling thread first and the rune's coin stands until the new one cross-fades in.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(MimirButtonStageCoinTag).fetchSemanticsNodes().size == 3 && !sameRegion(coinPixels(0), rune[0])
        }
        // A custom mark is a COLD arrival (Q13): it rises from nothing on FocusMs once its mask has decoded, so the seats are waited for until all three wear it whole.
        compose.waitUntil(timeoutMillis = 10_000) { (0 until 3).all { inked(coinPixels(it)) > 20 && !sameRegion(coinPixels(it), rune[it]) } }
        for (seat in 0 until 3) {
            assertTrue("seat $seat draws the mark", inked(coinPixels(seat)) > 20)
            assertFalse("and it is not the rune any more", sameRegion(coinPixels(seat), rune[seat]))
        }

        Snapshot.withMutableSnapshot { preview.value = null }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(MimirButtonStageCoinTag).fetchSemanticsNodes().size == 3 && (0 until 3).all { sameRegion(coinPixels(it), rune[it]) }
        }
        for (seat in 0 until 3) assertTrue("the rune again at seat $seat", sameRegion(coinPixels(seat), rune[seat]))
        assertTrue(nativeCoins().isEmpty())
    }

    @Test
    fun `a mask is warmed off the calling thread and is resident only afterwards`() {
        val mark = mask("bbbbbbbb")
        assertFalse("nothing decoded yet", isMimirButtonMarkResident(mark.path))
        assertTrue(runBlocking { warmMimirButtonMark(mark.path) })
        assertTrue("and now a coin built from it decodes nothing on the calling thread", isMimirButtonMarkResident(mark.path))
        val gone = File(dir, "gone.png").absolutePath
        assertFalse("a missing file is not warmed", runBlocking { warmMimirButtonMark(gone) })
        assertFalse(isMimirButtonMarkResident(gone))
    }

    /**
     * A coin struck once is kept (audit W1A #7): the software draw of a coin, a View built, measured and drawn onto a canvas on the composition thread, is cached by (theme, scale,
     * size), so a preview moved across tiles it has already shown, and back, draws nothing. The proof is identity: a coin the cache holds after the round trip is the very bitmap it
     * held before, and not a second one struck in its place.
     */
    @Test
    fun `a coin struck once is kept, and the preview coming back to it strikes nothing`() {
        MimirButtonCoinCache.clear()
        val preview = mutableStateOf<MimirButtonMark?>(null)
        mount(preview)
        val rune = MimirButtonCoinCache.held()
        // The 56 dp seat on the stage ground and the one on the light panel are the same (theme, scale, size) and share one struck coin; the 2x copy is the other.
        assertEquals("one struck coin per distinct (theme, scale, size)", 2, rune.size)
        val runeLook = (0 until 3).map { coinPixels(it) }

        Snapshot.withMutableSnapshot { preview.value = mask("cccccccc") }
        compose.waitUntil(timeoutMillis = 10_000) { (0 until 3).all { inked(coinPixels(it)) > 20 && !sameRegion(coinPixels(it), runeLook[it]) } }
        assertEquals("the mask's two coins are struck and kept beside the rune's", 4, MimirButtonCoinCache.held().size)

        Snapshot.withMutableSnapshot { preview.value = null }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(MimirButtonStageCoinTag).fetchSemanticsNodes().size == 3 && (0 until 3).all { sameRegion(coinPixels(it), runeLook[it]) }
        }
        val again = MimirButtonCoinCache.held()
        for ((key, coin) in rune) assertSame("the rune's coin at ${key.scale}x was kept, not struck again", coin, again[key])
        assertEquals("and nothing new was struck for the round trip", 4, again.size)
        for (seat in 0 until 3) assertTrue("the rune is drawn at seat $seat", inked(coinPixels(seat)) > 20)
    }

    /** How far the coin's pixels stand from the ground at its corner, summed over a sample: grows with the coin's alpha. */
    private fun strength(bitmap: Bitmap): Float {
        val ground = bitmap.getPixel(0, 0)
        fun luma(p: Int) = 0.2126f * (p shr 16 and 0xFF) + 0.7152f * (p shr 8 and 0xFF) + 0.0722f * (p and 0xFF)
        var sum = 0f
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) sum += abs(luma(bitmap.getPixel(x, y)) - luma(ground))
        return sum
    }

    /**
     * Q13 (audit W1A #7): a custom mark's coin that is still to decode is a COLD arrival. It draws nothing while its mask is warmed off the calling thread, and then rises from nothing on
     * the product's one cold-art clock; it used to pop in at full strength the frame it was struck. A coin the cache already holds (the rune) is whole on its first frame.
     */
    @Test
    fun `a custom mark that has to decode rises from nothing instead of popping in, and a held coin is whole at once`() {
        MimirButtonCoinCache.clear()
        val mark = mask("dddddddd")
        assertFalse("the mask has not been decoded", isMimirButtonMarkResident(mark.path))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                val palette = LocalMimirPalette.current
                val theme = remember(palette) { companionOverlayTheme(palette.id, mark) }
                Box(Modifier.size(80.dp)) { MimirButtonCoinSnapshot(theme = theme, markPath = mark.path, scale = 1f) }
            }
        }
        fun step(ms: Long) {
            if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
        }
        step(0L)
        assertEquals("nothing is drawn on the first frame of a cold coin: no placeholder", 0f, strength(coinPixels(0)), 0f)

        // The mask is warmed on another thread; frames go by, and the coin appears from nothing.
        val strengths = mutableListOf<Float>()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && (strengths.isEmpty() || strengths.size < MimirMotion.FocusMs / 16 + 8)) {
            step(16L)
            val now = strength(coinPixels(0))
            if (strengths.isNotEmpty() || now > 0f) strengths += now
            Thread.sleep(10)
        }
        println("MIMIR-BUTTON-STAGE cold coin strength per 16 ms: ${strengths.map { "%.0f".format(it) }}")
        val whole = strengths.last()
        assertTrue("the coin ends up drawn: $strengths", whole > 0f)
        assertTrue("it rises from nothing, not at full strength: first ${strengths.first()} of $whole", strengths.first() < whole * 0.6f)
        assertTrue("it passes through intermediate strengths, a fade and not a cut: $strengths", strengths.count { it > whole * 0.05f && it < whole * 0.95f } >= 2)
        assertTrue("it never goes backwards: $strengths", strengths.zipWithNext().all { (a, b) -> b >= a - 1f })
    }

    private fun sameRegion(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) return false
        return true
    }

    @Test
    fun `the stage is built from the preview view drawn into a bitmap, never hosted, and the mask is warmed off-thread first`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirButtonPickerStage.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirButtonPickerStage.kt"),
        ).first(File::exists).readText()
        val browse = source.substringBefore("internal fun MimirButtonPickerAdjustCoin(")
        assertFalse("the browse stage hosts no AndroidView", browse.contains("AndroidView("))
        assertTrue(browse.contains("view.draw(Canvas(bitmap))"))
        assertTrue(browse.contains("warmMimirButtonMark(it)"))
        assertTrue("one already resident is struck in the composition's first frame", browse.contains("isMimirButtonMarkResident(markPath)"))
    }
}
