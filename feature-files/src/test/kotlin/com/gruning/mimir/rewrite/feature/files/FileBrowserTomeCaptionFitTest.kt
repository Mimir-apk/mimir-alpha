package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Audit 2026-10-14, F-18 (RC-21's third finding): on the first screen of the Files root the second row of folder tiles had its child-count captions ("0 items") sliced by the
 * footer, at font 1.0 and at 1.3 (emulator, `files1`).
 *
 * Two arithmetic errors made it, and this reads both off the drawn nodes of the whole [FileBrowserSurface] at the three reference viewports: the Thor's Main (833 x 468 dp at
 * 369 dpi), the emulator's Main (853 x 480 dp) and its 960 x 540 dp window, each at font 1.0 and 1.3.
 *  - The caption was drawn on the ambient style's 24 sp line (a 10 sp word on a 24 sp line, 31 dp at 1.3) where the grid's row pitch ([FileBrowserTomeMetaHeightDp]) counts 17 dp, so
 *    every row was 7 dp (14 dp at 1.3) taller than the lattice, and the second row ended below the listing.
 *  - The content row's own 11 dp top padding stacked on the listing's 14 dp ring room (added 2026-09-28), 25 dp of dead ground above the first heading, which took the rest.
 *
 * The listing is clipped to its own bounds, so a node's `boundsInRoot` never reaches past it; the unclipped `positionInRoot` and `size` are what is compared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w1200dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserTomeCaptionFitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val names = listOf(
        "Alarms", "Android", "Audiobooks", "DCIM", "Documents", "Download", "FilesTest", "Movies",
        "Music", "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones", "Screenshots",
    )

    private fun captions(): List<SemanticsNode> = compose.onAllNodes(
        SemanticsMatcher("a folder tile's child count") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { Regex("""\d+ items?""").matches(it.text) } == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun case(where: String, widthDp: Float, heightDp: Float, density: Float, fontScale: Float) {
        val root = "/storage/emulated/0"
        val dirs = names.mapIndexed { i, name -> FileItem(name = name, path = "$root/$name", isDirectory = true, childCount = i % 4) }
        val authority = DefaultFocusAuthority()
        val controller = FileBrowserController(
            authority = authority,
            rootPath = root,
            mode = FileBrowserMode.MANAGE,
            listDirectory = { dirs },
            onChoose = {},
            onDismiss = {},
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        FileBrowserSurface(controller = controller, authority = authority, modifier = Modifier)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()

        val list = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        val listBottom = list.boundsInRoot.bottom
        // One caption per tile; a row is the captions within 40 dp of its first (tiles of one row differ by a pixel or two), and the first two rows are the ones the first screen is meant to show whole.
        // (The inspector beside the listing says "0 items" for the open folder too; only the listing's own captions are the tiles'.)
        val tileCaptions = captions().filter { it.positionInRoot.x >= list.boundsInRoot.left && it.positionInRoot.x < list.boundsInRoot.right }
        val rows = mutableListOf<MutableList<SemanticsNode>>()
        tileCaptions.sortedBy { it.positionInRoot.y }.forEach { node ->
            val current = rows.lastOrNull()
            if (current != null && node.positionInRoot.y - current.first().positionInRoot.y < 40f * density) current += node else rows += mutableListOf(node)
        }
        assertTrue("$where: the grid draws at least two rows of folder captions", rows.size >= 2)
        // The listing's first heading and the inspector's title start at the same y: the content row no longer pads above the listing, whose ring room is the heading's gap, and the
        // inspector takes that same number ([fileBrowserInspectorTopGap]). Equal to the pixel at both font scales, so neither can drift from the other.
        val texts = compose.onAllNodes(SemanticsMatcher("any text") { it.config.getOrNull(SemanticsProperties.Text) != null }, useUnmergedTree = true).fetchSemanticsNodes()
        val heading = texts.firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Folders") } == true }
            ?: error("$where: the listing's 'Folders' heading is not drawn")
        val inspectorTitle = texts.filter { it.positionInRoot.x >= list.boundsInRoot.right && it.positionInRoot.y >= list.boundsInRoot.top }.minByOrNull { it.positionInRoot.y }
            ?: error("$where: the inspector's title is not drawn")
        println("TOMEALIGN $where heading y=${heading.positionInRoot.y / density} dp, inspector title y=${inspectorTitle.positionInRoot.y / density} dp")
        assertEquals("$where: the inspector's title starts where the listing's heading does", heading.positionInRoot.y, inspectorTitle.positionInRoot.y, 0.5f)
        val captionPx = FileBrowserTomeMetaHeightDp * density
        rows.take(2).forEachIndexed { index, row ->
            row.forEach { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)?.first()?.text
                println("TOMECAPTION $where row ${index + 1} '$text' y=${node.positionInRoot.y / density} h=${node.size.height / density} listBottom=${listBottom / density}")
                // The caption is the line the grid counts (model and paint agree, whatever the font scale).
                assertEquals("$where row ${index + 1} '$text': the caption's line is the grid's ${FileBrowserTomeMetaHeightDp} dp", captionPx, node.size.height.toFloat(), 1f)
                assertTrue(
                    "$where row ${index + 1} '$text': the caption ends at ${(node.positionInRoot.y + node.size.height) / density} dp, past the listing's bottom at ${listBottom / density} dp",
                    node.positionInRoot.y + node.size.height <= listBottom + 0.5f,
                )
            }
        }
    }

    @Test
    fun `the Thor's Main at font 1 point 0`() = case("thor 1.0", 833f, 468f, 369f / 160f, 1.0f)

    @Test
    fun `the Thor's Main at font 1 point 3`() = case("thor 1.3", 833f, 468f, 369f / 160f, 1.3f)

    @Test
    fun `the emulator's Main at font 1 point 0`() = case("main 853x480 1.0", 853f, 480f, 2f, 1.0f)

    @Test
    fun `the emulator's Main at font 1 point 3`() = case("main 853x480 1.3", 853f, 480f, 2f, 1.3f)

    @Test
    fun `the emulator's window at font 1 point 0`() = case("emulator 960x540 1.0", 960f, 540f, 2f, 1.0f)

    @Test
    fun `the emulator's window at font 1 point 3`() = case("emulator 960x540 1.3", 960f, 540f, 2f, 1.3f)
}
