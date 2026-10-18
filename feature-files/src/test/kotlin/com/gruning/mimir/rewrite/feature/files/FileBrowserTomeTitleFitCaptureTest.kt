package com.gruning.mimir.rewrite.feature.files

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Alpha-cert sweep 2026-10-09 (P1):** at a 1.3x font the Files grid's folder tiles set "Audiobooks" as "Audiobook" / "s" and "Documents" as "Document" / "s", and the stray
 * letter landed on the embossed Mannaz. The real [TomeTile] (which passes `fitTitle` to the cover) in the Files grid's own geometry on the Thor's Main -- 832.8 x 468.3 dp at 369 dpi,
 * the four-column folio's cell -- at the font scales 1.0 and 1.3, with the long single words the sweep names beside short names and a platform folder. The PNGs land in
 * `build/test-captures/sweepa/`; the pixels are the JVM's, a handheld's are device-owed.
 *
 * What is asserted is read off the drawn text, not off the function that chose its size: no title's line ends inside a word, and the word is whole or the line ends in an ellipsis.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserTomeTitleFitCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val controller = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { emptyList() },
        onChoose = {},
        onDismiss = {},
    )

    private val viewportWidthDp = 832.8f
    private val viewportHeightDp = 468.3f
    private val grid = fileBrowserSolveGrid(viewportWidthDp, viewportHeightDp)

    private fun folder(name: String, platformIdValue: String? = null, machinery: Boolean = false) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = true, childCount = 3),
        kind = FileBrowserTileKind.DIRECTORY,
        machinery = machinery,
        platformIdValue = platformIdValue,
    )

    private val firstRow = listOf("Alarms", "Audiobooks", "Documents", "Screenshots").map { folder(it) }
    private val secondRow = listOf(
        folder("Notifications"),
        folder("DCIM"),
        folder("Super Nintendo ROMs"),
        folder("saves", machinery = true),
    )
    private val platformRow = listOf(
        folder("Screenshots", platformIdValue = "snes"),
        folder("Notifications", platformIdValue = "n64"),
        folder("Audiobooks", platformIdValue = "gba"),
        folder("Documents", platformIdValue = "psx"),
    )

    private fun mount(fontScale: Float, rows: List<List<FileBrowserTile>>) {
        // The window's own action bar would stand over the top 48 dp of the frame: the app's screen has none.
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, fontScale)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(viewportWidthDp.dp, viewportHeightDp.dp).background(palette.surfaceBase)) {
                        Column(
                            Modifier
                                .padding(start = FileBrowserContentRowHorizontalPaddingDp.dp, top = 24.dp)
                                .width(((grid.tomeCellWidthDp + FileBrowserTomeColumnGapDp) * grid.tomeColumns - FileBrowserTomeColumnGapDp).dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            rows.forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(FileBrowserTomeColumnGapDp.dp)) {
                                    row.forEach { tile ->
                                        TomeTileForCapture(
                                            tile = tile,
                                            focused = false,
                                            authority = authority,
                                            key = FileBrowserFocus.tileKey(tile.path),
                                            scope = FileBrowserFocus.DefaultScope,
                                            controller = controller,
                                            palette = palette,
                                            modifier = Modifier.width(grid.tomeCellWidthDp.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private fun save(fileName: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/test-captures/sweepa")
        if (!dir.isDirectory) dir.mkdirs()
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("TOMEFIT wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    /** The drawn title's layout: the text the cover set, as the text engine laid it out. */
    private fun layoutOf(name: String, index: Int): TextLayoutResult {
        val nodes = compose.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes()
        val layouts = mutableListOf<TextLayoutResult>()
        nodes[index].config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.first()
    }

    /** The first line (not the last) that ends between two letters of one word: where the text engine split a word too wide for its line. -1 when none does. */
    private fun firstSplitLine(layout: TextLayoutResult): Int {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val next = layout.getLineStart(line + 1)
            if (next <= 0 || next >= text.length) continue
            val before = text[next - 1]
            val after = text[next]
            if (!(before.isWhitespace() || after.isWhitespace() || before == '-' || before == '/')) return line
        }
        return -1
    }

    private fun lines(layout: TextLayoutResult) = (0 until layout.lineCount).map {
        layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim()
    }

    private fun assertWholeWords(label: String, rows: List<List<FileBrowserTile>>) {
        for (tile in rows.flatten()) {
            val seen = rows.flatten().filter { it.name == tile.name }
            val index = seen.indexOf(tile)
            val layout = layoutOf(tile.name, index)
            val shown = lines(layout)
            println("TOMEFIT $label '${tile.name}' -> ${layout.layoutInput.style.fontSize.value}sp lines=$shown")
            assertEquals("$label '${tile.name}': no line ends inside a word ($shown)", -1, firstSplitLine(layout))
            val ellipsized = (0 until layout.lineCount).any(layout::isLineEllipsized)
            if (!ellipsized) assertEquals("$label '${tile.name}': the whole name stands", tile.name, shown.joinToString(" "))
        }
    }

    @Test
    fun `the folder tiles at 1x keep their names whole`() {
        mount(1.0f, listOf(firstRow, secondRow))
        save("files-grid-1.0x.png")
        assertWholeWords("1.0x", listOf(firstRow, secondRow))
    }

    @Test
    fun `the folder tiles at 1_3x never split a name, and the stray letter is gone from the Mannaz`() {
        mount(1.3f, listOf(firstRow, secondRow))
        save("files-grid-1.3x.png")
        assertWholeWords("1.3x", listOf(firstRow, secondRow))
    }

    @Test
    fun `platform folders at 1x keep their names whole above the platform plate`() {
        mount(1.0f, listOf(platformRow))
        save("files-grid-platform-1.0x.png")
        assertWholeWords("plate 1.0x", listOf(platformRow))
    }

    @Test
    fun `platform folders at 1_3x never split a name above the platform plate`() {
        mount(1.3f, listOf(platformRow))
        save("files-grid-platform-1.3x.png")
        assertWholeWords("plate 1.3x", listOf(platformRow))
    }
}
