package com.gruning.mimir.rewrite.feature.library

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import coil.request.SuccessResult
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The render checks the Android launch card of an app with a white adaptive icon (Calendar's) beside a cartridge game's card, both the REAL [HomeLaunchCard] at Thor Main's geometry
 * (1920 x 1080 px at density 369, landscape, 832.5 x 468.3 dp; the JVM window is 832 x 468 dp) at font 1.0, the icon rendered at the px its art box measures. the icon (an adaptive icon's foreground layer, no white
 * background or mask) sits on a dark etched plate that is exactly the cartridge's area and slot; this test asserts that, and that no white disc is painted (the plate's corners and edge midpoints are dark).
 *
 * Written to `$MIMIR_RENDER_DIR/android-card-plate.png` when that variable names a folder; otherwise the render is made and checked and not saved. The icon is an [AdaptiveIconDrawable] over
 * [CalendarLikeIcon], a resolution-independent stand-in for the real launcher drawable, which a JVM cannot load. Pixel values are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w832dp-h468dp-land-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardAndroidCardRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calendar = "com.google.android.calendar"
    private var shown by mutableStateOf<HomeLaunchCardUiState?>(null)

    @After
    fun clean() {
        HomeLaunchCardIconCache.clear()
        Coil.reset()
    }

    /** The FOREGROUND layer of a Calendar-like adaptive icon: a blue square with a "31" on its 108 dp canvas, nothing behind it (the white background is the icon's other layer), drawn at whatever size it is given. */
    private class CalendarLikeIcon : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val s = bounds.width().toFloat()
            val l = bounds.left.toFloat()
            val t = bounds.top.toFloat()
            paint.style = Paint.Style.FILL
            val box = RectF(l + s * 0.25f, t + s * 0.25f, l + s * 0.75f, t + s * 0.75f)
            val r = s * 0.06f
            paint.color = AndroidColor.rgb(66, 133, 244)
            canvas.drawRoundRect(box, r, r, paint)
            paint.color = AndroidColor.rgb(52, 168, 83)
            canvas.drawRoundRect(RectF(box.left, box.centerY() + s * 0.06f, box.right, box.bottom), r, r, paint)
            paint.color = AndroidColor.rgb(251, 188, 4)
            canvas.drawRect(RectF(box.right - s * 0.12f, box.top + s * 0.16f, box.right, box.centerY() + s * 0.1f), paint)
            paint.color = AndroidColor.WHITE
            canvas.drawRect(RectF(box.left + s * 0.12f, box.top + s * 0.12f, box.right - s * 0.12f, box.bottom - s * 0.12f), paint)
            paint.color = AndroidColor.rgb(26, 115, 232)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = s * 0.24f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("31", box.centerX(), box.centerY() + s * 0.08f, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun installCalendar() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = calendar; applicationInfo = ApplicationInfo().apply { packageName = calendar; name = calendar } })
        // An adaptive icon with a WHITE background layer: before the adaptive-icon foreground change the card painted it as a bare white disc.
        pm.setApplicationIcon(calendar, AdaptiveIconDrawable(ColorDrawable(AndroidColor.WHITE), CalendarLikeIcon()))
    }

    /** A cartridge: a grey shell, a label, the notch, as a square render (physical media is square on the card). */
    private fun cartridgeBitmap(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = AndroidColor.rgb(92, 94, 104)
        c.drawRoundRect(RectF(60f, 30f, 452f, 482f), 28f, 28f, p)
        p.color = AndroidColor.rgb(60, 62, 70)
        c.drawRect(RectF(120f, 420f, 392f, 482f), p)
        p.color = AndroidColor.rgb(214, 204, 168)
        c.drawRoundRect(RectF(96f, 70f, 416f, 330f), 14f, 14f, p)
        p.color = AndroidColor.rgb(40, 44, 70)
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 40f
        p.textAlign = Paint.Align.CENTER
        c.drawText("CHRONO", 256f, 180f, p)
        c.drawText("TRIGGER", 256f, 230f, p)
        return bmp
    }

    /** The cover loader answers every request with the cartridge render, so the frame does not wait on a decode (the card's wired check still wants the file to exist). */
    private class FixedArtInterceptor(private val art: Bitmap) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            return SuccessResult(drawable = BitmapDrawable(request.context.resources, art), request = request, dataSource = DataSource.DISK)
        }
    }

    @Before
    fun loader() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.25).build() }
                .components { add(FixedArtInterceptor(cartridgeBitmap())) }
                .build(),
        )
    }

    private fun mount() {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
            CompositionLocalProvider(
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f),
            ) {
                MimirTheme {
                    Box(Modifier.fillMaxSize().background(LocalMimirPalette.current.surfaceBase)) {
                        shown?.let { state ->
                            HomeLaunchCard(state = state, onPlay = {}, onAchievements = {}, onOpenWith = {}, onClose = {}, authority = authority)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun boundsOf(tag: String) =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun capture(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Lets the icon render (off the main thread) and the cartridge decode (Coil), then reads the frame. */
    private fun settle() {
        repeat(60) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(40)
        }
        compose.waitForIdle()
    }

    @Test
    fun `render the Android launch card beside a cartridge game's card at Thor Main`() {
        installCalendar()
        val cache = File(ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir, NativeShelfIconCacheDirName).apply { mkdirs() }
        val iconFile = File(cache, "$calendar-com.google.android.calendar.AllInOneCalendarActivity-e1-render.png").apply { writeBytes(ByteArray(8)) }
        val cartridge = File.createTempFile("cartridge-render", ".png").apply { writeBytes(ByteArray(8)); deleteOnExit() }
        mount()

        shown = HomeLaunchCardUiState(
            row = HomeShelfRow(
                itemId = HomeShelfItemId.AndroidGame(AndroidPackageName(calendar)),
                rank = 0L, addedAtMillis = 0L, title = "Calendar",
                artworkUri = iconFile.absolutePath, tileIconUri = iconFile.absolutePath,
                supportingLabel = "Android game",
            ),
            platformLabel = "Android",
        )
        settle()
        val androidCard = capture()
        val plate = boundsOf(HomeLaunchCardIconPlateTestTag)
        val androidArt = boundsOf(HomeLaunchCardCoverArtTestTag)

        shown = HomeLaunchCardUiState(
            row = HomeShelfRow(
                itemId = HomeShelfItemId.Emulated(GameId("chrono-trigger")),
                rank = 0L, addedAtMillis = 0L, title = "Chrono Trigger", supportingLabel = "snes",
            ),
            platformLabel = "Super Nintendo",
            emulatorLabel = "Snes9x EX+",
            lastPlayedLabel = "Yesterday",
            cartridgeArtUri = cartridge.absolutePath,
            cartridgeArtIsPhysical = true,
        )
        settle()
        val cartridgeCard = capture()
        val cartridgeArt = boundsOf(HomeLaunchCardCoverArtTestTag)

        // the icon sits on a dark plate that IS the cartridge's slot (same area, same place), and no white disc is painted.
        fun near(a: Float, b: Float) = kotlin.math.abs(a - b) <= 1.5f
        assertTrue("the plate is the Android art box ($plate vs $androidArt)", near(plate.left, androidArt.left) && near(plate.top, androidArt.top) && near(plate.width, androidArt.width) && near(plate.height, androidArt.height))
        assertTrue("the plate is the cartridge's slot and area ($plate vs $cartridgeArt)", near(plate.left, cartridgeArt.left) && near(plate.top, cartridgeArt.top) && near(plate.width, cartridgeArt.width) && near(plate.height, cartridgeArt.height))
        fun luma(p: Int) = 0.2126f * AndroidColor.red(p) + 0.7152f * AndroidColor.green(p) + 0.0722f * AndroidColor.blue(p)
        fun at(fx: Float, fy: Float) = androidCard.getPixel((plate.left + plate.width * fx).toInt(), (plate.top + plate.height * fy).toInt())
        listOf(0.06f to 0.06f, 0.94f to 0.06f, 0.06f to 0.94f, 0.94f to 0.94f, 0.5f to 0.03f, 0.03f to 0.5f, 0.97f to 0.5f, 0.5f to 0.97f).forEach { (fx, fy) ->
            println("PLATE pixel ($fx, $fy) = #${Integer.toHexString(at(fx, fy))} luma ${luma(at(fx, fy))}")
            assertTrue("plate pixel ($fx, $fy) is dark, not the old white disc (luma ${luma(at(fx, fy))})", luma(at(fx, fy)) < 70f)
        }
        val blue = at(0.30f, 0.40f)
        assertTrue("the icon's foreground is drawn on the plate (blue edge of the calendar square, is #${Integer.toHexString(blue)})", AndroidColor.blue(blue) > 150 && AndroidColor.red(blue) < 120)

        val w = androidCard.width
        val h = androidCard.height
        val both = Bitmap.createBitmap(w * 2 + 24, h, Bitmap.Config.ARGB_8888)
        Canvas(both).apply {
            drawColor(AndroidColor.rgb(20, 20, 24))
            drawBitmap(androidCard, 0f, 0f, null)
            drawBitmap(cartridgeCard, (w + 24).toFloat(), 0f, null)
        }
        println("RENDER ${w}x$h per card; icon cache holds ${HomeLaunchCardIconCache.get(HomeLaunchCardIconCache.key(calendar, 592))?.width}")
        assertTrue("the frame is Thor Main's 1920 x 1080 px to within a few px (is ${w}x$h)", kotlin.math.abs(w - 1920) <= 6 && kotlin.math.abs(h - 1080) <= 6)
        System.getenv("MIMIR_RENDER_DIR")?.takeIf(String::isNotBlank)?.let { dir ->
            File(dir).mkdirs()
            FileOutputStream(File(dir, "android-card-plate.png")).use { both.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        iconFile.delete()
    }
}
