package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.companionnative.CompanionButtonMark
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.companionnative.createMimirButtonPreviewView
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerMark
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerScope
import com.gruning.mimir.rewrite.feature.settings.dispatchMimirButtonPicker
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonTileKey
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The adjust footer's two legends (`MimirButtonControlHintAdjusting` and `...AdjustingShort`, `internal` to `:feature-settings`). */
private const val WholeAdjustHint = "L1 R1 SIZE · A SAVE · X RESET · B CANCEL"
private const val ShortAdjustHint = "L1 R1 SIZE · A SAVE · B CANCEL"

/**
 * The Mimir-button picker in ADJUST mode as pixels (size and place your own image), drawn the way this repo rasterises (the
 * view hierarchy into a software bitmap) with the real fonts and the REAL native coins on the stage. The PNGs land in `build/test-captures/markhint/`
 * when that folder exists, and what each must show is read off them: the rings hug, nothing is cut, no filled button, the words fit whole at
 * font 1.3, and the adjust footer names the size keys whole on one line (the whole legend where it fits, the shorter one where it does not).
 *
 * Two screens: the Thor's Main (1920 x 1080 px at 369 dpi = 832 x 468 dp) and the 360 dp square the RG Rotate is, each at font 1.0 and 1.3, in
 * adjust mode and in the tiles (where the footer names Y ADJUST). The masks are synthetic (a triangle, a bar, a disc): none is the owner's.
 *
 * What a render cannot prove, this asserts from the nodes: every chip sits inside the screen, none overlaps another, and each is one 48 dp line.
 */
abstract class MimirButtonAdjustRenderBase {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var dir: File
    private val authority = DefaultFocusAuthority()
    private var state by mutableStateOf(mimirButtonPickerInitialState(null, emptyList()))
    private var fontScale by mutableStateOf(1f)

    @Before
    fun setUpMasks() {
        dir = File.createTempFile("adjustrender", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDownMasks() {
        dir.deleteRecursively()
    }

    // ---- synthetic masks ------------------------------------------------------------------------------------------------------

    private fun write(name: String, draw: (Canvas, Paint) -> Unit): String {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true })
        val file = File(dir, "$name.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file.absolutePath
    }

    private fun triangle(name: String) = write(name) { canvas, paint ->
        canvas.drawPath(Path().apply { moveTo(128f, 8f); lineTo(248f, 248f); lineTo(8f, 248f); close() }, paint)
    }

    private fun bar(name: String) = write(name) { canvas, paint -> canvas.drawRoundRect(8f, 96f, 248f, 160f, 24f, 24f, paint) }

    private fun disc(name: String) = write(name) { canvas, paint -> canvas.drawCircle(128f, 128f, 120f, paint) }

    // ---- the picker -----------------------------------------------------------------------------------------------------------

    private fun open(): List<MimirButtonPickerMark> {
        val marks = listOf(
            MimirButtonPickerMark("aaaaaaaa", "Your mark 1", triangle("aaaaaaaa"), MimirMarkPlacement(0.8f, 3f, 4f)),
            MimirButtonPickerMark("bbbbbbbb", "Your mark 2", bar("bbbbbbbb")),
            MimirButtonPickerMark("cccccccc", "Your mark 3", disc("cccccccc"), MimirMarkPlacement(0.7f, -6f, -5f)),
        )
        state = mimirButtonPickerInitialState(null, marks)
        authority.pushScope(MimirButtonPickerScope)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MimirTheme {
                    MimirButtonPickerOverlay(
                        authority = authority,
                        state = state,
                        onFocus = { key -> state = mimirButtonPickerOnFocus(state, key) },
                        onTransition = { transition -> state = transition.state },
                        presenceManagedExternally = true,
                        stage = { preview -> MimirButtonPickerLiveStage(preview) },
                        adjustCoin = { mark, scale -> MimirButtonPickerAdjustCoin(mark, scale) },
                    )
                }
            }
        }
        frames()
        compose.runOnIdle { authority.requestFocus(mimirButtonTileKey(marks[0].id)) }
        frames(4)
        return marks
    }

    private fun frames(count: Int = 8) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    private fun press(action: ControllerAction, times: Int = 1) {
        repeat(times) {
            compose.runOnIdle { dispatchMimirButtonPicker(authority, state, action) { state = it.state } }
        }
        frames(3)
    }

    private fun frame(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        return full
    }

    private fun save(frame: Bitmap, name: String) {
        val folder = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/markhint")
        if (!folder.isDirectory) return
        File(folder, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The footer band alone, 3x, so the legend can be read word by word off the PNG. */
    private fun footerCrop(frame: Bitmap, shown: String): Bitmap {
        val b = compose.onNode(androidx.compose.ui.test.hasContentDescription(shown), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pad = (10 * density).toInt()
        val top = (b.top.toInt() - pad).coerceAtLeast(0)
        val bottom = (b.bottom.toInt() + pad).coerceAtMost(frame.height)
        val band = Bitmap.createBitmap(frame, 0, top, frame.width, bottom - top)
        return Bitmap.createScaledBitmap(band, band.width * 3, band.height * 3, true)
    }

    /**
     * The adjust footer on screen: the whole legend or its shorter fallback, and exactly one of them. Both are the picker's own words (the constants there are
     * `internal` to `:feature-settings`).
     */
    private fun footerShown(): String {
        val candidates = listOf(WholeAdjustHint, ShortAdjustHint)
        val shown = candidates.filter { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("exactly one adjust footer is on screen, found $shown", shown.size == 1)
        return shown.single()
    }

    protected abstract val screen: String

    private fun shoot(adjust: Boolean, scale: Float) {
        var footer: String? = null
        fontScale = scale
        open()
        if (adjust) {
            press(ControllerAction.TERTIARY)
            // The pad's own steps: lower it, shrink it, as a player would to place a Triforce.
            press(ControllerAction.NAV_DOWN, times = 4)
            press(ControllerAction.PAGE_LEFT, times = 2)
            assertChipsFit()
            // The footer names the size keys (): the whole legend where it fits on one line, else the shorter one,
            // which still leads with them. Whichever the picker chose stands whole inside the gutters, and the choice is the one this screen has always had.
            val shown = footerShown()
            footer = shown
            assertFooterFitsWhole(shown)
            assertTrue("the footer names the size keys: $shown", shown.startsWith("L1 R1 SIZE · A SAVE"))
            assertEquals("the footer on the $screen screen at font $scale", expectedFooter(scale), shown)
            assertTextWhole("D-pad moves it, L1 and R1 size it. The small button is the real one.", maxLines = 2, what = "the adjust subtitle")
            assertInStage(listOf("DRAG · PINCH", "ACTUAL SIZE · 56 DP"), "the adjust stage")
            assertInStage(null, "the work coin", tag = "mbutton-adjust-coin", room = 6f)
        } else {
            assertInStage(listOf("ACTUAL SIZE · 56 DP", "2× FOR DETAIL", "ON A BRIGHT GAME"), "the browse stage")
            assertTileNamesWhole(listOf("ᛗ Rune", "Your mark 1", "Your mark 2", "Your mark 3"))
            assertTextWhole("The M you hold for Mimir’s menu", maxLines = 2, what = "the picker subtitle")
        }
        assertNoCutText()
        frames(4)
        val shot = frame()
        assertStageEdgesClear(shot, if (adjust) "the adjust stage" else "the browse stage")
        save(shot, "picker-${if (adjust) "adjust" else "tiles"}-$screen-font$scale.png")
        footer?.let { save(footerCrop(shot, it), "footer-adjust-$screen-font$scale.png") }
    }

    /** What the adjust footer reads on this screen at [scale], pinned off the renders (read by eye, 2026-10-13). */
    protected abstract fun expectedFooter(scale: Float): String

    private val density get() = compose.activity.resources.displayMetrics.density

    /** The footer's whole hint stands on ONE line inside the page's 16 dp gutters: the footer is `maxLines = 1`, so a hint too wide for it would lose its last words in silence. */
    private fun assertFooterFitsWhole(hint: String) {
        val b = compose.onNode(androidx.compose.ui.test.hasContentDescription(hint), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.activity.window.decorView.width.toFloat()
        val gutter = 16f * density
        assertTrue("the hint ($b) stands inside the ${root / density} dp screen with room left either side of it", b.left >= gutter + 3f * density && b.right <= root - gutter - 3f * density)
        assertTrue("and it is one line: ${b.height / density} dp", b.height / density < 24f * fontScale)
    }

    /**
     * Each of [captions] (or the node [tag]) lies inside the stage band, [room] dp from its edges: nothing the stage holds is cut by the stage's own clip. A caption
     * that wraps is one node whose box is the whole of it.
     */
    private fun assertInStage(captions: List<String>?, what: String, tag: String? = null, room: Float = 1f) {
        val stage = compose.onNodeWithTag("appearance-picker-stage", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val inner = androidx.compose.ui.geometry.Rect(stage.left + room * density, stage.top + room * density, stage.right - room * density, stage.bottom - room * density)
        val nodes = captions?.map { it to compose.onNode(androidx.compose.ui.test.hasContentDescription(it), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
            ?: listOf((tag ?: "") to compose.onNodeWithTag(tag!!, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        for ((name, b) in nodes) {
            assertTrue("$what: \"$name\" $b inside the stage $stage", b.left >= inner.left && b.top >= inner.top && b.right <= inner.right && b.bottom <= inner.bottom)
        }
    }

    /**
     * Nothing the stage holds is cut by the stage's own clip. A clipped caption or coin still reports bounds inside the band (a layout hands it only the room it has),
     * so this reads the PIXELS: a strip three pixels deep just inside the band's border, all four sides but the rounded corners, holds no ink as bright as the captions.
     */
    private fun assertStageEdgesClear(frame: Bitmap, what: String) {
        val stage = compose.onNodeWithTag("appearance-picker-stage", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val corner = (14 * density).toInt()
        val border = density.toInt() + 1
        fun bright(x: Int, y: Int): Boolean {
            val c = frame.getPixel(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
            return 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF) > 140.0
        }
        var hits = 0
        for (depth in 0 until 3) {
            for (x in (stage.left.toInt() + corner) until (stage.right.toInt() - corner)) {
                if (bright(x, stage.top.toInt() + border + depth)) hits++
                if (bright(x, stage.bottom.toInt() - border - 1 - depth)) hits++
            }
            for (y in (stage.top.toInt() + corner) until (stage.bottom.toInt() - corner)) {
                if (bright(stage.left.toInt() + border + depth, y)) hits++
                if (bright(stage.right.toInt() - border - 1 - depth, y)) hits++
            }
        }
        assertTrue("$what: ink runs into the stage's edge ($hits bright pixels in its 3 px rim; a few stars twinkle there, a clipped caption is dozens)", hits < 16)
    }

    /** A tile's name is whole (it wrapped at its spaces, it was not cut), at the font and the width this screen gives it. */
    private fun assertTileNamesWhole(names: List<String>) {
        for (name in names) {
            val nodes = compose.onAllNodes(androidx.compose.ui.test.hasText(name), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("the tile name \"$name\" stands whole", nodes.isNotEmpty())
            assertTextWhole(name, maxLines = 2, what = "the tile name")
        }
    }

    /** [text], as the Text composable that shows it laid it out: within [maxLines], not ellipsized or clipped, and no line ending inside a word. */
    private fun assertTextWhole(text: String, maxLines: Int, what: String) {
        val node = compose.onNode(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val asked = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) ?: false
        assertTrue("$what: the node reports its layout", asked && results.isNotEmpty())
        val layout = results.first()
        assertTrue("$what: ${layout.lineCount} lines, at most $maxLines", layout.lineCount <= maxLines)
        assertTrue(
            "$what: ellipsized or clipped (lines ${layout.lineCount}, overflowHeight ${layout.didOverflowHeight}, overflowWidth ${layout.didOverflowWidth}, size ${layout.size}, " +
                "multi ${layout.multiParagraph.height}, node ${node.size}, ellipsized ${(0 until layout.lineCount).map { layout.isLineEllipsized(it) }})",
            !layout.didOverflowHeight && (0 until layout.lineCount).none { layout.isLineEllipsized(it) },
        )
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            assertTrue("$what: line ${line + 1} ends inside a word at $end of \"$text\"", end >= text.length || text[end - 1].isWhitespace() || text[end].isWhitespace())
        }
    }

    /** No text anywhere on screen is shortened with an ellipsis: what does not fit wraps, or is left off whole. */
    private fun assertNoCutText() {
        val cut = compose.onAllNodes(androidx.compose.ui.test.hasText("…", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("text cut with an ellipsis: ${cut.map { it.config }}", cut.isEmpty())
    }

    /** The four chips are inside the screen, each a single 48 dp line, and none overlaps its neighbour. */
    private fun assertChipsFit() {
        val density = compose.activity.resources.displayMetrics.density
        val root = compose.activity.window.decorView
        val bounds = listOf("Smaller", "Larger", "Reset", "Done").map { label ->
            val node = compose.onNodeWithTag("mbutton-adjust-chip-$label").fetchSemanticsNode()
            label to node.boundsInRoot
        }
        for ((label, b) in bounds) {
            assertTrue("$label $b inside the ${root.width} px screen", b.left >= 0f && b.right <= root.width.toFloat())
            assertTrue("$label is a 48 dp target, one line: ${b.height / density} dp", b.height / density <= 48.5f)
        }
        bounds.zipWithNext().forEach { (a, b) -> assertTrue("${a.first} and ${b.first} do not overlap", a.second.right <= b.second.left + 0.5f) }
    }

    @Test
    fun adjustAtFont1() = shoot(adjust = true, scale = 1.0f)

    @Test
    fun adjustAtFont13() = shoot(adjust = true, scale = 1.3f)

    /**
     * The fallback, on the screen where the whole legend stops fitting: at the largest font the system offers, the 360 dp square's footer cannot hold "L1 R1 SIZE · A SAVE ·
     * X RESET · B CANCEL" on one line, so it reads "L1 R1 SIZE · A SAVE · B CANCEL" -- whole words, one line, the size keys kept. (The Thor's footer holds the whole one at any font.)
     */
    @Test
    fun adjustFooterAtFont20() {
        fontScale = 2.0f
        open()
        press(ControllerAction.TERTIARY)
        val shown = footerShown()
        assertFooterFitsWhole(shown)
        assertEquals("the footer on the $screen screen at font 2.0", expectedFooter(2.0f), shown)
        frames(4)
        val shot = frame()
        save(shot, "picker-adjust-$screen-font2.0.png")
        save(footerCrop(shot, shown), "footer-adjust-$screen-font2.0.png")
    }

    @Test
    fun tilesAtFont1() = shoot(adjust = false, scale = 1.0f)

    @Test
    fun tilesAtFont13() = shoot(adjust = false, scale = 1.3f)
}

/** The Thor's Main: 1920 x 1080 px at 369 dpi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirButtonAdjustThorRenderTest : MimirButtonAdjustRenderBase() {
    override val screen = "thor"
    override fun expectedFooter(scale: Float) = WholeAdjustHint
}

/** The compact square: 360 x 360 dp, the RG Rotate's 720 px at 320 dpi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h360dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirButtonAdjustCompactRenderTest : MimirButtonAdjustRenderBase() {
    override val screen = "compact"
    override fun expectedFooter(scale: Float) = if (scale > 1.5f) ShortAdjustHint else WholeAdjustHint
}

/**
 * The real button at three placements, a triangle for a mask : centred at 100%, lowered and smaller, raised and larger. Each coin
 * is the native overlay's own view at its real 56 dp, drawn into a bitmap and magnified 6x for the PNG so the placement can be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirButtonCoinPlacementRenderTest {

    @Test
    fun coinAtThreePlacements() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val dir = File.createTempFile("coinplace", "dir").apply { delete(); mkdirs() }
        try {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPath(
                Path().apply { moveTo(128f, 8f); lineTo(248f, 248f); lineTo(8f, 248f); close() },
                Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true },
            )
            val file = File(dir, "triangle.png").also { f -> f.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            val placements = listOf(
                MimirMarkPlacement.Default,
                MimirMarkPlacement(scale = 0.75f, offsetX = 0f, offsetY = 7f),
                MimirMarkPlacement(scale = 1.25f, offsetX = 0f, offsetY = -6f),
            )
            val magnify = 6
            val edge = (56 * context.resources.displayMetrics.density).toInt()
            val gap = 12
            val strip = Bitmap.createBitmap(placements.size * edge * magnify + (placements.size + 1) * gap, edge * magnify + 2 * gap, Bitmap.Config.ARGB_8888)
            val stripCanvas = Canvas(strip)
            stripCanvas.drawColor(Color.rgb(24, 26, 32))
            placements.forEachIndexed { index, placement ->
                val theme: CompanionOverlayTheme = companionOverlayTheme(MimirThemeId.DEFAULT, buttonMark = null)
                    .copy(mark = CompanionButtonMark.Image(id = "tri$index", path = file.absolutePath, placement = placement))
                val view = createMimirButtonPreviewView(context, theme)
                view.measure(View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, edge, edge)
                val coin = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
                val big = Bitmap.createScaledBitmap(coin, edge * magnify, edge * magnify, true)
                stripCanvas.drawBitmap(big, (gap + index * (edge * magnify + gap)).toFloat(), gap.toFloat(), null)
            }
            val folder = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/markadjust/round2")
            if (folder.isDirectory) File(folder, "coin-three-placements.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(strip.width > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
