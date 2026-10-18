package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlin.math.abs
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
 * **Emulator re-look vqa18, 2026-10-14 (P3):** at a 1.3 font the Files detail panel with a ZIP selected cut "Where: Internal Storage › Downl..." and "Launches: Nothing yet — extract it f..." on one line
 * each, and its Archive chip was a filled ember pill. The real panel ([FileBrowserInspectorPanelForCapture], the surface's own composable) in the 270 dp column the Files surface gives it, in a host that
 * gives it that width and nothing else, at the font scales 1.0 and 1.3, for an archive, an image and a deep folder. The PNGs land in `MIMIR_RENDER_DIR` (default `build/test-captures/tfp`); the pixels are the JVM's.
 *
 * What is asserted is read off the drawn nodes: each field value stands whole (wrapped at its words, no line ending inside a word, nothing cut by the engine) or, for a path too deep for its lines, keeps
 * its volume and its last folder; and every chip's inside is the panel's own ground, never a fill (no filled or tinted chips).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserInspectorFitCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val viewportWidthDp = 832.8f
    private val viewportHeightDp = 468.3f
    private val rootPath = "/storage/emulated/0"
    private val crumbLabels = mapOf(rootPath to "Internal Storage")

    private fun tile(name: String, kind: FileBrowserTileKind, platformIdValue: String? = null, directory: Boolean = false) = FileBrowserTile(
        item = FileItem(
            name = name,
            path = "$rootPath/Download/$name",
            isDirectory = directory,
            size = 141L,
            extension = name.substringAfterLast('.', ""),
            lastModified = 1_791_940_000_000L,
        ),
        kind = kind,
        machinery = false,
        platformIdValue = platformIdValue,
    )

    private val zip = tile("vqa18.zip", FileBrowserTileKind.ARCHIVE)
    private val image = tile("logo_1p78.png", FileBrowserTileKind.FILE)
    private val folder = tile("Hacks", FileBrowserTileKind.DIRECTORY, directory = true)
    private val platformFolder = tile("GBA", FileBrowserTileKind.DIRECTORY, platformIdValue = "game-boy-advance", directory = true)

    private var groundArgb = 0

    private fun mount(fontScale: Float, tile: FileBrowserTile, currentPath: String) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, fontScale)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    groundArgb = palette.surfaceBase.toArgb()
                    Box(Modifier.requiredSize(viewportWidthDp.dp, viewportHeightDp.dp).background(palette.surfaceBase)) {
                        // The column the Files surface gives the panel: FileBrowserInspectorWidthDp wide, at the right of the content.
                        FileBrowserInspectorPanelForCapture(
                            tile = tile,
                            currentPath = currentPath,
                            rootPath = rootPath,
                            crumbLabels = crumbLabels,
                            palette = palette,
                            modifier = Modifier.padding(start = (viewportWidthDp - FileBrowserInspectorWidthDp - 24f).dp, top = 24.dp).width(FileBrowserInspectorWidthDp.dp),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private fun save(fileName: String): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/tfp")
        if (!dir.isDirectory) dir.mkdirs()
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("INSPECTORFIT wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
        return bitmap
    }

    private fun clean(text: String) = text.replace("\u200B", "").replace("\u00AD", "").replace('\u00A0', ' ')

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    /** The value node of a field row: its text, or its spoken name, is [value] (a path set in the panel is spoken whole whatever it shows). */
    private fun valueNode(value: String): SemanticsNode = nodes(
        SemanticsMatcher("value is $value") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == value } == true ||
                (node.config.getOrNull(SemanticsProperties.ContentDescription) == null && node.config.getOrNull(SemanticsProperties.Text)?.any { clean(it.text) == value } == true)
        },
    ).firstOrNull { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null } ?: error("the value '$value' is not drawn")

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertTrue("the node reports its layout", layouts.isNotEmpty())
        return layouts.first()
    }

    private fun lines(layout: TextLayoutResult) = (0 until layout.lineCount).map {
        clean(layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it))).trim()
    }

    /** Whether any line but the last ends inside a word: a split between two characters that are not separated by a space. */
    private fun splitsAWord(layout: TextLayoutResult): Boolean {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount - 1).any { line ->
            val next = layout.getLineStart(line + 1)
            next in 1 until text.length && !text[next - 1].isWhitespace() && !text[next].isWhitespace() && text[next - 1] != '\u200B' && text[next - 1] != '\u00AD'
        }
    }

    /** A value that stands whole: all of it, in words, with nothing cut by the engine and no line ending inside a word. */
    private fun assertWhole(where: String, value: String) {
        val layout = layoutOf(valueNode(value))
        println("INSPECTORFIT $where '$value' lines=${lines(layout)}")
        assertEquals("$where: '$value' is drawn whole", value, clean(layout.layoutInput.text.text))
        assertFalse("$where: '$value' is not cut by the engine", (0 until layout.lineCount).any(layout::isLineEllipsized) || layout.didOverflowHeight)
        assertFalse("$where: no line of '$value' ends inside a word (${lines(layout)})", splitsAWord(layout))
        assertTrue("$where: '$value' takes at most $FileBrowserInspectorValueLines lines", layout.lineCount <= FileBrowserInspectorValueLines)
    }

    private fun near(a: Int, b: Int) = abs(AndroidColor.red(a) - AndroidColor.red(b)) <= 8 && abs(AndroidColor.green(a) - AndroidColor.green(b)) <= 8 && abs(AndroidColor.blue(a) - AndroidColor.blue(b)) <= 8

    /** A chip is an outline: inside its padding, left of its word, the pixel is the panel's own ground. */
    private fun assertChipNotFilled(where: String, bitmap: Bitmap, word: String) {
        val density = compose.activity.resources.displayMetrics.density
        val node = nodes(SemanticsMatcher("chip $word") { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == word } == true }).firstOrNull()
            ?: error("$where: the chip '$word' is not drawn")
        val y = node.boundsInRoot.center.y.toInt()
        val inside = bitmap.getPixel((node.boundsInRoot.left - 5f * density).toInt(), y)
        println("INSPECTORFIT $where chip '$word' inside=#${Integer.toHexString(inside)} ground=#${Integer.toHexString(groundArgb)}")
        assertTrue("$where: the '$word' chip's inside (#${Integer.toHexString(inside)}) is the panel's ground (#${Integer.toHexString(groundArgb)}), not a fill", near(inside, groundArgb))
    }

    @Test
    fun `an archive at 1_3x shows its whole path and its whole Launches line, and its Archive chip is an outline`() {
        mount(1.3f, zip, "$rootPath/Download")
        val bitmap = save("detail-zip-1.3.png")
        assertWhole("zip 1.3", "Internal Storage › Download")
        assertWhole("zip 1.3", "Nothing yet — extract it first")
        assertChipNotFilled("zip 1.3", bitmap, "Archive")
    }

    @Test
    fun `an image at 1_3x shows its whole path and its Launches line, and its Media art chip is an outline`() {
        mount(1.3f, image, "$rootPath/Download")
        val bitmap = save("detail-image-1.3.png")
        assertWhole("image 1.3", "Internal Storage › Download")
        assertWhole("image 1.3", "Not game content")
        assertChipNotFilled("image 1.3", bitmap, "Media art")
    }

    @Test
    fun `at 1x the panel is as it was, whole on the first line`() {
        mount(1.0f, zip, "$rootPath/Download")
        val bitmap = save("detail-zip-1.0.png")
        assertWhole("zip 1.0", "Internal Storage › Download")
        assertWhole("zip 1.0", "Nothing yet — extract it first")
        assertEquals("a value that fits stays on one line", 1, layoutOf(valueNode("Internal Storage › Download")).lineCount)
        assertChipNotFilled("zip 1.0", bitmap, "Archive")
    }

    @Test
    fun `a deep path at 1_3x gives up its middle folders and keeps its volume and its last folder`() {
        val deep = "$rootPath/Roms/Nintendo DS/Hacks and Homebrew/Translations/Downloads"
        val full = "Internal Storage › Roms › Nintendo DS › Hacks and Homebrew › Translations › Downloads"
        mount(1.3f, zip, deep)
        save("detail-deep-1.3.png")
        val layout = layoutOf(valueNode(full))
        val drawn = clean(layout.layoutInput.text.text)
        println("INSPECTORFIT deep 1.3 lines=${lines(layout)}")
        assertTrue("the volume stays first: $drawn", drawn.startsWith("Internal Storage › "))
        assertTrue("the last folder stays readable: $drawn", drawn.endsWith("Downloads"))
        assertTrue("the middle is given up with an ellipsis: $drawn", drawn.contains(" › … › "))
        assertFalse("nothing is cut by the engine", (0 until layout.lineCount).any(layout::isLineEllipsized) || layout.didOverflowHeight)
        assertFalse("no line ends inside a word (${lines(layout)})", splitsAWord(layout))
        assertTrue("it takes at most $FileBrowserInspectorValueLines lines", layout.lineCount <= FileBrowserInspectorValueLines)
    }

    private fun chipCase(tile: FileBrowserTile, word: String) {
        mount(1.3f, tile, "$rootPath/Download")
        assertChipNotFilled("chip $word", save("detail-chip-${word.replace(' ', '-')}-1.3.png"), word)
    }

    @Test
    fun `the Folder chip is an outline`() = chipCase(folder, "Folder")

    @Test
    fun `the Platform tome chip is an outline, not a green tint`() = chipCase(platformFolder, "Platform tome")
}
