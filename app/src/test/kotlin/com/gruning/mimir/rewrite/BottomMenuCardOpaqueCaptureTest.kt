package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
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
 * Fix 29 G3 (sweep vqa8, Thor): on the Super Nintendo shelf the label "Super Metroid" showed faintly through the header of the Bottom's ᛗ menu card ("ᛗ · BOTTOM SCREEN"), and the shelf behind the
 * card was not blurred there. The card's wash was glass (232 at its top, 220 at its foot), so a bright label right behind it read through a tenth of the way; the header is where it showed because
 * that is where the shelf's labels stand. The Bottom's card is opaque now, top to foot (the gradient, the rim and the top sheen are as they were).
 *
 * The REAL controller opens the REAL menu on a Bottom display and the card is drawn twice, over a white ground and over a black one: where the card is opaque the two pictures are the same. The
 * header's band and the card's whole inside are held to that, and a third picture, over a stand-in of the shelf with a bright label behind the header, is the one to look at. The PNGs land in
 * `build/test-captures/fix29a/`. Pixel values are the JVM's (native graphics); the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomMenuCardOpaqueCaptureTest {

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

    private fun openMenu(): View {
        val overlay = startCompanionOverlay(app, scope)
        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId))
        idle()
        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1_000)
        assertTrue("fixture: the menu is open", overlay.quickMenuOpen.value)
        return checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView"))
    }

    private fun layoutCard(root: View) {
        val density = root.resources.displayMetrics.density
        root.measure(
            View.MeasureSpec.makeMeasureSpec((900 * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((900 * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    /** The card drawn over [ground]: [paintBehind] first (what stands behind it), then the card. */
    private fun drawOver(root: View, ground: Int, paintBehind: (Canvas, Float) -> Unit = { _, _ -> }): Bitmap {
        val density = root.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ground)
        paintBehind(canvas, density)
        root.draw(canvas)
        return bitmap
    }

    private fun write(bitmap: Bitmap, fileName: String) {
        val out = File(File("build/fix29a").apply { mkdirs() }, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, "fix29a/$fileName").apply { parentFile.mkdirs() }, overwrite = true)
        println("FIX29A wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue(out.isFile && out.length() > 1_000L)
    }

    private fun channelGap(a: Int, b: Int): Int =
        maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))

    /** The largest channel difference between two pictures of the card over the pixels of [rect] (left, top, right, bottom), and where. */
    private fun worstGap(over1: Bitmap, over2: Bitmap, rect: IntArray): Pair<Int, String> {
        var worst = 0
        var at = ""
        for (y in rect[1] until rect[3]) for (x in rect[0] until rect[2]) {
            val gap = channelGap(over1.getPixel(x, y), over2.getPixel(x, y))
            if (gap > worst) { worst = gap; at = "$x,$y" }
        }
        return worst to at
    }

    /** Super Nintendo's shelf, as far as the card's header sees it: a dark ground with a bright cream label standing right behind where the header's words are. */
    private fun shelfStandIn(canvas: Canvas, density: Float) {
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 255, 240, 200)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 17f * density
        }
        canvas.drawText("Super Metroid", 20f * density, 46f * density, label)
    }

    @Test
    fun `no content reads through any part of the Bottom menu card, its header included`() {
        val root = openMenu()
        layoutCard(root)
        val density = root.resources.displayMetrics.density
        // The shelf stand-in is drawn FIRST: the first draw of the view is the one a handheld shows. Then over white, over black, and over white again: the card paints the same each time it is
        // drawn (its paint is shared with the strokes, which once left their alpha on the next fill, so every draw after the first was a third as opaque).
        val overShelf = drawOver(root, Color.rgb(20, 22, 26), ::shelfStandIn)
        val overWhite = drawOver(root, Color.WHITE)
        val overBlack = drawOver(root, Color.BLACK)
        val overWhiteAgain = drawOver(root, Color.WHITE)
        write(overWhite, "bottom-menu-card-over-white.png")
        write(overBlack, "bottom-menu-card-over-black.png")
        write(overShelf, "bottom-menu-card-over-shelf-label.png")

        // The card's inside, clear of the rounded corners, and its header band (the top of it, where the title stands).
        val corner = (16f * density).toInt() + 2
        val inside = intArrayOf(corner, corner, root.width - corner, root.height - corner)
        val header = intArrayOf(corner, corner, root.width - corner, (50f * density).toInt())
        val (headerGap, headerAt) = worstGap(overWhite, overBlack, header)
        val (bodyGap, bodyAt) = worstGap(overWhite, overBlack, inside)
        println("FIX29A card ${root.width}x${root.height} px: header band white-vs-black gap = $headerGap/255 at $headerAt, whole inside = $bodyGap/255 at $bodyAt")
        assertTrue("the header is opaque: what is behind it (white or black) does not change a pixel ($headerGap/255 at $headerAt)", headerGap <= 2)
        assertTrue("and no part of the card lets the ground through ($bodyGap/255 at $bodyAt)", bodyGap <= 2)

        val (redrawGap, redrawAt) = worstGap(overWhite, overWhiteAgain, inside)
        println("FIX29A card drawn twice over white: worst gap = $redrawGap/255 at $redrawAt")
        assertTrue("a second draw paints the card as the first ($redrawGap/255 at $redrawAt)", redrawGap <= 2)

        // The label behind the header leaves no ghost: the card over the shelf stand-in is the card over a plain ground, pixel for pixel.
        val (labelGap, labelAt) = worstGap(overShelf, overBlack, inside)
        println("FIX29A card over the shelf stand-in vs over black: worst gap = $labelGap/255 at $labelAt")
        assertTrue("the bright label standing behind the header does not show ($labelGap/255 at $labelAt)", labelGap <= 2)
    }

    // ---- fixtures ----

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
