package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onParent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-11 (vqa11), P3: with the favourites filter on and the font at 1.3, the Library header's count read "Favorites · 2…" while the row had room beside it. The count's seat
 * (152 dp, measured at font 1.0) did not grow with the font. It does now ([resolveGamelistHeaderSearchGeometry]'s `fontScale`), so the count stands whole like the "Local library"
 * tag and, when the row is short of room, it is the title that gives way, at a word.
 *
 * The real [GamelistHeader] in the Library's own shell at the RP5's 700 x 350 dp and the Thor's Main (832.5 x 468.3 dp), at font 1.0 and 1.3, read back through the layouts Compose drew.
 * The header's right edge stops before the status rail (136 dp from the end). The frames are written to `build/test-captures/sweepfix32/` for the lead to look at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistHeaderLargeTypeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val layouts = mutableMapOf<String, TextLayoutResult>()
    private val density = 2f

    private fun mount(widthDp: Float, heightDp: Float, fontScale: Float, title: String = "Super Nintendo", meta: String = "Favorites · 2 games", rail: Boolean = false) {
        layouts.clear()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
                LocalMimirTextLayoutObserver provides { spoken, layout -> layouts[spoken] = layout },
            ) {
                MimirTheme {
                    // The Library screen's own ground (the theme draws none of its own), so the light words are read on what they sit on.
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).background(MimirColors.SurfaceBase)) {
                        // LibraryScreen's gamelist Column: fillMaxSize, then its own horizontal inset on both sides.
                        Column(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md)) {
                            GamelistHeader(
                                title = title,
                                metaLabel = meta,
                                showSearch = true,
                                searchQuery = "",
                                searchPlaceholder = "Search games",
                                authority = authority,
                                searchControllerEnabled = true,
                                searchFocused = false,
                                onOpenSearch = {},
                                onClearSearch = {},
                                segments = emptyList(),
                                segmentCounts = emptyMap(),
                                activeSegment = FavoriteSegment.GAMES,
                                onSelectSegment = {},
                                mediaContrastRail = rail,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(startsWith: String): String =
        compose.onAllNodes(hasText(startsWith, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .first { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text }.startsWith(startsWith) }
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    /** The layout Compose drew for the title's text node, through the node's own GetTextLayoutResult action (the title is plain Text, which the observer does not see). */
    private fun titleLayout(startsWith: String): TextLayoutResult {
        val node = compose.onAllNodes(hasText(startsWith, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.config[SemanticsProperties.Text].joinToString("") { text -> text.text }.startsWith(startsWith) }
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("the title exposes its layout", node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    private fun assertWholeWords(full: String, shown: String, label: String) {
        if (shown == full) return
        assertTrue("$label is shortened with an ellipsis: <$shown>", shown.endsWith("…"))
        val head = shown.removeSuffix("…")
        assertTrue("$label: <$head> is the start of <$full>", full.startsWith(head))
        assertTrue("$label is cut between two words, not inside one: <$shown>", full[head.length].isWhitespace())
    }

    private fun assertTagWhole(label: String) {
        val layout = layouts["Local library"]
        assertNotNull("$label: the tag drew", layout)
        assertFalse("$label: the tag is never cut", layout!!.isLineEllipsized(0) || layout.hasVisualOverflow)
        val tag = compose.onNodeWithTag(GamelistLocalLibraryTagTestTag).fetchSemanticsNode().boundsInRoot
        assertTrue("$label: the tag is as wide as its words (${tag.width} >= ${layout.size.width})", tag.width >= layout.size.width)
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/test-captures/sweepfix32").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SWEEPFIX32 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: $out", out.isFile && out.length() > 1_000L)
    }

    /**
     * [searchMark]: the row cannot hold the title's first word beside the tag, the whole count and a full pill (the RP5 at 1.3x leaves it about 30 dp), so Search is the 48 dp magnifier
     * alone and the title takes the room. [expectedTitle] is the title as drawn: whole, or its first word and an ellipsis.
     */
    private fun check(
        widthDp: Float,
        heightDp: Float,
        fontScale: Float,
        name: String,
        searchMark: Boolean = false,
        expectedTitle: String = "Super Nintendo",
        rail: Boolean = false,
        countWhole: Boolean = true,
    ) {
        mount(widthDp, heightDp, fontScale, rail = rail)
        val label = "${widthDp.toInt()}x${heightDp.toInt()} @${"%.1f".format(fontScale)}x"
        val count = shown("Favorites ·")
        val title = shown("Super")
        val pill = compose.onNode(hasContentDescription("Search games"), useUnmergedTree = true).onParent().fetchSemanticsNode().boundsInRoot
        val tag = compose.onNodeWithTag(GamelistLocalLibraryTagTestTag).fetchSemanticsNode().boundsInRoot
        val titleCutInWord = titleLayout("Super").isLineEllipsized(0) && title == "Super Nintendo"
        println("GAMELISTHEADER $label count=<$count> title=<$title> titleCutInsideItsFirstWord=$titleCutInWord tag=${tag.width / density} dp wide, search pill right edge ${pill.right / density} dp, width ${pill.width / density} dp")
        save("gamelist-header-$name.png")
        if (countWhole) {
            assertEquals("$label: the count stands whole", "Favorites · 2 games", count)
        } else {
            assertWholeWords("Favorites · 2 games", count, "$label: the count")
        }
        assertFalse("$label: the title is cut inside a word", titleCutInWord)
        assertWholeWords("Super Nintendo", title, "$label: the title")
        assertEquals("$label: the title as drawn", expectedTitle, title)
        assertTagWhole(label)
        if (searchMark) {
            assertEquals("$label: Search is the magnifier alone", LibraryGamelistSearchMarkWidthDp, pill.width / density, 0.5f)
        } else {
            assertTrue("$label: Search keeps its readability floor (${pill.width / density} dp)", pill.width / density >= LibraryGamelistSearchReadabilityFloorDp - 0.5f)
        }
        // The header stops short of the status rail: the Library's 32 dp edge inset, then the rail's 136 dp reserve.
        val railStart = (widthDp - LibraryGamelistBodyPaddingDp.value - LibraryGamelistStatusEndInsetDp) * density
        assertTrue("$label: Search ends at ${pill.right / density} dp, past the status rail at ${railStart / density} dp", pill.right <= railStart + 1f)
    }

    @Test
    fun `Main at font 1_0 shows the count whole beside the whole title`() = check(832.5f, 468.3f, 1.0f, "main-1.0")

    @Test
    fun `Main at font 1_3 shows the count and the tag and the title whole, Search the magnifier`() = check(832.5f, 468.3f, 1.3f, "main-1.3", searchMark = true)

    @Test
    fun `the RP5 at font 1_0 shows the count and the title whole, Search the magnifier`() = check(700f, 350f, 1.0f, "rp5-1.0", searchMark = true)

    @Test
    fun `the RP5 at font 1_3 gives Search's room to the title, which gives way at a word`() = check(700f, 350f, 1.3f, "rp5-1.3", searchMark = true, expectedTitle = "Super…")

    @Test
    fun `the RP5 at font 1_3 in the light-contrast rail does the same, and the count gives way at a word before the title is cut inside one`() =
        check(700f, 350f, 1.3f, "rp5-1.3-rail", searchMark = true, expectedTitle = "Super…", rail = true, countWhole = false)

    @Test
    fun `the Nova's stacked header shows the count whole at font 1_3`() = check(640f, 480f, 1.3f, "nova-1.3")

    @Test
    fun `a three-digit count stands whole at font 1_3 on Main`() {
        mount(832.5f, 468.3f, 1.3f, meta = "Favorites · 100 games")
        assertEquals("Favorites · 100 games", shown("Favorites ·"))
        assertTagWhole("Main @1.3x, 100 games")
    }

    @Test
    fun `a mark-only Search is the magnifier's width and gives the rest of the row to the title`() {
        val full = resolveGamelistHeaderSearchGeometry(500f + LibraryGamelistStatusEndInsetDp, compact = false, fontScale = 1.3f)
        val mark = resolveGamelistHeaderSearchGeometry(500f + LibraryGamelistStatusEndInsetDp, compact = false, fontScale = 1.3f, searchMarkOnly = true)
        assertEquals(136f, full.searchWidthDp, 0.01f)
        assertEquals(LibraryGamelistSearchMarkWidthDp, mark.searchWidthDp, 0.01f)
        assertTrue(mark.showSearch)
        assertEquals("the title gets what the pill gave back", full.titleMaxWidthDp + (136f - LibraryGamelistSearchMarkWidthDp), mark.titleMaxWidthDp, 0.01f)
        assertEquals("the count's seat is the same", full.metadataMaxWidthDp, mark.metadataMaxWidthDp, 0.01f)
    }

    @Test
    fun `the seat grows with the font and never shrinks below the one measured at font 1_0`() {
        val one = resolveGamelistHeaderSearchGeometry(768.5f, compact = false)
        val big = resolveGamelistHeaderSearchGeometry(768.5f, compact = false, fontScale = 1.3f)
        val small = resolveGamelistHeaderSearchGeometry(768.5f, compact = false, fontScale = 0.85f)
        assertEquals(LibraryGamelistMetadataCeilingDp, one.metadataMaxWidthDp, 0.01f)
        assertEquals(LibraryGamelistMetadataCeilingDp * 1.3f, big.metadataMaxWidthDp, 0.01f)
        assertEquals(LibraryGamelistMetadataCeilingDp, small.metadataMaxWidthDp, 0.01f)
        // Search keeps its full seat beside the larger label on the wide branch, and the compact branch still floors the label at 90 dp on the smallest surfaces.
        assertEquals(one.searchWidthDp, big.searchWidthDp, 0.01f)
        val tight = resolveGamelistHeaderSearchGeometry(260f, compact = true, fontScale = 1.3f)
        assertTrue("the label never takes Search's seat on the smallest surfaces", tight.metadataMaxWidthDp <= (260f - 136f - tight.itemGapDp).coerceAtLeast(LibraryGamelistCompactMetadataFloorDp) + 0.01f)
    }
}
