package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
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
 * The gallery's tiles , the REAL [EnrichGallery], for each of the five types at font scales 1.0 and 1.3, at the Main width and at the
 * compact width, with the Default tile active and with a saved tile active and another focused (which shows the remove mark, the widest the
 * bar gets). Every piece of tile text is ONE line, uncut and not ellipsized, and every tile is its art over ONE bar, the same height lit or
 * not (the 10-09 sweep): the Default tile's bar says DEFAULT (and its check), a saved tile's bar the whole label, or the provider and the
 * dimensions, or the dimensions alone; nothing is drawn over the art, and NO PREVIEW sits in the art, above the bar. `CUSTOM-MEDIA-TILE` prints what each tile said. The footer under the gallery  is checked the same way in the same twenty cases: the REAL [EnrichFooter] with a removable
 * saved asset focused (the longest legend, "A USE · SELECT PREVIEW · X REMOVE · B BACK", and the Expand hint), with Upload focused and with Search focused. Its legend stacks as whole
 * segments inside the room to the right of Upload: never clipped, never beginning before Upload ends, never past the panel's edge. `CUSTOM-MEDIA-FOOTER` prints where each piece stood.
 * Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMediaTileRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density = 2f
    private val mainWidthDp = 656f
    private val compactWidthDp = 322f

    private val authority = DefaultFocusAuthority()
    private val layouts = ArrayList<Pair<String, TextLayoutResult>>()

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

    private val savedLabels = listOf(
        "ScreenScraper · 600×800",
        "SteamGridDB · 1920×1080",
        "Uploaded image",
        "Existing selection",
        "ScreenScraper · Video · 3840×2160",
    )

    private fun saved(n: Int, label: String) = EnrichSavedAsset(
        assetId = "saved-$n",
        source = EnrichSavedSource.SCREENSCRAPER,
        label = label,
        localPath = "",
        previewPath = "",
        width = 600,
        height = 800,
        bytes = 1L,
        addedAt = n.toLong(),
    )

    private fun stateFor(type: CustomMediaType, activeSavedId: String?) = HomeCustomEnrichState(
        sessionId = "tiles-${type.label}",
        kind = type.kind,
        cacheKey = mediaStorageKey("game-1", type.mediaState),
        gameTitle = "Metroid Prime Fusion Echoes",
        queryDraft = "Metroid Prime Fusion Echoes",
        saved = savedLabels.mapIndexed { i, label -> saved(i + 1, label) },
        activeAssetId = activeSavedId,
        defaultArt = HomeCustomEnrichDefaultArt.ConsoleLineArt("gba"),
        mediaState = type.mediaState,
        mediaTypes = types,
    )

    private var shown by mutableStateOf(stateFor(types.first(), null))
    private var focused by mutableStateOf<FocusKey?>(null)

    private fun mount(widthDp: Float, font: Float, compact: Boolean) {
        val observer: (String, TextLayoutResult) -> Unit = { spoken, layout -> layouts += spoken to layout }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, font),
                LocalMimirTextLayoutObserver provides observer,
            ) {
                MimirTheme {
                    // Tall enough for the tile band (its bar is carved out of it) and nothing more, so no preview stage is drawn under it.
                    Box(Modifier.requiredSize(widthDp.dp, (homeCustomEnrichGalleryMinHeightDp(shown.kind, compact) + 24).dp)) {
                        EnrichGallery(
                            authority = authority,
                            state = shown,
                            focusedKey = focused,
                            compactSquare = compact,
                            onAction = {},
                            onExpandPreview = {},
                            modifier = Modifier.fillMaxSize(),
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

    private fun dp(px: Float) = px / density

    /**
     * A node's whole box. `boundsInRoot` is clipped by the ancestors, and the gallery scrolls: a tile beyond the viewport's edge reports a
     * sliver (or nothing), which says where it is shown, not how big it is.
     */
    private fun SemanticsNode.full(): Rect =
        Rect(positionInRoot.x, positionInRoot.y, positionInRoot.x + size.width, positionInRoot.y + size.height)

    private fun textsUnder(root: SemanticsNode): List<Triple<String, Rect, TextLayoutResult>> {
        val out = ArrayList<Triple<String, Rect, TextLayoutResult>>()
        fun walk(node: SemanticsNode) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = ArrayList<TextLayoutResult>()
                action.invoke(results)
                results.firstOrNull()?.let { layout -> out += Triple(layout.layoutInput.text.text, node.full(), layout) }
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    private val isTile = SemanticsMatcher("is a gallery tile") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("custom-media-tile-") == true
    }

    private fun checkTiles(where: String, state: HomeCustomEnrichState) {
        val tiles = compose.onAllNodes(isTile, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("$where: Default and the five saved tiles", 1 + savedLabels.size, tiles.size)
        val bars = compose.onAllNodesWithTag(CustomMediaTileBarTag, useUnmergedTree = true).fetchSemanticsNodes().map { it.full() }
        val plates = compose.onAllNodesWithTag(CustomMediaTilePlateTag, useUnmergedTree = true).fetchSemanticsNodes().map { it.full() }
        assertEquals(
            "$where: every tile's bar is one height, lit or not: ${bars.map { dp(it.height) }}",
            1,
            bars.map { (it.height * 10f).toInt() }.distinct().size,
        )
        val said = ArrayList<String>()
        tiles.forEach { tile ->
            val tag = tile.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            val box = tile.full()
            val words = textsUnder(tile)
            val tileBars = bars.filter { it.center.x in box.left..box.right && it.center.y in box.top..box.bottom }
            assertEquals("$where $tag: one bar, below the art", 1, tileBars.size)
            val bar = tileBars.single()
            plates.filter { it.center.x in box.left..box.right && it.center.y in box.top..box.bottom }.forEach { plate ->
                assertTrue(
                    "$where $tag: the art ends where the bar begins, nothing under or over it: plate bottom ${dp(plate.bottom)} vs bar top ${dp(bar.top)} dp",
                    plate.bottom <= bar.top + 0.5f,
                )
            }
            words.forEach { (text, textBox, layout) ->
                if (text != "NO PREVIEW" && text != "NO ART") {
                    assertTrue(
                        "$where $tag: '$text' is said in the bar, never on the art: ${dp(textBox.top)}..${dp(textBox.bottom)} vs bar ${dp(bar.top)}..${dp(bar.bottom)} dp",
                        textBox.top >= bar.top - 0.5f && textBox.bottom <= bar.bottom + 0.5f,
                    )
                }
                assertEquals("$where $tag: '$text' is ONE line", 1, layout.lineCount)
                assertFalse("$where $tag: '$text' is not ellipsized", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                assertTrue(
                    "$where $tag: '$text' is whole inside its box: ${layout.getLineRight(0)} vs ${layout.size.width}",
                    layout.getLineRight(0) <= layout.size.width + 0.5f,
                )
                assertTrue(
                    "$where $tag: '$text' stands inside its tile: ${dp(textBox.left)}..${dp(textBox.right)} vs ${dp(box.left)}..${dp(box.right)} dp",
                    textBox.left >= box.left - 0.5f && textBox.right <= box.right + 0.5f,
                )
                said += "$tag:'$text'"
            }
            if (tag == customMediaTileTag("auto")) {
                assertTrue("$where: the Default tile's bar says DEFAULT, whole, or nothing (its check is drawn): ${words.map { it.first }}", words.all { it.first == "DEFAULT" })
            }
            bars.filter { it.center.x in box.left..box.right && it.center.y in box.top..box.bottom }.forEach { bar ->
                assertTrue(
                    "$where $tag: the bar stands inside its tile: ${dp(bar.left)}..${dp(bar.right)} vs ${dp(box.left)}..${dp(box.right)} dp",
                    bar.left >= box.left - 0.5f && bar.right <= box.right + 0.5f,
                )
            }
        }
        // NO PREVIEW stands above the bar of its tile, never under it.
        val noPreview = compose.onAllNodes(hasContentDescription("No preview"), useUnmergedTree = true).fetchSemanticsNodes()
        noPreview.forEach { node ->
            val at = node.full()
            val tile = tiles.map { it.full() }.first { at.left >= it.left - 0.5f && at.right <= it.right + 0.5f && at.top >= it.top - 0.5f && at.bottom <= it.bottom + 0.5f }
            bars.firstOrNull { it.left >= tile.left - 0.5f && it.right <= tile.right + 0.5f && it.top >= tile.top - 0.5f && it.bottom <= tile.bottom + 0.5f }?.let { bar ->
                assertTrue("$where: NO PREVIEW stands above the bar: bottom ${dp(at.bottom)} vs bar top ${dp(bar.top)} dp", at.bottom <= bar.top + 0.5f)
            }
        }
        println("CUSTOM-MEDIA-TILE $where: bar words ${said.filterNot { it.endsWith("'✓'") }} ; ${noPreview.size} NO PREVIEW nodes")
    }

    private fun walk(where: String, widthDp: Float, font: Float, compact: Boolean) {
        mount(widthDp, font, compact)
        types.forEach { type ->
            listOf<Pair<String?, Int?>>(null to null, "saved-1" to 2).forEach { (active, focusIndex) ->
                val next = stateFor(type, active)
                // A bare global write would poison every later Compose class in this fork (AppNotIdle); wrap it.
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    shown = next
                    focused = focusIndex?.let { homeCustomEnrichAssetKey(next, HomeCustomEnrichAsset.Saved(next.saved[it])) }
                }
                settle()
                checkTiles("$where ${type.label}${if (active == null) " (Default active)" else " (saved-1 active, saved-3 focused)"}", shown)
            }
        }
        // The words drawn through the text intent (the DEFAULT and provider badges, NO PREVIEW), as each state laid them out: one line, whole.
        layouts.forEach { (spoken, layout) ->
            assertEquals("$where: '$spoken' is ONE line", 1, layout.lineCount)
            assertFalse("$where: '$spoken' is not ellipsized", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertTrue(
                "$where: '$spoken' is whole inside its box: ${layout.getLineRight(0)} vs ${layout.size.width}",
                layout.getLineRight(0) <= layout.size.width + 0.5f,
            )
        }
        println("CUSTOM-MEDIA-TILE $where: intent words ${layouts.map { it.first }.distinct()}")
    }

    @Test
    fun `at the Main width and font 1_0 every tile's text is one whole line for all five types`() =
        walk("main font=1.0", mainWidthDp, 1.0f, compact = false)

    @Test
    fun `at the Main width and font 1_3 every tile's text is one whole line for all five types`() =
        walk("main font=1.3", mainWidthDp, 1.3f, compact = false)

    @Test
    fun `at the compact width and font 1_0 every tile's text is one whole line for all five types`() =
        walk("compact font=1.0", compactWidthDp, 1.0f, compact = true)

    @Test
    fun `at the compact width and font 1_3 every tile's text is one whole line for all five types`() =
        walk("compact font=1.3", compactWidthDp, 1.3f, compact = true)

    // ── The footer  ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Saved choices that HAVE a file, so a focused one can be previewed (Select) and, when not the active one, removed (X): the longest legend. */
    private fun savedWithFile(n: Int, label: String) = saved(n, label).copy(localPath = "/saved/$n.png")

    private fun footerStateFor(type: CustomMediaType) = stateFor(type, "saved-1").copy(
        saved = savedLabels.mapIndexed { i, label -> savedWithFile(i + 1, label) },
    )

    private fun mountFooter(widthDp: Float, font: Float, compact: Boolean) {
        val observer: (String, TextLayoutResult) -> Unit = { spoken, layout -> layouts += spoken to layout }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, font),
                LocalMimirTextLayoutObserver provides observer,
            ) {
                MimirTheme {
                    // Taller than the footer needs, so a legend that grows the footer is measured, not squeezed.
                    Box(Modifier.requiredSize(widthDp.dp, 160.dp)) {
                        EnrichFooter(
                            authority = authority,
                            state = shown,
                            focusedKey = focused,
                            compactTypography = true,
                            narrowWidth = compact,
                            onAction = {},
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun checkFooter(where: String, widthDp: Float, state: HomeCustomEnrichState) {
        val widthPx = widthDp * density
        val slot = compose.onAllNodesWithTag(EnrichFooterHintSlotTag, useUnmergedTree = true).fetchSemanticsNodes().single().full()
        val upload = compose.onAllNodesWithTag(EnrichFooterUploadTag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.full()
        val stacks = compose.onAllNodesWithTag(EnrichHintStackTag, useUnmergedTree = true).fetchSemanticsNodes().map { it.full() }
        assertTrue("$where: the footer has a legend", stacks.isNotEmpty())
        stacks.forEach { stack ->
            assertTrue("$where: the legend ends inside the panel: ${dp(stack.right)} of $widthDp dp", stack.right <= widthPx + 0.5f)
            assertTrue(
                "$where: the legend stays in its slot: ${dp(stack.left)}..${dp(stack.right)} vs ${dp(slot.left)}..${dp(slot.right)} dp",
                stack.left >= slot.left - 0.5f && stack.right <= slot.right + 0.5f,
            )
            if (upload != null) {
                assertTrue(
                    "$where: the legend begins a gap (12 dp) after Upload: ${dp(stack.left)} vs Upload's end ${dp(upload.right)} dp",
                    stack.left >= upload.right + 12f * density - 0.5f,
                )
            }
            assertTrue("$where: the legend stack is at most four lines tall: ${dp(stack.height)} dp", dp(stack.height) <= 80f)
        }
        assertTrue("$where: Upload stands inside the panel: ${upload?.let { dp(it.right) }} of $widthDp dp", upload == null || upload.right <= widthPx + 0.5f)
        println(
            "CUSTOM-MEDIA-FOOTER $where: upload ${upload?.let { "${dp(it.left).toInt()}..${dp(it.right).toInt()}" }} dp, " +
                "slot ${dp(slot.left).toInt()}..${dp(slot.right).toInt()} dp, stacks " +
                stacks.joinToString { "${dp(it.left).toInt()}..${dp(it.right).toInt()} x ${dp(it.height).toInt()}" },
        )
    }

    private fun walkFooter(where: String, widthDp: Float, font: Float, compact: Boolean) {
        mountFooter(widthDp, font, compact)
        types.forEach { type ->
            val next = footerStateFor(type)
            val focusCases = listOf(
                "removable saved asset focused" to homeCustomEnrichAssetKey(next, HomeCustomEnrichAsset.Saved(next.saved[2])),
                "Upload focused" to homeCustomEnrichUploadKey(next),
                "Search focused" to homeCustomEnrichSearchKey(next),
            )
            focusCases.forEach { (what, key) ->
                // A bare global write would poison every later Compose class in this fork (AppNotIdle); wrap it.
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    shown = next
                    focused = key
                }
                settle()
                // The Upload pill is not drawn for a clip (nothing to upload), so there is no Upload focus to try there.
                if (type.kind == EnrichMediaKind.CLIP && what == "Upload focused") return@forEach
                checkFooter("$where ${type.label} ($what)", widthDp, next)
            }
        }
        // Every line of text the footer drew (Upload, the legend's lines): one line, whole, not ellipsized.
        layouts.forEach { (spoken, layout) ->
            assertEquals("$where: '$spoken' is ONE line", 1, layout.lineCount)
            assertFalse("$where: '$spoken' is not ellipsized", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertTrue(
                "$where: '$spoken' is whole inside its box, not clipped: ${layout.getLineRight(0)} vs ${layout.size.width}",
                layout.getLineRight(0) <= layout.size.width + 0.5f,
            )
        }
        println("CUSTOM-MEDIA-FOOTER $where: footer words ${layouts.map { it.first }.distinct()}")
    }

    @Test
    fun `at the Main width and font 1_0 the footer legend is never clipped for all five types`() =
        walkFooter("main font=1.0", mainWidthDp, 1.0f, compact = false)

    @Test
    fun `at the Main width and font 1_3 the footer legend is never clipped for all five types`() =
        walkFooter("main font=1.3", mainWidthDp, 1.3f, compact = false)

    @Test
    fun `at the compact width and font 1_0 the footer legend is never clipped for all five types`() =
        walkFooter("compact font=1.0", compactWidthDp, 1.0f, compact = true)

    @Test
    fun `at the compact width and font 1_3 the footer legend is never clipped for all five types`() =
        walkFooter("compact font=1.3", compactWidthDp, 1.3f, compact = true)

    @Test
    fun `a legend breaks only between its segments and drops the separator at the break`() {
        val segments = listOf("A USE", "SELECT PREVIEW", "X REMOVE", "B BACK")
        assertEquals(
            listOf("A USE · SELECT PREVIEW · X REMOVE · B BACK"),
            enrichHintLines(segments) { true },
        )
        assertEquals(
            listOf("A USE · SELECT PREVIEW", "X REMOVE · B BACK"),
            enrichHintLines(segments) { it.length <= 22 },
        )
        assertEquals(
            listOf("A USE", "SELECT PREVIEW", "X REMOVE", "B BACK"),
            enrichHintLines(segments) { it.length <= 14 },
        )
        // A segment that fits nowhere is still never split: it stands alone on its line.
        assertEquals(listOf("A USE", "SELECT PREVIEW"), enrichHintLines(listOf("A USE", "SELECT PREVIEW")) { it.length <= 6 })
        assertEquals(emptyList<String>(), enrichHintLines(emptyList()) { true })
    }
}
