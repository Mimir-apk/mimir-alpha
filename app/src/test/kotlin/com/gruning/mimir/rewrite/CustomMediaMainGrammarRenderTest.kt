package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import kotlin.math.abs
import org.junit.After
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
 * The Customize media page on Main , the REAL [EnrichGallery], [EnrichFooter] and [CustomMediaTopRow], drawn the way this repo rasterises (the view hierarchy into a software
 * bitmap; see [CustomMediaPageCaptureTest]) at the Main width and font 1.0:
 *  - the focused tile's ring is the tile plus the ring's reach and nothing more, and NOTHING inside the tile changes when it takes focus (no wash, no border, no scale, no layout shift);
 *  - every tile's content plate fills its tile above the tile's bar: no plate inset in the tile's glass, for each kind and each kind of Default face;
 *  - exactly ONE tile wears the check mark, whatever is in use, and it stands inside that tile's label bar;
 *  - a focused top-row control (Search, Media, a chip, the Filters mark) rings ONCE, on its plate: the plate plus the ring's reach, as a tile's is;
 *  - Upload and Retry/Stop are unselected etched chips: the metal plate the top row's chips wear, at their plate height and label style, nothing painted into the 48 dp hit box around it.
 * Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMediaMainGrammarRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f
    private val ground = Color(0xFF101418)
    private val authority = DefaultFocusAuthority()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun drawWindow(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(full))
        return full
    }

    private fun Bitmap.pixelArray(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun dp(px: Float) = px / density

    private fun nodes(tag: String): List<SemanticsNode> = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    /** A node's whole box in the window. `boundsInWindow` is clipped by the ancestors, and the gallery scrolls. */
    private fun SemanticsNode.full(): Rect =
        Rect(positionInWindow.x, positionInWindow.y, positionInWindow.x + size.width, positionInWindow.y + size.height)

    private fun rect(tag: String): Rect = nodes(tag).first().full()

    private val isTile = SemanticsMatcher("is a gallery tile") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("custom-media-tile-") == true
    }

    private fun tileBounds(): Map<String, Rect> =
        compose.onAllNodes(isTile, useUnmergedTree = true).fetchSemanticsNodes()
            .associate { it.config.getOrNull(SemanticsProperties.TestTag).orEmpty() to it.full() }

    // ── The gallery ─────────────────────────────────────────────────────────────────────────────────

    private fun heroResult(n: Int, width: Int = 0, height: Int = 0, saved: EnrichSavedAsset? = null) = HomeCustomEnrichResult.Hero(
        CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = "https://cdn2.steamgriddb.com/hero-$n.png",
            ordinal = n,
            width = width,
            height = height,
        ),
        saved,
    )

    private fun savedAsset(n: Int) = EnrichSavedAsset(
        assetId = "saved-$n",
        source = EnrichSavedSource.SCREENSCRAPER,
        label = "ScreenScraper · 600×800",
        localPath = "",
        previewPath = "",
        width = 600,
        height = 800,
        bytes = 1L,
        addedAt = n.toLong(),
    )

    private fun galleryState(
        kind: EnrichMediaKind,
        defaultArt: HomeCustomEnrichDefaultArt,
        activeId: String? = null,
        saved: List<EnrichSavedAsset> = emptyList(),
        results: List<HomeCustomEnrichResult> = listOf(heroResult(1), heroResult(2, 1920, 1080)),
    ) = HomeCustomEnrichState(
        sessionId = "grammar-$kind-${defaultArt::class.simpleName}",
        kind = kind,
        cacheKey = "game-1",
        gameTitle = "Metroid Fusion",
        queryDraft = "Metroid Fusion",
        saved = saved,
        activeAssetId = activeId,
        results = results,
        defaultArt = defaultArt,
        searchStatus = HomeCustomEnrichSearchStatus.Complete(results.size),
    )

    private var shown by mutableStateOf(galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable))
    private var focused by mutableStateOf<FocusKey?>(null)

    private fun mountGallery() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MimirTheme {
                    // The ring reaches 13.5 dp past a tile: leave it room above and to the left, on the page's ground.
                    Box(Modifier.fillMaxSize().background(ground).padding(start = 16.dp, top = 40.dp)) {
                        Box(Modifier.requiredSize(656.dp, (homeCustomEnrichGalleryMinHeightDp(shown.kind, false) + 24).dp)) {
                            EnrichGallery(
                                authority = authority,
                                state = shown,
                                focusedKey = focused,
                                compactSquare = false,
                                onAction = {},
                                onExpandPreview = {},
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun show(state: HomeCustomEnrichState, focus: FocusKey?) {
        // A bare global write would poison every later Compose class in this fork (AppNotIdle); wrap it.
        Snapshot.withMutableSnapshot {
            shown = state
            focused = focus
        }
        settle()
    }

    private class PxBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        override fun toString() = "$left,$top..$right,$bottom"
    }

    private fun channelDiff(a: Int, b: Int): Int = maxOf(
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
    )

    /** The smallest box holding every pixel that differs between the two frames, left of [xMax]. */
    private fun diffBox(before: IntArray, after: IntArray, width: Int, height: Int, xMax: Int, threshold: Int = 3, yMax: Int = Int.MAX_VALUE, xMin: Int = 0): PxBox? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until minOf(height, yMax)) for (x in maxOf(0, xMin) until minOf(width, xMax)) {
            if (channelDiff(before[y * width + x], after[y * width + x]) > threshold) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < 0) null else PxBox(left, top, right, bottom)
    }

    @Test
    fun `the focused tile's ring is the tile plus the ring's reach, and nothing inside the tile changes`() {
        val state = galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable)
        mountGallery()
        show(state, focus = null)
        settle()
        val tilesBefore = tileBounds()
        val auto = tilesBefore.getValue(customMediaTileTag("auto"))
        val before = drawWindow()
        val width = before.width
        val height = before.height
        val beforePixels = before.pixelArray()

        show(state, focus = homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
        settle()
        val tilesAfter = tileBounds()
        val afterPixels = drawWindow().pixelArray()

        // The focused tile occupies the same layout box as every other: no scale, no layout shift, for any tile.
        assertEquals("no tile moved or grew when the Default tile took focus", tilesBefore, tilesAfter)

        // What changed on screen is the ring and only the ring. The right-hand cue rune and the tiles beyond the first are left of nothing
        // this checks: the frame is read up to a little past the tile's right edge.
        val box = requireNotNull(diffBox(beforePixels, afterPixels, width, height, xMax = (auto.right + 40f * density).toInt())) { "taking focus drew nothing" }
        val reachPx = 13.5f * density // the outermost stroke's 10 dp outset plus half its 7 dp width
        val tolerance = 3
        assertTrue(
            "the ring is as wide as the tile plus the ring's reach each side: ${box.width} px vs ${auto.width + 2 * reachPx} px (tile ${dp(auto.width)} dp)",
            abs(box.width - (auto.width + 2 * reachPx)) <= tolerance,
        )
        assertTrue(
            "the ring is as tall as the tile plus the ring's reach each side: ${box.height} px vs ${auto.height + 2 * reachPx} px (tile ${dp(auto.height)} dp)",
            abs(box.height - (auto.height + 2 * reachPx)) <= tolerance,
        )
        // Inside the tile (past its 1 dp resting outline) not one pixel changed: no wash, no inner border, no plate stepping in. The four
        // corners are left out: the ring's innermost stroke follows the tile's rounded corner, and the corner of any inset rectangle
        // lies outside that arc, on the ring.
        val inset = (reachPx + 3f * density).toInt()
        val cornerPx = (MimirSpacing.radiusCard.value * 2f * density).toInt()
        val tileLeft = box.left + reachPx.toInt()
        val tileTop = box.top + reachPx.toInt()
        val tileRight = box.right - reachPx.toInt()
        val tileBottom = box.bottom - reachPx.toInt()
        var changedInside = 0
        val changedAt = ArrayList<String>()
        for (y in (box.top + inset)..(box.bottom - inset)) for (x in (box.left + inset)..(box.right - inset)) {
            val nearCornerX = x < tileLeft + cornerPx || x > tileRight - cornerPx
            val nearCornerY = y < tileTop + cornerPx || y > tileBottom - cornerPx
            if (nearCornerX && nearCornerY) continue
            if (channelDiff(beforePixels[y * width + x], afterPixels[y * width + x]) > 2) {
                changedInside += 1
                if (changedAt.size < 8) changedAt += "($x,$y rel ${x - tileLeft},${y - tileTop})"
            }
        }
        assertEquals("pixels inside the tile that changed with focus, first at $changedAt (tile ${tileLeft},${tileTop} to ${tileRight},${tileBottom})", 0, changedInside)
        println(
            "CM263-RING tile ${dp(auto.width)}x${dp(auto.height)} dp, ring box ${dp(box.width.toFloat())}x${dp(box.height.toFloat())} dp " +
                "(reach ${dp(reachPx)} dp each side), offset window->frame ${box.left + reachPx - auto.left},${box.top + reachPx - auto.top} px, inside changed $changedInside",
        )
    }

    @Test
    fun `every tile's content plate fills its tile for each kind and each kind of Default face`() {
        val cases = listOf(
            EnrichMediaKind.HERO to HomeCustomEnrichDefaultArt.Unavailable,
            EnrichMediaKind.HERO to HomeCustomEnrichDefaultArt.Automatic("file:///nowhere/hero.png"),
            EnrichMediaKind.ICON to HomeCustomEnrichDefaultArt.ConsoleLineArt("gba"),
            EnrichMediaKind.ICON to HomeCustomEnrichDefaultArt.TitleBook("Metroid Fusion", "gba"),
            EnrichMediaKind.COVER to HomeCustomEnrichDefaultArt.TitleBook("Metroid Fusion", "gba"),
            EnrichMediaKind.COVER to HomeCustomEnrichDefaultArt.Automatic("file:///nowhere/cover.png"),
            EnrichMediaKind.CLIP to HomeCustomEnrichDefaultArt.ClipAutomatic(null),
            EnrichMediaKind.TOME_PHYSICAL to HomeCustomEnrichDefaultArt.TitleBook("Metroid Fusion", "gba"),
        )
        mountGallery()
        cases.forEach { (kind, art) ->
            val state = galleryState(kind, art)
            show(state, focus = homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
            val tiles = tileBounds()
            val plates = nodes(CustomMediaTilePlateTag).map { it.full() }
            assertTrue("$kind/${art::class.simpleName}: Default and both found tiles are drawn: ${tiles.keys}", tiles.size == 3)
            val bars = nodes(CustomMediaTileBarTag).map { it.full() }
            tiles.forEach { (tag, tile) ->
                val mine = plates.filter { it.left >= tile.left - 0.5f && it.right <= tile.right + 0.5f && it.top >= tile.top - 0.5f && it.bottom <= tile.bottom + 0.5f }
                assertTrue("$kind/${art::class.simpleName} $tag: the tile has a content plate", mine.isNotEmpty())
                val bar = bars.single { it.center.x in tile.left..tile.right && it.center.y in tile.top..tile.bottom }
                assertTrue("$kind/${art::class.simpleName} $tag: the bar runs the tile's width to its bottom: bar $bar vs tile $tile", abs(bar.left - tile.left) <= 0.5f && abs(bar.right - tile.right) <= 0.5f && abs(bar.bottom - tile.bottom) <= 0.5f)
                mine.forEach { plate ->
                    assertTrue(
                        "$kind/${art::class.simpleName} $tag: the plate is the whole art, from the tile's edges to the bar, not inset in it: plate $plate vs tile $tile, bar $bar",
                        abs(plate.left - tile.left) <= 0.5f && abs(plate.right - tile.right) <= 0.5f &&
                            abs(plate.top - tile.top) <= 0.5f && abs(plate.bottom - bar.top) <= 0.5f,
                    )
                }
            }
        }
    }

    @Test
    fun `exactly one tile wears the check mark, whichever choice is in use`() {
        val saved = listOf(savedAsset(1), savedAsset(2))
        val found = listOf(heroResult(1), heroResult(2, 1920, 1080), heroResult(3, 1280, 720))
        val boundToSaved1 = heroResult(4, 600, 800, saved = saved[0])
        val cases = linkedMapOf(
            "Default in use, two saved, three unsaved results" to galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable, null, saved, found),
            "Default in use, no saved, two unsaved results" to galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable, null, emptyList(), found.take(2)),
            "a saved tile in use, unsaved results beside it" to galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable, "saved-2", saved, found),
            "a saved tile in use and the same record again among the results" to
                galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable, "saved-1", saved, found + boundToSaved1),
            "a result bound to a record the Saved rail does not hold, in use" to
                galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable, "saved-9", saved, found + heroResult(5, 600, 800, saved = savedAsset(9))),
        )
        mountGallery()
        cases.forEach { (what, state) ->
            show(state, focus = homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
            val ticks = nodes(CustomMediaTileCheckTag)
            assertEquals("$what: the check mark stands on exactly one tile", 1, ticks.size)
            val inUse = state.assets.filter { homeCustomEnrichAssetIsActive(state, it) }
            assertEquals("$what: exactly one asset is in use: ${inUse.map { it.stableId }}", 1, inUse.size)
            // The ✓ is on the tile of the asset in use.
            val tile = tileBounds().getValue(customMediaTileTag(inUse.single().stableId))
            val tick = ticks.single().full()
            assertTrue(
                "$what: the check stands inside the tile of the asset in use: tick $tick, tile $tile",
                tick.left >= tile.left - 0.5f && tick.right <= tile.right + 0.5f && tick.top >= tile.top - 0.5f && tick.bottom <= tile.bottom + 0.5f,
            )
            // ...and on its label bar, whole: the bar is the tile's own, inside the tile, and holds the check from edge to edge.
            val bar = nodes(CustomMediaTileBarTag).map { it.full() }.single { it.center.x in tile.left..tile.right && it.center.y in tile.top..tile.bottom }
            assertTrue("$what: the bar stands inside the tile: bar $bar, tile $tile", bar.bottom <= tile.bottom + 0.5f && bar.top >= tile.top - 0.5f)
            assertTrue(
                "$what: the check stands on its bar, not at or below its edge: tick $tick, bar $bar",
                tick.left >= bar.left - 0.5f && tick.right <= bar.right + 0.5f && tick.top >= bar.top - 0.5f && tick.bottom <= bar.bottom + 0.5f,
            )
        }
    }

    // ── The 10-09 sweep: the column, the chevrons and the fixed bar ─────────────────────────────────

    @Test
    fun `the strip's first tile starts on the column's edge, and no chevron shows while the strip fits`() {
        val state = galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable)
        mountGallery()
        show(state, focus = null)
        val first = tileBounds().getValue(customMediaTileTag("auto"))
        // The harness seats the gallery's column 16 dp in from the root's edge (wherever the window puts the root).
        val columnLeft = compose.onRoot().fetchSemanticsNode().positionInWindow.x + 16f * density
        assertEquals("the first tile starts on the column's edge: ${dp(first.left)} dp vs ${dp(columnLeft)} dp", columnLeft, first.left, 1.5f)
        assertEquals("a strip that fits shows no chevron", 0, nodes(CustomMediaGalleryCueTag).size)
    }

    @Test
    fun `a chevron shows only on a side the strip can still scroll to, and stands outside the column`() {
        val many = (1..8).map { heroResult(it, 1920, 1080) }
        val state = galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable, results = many)
        mountGallery()
        show(state, focus = homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
        val atStart = nodes(CustomMediaGalleryCueTag)
        assertEquals("at the start only the forward chevron shows", 1, atStart.size)
        val columnLeft = compose.onRoot().fetchSemanticsNode().positionInWindow.x + 16f * density
        val columnRight = columnLeft + 656f * density
        assertTrue("the forward chevron stands right of the column's edge: ${atStart.single().full()}", atStart.single().full().left >= columnRight)

        show(state, focus = homeCustomEnrichAssetKey(state, state.assets.last()))
        settle()
        val atEnd = nodes(CustomMediaGalleryCueTag)
        assertEquals("at the end only the backward chevron shows", 1, atEnd.size)
        assertTrue("the backward chevron stands left of the column's edge: ${atEnd.single().full()}", atEnd.single().full().right <= columnLeft + 0.5f)
    }

    @Test
    fun `focusing a saved tile moves nothing in the strip, its bar, its dot or the remove mark's seat`() {
        val state = galleryState(EnrichMediaKind.HERO, HomeCustomEnrichDefaultArt.Unavailable, saved = listOf(savedAsset(1), savedAsset(2)), results = emptyList())
        mountGallery()
        show(state, focus = null)
        fun layout() = listOf(
            tileBounds().toString(),
            nodes(CustomMediaTileBarTag).map { it.full() }.toString(),
            nodes(CustomMediaTileDotTag).map { it.full() }.toString(),
            nodes(CustomMediaTileRemoveTag).map { it.full() }.toString(),
        )
        val before = layout()
        assertEquals("both saved tiles carry a dot and the remove mark's seat, focused or not", 2, nodes(CustomMediaTileDotTag).size)
        assertEquals(2, nodes(CustomMediaTileRemoveTag).size)
        show(state, focus = homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(state.saved.first())))
        assertEquals("the tile, the bars, the dots and the seats stand where they stood", before, layout())
    }

    // ── The footer's chips ──────────────────────────────────────────────────────────────────────────

    private val menu = HomeTileMenuState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 1L,
            addedAtMillis = 10L,
            title = "Metroid Fusion",
        ),
        removable = true,
    )
    private val types = homeTileCustomMediaTypes(menu)

    private fun pageState(searchStatus: HomeCustomEnrichSearchStatus) = HomeCustomEnrichState(
        sessionId = "grammar-footer-${searchStatus::class.simpleName}",
        kind = EnrichMediaKind.HERO,
        cacheKey = mediaStorageKey("game-1", MediaState.SHELF),
        gameTitle = "Metroid Fusion",
        queryDraft = "Metroid Fusion",
        searchStatus = searchStatus,
        mediaTypes = types,
    )

    private var footerState by mutableStateOf(pageState(HomeCustomEnrichSearchStatus.Idle))
    private var footerFocus by mutableStateOf<FocusKey?>(null)
    private var referenceSize by mutableStateOf<DpSize?>(null)
    private var rowWidthDp by mutableStateOf(656f)

    /** The words drawn through the text intent (Upload, Retry, Stop), as each was laid out. */
    private val observed = ArrayList<Pair<String, TextLayoutResult>>()

    private fun mountFooter() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        val observer: (String, TextLayoutResult) -> Unit = { spoken, layout -> observed += spoken to layout }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f), LocalMimirTextLayoutObserver provides observer) {
                MimirTheme {
                    Column(Modifier.fillMaxSize().background(ground).padding(start = 16.dp, top = 24.dp)) {
                        Box(Modifier.requiredSize(rowWidthDp.dp, 56.dp)) {
                            CustomMediaTopRow(authority, footerState, footerFocus, {}, Modifier.fillMaxWidth())
                        }
                        Box(Modifier.requiredSize(656.dp, 72.dp)) {
                            EnrichFooter(
                                authority = authority,
                                state = footerState,
                                focusedKey = footerFocus,
                                compactTypography = false,
                                narrowWidth = false,
                                onAction = {},
                            )
                        }
                        // The reference: the etched chip surface alone, at the size the footer's plate measured.
                        referenceSize?.let { size ->
                            Box(Modifier.size(size).testTag("reference-plate").mimirEtchedChipSurface(LocalMimirPalette.current, warmed = false))
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun textsUnder(root: SemanticsNode): List<Pair<String, TextLayoutResult>> {
        val out = ArrayList<Pair<String, TextLayoutResult>>()
        fun walk(node: SemanticsNode) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = ArrayList<TextLayoutResult>()
                action.invoke(results)
                results.firstOrNull()?.let { layout -> out += layout.layoutInput.text.text to layout }
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    /** One chip of the footer, as drawn: the plate is the metal plate of the top row's chips and nothing is painted into the hit box around it. */
    private fun checkChip(where: String, plateTag: String, hitTag: String, labelText: String) {
        val plate = rect(plateTag)
        val hit = rect(hitTag)
        assertTrue("$where: the hit box is at least 48 dp each way: ${dp(hit.width)} x ${dp(hit.height)} dp", dp(hit.width) >= 47.9f && dp(hit.height) >= 47.9f)

        // The plate is as tall as the plate of every chip of the top row (they share one height).
        val chipPlates = nodes(AchievementFilterChipPlateTag).map { it.full() }
        assertTrue("$where: the top row's chips are drawn", chipPlates.isNotEmpty())
        chipPlates.forEach { chip ->
            assertTrue("$where: the plate is the chips' height: ${dp(plate.height)} vs ${dp(chip.height)} dp", abs(plate.height - chip.height) <= 0.5f)
        }
        // The label is set in the chip label style.
        val words = textsUnder(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        val chipLabel = requireNotNull(words.firstOrNull { it.first == customMediaTypeChipText(footerState.currentMediaType()) }) { "$where: the Media chip's label was laid out: ${words.map { it.first }}" }.second
        val mine = requireNotNull(observed.lastOrNull { it.first == labelText }) { "$where: '$labelText' was laid out: ${observed.map { it.first }.distinct()}" }.second
        assertEquals("$where: the label is the chips' size", chipLabel.layoutInput.style.fontSize, mine.layoutInput.style.fontSize)
        assertEquals("$where: the label is the chips' weight", chipLabel.layoutInput.style.fontWeight, mine.layoutInput.style.fontWeight)

        // Draw the reference surface at the plate's size, then read both.
        Snapshot.withMutableSnapshot { referenceSize = DpSize(dp(plate.width).dp, dp(plate.height).dp) }
        settle()
        val reference = rect("reference-plate")
        val image = drawWindow()
        val px = image.pixelArray()
        val w = image.width
        fun at(x: Float, y: Float) = px[y.toInt() * w + x.toInt()]
        val groundPixel = ground.let { android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }

        // Not a fill: between the 48 dp box and the plate (above it) nothing is painted, so the page's ground shows.
        val gapTop = hit.top + 1f * density
        val gapBottom = plate.top - 1f * density
        assertTrue("$where: there is a gap between the hit box and the plate to read: ${dp(gapBottom - gapTop)} dp", gapBottom - gapTop > 2f * density)
        var y = gapTop
        while (y <= gapBottom) {
            assertTrue(
                "$where: the hit box is not filled outside the plate: pixel at y=${dp(y)} dp is ${Integer.toHexString(at(plate.center.x, y))}, the ground is ${Integer.toHexString(groundPixel)}",
                channelDiff(at(plate.center.x, y), groundPixel) <= 3,
            )
            y += density
        }
        // The plate is metal, not the ground: along its centre line, from its edge in to its padding, something other than the ground is drawn (its rim).
        var brightest = 0
        var probe = 0f
        while (probe <= 6f) {
            brightest = maxOf(brightest, channelDiff(at(plate.left + probe * density, plate.center.y), groundPixel))
            probe += 0.5f
        }
        assertTrue("$where: the plate is drawn: its edge differs from the ground by $brightest/255", brightest > 30)

        // The plate's own surface, left of its content, is the etched chip surface's: the same pixels as the reference box.
        var compared = 0
        var worst = 0
        var cy = -4f
        while (cy <= 4f) {
            var cx = 4f
            while (cx <= 9f) {
                val mineAt = at(plate.left + cx * density, plate.center.y + cy * density)
                val referenceAt = at(reference.left + cx * density, reference.center.y + cy * density)
                worst = maxOf(worst, channelDiff(mineAt, referenceAt))
                compared += 1
                cx += 1f
            }
            cy += 1f
        }
        assertTrue("$where: $compared sampled pixels of the plate match the etched chip surface; the worst differs by $worst/255", worst <= 10)
        println("CM263-CHIP $where: hit ${dp(hit.width)}x${dp(hit.height)} dp, plate ${dp(plate.width)}x${dp(plate.height)} dp, $compared pixels vs the etched surface, worst $worst/255")
        Snapshot.withMutableSnapshot { referenceSize = null }
        settle()

        // Focused, the chip rings ONCE, on the plate: the ring's box is the plate plus the ring's reach, as a tile's is (no second rim floating off the plate).
        settle()
        val plateNow = rect(plateTag)
        val unfocused = drawWindow()
        val unfocusedPixels = unfocused.pixelArray()
        val key = if (plateTag == EnrichFooterUploadPlateTag) homeCustomEnrichUploadKey(footerState) else homeCustomEnrichRetryKey(footerState)
        Snapshot.withMutableSnapshot { footerFocus = key }
        settle()
        settle()
        val focusedPixels = drawWindow().pixelArray()
        val ring = requireNotNull(diffBox(unfocusedPixels, focusedPixels, unfocused.width, unfocused.height, xMax = (plateNow.right + 60f * density).toInt())) { "$where: focusing the chip drew nothing" }
        val reachPx = 13.5f * density
        assertTrue(
            "$where: the ring is the plate plus the ring's reach each side, wide: ${ring.width} px vs ${plateNow.width + 2 * reachPx} px",
            abs(ring.width - (plateNow.width + 2 * reachPx)) <= 3,
        )
        assertTrue(
            "$where: the ring is the plate plus the ring's reach each side, tall: ${ring.height} px vs ${plateNow.height + 2 * reachPx} px",
            abs(ring.height - (plateNow.height + 2 * reachPx)) <= 3,
        )
        println("CM263-CHIP $where: focused ring box ${dp(ring.width.toFloat())}x${dp(ring.height.toFloat())} dp around the ${dp(plateNow.width)}x${dp(plateNow.height)} dp plate")
        Snapshot.withMutableSnapshot { footerFocus = null }
        settle()
    }

    @Test
    fun `Upload is an unselected etched chip, never a filled pill`() {
        mountFooter()
        checkChip("Upload", EnrichFooterUploadPlateTag, EnrichFooterUploadTag, "Upload image")
    }

    @Test
    fun `Retry and Stop wear the same etched chip as Upload`() {
        mountFooter()
        Snapshot.withMutableSnapshot { footerState = pageState(HomeCustomEnrichSearchStatus.Failed("Provider offline")) }
        settle()
        checkChip("Retry", EnrichFooterRetryPlateTag, EnrichFooterRetryTag, "Retry")
        Snapshot.withMutableSnapshot { footerState = pageState(HomeCustomEnrichSearchStatus.Searching("SteamGridDB", 0)) }
        settle()
        checkChip("Stop", EnrichFooterRetryPlateTag, EnrichFooterRetryTag, "Stop")
    }

    @Test
    fun `the page has no Back chip anywhere in its top row`() {
        mountFooter()
        assertTrue("the top row is drawn", nodes(CustomMediaTopRowTag).isNotEmpty())
        assertFalse("no Back chip", nodes("custom-media-back").isNotEmpty())
        val words = textsUnder(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()).map { it.first }
        assertTrue("nothing in the row says Back: $words", words.none { it.startsWith("‹") || it == "Back" })
        val search = rect(CustomMediaSearchTag)
        val media = rect(CustomMediaTypeChipTag)
        val row = rect(CustomMediaTopRowTag)
        assertTrue("the row starts with Search", search.left <= row.left + 0.5f)
        assertTrue("and Media follows it", media.left >= search.right - 0.5f)
    }

    // ── The top row's rings ─────────────────────────────────────────────────────────────────────────

    /** The plate of the chip whose 48 dp target carries [targetTag]. */
    private fun chipPlate(targetTag: String): Rect {
        val target = rect(targetTag)
        return nodes(AchievementFilterChipPlateTag).map { it.full() }.first {
            it.left >= target.left - 0.5f && it.right <= target.right + 0.5f && it.top >= target.top - 0.5f && it.bottom <= target.bottom + 0.5f
        }
    }

    /** Focusing [key] rings ONCE on [plate]: the ring's box is the plate plus the ring's reach each side, so no second rim floats off the plate. */
    private fun assertRingsOnPlate(where: String, plate: Rect, key: FocusKey) {
        Snapshot.withMutableSnapshot { footerFocus = null }
        settle()
        settle()
        val unfocused = drawWindow()
        val before = unfocused.pixelArray()
        Snapshot.withMutableSnapshot { footerFocus = key }
        settle()
        settle()
        val after = drawWindow().pixelArray()
        val reachPx = 13.5f * density
        // Only the top row's band, around the plate: focusing a top-row control also changes the footer's legend (A OPEN, A EDIT), which is not the ring.
        val slackPx = (8f * density).toInt()
        val ring = requireNotNull(
            diffBox(
                before,
                after,
                unfocused.width,
                unfocused.height,
                xMax = (plate.right + reachPx).toInt() + slackPx,
                yMax = (90f * density).toInt(),
                xMin = (plate.left - reachPx).toInt() - slackPx,
            ),
        ) { "$where: focusing drew nothing" }
        assertTrue(
            "$where: the ring is the plate plus the ring's reach each side, wide: ${ring.width} px vs ${plate.width + 2 * reachPx} px (plate ${dp(plate.width)} dp)",
            abs(ring.width - (plate.width + 2 * reachPx)) <= 3,
        )
        assertTrue(
            "$where: the ring is the plate plus the ring's reach each side, tall: ${ring.height} px vs ${plate.height + 2 * reachPx} px (plate ${dp(plate.height)} dp)",
            abs(ring.height - (plate.height + 2 * reachPx)) <= 3,
        )
        // Centred on the plate: the ring's centre is the plate's.
        assertTrue(
            "$where: the ring is centred on the plate: ring centre ${(ring.left + ring.right) / 2f},${(ring.top + ring.bottom) / 2f} vs plate ${plate.center}",
            abs((ring.left + ring.right) / 2f - plate.center.x) <= 3f && abs((ring.top + ring.bottom) / 2f - plate.center.y) <= 3f,
        )
        println("CM263B-TOPRING $where: ring ${dp(ring.width.toFloat())}x${dp(ring.height.toFloat())} dp around the ${dp(plate.width)}x${dp(plate.height)} dp plate")
        Snapshot.withMutableSnapshot { footerFocus = null }
        settle()
    }

    @Test
    fun `a focused top-row chip or search rings once, on its plate`() {
        mountFooter()
        val state = footerState
        val firstChip = state.visibleFilters().first()
        assertRingsOnPlate("Search (the name)", rect(CustomMediaSearchPlateTag), homeCustomEnrichSearchKey(state))
        assertRingsOnPlate("Media", chipPlate(CustomMediaTypeChipTag), homeCustomEnrichMediaKey(state))
        assertRingsOnPlate("chip ${firstChip.key}", chipPlate(customMediaFilterChipTag(firstChip.key)), homeCustomEnrichFilterKey(state, firstChip))
        // The plate is NOT the whole 48 dp target: the chips' plates are shorter than their targets, which is why the ring's source mattered.
        assertTrue("a chip's plate is shorter than its 48 dp target", chipPlate(CustomMediaTypeChipTag).height < rect(CustomMediaTypeChipTag).height - 1f)
    }

    @Test
    fun `the folded Filters mark and the collapsed Search mark ring once, on their plates`() {
        mountFooter()
        Snapshot.withMutableSnapshot { rowWidthDp = 240f }
        settle()
        settle()
        val state = footerState
        assertTrue("the three chips fold into the Filters mark at 240 dp", nodes(CustomMediaFiltersTag).isNotEmpty())
        assertEquals("Search is the 48 dp mark here", true, dp(rect(CustomMediaSearchPlateTag).width) <= 41f)
        assertRingsOnPlate("Filters mark", rect(CustomMediaFiltersPlateTag), homeCustomEnrichFiltersKey(state))
        assertRingsOnPlate("Search (the mark)", rect(CustomMediaSearchPlateTag), homeCustomEnrichSearchKey(state))
        assertRingsOnPlate("Media (beside the Filters mark)", chipPlate(CustomMediaTypeChipTag), homeCustomEnrichMediaKey(state))
    }
}
