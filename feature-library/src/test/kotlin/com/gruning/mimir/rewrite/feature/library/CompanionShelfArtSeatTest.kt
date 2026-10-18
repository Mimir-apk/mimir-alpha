package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.WiredFace
import com.gruning.mimir.rewrite.core.ui.MediaArtBookTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Changing a game's shelf art on Bottom previously changed its painted size. Each platform needs a
 * standard art seat per grid configuration. The Bottom's platform shelf cut one uniform cell per game,
 * but fitted each game's art into the cell's own shape, so a
 * face of another proportion than the platform's box art painted at another size. Every game's art now sits in the platform's ART SEAT
 * ([companionShelfArtSeat]), the same rect for all of them at one density, whatever art each wears and whenever it arrives.
 *
 * The composed cases run at the Thor Bottom Presentation's size (537.7 x 468.3 dp at 369 dpi) on one platform (Genesis, a curated 0.71 cover) with three games:
 * the default cover, a wide hand-wired face and a tall one. The art goes through Coil's real request path, answered by a test interceptor that holds every request
 * until the case opens a gate, so "before the art arrives" is a real state and not a race, and "after" waits until every bitmap has been delivered and painted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionShelfArtSeatTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private var platformId = "genesis"

    private val rows = MutableStateFlow<List<GameRow>>(emptyList())
    private val gate = CompletableDeferred<Unit>()
    private val delivered = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val bitmaps = mutableMapOf<String, Bitmap>()

    private inner class LiveRepository : LibraryRepository by FakeLibraryRepository() {
        override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> = rows
    }

    @Before
    fun answerEveryArtRequestFromTheGate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context)
            .components {
                add(
                    Interceptor { chain ->
                        val uri = chain.request.data.toString()
                        gate.await()
                        val bitmap = checkNotNull(bitmaps[uri]) { "no test bitmap for $uri" }
                        delivered += uri
                        SuccessResult(
                            drawable = BitmapDrawable(chain.request.context.resources, bitmap),
                            request = chain.request,
                            dataSource = DataSource.MEMORY,
                        )
                    },
                )
            }
            .build()
        Coil.setImageLoader(loader)
    }

    @After
    fun releaseLoader() = Coil.reset()

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────

    private fun art(name: String, width: Int, height: Int): String {
        val uri = "file:///media_art/genesis/$name.png"
        bitmaps[uri] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return uri
    }

    /** The provider's cover at Genesis's own proportion (142 x 200 = 0.71). */
    private val coverUri = art("cover", 142, 200)
    private val otherCoverUri = art("other-cover", 142, 200)

    /** A 16:9 face the player wired by hand. */
    private val wideUri = art("wide-face", 400, 225)

    /** A tall face the player wired by hand (9:16). */
    private val tallUri = art("tall-face", 225, 400)

    private fun game(id: String, art: String, wired: String? = null) = GameRow(
        gameId = GameId(id),
        title = "Game $id",
        platformId = PlatformId(platformId),
        memberCount = 1,
        duplicateKind = null,
        artUri = art,
        wiredFace = wired?.let { WiredFace(uri = it, physical = false) },
    )

    /** A game with no art at all: the book, with its title on the leather. */
    private fun coverless(id: String, title: String = "Game $id") = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId(platformId),
        memberCount = 1,
        duplicateKind = null,
    )

    private fun fourGames() = listOf(
        game("g1", art = coverUri),
        game("g2", art = otherCoverUri, wired = wideUri),
        game("g3", art = otherCoverUri, wired = tallUri),
        coverless("g4"),
    )

    private fun mount(step: CompanionShelfDensity, games: List<GameRow>) {
        rows.value = games
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).testTag("seat-host")) {
                        CompanionPlatformShelf(
                            platformIdValue = platformId,
                            label = "Genesis",
                            repository = LiveRepository(),
                            filedOrder = emptyList(),
                            onArranged = {},
                            onBack = {},
                            onLaunch = {},
                            active = true,
                            density = step,
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 1_000L) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun deliveries(uri: String): Int = synchronized(delivered) { delivered.count { it == uri } }

    /** Opens the gate (art may now arrive) and waits until each of [uris] has been delivered [atLeast] times and painted. */
    private fun awaitDelivered(uris: Collection<String>, atLeast: Map<String, Int> = emptyMap()) {
        gate.complete(Unit)
        repeat(200) {
            if (uris.all { deliveries(it) >= (atLeast[it] ?: 1) }) {
                settle(500L)
                return
            }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50L)
        }
        compose.waitForIdle()
        throw AssertionError("the art never arrived: wanted $uris, delivered $delivered")
    }

    private fun boundsOf(tag: String): List<Rect> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun seats(): List<Rect> = boundsOf(CompanionShelfTileArtTag)

    private fun sizeOf(rect: Rect): IntSize = IntSize(Math.round(rect.width), Math.round(rect.height))

    private fun assertOneSize(message: String, found: List<Rect>, expectedCount: Int): IntSize {
        assertEquals("$message: how many were found", expectedCount, found.size)
        val sizes = found.map(::sizeOf).distinct()
        assertEquals("$message: they must all be the same size, was $sizes", 1, sizes.size)
        return sizes.single()
    }

    // ── The seat's arithmetic ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the seat is the largest rect of the platform proportion that fits the region`() {
        // A Standard cell's art region (about 117 x 133 dp): taller than the cover, so the cover's height sets it.
        assertEquals(IntSize(94, 133), companionShelfArtSeat(117, 133, 0.71f))
        // A wide platform (N64 1.46) in a narrow Dense cell: the width sets it.
        assertEquals(IntSize(74, 51), companionShelfArtSeat(74, 113, 1.46f))
        // A square platform in a square region fills it.
        assertEquals(IntSize(80, 80), companionShelfArtSeat(80, 80, 1f))
    }

    @Test
    fun `the seat never leaves its region and keeps the platform proportion`() {
        for (aspect in listOf(0.59f, 0.71f, 0.72f, 1f, 1.11f, 1.46f)) {
            for (w in listOf(40, 74, 117, 247, 505)) {
                for (h in listOf(40, 113, 133, 324, 362)) {
                    val seat = companionShelfArtSeat(w, h, aspect)
                    assertTrue("seat $seat leaves the region ${w}x$h at $aspect", seat.width in 1..w && seat.height in 1..h)
                    assertTrue("seat $seat touches no edge of ${w}x$h at $aspect", seat.width == w || seat.height == h)
                    val ratio = seat.width.toFloat() / seat.height
                    assertTrue("seat $seat is $ratio, not $aspect", abs(ratio - aspect) <= 0.03f + 1f / minOf(seat.width, seat.height))
                }
            }
        }
    }

    @Test
    fun `an empty region has no seat and an unusable proportion reads as the default cover`() {
        assertEquals(IntSize.Zero, companionShelfArtSeat(0, 100, 0.71f))
        assertEquals(IntSize.Zero, companionShelfArtSeat(100, 0, 0.71f))
        val expected = companionShelfArtSeat(117, 133, DefaultCoverAspect)
        assertEquals(expected, companionShelfArtSeat(117, 133, Float.NaN))
        assertEquals(expected, companionShelfArtSeat(117, 133, 0f))
        assertEquals(expected, companionShelfArtSeat(117, 133, -1f))
    }

    // ── Three games, three kinds of art, one seat, at every grid configuration ──────────────────────

    private fun assertOneSeatAtEveryGame(step: CompanionShelfDensity) {
        mount(step, fourGames())
        // Large holds two games a page; the other two are on the next page, laid out by the same solve.
        val firstPage = if (step.perPage >= 4) 4 else 2

        // Before anything has arrived: the seat is already there, at its size (the gate is closed, so no art has).
        assertEquals("no art has arrived yet", 0, synchronized(delivered) { delivered.size })
        val beforeSeats = seats()
        val beforeTiles = boundsOf(CompanionShelfTileTag)
        val beforeSize = assertOneSize("${step.name} before the art arrives", beforeSeats, firstPage)

        awaitDelivered(listOf(coverUri, wideUri, tallUri).take(minOf(firstPage, 3)))

        // The room a tile leaves above its label, read while its page is the one on screen (a label is gone with its page).
        val xs = MimirSpacing.xs.value * density369
        fun labelLane(tile: Rect): Float = if (step.showsLabel) {
            boundsOf(CompanionShelfTileLabelTag)
                .first { it.left >= tile.left - 0.5f && it.right <= tile.right + 0.5f && it.top >= tile.top - 0.5f && it.bottom <= tile.bottom + 0.5f }
                .height + xs
        } else {
            0f
        }
        val seen = mutableListOf<Triple<Rect, Rect, Float>>() // a tile, its seat and its label lane
        fun collectPage() {
            val tiles = boundsOf(CompanionShelfTileTag)
            for (seat in seats()) {
                val tile = tiles.single { it.left <= seat.left + 0.5f && it.right >= seat.right - 0.5f && it.top <= seat.top + 0.5f && it.bottom >= seat.bottom - 0.5f }
                seen += Triple(tile, seat, labelLane(tile))
            }
        }
        val tiles = boundsOf(CompanionShelfTileTag)
        collectPage()
        if (step.perPage < 4) {
            compose.onNodeWithTag(CompanionShelfPageAreaTag).performTouchInput { swipeUp() }
            settle()
            awaitDelivered(listOf(tallUri))
            assertEquals("page 2 holds the other two games", 2, seats().size)
            collectPage()
        }
        val found = seen.map { it.second }
        val size = assertOneSize("${step.name} after the art arrived (a default cover, a wide face, a tall face and a coverless book)", found, 4)
        assertEquals("${step.name}: arriving art must not move the seat", beforeSize, size)
        assertEquals("${step.name}: arriving art must not move the tiles", beforeTiles.map(::sizeOf).distinct(), tiles.map(::sizeOf).distinct())

        // The seat is the platform's proportion (Genesis 0.71), whatever the game wears: the wide face, the tall face and the book do not change it.
        val aspect = platformCoverAspect(platformId)
        assertEquals(0.71f, aspect, 0.001f)
        val ratio = size.width.toFloat() / size.height
        assertTrue("${step.name}: the seat is $ratio, not the platform's $aspect", abs(ratio - aspect) <= 0.02f)

        // And it is the seat the arithmetic gives for the room the tile leaves above its label.
        for ((tile, seat, lane) in seen) {
            val region = companionShelfArtSeat(Math.round(tile.width), Math.round(tile.height - lane), aspect)
            assertTrue(
                "${step.name}: the seat ${sizeOf(seat)} is not the solved $region",
                abs(region.width - seat.width) <= 1.5f && abs(region.height - seat.height) <= 1.5f,
            )
            assertTrue(
                "${step.name}: the seat sits in its tile",
                seat.left >= tile.left - 0.6f && seat.right <= tile.right + 0.6f && seat.top >= tile.top - 0.6f && seat.bottom <= tile.bottom + 0.6f,
            )
        }
        val tileSizes = seen.map { sizeOf(it.first) }.distinct()
        assertEquals("every game on the shelf was measured", 4, seen.size)
        assertEquals("${step.name}: the tiles are one size too, was $tileSizes", 1, tileSizes.size)
        println("SEAT-4 ${step.name} ${step.readout} seat=${size.width}x${size.height}px tile=${tileSizes.single().width}x${tileSizes.single().height}px aspect=${"%.3f".format(ratio)}")
    }

    @Test
    fun `Large - a default cover, a wide face, a tall face and a coverless book wear one seat`() = assertOneSeatAtEveryGame(CompanionShelfDensity.LARGE)

    @Test
    fun `Standard - a default cover, a wide face, a tall face and a coverless book wear one seat`() = assertOneSeatAtEveryGame(CompanionShelfDensity.STANDARD)

    @Test
    fun `Dense - a default cover, a wide face, a tall face and a coverless book wear one seat`() = assertOneSeatAtEveryGame(CompanionShelfDensity.DENSE)

    // ── The book at Dense carries the game's only name ─────────────────────────────────────────────

    private class BookTitle(val layout: TextLayoutResult, val bounds: Rect)

    private fun bookTitle(): BookTitle {
        val node = compose.onAllNodes(hasTestTag(MediaArtBookTitleTag), useUnmergedTree = true).fetchSemanticsNodes().single()
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) { "the book's title has no text layout" }
        check(action(layouts)) { "the book's title has no text layout yet" }
        return BookTitle(layouts.single(), node.boundsInRoot)
    }

    private fun assertTitleIsWhole(where: String, title: BookTitle, seat: Rect) {
        // Inside the seat, so inside the tile: the leather clips at its own edge, and a title past the seat is a title cut there.
        assertTrue(
            "$where: the title ${title.bounds} must sit inside the seat $seat",
            title.bounds.left >= seat.left - 0.6f && title.bounds.right <= seat.right + 0.6f &&
                title.bounds.top >= seat.top - 0.6f && title.bounds.bottom <= seat.bottom + 0.6f,
        )
        // Above the embossed Mannaz low on the leaf (84 % of the book).
        assertTrue("$where: the title runs into the rune: ${title.bounds} in $seat", title.bounds.bottom <= seat.top + seat.height * 0.84f)
        val layout = title.layout
        assertTrue("$where: at most three lines, was ${layout.lineCount}", layout.lineCount in 1..3)
        // Every line is whole: it ends inside the text's own box, which is inside the seat (no glyph is cut at either edge). An ellipsised line is the
        // exception to the right-edge read: the layout reports its untruncated extent, and the ellipsis is what stands at the box's edge.
        for (line in 0 until layout.lineCount) {
            if (!layout.isLineEllipsized(line)) {
                assertTrue("$where: line $line is cut at the right edge: ${layout.getLineRight(line)} of ${layout.size.width}", layout.getLineRight(line) <= layout.size.width + 0.5f)
            }
            assertTrue("$where: line $line is cut at the bottom: ${layout.getLineBottom(line)} of ${layout.size.height}", layout.getLineBottom(line) <= layout.size.height + 0.5f)
        }
        assertTrue("$where: the layout is taller than its box", layout.size.height <= title.bounds.height + 0.5f)
    }

    private fun mountDenseCoverless(platform: String, title: String): Rect {
        platformId = platform
        mount(CompanionShelfDensity.DENSE, listOf(coverless("b1", title)))
        val seat = seats().single()
        val tile = boundsOf(CompanionShelfTileTag).single()
        val solved = companionShelfArtSeat(Math.round(tile.width), Math.round(tile.height), platformCoverAspect(platform))
        assertTrue("the book's seat ${sizeOf(seat)} is the platform's seat $solved", abs(solved.width - seat.width) <= 1.5f && abs(solved.height - seat.height) <= 1.5f)
        assertEquals("Dense draws no label: the book carries the only name", 0, boundsOf(CompanionShelfTileLabelTag).size)
        return seat
    }

    @Test
    fun `Dense - a long name in the book on a wide platform is ellipsised at the floor, never cut`() {
        val seat = mountDenseCoverless("n64", "The Legend of Zelda Ocarina of Time Master Quest Collectors Edition")
        val title = bookTitle()
        println("BOOK-DENSE n64 seat=${seat.width}x${seat.height}px title=${title.bounds.width}x${title.bounds.height}px lines=${title.layout.lineCount} sp=${title.layout.layoutInput.style.fontSize.value} ellipsized=${title.layout.isLineEllipsized(title.layout.lineCount - 1)}")
        assertTitleIsWhole("n64 Dense", title, seat)
        assertEquals("a name that cannot fit shrinks to the type scale's floor", MimirTypeStep.Micro.value, title.layout.layoutInput.style.fontSize.value, 0.01f)
        assertTrue("and ends in an ellipsis on its last line", title.layout.isLineEllipsized(title.layout.lineCount - 1))
        assertTrue("in as many of the three lines as the seat holds: ${title.layout.lineCount}", title.layout.lineCount in 2..3)
    }

    @Test
    fun `Dense - a long name in the book on a portrait platform also stays whole inside the seat`() {
        val seat = mountDenseCoverless("psp", "The Legend of Zelda Ocarina of Time Master Quest Collectors Edition")
        val title = bookTitle()
        println("BOOK-DENSE psp seat=${seat.width}x${seat.height}px title=${title.bounds.width}x${title.bounds.height}px lines=${title.layout.lineCount} sp=${title.layout.layoutInput.style.fontSize.value} ellipsized=${title.layout.isLineEllipsized(title.layout.lineCount - 1)}")
        assertTitleIsWhole("psp Dense", title, seat)
        assertTrue("never below the floor", title.layout.layoutInput.style.fontSize.value >= MimirTypeStep.Micro.value - 0.01f)
    }

    @Test
    fun `Dense - a short name in the book is not shrunk or ellipsised`() {
        val seat = mountDenseCoverless("n64", "Zelda")
        val title = bookTitle()
        println("BOOK-DENSE short seat=${seat.width}x${seat.height}px lines=${title.layout.lineCount} sp=${title.layout.layoutInput.style.fontSize.value}")
        assertTitleIsWhole("short name", title, seat)
        assertFalse("a short name is whole, no ellipsis", (0 until title.layout.lineCount).any { title.layout.isLineEllipsized(it) })
        assertTrue("and keeps the book's own size, not the floor: ${title.layout.layoutInput.style.fontSize.value}", title.layout.layoutInput.style.fontSize.value > MimirTypeStep.Micro.value)
    }

    // ── Changing a game's art while the shelf is open ──────────────────────────────────────────────

    @Test
    fun `changing a games art at runtime does not change its tile or its seat`() {
        val plain = listOf(game("g1", art = coverUri), game("g2", art = otherCoverUri), game("g3", art = coverUri))
        mount(CompanionShelfDensity.STANDARD, plain)
        awaitDelivered(listOf(coverUri, otherCoverUri))
        val seatBefore = assertOneSize("default art", seats(), 3)
        val tilesBefore = boundsOf(CompanionShelfTileTag).map(::sizeOf).distinct()
        assertEquals(1, tilesBefore.size)

        // The player wires a WIDE face onto g2 ...
        rows.value = listOf(plain[0], game("g2", art = otherCoverUri, wired = wideUri), plain[2])
        settle()
        awaitDelivered(listOf(wideUri))
        assertEquals("a wide face: the seat is unchanged", seatBefore, assertOneSize("wide face", seats(), 3))
        assertEquals("a wide face: the tile is unchanged", tilesBefore, boundsOf(CompanionShelfTileTag).map(::sizeOf).distinct())

        // ... then a TALL one ...
        rows.value = listOf(plain[0], game("g2", art = otherCoverUri, wired = tallUri), plain[2])
        settle()
        awaitDelivered(listOf(tallUri))
        assertEquals("a tall face: the seat is unchanged", seatBefore, assertOneSize("tall face", seats(), 3))
        assertEquals("a tall face: the tile is unchanged", tilesBefore, boundsOf(CompanionShelfTileTag).map(::sizeOf).distinct())

        // ... and back to the default cover.
        val coversBefore = deliveries(otherCoverUri)
        rows.value = plain
        settle()
        awaitDelivered(listOf(otherCoverUri), atLeast = mapOf(otherCoverUri to coversBefore + 1))
        assertEquals("back to the default: the seat is unchanged", seatBefore, assertOneSize("default again", seats(), 3))
        assertEquals("back to the default: the tile is unchanged", tilesBefore, boundsOf(CompanionShelfTileTag).map(::sizeOf).distinct())
    }

    // ── Custom art never teaches the platform its proportion ───────────────────────────────────────

    private fun media(source: LibraryListPreviewMediaSource) =
        LibraryListPreviewMedia(artUri = "file:///x.png", aspectRatio = 1f, source = source)

    private fun bareGame(wired: WiredFace? = null, art: String? = null, physical: String? = null) = GameRow(
        gameId = GameId("m1"),
        title = "M",
        platformId = PlatformId("xbox"),
        memberCount = 1,
        duplicateKind = null,
        artUri = art,
        physicalMediaUri = physical,
        wiredFace = wired,
    )

    @Test
    fun `only an automatic cover may teach the platform its proportion`() {
        val automatic = bareGame(art = "file:///c.png")
        assertTrue("the provider's cover measures", gamelistArtMayMeasurePlatform(automatic, libraryListPreviewMedia(automatic)))

        val wiredCover = bareGame(wired = WiredFace("file:///mine.png", physical = false), art = "file:///c.png")
        assertEquals("a wired cover is the COVER tier", LibraryListPreviewMediaSource.COVER, libraryListPreviewMedia(wiredCover).source)
        assertFalse("a hand-wired cover never measures", gamelistArtMayMeasurePlatform(wiredCover, libraryListPreviewMedia(wiredCover)))

        val wiredDisc = bareGame(wired = WiredFace("file:///disc.png", physical = true), art = "file:///c.png")
        assertFalse("a hand-wired disc never measures", gamelistArtMayMeasurePlatform(wiredDisc, libraryListPreviewMedia(wiredDisc)))

        val discOnly = bareGame(physical = "file:///support.png")
        assertEquals(LibraryListPreviewMediaSource.PHYSICAL, libraryListPreviewMedia(discOnly).source)
        assertFalse("a provider's disc render is not a cover", gamelistArtMayMeasurePlatform(discOnly, libraryListPreviewMedia(discOnly)))

        val blankWire = bareGame(wired = WiredFace("  ", physical = false), art = "file:///c.png")
        assertTrue("a blank wire is no wire (the ladder ignores it too)", gamelistArtMayMeasurePlatform(blankWire, libraryListPreviewMedia(blankWire)))

        val nothing = bareGame()
        assertFalse("no art measures nothing", gamelistArtMayMeasurePlatform(nothing, libraryListPreviewMedia(nothing)))
        assertFalse(gamelistArtMayMeasurePlatform(automatic, media(LibraryListPreviewMediaSource.UNRESOLVED)))
        assertFalse(gamelistArtMayMeasurePlatform(automatic, media(LibraryListPreviewMediaSource.FALLBACK)))
    }

    private fun source(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name")
        assertTrue("$name not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `the gamelist feeds the measured store only under that rule, and the shelf never feeds it`() {
        val gamelist = source("LibraryGameShelf.kt")
        val calls = gamelist.lines().count { it.contains("MeasuredCoverAspects.record(") }
        assertEquals("the one call site (this test must not pass on nothing)", 1, calls)
        val gate = gamelist.substringBefore("MeasuredCoverAspects.record(").substringAfterLast("private fun MeasurePlatformCoverAspect(")
        assertTrue("the record call stands under gamelistArtMayMeasurePlatform: $gate", gate.contains("gamelistArtMayMeasurePlatform(game, media)"))
        assertTrue("and only for a platform nobody has settled: $gate", gate.contains("!platformCoverAspectIsSettled("))

        val shelf = source("CompanionPlatformShelf.kt")
        assertTrue("the shelf source was read", shelf.contains("companionShelfArtSeat("))
        assertFalse("the shelf only reads the store", shelf.contains("MeasuredCoverAspects.record("))
        assertFalse("and no art's own aspect reaches its layout", shelf.contains("MeasuredArtAspects"))
    }
}
