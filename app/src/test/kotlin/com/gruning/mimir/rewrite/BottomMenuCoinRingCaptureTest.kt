package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.io.File
import java.time.Duration
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

/**
 * Sweep 10-09, the Bottom's ᛗ long-press menu ("ᛗ · BOTTOM SCREEN"): the focused coin showed TWO outlines with a gap between them, the gold focus ring and, inside it, the coin's own metal
 * rim. A focused element hands its rim to the ring , so the lit coin now draws its ground only and the ring hugs the disc as its one edge; an unlit coin keeps its rim.
 *
 * The REAL controller opens the REAL menu on a Bottom display; each coin and the ring are drawn into a bitmap and read back along a radius. The picture lands in `app/build/sweepb/` and
 * `build/test-captures/sweepb/`. Pixel values are the JVM's (native graphics); the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomMenuCoinRingCaptureTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private var bottomDisplayId = -1

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        CompanionState.set(CompanionPayload.idle())
        resetOverlaySingleton()
        ShadowSettings.setCanDrawOverlays(true)
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        resetOverlaySingleton()
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        idle()
    }

    private class Menu(val root: View, val coins: List<View>, val ring: View, val overlay: AndroidCompanionOverlayController)

    private fun openMenu(): Menu {
        val overlay = startCompanionOverlay(app, scope)
        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId))
        idle()
        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1_000)
        assertTrue("fixture: the menu is open", overlay.quickMenuOpen.value)
        val root = checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView"))
        val coins = root.descendants().filter { it.javaClass.simpleName == "CompanionMenuCoinView" }.toList()
        val ring = root.descendants().single { it.javaClass.simpleName == "CompanionMenuFocusRingView" }
        assertTrue("fixture: the menu has its coins (${coins.size})", coins.size >= 4)
        return Menu(root, coins, ring, overlay)
    }

    private fun draw(menu: Menu): Bitmap {
        val density = menu.root.resources.displayMetrics.density
        menu.root.measure(
            View.MeasureSpec.makeMeasureSpec((900 * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((900 * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        menu.root.layout(0, 0, menu.root.measuredWidth, menu.root.measuredHeight)
        val bitmap = Bitmap.createBitmap(menu.root.measuredWidth, menu.root.measuredHeight, Bitmap.Config.ARGB_8888)
        menu.root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun write(bitmap: Bitmap, fileName: String) {
        val out = File(File("build/sweepb").apply { mkdirs() }, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, "sweepb/$fileName").apply { parentFile.mkdirs() }, overwrite = true)
        println("SWEEPB wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue(out.isFile && out.length() > 1_000L)
    }

    /** A coin's centre and half-size in the menu root's own coordinates. */
    private fun centreOf(menu: Menu, coin: View): Triple<Float, Float, Float> {
        val rect = android.graphics.Rect(0, 0, coin.width, coin.height)
        (menu.root as ViewGroup).offsetDescendantRectToMyCoords(coin, rect)
        return Triple(rect.exactCenterX(), rect.exactCenterY(), minOf(rect.width(), rect.height()) / 2f)
    }

    private fun luma(pixel: Int): Float =
        ((pixel shr 16 and 0xff) * 0.3f + (pixel shr 8 and 0xff) * 0.6f + (pixel and 0xff) * 0.1f)

    /** The brightest pixel on the circle of [radius] px about ([cx], [cy]), over every angle in 1-degree steps that falls inside the bitmap. */
    private fun brightestOnCircle(bitmap: Bitmap, cx: Float, cy: Float, radius: Float): Float {
        var best = 0f
        for (degrees in 0 until 360 step 1) {
            val rad = Math.toRadians(degrees.toDouble())
            val x = (cx + radius * Math.cos(rad)).toInt()
            val y = (cy + radius * Math.sin(rad)).toInt()
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) continue
            best = maxOf(best, luma(bitmap.getPixel(x, y)))
        }
        return best
    }

    /** The mean luminance on the circle of [radius] px, over the same angles: a band that is the ring's or the rim's reads bright all the way round; the disc's ground does not. */
    private fun meanOnCircle(bitmap: Bitmap, cx: Float, cy: Float, radius: Float): Float {
        var sum = 0f
        var count = 0
        for (degrees in 0 until 360 step 3) {
            val rad = Math.toRadians(degrees.toDouble())
            val x = (cx + radius * Math.cos(rad)).toInt()
            val y = (cy + radius * Math.sin(rad)).toInt()
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) continue
            sum += luma(bitmap.getPixel(x, y))
            count += 1
        }
        return sum / count
    }

    @Test
    fun `the lit coin draws its ground only and the ring is its one edge, an unlit coin keeps its rim`() {
        val menu = openMenu()
        val density = menu.root.resources.displayMetrics.density
        val lit = menu.coins.first()
        val unlit = menu.coins[1]
        assertTrue("fixture: the ring is lit on the first coin when the menu opens", ReflectionHelpers.getField<Boolean>(lit, "ringed"))
        assertFalse("and on no other", ReflectionHelpers.getField<Boolean>(unlit, "ringed"))
        val bitmap = draw(menu)
        write(bitmap, "bottom-menu-coin-ring-first-lit.png")

        val rim = 1.25f * density
        val stroke = 3f * density
        val (lx, ly, litHalf) = centreOf(menu, lit)
        val (ux, uy, unlitHalf) = centreOf(menu, unlit)
        val discEdge = litHalf - rim
        // Inside the disc's edge, where the coin's rim band stands (centred on the disc radius, rim wide): bright on an unlit coin, the ground's own on the lit one.
        val litRimBand = meanOnCircle(bitmap, lx, ly, discEdge - rim * 1.6f)
        val unlitRimBand = meanOnCircle(bitmap, ux, uy, (unlitHalf - rim) - rim * 0.0f)
        val unlitInside = meanOnCircle(bitmap, ux, uy, (unlitHalf - rim) - rim * 3f)
        println("SWEEPB coin bands: lit inside-edge band=$litRimBand, unlit rim band=$unlitRimBand, unlit disc=$unlitInside")
        assertTrue("an unlit coin's rim reads brighter than its own disc ($unlitRimBand vs $unlitInside): it keeps its rim", unlitRimBand > unlitInside + 12f)
        // The ring hugs the disc: bright all the way round at the ring's centre line, one stroke wide, starting AT the disc's edge.
        val ringMid = discEdge + stroke / 2f
        val ringBand = meanOnCircle(bitmap, lx, ly, ringMid)
        assertTrue("the ring is the lit coin's bright band at its centre line ($ringBand)", ringBand > unlitInside + 20f)
        // No second outline: between the disc's own ground just inside the ring and the ring there is no bright band of the rim's, and no dark gap between the ring and the disc.
        val justInside = meanOnCircle(bitmap, lx, ly, discEdge - rim * 1.2f)
        val gapBand = meanOnCircle(bitmap, lx, ly, discEdge + rim * 0.6f)
        println("SWEEPB lit coin: just inside the disc edge=$justInside, ring's first pixels=$gapBand, ring mid=$ringBand")
        assertTrue("no dark gap between the disc and the ring: the ring's first pixels ($gapBand) are the ring, not the ground ($justInside)", gapBand > justInside + 12f)
        assertTrue("the ring is not a thin rim over a wider ring: the disc's edge band is the ground ($justInside), well below the ring ($ringBand)", justInside + 15f < ringBand)
        // The ring's outer edge stays inside the gap to the label under the coin.
        assertTrue("the ring reaches under the 4 dp label gap", discEdge + stroke <= litHalf + 4f * density)
    }

    @Test
    fun `moving the ring moves the rim - the coin that loses the ring gets its rim back and the one that gains it loses its own`() {
        val menu = openMenu()
        val ringView = menu.ring
        val first = menu.coins[0]
        val second = menu.coins[1]
        // The property's setter does the work (it hands the rim over); call the setter, not the field.
        ReflectionHelpers.callInstanceMethod<Unit>(ringView, "setLitIndex", ReflectionHelpers.ClassParameter.from(Integer::class.java, 1))
        assertFalse("the coin that lost the ring wears its rim again", ReflectionHelpers.getField<Boolean>(first, "ringed"))
        assertTrue("the coin that gained it hands its rim over", ReflectionHelpers.getField<Boolean>(second, "ringed"))
        ReflectionHelpers.callInstanceMethod<Unit>(ringView, "setLitIndex", ReflectionHelpers.ClassParameter.from(Integer::class.java, null))
        assertFalse(ReflectionHelpers.getField<Boolean>(second, "ringed"))
        write(draw(menu), "bottom-menu-coin-ring-none-lit.png")
        assertEquals(true, hypot(1f, 1f) > 1f)
    }

    // ---- fixtures ----

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private fun advance(ms: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resetOverlaySingleton() {
        ReflectionHelpers.setStaticField(AndroidCompanionOverlayController::class.java, "shared", null)
    }
}
