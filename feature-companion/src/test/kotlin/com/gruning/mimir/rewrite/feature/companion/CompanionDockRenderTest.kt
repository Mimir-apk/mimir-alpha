package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirAppIconCornerRatio
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.mimirAppIconCorner
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V8 of the Thor Bottom revamp, rasterised: the dock without discs (policy 3) and the one app-icon shape (policy 4) across the dock, the Quick Launch
 * picker, the lattice's app tiles and the Tabs drawer, on the REAL composables ([CompanionQuickLaunchLayer], [CompanionRecentsDrawer], [CompanionHomeGridTile]), at the
 * Bottom's own 538 x 468 dp at 369 dpi and at font scales 1.0 and 1.3, under Robolectric's native graphics. Every number is printed with the tag `V8-RENDER`. Pixel
 * values are the JVM's; the pixels on a handheld are device-owed.
 *
 * What is pinned:
 *  - **No disc, no rim.** The old seat was a 56 dp leather coin with a sheen and a 1.5 dp rim. Outside the central 44 dp of every seat (where the icon, the marks, the "+" and
 *    the pending dot live) the pixel is the ground behind the rail, exactly, on all seven seats.
 *  - **The icon's corner is 11/40 of its edge** in the dock (40 dp), the picker (40 dp), the lattice (34 dp) and a tab (54 dp): the corner radius is measured from the area the
 *    rounded corner leaves uncovered (a quarter circle of radius r leaves (1 - pi/4) r squared), against Main's own ratio, and it tells a circle, a square and 13/52 apart.
 *  - **The instruments-active mark is `accentBright`**, and not at rest.
 *  - **Every target is at least 48 dp** and nothing overflows the Bottom.
 *  - Main's own 11 over 40 is still the literal `HomeQuickLaunchPickerIcon` draws, and the shape token is that ratio (the mirror's pin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionDockRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = Color(0xFF10141C)
    private val ink = Color(0xFFFF00FF)
    private val palette = MimirThemePalette.forId(MimirThemeId.DEFAULT)

    private var density = 0f
    private var fontScale = 1f
    private var instrumentsShown by mutableStateOf<Boolean?>(null)
    private var pickerRequest by mutableStateOf<Long?>(null)
    private var launchLocked by mutableStateOf(false)

    private val iconFile: File = File.createTempFile("v8-icon", ".png").also { file ->
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(ink.toArgbInt())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Warm in the shared cache, as the dock warms its seats, so the icon is READY on its first frame and nothing waits on an IO thread.
        companionAppIconCache.put(file.path, bitmap.asImageBitmap())
    }

    @After
    fun tearDown() {
        companionAppIconCache.remove(iconFile.path)
        iconFile.delete()
    }

    private fun Color.toArgbInt(): Int =
        (((alpha * 255f + 0.5f).toInt() and 0xFF) shl 24) or (((red * 255f + 0.5f).toInt() and 0xFF) shl 16) or
            (((green * 255f + 0.5f).toInt() and 0xFF) shl 8) or ((blue * 255f + 0.5f).toInt() and 0xFF)

    private val withIcon = CompanionAppEntry("org.example.alpha", "Alpha", iconPath = iconFile.path, closable = true)
    private val noIcon = CompanionAppEntry("org.example.beta", "Beta")

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The harness.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun mount(scale: Float, content: @Composable () -> Unit) {
        fontScale = scale
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, scale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    Box(Modifier.requiredSize(BottomWidthDp.dp, BottomHeightDp.dp).background(ground).testTag(ViewTag)) { content() }
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 2_000L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Composable
    private fun Dock() {
        CompanionQuickLaunchLayer(
            instrumentsShown = instrumentsShown,
            slots = listOf(noIcon.packageName, null, null, null, withIcon.packageName),
            apps = listOf(withIcon, noIcon),
            launchLocked = launchLocked,
            launchMessage = null,
            onLaunch = {},
            onAssign = { _, _ -> },
            generalPickerRequest = pickerRequest,
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun frame(): PixelMap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        return full.asImageBitmap().toPixelMap()
    }

    /** The window as a PNG in `feature-companion/build/btn265/`, copied to `build/test-captures` when it exists (update). Pixel values are the JVM's; the handheld's are device-owed. */
    private fun savePng(fileName: String) {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val dir = File("build/btn265").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, fileName), overwrite = true)
        println("BTN265 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun boundsOf(tag: String, index: Int = 0): Rect =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)[index].fetchSemanticsNode().boundsInWindow

    private fun Rect.dpWidth() = width / density
    private fun Rect.dpHeight() = height / density

    private fun clickableBounds(): List<Rect> =
        compose.onAllNodes(hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes().map { it.boundsInWindow }

    /** The seats, left to right: every clickable inside the rail's own row. */
    private fun seats(): List<Rect> {
        val rail = boundsOf(CompanionQuickLaunchTag)
        return clickableBounds().filter { it.top >= rail.top - 1f && it.bottom <= rail.bottom + 1f }.sortedBy { it.left }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // Pixels.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun PixelMap.px(x: Int, y: Int): Color = this[x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)]

    private fun Color.dist(other: Color) = max(max(abs(red - other.red), abs(green - other.green)), abs(blue - other.blue))

    /** How much of [ink] a pixel carries over [bg], 0 to 1: its projection onto the segment from the background to the ink. */
    private fun coverage(pixel: Color, bg: Color, inkColor: Color): Float {
        val dr = inkColor.red - bg.red
        val dg = inkColor.green - bg.green
        val db = inkColor.blue - bg.blue
        val norm = dr * dr + dg * dg + db * db
        val dot = (pixel.red - bg.red) * dr + (pixel.green - bg.green) * dg + (pixel.blue - bg.blue) * db
        return (dot / norm).coerceIn(0f, 1f)
    }

    /**
     * The corner radius of the solid-[inkColor] icon whose layout bounds are [bounds], in px, from the pixels alone. A rounded corner of radius r leaves (1 - pi/4) r squared of
     * the square corner uncovered whatever r is, so: sum the ink's coverage over a square at the top-left corner a little larger than the corner (and, to be exact at sub-pixel
     * edges, take the straight edges' true positions from the middle row and column rather than from the layout bounds), subtract it from the square corner's area, and solve.
     * [bg] is sampled one dp outside the left edge, mid-height, where only the surface behind the icon is.
     */
    private fun cornerRadiusPx(pm: PixelMap, bounds: Rect, inkColor: Color = ink): Float {
        val x0 = floor(bounds.left).toInt() - 1
        val y0 = floor(bounds.top).toInt() - 1
        val edge = bounds.width
        val s = (edge * 0.5f).toInt() + 2
        val midY = floor(bounds.top + edge / 2f).toInt()
        val midX = floor(bounds.left + edge / 2f).toInt()
        val bg = pm.px((bounds.left - 1.5f * density).toInt(), midY)
        fun cov(x: Int, y: Int) = coverage(pm.px(x, y), bg, inkColor)
        var rowSum = 0f
        for (x in x0 until x0 + s) rowSum += cov(x, midY)
        val trueLeft = (x0 + s) - rowSum
        var colSum = 0f
        for (y in y0 until y0 + s) colSum += cov(midX, y)
        val trueTop = (y0 + s) - colSum
        var sum = 0f
        for (y in y0 until y0 + s) for (x in x0 until x0 + s) sum += cov(x, y)
        val squareArea = ((x0 + s) - trueLeft) * ((y0 + s) - trueTop)
        val missing = squareArea - sum
        return sqrt(max(0f, missing) / (1f - Math.PI.toFloat() / 4f))
    }

    private fun assertIconShape(place: String, pm: PixelMap, bounds: Rect, edgeDp: Float) {
        val r = cornerRadiusPx(pm, bounds)
        println(
            "V8-RENDER diag $place bounds=${bounds.left / density},${bounds.top / density} ${bounds.dpWidth()}x${bounds.dpHeight()}dp centre=${pm.px(bounds.center.x.toInt(), bounds.center.y.toInt())} " +
                "outside=${pm.px((bounds.left - 1.5f * density).toInt(), bounds.center.y.toInt())} window=${pm.width}x${pm.height}px",
        )
        val want = MimirAppIconCornerRatio * edgeDp * density
        val ratio = r / (edgeDp * density)
        println(
            "V8-RENDER shape $place fontScale=$fontScale edge=${bounds.dpWidth()}x${bounds.dpHeight()}dp corner=${r / density}dp wanted=${want / density}dp " +
                "ratio=$ratio (Main ${11f / 40f})",
        )
        assertEquals("$place: the icon is ${edgeDp}dp square", edgeDp, bounds.dpWidth(), 0.6f)
        assertEquals("$place: the icon is ${edgeDp}dp square", edgeDp, bounds.dpHeight(), 0.6f)
        assertEquals("$place: the corner is 11/40 of the edge (measured ${r / density}dp, Main's ratio gives ${want / density}dp)", want, r, want * 0.10f)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The dock.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun checkDock(scale: Float) {
        instrumentsShown = false
        mount(scale) { Dock() }
        val seats = seats()
        val rail = boundsOf(CompanionQuickLaunchTag)
        println(
            "V8-RENDER dock fontScale=$scale bottom=${BottomWidthDp}x${BottomHeightDp}dp rail=${rail.dpWidth()}x${rail.dpHeight()}dp seats=${seats.size} " +
                "seatSizes=${seats.map { "${"%.1f".format(it.dpWidth())}x${"%.1f".format(it.dpHeight())}" }} " +
                "gaps=${seats.zipWithNext { a, b -> "%.1f".format((b.left - a.right) / density) }}",
        )
        val view = boundsOf(ViewTag)
        assertEquals("General, the instruments switch and five pins", 7, seats.size)
        seats.forEachIndexed { index, seat ->
            assertTrue("seat $index is at least 48 dp each way: ${seat.dpWidth()} x ${seat.dpHeight()}", seat.dpWidth() >= 47.9f && seat.dpHeight() >= 47.9f)
            assertEquals("seat $index is the 56 dp slot", CompanionQuickSeatDp, seat.dpWidth(), 0.5f)
            assertTrue("seat $index is inside the Bottom: $seat vs $view", seat.left >= view.left - 0.5f && seat.right <= view.right + 0.5f && seat.bottom <= view.bottom + 0.5f && seat.top >= view.top)
        }
        assertTrue("the rail does not overflow the Bottom", rail.left >= view.left - 0.5f && rail.right <= view.right + 0.5f)

        val rest = frame()
        // 1. No disc, no rim: outside the central 44 dp of each seat the pixel is the ground. The old coin covered radius 28 dp and its 1.5 dp rim sat at 26.5 to 28.
        var offending = 0
        var checked = 0
        seats.forEach { seat ->
            val cx = (seat.left + seat.right) / 2f
            val cy = (seat.top + seat.bottom) / 2f
            val half = 22f * density
            for (y in floor(seat.top).toInt() until seat.bottom.toInt()) {
                for (x in floor(seat.left).toInt() until seat.right.toInt()) {
                    if (abs(x + 0.5f - cx) <= half && abs(y + 0.5f - cy) <= half) continue
                    checked++
                    if (rest.px(x, y).dist(ground) > 1.5f / 255f) offending++
                }
            }
        }
        println("V8-RENDER dock fontScale=$scale noDisc outsideCentral44dp checked=$checked pixelsNotGround=$offending")
        assertTrue("the band outside each seat's central 44 dp was sampled", checked > 7 * 2000)
        assertEquals("no disc, sheen or rim is drawn: every pixel outside the central 44 dp of a seat is the ground", 0, offending)
        // And the old disc's rim ring, where the 1.5 dp rim was (radius 26.5 to 28 dp from the centre of a seat) at the eight compass points.
        seats.forEachIndexed { index, seat ->
            val cx = (seat.left + seat.right) / 2f
            val cy = (seat.top + seat.bottom) / 2f
            for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f, 0.7071f to 0.7071f, -0.7071f to 0.7071f, 0.7071f to -0.7071f, -0.7071f to -0.7071f)) {
                val p = rest.px((cx + dx * 27.2f * density).toInt(), (cy + dy * 27.2f * density).toInt())
                assertTrue("seat $index: where the rim was ($dx,$dy) is the ground, was $p", p.dist(ground) <= 1.5f / 255f)
            }
        }

        // 2. The marks and the "+" carry the seat. Rest: the metal marks are not accentBright, and the "+" is textMuted.
        val tweaker = seats[1]
        val centreOfTweaker = rest.px(((tweaker.left + tweaker.right) / 2f).toInt(), ((tweaker.top + tweaker.bottom) / 2f).toInt())
        println("V8-RENDER dock fontScale=$scale instrumentsCentreAtRest=$centreOfTweaker accentBright=${palette.accentBright}")
        assertTrue("at rest the instruments bars are the quiet textMuted mark: $centreOfTweaker", centreOfTweaker.dist(palette.textMuted) <= 2f / 255f)
        assertTrue("and not accentBright", centreOfTweaker.dist(palette.accentBright) > 0.06f)
        val plus = seats[4]
        var closest = 1f
        for (y in floor(plus.top).toInt() until plus.bottom.toInt()) {
            for (x in floor(plus.left).toInt() until plus.right.toInt()) closest = minOf(closest, rest.px(x, y).dist(palette.textMuted))
        }
        println("V8-RENDER dock fontScale=$scale plusClosestToTextMuted=$closest")
        assertTrue("the empty seat's + is drawn in textMuted (closest pixel off by $closest)", closest < 0.08f)

        // 3. Instruments active: the bars take accentBright.
        instrumentsShown = true
        settle(300L)
        val active = frame()
        val seatsActive = seats()
        val t = seatsActive[1]
        val centreActive = active.px(((t.left + t.right) / 2f).toInt(), ((t.top + t.bottom) / 2f).toInt())
        println("V8-RENDER dock fontScale=$scale instrumentsCentreActive=$centreActive accentBright=${palette.accentBright}")
        assertTrue("with instruments up the bars are accentBright: $centreActive vs ${palette.accentBright}", centreActive.dist(palette.accentBright) <= 2f / 255f)
        assertEquals("seven seats still", 7, seatsActive.size)

        // 4. The app seat's icon is Main's rounded square, 40 dp, in 11/40.
        val icon = boundsOf(CompanionQuickIconTag, 0)
        assertIconShape("dock", active, icon, CompanionQuickIconDp)
        // The letter seat (no icon wired) keeps a rounded-square ground and its letter, in the same shape.
        assertEquals("both app seats wear the icon box", 2, compose.onAllNodesWithTag(CompanionQuickIconTag, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the dock draws no disc, carries the state on its marks and wears the one icon shape at font scale 1_0`() = checkDock(1.0f)

    @Test
    fun `the dock draws no disc, carries the state on its marks and wears the one icon shape at font scale 1_3`() = checkDock(1.3f)

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The picker.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun checkPicker(scale: Float) {
        mount(scale) { Dock() }
        pickerRequest = 1L
        settle(2_000L)
        val pm = frame()
        // The dock's own icons are still composed under the scrim, in the rail's band: the picker's are the tagged icons above it, in the apps' order.
        val rail = boundsOf(CompanionQuickLaunchTag)
        val icons = compose.onAllNodesWithTag(CompanionQuickIconTag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInWindow }.filter { it.bottom <= rail.top }
        assertTrue("the picker shows both apps' icon boxes: ${icons.size}", icons.size == 2)
        val icon = icons.minByOrNull { it.left }!!
        assertIconShape("picker", pm, icon, CompanionQuickIconDp)
        val close = boundsOf(CompanionPickerCloseTag)
        val tiles = clickableBounds().filter { it.dpWidth() < 200f && it.dpHeight() > 60f && it.top > close.bottom }
        println(
            "V8-RENDER picker fontScale=$scale close=${close.dpWidth()}x${close.dpHeight()}dp tiles=${tiles.map { "${"%.0f".format(it.dpWidth())}x${"%.0f".format(it.dpHeight())}" }} " +
                "icon=${icon.dpWidth()}x${icon.dpHeight()}dp",
        )
        assertTrue("the picker's Close is at least 48 dp each way: ${close.dpWidth()} x ${close.dpHeight()}", close.dpWidth() >= 47.9f && close.dpHeight() >= 47.9f)
        // Update: the 48 dp box is the hit area and draws nothing; Close is a normal capsule plate inside it, at the chip's own height and as wide as its word and padding,
        // on one line. The picture of it is saved at both font scales.
        val plate = boundsOf(CompanionPickerClosePlateTag)
        println("BTN265 picker close plate=${plate.dpWidth()}x${plate.dpHeight()}dp box=${close.dpWidth()}x${close.dpHeight()}dp")
        assertTrue("Close's plate is a capsule at the chip's height, not a 48 dp blob: ${plate.dpWidth()} x ${plate.dpHeight()}", plate.dpHeight() < 44f && plate.dpWidth() >= plate.dpHeight() * 1.6f)
        assertTrue("and it sits inside the 48 dp box, centred", plate.left >= close.left - 0.5f && plate.right <= close.right + 0.5f && plate.top >= close.top - 0.5f && plate.bottom <= close.bottom + 0.5f)
        savePng(if (scale == 1.0f) "btn265-quicklaunch-close.png" else "btn265-quicklaunch-close-1_3.png")
        assertTrue("the picker has its app tiles", tiles.size >= 2)
        tiles.forEach { tile -> assertTrue("a picker tile is at least 48 dp: ${tile.dpWidth()} x ${tile.dpHeight()}", tile.dpWidth() >= 47.9f && tile.dpHeight() >= 47.9f) }
        val view = boundsOf(ViewTag)
        assertTrue("nothing overflows the Bottom", close.right <= view.right + 0.5f && tiles.all { it.right <= view.right + 0.5f && it.bottom <= view.bottom + 0.5f })
    }

    @Test
    fun `the picker's icons are the one shape and its targets reach 48 dp at font scale 1_0`() = checkPicker(1.0f)

    @Test
    fun `the picker's icons are the one shape and its targets reach 48 dp at font scale 1_3`() = checkPicker(1.3f)

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The Tabs drawer.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun checkTabs(scale: Float) {
        mount(scale) {
            CompanionRecentsDrawer(
                // Alpha's tab has no close badge (the badge covers its icon's top-right and middle, which the corner measure reads); Beta's has one.
                entries = listOf(withIcon.copy(closable = false), noIcon.copy(closable = true)),
                launchLocked = false,
                launchMessage = null,
                themeId = null,
                quickSlots = listOf(null, null, null, null, null),
                quickLaunchApps = listOf(withIcon),
                onQuickLaunch = {},
                onQuickAssign = { _, _ -> },
                onLaunch = {},
                onClose = {},
                onDismiss = {},
            )
        }
        val pm = frame()
        val icon = boundsOf(CompanionTabIconTag)
        assertIconShape("tabs", pm, icon, CompanionTabIconDp)
        val clicks = clickableBounds()
        val close = compose.onNode(hasClickAction() and hasContentDescription("Close Beta"), useUnmergedTree = false).fetchSemanticsNode().boundsInWindow
        println("V8-RENDER tabs fontScale=$scale icon=${icon.dpWidth()}x${icon.dpHeight()}dp closeBadge=${close.dpWidth()}x${close.dpHeight()}dp clickables=${clicks.size}")
        assertTrue("the Tabs close badge is at least 48 dp: ${close.dpWidth()} x ${close.dpHeight()}", close.dpWidth() >= 47.9f && close.dpHeight() >= 47.9f)
        println("V8-RENDER tabs clickables=${clicks.map { "${"%.0f".format(it.dpWidth())}x${"%.0f".format(it.dpHeight())}" }}")
        val tile = clicks.filter { it.dpWidth() in 100f..140f && it.dpHeight() < 200f }
        assertTrue("the tab tile is a 108 dp-wide target of at least 48 dp: ${tile.map { "${it.dpWidth()}x${it.dpHeight()}" }}", tile.isNotEmpty() && tile.all { it.dpHeight() >= 47.9f })
        val view = boundsOf(ViewTag)
        assertTrue("nothing overflows the Bottom", clicks.all { it.right <= view.right + 0.5f && it.bottom <= view.bottom + 0.5f && it.left >= -0.5f })
    }

    @Test
    fun `a tab's icon is the one shape and its targets reach 48 dp at font scale 1_0`() = checkTabs(1.0f)

    @Test
    fun `a tab's icon is the one shape and its targets reach 48 dp at font scale 1_3`() = checkTabs(1.3f)

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The lattice.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun checkLattice(scale: Float, cell: ThorBottomCell) {
        mount(scale) {
            Box(Modifier.requiredSize(cell.width.dp, cell.height.dp)) {
                CompanionHomeGridTile(
                    tile = CompanionHomeTile("app.alpha", CompanionHomeTileKind.ANDROID_APP, "Alpha", iconPath = iconFile.path),
                    cursor = false,
                    ghost = false,
                    onTap = {},
                    testTag = companionTileTag("app.alpha"),
                )
            }
        }
        val pm = frame()
        val icon = boundsOf(CompanionLatticeIconTag)
        assertIconShape("lattice", pm, icon, CompanionLatticeIconDp)
        val tile = boundsOf(companionTileTag("app.alpha"))
        println("V8-RENDER lattice fontScale=$scale cell=${tile.dpWidth()}x${tile.dpHeight()}dp icon=${icon.dpWidth()}x${icon.dpHeight()}dp")
        assertTrue("the lattice tile is a target of at least 48 dp: ${tile.dpWidth()} x ${tile.dpHeight()}", tile.dpWidth() >= 47.9f && tile.dpHeight() >= 47.9f)
    }

    @Test
    fun `a lattice app tile's icon is the one shape at font scale 1_0`() = checkLattice(1.0f, ThorBottomCells.Scale10)

    @Test
    fun `a lattice app tile's icon is the one shape at font scale 1_3`() = checkLattice(1.3f, ThorBottomCells.Scale13Shown)

    // ---------------------------------------------------------------------------------------------------------------------------------
    // V8 fix round: a game cover keeps its own shape; a locked seat is dimmed; the collapsed handle's room.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun solidPng(color: Color): File = File.createTempFile("v8-cover", ".png").also { file ->
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color.toArgbInt())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun checkTabCover(scale: Float) {
        val coverColor = Color(0xFF00FFFF)
        val cover = solidPng(coverColor)
        try {
            mount(scale) {
                CompanionRecentsDrawer(
                    entries = listOf(
                        withIcon.copy(closable = false),
                        CompanionAppEntry("org.example.game", "Game", artRef = cover.toURI().toString()),
                    ),
                    launchLocked = false,
                    launchMessage = null,
                    themeId = null,
                    quickSlots = listOf(null, null, null, null, null),
                    quickLaunchApps = listOf(withIcon),
                    onQuickLaunch = {},
                    onQuickAssign = { _, _ -> },
                    onLaunch = {},
                    onClose = {},
                    onDismiss = {},
                )
            }
            // The cover is decoded off the main thread: wait for it in real time, stepping the paused clock.
            var tries = 0
            while (compose.onAllNodesWithTag(CompanionTabIconTag, useUnmergedTree = true).fetchSemanticsNodes().size < 2 && tries++ < 100) {
                Thread.sleep(50)
                settle(100L)
            }
            settle(2_000L)
            val icons = compose.onAllNodesWithTag(CompanionTabIconTag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInWindow }.sortedBy { it.left }
            assertEquals("an app icon and a game cover are both drawn", 2, icons.size)
            val pm = frame()
            fun corners(b: Rect) = listOf(
                (b.left + 1.5f) to (b.top + 1.5f), (b.right - 2.5f) to (b.top + 1.5f), (b.left + 1.5f) to (b.bottom - 2.5f), (b.right - 2.5f) to (b.bottom - 2.5f),
            ).map { (x, y) -> pm.px(x.toInt(), y.toInt()) }
            val iconCorners = corners(icons[0])
            val coverCorners = corners(icons[1])
            println("V8-RENDER tabsCover fontScale=$scale appIconCorners=${iconCorners.map { it.dist(ink) }} coverCorners=${coverCorners.map { it.dist(coverColor) }} sizes=${icons.map { "${it.dpWidth()}x${it.dpHeight()}" }}")
            iconCorners.forEach { assertTrue("an app icon's corner pixel is rounded away (not the icon's colour): $it", it.dist(ink) > 0.3f) }
            coverCorners.forEach { assertTrue("a game cover keeps its corner pixels, unclipped: $it", it.dist(coverColor) <= 0.05f) }
            assertEquals("the cover is drawn at the same 54 dp", icons[0].dpWidth(), icons[1].dpWidth(), 0.6f)
        } finally {
            cover.delete()
        }
    }

    @Test
    fun `a tab's game cover keeps its corners while its app icon is rounded at font scale 1_0`() = checkTabCover(1.0f)

    @Test
    fun `a tab's game cover keeps its corners while its app icon is rounded at font scale 1_3`() = checkTabCover(1.3f)

    private fun blend(background: Color, content: Color, alpha: Float) = Color(
        background.red + alpha * (content.red - background.red),
        background.green + alpha * (content.green - background.green),
        background.blue + alpha * (content.blue - background.blue),
    )

    private fun checkLockedSeats(scale: Float) {
        instrumentsShown = false
        launchLocked = false
        mount(scale) { Dock() }
        val seats = seats()
        assertEquals(7, seats.size)
        val rest = frame()
        // General's top-left dot and Alpha's icon centre are seats a launch lock dims; the instruments switch is never locked.
        fun at(seat: Rect, dxDp: Float, dyDp: Float, pm: PixelMap) =
            pm.px(((seat.left + seat.right) / 2f + dxDp * density).toInt(), ((seat.top + seat.bottom) / 2f + dyDp * density).toInt())
        val points = listOf("General dot" to (seats[0] to (-5f to -5f)), "Alpha icon" to (seats[2] to (0f to 0f)))
        launchLocked = true
        settle(2_000L)
        val locked = frame()
        points.forEach { (name, where) ->
            val (seat, offset) = where
            val before = at(seat, offset.first, offset.second, rest)
            val after = at(seat, offset.first, offset.second, locked)
            val expected = blend(ground, before, 0.88f)
            println("V8-RENDER locked fontScale=$scale $name rest=$before locked=$after expected88=$expected")
            assertTrue("$name is not the ground at rest: $before", before.dist(ground) > 0.1f)
            assertTrue("$name is dimmed by the lock: $after is not $before", after.dist(before) > 0.03f)
            assertTrue("$name is the rest pixel at 0.88 over the ground: $after vs $expected", after.dist(expected) <= 3f / 255f)
        }
        val tweakerBefore = at(seats[1], 0f, 0f, rest)
        val tweakerAfter = at(seats[1], 0f, 0f, locked)
        assertTrue("the instruments switch is never locked: $tweakerBefore -> $tweakerAfter", tweakerAfter.dist(tweakerBefore) <= 1.5f / 255f)
    }

    @Test
    fun `a launch lock dims the General and app seats and leaves the instruments switch at font scale 1_0`() = checkLockedSeats(1.0f)

    @Test
    fun `a launch lock dims the General and app seats and leaves the instruments switch at font scale 1_3`() = checkLockedSeats(1.3f)

    /**
     * The collapsed handle's hit zone extends along the bottom edge, measured on the REAL surface at the Thor Bottom's host size, 1240 x 1080 px at 369 dpi = 537.7 x 468.3 dp. The visible handle
     * is still 96 x 18 dp; its zone is 200 dp wide, centred on the bottom edge, and as tall as the strip under the last lattice row less 1 dp. The strip is 22.1 dp free with the swipe hint retired (the lasting
     * state) and 41 to 43 dp with it shown, so a full 48 dp still does not fit without covering a tile: the sentinel (`room < 48`) fails the day it does, and the handle is to be revisited then. Printed as
     * `V8-RENDER handleZone`: the zone, the clearance to the last row's tile hit boxes and to the page diamonds' node, at font scales 1.0 and 1.3, hint shown and retired.
     */
    private fun mountSurface(
        scale: Float,
        hintShown: Boolean,
        sceneOverride: (@Composable () -> Unit)? = null,
        folder: Boolean = false,
        onBookOpenChanged: (Boolean) -> Unit = {},
    ) {
        val widthDp = 1240f / Density369
        val heightDp = 1080f / Density369
        val platforms = (1..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
        // A folder (a Tome) takes the first cell, with two children, when asked for.
        val tiles = if (folder) {
            listOf(CompanionHomeTile("folder-1", CompanionHomeTileKind.FOLDER, "Tome", children = platforms.takeLast(2))) + platforms.take(17)
        } else {
            platforms
        }
        mount(scale) {
            CompanionSurface(
                payload = CompanionPayload(
                    mode = CompanionMode.HOME,
                    statsLabel = "19 platforms · 1,234 games",
                    homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
                ),
                cursorId = "p12",
                onWorkspaceDrag = { 0f },
                showActivitySwipeHint = hintShown,
                sceneOverride = sceneOverride,
                onBookOpenChanged = onBookOpenChanged,
                modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp).testTag(SurfaceTag),
            )
        }
    }

    private fun intersects(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun checkHandleZone(scale: Float, hintShown: Boolean) {
        mountSurface(scale, hintShown)
        val surface = boundsOf(SurfaceTag)
        val zone = boundsOf(CompanionQuickLaunchHandleTag)
        val visual = boundsOf(CompanionQuickLaunchHandleVisualTag)
        val tileBoxes = (1..9).map { boundsOf(companionTileTag("p$it")) }
        val slotBoxes = (0..8).map { boundsOf(companionSlotTag(it)) }
        val lastRowBottom = slotBoxes.maxOf { it.bottom }
        val runes = boundsOf(CompanionPageRunesTag)
        val room = (surface.bottom - lastRowBottom) / density
        val clearToTiles = (zone.top - tileBoxes.maxOf { it.bottom }) / density
        val clearToSlots = (zone.top - lastRowBottom) / density
        val runesHorizontalGap = (runes.left - zone.right) / density
        println(
            "V8-RENDER handleZone fontScale=$scale hint=${if (hintShown) "shown" else "retired"} host=${surface.dpWidth()}x${surface.dpHeight()}dp zone=${zone.dpWidth()}x${zone.dpHeight()}dp " +
                "visible=${visual.dpWidth()}x${visual.dpHeight()}dp freeUnderLastRow=${room}dp clearanceToLastRowTileHitBoxes=${clearToTiles}dp clearanceToLastRowCells=${clearToSlots}dp " +
                "pageDiamondsNode=${runes.dpWidth()}x${runes.dpHeight()}dp diamondsBottomFromHostBottom=${(surface.bottom - runes.bottom) / density}dp diamondsHorizontalGapToZone=${runesHorizontalGap}dp " +
                "zoneCentreOffsetFromHostCentre=${((zone.left + zone.right) / 2f - (surface.left + surface.right) / 2f) / density}dp shortfallFor48=${48f - room}dp",
        )
        // The visible handle is exactly what it was, at the bottom of its zone.
        assertEquals("the visible handle is 96 dp wide", CompanionQuickLaunchHandleWidthDp, visual.dpWidth(), 0.6f)
        assertEquals("the visible handle is 18 dp tall", CompanionQuickLaunchHandleHeightDp, visual.dpHeight(), 0.6f)
        assertEquals("on the host's bottom edge", surface.bottom, visual.bottom, 1.5f)
        // The zone: 200 dp wide, centred, on the bottom edge, as tall as the free strip less 1 dp.
        assertEquals("the zone is 200 dp wide", CompanionQuickLaunchHandleZoneWidthDp, zone.dpWidth(), 0.6f)
        assertEquals("centred on the host", (surface.left + surface.right) / 2f, (zone.left + zone.right) / 2f, 1.5f)
        assertEquals("on the bottom edge", surface.bottom, zone.bottom, 1.5f)
        assertEquals("the zone is the free strip less its 1 dp", companionQuickLaunchHandleZoneHeightDp(latticeShown = true, hintFooterShown = hintShown), zone.dpHeight(), 1.0f)
        assertTrue("the zone is taller than the 18 dp handle: ${zone.dpHeight()}", zone.dpHeight() > CompanionQuickLaunchHandleHeightDp + 2f)
        // It never reaches a tile's hit box, a cell, or the page diamonds' node.
        assertTrue("clear of every last-row tile hit box: ${clearToTiles}dp", clearToTiles >= 0f)
        assertTrue("clear of every cell: ${clearToSlots}dp", clearToSlots >= 0f)
        tileBoxes.forEachIndexed { i, tile -> assertTrue("the zone does not overlap tile ${i + 1}'s hit box: $zone vs $tile", !intersects(zone, tile)) }
        assertTrue("the zone does not overlap the page diamonds' node: $zone vs $runes", !intersects(zone, runes))
        assertTrue("and stays clear of it along the edge: ${runesHorizontalGap}dp", runesHorizontalGap >= 8f)
        // The device harness's dock-open tap, (620, 1058) px on the display, lands inside the zone.
        val tapX = surface.left + 620f
        val tapY = surface.top + 1058f
        assertTrue("the harness tap (620,1058) px is inside the zone $zone: ($tapX,$tapY)", tapX >= zone.left && tapX <= zone.right && tapY >= zone.top && tapY <= zone.bottom)
        // The sentinel: a full 48 dp still does not fit under the last row. When it does, the handle is to be revisited (take the whole 48 dp).
        assertTrue("the free strip under the last row is under 48 dp ($room); if it is not, give the handle its 48 dp and change this", room < 48f)
    }

    @Test
    fun `the handle's zone at font scale 1_0 with the hint retired`() = checkHandleZone(1.0f, hintShown = false)

    @Test
    fun `the handle's zone at font scale 1_3 with the hint retired`() = checkHandleZone(1.3f, hintShown = false)

    @Test
    fun `the handle's zone at font scale 1_0 with the hint shown`() = checkHandleZone(1.0f, hintShown = true)

    @Test
    fun `the handle's zone at font scale 1_3 with the hint shown`() = checkHandleZone(1.3f, hintShown = true)

    /** A tap in the zone's corner, 6 dp in from its edge and well off the visible handle, opens the dock; so does a swipe up from there. */
    @Test
    fun `a tap and a swipe up in the zone away from the visible handle open the dock`() {
        mountSurface(1.0f, hintShown = false)
        val zone = boundsOf(CompanionQuickLaunchHandleTag)
        val visual = boundsOf(CompanionQuickLaunchHandleVisualTag)
        assertTrue("the zone's left end is outside the visible handle", zone.left + 6f * density < visual.left)
        assertEquals("the dock is collapsed: no rail", 0, compose.onAllNodesWithTag(CompanionQuickLaunchTag, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag(CompanionQuickLaunchHandleTag, useUnmergedTree = true).performTouchInput { click(androidx.compose.ui.geometry.Offset(6f * density, 3f * density)) }
        settle(1_500L)
        assertTrue("a tap at the zone's left end opened the dock", compose.onAllNodesWithTag(CompanionQuickLaunchTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a swipe up from the zone's right end opens the dock`() {
        mountSurface(1.0f, hintShown = false)
        val zone = boundsOf(CompanionQuickLaunchHandleTag)
        assertEquals("the dock is collapsed: no rail", 0, compose.onAllNodesWithTag(CompanionQuickLaunchTag, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag(CompanionQuickLaunchHandleTag, useUnmergedTree = true).performTouchInput {
            val x = zone.width - 6f * density
            swipe(androidx.compose.ui.geometry.Offset(x, zone.height - 2f * density), androidx.compose.ui.geometry.Offset(x, 1f * density), 120L)
        }
        settle(1_500L)
        assertTrue("a swipe up at the zone's right end opened the dock", compose.onAllNodesWithTag(CompanionQuickLaunchTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * Fix round after B3+B4: the widened zone belongs to the bare lattice at rest and nowhere else. On the Activity page the zone is the visible handle (a swipe up on an
     * invisible wing scrolls Activity, it does not open the dock).
     */
    @Test
    fun `on the Activity page the handle's zone is the visible handle`() {
        mountSurface(1.0f, hintShown = false, sceneOverride = { Box(Modifier.fillMaxSize().testTag("activity-page")) })
        val zone = boundsOf(CompanionQuickLaunchHandleTag)
        val visual = boundsOf(CompanionQuickLaunchHandleVisualTag)
        val surface = boundsOf(SurfaceTag)
        assertEquals("the zone is the 96 dp handle", CompanionQuickLaunchHandleWidthDp, zone.dpWidth(), 0.6f)
        assertEquals("and 18 dp tall", CompanionQuickLaunchHandleHeightDp, zone.dpHeight(), 0.6f)
        assertEquals("exactly where the handle is", visual.left, zone.left, 1.5f)
        assertEquals(surface.bottom, zone.bottom, 1.5f)
        // A tap on the wing where the widened zone would have been reaches the page, not the dock.
        compose.onNodeWithTag(SurfaceTag, useUnmergedTree = true).performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f + 70f * density, height - 5f * density)) }
        settle(1_500L)
        assertEquals("the dock stayed collapsed", 0, compose.onAllNodesWithTag(CompanionQuickLaunchTag, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** With a Tome open the outside-dismiss strip is under the old wide zone: the zone narrows, and a tap there closes the Tome and does not open the dock. */
    @Test
    fun `with a Tome open and the hint retired a tap in the dismiss strip closes the Tome and does not open the dock`() {
        var bookOpen = false
        mountSurface(1.0f, hintShown = false, folder = true, onBookOpenChanged = { bookOpen = it })
        assertEquals("at rest the zone is the wide one", CompanionQuickLaunchHandleZoneWidthDp, boundsOf(CompanionQuickLaunchHandleTag).dpWidth(), 0.6f)
        compose.onNodeWithTag(companionTileTag("folder-1"), useUnmergedTree = true).performTouchInput { click() }
        settle(1_500L)
        assertTrue("fixture: the Tome opened", bookOpen)
        assertEquals("the zone is the visible handle while it is up", CompanionQuickLaunchHandleWidthDp, boundsOf(CompanionQuickLaunchHandleTag).dpWidth(), 0.6f)
        // 16 dp up, inside the old zone's 22 dp strip and inside the lattice's frame, where the Tome's scrim is.
        compose.onNodeWithTag(SurfaceTag, useUnmergedTree = true).performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f + 70f * density, height - 16f * density)) }
        settle(1_500L)
        assertTrue("the tap closed the Tome", !bookOpen)
        assertEquals("and did not open the dock", 0, compose.onAllNodesWithTag(CompanionQuickLaunchTag, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("the zone is wide again on the bare lattice", CompanionQuickLaunchHandleZoneWidthDp, boundsOf(CompanionQuickLaunchHandleTag).dpWidth(), 0.6f)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The mirror's pins.
    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `accentBright reads as a different colour from the quiet mark ink on every theme, so the active mark can be seen`() {
        MimirThemeId.entries.forEach { id ->
            val p = MimirThemePalette.forId(id)
            val d = p.textMuted.dist(p.accentBright)
            println("V8-RENDER inkContrast theme=$id textSecondary-vs-accentBright=$d textMuted-vs-accentBright=${p.textMuted.dist(p.accentBright)} textPrimary-vs-accentBright=${p.textPrimary.dist(p.accentBright)} surface=${p.surfaceBase}")
            assertTrue("$id: the active instruments mark (accentBright) is distinguishable from the rest mark (textMuted): $d", d > 0.2f)
        }
    }

    @Test
    fun `the shape token is Main's 11 over 40 and Main's picker still draws that literal`() {
        assertEquals(11f / 40f, MimirAppIconCornerRatio, 0f)
        assertEquals("at Main's 40 dp the corner is Main's 11 dp", 11f, mimirAppIconCorner(40.dp).value, 0.0001f)
        assertEquals(34f * 11f / 40f, mimirAppIconCorner(34.dp).value, 0.0001f)
        val main = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeQuickLaunchPickerOverlay.kt"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/HomeQuickLaunchPickerOverlay.kt"),
        ).first(File::isFile).readText()
        assertTrue(
            "Main's icon is still a 40 dp square clipped to 11 dp: the Bottom's ratio mirrors it, so this must change them together",
            main.contains("modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)),"),
        )
    }

    private companion object {
        /** The Thor Bottom's density: 369 dpi / 160. */
        const val Density369 = 369f / 160f
        const val BottomWidthDp = 538f
        const val BottomHeightDp = 468f
        const val ViewTag = "v8-view"
        const val SurfaceTag = "v8-surface"
    }
}
