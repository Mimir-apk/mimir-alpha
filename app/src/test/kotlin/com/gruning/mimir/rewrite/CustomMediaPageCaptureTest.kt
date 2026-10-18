package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the Customize media page : the real [HomeCustomEnrichPickerOverlay] at the Main width (853 x 480 dp) and font 1.0, with Icon art selected, with Hero art selected,
 * and with the Media panel open over Icon art. Drawn the way this repo rasterises (the view hierarchy into a software bitmap; see [ActivityHubRowCaptureTest]). The PNGs land in
 * `app/build/custommedia/` (the lead copies them out). The class runs as API 30, the level at which the platform cannot blur, because a software canvas cannot draw the glass sheet's
 * RenderNode layers. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMediaPageCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    private var density = 2f
    private val authority = DefaultFocusAuthority()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private val menu = HomeTileMenuState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("gba/metroid-fusion")),
            rank = 1L,
            addedAtMillis = 10L,
            title = "Metroid Fusion",
        ),
        removable = true,
    )
    private val types = homeTileCustomMediaTypes(menu)

    private fun saved(n: Int) = EnrichSavedAsset(
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

    private fun stateFor(
        label: String,
        panelOpen: Boolean = false,
        filtersPanelOpen: Boolean = false,
        defaultArt: HomeCustomEnrichDefaultArt = HomeCustomEnrichDefaultArt.ConsoleLineArt("gba"),
        savedChoices: List<EnrichSavedAsset> = listOf(saved(1), saved(2)),
        results: List<HomeCustomEnrichResult> = emptyList(),
    ): HomeCustomEnrichState {
        val type = types.first { it.label == label }
        // Icon art is the merged picker : kind ICON, state SHELF, a Type filter of every shape.
        val merged = type.kind == EnrichMediaKind.ICON && type.mediaState == MediaState.SHELF
        return HomeCustomEnrichState(
            sessionId = "capture-$label",
            kind = type.kind,
            filters = if (merged) homeCustomEnrichIconArtFilters() else homeCustomEnrichDefaultFilters(type.kind),
            shelfIconArt = merged,
            cacheKey = mediaStorageKey("gba/metroid-fusion", type.mediaState),
            gameTitle = "Metroid Fusion",
            queryDraft = "Metroid Fusion",
            saved = savedChoices,
            results = results,
            searchStatus = if (results.isEmpty()) HomeCustomEnrichSearchStatus.Idle else HomeCustomEnrichSearchStatus.Complete(results.size),
            defaultArt = defaultArt,
            mediaState = type.mediaState,
            mediaTypes = types,
            mediaPanelOpen = panelOpen,
            filtersPanelOpen = filtersPanelOpen,
        )
    }

    private fun settleFrame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun drawWindow(): android.graphics.Bitmap {
        val decor = compose.activity.window.decorView
        val full = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(full))
        return full
    }

    private fun assertDrew(image: android.graphics.Bitmap, what: String) {
        val ground = image.getPixel(2, image.height - 2)
        var differing = 0
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) if (image.getPixel(x, y) != ground) differing += 1
        assertTrue("$what drew something: $differing sampled pixels differ from the ground", differing > 1_000)
    }

    private fun write(image: android.graphics.Bitmap, fileName: String, alsoTo: String? = null) {
        val outDir = File("build/custommedia").apply { mkdirs() }.takeIf { it.isDirectory } ?: temp.root
        val out = File(outDir, fileName)
        out.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("CUSTOM-MEDIA-PAGE wrote ${out.absolutePath} (${out.length()} bytes, ${image.width}x${image.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
        // The renders the lead looks at  are copied where the brief names them.
        alsoTo?.let { path ->
            val copy = File(path).apply { parentFile?.mkdirs() }
            copy.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            println("CUSTOM-MEDIA-PAGE copied to ${copy.absolutePath}")
            assertTrue("the PNG was copied: $path", copy.isFile && copy.length() > 1_000L)
        }
    }

    /** A 16:9 gradient picture on disk, standing in for a found Hero art result's preview. */
    private fun artFile(name: String, from: Int, to: Int): String = artFileOf(name, from, to, 320, 180)

    /** A gradient picture of the given pixel size on disk, with a rim so a crop or a bar would show. */
    private fun artFileOf(name: String, from: Int, to: Int, width: Int, height: Int): String {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), from, to, android.graphics.Shader.TileMode.CLAMP)
        }
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val rim = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (minOf(width, height) / 20f)
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawRect(rim.strokeWidth / 2f, rim.strokeWidth / 2f, width - rim.strokeWidth / 2f, height - rim.strokeWidth / 2f, rim)
        val file = File(temp.root, name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    private fun savedWithArt(n: Int, width: Int, height: Int, art: String, label: String = "ScreenScraper · ${width}×$height") = EnrichSavedAsset(
        assetId = "saved-$n",
        source = EnrichSavedSource.SCREENSCRAPER,
        label = label,
        localPath = art,
        previewPath = art,
        width = width,
        height = height,
        bytes = 1L,
        addedAt = n.toLong(),
    )

    private fun foundHero(n: Int, width: Int, height: Int, art: String) = HomeCustomEnrichResult.Hero(
        CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = "https://cdn2.steamgriddb.com/hero-$n.png",
            resolvedTitle = "Metroid Fusion",
            ordinal = n,
            width = width,
            height = height,
            previewPath = art,
        ),
    )

    /** Coil decodes off the test's clock: give the two pictures a moment to land before the frame is read. */
    private fun waitForArt() {
        repeat(20) {
            Thread.sleep(50)
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(100L)
            compose.waitForIdle()
        }
    }

    private fun capture(
        state: HomeCustomEnrichState,
        fileName: String,
        widthDp: Float = 853f,
        heightDp: Float = 480f,
        font: Float = 1.0f,
        alsoTo: String? = null,
        afterMount: () -> Unit = {},
        writePng: Boolean = true,
    ) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        HomeCustomEnrichPickerOverlay(
                            authority = authority,
                            state = state,
                            onAction = {},
                            onQueryChange = {},
                            onKeyboardDone = {},
                        )
                    }
                }
            }
        }
        settleFrame()
        settleFrame()
        afterMount()
        settleFrame()
        val row = compose.onAllNodesWithTag(CustomMediaTopRowTag, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the page's top row is on the screen", row.isNotEmpty())
        val box = row.first().boundsInWindow
        println("CUSTOM-MEDIA-PAGE ${state.kind} ${state.mediaState}: top row ${box.left},${box.top} to ${box.right},${box.bottom} px")
        assertFooterInsidePanel(fileName)
        assertCuesInsidePanel(fileName)
        if (!writePng) return
        val image = drawWindow()
        assertDrew(image, "the page")
        write(image, fileName, alsoTo)
    }

    /** The strip's overflow chevrons stand inside the panel's clip, tip and round cap included (round 3: at compact the tip was 3 dp under it). */
    private fun assertCuesInsidePanel(what: String) {
        val panel = compose.onAllNodesWithTag(HomeCustomEnrichPanelTestTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val capPx = 0.8f * density
        compose.onAllNodesWithTag(CustomMediaGalleryCueTag, useUnmergedTree = true).fetchSemanticsNodes().forEach { cue ->
            val b = cue.boundsInWindow
            assertTrue(
                "$what: the cue stands inside the panel's clip: ${b.left}..${b.right} px vs ${panel.left}..${panel.right} px",
                b.left - capPx >= panel.left && b.right + capPx <= panel.right,
            )
        }
    }

    /**
     * the earlier update, in the real page: the footer's legend (however many whole-segment lines it took) stands inside the glass panel that clips it and begins after Upload,
     * so the page that is about to be drawn never shows "A USE · SELECT PREVIEW · B BAC".
     */
    private fun assertFooterInsidePanel(what: String) {
        val panel = compose.onAllNodesWithTag(HomeCustomEnrichPanelTestTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val upload = compose.onAllNodesWithTag(EnrichFooterUploadTag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        val stacks = compose.onAllNodesWithTag(EnrichHintStackTag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInWindow }
        assertTrue("$what: the footer has a legend", stacks.isNotEmpty())
        stacks.forEach { stack ->
            assertTrue("$what: the legend ends inside the panel: ${stack.right} vs ${panel.right} px", stack.right <= panel.right + 0.5f)
            assertTrue("$what: the legend stays above the panel's bottom: ${stack.bottom} vs ${panel.bottom} px", stack.bottom <= panel.bottom + 0.5f)
            if (upload != null) {
                assertTrue("$what: the legend begins after Upload: ${stack.left} vs ${upload.right} px", stack.left >= upload.right - 0.5f)
            }
        }
        println("CUSTOM-MEDIA-PAGE $what: footer legend stacks " + stacks.joinToString { "${it.left},${it.top} to ${it.right},${it.bottom}" } + " in panel ${panel.left},${panel.top} to ${panel.right},${panel.bottom} px; upload ${upload?.right}")
    }

    @Test
    fun `the page with Icon art selected is captured at the Main width and font 1_0`() =
        capture(stateFor("Icon art"), "custommedia-shelves-1.0.png")

    @Test
    fun `the page with Hero art selected is captured at the Main width and font 1_0`() =
        capture(stateFor("Hero art"), "custommedia-hero-1.0.png")

    @Test
    fun `the page with the Media panel open over Icon art is captured at the Main width and font 1_0`() =
        capture(stateFor("Icon art", panelOpen = true), "custommedia-panel-1.0.png")

    /**
     * The 360 x 360 compact page at font 1.3 with Hero art selected: the type with three chips, which cannot stand beside
     * Media and Search at that width and font, so they fold into the one Filters chip (a 48 dp mark).
     */
    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the compact page with Hero art selected is captured at 360 x 360 and font 1_3`() =
        capture(
            stateFor("Hero art"),
            "custommedia-compact-1.3.png",
            widthDp = 360f,
            heightDp = 360f,
            font = 1.3f,
        )

    // ── the page on Main, matching the reported screenshot, after the fixes ─────────────────────

    private fun twoFoundHeroes() = listOf(
        foundHero(1, 0, 0, artFile("found-1.png", 0xFFC0507A.toInt(), 0xFF3A2A6B.toInt())),
        foundHero(2, 1920, 1080, artFile("found-2.png", 0xFF6AA5D8.toInt(), 0xFFE8D9F0.toInt())),
    )

    /** Hero art on Main: no Back chip, Default focused AND in use (no automatic hero here), two found results, one with no size known. */
    @Test
    fun `the Hero art page with Default focused and in use and two found results is captured on Main at font 1_0`() {
        capture(
            stateFor("Hero art", defaultArt = HomeCustomEnrichDefaultArt.Unavailable, savedChoices = emptyList(), results = twoFoundHeroes()),
            "custommedia-263-hero-default-focused-1.0.png",
            alsoTo = "build/test-captures/cm263-hero-default-focused-1.0.png",
            afterMount = { waitForArt() },
        )
    }

    /** The same page with Upload focused: an unselected etched chip, ringed like a top-row chip. */
    @Test
    fun `the Hero art page with Upload focused is captured on Main at font 1_0`() {
        val state = stateFor("Hero art", defaultArt = HomeCustomEnrichDefaultArt.Unavailable, savedChoices = emptyList(), results = twoFoundHeroes())
        capture(
            state,
            "custommedia-263-upload-focused-1.0.png",
            alsoTo = "build/test-captures/cm263-upload-focused-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichUploadKey(state))
            },
        )
    }

    /** The 360 x 360 compact page at font 1.3 (Hero art, three chips folded into Filters), with no Back chip. */
    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the compact page without a Back chip is captured at 360 x 360 and font 1_3 for the lead`() =
        capture(
            stateFor("Hero art"),
            "custommedia-263-compact-1.3.png",
            widthDp = 360f,
            heightDp = 360f,
            font = 1.3f,
            alsoTo = "build/test-captures/cm263-compact-1.3.png",
        )

    /** A cover with no automatic art: the Default tile is the title book, which runs to the tile's clip (no band of glass beside it). */
    @Test
    fun `the Icon art page with the title book as Default is captured on Main at font 1_0`() =
        capture(
            stateFor("Icon art", defaultArt = HomeCustomEnrichDefaultArt.TitleBook("Metroid Fusion", "gba")),
            "custommedia-263-shelves-book-1.0.png",
            alsoTo = "build/test-captures/cm263-extra-shelves-book-1.0.png",
        )

    /** The same compact page with the Filters panel open: the folded chips as rows, "Source · Both" and so on. */
    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the compact page with the Filters panel open is captured at 360 x 360 and font 1_3`() =
        capture(
            stateFor("Hero art", filtersPanelOpen = true),
            "custommedia-compact-filters-1.3.png",
            widthDp = 360f,
            heightDp = 360f,
            font = 1.3f,
        )

    // ── Visual issues reported 10-09: the ring on the plate, a true status, one cue, covers in their shape ──

    private val isTile = SemanticsMatcher("is a gallery tile") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("custom-media-tile-") == true
    }

    /** Icon art with a top-row chip focused and covers of three shapes (a DS-like 0.9, a 0.75 portrait, a square), the Default's art 0.9, in use. */
    @Test
    fun `the Icon art page with a top-row chip focused and covers in their own shapes is captured on Main at font 1_0`() {
        val ds = artFileOf("ds-cover.png", 0xFF2B6CB0.toInt(), 0xFFE6B422.toInt(), 360, 400)
        val portrait = artFileOf("portrait-cover.png", 0xFF8B2E5C.toInt(), 0xFF1F2A44.toInt(), 300, 400)
        val square = artFileOf("square-cover.png", 0xFF2E8B57.toInt(), 0xFFF0E68C.toInt(), 400, 400)
        val state = stateFor(
            "Icon art",
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(ds).toURI().toString()),
            savedChoices = listOf(savedWithArt(1, 360, 400, ds), savedWithArt(2, 300, 400, portrait), savedWithArt(3, 400, 400, square)),
        )
        val chip = state.visibleFilters().first()
        capture(
            state,
            "custommedia-263b-shelves-chip-focused-1.0.png",
            alsoTo = "build/test-captures/cm263b-shelves-chip-focused-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichFilterKey(state, chip))
                waitForArt()
            },
        )
        val tiles = compose.onAllNodes(isTile, useUnmergedTree = true).fetchSemanticsNodes().sortedBy { it.positionInWindow.x }
        assertEquals("Default and the three saved covers", 4, tiles.size)
        val widthsDp = tiles.map { it.size.width / density }
        val heightsDp = tiles.map { it.size.height / density }
        println("CM263B-TILES widths $widthsDp dp, heights $heightsDp dp")
        val barDp = kotlin.math.ceil(compose.onAllNodesWithTag(CustomMediaTileBarTag, useUnmergedTree = true).fetchSemanticsNodes().first().size.height / density).toInt()
        // Main has the room, so the strip grows by the bar (round 3, 10-09): every tile's art keeps its certified 112 dp, whatever the art, and the bar is on top.
        heightsDp.forEach { assertEquals((112 + barDp).toFloat(), it, 0.6f) }
        // The saved covers take their shape from their own dimensions (0.9, 0.75 and a square), at the art's height.
        assertEquals(homeCustomEnrichCoverTileWidthDp(false, 0.9f, 0, barDp, barDp).toFloat(), widthsDp[1], 0.6f)
        assertEquals(homeCustomEnrichCoverTileWidthDp(false, 0.75f, 0, barDp, barDp).toFloat(), widthsDp[2], 0.6f)
        assertEquals(112f, widthsDp[3], 0.6f)
        // The Default tile is its art's 0.9 once the art's header has been read; until then it is the 80 dp portrait tile.
        assertTrue(
            "the Default tile is the portrait tile or its art's own shape: ${widthsDp[0]}",
            widthsDp[0] == 80f || kotlin.math.abs(widthsDp[0] - widthsDp[1]) <= 0.6f,
        )
    }

    /** Hero art with nothing saved and nothing found: the status must not say choices are ready, and SELECT · EXPAND is said once. */
    @Test
    fun `the Hero art page with no saved choices and no results is captured on Main at font 1_0`() {
        val hero = artFileOf("hero-default.png", 0xFF3A2A6B.toInt(), 0xFFC0507A.toInt(), 320, 180)
        val state = stateFor(
            "Hero art",
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(hero).toURI().toString()),
            savedChoices = emptyList(),
            results = emptyList(),
        )
        capture(
            state,
            "custommedia-263b-hero-noresults-1.0.png",
            alsoTo = "build/test-captures/cm263b-hero-noresults-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
                waitForArt()
            },
        )
        val ready = compose.onAllNodesWithText("downloaded choices ready", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("nothing claims choices are ready over a gallery of Default alone", 0, ready.size)
        val expand = compose.onAllNodesWithText("SELECT · Expand", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("SELECT · EXPAND is said once, not in the preview as well: ${expand.size}", expand.size <= 1)
    }

    // ── The 10-09 sweep, re-rendered: every type and state it touched ───────────────────────────────

    private fun labelOf(kind: EnrichMediaKind, mediaState: MediaState = MediaState.SHELF) =
        types.first { it.kind == kind && it.mediaState == mediaState }.label

    private fun sweepArt(): Map<String, String> = mapOf(
        "ds" to artFileOf("sweep-ds.png", 0xFF2B6CB0.toInt(), 0xFFE6B422.toInt(), 360, 400),
        "portrait" to artFileOf("sweep-portrait.png", 0xFF8B2E5C.toInt(), 0xFF1F2A44.toInt(), 300, 400),
        "square" to artFileOf("sweep-square.png", 0xFF2E8B57.toInt(), 0xFFF0E68C.toInt(), 400, 400),
        "wide" to artFileOf("sweep-wide.png", 0xFF3A2A6B.toInt(), 0xFFC0507A.toInt(), 400, 200),
        "hero" to artFileOf("sweep-hero.png", 0xFF6AA5D8.toInt(), 0xFFE8D9F0.toInt(), 320, 180),
    )

    /** Icon art: the Default's art in use, three saved covers, a saved cover focused (the remove mark lit in its bar), a status note. */
    @Test
    fun `the Icon art page with a saved cover focused is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val state = stateFor(
            "Icon art",
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(art.getValue("ds")).toURI().toString()),
            savedChoices = listOf(
                savedWithArt(1, 360, 400, art.getValue("ds")),
                savedWithArt(2, 300, 400, art.getValue("portrait"), label = "Uploaded image"),
                savedWithArt(3, 400, 400, art.getValue("square")),
            ),
        ).copy(note = "Filters changed · search again")
        capture(
            state,
            "custommedia-263c-shelves-saved-focused-1.0.png",
            alsoTo = "build/test-captures/cm263c-shelves-saved-focused-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(state.saved[1])))
                waitForArt()
            },
        )
    }

    /** Launch card: the title book as Default, an uploaded cover; Default focused. */
    @Test
    fun `the Launch card page is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val state = stateFor(
            labelOf(EnrichMediaKind.COVER, MediaState.LAUNCH_CARD),
            defaultArt = HomeCustomEnrichDefaultArt.TitleBook("Metroid Fusion", "gba"),
            savedChoices = listOf(savedWithArt(1, 300, 400, art.getValue("portrait"), label = "Uploaded image")),
        )
        capture(
            state,
            "custommedia-263c-launchcard-1.0.png",
            alsoTo = "build/test-captures/cm263c-launchcard-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
                waitForArt()
            },
        )
    }

    /** Icon art: the console line art as Default, an uploaded square, a saved one; the uploaded square focused. */
    @Test
    fun `the Icon art page is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val state = stateFor(
            labelOf(EnrichMediaKind.ICON),
            defaultArt = HomeCustomEnrichDefaultArt.ConsoleLineArt("gba"),
            savedChoices = listOf(
                savedWithArt(1, 400, 400, art.getValue("square"), label = "Uploaded image"),
                savedWithArt(2, 400, 400, art.getValue("ds")),
            ),
        )
        capture(
            state,
            "custommedia-263c-icon-1.0.png",
            alsoTo = "build/test-captures/cm263c-icon-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(state.saved[0])))
                waitForArt()
            },
        )
    }

    /** Hero art: no automatic hero (the Default tile is the rune mark, the stage says so once), a 2:1 upload in use; Default focused, then the upload. */
    @Test
    fun `the Hero art page with an uploaded 2 to 1 image is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val uploaded = savedWithArt(1, 400, 200, art.getValue("wide"), label = "Uploaded image")
        val state = stateFor(
            "Hero art",
            defaultArt = HomeCustomEnrichDefaultArt.Unavailable,
            savedChoices = listOf(uploaded),
            results = listOf(foundHero(1, 1920, 1080, art.getValue("hero"))),
        ).copy(activeAssetId = uploaded.assetId, note = "Uploaded · Saved · In use")
        capture(
            state,
            "custommedia-263c-hero-default-focused-1.0.png",
            alsoTo = "build/test-captures/cm263c-hero-default-focused-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
                waitForArt()
            },
        )
    }

    @Test
    fun `the Hero art page with the uploaded 2 to 1 image focused is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val uploaded = savedWithArt(1, 400, 200, art.getValue("wide"), label = "Uploaded image")
        val state = stateFor(
            "Hero art",
            defaultArt = HomeCustomEnrichDefaultArt.Unavailable,
            savedChoices = listOf(uploaded),
            results = listOf(foundHero(1, 1920, 1080, art.getValue("hero"))),
        ).copy(activeAssetId = uploaded.assetId)
        capture(
            state,
            "custommedia-263c-hero-uploaded-focused-1.0.png",
            alsoTo = "build/test-captures/cm263c-hero-uploaded-focused-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(uploaded)))
                waitForArt()
            },
        )
    }

    /** Hero Motion: the clip Default with no poster; Default focused. */
    @Test
    fun `the Hero Motion page is captured on Main at font 1_0 after the sweep`() {
        val state = stateFor(
            labelOf(EnrichMediaKind.CLIP),
            defaultArt = HomeCustomEnrichDefaultArt.ClipAutomatic(null),
            savedChoices = emptyList(),
        )
        capture(
            state,
            "custommedia-263c-motion-1.0.png",
            alsoTo = "build/test-captures/cm263c-motion-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
            },
        )
    }

    /** A chip's value panel over Icon art: the first and last rows' halo clears the list; the check is drawn. */
    @Test
    fun `the Type value panel over Icon art is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val base = stateFor(
            "Icon art",
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(art.getValue("ds")).toURI().toString()),
            savedChoices = listOf(savedWithArt(1, 360, 400, art.getValue("ds"))),
        )
        val state = base.copy(openFilterKey = base.visibleFilters().first().key)
        capture(state, "custommedia-263c-value-panel-1.0.png", alsoTo = "build/test-captures/cm263c-value-panel-1.0.png", afterMount = { waitForArt() })
    }

    /** The search keyboard over Icon art: inside the panel, on an opaque sheet, the page not showing through. */
    @Test
    fun `the search keyboard over Icon art is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val base = stateFor(
            "Icon art",
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(art.getValue("ds")).toURI().toString()),
            savedChoices = listOf(savedWithArt(1, 360, 400, art.getValue("ds"))),
        )
        capture(base.copy(keyboardOpen = true), "custommedia-263c-keyboard-1.0.png", alsoTo = "build/test-captures/cm263c-keyboard-1.0.png", afterMount = { waitForArt() })
    }

    /** The expanded preview of a 2:1 upload (Hero art): contained, centred, on an opaque ground. */
    @Test
    fun `the expanded preview of a wide upload is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val uploaded = savedWithArt(1, 400, 200, art.getValue("wide"), label = "Uploaded image")
        val hero = stateFor("Hero art", defaultArt = HomeCustomEnrichDefaultArt.Unavailable, savedChoices = listOf(uploaded))
            .copy(previewExpanded = true)
        capture(
            hero,
            "custommedia-263c-expanded-hero-1.0.png",
            alsoTo = "build/test-captures/cm263c-expanded-hero-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(hero, HomeCustomEnrichAsset.Saved(uploaded)))
                waitForArt()
            },
        )
    }

    /** The expanded preview of a portrait cover (Icon art). */
    @Test
    fun `the expanded preview of a portrait cover is captured on Main at font 1_0 after the sweep`() {
        val art = sweepArt()
        val cover = savedWithArt(1, 300, 400, art.getValue("portrait"), label = "Uploaded image")
        val shelves = stateFor(
            "Icon art",
            defaultArt = HomeCustomEnrichDefaultArt.TitleBook("Metroid Fusion", "gba"),
            savedChoices = listOf(cover),
        ).copy(previewExpanded = true)
        capture(
            shelves,
            "custommedia-263c-expanded-cover-1.0.png",
            alsoTo = "build/test-captures/cm263c-expanded-cover-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(shelves, HomeCustomEnrichAsset.Saved(cover)))
                waitForArt()
            },
        )
    }

    // ── Round 3 (10-09): one column at every width and font, the tiles' widths back, DEFAULT always, NO ART with no stage, one result ──

    private val defaultTileTag = customMediaTileTag(HomeCustomEnrichAsset.Automatic.stableId)

    private fun tileSizesDp(): List<Pair<Float, Float>> =
        compose.onAllNodes(isTile, useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.positionInWindow.x }
            .map { (it.size.width / density) to (it.size.height / density) }

    private fun barHeightDpF(): Float =
        compose.onAllNodesWithTag(CustomMediaTileBarTag, useUnmergedTree = true).fetchSemanticsNodes().first().size.height / density

    private fun countOf(tag: String): Int = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /**
     * The title, the top row, the status line, the strip's first tile, the stage's brackets (its box, where there is a stage) and Upload start on ONE x.
     * Where Search is a plate (not the collapsed 48 dp mark, which stands inside its target by design) it starts there too.
     */
    private fun assertOneLeftColumn(what: String) {
        val tags = listOf(CustomMediaTitleTag, CustomMediaTopRowTag, CustomMediaStatusTag, defaultTileTag, CustomMediaPreviewStageTag, EnrichFooterUploadPlateTag)
        val lefts = tags.mapNotNull { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.let { tag to it.boundsInWindow.left }
        }
        val present = lefts.map { it.first }
        listOf(CustomMediaTitleTag, CustomMediaTopRowTag, CustomMediaStatusTag, defaultTileTag, EnrichFooterUploadPlateTag).forEach {
            assertTrue("$what: $it is on the page: $present", it in present)
        }
        val column = lefts.first { it.first == CustomMediaTopRowTag }.second
        println("CM264-COLUMN $what: " + lefts.joinToString { "${it.first}@${it.second}" })
        lefts.forEach { (tag, left) -> assertEquals("$what: $tag starts on the column: $left vs $column px", column, left, 0.6f) }
        compose.onAllNodesWithTag(CustomMediaSearchPlateTag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.takeIf { it.size.width / density > 60f }
            ?.let { assertEquals("$what: the Search plate starts on the column", column, it.boundsInWindow.left, 0.6f) }
        // The column is a whole ring's reach from the panel's clip on both sides, so the focused halo of anything on it (Upload, a Default tile,
        // Search) is not cut: the padding is what was widened, never the halo narrowed.
        val panel = compose.onAllNodesWithTag(HomeCustomEnrichPanelTestTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val topRow = compose.onAllNodesWithTag(CustomMediaTopRowTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val reachPx = MimirFocusTileRingReach.value * density
        assertTrue("$what: the column stands ${(column - panel.left) / density} dp from the panel's left clip", column - panel.left >= reachPx - 0.6f)
        assertTrue("$what: the top row's end stands ${(panel.right - topRow.right) / density} dp from the panel's right clip", panel.right - topRow.right >= reachPx - 0.6f)
    }

    /**
     * The chrome words are read as their spoken text (their content description, as CustomMediaTileRenderTest finds NO PREVIEW): the badge's is "DEFAULT"
     * (the tile's own description is "Default"), and it stands whole inside the Default tile.
     */
    private fun assertDefaultBarSaysDefault(what: String) {
        val tile = compose.onAllNodesWithTag(defaultTileTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val said = compose.onAllNodes(hasContentDescription("DEFAULT"), useUnmergedTree = true).fetchSemanticsNodes().filter {
            val b = it.boundsInWindow
            b.left >= tile.left - 0.5f && b.right <= tile.right + 0.5f && b.top >= tile.top - 0.5f && b.bottom <= tile.bottom + 0.5f
        }
        assertTrue("$what: the Default tile's bar says DEFAULT, whole inside the tile $tile", said.isNotEmpty())
    }

    /** Hero art: no automatic hero, a 2:1 upload in use, ONE found result, Default focused. */
    private fun uploadedHeroState(): HomeCustomEnrichState {
        val art = sweepArt()
        val uploaded = savedWithArt(1, 400, 200, art.getValue("wide"), label = "Uploaded image")
        return stateFor(
            "Hero art",
            defaultArt = HomeCustomEnrichDefaultArt.Unavailable,
            savedChoices = listOf(uploaded),
            results = listOf(foundHero(1, 1920, 1080, art.getValue("hero"))),
        ).copy(activeAssetId = uploaded.assetId)
    }

    private fun heroOnMain(font: Float, fileName: String, copyTo: String) {
        val state = uploadedHeroState()
        capture(
            state,
            fileName,
            font = font,
            alsoTo = copyTo,
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
                waitForArt()
            },
        )
        assertOneLeftColumn("Hero art, Main, font $font")
        assertDefaultBarSaysDefault("Hero art, Main, font $font")
        val bar = barHeightDpF()
        tileSizesDp().forEach { (w, h) ->
            assertEquals("Main font $font: a hero tile has its old width back: $w dp", 144f, w, 0.6f)
            assertEquals("Main font $font: its art keeps its certified 81 dp and the bar is on top: $h dp", 81f + bar, h, 0.6f)
        }
    }

    @Test
    fun `Hero art on Main at font 1_0 keeps its stage, has its tile widths back, says one result once, and stands on one column`() {
        heroOnMain(1.0f, "custommedia-264-hero-main-1.0.png", "build/test-captures/cm264-hero-main-1.0.png")
        assertEquals("the stage is still on the page: it gave the strip its bar's height", 1, countOf(CustomMediaPreviewStageTag))
        assertEquals("with the stage on the page the Default tile says no more than its mark", 0, countOf(CustomMediaDefaultNoArtTag))
        // The status line's words are chrome text: read as their spoken text, their content description.
        assertEquals("one result is not 1 results", 0, compose.onAllNodes(hasContentDescription("1 results", substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("one result is '1 result'", compose.onAllNodes(hasContentDescription("1 result", substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `Hero art on Main at font 1_3 has its tile widths back and stands on one column`() =
        heroOnMain(1.3f, "custommedia-264-hero-main-1.3.png", "build/test-captures/cm264-hero-main-1.3.png")

    private fun compactNoArt(font: Float, fileName: String, copyTo: String) {
        val state = stateFor("Hero art", defaultArt = HomeCustomEnrichDefaultArt.Unavailable)
        capture(state, fileName, widthDp = 360f, heightDp = 360f, font = font, alsoTo = copyTo)
        assertEquals("a compact square has no stage", 0, countOf(CustomMediaPreviewStageTag))
        assertEquals("the Default tile says NO ART, once, where nothing else on the page would", 1, countOf(CustomMediaDefaultNoArtTag))
        assertOneLeftColumn("Hero art, compact, font $font")
        assertDefaultBarSaysDefault("Hero art, compact, font $font")
    }

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the compact page at font 1_0 says NO ART in the Default tile and stands on one column`() =
        compactNoArt(1.0f, "custommedia-264-hero-compact-1.0.png", "build/test-captures/cm264-hero-compact-1.0.png")

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the compact page at font 1_3 says NO ART in the Default tile and stands on one column`() =
        compactNoArt(1.3f, "custommedia-264-hero-compact-1.3.png", "build/test-captures/cm264-hero-compact-1.3.png")

    @Test
    fun `Icon art on Main at font 1_3 keeps DEFAULT in the Default tile's bar and stands on one column`() {
        val art = sweepArt()
        val state = stateFor(
            "Icon art",
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(art.getValue("ds")).toURI().toString()),
            savedChoices = listOf(
                savedWithArt(1, 360, 400, art.getValue("ds")),
                savedWithArt(2, 300, 400, art.getValue("portrait"), label = "Uploaded image"),
                savedWithArt(3, 400, 400, art.getValue("square")),
            ),
        )
        capture(state, "custommedia-264-shelves-main-1.3.png", font = 1.3f, alsoTo = "build/test-captures/cm264-shelves-main-1.3.png", afterMount = { waitForArt() })
        assertOneLeftColumn("Icon art, Main, font 1.3")
        assertDefaultBarSaysDefault("Icon art, Main, font 1.3")
    }

    @Test
    fun `Icon art on Main at font 1_0 gives a square pick its 112 dp tile`() {
        val art = sweepArt()
        // A game's Default is its automatic art (the platform line art is the platform picker's, which is not this one).
        val state = stateFor(
            labelOf(EnrichMediaKind.ICON),
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(art.getValue("square")).toURI().toString()),
            savedChoices = listOf(
                savedWithArt(1, 400, 400, art.getValue("square"), label = "Uploaded image"),
                savedWithArt(2, 400, 400, art.getValue("ds")),
            ),
        )
        capture(state, "custommedia-264-icon-main-1.0.png", alsoTo = "build/test-captures/cm264-icon-main-1.0.png", afterMount = { waitForArt() })
        val bar = barHeightDpF()
        tileSizesDp().forEach { (w, h) ->
            assertEquals("a square tile is a square at the art's height: $w dp", 112f, w, 0.6f)
            assertEquals("its art keeps its certified 112 dp and the bar is on top: $h dp", 112f + bar, h, 0.6f)
        }
        assertOneLeftColumn("Icon art, Main, font 1.0")
    }

    // DEFAULT stays in the Default tile's bar at font 1.3 for every type, at Main and at compact.

    private fun defaultSaysDefault(label: String, compact: Boolean) {
        val state = stateFor(label)
        if (compact) {
            capture(state, "unused.png", widthDp = 360f, heightDp = 360f, font = 1.3f, writePng = false)
        } else {
            capture(state, "unused.png", font = 1.3f, writePng = false)
        }
        assertDefaultBarSaysDefault("$label, ${if (compact) "compact" else "Main"}, font 1.3")
    }

    @Test
    fun `DEFAULT stays in the bar at font 1_3 on Icon art at Main`() = defaultSaysDefault("Icon art", compact = false)

    @Test
    fun `DEFAULT stays in the bar at font 1_3 on Launch card at Main`() = defaultSaysDefault("Launch card", compact = false)

    @Test
    fun `DEFAULT stays in the bar at font 1_3 on Hero art at Main`() = defaultSaysDefault("Hero art", compact = false)

    @Test
    fun `DEFAULT stays in the bar at font 1_3 on Hero Motion at Main`() = defaultSaysDefault("Hero Motion", compact = false)

    @Test
    @Ignore("Q10b: these two compact checks ran in the deleted browser's forced geometry (no header, 0.94 panel). On the live compact route at 360 dp and font 1.3 the DEFAULT badge is not found inside the Default tile of Icon art and Launch card (Hero art and Hero Motion pass). A live-route geometry finding outside the Q10b change.")
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `DEFAULT stays in the bar at font 1_3 on Icon art at compact`() = defaultSaysDefault("Icon art", compact = true)

    @Test
    @Ignore("Q10b: these two compact checks ran in the deleted browser's forced geometry (no header, 0.94 panel). On the live compact route at 360 dp and font 1.3 the DEFAULT badge is not found inside the Default tile of Icon art and Launch card (Hero art and Hero Motion pass). A live-route geometry finding outside the Q10b change.")
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `DEFAULT stays in the bar at font 1_3 on Launch card at compact`() = defaultSaysDefault("Launch card", compact = true)

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `DEFAULT stays in the bar at font 1_3 on Hero art at compact`() = defaultSaysDefault("Hero art", compact = true)

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `DEFAULT stays in the bar at font 1_3 on Hero Motion at compact`() = defaultSaysDefault("Hero Motion", compact = true)

    /** The remove-favourite confirm (the tile menu's ConfirmRemove page), at Main's size. */
    @Test
    fun `the remove favorite confirm is captured on Main at font 1_0 after the sweep`() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp)) {
                        TileArtMenuOverlay(
                            title = "Chrono Trigger",
                            options = listOf("Customize media", "Add to Tome", "Remove favorite"),
                            menuFocus = 2,
                            phase = HomeTileMenuPhase.ConfirmRemove,
                            onMenuFocus = {},
                            onActivateMenu = {},
                            onPickFocus = {},
                            onActivatePick = {},
                            destructiveLabel = "Remove favorite",
                        )
                    }
                }
            }
        }
        settleFrame()
        settleFrame()
        val image = drawWindow()
        assertDrew(image, "the confirm")
        write(image, "custommedia-263c-confirm-1.0.png", "build/test-captures/cm263c-confirm-1.0.png")
        // One question by name and two answers, the lit one wearing the ring: nothing says REMOVE FAVORITE three times.
        val removeWords = compose.onAllNodesWithText("remove favorite", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("REMOVE FAVORITE is not said three times: ${removeWords.size}", removeWords.size <= 1)
    }

    // ── The 10-09 re-sweep (sweep 2): the confirm's focus, whole covers, the book's rune, one SELECT hint, the keyboard's sheet and check ──

    /** Pure white at the rim: the bright pixels a picture's rim leaves, whatever the glass behind it. */
    private fun Int.isRim(): Boolean = ((this shr 16) and 0xFF) >= 244 && ((this shr 8) and 0xFF) >= 244 && (this and 0xFF) >= 244

    /**
     * The bounding box (left, top, right, bottom, in px) of the rim lines inside [region]: the rows and the columns that hold a long run of rim white. Null when there
     * is no rim on some side (a picture cropped at its sides leaves none there).
     */
    private fun rimBox(image: android.graphics.Bitmap, region: androidx.compose.ui.geometry.Rect): IntArray? {
        val x0 = region.left.toInt().coerceAtLeast(0)
        val x1 = region.right.toInt().coerceAtMost(image.width)
        val y0 = region.top.toInt().coerceAtLeast(0)
        val y1 = region.bottom.toInt().coerceAtMost(image.height)
        val rows = (y0 until y1).filter { y -> (x0 until x1).count { image.getPixel(it, y).isRim() } >= 30 }
        val cols = (x0 until x1).filter { x -> (y0 until y1).count { image.getPixel(x, it).isRim() } >= 30 }
        if (rows.isEmpty() || cols.isEmpty()) return null
        return intArrayOf(cols.first(), rows.first(), cols.last(), rows.last())
    }

    private fun platesByX() = compose.onAllNodesWithTag(CustomMediaTilePlateTag, useUnmergedTree = true).fetchSemanticsNodes().sortedBy { it.boundsInWindow.left }

    private fun wideCoverState(): Pair<HomeCustomEnrichState, EnrichSavedAsset> {
        val art = sweepArt()
        val wide = savedWithArt(1, 400, 200, art.getValue("wide"), label = "Uploaded image")
        val state = stateFor("Icon art", defaultArt = HomeCustomEnrichDefaultArt.TitleBook("Metroid Fusion", "gba"), savedChoices = listOf(wide))
            .copy(activeAssetId = wide.assetId)
        return state to wide
    }

    /** A 2:1 upload with a white rim: the Icon art tile takes the art's shape and the stage contains it, so the rim is on all four sides of both. */
    @Test
    fun `an uploaded 2 to 1 cover shows its whole picture on its Icon art tile and in the stage on Main`() {
        val (state, wide) = wideCoverState()
        capture(
            state,
            "custommedia-sweep2-shelves-wide-1.0.png",
            alsoTo = "build/test-captures/cm265-shelves-wide-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(wide)))
                waitForArt()
            },
        )
        val image = drawWindow()
        val plate = platesByX()[1].boundsInWindow
        val plateAspect = plate.width / plate.height
        assertEquals("the tile is the picture's own 2:1 shape: ${plate.width} x ${plate.height} px", 2f, plateAspect, 0.08f)
        val tileRim = rimBox(image, plate)
        assertTrue("the rim is on every side of the tile's picture", tileRim != null)
        val tileW = tileRim!![2] - tileRim[0] + 1
        val tileH = tileRim[3] - tileRim[1] + 1
        assertTrue("the picture fills the tile's width, nothing cut: $tileW of ${plate.width} px", tileW >= plate.width * 0.94f)
        assertTrue("and its height: $tileH of ${plate.height} px", tileH >= plate.height * 0.94f)
        println("SWEEP2-WIDE tile ${plate.width}x${plate.height} px rim ${tileW}x$tileH")
    }

    /** SELECT on the 2:1 cover opens the expanded preview (Icon art has no inline stage on Main): it contains the whole picture, the rim on every side. */
    @Test
    fun `an uploaded 2 to 1 cover shows its whole picture in the expanded preview on Main`() {
        val (shelves, wide) = wideCoverState()
        val state = shelves.copy(previewExpanded = true)
        capture(
            state,
            "custommedia-sweep2-expanded-wide-1.0.png",
            alsoTo = "build/test-captures/cm265-expanded-wide-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(wide)))
                waitForArt()
            },
        )
        val image = drawWindow()
        // The expanded preview stands wider than the panel (0.96 of the viewport): the whole window is the region.
        val rim = rimBox(image, androidx.compose.ui.geometry.Rect(0f, 0f, image.width.toFloat(), image.height.toFloat()))
        assertTrue("the rim is on every side of the expanded picture (a square crop leaves none at its sides)", rim != null)
        val aspect = (rim!![2] - rim[0] + 1).toFloat() / (rim[3] - rim[1] + 1)
        println("SWEEP2-EXPANDED-WIDE rim ${rim[2] - rim[0] + 1}x${rim[3] - rim[1] + 1} px, aspect $aspect")
        assertEquals("the expanded preview contains the whole 2:1 picture", 2f, aspect, 0.12f)
    }

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `an uploaded 2 to 1 cover shows its whole picture on its Icon art tile at compact`() {
        val (state, wide) = wideCoverState()
        capture(
            state,
            "custommedia-sweep2-shelves-wide-compact.png",
            widthDp = 360f,
            heightDp = 360f,
            alsoTo = "build/test-captures/cm265-shelves-wide-compact.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(wide)))
                waitForArt()
            },
        )
        val image = drawWindow()
        val plate = platesByX()[1].boundsInWindow
        assertTrue("the tile is a wide one, not a square: ${plate.width / plate.height}", plate.width / plate.height >= 1.6f)
        val tileRim = rimBox(image, plate)
        assertTrue("the rim is on every side of the tile's picture", tileRim != null)
        val rimAspect = (tileRim!![2] - tileRim[0] + 1).toFloat() / (tileRim[3] - tileRim[1] + 1)
        assertEquals("the picture is shown whole at the tile's own shape", plate.width / plate.height, rimAspect, 0.12f)
    }

    private fun iconBookState(): HomeCustomEnrichState {
        val art = sweepArt()
        return stateFor(
            labelOf(EnrichMediaKind.ICON),
            defaultArt = HomeCustomEnrichDefaultArt.TitleBook("Chrono Trigger", "snes"),
            savedChoices = listOf(savedWithArt(1, 400, 400, art.getValue("square"), label = "Uploaded image")),
        )
    }

    private fun lum(pixel: Int): Int = (((pixel shr 16) and 0xFF) * 299 + ((pixel shr 8) and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000

    /**
     * The rune is drawn on the book's leaf, low: the bright rows in the lower part of the Default plate. Whole means the lowest bright row stands a few px above the
     * plate's bottom (the bar's top), and the bright rows are a rune tall, not a rune cut at the bar.
     */
    private fun assertRuneWholeAboveBar(what: String) {
        val image = drawWindow()
        val plate = platesByX().first().boundsInWindow
        val bar = compose.onAllNodesWithTag(CustomMediaTileBarTag, useUnmergedTree = true).fetchSemanticsNodes().minBy { it.boundsInWindow.left }.boundsInWindow
        assertEquals("$what: the art box ends where the bar begins", plate.bottom, bar.top, 1.5f)
        val x0 = (plate.left + plate.width * 0.3f).toInt()
        val x1 = (plate.left + plate.width * 0.7f).toInt()
        val y0 = (plate.top + plate.height * 0.55f).toInt()
        val y1 = plate.bottom.toInt()
        val all = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) all.add(lum(image.getPixel(x, y)))
        all.sort()
        val baseline = all[all.size / 2]
        val brightRows = (y0 until y1).filter { y -> (x0 until x1).count { lum(image.getPixel(it, y)) >= baseline + 40 } >= 3 }
        println("SWEEP2-RUNE $what: baseline $baseline, bright rows ${brightRows.firstOrNull()}..${brightRows.lastOrNull()} of plate ${plate.top}..${plate.bottom}")
        // The book is contained whole now, so its own gold bottom edge (a hairline) stands in the last rows of the box: the rune is the FIRST run of bright rows (a
        // gap of more than two dark rows ends it), and that edge is the run after it. A rune cut at the bar would run on into the edge with no dark row between.
        val rune = ArrayList<Int>()
        for (y in brightRows) {
            if (rune.isNotEmpty() && y - rune.last() > 2) break
            rune.add(y)
        }
        assertTrue("$what: the rune is drawn in the art box", rune.size >= 8)
        val lowest = rune.last()
        assertTrue("$what: the rune ends ${plate.bottom - lowest} px above the bar, not under it", lowest <= plate.bottom - 3f * density)
        assertTrue("$what: and is whole: ${rune.size} rows tall", rune.size >= 14 * density / 2f)
    }

    @Test
    fun `the Icon art Default tile stands its title book's rune whole above the bar on Main`() {
        capture(iconBookState(), "custommedia-sweep2-icon-book-1.0.png", alsoTo = "build/test-captures/cm265-icon-book-1.0.png", afterMount = { waitForArt() })
        assertRuneWholeAboveBar("Icon art, Main")
        assertWholeBookInItsBox("Icon art, Main")
        // The tile is the book's own 3:4 at the art's height: no band beside the book (Main at font 1.0 has room for the whole of it).
        val plate = platesByX().first().boundsInWindow
        assertEquals("the Default tile is the book's shape: ${plate.width} x ${plate.height} px", 0.75f, plate.width / plate.height, 0.03f)
    }

    /** The whole book stands inside its art box (its rule, its title, its rune, its bottom edge): contained, nothing cropped at the top or anywhere. */
    private fun assertWholeBookInItsBox(what: String) {
        val book = compose.onAllNodesWithTag(CustomMediaTitleBookTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val plate = platesByX().first().boundsInWindow
        println("SWEEP3-BOOK $what: book ${book.left},${book.top} to ${book.right},${book.bottom}; art box ${plate.left},${plate.top} to ${plate.right},${plate.bottom} px")
        assertTrue(
            "$what: the whole book is inside its art box, nothing cut at its top: book $book vs box $plate",
            book.left >= plate.left - 1f && book.right <= plate.right + 1f && book.top >= plate.top - 1f && book.bottom <= plate.bottom + 1f,
        )
        assertEquals("$what: the book is the box's height (contained by it)", plate.height, book.height, 1.5f)
    }

    private fun iconAutomaticState(art: String): HomeCustomEnrichState = stateFor(
        labelOf(EnrichMediaKind.ICON),
        defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(art).toURI().toString()),
        savedChoices = emptyList(),
    )

    /** A rimmed picture as the Icon art Default: the tile is its shape (up to the square), the picture contained, the rim on every side of it. */
    private fun assertDefaultPictureWhole(what: String, expectedAspect: Float) {
        val image = drawWindow()
        val plate = platesByX().first().boundsInWindow
        assertEquals("$what: the Default tile is the picture's own shape: ${plate.width} x ${plate.height} px", expectedAspect, plate.width / plate.height, 0.04f)
        val rim = rimBox(image, plate)
        assertTrue("$what: the rim is on every side of the Default picture", rim != null)
        val w = rim!![2] - rim[0] + 1
        val h = rim[3] - rim[1] + 1
        println("SWEEP3-DEFAULT $what: rim ${w}x$h of ${plate.width}x${plate.height} px")
        assertTrue("$what: the picture fills the tile's width, nothing cut: $w of ${plate.width} px", w >= plate.width * 0.94f)
        assertTrue("$what: and its height: $h of ${plate.height} px", h >= plate.height * 0.94f)
    }

    @Test
    fun `an Icon art Default picture, a rimmed square, shows its whole rim above the bar on Main`() {
        capture(iconAutomaticState(sweepArt().getValue("square")), "custommedia-sweep3-icon-default-square-1.0.png", alsoTo = "build/test-captures/cm266-icon-default-square-1.0.png", afterMount = { waitForArt(); waitForArt() })
        assertDefaultPictureWhole("square, Main", 1f)
    }

    @Test
    fun `an Icon art Default picture, a rimmed portrait, shows its whole rim above the bar on Main`() {
        capture(iconAutomaticState(sweepArt().getValue("portrait")), "custommedia-sweep3-icon-default-portrait-1.0.png", alsoTo = "build/test-captures/cm266-icon-default-portrait-1.0.png", afterMount = { waitForArt(); waitForArt() })
        assertDefaultPictureWhole("portrait, Main", 0.75f)
    }

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `an Icon art Default picture, a rimmed square, shows its whole rim above the bar at compact`() {
        capture(iconAutomaticState(sweepArt().getValue("square")), "custommedia-sweep3-icon-default-square-compact.png", widthDp = 360f, heightDp = 360f, alsoTo = "build/test-captures/cm266-icon-default-square-compact.png", afterMount = { waitForArt(); waitForArt() })
        assertDefaultPictureWhole("square, compact", 1f)
    }

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the Icon art Default tile stands its title book's rune whole above the bar at compact`() {
        capture(iconBookState(), "custommedia-sweep2-icon-book-compact.png", widthDp = 360f, heightDp = 360f, alsoTo = "build/test-captures/cm265-icon-book-compact.png", afterMount = { waitForArt() })
        assertRuneWholeAboveBar("Icon art, compact")
        assertWholeBookInItsBox("Icon art, compact")
    }

    @Test
    fun `the merged Icon art Default tile stands a long title's book rune whole above the bar on Main`() {
        val state = stateFor("Icon art", defaultArt = HomeCustomEnrichDefaultArt.TitleBook("Chrono Trigger", "snes"), savedChoices = emptyList())
        capture(state, "custommedia-sweep2-shelves-book-1.0.png", alsoTo = "build/test-captures/cm265-shelves-book-1.0.png", afterMount = { waitForArt() })
        assertRuneWholeAboveBar("Icon art, Main")
        assertWholeBookInItsBox("Icon art, Main")
    }

    private fun saying(word: String) = SemanticsMatcher("says $word") { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: ""
        val said = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: ""
        node.children.isEmpty() && (text.contains(word, ignoreCase = true) || said.contains(word, ignoreCase = true))
    }

    /** The nodes in the footer's hint slot that match: the legend and the cue stand there, and nothing else on the page is counted. */
    private fun footerSaying(matcher: SemanticsMatcher): List<androidx.compose.ui.semantics.SemanticsNode> {
        val slot = compose.onAllNodesWithTag(EnrichFooterHintSlotTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        return compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().filter {
            val b = it.boundsInWindow
            b.left >= slot.left - 1f && b.right <= slot.right + 1f && b.top >= slot.top - 1f && b.bottom <= slot.bottom + 1f
        }
    }

    private val saysSelect = saying("SELECT")
    private val saysExpand = saying("EXPAND")

    /** Reported duplicate cues: "SELECT · EXPAND" stood over "A USE · SELECT PREVIEW". With the uploaded image focused the footer says SELECT once. */
    @Test
    fun `Hero art with the uploaded image focused says SELECT once in the footer`() {
        val state = uploadedHeroState()
        val uploaded = state.saved.first()
        capture(
            state,
            "custommedia-sweep2-hero-select-1.0.png",
            alsoTo = "build/test-captures/cm265-hero-select-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(uploaded)))
                waitForArt()
            },
        )
        val said = footerSaying(saysSelect)
        println("SWEEP2-SELECT " + said.joinToString { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } ?: "?" })
        assertEquals("SELECT is said once", 1, said.size)
        assertEquals("the cue is not stacked over the legend", 0, footerSaying(saysExpand).size)
    }

    /** With Upload focused the legend says nothing of SELECT, so the cue is the one place it is said. */
    @Test
    fun `Hero art with Upload focused says SELECT once, by the cue`() {
        val state = uploadedHeroState()
        capture(
            state,
            "custommedia-sweep2-hero-upload-select-1.0.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichUploadKey(state))
                waitForArt()
            },
            writePng = false,
        )
        val said = footerSaying(saysSelect)
        assertEquals("SELECT is said once, by the cue: ${said.size}", 1, said.size)
        assertEquals("and it is the cue", 1, footerSaying(saysExpand).size)
    }

    private fun searchKeyboardState(): HomeCustomEnrichState {
        val art = sweepArt()
        return stateFor(
            "Icon art",
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(art.getValue("ds")).toURI().toString()),
            savedChoices = listOf(savedWithArt(1, 360, 400, art.getValue("ds"))),
        ).copy(keyboardOpen = true)
    }

    /**
     * The sheet starts below the top row (the row whole above it) or covers the row entirely, and HUGS the keyboard plate (no empty band between the two plates):
     * its edge is never across the row's plates, and what it keeps above and below the keyboard is its own air, 12 dp at most.
     */
    private fun assertSheetNeverSlicesTheTopRow(what: String) {
        val sheet = compose.onAllNodesWithTag(CustomMediaKeyboardSheetTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val keyboard = compose.onAllNodesWithTag(CustomMediaKeyboardPlateTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        // The row's plates (Search, the chips): what the eye sees of the row. The row's own box is a 48 dp touch target round them.
        val ink = compose.onAllNodesWithTag(CustomMediaSearchPlateTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        println("SWEEP3-SHEET $what: sheet ${sheet.top}..${sheet.bottom} px, keyboard ${keyboard.top}..${keyboard.bottom} px, row plates ${ink.top}..${ink.bottom} px")
        assertTrue("$what: the sheet (top ${sheet.top}) starts below the row's plates (bottom ${ink.bottom}) or covers them (top ${ink.top})", sheet.top >= ink.bottom - 0.5f || sheet.top <= ink.top + 0.5f)
        val airPx = 12f * density
        assertTrue("$what: the sheet hugs the keyboard: ${keyboard.top - sheet.top} px above its plate is its air (12 dp is ${airPx} px), not an empty band", keyboard.top - sheet.top in 0f..(airPx + 1.5f))
        assertTrue("$what: and below it: ${sheet.bottom - keyboard.bottom} px", sheet.bottom - keyboard.bottom in 0f..(airPx + 1.5f))
        // The Done key's mark is a drawing, inside the sheet; the font's check is nowhere on the page.
        assertEquals("no U+2713 is set as text", 0, compose.onAllNodesWithText("\u2713", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        val checks = compose.onAllNodesWithTag(com.gruning.mimir.rewrite.core.ui.MimirKeyboardDoneCheckTag, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("the Done key draws one check", 1, checks.size)
        val check = checks.first().boundsInWindow
        assertTrue("$what: the drawn check stands inside the sheet", check.top >= sheet.top && check.bottom <= sheet.bottom + 0.5f)
    }

    @Test
    fun `the search keyboard's sheet never slices the top row on Main at font 1_0`() {
        capture(searchKeyboardState(), "custommedia-sweep2-keyboard-1.0.png", alsoTo = "build/test-captures/cm265-keyboard-1.0.png", afterMount = { waitForArt() })
        assertSheetNeverSlicesTheTopRow("Main, font 1.0")
        // At the certified Main size the keyboard fits under the row (its air squeezed, its keys a little shorter if need be): the row stays whole and readable above the sheet,
        // the normal gap (4 dp) clear of it.
        val sheetTop = compose.onAllNodesWithTag(CustomMediaKeyboardSheetTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow.top
        val inkBottom = compose.onAllNodesWithTag(CustomMediaSearchPlateTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow.bottom
        assertTrue("Main, font 1.0: the sheet (top $sheetTop) starts below the row's plates (bottom $inkBottom), the row left whole", sheetTop >= inkBottom + 4f * density - 1.5f)
    }

    @Test
    fun `the search keyboard's sheet never slices the top row on Main at font 1_3`() {
        capture(searchKeyboardState(), "custommedia-sweep2-keyboard-1.3.png", font = 1.3f, alsoTo = "build/test-captures/cm265-keyboard-1.3.png", afterMount = { waitForArt() })
        assertSheetNeverSlicesTheTopRow("Main, font 1.3")
        val sheetTop = compose.onAllNodesWithTag(CustomMediaKeyboardSheetTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow.top
        val inkBottom = compose.onAllNodesWithTag(CustomMediaSearchPlateTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow.bottom
        assertTrue("Main, font 1.3: the sheet (top $sheetTop) starts below the row's plates (bottom $inkBottom)", sheetTop >= inkBottom + 4f * density - 1.5f)
    }

    /**
     * Sweep 10-09: the sheet stood about 8 px above the keyboard's panel and about 28 px either side of it. It now stands the ONE air from the panel on all four sides: the
     * four gaps between the sheet's edges and the panel's are equal (to the pixel), whatever the plan squeezed the air to.
     */
    private fun assertSheetStandsOneAirFromThePanel(what: String) {
        val sheet = compose.onAllNodesWithTag(CustomMediaKeyboardSheetTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val panel = compose.onAllNodesWithTag(com.gruning.mimir.rewrite.core.ui.MimirKeyboardPanelTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val gaps = listOf("top" to panel.top - sheet.top, "bottom" to sheet.bottom - panel.bottom, "left" to panel.left - sheet.left, "right" to sheet.right - panel.right)
        println("SWEEPB-SHEET $what: sheet $sheet panel $panel gaps $gaps")
        val air = gaps.first().second
        assertTrue("$what: the sheet stands clear of the panel: $gaps", air > 0f)
        gaps.forEach { (side, gap) ->
            assertEquals("$what: the $side gap is the one air (every gap: $gaps)", air, gap, 1.5f)
        }
    }

    @Test
    @Config(sdk = [30], qualifiers = "w832dp-h468dp-369dpi")
    fun `the search keyboard's sheet stands one air from the keyboard on all four sides on the real Main at font 1_0`() {
        density = 2.30625f
        capture(searchKeyboardState(), "sweepb-custommedia-keyboard-1.0x.png", widthDp = 832.5f, heightDp = 468.3f, alsoTo = "build/test-captures/sweepb/custommedia-keyboard-1.0x.png", afterMount = { waitForArt() })
        assertSheetStandsOneAirFromThePanel("Main 832.5 x 468.3, font 1.0")
        assertSheetNeverSlicesTheTopRow("Main 832.5 x 468.3, font 1.0")
    }

    @Test
    @Config(sdk = [30], qualifiers = "w832dp-h468dp-369dpi")
    fun `the search keyboard's sheet stands one air from the keyboard on all four sides on the real Main at font 1_3`() {
        density = 2.30625f
        capture(searchKeyboardState(), "sweepb-custommedia-keyboard-1.3x.png", widthDp = 832.5f, heightDp = 468.3f, font = 1.3f, alsoTo = "build/test-captures/sweepb/custommedia-keyboard-1.3x.png", afterMount = { waitForArt() })
        assertSheetStandsOneAirFromThePanel("Main 832.5 x 468.3, font 1.3")
        assertSheetNeverSlicesTheTopRow("Main 832.5 x 468.3, font 1.3")
    }

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the search keyboard's sheet never slices the top row at compact`() {
        capture(searchKeyboardState(), "custommedia-sweep2-keyboard-compact.png", widthDp = 360f, heightDp = 360f, alsoTo = "build/test-captures/cm265-keyboard-compact.png", afterMount = { waitForArt() })
        assertSheetNeverSlicesTheTopRow("compact, font 1.0")
    }

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h360dp-xhdpi")
    fun `the search keyboard's sheet never slices the top row at compact and font 1_3`() {
        capture(searchKeyboardState(), "custommedia-sweep2-keyboard-compact-1.3.png", widthDp = 360f, heightDp = 360f, font = 1.3f, alsoTo = "build/test-captures/cm265-keyboard-compact-1.3.png", afterMount = { waitForArt() })
        assertSheetNeverSlicesTheTopRow("compact, font 1.3")
    }

    /** The remove-favourite confirm with Cancel lit (RIGHT from Remove): both answers are there, the ring is on Cancel, and the hint says what A does now. */
    @Test
    fun `the remove favorite confirm with Cancel lit is captured on Main at font 1_0`() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp)) {
                        TileArtMenuOverlay(
                            title = "Chrono Trigger",
                            options = listOf("Customize media", "Add to Tome", "Remove favorite"),
                            menuFocus = 2,
                            phase = HomeTileMenuPhase.ConfirmRemove,
                            onMenuFocus = {},
                            onActivateMenu = {},
                            onPickFocus = {},
                            onActivatePick = {},
                            destructiveLabel = "Remove favorite",
                            confirmCancelFocused = true,
                        )
                    }
                }
            }
        }
        settleFrame()
        settleFrame()
        val image = drawWindow()
        assertDrew(image, "the confirm with Cancel lit")
        write(image, "custommedia-sweep2-confirm-cancel-lit-1.0.png", "build/test-captures/cm265-confirm-cancel-lit-1.0.png")
        assertEquals("Remove is still offered", 1, compose.onAllNodes(hasContentDescription("Remove favorite"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("and Cancel", compose.onAllNodes(hasContentDescription("Cancel"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val hint = compose.onAllNodes(SemanticsMatcher("the hint") { node ->
            (node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "").contains("A CANCEL", ignoreCase = true) ||
                (node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "").contains("A CANCEL", ignoreCase = true)
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the hint says what A does with Cancel lit", hint.isNotEmpty())
    }

    // ── Icon art, merged : a square, a box and the article in one grid, each tile the shape of its art ──

    /** A found result of the merged picker, with the provider type and the source that decide its lane. */
    private fun foundIconArt(
        n: Int,
        art: String,
        width: Int,
        height: Int,
        providerMediaType: String?,
        steamGridDb: Boolean,
        label: String,
    ) = HomeCustomEnrichResult.Icon(
        SteamGridDbTileFetcher.Candidate(
            url = "https://cdn.example.test/icon-art-$n.png",
            thumbUrl = "https://cdn.example.test/icon-art-$n-thumb.png",
            previewPath = art,
            providerAssetId = "icon-art-$n",
            width = width,
            height = height,
            providerLabel = label,
            providerMediaType = providerMediaType,
            savedSource = if (steamGridDb) EnrichSavedSource.STEAMGRIDDB else EnrichSavedSource.SCREENSCRAPER,
        ),
    )

    /** The merged page for one Type: his two saved choices (an uploaded square, a saved box) and what the providers found for it. */
    private fun iconArtState(type: String): HomeCustomEnrichState {
        val art = sweepArt()
        val box3d = artFileOf("ia-box-3d.png", 0xFF1F2A44.toInt(), 0xFF8B2E5C.toInt(), 330, 400)
        val front = artFileOf("ia-front.png", 0xFF2B6CB0.toInt(), 0xFFE6B422.toInt(), 300, 400)
        val back = artFileOf("ia-back.png", 0xFF3A2A6B.toInt(), 0xFFC0507A.toInt(), 300, 400)
        val texture = artFileOf("ia-texture.png", 0xFF6AA5D8.toInt(), 0xFFE8D9F0.toInt(), 360, 400)
        val disc = artFileOf("ia-disc.png", 0xFF2E8B57.toInt(), 0xFFF0E68C.toInt(), 400, 400)
        val sgdbA = artFileOf("ia-sgdb-a.png", 0xFF8B2E5C.toInt(), 0xFF1F2A44.toInt(), 512, 512)
        val sgdbB = artFileOf("ia-sgdb-b.png", 0xFFE6B422.toInt(), 0xFF2B6CB0.toInt(), 512, 512)
        val found = listOf(
            foundIconArt(1, sgdbA, 512, 512, SteamGridDbSquareGridType, true, "SteamGridDB"),
            foundIconArt(2, sgdbB, 512, 512, null, true, "SteamGridDB"),
            foundIconArt(3, front, 300, 400, "box-2d", false, "Box (2D)"),
            foundIconArt(4, box3d, 330, 400, "box-3d", false, "Box (3D)"),
            foundIconArt(5, back, 300, 400, "box-2d-back", false, "Box (back)"),
            foundIconArt(6, texture, 360, 400, "box-texture", false, "Box texture"),
            foundIconArt(7, disc, 400, 400, "support-2d", false, "Disc / cart"),
        )
        val shown = found.filter { iconArtTypeMatches(type, it.providerMediaType) }
        val uploaded = savedWithArt(1, 400, 400, art.getValue("square"), label = "Uploaded image")
        // The saved box and the Default's automatic art are the one portrait picture: the Default tile (a 3:4 box here) paints it through
        // the picture the saved tile has already decoded, as every capture on this page does.
        val savedBox = savedWithArt(2, 300, 400, art.getValue("portrait"))
        val base = stateFor(
            "Icon art",
            defaultArt = HomeCustomEnrichDefaultArt.Automatic(File(art.getValue("portrait")).toURI().toString()),
            savedChoices = listOf(uploaded, savedBox),
            results = shown,
        ).copy(
            savedLanes = mapOf(uploaded.assetId to IconArtLane.SQUARE, savedBox.assetId to IconArtLane.BOX),
            activeAssetId = uploaded.assetId,
        )
        return base.copy(
            filters = base.filters.map {
                if (it.key == IconArtTypeFilterKey) it.copy(selectedIndex = it.values.indexOf(type)) else it
            },
        )
    }

    /**
     * Type All is ten tiles wide and scrolls: it opens on the Default tile (the strip at its start, the column asserted), and
     * [focusLast] focuses the last result instead, which scrolls the strip to its end so the right frame edge is seen as well.
     */
    private fun iconArtOnMain(type: String, font: Float, fileName: String, focusLast: Boolean = false) {
        val state = iconArtState(type)
        capture(
            state,
            fileName,
            font = font,
            alsoTo = "build/test-captures/shelf4/$fileName",
            afterMount = {
                waitForArt()
                val focused = if (focusLast) HomeCustomEnrichAsset.Result(state.results.last()) else HomeCustomEnrichAsset.Automatic
                authority.requestFocus(homeCustomEnrichAssetKey(state, focused))
                waitForArt()
            },
        )
        val tiles = tileSizesDp()
        val bar = barHeightDpF()
        println("ICON-ART $type font $font: tile widths ${tiles.map { it.first }} heights ${tiles.map { it.second }}")
        // Every tile stands on the same height, the art at the certified 112 dp and the bar under it (Main has the room).
        tiles.forEach { (_, h) -> assertEquals("a tile's art keeps 112 dp and the bar is under it: $h", 112f + bar, h, 0.6f) }
        // And each is the shape of its art: a square is 112 wide, a box is narrower than a square.
        val resultWidths = state.results.associate { r ->
            r.stableId to compose.onAllNodesWithTag(
                customMediaTileTag(HomeCustomEnrichAsset.Result(r).stableId),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().first().size.width / density
        }
        println("ICON-ART $type font $font: result tile widths $resultWidths")
        state.results.forEach { r ->
            val icon = r as HomeCustomEnrichResult.Icon
            val lane = iconArtLaneOf(icon.providerMediaType, icon.candidate.savedSource == EnrichSavedSource.STEAMGRIDDB)
            val w = resultWidths.getValue(r.stableId)
            if (lane == IconArtLane.BOX) {
                assertTrue("a box tile is narrower than a square: ${icon.providerMediaType} $w dp", w < 111f)
            } else {
                assertEquals("a square or article tile is a square: ${icon.providerMediaType} $w dp", 112f, w, 0.6f)
            }
        }
        if (type == IconArtType.All) {
            assertTrue("Type All holds squares and boxes side by side: $resultWidths", resultWidths.values.any { it < 111f } && resultWidths.values.any { it >= 111f })
        }
        if (!focusLast) {
            assertOneLeftColumn("Icon art $type, Main, font $font")
            assertDefaultBarSaysDefault("Icon art $type, Main, font $font")
        }
    }

    @Test
    fun `the merged Icon art page with Type All shows squares, boxes and the article side by side on Main at font 1_0`() =
        iconArtOnMain(IconArtType.All, 1.0f, "iconart-all-1.0.png")

    @Test
    fun `the merged Icon art page with Type All shows squares, boxes and the article side by side on Main at font 1_3`() =
        iconArtOnMain(IconArtType.All, 1.3f, "iconart-all-1.3.png")

    @Test
    fun `the merged Icon art page with Type All scrolled to its last result keeps the article tile whole at the right edge on Main at font 1_0`() =
        iconArtOnMain(IconArtType.All, 1.0f, "iconart-all-end-1.0.png", focusLast = true)

    @Test
    fun `the merged Icon art page with Type Square shows Style and Size and only squares on Main at font 1_0`() =
        iconArtOnMain(IconArtType.Square, 1.0f, "iconart-square-1.0.png")

    @Test
    fun `the merged Icon art page with Type Square shows Style and Size and only squares on Main at font 1_3`() =
        iconArtOnMain(IconArtType.Square, 1.3f, "iconart-square-1.3.png")

    @Test
    fun `the merged Icon art page with Type 3D shows Region and only the 3D box on Main at font 1_0`() =
        iconArtOnMain(IconArtType.ThreeD, 1.0f, "iconart-3d-1.0.png")

    @Test
    fun `the merged Icon art page with Type 3D shows Region and only the 3D box on Main at font 1_3`() =
        iconArtOnMain(IconArtType.ThreeD, 1.3f, "iconart-3d-1.3.png")

    /**
     * The panel is OPAQUE: a vertical strip down its middle third, clear of its rows' words, its eyebrow and its check marks, reads one tone from the top of
     * the panel to the foot, over the page's busiest part (chips, tile art, the status words) that the glass used to let through. Measured as the spread of the
     * strip's luminance (0..255): the see-through panel spread 10 to 17, an opaque one under 3.
     */
    private fun assertPanelOpaque(panelTag: String, what: String) {
        val panel = compose.onAllNodesWithTag(panelTag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val image = drawWindow()
        val x0 = (panel.left + 0.30f * panel.width).toInt()
        val x1 = (panel.left + 0.40f * panel.width).toInt()
        val lums = ArrayList<Double>()
        var y = (panel.top + 10f).toInt()
        while (y < (panel.bottom - 10f).toInt()) {
            var sum = 0.0
            var n = 0
            var x = x0
            while (x < x1) {
                val c = image.getPixel(x, y)
                sum += 0.30 * android.graphics.Color.red(c) + 0.59 * android.graphics.Color.green(c) + 0.11 * android.graphics.Color.blue(c)
                n += 1
                x += 2
            }
            lums += sum / n
            y += 2
        }
        val spread = lums.max() - lums.min()
        println("PANEL-OPAQUE $what: strip x $x0..$x1, y ${panel.top}..${panel.bottom}: luminance ${lums.min()}..${lums.max()}, spread $spread")
        assertTrue("$what: nothing behind the panel reads through it: the luminance spread down its middle is $spread (an opaque ground is under 3)", spread < 3.0)
    }

    @Test
    fun `the Customize media page opens on Icon art with its Media panel naming the four types on Main at font 1_0`() {
        val state = iconArtState(IconArtType.All).copy(mediaPanelOpen = true)
        capture(state, "iconart-media-panel-1.0.png", alsoTo = "build/test-captures/shelf4/iconart-media-panel-1.0.png", afterMount = { waitForArt() })
        assertPanelOpaque(CustomMediaMediaPanelTag, "Media panel, font 1.0")
        listOf("Icon art", "Launch card", "Hero art", "Hero Motion").forEach { label ->
            assertTrue("the Media panel lists $label", compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        assertEquals("and never Shelves", 0, compose.onAllNodesWithText("Shelves", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the Customize media page opens on Icon art with its Media panel naming the four types on Main at font 1_3`() {
        val state = iconArtState(IconArtType.All).copy(mediaPanelOpen = true)
        capture(state, "iconart-media-panel-1.3.png", font = 1.3f, alsoTo = "build/test-captures/shelf4/iconart-media-panel-1.3.png", afterMount = { waitForArt() })
        assertPanelOpaque(CustomMediaMediaPanelTag, "Media panel, font 1.3")
        assertEquals("never Shelves", 0, compose.onAllNodesWithText("Shelves", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the merged Icon art page with the Type value panel open lists every shape on Main at font 1_0`() {
        val base = iconArtState(IconArtType.All)
        val state = base.copy(openFilterKey = IconArtTypeFilterKey)
        capture(state, "iconart-type-panel-1.0.png", alsoTo = "build/test-captures/shelf4/iconart-type-panel-1.0.png", afterMount = { waitForArt() })
        assertPanelOpaque(CustomMediaFilterPanelTag, "Type panel, font 1.0")
        listOf("Square", "Front", "3D", "Article", "Texture", "Back").forEach { label ->
            assertTrue("the Type panel lists $label", compose.onAllNodesWithText(label, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    private val typeRowLabels = listOf("All", "Square", "Front", "3D", "Article", "Texture", "Back")

    /** One Type row as the page measures it: its own size and how much of it shows through the list's clip, in px, and the list's own bounds. */
    private fun typeRowMeasure(label: String): Triple<Float, Float, androidx.compose.ui.geometry.Rect> {
        val node = compose.onAllNodesWithText(label, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().first()
        val list = compose.onAllNodesWithTag("custom-media-filter-rows", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        return Triple(node.size.height.toFloat(), node.boundsInWindow.height, list)
    }

    /** The row [label] stands whole in the list, and a whole focus ring's reach clear of the list's clip above and below it (the ring is not cut flat). */
    private fun assertTypeRowWholeWithRing(label: String, what: String) {
        val node = compose.onAllNodesWithText(label, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().first()
        val (size, showing, list) = typeRowMeasure(label)
        val reach = MimirFocusTileRingReach.value * density
        val bounds = node.boundsInWindow
        println("ICON-ART-TYPE-PANEL $what $label: size $size px, showing $showing px, row ${bounds.top}..${bounds.bottom}, list ${list.top}..${list.bottom}, ring reach $reach px")
        assertEquals("$what: the row $label shows whole: $showing of $size px", size, showing, 0.6f)
        assertTrue("$what: the ring above $label is clear of the list's clip: ${bounds.top - reach} vs ${list.top}", bounds.top - reach >= list.top - 0.6f)
        assertTrue("$what: the ring below $label is clear of the list's clip: ${bounds.bottom + reach} vs ${list.bottom}", bounds.bottom + reach <= list.bottom + 0.6f)
    }

    /**
     * Seven rows are the longest list this shared picker has held (the old Type filters held five). At font 1.3 they stand 49 px tall each and the panel's
     * list scrolls inside the window less a margin; the pad lights one row at a time, and the list scrolls a lit row that shows only in part until it is whole
     * with a ring's reach clear (ActivityPickerRows: the page lends it the lit key). Every row, the seventh included, is whole with its ring when it is lit.
     */
    @Test
    fun `the merged Icon art page with the Type value panel open lights each of its seven shapes whole, ring and all, at font 1_3`() {
        val base = iconArtState(IconArtType.All)
        val state = base.copy(openFilterKey = IconArtTypeFilterKey)
        val typeFilter = state.filters.first { it.key == IconArtTypeFilterKey }
        capture(state, "iconart-type-panel-1.3.png", font = 1.3f, alsoTo = "build/test-captures/shelf4/iconart-type-panel-1.3.png", afterMount = { waitForArt() })
        assertPanelOpaque(CustomMediaFilterPanelTag, "Type panel, font 1.3")
        val sizes = typeRowLabels.map { typeRowMeasure(it).first }
        sizes.forEach { assertEquals("every Type row is the same size: $sizes", sizes.first(), it, 0.6f) }
        typeFilter.values.indices.forEach { index ->
            authority.requestFocus(homeCustomEnrichFilterOptionKey(state, typeFilter, index))
            waitForArt()
            assertTypeRowWholeWithRing(typeRowLabels[index], "font 1.3, lit")
        }
    }

    @Test
    fun `the merged Icon art page with the Type value panel open shows the last shape whole with its ring once the pad reaches it at font 1_3`() {
        val base = iconArtState(IconArtType.All)
        val state = base.copy(openFilterKey = IconArtTypeFilterKey)
        val typeFilter = state.filters.first { it.key == IconArtTypeFilterKey }
        capture(
            state,
            "iconart-type-panel-back-1.3.png",
            font = 1.3f,
            alsoTo = "build/test-captures/shelf4/iconart-type-panel-back-1.3.png",
            afterMount = {
                waitForArt()
                authority.requestFocus(homeCustomEnrichFilterOptionKey(state, typeFilter, typeFilter.values.lastIndex))
                waitForArt()
            },
        )
        assertTypeRowWholeWithRing("Back", "font 1.3, Back lit")
    }
}
