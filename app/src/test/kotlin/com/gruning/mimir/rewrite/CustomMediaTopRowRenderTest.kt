package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
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
 * The Customize media page's top row , the REAL [CustomMediaTopRow], at the two content widths the page gives it and at font scales 1.0 and 1.3, for each of the five types.
 *
 * The row uses one line with no ellipsis, at every width and font, and  no Back chip: the page opens only on Main, where B returns. , so the row
 * STARTS with Search and reads Search, Media, the type's chips, as the Achievements hub does. The Main width
 * (the panel's 680 dp less its padding and the top row's 2 dp a side for the ring: ~652 dp of content) keeps every type's chips as they are. The
 * compact width (the 360 dp screen's ~322 dp of content) cannot seat Media, three chips and Search on one line at font 1.3 (at 1.0 it just can, about 319 of 322 dp), so the type's chips FOLD into one Filters chip there (48 dp, like the
 * collapsed magnifier). In EVERY one of the 20 cases (5 types x 2 widths x 2 fonts) the row is ONE line: nothing sits outside it, nothing overlaps, every target is at least 48 dp, every
 * chip word is ONE line, and no chip word is ellipsized. Search is the whole name, the name cut at a WORD with an ellipsis, or the 48 dp magnifier mark, and never a name cut inside a
 * word ("Metroid F"; the cut is at a word, the mark only when not even the first word stands); at the compact width, where the name has no room, it is the mark in every case.
 * To prevent Search shrinking during type changes, Search is ONE width for the whole page, so at every width and font its target is the same
 * width for every type. `CUSTOM-MEDIA-ROW` reports which cases fold. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMediaTopRowRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density = 2f
    private val mainContentDp = 652f
    private val compactContentDp = 322f

    private val authority = DefaultFocusAuthority()
    private val layouts = ArrayList<Pair<String, TextLayoutResult>>()

    /** Per case (width, font, type): whether Search was drawn as its 48 dp mark, the other case being the name (whole, or cut at a word). */
    private val searchIsMark = linkedMapOf<String, Boolean>()

    /** Per case (width, font, type): the width of Search's target, which must not depend on the type. */
    private val searchTargetDp = linkedMapOf<String, Float>()

    private val menu = HomeTileMenuState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 1L,
            addedAtMillis = 10L,
            title = "Metroid Prime Fusion Echoes",
        ),
        removable = true,
    )
    private val types = homeTileCustomMediaTypes(menu)

    private val typeFilters = customMediaTypeFilters(menu, types)

    // Icon art is the merged picker : kind ICON, state SHELF, the Type chip of every shape, as the builder makes it.
    private fun stateFor(type: CustomMediaType) = HomeCustomEnrichState(
        sessionId = "render-${type.label}",
        kind = type.kind,
        filters = typeFilters.getValue(type.selection),
        shelfIconArt = type.isMergedIconArt,
        cacheKey = mediaStorageKey("game-1", type.mediaState),
        gameTitle = "Metroid Prime Fusion Echoes",
        queryDraft = "Metroid Prime Fusion Echoes",
        mediaState = type.mediaState,
        mediaTypes = types,
        mediaTypeFilters = typeFilters,
    )

    private var shown by mutableStateOf(stateFor(types.first()))

    private fun mount(widthDp: Float, font: Float) {
        val observer: (String, TextLayoutResult) -> Unit = { spoken, layout -> layouts += spoken to layout }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, font),
                LocalMimirTextLayoutObserver provides observer,
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, 160.dp)) {
                        CustomMediaTopRow(
                            authority = authority,
                            state = shown,
                            focusedKey = null,
                            onAction = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun rect(tag: String): Rect =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun textsUnder(root: SemanticsNode): List<Triple<String, Rect, TextLayoutResult>> {
        val out = ArrayList<Triple<String, Rect, TextLayoutResult>>()
        fun walk(node: SemanticsNode) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = ArrayList<TextLayoutResult>()
                action.invoke(results)
                results.firstOrNull()?.let { layout -> out += Triple(layout.layoutInput.text.text, node.boundsInRoot, layout) }
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun dp(px: Float) = px / density

    private fun exists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Checks one type's row, which must stand on ONE line whatever the width and font, and returns whether its chips were
     * folded into the one Filters chip.
     */
    private fun checkRow(where: String, state: HomeCustomEnrichState): Boolean {
        val root = compose.onAllNodesWithTag(CustomMediaTopRowTag, useUnmergedTree = true).fetchSemanticsNodes().first()
        val row = root.boundsInRoot
        val chips = state.visibleFilters()
        val folded = exists(CustomMediaFiltersTag)
        val pieces = linkedMapOf<String, Rect>()
        // no Back chip, by tag and by word. Search is the first piece, standing at the row's start, then Media.
        assertFalse("$where: no Back chip", exists("custom-media-back"))
        pieces["search"] = rect(CustomMediaSearchTag)
        assertTrue(
            "$where: the row starts with Search: ${dp(pieces.getValue("search").left)} vs the row's ${dp(row.left)} dp",
            pieces.getValue("search").left <= row.left + 0.5f,
        )
        pieces["media"] = rect(CustomMediaTypeChipTag)
        if (folded) {
            pieces["filters"] = rect(CustomMediaFiltersTag)
            chips.forEach { assertFalse("$where: chip ${it.key} is folded away", exists(customMediaFilterChipTag(it.key))) }
        } else {
            chips.forEach { pieces["chip ${it.key}"] = rect(customMediaFilterChipTag(it.key)) }
            assertFalse("$where: no Filters chip while the chips stand", exists(CustomMediaFiltersTag))
        }
        val searchPlate = rect(CustomMediaSearchPlateTag)

        pieces.forEach { (name, r) ->
            assertTrue("$where: $name starts inside the row: ${dp(r.left)} vs ${dp(row.left)} dp", r.left >= row.left - 0.5f)
            assertTrue("$where: $name ends inside the row: ${dp(r.right)} vs ${dp(row.right)} dp", r.right <= row.right + 0.5f)
            assertTrue("$where: $name is at least 48 dp wide: ${dp(r.width)}", dp(r.width) >= 47.9f)
            assertTrue("$where: $name is at least 48 dp tall: ${dp(r.height)}", dp(r.height) >= 47.9f)
        }
        assertTrue(
            "$where: the search plate stands inside its target: $searchPlate in ${pieces.getValue("search")}",
            searchPlate.left >= pieces.getValue("search").left - 0.5f && searchPlate.right <= pieces.getValue("search").right + 0.5f,
        )

        // Search is the name on one line (whole, or cut AT A WORD with an ellipsis) or only its 48 dp mark, never a name cut inside a word ("Metroid F").
        // The plate is the magnifier's 16 dp and its 2 x 12 dp padding when collapsed.
        val searchTarget = pieces.getValue("search")
        searchTargetDp[where] = dp(searchTarget.width)
        val markPlateDp = 16f + 24f
        val nameShown = dp(searchPlate.width) > markPlateDp + 1f
        if (nameShown) {
            val nameLayout = textsUnder(root)
                .filter { (_, box, _) -> box.left >= searchPlate.left - 0.5f && box.right <= searchPlate.right + 0.5f && box.top >= searchPlate.top - 0.5f && box.bottom <= searchPlate.bottom + 0.5f }
                .map { it.third }
                .firstOrNull()
            requireNotNull(nameLayout) { "$where: the search plate is ${dp(searchPlate.width)} dp wide, so it holds the name, which was laid out" }
            val drawn = nameLayout.layoutInput.text.text
            assertEquals("$where: the search name is ONE line", 1, nameLayout.lineCount)
            assertFalse("$where: the search name is not cut by the text's own overflow", (0 until nameLayout.lineCount).any { nameLayout.isLineEllipsized(it) })
            assertTrue(
                "$where: the search name is WHOLE inside its box, not clipped: ${nameLayout.getLineRight(0)} vs ${nameLayout.size.width}",
                nameLayout.getLineRight(0) <= nameLayout.size.width + 0.5f,
            )
            assertWholeOrWordCut(where, state.queryDraft, drawn)
        } else {
            assertTrue("$where: collapsed, the search plate is the magnifier mark: ${dp(searchPlate.width)} dp", dp(searchPlate.width) <= markPlateDp + 1f)
            assertTrue("$where: collapsed, the search target is the 48 dp mark chip: ${dp(searchTarget.width)} dp", dp(searchTarget.width) <= 48.5f)
        }
        searchIsMark[where] = !nameShown

        val mediaY = pieces.getValue("media").center.y
        assertTrue(
            "$where: the row is ONE line: " + pieces.entries.joinToString { "${it.key} y=${dp(it.value.center.y).toInt()}" },
            pieces.values.all { abs(it.center.y - mediaY) < 4f },
        )
        // Nothing overlaps, and the row reads left to right in the drawn order: Search, Media, the chips (or Filters).
        val ordered = pieces.entries.sortedBy { it.value.left }
        assertEquals("$where: the pieces stand in the drawn order", pieces.keys.toList(), ordered.map { it.key })
        for (i in 0 until ordered.size - 1) {
            assertTrue(
                "$where: ${ordered[i].key} and ${ordered[i + 1].key} do not touch: ${dp(ordered[i].value.right)} vs ${dp(ordered[i + 1].value.left)} dp",
                ordered[i].value.right <= ordered[i + 1].value.left + 0.5f,
            )
        }

        val words = textsUnder(root)
        val spoken = words.map { it.first }
        assertTrue("$where: the Media chip says ${customMediaTypeChipText(state.currentMediaType())}: $spoken", customMediaTypeChipText(state.currentMediaType()) in spoken)
        val full = !folded && chips.all { customMediaFilterChipText(it, CustomMediaChipForm.FULL) in spoken }
        val value = !folded && chips.all { customMediaFilterChipText(it, CustomMediaChipForm.VALUE) in spoken }
        if (!folded) assertTrue("$where: the chips all wear one form (label and value, or value alone): $spoken", full || value)
        assertTrue("$where: nothing in the row says Back or leads with its glyph: $spoken", spoken.none { it.startsWith("‹") || it == "Back" })
        words.forEach { (text, _, layout) ->
            assertEquals("$where: '$text' is ONE line", 1, layout.lineCount)
            assertFalse("$where: '$text' is not ellipsized", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertTrue("$where: '$text' ends inside its box: ${layout.getLineRight(0)} vs ${layout.size.width}", layout.getLineRight(0) <= layout.size.width + 0.5f)
        }
        println(
            "CUSTOM-MEDIA-ROW $where: ONE line, " +
                "chips=${if (folded) "FOLDED into Filters" else if (full) "label+value" else "value"}, " +
                "pieces=" + pieces.entries.joinToString { "${it.key} ${dp(it.value.left).toInt()}..${dp(it.value.right).toInt()}dp" } +
                ", search ${if (nameShown) "name" else "MARK"} plate ${dp(searchPlate.width).toInt()}dp",
        )
        return folded
    }

    /** [drawn] is [full] whole, or [full] cut where a word ends and closed with the ellipsis, and nothing else (a cut inside a word is the defect this pins). */
    private fun assertWholeOrWordCut(where: String, full: String, drawn: String) {
        if (drawn == full) return
        assertTrue("$where: a shortened name ends in the ellipsis: '$drawn'", drawn.endsWith("…"))
        val stem = drawn.removeSuffix("…")
        assertTrue("$where: '$stem' is the start of '$full'", full.startsWith(stem))
        assertTrue("$where: the cut stands where a word ends, not inside one: '$drawn' of '$full'", full.length > stem.length && full[stem.length].isWhitespace())
    }

    /** Every type's row at one width and font; each must be one line. Returns which folded their chips. */
    private fun walkEveryType(where: String, widthDp: Float, font: Float): Map<String, Boolean> {
        mount(widthDp, font)
        val folds = linkedMapOf<String, Boolean>()
        types.forEach { type ->
            val next = stateFor(type)
            // A bare global write would poison every later Compose class in this fork (AppNotIdle); wrap it.
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { shown = next }
            settle()
            folds[type.label] = checkRow("$where ${type.label}", shown)
        }
        // The search's name was laid out on one line, whole or clipped, never ellipsized or wrapped.
        layouts.forEach { (spoken, layout) ->
            assertEquals("'$spoken' is ONE line", 1, layout.lineCount)
            assertFalse("'$spoken' is not ellipsized", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        }
        // Search is ONE width for the whole page: the same for every type, folded or not.
        val seats = types.map { searchTargetDp.getValue("$where ${it.label}") }
        assertTrue(
            "$where: Search keeps one width across the types: " + types.zip(seats).joinToString { (type, w) -> "${type.label} ${w}dp" },
            seats.max() - seats.min() <= 0.5f,
        )
        println("CUSTOM-MEDIA-ROW $where: all ${folds.size} types ONE line; chips folded for ${folds.filterValues { it }.keys}; Search ${seats.first()}dp in every type")
        return folds
    }

    @Test
    fun `at the Main width and font 1_0 every type's row is one line with nothing cut or folded`() {
        val folds = walkEveryType("main font=1.0", mainContentDp, 1.0f)
        assertTrue("no chips fold at the Main width: $folds", folds.values.none { it })
    }

    @Test
    fun `at the Main width and font 1_3 every type's row is one line with nothing cut or folded`() {
        val folds = walkEveryType("main font=1.3", mainContentDp, 1.3f)
        assertTrue("no chips fold at the Main width: $folds", folds.values.none { it })
    }

    @Test
    fun `at the compact width and font 1_0 every type's row is one line, and only the four-chip Icon art folds`() {
        val folds = walkEveryType("compact font=1.0", compactContentDp, 1.0f)
        // The Back chip's room is the chips' now: Hero art's three chips, which had to fold into Filters beside it, stand in their value form
        // beside Media and the magnifier (about 319 of the 322 dp). Icon art is the merged picker (Type, Region, Style, Size: four chips, as the
        // builder makes it; this fixture once gave it the legacy one-chip row, which is why this pin said no type folds), and its four cannot
        // stand beside Media and the magnifier: they fold, and the other three do not.
        assertEquals("only Icon art folds at the compact width and font 1.0: $folds", setOf("Icon art"), folds.filterValues { it }.keys)
        assertCompactSearchIsTheMark()
    }

    @Test
    fun `at the compact width and font 1_3 every type's row is one line, and the three-chip type folds`() {
        val folds = walkEveryType("compact font=1.3", compactContentDp, 1.3f)
        assertTrue("Hero art (three chips) folds into Filters at the compact width: $folds", folds.getValue("Hero art"))
        assertCompactSearchIsTheMark()
    }

    /**
     * At the compact width the room the widest type leaves Search holds not even the first word of the name and its ellipsis, so Search is the 48 dp mark in every type. (It is the
     * same mark in every type because the seat is the page's, not the type's:.)
     */
    private fun assertCompactSearchIsTheMark() {
        assertEquals("one verdict per type", types.size, searchIsMark.size)
        assertTrue("Search collapses to its mark at the compact width: $searchIsMark", searchIsMark.values.all { it })
    }
}
