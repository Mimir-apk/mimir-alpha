package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MediaArtBookTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirArtAvailability
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
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
 * The shared game shelf and Home's title rail, at the Odin's and the Thor's 897 x 505 dp, at font scale 1.0 and 1.3 (density 2).
 *
 * Four seats wear the four faces a shelf can be handed: a SQUARE tile (green), a LEDGE_COVER box (red), a PHYSICAL article, the
 * disc (blue), and NONE. The real [GameShelf] and [HomeShelfTitle] stand on the house background, hosted the way a second caller
 * (the Library) will host them: the caller pushes a scope of its own, registers the keys and hands the shelf the selected one. The
 * scene is not [HomeShelfScreen] because that wraps its band in `MimirPageHandoff`, whose recorded layers cannot be drawn into the
 * software bitmap this repo rasterises with. Each row carries its face explicitly, so the shelf is read through `face` alone and
 * never through which field a row happened to set. For every seat the ring is moved onto it and the frame is written to
 * `build/test-captures/shelf1/` (and `build/shelf1/`). Beside the PNGs the cases assert what the pixels say: each seat paints its own colour
 * in its own proportion, no-art paints the ledge painter's fallback book as Home's tile always has, the stationary ring sits on the selected seat and its stroke hugs the painted art of a square tile and of a ledge box
 * (read off the pixels, to within a few device pixels), and a long title stays on one
 * line (a break inside a word would make it two). The PNGs are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameShelfCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val delivered = Collections.synchronizedList(mutableListOf<String>())
    private val tempFiles = mutableListOf<File>()
    private val widthDp = 897f
    private val heightDp = 505f

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
        val file = File.createTempFile("shelf1-art", ".png")
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

    private fun row(index: Int, title: String, face: ShelfFace) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("game-$index")),
        rank = index * 1_024L,
        addedAtMillis = index.toLong(),
        title = title,
        supportingLabel = "gba",
        face = face,
    )

    private fun fourFaces(firstTitle: String = "Square Tile") = listOf(
        row(0, firstTitle, ShelfFace(squareUri, ShelfFaceShape.SQUARE)),
        row(1, "Ledge Cover", ShelfFace(coverUri, ShelfFaceShape.LEDGE_COVER)),
        row(2, "Game Article", ShelfFace(discUri, ShelfFaceShape.PHYSICAL)),
        row(3, "No Art At All", ShelfFace.None),
    )

    // ── Harness ────────────────────────────────────────────────────────────────────────────────────

    private val shelfScope = FocusScope("capture/game-shelf")
    private val windowMemory = GameShelfWindowMemory()

    private fun mount(rows: List<HomeShelfRow>, density: Float, fontScale: Float) {
        val items = rows.map { row ->
            GameShelfItem.Game(
                focusKey = row.focusKey,
                title = row.title,
                face = row.face,
                platformIdValue = row.supportingLabel.orEmpty(),
            )
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        ShelfScene(items)
                    }
                }
            }
        }
        settle()
        authority.requestFocus(rows[0].focusKey)
        awaitArt(rows)
    }

    /** What a hosting screen does: push its scope, register its keys, and hand the shelf the selected one. */
    @Composable
    private fun ShelfScene(items: List<GameShelfItem>) {
        DisposableEffect(authority) {
            authority.pushScope(shelfScope)
            onDispose { authority.popScope() }
        }
        RegisterVirtualFocusRow(authority = authority, scope = shelfScope, keys = items.map { it.focusKey })
        val focusState by authority.state.collectAsState()
        val selectedKey = if (focusState.scope == shelfScope) focusState.focused else authority.rememberedFocusIn(shelfScope)
        val geometry = remember { solveHomeShelfGeometry(widthDp, heightDp) }
        MimirShelfBackground(modifier = Modifier.fillMaxSize(), showAtmosphericClouds = false) {
            HomeShelfTitle(
                title = items.firstOrNull { it.focusKey == selectedKey }?.title,
                geometry = geometry,
                eyebrow = "Editor's Choice",
                statusChip = GameStatusChip("Ready", true),
            )
            val state = rememberGameShelfState(
                items = items,
                selectedKey = selectedKey,
                geometry = geometry,
                windowMemory = windowMemory,
                windowMemoryKey = "capture",
                surfaceEpoch = 0,
                reducedMotion = false,
            )
            GameShelf(
                scope = shelfScope,
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

    private fun awaitArt(rows: List<HomeShelfRow>) {
        val wanted = rows.mapNotNull { it.face.uri }.toSet()
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

    private fun focus(rows: List<HomeShelfRow>, index: Int) {
        authority.requestFocus(rows[index].focusKey)
        settle()
    }

    private fun capture(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/shelf1").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures/shelf1")
        if (shared.isDirectory) out.copyTo(File(shared, name), overwrite = true)
        println("SHELF1 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    /** A seat's tile in window pixels: the art box, the largest the seat can show (the hero is a little bigger). */
    private fun tilePx(row: HomeShelfRow): IntArray {
        val bounds = compose.onNodeWithTag(homeShelfTileTestTag(row)).fetchSemanticsNode().boundsInWindow
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

    private fun assertPaints(bitmap: Bitmap, row: HomeShelfRow, ink: Ink, wide: ClosedFloatingPointRange<Float>, label: String) {
        val box = tilePx(row)
        val (count, ink_) = inkIn(bitmap, box, ink)
        val area = (box[2] - box[0]) * (box[3] - box[1])
        assertTrue("$label: the seat paints its own colour ($count of $area px)", count > area / 5)
        val painted = checkNotNull(ink_)
        val ratio = (painted[2] - painted[0]).toFloat() / (painted[3] - painted[1])
        assertTrue("$label: width/height ${"%.2f".format(ratio)} should be within $wide", ratio in wide)
    }

    /**
     * A face with no art paints none of the other faces' ink, and it paints the ledge painter's no-art fallback, the runed book
     * with the game's title: that is what Home's tile drew for a game with no art before the shelf was shared, and the shelf draws
     * the same (the 09-17 shelf face law: a shelf game with no cover shows the book).
     */
    private fun assertPaintsNoArtInkAndTheFallbackBook(bitmap: Bitmap, rows: List<HomeShelfRow>, label: String) {
        val box = tilePx(rows[3])
        val area = (box[2] - box[0]) * (box[3] - box[1])
        Ink.values().forEach { ink ->
            val (count, _) = inkIn(bitmap, box, ink)
            assertTrue("$label: a no-art seat paints no $ink ($count of $area px)", count < area / 100)
        }
        fun books(row: HomeShelfRow) = compose.onAllNodes(
            hasTestTag(MediaArtBookTitleTag) and hasAnyAncestor(hasTestTag(homeShelfTileTestTag(row))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("$label: the no-art seat wears the fallback book", 1, books(rows[3]))
        (0..2).forEach { assertEquals("$label: a seat with art wears no book (seat $it)", 0, books(rows[it])) }
    }

    private fun assertRingOnSeat(row: HomeShelfRow, label: String) {
        val ring = compose.onNodeWithTag(HomeShelfStationaryRingTestTag).fetchSemanticsNode().boundsInWindow
        val seat = compose.onNodeWithTag(HomeShelfSelectedSlotTestTag).fetchSemanticsNode().boundsInWindow
        val tile = compose.onNodeWithTag(homeShelfTileTestTag(row)).fetchSemanticsNode().boundsInWindow
        assertTrue(
            "$label: the stationary ring (${ring.left}) stands on the selected seat (${seat.left})",
            abs(ring.left - seat.left) < 1.5f && abs(ring.width - seat.width) < 1.5f,
        )
        assertTrue("$label: the selected tile sits inside the ring's seat", tile.left >= seat.left - 1f && tile.right <= seat.right + 1f)
    }

    /**
     * A pixel of the ring's stroke. The stroke is the accent gold -- (212,180,116) down the sides, lighter along the top and dimmer
     * along the bottom -- and nothing else on the scene is that colour: the art inks are green, red and blue, the shelfmark text is
     * far darker, and the bloom outside the stroke falls to about (55,50,40).
     */
    private fun isRingStroke(pixel: Int): Boolean {
        val r = AndroidColor.red(pixel)
        val g = AndroidColor.green(pixel)
        val b = AndroidColor.blue(pixel)
        return r >= 140 && r >= g && r - b >= 45 && g - b >= 28
    }

    /** Pixels between the art and the ring's stroke, walking outward from the art's edge pixel; null when no stroke is within 16 px. */
    private fun gapToRing(bitmap: Bitmap, startX: Int, startY: Int, stepX: Int, stepY: Int): Int? {
        for (i in 0 until 16) {
            val x = startX + stepX * i
            val y = startY + stepY * i
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return null
            if (isRingStroke(bitmap.getPixel(x, y))) return i
        }
        return null
    }

    /**
     * The ring hugs the PAINTED silhouette, read off the pixels and not off the layout boxes (which agree by construction). The art's
     * extent is where its own ink starts and stops; the ring's inner edge is where the gold stroke starts, walked outward from there
     * at a quarter, a half and three quarters along each side. What lies between is the keyline and the stroke's soft inner edge,
     * about one device pixel on every side in the PNGs; the ring a couple of px wide of the art (or inside it) is a ring shaped for
     * some other face, and fails.
     */
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
        fun side(name: String) = (1..3).map { gaps.getValue("$name@$it") }
        assertTrue(
            "$label: opposite sides agree within 2 px, so the ring is centred on the art: $gaps",
            abs(side("left").average() - side("right").average()) <= 2.0 && abs(side("top").average() - side("bottom").average()) <= 2.0,
        )
    }

    /** The painted extent of [ink] near [row]'s tile: the box that ink fills, the hero a little beyond the layout box included. */
    private fun artBox(bitmap: Bitmap, row: HomeShelfRow, ink: Ink): IntArray {
        val tile = tilePx(row)
        val search = intArrayOf(tile[0] - 60, tile[1] - 60, tile[2] + 60, tile[3] + 60)
        return checkNotNull(inkIn(bitmap, search, ink).second) { "no $ink art near ${tile.toList()}" }
    }

    private fun captureAllSeats(density: Float, fontScale: Float) {
        val label = "d${"%.1f".format(density)}-f${"%.1f".format(fontScale)}"
        val rows = fourFaces()
        mount(rows, density, fontScale)
        for (seat in 0..3) {
            focus(rows, seat)
            val frame = capture()
            save(frame, "shelf-$label-seat$seat.png")
            val at = "$label seat $seat"
            assertRingOnSeat(rows[seat], at)
            // The ring's stroke against the painted art, for a square tile and a ledge box (the ring is on the selected seat only).
            if (seat == 0) assertRingHugsArt(frame, artBox(frame, rows[0], Ink.GREEN), "$at: the ring on the square tile")
            if (seat == 1) assertRingHugsArt(frame, artBox(frame, rows[1], Ink.RED), "$at: the ring on the ledge cover")
            assertPaints(frame, rows[0], Ink.GREEN, 0.9f..1.1f, "$at: the square tile")
            assertPaints(frame, rows[1], Ink.RED, 0.45f..0.9f, "$at: the ledge cover")
            assertPaints(frame, rows[2], Ink.BLUE, 0.85f..1.15f, "$at: the article")
            assertPaintsNoArtInkAndTheFallbackBook(frame, rows, "$at: the no-art seat")
        }
    }

    @Test
    fun `the four faces and the ring at every seat, at font scale 1_0`() = captureAllSeats(density = 2f, fontScale = 1f)

    @Test
    fun `the four faces and the ring at every seat, at font scale 1_3`() = captureAllSeats(density = 2f, fontScale = 1.3f)

    private fun assertTitleStaysOnOneLine(title: String, label: String) {
        // A title wider than its rail is shortened to whole words and an ellipsis; the node keeps the full title as its spoken name.
        val node = compose.onNode(
            hasContentDescription(title) or hasText(title) and hasAnyAncestor(hasTestTag(HomeShelfTitleTestTag)),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        val read = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("$label: the title exposes its layout", read != null && read(results))
        assertEquals("$label: a long title stays on one line (a second line would be a break inside a word)", 1, results.first().lineCount)
        val shown = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertWholeWordsOnly(title, shown, label)
    }

    /** [shown] is [title], or whole words of it closed by an ellipsis: never a cut inside a word. */
    private fun assertWholeWordsOnly(title: String, shown: String, label: String) {
        if (shown == title) return
        assertTrue("$label: a shortened title ends in an ellipsis: <$shown>", shown.endsWith("…"))
        val head = shown.removeSuffix("…")
        assertTrue("$label: <$head> is the start of the title", title.startsWith(head))
        assertTrue("$label: the title is cut between two words, not inside one: <$shown>", title[head.length].isWhitespace())
    }

    private fun longTitle(density: Float, fontScale: Float) {
        val label = "d${"%.1f".format(density)}-f${"%.1f".format(fontScale)}"
        val title = "Supercalifragilisticexpialidocious Adventures of the Unbelievably Long Titled Hedgehog Collector's Edition Remastered"
        val rows = fourFaces(firstTitle = title)
        mount(rows, density, fontScale)
        focus(rows, 0)
        save(capture(), "title-$label.png")
        assertTitleStaysOnOneLine(title, label)
    }

    @Test
    fun `a long title keeps to its rail at font scale 1_0`() = longTitle(density = 2f, fontScale = 1f)

    @Test
    fun `a long title keeps to its rail at font scale 1_3`() = longTitle(density = 2f, fontScale = 1.3f)
}
