package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
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
 * Walk-32i (lead, emulator at the Nova's and the RG's geometry, 2026-09-30): with Favorites only on, the gamelist
 * header read "Favorites · …" on the Nova and "Favorites · 1 …" on the RG, the count gone, while everything right of
 * the Search pill stood empty. The real [GamelistHeader] is composed here inside the shell that LibraryScreen gives it
 * (the viewport, then the Column's own [LibraryGamelistBodyPaddingDp] on both sides), and its label is read back
 * through the text layout Compose actually drew.
 *
 * NATIVE graphics: under Robolectric's default mode text is not shaped, so every width would be fiction. The cut is
 * read as `isLineEllipsized(0)`, not `hasVisualOverflow`: a single-line Ellipsis text is laid out at its constraint
 * width, so the overflow flags stay false even when the tail was replaced by "…". The 240 dp case is the control
 * that shows the detection fires.
 *
 * Each fitting case also pins the seat against what it measured: the label's natural width plus the light-contrast
 * rail's padding stays under [LibraryGamelistMetadataCeilingDp], so the ceiling is a measured number, not a guess.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistHeaderCountFitsTest {

    @get:Rule
    val compose = createComposeRule()

    private val authority = DefaultFocusAuthority()

    @Test
    fun `at the Nova's width Favorites and the count are shown in full beside a full Search pill`() {
        host(NovaViewport) { header(FavoritesTenGames) }

        assertHeaderWidth(NovaViewport)
        val label = labelLayout(FavoritesTenGames, NovaViewport)
        assertFalse("the count is cut on the Nova", label.wasCut())
        assertEquals(1, label.lineCount)
        val pill = pillWidthDp(SearchGames)
        assertTrue("Search is $pill", pill >= LibraryGamelistSearchReadabilityFloorDp.dp && pill <= 136.dp)
        assertTrue("the label fits the seat with margin", label.naturalWidthDp(NovaViewport) + RailPaddingDp <= LibraryGamelistMetadataCeilingDp)
    }

    @Test
    fun `at the Nova's width the label still fits inside the light-contrast rail`() {
        host(NovaViewport) { header(FavoritesTenGames, mediaContrastRail = true) }

        val label = labelLayout(FavoritesTenGames, NovaViewport)
        assertFalse("the rail's padding cuts the count", label.wasCut())
    }

    @Test
    fun `at the RG's width the count is shown in full and the small pill keeps its width`() {
        host(RgViewport) { header(FavoritesTenGames) }

        assertHeaderWidth(RgViewport)
        val label = labelLayout(FavoritesTenGames, RgViewport)
        assertFalse("the count is cut on the RG", label.wasCut())
        // The RG's square profile draws the small pill , so its drawn width is the 100 dp constant
        // and not the seat; the seat itself is pinned on the resolver in LibraryShelfStabilizationContractTest.
        assertEquals(LibraryGamelistCompactSearchWidthDp, pillWidthDp(LibraryGamelistCompactSearchPlaceholder).value, 1f)
        val seat = resolveGamelistHeaderSearchGeometry(RgViewport.headerWidthDp, compact = true)
        assertTrue(seat.searchWidthDp in LibraryGamelistSearchReadabilityFloorDp..136f)
        assertTrue(label.naturalWidthDp(RgViewport) + RailPaddingDp <= LibraryGamelistMetadataCeilingDp)
    }

    @Test
    fun `at the Odin's and the Thor's width the shared row shows the count in full, plain and in the rail`() {
        // Both land in the wide branch (title, label and Search on one row) at ~2.14 density; 1920x1080 px.
        host(OdinViewport) { header(FavoritesTenGames) }

        assertHeaderWidth(OdinViewport)
        val label = labelLayout(FavoritesTenGames, OdinViewport)
        assertFalse("the count is cut on the wide branch", label.wasCut())
        val pill = pillWidthDp(SearchGames)
        assertTrue("Search is $pill", pill >= LibraryGamelistSearchReadabilityFloorDp.dp && pill <= 136.dp)
        assertTrue(label.naturalWidthDp(OdinViewport) + RailPaddingDp <= LibraryGamelistMetadataCeilingDp)
    }

    @Test
    fun `at the Odin's and the Thor's width the label still fits inside the light-contrast rail`() {
        host(OdinViewport) { header(FavoritesTenGames, mediaContrastRail = true) }

        assertFalse(
            "the rail's padding cuts the count on the wide branch",
            labelLayout(FavoritesTenGames, OdinViewport).wasCut(),
        )
    }

    @Test
    fun `a three-digit favourites count is measured for the record, not pinned`() {
        host(NovaViewport) { header("Favorites · 100 games") }

        // Printed by labelLayout; the brief's target is the two-digit count, so this only records the width.
        labelLayout("Favorites · 100 games", NovaViewport)
    }

    @Test
    fun `at the narrowest header that shows Search the pill keeps its floor and the label is cut first`() {
        // 240 dp is the visibility rule; a standard profile so the pill is drawn at the seat the resolver hands it.
        host(NarrowestViewport, profile = MimirViewportProfile(MimirViewportPosture.STANDARD, NarrowestViewport.widthDp, NarrowestViewport.heightDp)) {
            header(FavoritesTenGames)
        }

        assertHeaderWidth(NarrowestViewport)
        val label = labelLayout(FavoritesTenGames, NarrowestViewport)
        assertTrue("the label yields first at 240 dp", label.wasCut())
        val pill = pillWidthDp(SearchGames)
        assertTrue("Search is $pill", pill >= LibraryGamelistSearchReadabilityFloorDp.dp && pill <= 136.dp)
        // The row closes on the header's own width: label seat + gap + pill.
        val labelSeat = labelNode(FavoritesTenGames).getUnclippedBoundsInRoot().width
        assertTrue(
            "$labelSeat + gap + $pill",
            labelSeat + LibraryGamelistHeaderGapDp.dp + pill <= NarrowestViewport.headerWidthDp.dp + 1.dp,
        )
    }

    @Test
    fun `the plain count renders as before at the Nova's width`() {
        host(NovaViewport) { header("17 games") }

        assertFalse(labelLayout("17 games", NovaViewport).wasCut())
        assertTrue(pillWidthDp(SearchGames) >= LibraryGamelistSearchReadabilityFloorDp.dp)
    }

    @Test
    fun `the apps segment's count renders as before at the Nova's width`() {
        host(NovaViewport) { header("3 apps", placeholder = SearchApps) }

        assertFalse(labelLayout("3 apps", NovaViewport).wasCut())
        assertTrue(pillWidthDp(SearchApps) >= LibraryGamelistSearchReadabilityFloorDp.dp)
    }

    // ── the shell around the header ─────────────────────────────────────────────────────────────

    private data class Viewport(val widthDp: Float, val heightDp: Float, val density: Float) {
        val headerWidthDp: Float get() = widthDp - 2f * LibraryGamelistBodyPaddingDp.value
    }

    private fun host(
        viewport: Viewport,
        profile: MimirViewportProfile = mimirViewportProfile(viewport.widthDp, viewport.heightDp),
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(viewport.density, 1f),
                LocalMimirViewportProfile provides profile,
            ) {
                MimirTheme {
                    FixedViewport(viewport) {
                        // LibraryScreen's gamelist Column: fillMaxSize, then its own horizontal inset on both sides.
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                        ) {
                            Box(Modifier.fillMaxWidth().testTag(HeaderSeatTag)) { content() }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Measures the child at exactly the viewport, whatever the Robolectric window is. */
    @Composable
    private fun FixedViewport(viewport: Viewport, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val widthPx = with(density) { viewport.widthDp.dp.roundToPx() }
        val heightPx = with(density) { viewport.heightDp.dp.roundToPx() }
        Layout(content = { Box(propagateMinConstraints = true) { content() } }) { measurables, constraints ->
            val placeable = measurables.single().measure(Constraints.fixed(widthPx, heightPx))
            layout(
                widthPx.coerceIn(constraints.minWidth, constraints.maxWidth),
                heightPx.coerceIn(constraints.minHeight, constraints.maxHeight),
            ) { placeable.placeRelative(0, 0) }
        }
    }

    @Composable
    private fun header(metaLabel: String, placeholder: String = SearchGames, mediaContrastRail: Boolean = false) {
        GamelistHeader(
            title = "Super Nintendo",
            metaLabel = metaLabel,
            showSearch = true,
            searchQuery = "",
            searchPlaceholder = placeholder,
            authority = authority,
            searchControllerEnabled = true,
            searchFocused = false,
            onOpenSearch = {},
            onClearSearch = {},
            segments = listOf(FavoriteSegment.GAMES),
            segmentCounts = emptyMap(),
            activeSegment = FavoriteSegment.GAMES,
            onSelectSegment = {},
            mediaContrastRail = mediaContrastRail,
        )
    }

    // ── reading back what was drawn ─────────────────────────────────────────────────────────────

    private fun assertHeaderWidth(viewport: Viewport) {
        val seat = compose.onNodeWithTag(HeaderSeatTag).getUnclippedBoundsInRoot().width
        assertEquals("the header is measured at the shell's width", viewport.headerWidthDp, seat.value, 1f)
    }

    /** The words each layout was asked to show in full: the count is shortened at a WORD now (a different text), not ellipsized in place. */
    private val fullTextOf = java.util.IdentityHashMap<TextLayoutResult, String>()

    private fun TextLayoutResult.wasCut(): Boolean = isLineEllipsized(0) || layoutInput.text.text != fullTextOf[this]

    /** The count's node: its own words when whole, its spoken name when shortened (the full words stay its contentDescription). */
    private fun labelNode(text: String) =
        compose.onNode(hasText(text) or hasContentDescription(text), useUnmergedTree = true)

    /** The layout Compose drew for the label, through the text node's own GetTextLayoutResult action. */
    private fun labelLayout(text: String, viewport: Viewport): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = labelNode(text).fetchSemanticsNode()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        val layout = results.single()
        fullTextOf[layout] = text
        println(
            "header label \"$text\" at ${viewport.widthDp}x${viewport.heightDp}@${viewport.density}: " +
                "natural ${layout.naturalWidthDp(viewport)} dp, drawn ${layout.size.width / viewport.density} dp, " +
                "ellipsized=${layout.wasCut()}",
        )
        return layout
    }

    private fun TextLayoutResult.naturalWidthDp(viewport: Viewport): Float =
        multiParagraph.maxIntrinsicWidth / viewport.density

    /** The pill is the nearest semantics parent of its placeholder (MimirIntentText speaks the placeholder). */
    private fun pillWidthDp(placeholder: String) =
        compose.onNode(hasContentDescription(placeholder), useUnmergedTree = true)
            .onParent()
            .getUnclippedBoundsInRoot()
            .width
            .also { println("search pill \"$placeholder\": $it") }

    private companion object {
        const val FavoritesTenGames = "Favorites · 10 games"
        const val SearchGames = "Search games"
        const val SearchApps = "Search apps"
        const val HeaderSeatTag = "gamelist-header-seat"

        /** The light-contrast rail pads the label 6 dp on each side inside its seat (GamelistMetaSearchRow). */
        const val RailPaddingDp = 12f

        /** 1280x960 px at 320 dpi. */
        val NovaViewport = Viewport(640f, 480f, 2f)

        /** 720x720 px at density 204: the RG Rotate's square. */
        val RgViewport = Viewport(564.7f, 564.7f, 1.275f)

        /** 1920x1080 px at ~2.14: the Odin's and the Thor's ~897x505 dp, whose post-inset header is the 833 dp the wide pins use. */
        val OdinViewport = Viewport(897.2f, 504.7f, 2.14f)

        /** The narrowest header that still shows Search (240 dp) plus the shell's inset on both sides. */
        val NarrowestViewport = Viewport(240f + 2f * LibraryGamelistBodyPaddingDp.value, 240f, 2f)
    }
}
