package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.library.WiredFace
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MediaArtBookTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirArtAvailability
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import java.io.File
import java.util.Collections
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The Library's game shelf next to Home's, on the Thor's / Odin's 897 x 505 dp at font scale 1.0 and 1.3 (density 2), and on two
 * smaller screens. The real [LibraryGameShelf] stands under the real [GamelistHeader] in the gamelist Column's own padding, on the
 * house background; Home's shelf ([HomeShelfTitle] and [GameShelf]) is mounted in the same window for the same games. The scene is
 * not [LibraryScreen] / [HomeShelfScreen] because those wrap their bands in page transitions whose recorded layers cannot be drawn
 * into the software bitmap this repo rasterises with.
 *
 * Eight games wear the faces a Library can hand the shelf: a SQUARE tile that is a favourite and a two-disc title (green), a
 * LEDGE_COVER box that is a favourite (red), a plain square and a plain cover to read the marks against, no art at all (the runed
 * book), a PHYSICAL article (blue) that is a three-version title, and more covers. For every seat the ring is moved onto it and the
 * frame is written to `build/test-captures/shelf3/` (and `build/shelf3/`). Beside the PNGs the cases assert what the pixels say: each seat paints
 * its own colour in its own proportion, the ring stands on the selected seat and its stroke hugs the painted art, the two marks sit on
 * the corners of the art of the games that own them and of no other, the runed book is the no-art face, the Library's tiles stand
 * exactly where Home's do and are exactly as large, and the title block shares the header's left edge and keeps to one line. The
 * PNGs are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryShelfCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val delivered = Collections.synchronizedList(mutableListOf<String>())
    private val tempFiles = mutableListOf<File>()

    @Before
    fun answerEveryArtRequestFromTheTestBitmaps() {
        MimirArtAvailability.clearForTest()
        MimirArtAvailability.useDispatcherForTest(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components {
                    add(
                        Interceptor { chain ->
                            val uri = chain.request.data.toString()
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
                .build(),
        )
    }

    @After
    fun tearDown() {
        Coil.reset()
        MimirArtAvailability.useDispatcherForTest(null)
        tempFiles.forEach { it.delete() }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────

    private val greenInk = AndroidColor.rgb(30, 170, 70)
    private val redInk = AndroidColor.rgb(190, 45, 45)
    private val blueInk = AndroidColor.rgb(40, 90, 200)

    /** A real file (the shelf stats its art source) that Coil's interceptor answers by its uri. */
    private fun art(width: Int, height: Int, draw: (Canvas) -> Unit): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        val file = File.createTempFile("shelf3-art", ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tempFiles += file
        val uri = file.toURI().toString().replaceFirst("file:/", "file:///")
        bitmaps[uri] = bitmap
        return uri
    }

    private val squareUri by lazy {
        art(512, 512) { canvas ->
            canvas.drawColor(greenInk)
            canvas.drawRect(64f, 64f, 448f, 448f, Paint().apply { color = AndroidColor.argb(60, 255, 255, 255) })
        }
    }
    private val coverUri by lazy {
        art(300, 420) { canvas ->
            canvas.drawColor(redInk)
            canvas.drawRect(0f, 300f, 300f, 420f, Paint().apply { color = AndroidColor.argb(90, 0, 0, 0) })
        }
    }

    /** The game article is a disc on a transparent square: the shelf must hug the disc's proportion, not paint a box. */
    private val discUri by lazy {
        art(400, 400) { canvas ->
            canvas.drawCircle(200f, 200f, 190f, Paint().apply { color = blueInk; isAntiAlias = true })
            canvas.drawCircle(200f, 200f, 36f, Paint().apply { color = AndroidColor.argb(255, 12, 12, 24); isAntiAlias = true })
        }
    }

    private fun game(
        index: Int,
        title: String,
        artUri: String? = null,
        wiredFace: WiredFace? = null,
        favorite: Boolean = false,
        memberCount: Int = 1,
        kind: DuplicateGroupKind? = null,
        discs: Int = 0,
    ) = GameRow(
        gameId = GameId("game-$index"),
        title = title,
        platformId = PlatformId.GBA,
        memberCount = memberCount,
        duplicateKind = kind,
        discCount = discs,
        isFavorite = favorite,
        genre = "Platformer",
        artUri = artUri,
        wiredFace = wiredFace,
    )

    private fun eightGames(firstTitle: String = "Square Tile") = listOf(
        game(0, firstTitle, wiredFace = WiredFace(squareUri, ShelfFaceShape.SQUARE), favorite = true, memberCount = 2, kind = DuplicateGroupKind.MULTI_DISC, discs = 2),
        game(1, "Ledge Cover", artUri = coverUri, favorite = true),
        game(2, "Plain Square", wiredFace = WiredFace(squareUri, ShelfFaceShape.SQUARE)),
        game(3, "Plain Cover", artUri = coverUri),
        game(4, "No Art At All"),
        game(5, "Game Article", wiredFace = WiredFace(discUri, ShelfFaceShape.PHYSICAL), memberCount = 3, kind = DuplicateGroupKind.CATALOG_TITLE_ID),
        game(6, "Further Cover", artUri = coverUri),
        game(7, "Last Game", artUri = coverUri),
    )

    // ── Harness ────────────────────────────────────────────────────────────────────────────────────

    private enum class Scene { LIBRARY, HOME }

    private val scene = mutableStateOf(Scene.LIBRARY)
    private val homeScope = FocusScope("capture/home-shelf")
    private val homeMemory = GameShelfWindowMemory()
    private var screenW = 897f
    private var screenH = 505f
    private lateinit var games: List<GameRow>

    private fun mount(rows: List<GameRow>, widthDp: Float, heightDp: Float, density: Float, fontScale: Float) {
        games = rows
        screenW = widthDp
        screenH = heightDp
        authority.pushScope(GamelistScope)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        when (scene.value) {
                            Scene.LIBRARY -> LibraryScene(rows)
                            Scene.HOME -> HomeScene(rows)
                        }
                    }
                }
            }
        }
        settle()
        authority.requestFocus(gameKey(rows[0]))
        // Only the first window's games are mounted, so only theirs are asked for (the strip mounts a seat's art when it scrolls in).
        awaitArt(rows.take(4))
    }

    /** The gamelist body as the screen builds it: the Column's own padding, the header, and the shelf in the rest. */
    @Composable
    private fun LibraryScene(rows: List<GameRow>) {
        val focus by authority.state.collectAsState()
        val density = LocalDensity.current
        val bottomInset = with(density) {
            gamelistListBottomControlInsetDp(hintTextHeightDp = MimirTypeScale.ControlHintCompact.fontSize.toDp().value).dp
        }
        MimirShelfBackground(modifier = Modifier.fillMaxSize(), showAtmosphericClouds = false) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            ) {
                GamelistHeader(
                    title = "Game Boy Advance",
                    metaLabel = "${rows.size} games",
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
                    mediaContrastRail = false,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LibraryGameShelf(
                        games = rows,
                        emptyMessage = "No games on this platform yet.",
                        isLoading = false,
                        searchQuery = "",
                        authority = authority,
                        focusState = focus,
                        centeredKey = authority.renderedFocusIn(GamelistScope, focus),
                        getArtFocused = false,
                        detailGame = null,
                        metaForGame = { "${it.genre} · GBA" },
                        statusForGame = { GameStatusChip("Ready", true) },
                        onOpenGame = {},
                        mediaContrastRail = false,
                        bottomControlInset = bottomInset,
                        windowMemoryKey = "capture-library",
                        departureFrame = null,
                        shatter = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** What Home does: its scope, its keys, the same title block and the same shelf, the plate included. */
    @Composable
    private fun HomeScene(rows: List<GameRow>) {
        DisposableEffect(authority) {
            authority.pushScope(homeScope)
            onDispose { authority.popScope() }
        }
        val items = remember(rows) {
            rows.map { row ->
                GameShelfItem.Game(focusKey = homeKey(row), title = row.title, face = shelfFaceOf(row), platformIdValue = "gba")
            }
        }
        RegisterVirtualFocusRow(authority = authority, scope = homeScope, keys = items.map { it.focusKey })
        val focusState by authority.state.collectAsState()
        val selectedKey = if (focusState.scope == homeScope) focusState.focused else authority.rememberedFocusIn(homeScope)
        val compact = LocalMimirViewportProfile.current.isCompact
        val geometry = remember { solveHomeShelfGeometry(screenW, screenH, compact) }
        MimirShelfBackground(modifier = Modifier.fillMaxSize(), showAtmosphericClouds = false) {
            HomeShelfTitle(
                title = items.firstOrNull { it.focusKey == selectedKey }?.title,
                geometry = geometry,
                eyebrow = "Platformer · GBA",
                statusChip = GameStatusChip("Ready", true),
                stackStatus = compact,
            )
            val state = rememberGameShelfState(
                items = items,
                selectedKey = selectedKey,
                geometry = geometry,
                windowMemory = homeMemory,
                windowMemoryKey = "capture-home",
                surfaceEpoch = 0,
                reducedMotion = false,
            )
            GameShelf(
                scope = homeScope,
                authority = authority,
                focusState = focusState,
                onRequestFocus = { key -> authority.requestFocus(key) },
                onActivate = {},
                emptyContent = {},
                state = state,
            )
        }
    }

    private fun settle(ms: Long = 1_500L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** Waits until the art of [rows] has been asked for and answered, then lets the frame settle. */
    private fun awaitArt(rows: List<GameRow>) {
        val wanted = rows.flatMap { listOfNotNull(it.artUri, it.wiredFace?.uri) }.toSet()
        repeat(200) {
            if (synchronized(delivered) { delivered.containsAll(wanted) }) {
                settle()
                return
            }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50L)
        }
        throw AssertionError("the art never arrived: wanted $wanted, delivered $delivered")
    }

    /**
     * Moves the ring onto [index] and lets exactly one [settle] pass, as Home's captures do, so both doors are read at the SAME phase of
     * the ring's gleam (the settle delay, then the crossing, then the sparkle at the ring's corner). A second settle would carry the
     * Library's frames past the sparkle and make an armed gleam look absent. Art is awaited only for a game that scrolled in.
     */
    private fun focus(index: Int) {
        authority.requestFocus(gameKey(games[index]))
        settle()
        val wanted = listOfNotNull(games[index].artUri, games[index].wiredFace?.uri)
        if (!synchronized(delivered) { delivered.containsAll(wanted) }) awaitArt(listOf(games[index]))
    }

    /** Near-white pixels around the ring's bottom-right corner: the gleam's sparkle (nothing else on the scene is that bright there). */
    private fun sparklePixels(bitmap: Bitmap, ringTag: String): Int {
        val ring = compose.onNodeWithTag(ringTag).fetchSemanticsNode().boundsInRoot
        var count = 0
        // The sparkle stands on the painted art's corner, which a portrait cover keeps inside the seat's box: look at the corner's quarter.
        for (y in (ring.bottom.toInt() - 60)..(ring.bottom.toInt() + 10)) {
            for (x in (ring.right.toInt() - 60)..(ring.right.toInt() + 10)) {
                if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) continue
                val p = bitmap.getPixel(x, y)
                if (AndroidColor.red(p) >= 225 && AndroidColor.green(p) >= 225 && AndroidColor.blue(p) >= 215) count++
            }
        }
        return count
    }

    /**
     * The scene as the window draws it, cropped to the scene's own box: the test activity wears an action bar the app does not, so the
     * content frame starts below it, and every coordinate this test reads (`boundsInRoot`) is relative to that frame.
     */
    private fun capture(widthDp: Float = screenW, heightDp: Float = screenH, density: Float = 2f): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val content = compose.activity.findViewById<android.view.View>(android.R.id.content)
        val at = IntArray(2)
        content.getLocationInWindow(at)
        val w = minOf((widthDp * density).toInt(), full.width - at[0])
        val h = minOf((heightDp * density).toInt(), full.height - at[1])
        return Bitmap.createBitmap(full, at[0], at[1], w, h)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/shelf3").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures/shelf3")
        if (shared.isDirectory) out.copyTo(File(shared, name), overwrite = true)
        println("SHELF3 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    /** Home's keys are its own: the Library's disposal of its row and Home's registration of its row never meet over one key. */
    private fun homeKey(row: GameRow) = FocusKey("capture/home/${row.gameId.value}")

    private fun tileTag(scene: Scene, row: GameRow) =
        if (scene == Scene.LIBRARY) libraryShelfTileTestTag(row) else GameShelfIds.Home.tilePrefix + homeKey(row).value

    private fun isMounted(scene: Scene, row: GameRow) =
        compose.onAllNodesWithTag(tileTag(scene, row)).fetchSemanticsNodes().isNotEmpty()

    /** A seat's tile in window pixels: the art box, the largest the seat can show (the hero is a little bigger). */
    private fun tilePx(row: GameRow, which: Scene = Scene.LIBRARY): IntArray {
        val bounds = compose.onNodeWithTag(tileTag(which, row)).fetchSemanticsNode().boundsInRoot
        return intArrayOf(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
    }

    private enum class Ink(val matches: (Int, Int, Int) -> Boolean) {
        GREEN({ r, g, b -> g > r + 60 && g > b + 50 }),
        RED({ r, g, b -> r > g + 60 && r > b + 60 }),
        BLUE({ r, g, b -> b > r + 60 && b > g + 30 }),
    }

    /** How many pixels inside [box] are [ink], and the bounding box of those (left, top, right, bottom), or null for none. */
    private fun inkIn(bitmap: Bitmap, box: IntArray, ink: Ink): Pair<Int, IntArray?> {
        var count = 0
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in box[1].coerceAtLeast(0) until box[3].coerceAtMost(bitmap.height)) {
            for (x in box[0].coerceAtLeast(0) until box[2].coerceAtMost(bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                if (ink.matches(AndroidColor.red(pixel), AndroidColor.green(pixel), AndroidColor.blue(pixel))) {
                    count++
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return count to if (count == 0) null else intArrayOf(left, top, right + 1, bottom + 1)
    }

    /** The painted extent of [ink] near [row]'s tile: the box that ink fills, the hero a little beyond the layout box included. */
    private fun artBox(bitmap: Bitmap, row: GameRow, ink: Ink): IntArray {
        val tile = tilePx(row)
        val search = intArrayOf(tile[0] - 60, tile[1] - 60, tile[2] + 60, tile[3] + 60)
        return checkNotNull(inkIn(bitmap, search, ink).second) { "no $ink art near ${tile.toList()}" }
    }

    /** Whether [row]'s tile is mounted and wholly inside the frame: a strip of eight games shows four, and the window moves with focus. */
    private fun isShown(bitmap: Bitmap, row: GameRow): Boolean {
        if (!isMounted(Scene.LIBRARY, row)) return false
        val box = tilePx(row)
        return box[0] >= 0 && box[2] <= bitmap.width && box[1] >= 0 && box[3] <= bitmap.height
    }

    private fun assertPaints(bitmap: Bitmap, row: GameRow, ink: Ink, wide: ClosedFloatingPointRange<Float>, label: String) {
        val box = tilePx(row)
        val (count, ink_) = inkIn(bitmap, box, ink)
        val area = (box[2] - box[0]) * (box[3] - box[1])
        assertTrue("$label: the seat paints its own colour ($count of $area px)", count > area / 5)
        val painted = checkNotNull(ink_)
        val ratio = (painted[2] - painted[0]).toFloat() / (painted[3] - painted[1])
        assertTrue("$label: width/height ${"%.2f".format(ratio)} should be within $wide", ratio in wide)
    }

    /**
     * A pixel of the ring's stroke. The stroke is the accent gold -- (212,180,116) down the sides, lighter along the top and dimmer
     * along the bottom -- and nothing else on the scene is that colour: the art inks are green, red and blue, and the bloom outside
     * the stroke falls to about (55,50,40).
     */
    private fun isRingStroke(pixel: Int): Boolean {
        val r = AndroidColor.red(pixel)
        val g = AndroidColor.green(pixel)
        val b = AndroidColor.blue(pixel)
        return r >= 140 && r >= g && r - b >= 45 && g - b >= 28
    }

    private fun gapToRing(bitmap: Bitmap, startX: Int, startY: Int, stepX: Int, stepY: Int): Int? {
        for (i in 0 until 16) {
            val x = startX + stepX * i
            val y = startY + stepY * i
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return null
            if (isRingStroke(bitmap.getPixel(x, y))) return i
        }
        return null
    }

    /** The ring hugs the PAINTED silhouette, read off the pixels (see the shelf's own capture test: the same law, the same tolerance). */
    private fun assertRingHugsArt(bitmap: Bitmap, art: IntArray, label: String) {
        val width = art[2] - art[0]
        val height = art[3] - art[1]
        val gaps = mutableMapOf<String, Int>()
        for (quarter in 1..3) {
            val x = art[0] + width * quarter / 4
            val y = art[1] + height * quarter / 4
            mapOf(
                "left@$quarter" to gapToRing(bitmap, art[0] - 1, y, -1, 0),
                "right@$quarter" to gapToRing(bitmap, art[2], y, 1, 0),
                "top@$quarter" to gapToRing(bitmap, x, art[1] - 1, 0, -1),
                "bottom@$quarter" to gapToRing(bitmap, x, art[3], 0, 1),
            ).forEach { (side, gap) ->
                assertTrue("$label: the ring's stroke is within 16 px of the art's $side edge", gap != null)
                gaps[side] = checkNotNull(gap)
            }
        }
        gaps.forEach { (side, gap) ->
            assertTrue("$label: the ring's inner edge hugs the art at $side (a $gap px gap, at most 3 allowed): $gaps", gap in 0..3)
        }
    }

    private fun assertRingOnSeat(row: GameRow, label: String) {
        val ring = compose.onNodeWithTag(LibraryGameShelfIds.ringTag).fetchSemanticsNode().boundsInRoot
        val seat = compose.onNodeWithTag(LibraryGameShelfIds.selectedSlotTag).fetchSemanticsNode().boundsInRoot
        val tile = compose.onNodeWithTag(libraryShelfTileTestTag(row)).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label: the stationary ring (${ring.left}) stands on the selected seat (${seat.left})",
            abs(ring.left - seat.left) < 1.5f && abs(ring.width - seat.width) < 1.5f,
        )
        assertTrue("$label: the selected tile sits inside the ring's seat", tile.left >= seat.left - 1f && tile.right <= seat.right + 1f)
    }

    /** The share of the art's top corner (the [fromX]..[toX] fraction of its width, the top [depth] of its height) that is not [ink]. */
    private fun cornerNonInk(bitmap: Bitmap, art: IntArray, ink: Ink, left: Boolean): Float {
        val w = art[2] - art[0]
        val h = art[3] - art[1]
        val x0 = if (left) art[0] + (w * 0.04f).toInt() else art[0] + (w * 0.60f).toInt()
        val x1 = if (left) art[0] + (w * 0.40f).toInt() else art[0] + (w * 0.96f).toInt()
        val y0 = art[1] + (h * 0.04f).toInt()
        val y1 = art[1] + (h * 0.30f).toInt()
        var total = 0
        var other = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                total++
                val p = bitmap.getPixel(x, y)
                if (!ink.matches(AndroidColor.red(p), AndroidColor.green(p), AndroidColor.blue(p))) other++
            }
        }
        return other.toFloat() / total
    }

    /**
     * The badge stands on the same etched dark plate as the favourite rune, so it reads on any art (orange on bright
     * green was faint): most of its box is dark, and its words are the brand gold on it. With [requireOnBrightArt] the art around it
     * must itself be bright, so the test cannot pass on a dark cover that would have shown any badge.
     */
    private fun assertBadgeIsEtched(bitmap: Bitmap, row: GameRow, words: String, label: String, requireOnBrightArt: Boolean = false) {
        val tile = tilePx(row)
        val badge = compose.onAllNodes(
            (hasText(words) or hasContentDescription(words)) and hasAnyAncestor(hasTestTag(libraryShelfTileTestTag(row))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first().boundsInRoot
        val box = intArrayOf(badge.left.toInt(), badge.top.toInt(), badge.right.toInt(), badge.bottom.toInt())
        if (requireOnBrightArt) {
            // Just outside the plate, to its left, on the art (the tile's own green).
            val outside = intArrayOf(maxOf(tile[0] + 4, box[0] - 28), box[1] + 4, maxOf(tile[0] + 6, box[0] - 8), box[3] - 4)
            val (greenCount, _) = inkIn(bitmap, outside, Ink.GREEN)
            val area = ((outside[2] - outside[0]) * (outside[3] - outside[1])).coerceAtLeast(1)
            assertTrue("$label: it stands on bright art ($greenCount of $area px beside it are the art's green)", greenCount > area / 2)
            return
        }
        var dark = 0
        var gold = 0
        var total = 0
        for (y in box[1] until box[3]) {
            for (x in box[0] until box[2]) {
                val p = bitmap.getPixel(x, y)
                val r = AndroidColor.red(p)
                val g = AndroidColor.green(p)
                val b = AndroidColor.blue(p)
                total++
                if (r + g + b < 3 * 90) dark++
                if (r > 190 && g > 150 && b < 150 && r >= g) gold++
            }
        }
        println("SHELF3 badge ${"%.2f".format(dark.toFloat() / total)} dark, $gold gold px of $total")
        assertTrue("$label: the plate is dark ($dark of $total px)", dark > total * 0.45f)
        assertTrue("$label: its words are the brand gold ($gold px)", gold >= 20)
    }

    private fun label(density: Float, fontScale: Float) = "d${"%.1f".format(density)}-f${"%.1f".format(fontScale)}"

    // ── Cases ──────────────────────────────────────────────────────────────────────────────────────

    private fun libraryAtEverySeat(density: Float, fontScale: Float) {
        val tag = label(density, fontScale)
        val rows = eightGames()
        mount(rows, 897f, 505f, density, fontScale)
        val libraryTiles = mutableMapOf<Int, IntArray>()
        val librarySparkle = mutableMapOf<Int, Int>()
        for (seat in 0..3) {
            focus(seat)
            val frame = capture(density = density)
            save(frame, "library-$tag-seat$seat.png")
            val at = "$tag seat $seat"
            assertRingOnSeat(rows[seat], at)
            if (seat == 0) assertRingHugsArt(frame, artBox(frame, rows[0], Ink.GREEN), "$at: the ring on the square tile")
            if (seat == 1) assertRingHugsArt(frame, artBox(frame, rows[1], Ink.RED), "$at: the ring on the ledge cover")
            if (isShown(frame, rows[0])) assertPaints(frame, rows[0], Ink.GREEN, 0.9f..1.1f, "$at: the square tile")
            if (isShown(frame, rows[1])) assertPaints(frame, rows[1], Ink.RED, 0.45f..0.9f, "$at: the ledge cover")
            if (isShown(frame, rows[2])) assertPaints(frame, rows[2], Ink.GREEN, 0.9f..1.1f, "$at: the plain square")
            if (isShown(frame, rows[3])) assertPaints(frame, rows[3], Ink.RED, 0.45f..0.9f, "$at: the plain cover")
            libraryTiles[seat] = tilePx(rows[seat])
            if (seat > 0) {
                val sparkle = sparklePixels(frame, LibraryGameShelfIds.ringTag)
                println("SHELF3 gleam library $tag seat $seat: $sparkle px")
                librarySparkle[seat] = sparkle
            }
            if (seat == 0) {
                // The two marks stand on the corners of the art of the games that own them, and on no other game's: the favourite rune
                // top-left and the disc badge top-right, measured against a plain game of the same shape in the same frame.
                val squareArt = artBox(frame, rows[0], Ink.GREEN)
                val plainSquareArt = artBox(frame, rows[2], Ink.GREEN)
                val coverArt = artBox(frame, rows[1], Ink.RED)
                val plainCoverArt = artBox(frame, rows[3], Ink.RED)
                val rune = cornerNonInk(frame, squareArt, Ink.GREEN, left = true)
                val runeBaseline = cornerNonInk(frame, plainSquareArt, Ink.GREEN, left = true)
                val badge = cornerNonInk(frame, squareArt, Ink.GREEN, left = false)
                val badgeBaseline = cornerNonInk(frame, plainSquareArt, Ink.GREEN, left = false)
                val coverRune = cornerNonInk(frame, coverArt, Ink.RED, left = true)
                val coverRuneBaseline = cornerNonInk(frame, plainCoverArt, Ink.RED, left = true)
                println("SHELF3 marks $tag: square rune $rune vs $runeBaseline, badge $badge vs $badgeBaseline, cover rune $coverRune vs $coverRuneBaseline")
                assertTrue("$at: the favourite rune is on the square tile's top-left ($rune vs $runeBaseline)", rune > runeBaseline + 0.03f)
                assertTrue("$at: the disc badge is on the square tile's top-right ($badge vs $badgeBaseline)", badge > badgeBaseline + 0.02f)
                assertTrue("$at: the rune rides the ledge cover's art too ($coverRune vs $coverRuneBaseline)", coverRune > coverRuneBaseline + 0.03f)
                assertTrue("$at: the plain square has no badge on it", badgeBaseline < 0.03f)
                assertBadgeIsEtched(frame, rows[0], "2 discs", "$at: the disc badge")
                assertBadgeIsEtched(frame, rows[0], "2 discs", "$at: the disc badge", requireOnBrightArt = true)
            }
        }

        // The window scrolls: the book (no art) and the article (a disc), with the continuation rune behind the left edge.
        focus(4)
        val book = capture(density = density)
        save(book, "library-$tag-book.png")
        fun books(row: GameRow) = compose.onAllNodes(
            hasTestTag(MediaArtBookTitleTag) and hasAnyAncestor(hasTestTag(libraryShelfTileTestTag(row))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("$tag: the no-art game wears the runed book", 1, books(rows[4]))
        (listOf(3, 5, 6).filter { isMounted(Scene.LIBRARY, rows[it]) }).forEach {
            assertEquals("$tag: a game with art wears no book (game $it)", 0, books(rows[it]))
        }
        focus(5)
        val disc = capture(density = density)
        save(disc, "library-$tag-article.png")
        assertPaints(disc, rows[5], Ink.BLUE, 0.85f..1.15f, "$tag: the article")
        focus(7)
        save(capture(density = density), "library-$tag-last.png")

        // Home beside it: the same games, the same window, the same focus. The Library's tiles stand where Home's do and are as large.
        authority.popScope()
        scene.value = Scene.HOME
        settle()
        authority.requestFocus(homeKey(rows[0]))
        settle()
        val homeTiles = mutableMapOf<Int, IntArray>()
        for (seat in 0..3) {
            authority.requestFocus(homeKey(rows[seat]))
            settle()
            val homeFrame = capture(density = density)
            save(homeFrame, "home-$tag-seat$seat.png")
            homeTiles[seat] = tilePx(rows[seat], Scene.HOME)
            if (seat > 0) {
                val sparkle = sparklePixels(homeFrame, GameShelfIds.Home.ringTag)
                println("SHELF3 gleam home $tag seat $seat: $sparkle px")
                // Gleam parity: the ring arms for a new selection on both doors, and at the same phase both show the sparkle.
                assertTrue("$tag seat $seat: Home's ring is at the sparkle ($sparkle px)", sparkle >= 6)
                assertTrue(
                    "$tag seat $seat: the Library's ring gleams as Home's does (Library ${librarySparkle[seat]} px, Home $sparkle px)",
                    (librarySparkle[seat] ?: 0) >= 6,
                )
            }
        }
        for (seat in 0..3) {
            val l = libraryTiles.getValue(seat)
            val h = homeTiles.getValue(seat)
            // x and size, not y: Home stands on its dock and the Library on the controls lane.
            assertTrue(
                "$tag seat $seat: the Library's tile (${l.toList()}) stands where Home's does (${h.toList()}) to within 2 px",
                abs(l[0] - h[0]) <= 2 && abs(l[2] - h[2]) <= 2,
            )
        }
        for (seat in 0..3) {
            val l = libraryTiles.getValue(seat)
            val h = homeTiles.getValue(seat)
            assertTrue(
                "$tag seat $seat: the Library's tile is Home's size: ${l[2] - l[0]} px vs ${h[2] - h[0]} px",
                abs((l[2] - l[0]) - (h[2] - h[0])) <= 2 && abs((l[3] - l[1]) - (h[3] - h[1])) <= 2,
            )
        }
    }

    @Test
    fun `the library shelf and Home's at every seat, at font scale 1_0`() = libraryAtEverySeat(density = 2f, fontScale = 1f)

    @Test
    fun `the library shelf and Home's at every seat, at font scale 1_3`() = libraryAtEverySeat(density = 2f, fontScale = 1.3f)

    private fun assertTitleStaysOnOneLine(title: String, label: String) {
        val node = compose.onNode(
            hasContentDescription(title) or hasText(title) and hasAnyAncestor(hasTestTag(HomeShelfTitleTestTag)),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        val read = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("$label: the title exposes its layout", read != null && read(results))
        assertEquals("$label: a long title stays on one line (a second line would be a break inside a word)", 1, results.first().lineCount)
        val shown = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        if (shown != title) {
            assertTrue("$label: a shortened title ends in an ellipsis: <$shown>", shown.endsWith("…"))
            val head = shown.removeSuffix("…")
            assertTrue("$label: <$head> is the start of the title", title.startsWith(head))
            assertTrue("$label: the title is cut between two words, not inside one: <$shown>", title[head.length].isWhitespace())
        }
    }

    private val longGameTitle = "Supercalifragilisticexpialidocious Adventures of the Unbelievably Long Titled Hedgehog Collector's Edition Remastered"

    /** One door's title block for a very long name: one line, cut between words, whole inside the screen. */
    private fun assertTitleBlockFits(door: String, widthDp: Float) {
        assertTitleStaysOnOneLine(longGameTitle, door)
        val rail = compose.onNodeWithTag(HomeShelfTitleTestTag).fetchSemanticsNode().boundsInRoot
        val density = 2f
        assertTrue("$door: the title block (${rail.left}..${rail.right}) is inside the screen (${widthDp * density})", rail.left >= 0f && rail.right <= widthDp * density + 1f)
    }

    private fun longTitle(name: String, widthDp: Float, heightDp: Float, density: Float, fontScale: Float) {
        val rows = eightGames(firstTitle = longGameTitle)
        mount(rows, widthDp, heightDp, density, fontScale)
        focus(0)
        save(capture(widthDp, heightDp, density), "library-title-$name.png")
        assertTitleBlockFits("$name library", widthDp)
        // The title block starts where the header above it starts (the gamelist Column's padding), not at Home's gutter.
        val rail = compose.onNodeWithTag(HomeShelfTitleTestTag).fetchSemanticsNode().boundsInRoot
        // The header's name is whole where it fits and shortened at a word on the narrow screens ("Game…"), so find it by its first word.
        val header = compose.onAllNodes(hasText("Game", substring = true) or hasContentDescription("Game Boy Advance"), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        assertTrue("$name: the title rail (${rail.left}) shares the header's left edge (${header.left})", abs(rail.left - header.left) <= 1.5f)
        // The same name on Home's own shelf: the same one line, cut at a word.
        authority.popScope()
        scene.value = Scene.HOME
        settle()
        authority.requestFocus(homeKey(rows[0]))
        settle()
        save(capture(widthDp, heightDp, density), "home-title-$name.png")
        assertTitleBlockFits("$name home", widthDp)
    }

    @Test
    fun `a long title keeps to its rail and to the header's left edge at font scale 1_0`() =
        longTitle(label(2f, 1f), 897f, 505f, 2f, 1f)

    @Test
    fun `a long title keeps to its rail and to the header's left edge at font scale 1_3`() =
        longTitle(label(2f, 1.3f), 897f, 505f, 2f, 1.3f)

    @Test
    fun `a long title on the RP5 keeps to one line on both doors`() {
        longTitle("rp5-700x350-f1.3", 700f, 350f, 2f, 1.3f)
    }

    @Test
    fun `a long title on the compact square keeps to one line on both doors`() {
        longTitle("compact-565x541-f1.3", 564.7f, 540.7f, 2f, 1.3f)
    }

    private fun smaller(name: String, widthDp: Float, heightDp: Float, fontScale: Float) {
        val rows = eightGames()
        mount(rows, widthDp, heightDp, 2f, fontScale)
        focus(1)
        val frame = capture(widthDp, heightDp)
        save(frame, "library-$name.png")
        // Whatever the room, the strip stays inside the screen: no tile is cut by the frame's bottom edge.
        val tile = tilePx(rows[1])
        assertTrue("$name: the selected tile (${tile.toList()}) is inside the frame (${frame.width} x ${frame.height})", tile[3] <= frame.height && tile[2] <= frame.width)
        assertRingOnSeat(rows[1], name)
        val libraryHero = (tile[3] - tile[1]) / 2f
        // The title block's own geometry, for the stacked status chip on the compact square: a chip shorter than its own text is a clip.
        fun block(label: String) {
            val rail = compose.onNodeWithTag(HomeShelfTitleTestTag).fetchSemanticsNode().boundsInRoot
            val chip = compose.onNodeWithTag(HomeShelfStatusTestTag).fetchSemanticsNode().boundsInRoot
            val chipText = compose.onAllNodes(hasText("Ready") or hasContentDescription("Ready"), useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInRoot }
            println("SHELF3 titleblock $name $label: band $rail, chip $chip, chip text $chipText")
            // The stacked chip (compact) is whole: inside the title band, and its words inside the chip, never a clipped half.
            assertTrue("$name $label: the chip ($chip) is inside the title block ($rail)", chip.top >= rail.top - 1f && chip.bottom <= rail.bottom + 1f)
            chipText.forEach { text ->
                assertTrue("$name $label: the chip's words ($text) are inside the chip ($chip)", text.top >= chip.top - 1f && text.bottom <= chip.bottom + 1f && text.right <= chip.right + 1f)
            }
        }
        block("library")
        // The same screen, Home's own shelf, for the eye and for the numbers: how much art the Library gives up to its header and lane.
        authority.popScope()
        scene.value = Scene.HOME
        settle()
        authority.requestFocus(homeKey(rows[1]))
        settle()
        save(capture(widthDp, heightDp), "home-$name.png")
        val homeHero = (tilePx(rows[1], Scene.HOME).let { it[3] - it[1] }) / 2f
        block("home")
        println("SHELF3 hero $name: library ${"%.1f".format(libraryHero)} dp tall, home ${"%.1f".format(homeHero)} dp tall")
    }

    @Test
    fun `the library shelf on the RP5's 700 x 350`() = smaller("rp5-700x350", 700f, 350f, 1f)

    @Test
    fun `the library shelf on the compact square`() = smaller("compact-565x541", 564.7f, 540.7f, 1f)

    @Test
    fun `the library shelf on the RP5's 700 x 350 at font scale 1_3`() = smaller("rp5-700x350-f1.3", 700f, 350f, 1.3f)

    @Test
    fun `the library shelf on the compact square at font scale 1_3`() = smaller("compact-565x541-f1.3", 564.7f, 540.7f, 1.3f)
}
