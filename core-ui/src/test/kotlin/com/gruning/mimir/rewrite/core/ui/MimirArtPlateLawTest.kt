package com.gruning.mimir.rewrite.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A cold start must never show fallback behind wired custom or sourced art. Fallback — the
 * square plate and glyph/book — belongs only to an item with no art.
 *
 * Before: [MimirSwitchTileArt] and [MimirLedgeCoverArt] seated a `surfaceHigh` plate for as long as `reveal < 1f`, which
 * is the whole cold decode plus the fade, and [MediaArtFrame] / [MimirFlatCoverArt] seated theirs UNDER wired art for good,
 * so a transparent PNG always sat on a square. The earlier fix (`9723de815`) only dropped the tile's plate once the art was
 * fully revealed, which left the cold-start square this rule is about.
 *
 * These render the real components under Robolectric's native graphics against a magenta host and read pixels back. The
 * Coil singleton is replaced by an interceptor that answers by model: PENDING never completes (the cold decode, held open
 * deterministically), READY returns a bitmap whose corners and edge ring are TRANSPARENT around an opaque disc, ERROR fails.
 *
 * **What this does not prove:** a real decode of a real file (Coil from a file URI does not decode reliably under Robolectric),
 * the 140 ms reveal curve itself (its tween is untouched here, only the surface behind it changed), and how the shelf reads on
 * the Nova's panel. The cold-start square was observed on Nova.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirArtPlateLawTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun installStubLoader() {
        // The availability stat is off-main in production; here it runs inline so its verdict lands before the first assertion.
        MimirArtAvailability.clearForTest()
        MimirArtAvailability.dispatcher = Dispatchers.Unconfined
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(StubInterceptor(discOnTransparent())) }
                .build(),
        )
    }

    @After
    fun resetLoader() {
        MimirArtAvailability.dispatcher = Dispatchers.IO
        MimirArtAvailability.clearForTest()
        Coil.reset()
    }

    /**
     * The witness that a failure test really observed a failure: the painter reached Error, which is what [MimirArtFailureLog] reports
     * once per model. Without it a painter stuck in Loading would draw the same bare host and pass the pixel assertions.
     */
    private fun assertPainterReachedError(model: String) {
        val logged = ShadowLog.getLogsForTag(MimirArtFailureLogTag).any { it.msg.contains(model) }
        assertTrue("the painter never reached Error for $model; a permanent Loading would pass the pixels", logged)
    }

    private class StubInterceptor(private val art: Bitmap) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            return when (request.data) {
                Pending -> awaitCancellation()
                Ready -> SuccessResult(
                    drawable = BitmapDrawable(request.context.resources, art),
                    request = request,
                    dataSource = DataSource.MEMORY,
                )
                else -> ErrorResult(drawable = null, request = request, throwable = IllegalStateException("decode failed"))
            }
        }
    }

    /** 64 px, opaque green disc in the middle, every other pixel fully transparent: a SteamGridDB-style icon. */
    private fun discOnTransparent(): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(32f, 32f, 20f, Paint().apply { color = ArtGreen.toArgbInt(); isAntiAlias = false })
        return bitmap
    }

    private fun Color.toArgbInt(): Int = this.toArgb()

    private fun host(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMimirPalette provides MimirThemePalette.forId(MimirThemeId.DEFAULT),
            ) {
                Box(Modifier.size(120.dp).background(HostMagenta).testTag(Host)) { content() }
            }
        }
        compose.waitForIdle()
        // The reveal is a 140 ms tween; give a ready bitmap time to land and finish it.
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /**
     * The host's pixels, drawn by the view hierarchy itself into a software bitmap. `captureToImage` waits on a
     * redraw callback that Robolectric's paused looper never delivers here (the same wall HomeTileMenuSwapScrimTest hit).
     */
    private fun pixels(): androidx.compose.ui.graphics.PixelMap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val bounds = compose.onNodeWithTag(Host).fetchSemanticsNode().boundsInWindow
        val crop = Bitmap.createBitmap(
            full,
            bounds.left.toInt(),
            bounds.top.toInt(),
            bounds.width.toInt(),
            bounds.height.toInt(),
        )
        return crop.asImageBitmap().toPixelMap()
    }

    private fun androidx.compose.ui.graphics.PixelMap.at(fx: Float, fy: Float): Color =
        this[(width * fx).toInt().coerceIn(0, width - 1), (height * fy).toInt().coerceIn(0, height - 1)]

    private fun assertHost(label: String, color: Color) {
        assertEquals("$label: expected the host behind, got $color", HostMagenta.toArgbInt(), color.toArgbInt())
    }

    /** Samples spread across the art box, none of them on a corner the rounded clip would hide. */
    private val spread = listOf(0.5f to 0.5f, 0.2f to 0.5f, 0.5f to 0.2f, 0.8f to 0.5f, 0.5f to 0.8f, 0.3f to 0.3f, 0.7f to 0.7f)

    // ---- the predicate -------------------------------------------------------------------------------

    @Test
    fun `a plate is only for an item with no art`() {
        assertTrue(mimirArtPlateVisible(hasArtSource = false))
        assertFalse(mimirArtPlateVisible(hasArtSource = true))
    }

    // ---- MediaArtFrame -------------------------------------------------------------------------------

    @Test
    fun `a frame with art wired draws no plate behind it`() {
        host {
            MediaArtFrame(
                title = "Game",
                modifier = Modifier.fillMaxSize(),
                artRef = Pending,
                artContent = { },
            )
        }
        val px = pixels()
        spread.forEach { (x, y) -> assertHost("art wired, frame, ($x,$y)", px.at(x, y)) }
    }

    @Test
    fun `a frame that does not know yet whether art exists draws nothing`() {
        host {
            MediaArtFrame(
                title = "Game",
                modifier = Modifier.fillMaxSize(),
                artRef = null,
                showNoArtCostume = false,
            )
        }
        val px = pixels()
        spread.forEach { (x, y) -> assertHost("unknown, frame, ($x,$y)", px.at(x, y)) }
    }

    @Test
    fun `a frame with no art at all keeps its fallback`() {
        host {
            MediaArtFrame(title = "Game", modifier = Modifier.fillMaxSize(), artRef = null)
        }
        assertNotEquals("no art keeps the plate and glyph", HostMagenta.toArgbInt(), pixels().at(0.5f, 0.5f).toArgbInt())
    }

    @Test
    fun `a frame whose art file is gone takes its fallback and plate together`() {
        // R8a + D3: the frame resolves ONE source state for its plate and its content. A missing file is no art, so the frame draws its
        // no-art fallback instead of handing the dead path to the art slot, which would draw nothing on a bare frame.
        host {
            MediaArtFrame(
                title = "Game",
                modifier = Modifier.fillMaxSize(),
                artRef = MissingFile,
                artContent = { },
            )
        }
        assertNotEquals("a missing file is no art: the fallback", HostMagenta.toArgbInt(), pixels().at(0.5f, 0.5f).toArgbInt())
    }

    // ---- MimirSwitchTileArt (Home's square tile) -----------------------------------------------------

    @Test
    fun `a tile whose art is pending draws nothing, no plate and no glyph`() {
        host {
            MimirSwitchTileArt(
                model = Pending,
                title = "Game",
                focused = false,
                focusRingProgress = { 0f },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val px = pixels()
        spread.forEach { (x, y) -> assertHost("pending tile, ($x,$y)", px.at(x, y)) }
    }

    @Test
    fun `a ready tile paints the art with the host, not a plate, behind its transparent pixels`() {
        host {
            MimirSwitchTileArt(
                model = Ready,
                title = "Game",
                focused = false,
                focusRingProgress = { 0f },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val px = pixels()
        assertEquals("the art's opaque disc", ArtGreen.toArgbInt(), px.at(0.5f, 0.5f).toArgbInt())
        // Transparent in the bitmap, well inside the tile: the host shows, not a surfaceHigh square.
        assertHost("ready tile, transparent ring", px.at(0.18f, 0.5f))
        assertHost("ready tile, transparent ring", px.at(0.5f, 0.15f))
    }

    @Test
    fun `a tile whose art failed to decode draws nothing`() {
        // R8 : wired art that FAILS shows nothing, just the ground. Not the book, not a plate.
        host {
            MimirSwitchTileArt(
                model = Failing,
                title = "Game",
                focused = false,
                focusRingProgress = { 0f },
                modifier = Modifier.fillMaxSize(),
            )
        }
        assertPainterReachedError(Failing)
        val px = pixels()
        assertHost("failed tile, centre", px.at(0.5f, 0.5f))
        spread.forEach { (x, y) -> assertHost("failed tile, ($x,$y)", px.at(x, y)) }
    }

    @Test
    fun `a tile whose custom file no longer exists is unwired and shows the fallback`() {
        // R8a: a pick whose FILE is gone counts as no art wired, so it gets the normal no-art glyph, not the empty ground.
        host {
            MimirSwitchTileArt(
                model = MissingFile,
                title = "Game",
                focused = false,
                focusRingProgress = { 0f },
                modifier = Modifier.fillMaxSize(),
            )
        }
        assertNotEquals("a missing file is unwired: the glyph", HostMagenta.toArgbInt(), pixels().at(0.5f, 0.5f).toArgbInt())
    }

    // ---- MimirLedgeCoverArt (Home's cover on the ledge) ----------------------------------------------

    @Test
    fun `a ledge cover whose art is pending draws nothing, no plate and no glyph`() {
        host {
            MimirLedgeCoverArt(
                model = Pending,
                title = "Game",
                focused = false,
                caretProgress = 0f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val px = pixels()
        spread.forEach { (x, y) -> assertHost("pending ledge cover, ($x,$y)", px.at(x, y)) }
    }

    @Test
    fun `a ready ledge cover paints the art with the host, not a plate, behind its transparent pixels`() {
        host {
            MimirLedgeCoverArt(
                model = Ready,
                title = "Game",
                focused = false,
                caretProgress = 0f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val px = pixels()
        assertEquals("the art's opaque disc", ArtGreen.toArgbInt(), px.at(0.5f, 0.5f).toArgbInt())
        assertHost("ready ledge cover, transparent ring", px.at(0.18f, 0.5f))
        assertHost("ready ledge cover, transparent ring", px.at(0.5f, 0.15f))
    }

    @Test
    fun `a ledge cover with no art at all keeps its fallback`() {
        host {
            MimirLedgeCoverArt(
                model = null,
                title = "Game",
                focused = false,
                caretProgress = 0f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        assertNotEquals("no art keeps the book", HostMagenta.toArgbInt(), pixels().at(0.5f, 0.5f).toArgbInt())
    }

    @Test
    fun `a ledge cover whose art failed to decode draws nothing`() {
        host {
            MimirLedgeCoverArt(
                model = Failing,
                title = "Game",
                focused = false,
                caretProgress = 0f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        assertPainterReachedError(Failing)
        val px = pixels()
        spread.forEach { (x, y) -> assertHost("failed ledge cover, ($x,$y)", px.at(x, y)) }
    }

    @Test
    fun `a ledge cover whose custom file no longer exists is unwired and shows the fallback`() {
        host {
            MimirLedgeCoverArt(
                model = MissingFile,
                title = "Game",
                focused = false,
                caretProgress = 0f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        assertNotEquals("a missing file is unwired: the book", HostMagenta.toArgbInt(), pixels().at(0.5f, 0.5f).toArgbInt())
    }

    // ---- MimirBoxArt (gamelist preview, launch card) -------------------------------------------------

    @Test
    fun `box art that is pending draws nothing`() {
        host {
            MimirBoxArt(model = Pending, title = "Game", focused = false)
        }
        val px = pixels()
        spread.forEach { (x, y) -> assertHost("pending box art, ($x,$y)", px.at(x, y)) }
    }

    @Test
    fun `box art that failed to decode draws nothing`() {
        host {
            MimirBoxArt(model = Failing, title = "Game", focused = false)
        }
        assertPainterReachedError(Failing)
        val px = pixels()
        spread.forEach { (x, y) -> assertHost("failed box art, ($x,$y)", px.at(x, y)) }
    }

    @Test
    fun `box art whose custom file no longer exists is unwired and shows the fallback`() {
        host {
            MimirBoxArt(model = MissingFile, title = "Game", focused = false)
        }
        assertNotEquals("a missing file is unwired: the glyph", HostMagenta.toArgbInt(), pixels().at(0.5f, 0.5f).toArgbInt())
    }

    // ---- the plates are gone from the source, not just from these paths -------------------------------

    private fun coreUiSource(name: String): String {
        val candidates = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun `neither Home art body names the plate colour any more`() {
        val source = coreUiSource("MediaArtFrame.kt")
        val tile = source.substringAfter("private fun MimirSwitchTileArtIdentity(")
            .substringBefore("internal fun mimirLedgeIntrinsicAspects(")
        val ledge = source.substringAfter("private fun MimirLedgeCoverArtIdentity(")
            .substringBefore("private fun BoxScope.MimirShelfmarkSeat(")
        assertTrue("search window missed the tile body", tile.contains("switchTileArtReveal") && tile.length > 800)
        assertTrue("search window missed the ledge body", ledge.contains("ledgeCoverReveal") && ledge.length > 800)
        assertFalse("the tile seats no plate", tile.contains("surfaceHigh"))
        assertFalse("the ledge cover seats no plate", ledge.contains("surfaceHigh"))
        assertFalse("the walk-32i reveal-alpha gate is gone with the plate", source.contains("mimirSwitchTilePlateVisible"))
    }

    private companion object {
        const val Host = "plate-law-host"
        const val Pending = "test://pending"
        const val Ready = "test://ready"
        const val Failing = "test://failing"

        /** An absolute path nothing exists at: a custom pick whose file was removed (R8a). */
        const val MissingFile = "/no/such/dir/custom-cover-art/removed-pick.png"
        val HostMagenta = Color(0xFFFF00FF)
        val ArtGreen = Color(0xFF00C000)
    }
}
