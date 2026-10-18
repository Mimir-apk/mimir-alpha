package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import coil.request.SuccessResult
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirCoverCacheKey
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bottom's art paints on its FIRST frame when the art is already warm (audit RC-37: B-39 the game tile, B-44 the Cinematic hero, B-36 the shelf icon seat). A fake image loader
 * with a memory cache stands in for the app's: the cover cache is seeded with a solid colour under the product's cover key, the content is composed with the clock paused, and
 * the frame is rasterised before any effect has run. A tile that asked for its art by a bare string never met that key and drew nothing until its own load finished.
 *
 * The hero is also read through a cold arrival, frame by frame: in no frame is the hero showing without its scrim (the scrim is drawn in the hero's own pass, inside the
 * fade's layer), so there is never a bright hero for a frame before it darkens.
 *
 * Policy Q13 (2026-10-14): art that arrives COLD fades in from nothing on FocusMs with the house easing, with no placeholder; art that is warm when its site is born is
 * whole on the first frame and never fades. Both halves are read here in pixels, one 16 ms frame at a time, with the clock paused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionArtWarmPaintTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var loader: ImageLoader

    private val coldArt = ArrayList<String>()

    @Before
    fun setUp() {
        coldArt.clear()
        loader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.25).build() }
            .components { add(ColdInterceptor(red(), coldArt)) }
            .build()
        Coil.setImageLoader(loader)
    }

    @After
    fun tearDown() {
        Coil.reset()
    }

    private fun red(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.RED) }

    /** The cover cache's warm entry for [model], as the Main shelves leave it. */
    private fun warm(model: String) {
        loader.memoryCache!![MemoryCache.Key(mimirCoverCacheKey(model))] = MemoryCache.Value(red())
    }

    private class ColdInterceptor(private val art: Bitmap, private val asked: MutableList<String>) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            asked += request.data.toString()
            return SuccessResult(drawable = BitmapDrawable(request.context.resources, art), request = request, dataSource = DataSource.DISK)
        }
    }

    private fun step(ms: Long = 16L) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun shoot(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** The pixel at the middle of the 100 x 100 dp box the content stands in (the box is at the window's top-left). */
    private fun centre(): Int {
        val bitmap = shoot()
        val px = (50 * context.resources.displayMetrics.density).toInt()
        return bitmap.getPixel(px, px)
    }

    private fun isWhite(p: Int) = AndroidColor.red(p) > 245 && AndroidColor.green(p) > 245 && AndroidColor.blue(p) > 245
    private fun isPureRed(p: Int) = AndroidColor.red(p) > 240 && AndroidColor.green(p) < 30 && AndroidColor.blue(p) < 30

    private fun mount(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme { Box(Modifier.size(100.dp).background(Color.White)) { content() } }
        }
    }

    // ── the game tile's art (B-39) ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a game tile whose cover is warm paints it on the first frame and never fades`() {
        warm("/art/sonic.png")
        mount { CompanionShelfTileArt(artRef = "/art/sonic.png", modifier = Modifier.fillMaxSize()) }
        val p = centre()
        assertTrue("the cached cover is on the first frame (pixel ${Integer.toHexString(p)})", isPureRed(p))
        val after = List(12) { step(); centre() }
        assertTrue("and it stays whole while the load reports in: ${after.joinToString { Integer.toHexString(it) }}", after.all(::isPureRed))
    }

    @Test
    fun `a tile whose cover is cold draws nothing on its first frame, then fades in from nothing on FocusMs`() {
        val states = ArrayList<String>()
        mount { CompanionShelfTileArt(artRef = "/art/cold.png", modifier = Modifier.fillMaxSize(), onState = { states += it::class.java.simpleName }) }
        assertTrue("nothing is drawn on the first frame: no placeholder (R3/R8)", isWhite(centre()))
        // The art is red over a white ground: as it rises the green channel falls from 255 to 0. The load itself runs on Coil's own dispatcher, off the paused clock, so
        // frames are pumped (with a little real time between them) until the art first shows; every frame before that must be white.
        val greens = ArrayList<Int>()
        var pumped = 0
        while (greens.none { it < 255 } && pumped < 400) {
            step()
            Thread.sleep(5)
            greens += AndroidColor.green(centre())
            pumped += 1
        }
        repeat(MimirMotion.FocusMs / 16 + 8) {
            step()
            greens += AndroidColor.green(centre())
        }
        println("TILE-COLD green channel per frame: $greens; the loader was asked for: $coldArt; painter states: $states")
        val landed = greens.indexOfFirst { it < 255 }
        assertTrue("the art does land (the loader was asked for: $coldArt; painter states $states)", landed >= 0)
        assertTrue("it does not pop in at full strength: first landed frame ${greens[landed]}", greens[landed] > 120)
        assertTrue("it is part way on at least two frames: $greens", greens.count { it in 20..235 } >= 2)
        assertTrue("it only ever gets stronger: $greens", greens.zipWithNext().all { (a, b) -> b <= a })
        assertEquals("and it ends whole", 0, greens.last())
        val frames = greens.size - landed
        assertTrue("the rise takes about FocusMs (${MimirMotion.FocusMs} ms), not longer than ${frames * 16} ms of frames: $greens", frames >= 4)
    }

    // ── the Cinematic hero and its scrim (B-44) ────────────────────────────────────────────────────────────────────────────────

    private fun heroKey(uri: String): String {
        val envelope = MimirHeroDecode.observe(context)
        return mimirHeroCacheKey(uri, envelope)
    }

    @Test
    fun `a warm hero paints on the first frame, already under its scrim, and never fades`() {
        val uri = "/art/hero-warm.png"
        loader.memoryCache!![MemoryCache.Key(heroKey(uri))] = MemoryCache.Value(red())
        mount { CompanionLaunchHeroArt(hero = uri, modifier = Modifier.fillMaxSize()) }
        val p = centre()
        assertFalse("the hero is on the first frame: ${Integer.toHexString(p)}", isWhite(p))
        assertFalse("and it is under the scrim, never the bare hero: ${Integer.toHexString(p)}", isPureRed(p))
        val after = List(12) { step(); centre() }
        assertTrue("and it does not move while the load reports in: ${after.joinToString { Integer.toHexString(it) }}", after.all { it == p })
    }

    @Test
    fun `a hero that arrives cold fades in with its scrim from nothing, and is never on a frame without it`() {
        mount { CompanionLaunchHeroArt(hero = "/art/hero-cold.png", modifier = Modifier.fillMaxSize()) }
        val frames = ArrayList<Int>()
        frames += centre()
        repeat(MimirMotion.FocusMs / 16 + 8) {
            step()
            frames += centre()
        }
        println("HERO-COLD centre pixels: ${frames.joinToString { Integer.toHexString(it) }}")
        assertTrue("nothing on the first frame: no placeholder", isWhite(frames.first()))
        assertFalse("no frame shows the bare hero without the scrim", frames.any(::isPureRed))
        assertTrue("and the hero does arrive, under the scrim", frames.any { !isWhite(it) })
        // White ground, a red hero under a dark scrim: the green channel falls as the layer rises.
        val greens = frames.map(AndroidColor::green)
        assertTrue("it is part way on at least two frames: $greens", greens.count { it in 40..235 } >= 2)
        assertTrue("it only ever gets stronger: $greens", greens.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("and it settles: ${greens.takeLast(3)}", greens.takeLast(3).distinct().size == 1)
    }

    // ── the sources ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `the tile, the hero and the shelf icon seat ask for their art by the product's requests, not by a bare string`() {
        val shelf = source("CompanionPlatformShelf.kt")
        assertTrue(shelf.contains("val request = remember(artRef, context) { mimirCoverRequest(context, artRef) }"))
        assertFalse("no bare-string model at the tile", shelf.contains("model = artRef,"))
        val looks = source("CompanionLaunchCardLooks.kt")
        assertTrue(looks.contains("mimirHomeStaticHeroRequest(context, hero, envelope)"))
        assertFalse("the scrim is not a later recomposition any more", looks.contains("if (loaded)"))
        val header = source("CompanionShelfHeader.kt")
        assertTrue(header.contains("mimirCoverRequest(context, it)"))
        assertFalse("no bare-string model at the icon seat", header.contains("model = wiredUri,"))
        // The density menu no longer reads its animated alpha in composition.
        assertFalse("the density popover's guard is not the alpha", header.contains("menuAlpha > 0.001f"))
        assertTrue(header.contains("if (open || fade.mounted)"))
        assertTrue(header.contains("graphicsLayer { alpha = fade.alpha }"))
    }
}
