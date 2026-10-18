package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Observed on Thor: the Bottom's long-press menu card ("ᛗ · BOTTOM SCREEN") opened touching the right edge of the screen. It opens at the coin's spot and the coin rests on that
 * edge, and the card's clamp allowed no margin. The card now keeps the overlay family's own edge margin (14 dp, what a fresh coin keeps) from every edge; the coin is not touched.
 *
 * The REAL controller opens the REAL menu on a Bottom display (538 x 468 dp at 369 dpi); the menu's window is laid out at the display's size and its card is read back and drawn over a
 * dark ground. The cards at the coin's default spot (the right edge), at a remembered spot against the top left, and against the bottom right land in `build/test-captures/sweep28a/`.
 * Pixel values are the JVM's (native graphics); the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomMenuCardEdgeCaptureTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private var bottomDisplayId = -1

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        CompanionState.set(CompanionPayload.idle())
        resetOverlaySingleton()
        ShadowSettings.setCanDrawOverlays(true)
        bottomDisplayId = ShadowDisplayManager.addDisplay("w538dp-h468dp-land-369dpi", 2)
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

    private class Edges(val left: Int, val top: Int, val right: Int, val bottom: Int, val widthPx: Int, val heightPx: Int, val density: Float, val bitmap: Bitmap)

    /** Opens the Bottom's menu with the card's remembered spot at ([xRatio], [yRatio]) of the display, or none, and reads the card's gaps to the screen's edges. */
    private fun openMenu(xRatio: Float?, yRatio: Float?): Edges {
        if (xRatio != null && yRatio != null) {
            app.getSharedPreferences("mimir_companion_overlay", Context.MODE_PRIVATE).edit()
                .putFloat("menu_secondary_companion_x", xRatio)
                .putFloat("menu_secondary_companion_y", yRatio)
                .commit()
        }
        val overlay = startCompanionOverlay(app, scope)
        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId))
        idle()
        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1_000)
        assertTrue("fixture: the menu is open", overlay.quickMenuOpen.value)
        val root = checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView")) as ViewGroup
        val metrics = root.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        val card = root.getChildAt(0)
        val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF10141C.toInt())
        root.draw(canvas)
        return Edges(
            left = card.left,
            top = card.top,
            right = metrics.widthPixels - card.right,
            bottom = metrics.heightPixels - card.bottom,
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            density = metrics.density,
            bitmap = bitmap,
        )
    }

    private fun write(bitmap: Bitmap, fileName: String) {
        val dir = File("build/test-captures/sweep28a").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SWEEP28A-F3 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue(out.isFile && out.length() > 1_000L)
    }

    private fun assertKeepsTheMargin(edges: Edges) {
        val inset = Math.round(14 * edges.density)
        val gaps = mapOf("left" to edges.left, "top" to edges.top, "right" to edges.right, "bottom" to edges.bottom)
        println("SWEEP28A-F3 display ${edges.widthPx}x${edges.heightPx}px density ${edges.density} gaps(px) $gaps, wanted at least $inset on every side")
        gaps.forEach { (side, gap) -> assertTrue("the card is $gap px from the $side edge, wanted at least $inset ($gaps)", gap >= inset) }
        assertTrue("the display is the Bottom's ${edges.widthPx / edges.density} dp wide", Math.abs(edges.widthPx / edges.density - 538f) < 1.5f)
    }

    @Test
    fun `the card opens at the coin's default spot on the right edge, and keeps the margin there`() {
        val edges = openMenu(null, null)
        write(edges.bitmap, "menu-card-default.png")
        assertKeepsTheMargin(edges)
        assertEquals("it still opens at the coin's side: its gap to the right edge is the margin and no more", Math.round(14 * edges.density).toFloat(), edges.right.toFloat(), 1.5f)
    }

    @Test
    fun `a card remembered against the top left keeps the margin from both edges`() {
        val edges = openMenu(0f, 0f)
        write(edges.bitmap, "menu-card-top-left.png")
        assertKeepsTheMargin(edges)
        assertEquals(Math.round(14 * edges.density).toFloat(), edges.left.toFloat(), 1.5f)
        assertEquals(Math.round(14 * edges.density).toFloat(), edges.top.toFloat(), 1.5f)
    }

    @Test
    fun `a card remembered against the bottom right keeps the margin from both edges`() {
        val edges = openMenu(1f, 1f)
        write(edges.bitmap, "menu-card-bottom-right.png")
        assertKeepsTheMargin(edges)
        assertEquals(Math.round(14 * edges.density).toFloat(), edges.right.toFloat(), 1.5f)
        assertEquals(Math.round(14 * edges.density).toFloat(), edges.bottom.toFloat(), 1.5f)
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
