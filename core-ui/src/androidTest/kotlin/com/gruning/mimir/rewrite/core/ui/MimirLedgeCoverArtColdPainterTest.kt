package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-Home crash regression: a COLD painter — no memory cache, loader not yet resolved —
 * reports Size.Unspecified from [rememberMimirLedgeArtPainter]; pre-guard, MimirLedgeCoverArt read
 * width/height off that sentinel during its FIRST composition and threw
 * "IllegalStateException: Size is unspecified", killing Home before anything rendered.
 *
 * The gated fetcher makes the cold window deterministic: the request cannot resolve until the test
 * releases it, so the initial composition is guaranteed to see an Unspecified intrinsic size.
 */
@RunWith(AndroidJUnit4::class)
class MimirLedgeCoverArtColdPainterTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun coldIntrinsicResolutionKeepsOuterEnvelopeFixedWhileArtRevealsInFlight() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = CompletableDeferred<Unit>()
        val fetched = CompletableDeferred<Unit>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(GatedFetcherFactory(context, gate, fetched)) }
                .build(),
        )
        compose.mainClock.autoAdvance = false

        // Pre-guard this setContent CRASHES (the P0): the cold painter's intrinsic size is
        // Unspecified during the first composition and the old code read .width/.height off it.
        compose.setContent {
            MimirLedgeCoverArt(
                model = ColdModel,
                title = "Cold Cover",
                focused = false,
                caretProgress = 0f,
                palette = MimirThemePalette.all.first(),
                modifier = Modifier
                    .height(200.dp)
                    .background(Color.Blue)
                    .testTag("ledge"),
            )
        }
        compose.waitForIdle()
        val loadingBounds: DpRect = compose.onNodeWithTag("ledge").getBoundsInRoot()
        // The outer seat is always square. The cold 3:4 glyph is an INNER silhouette and cannot
        // resize the identity-owned body when a non-3:4 intrinsic resolves.
        assertEquals(200f, loadingBounds.right.value - loadingBounds.left.value, 1f)
        assertEquals(200f, loadingBounds.bottom.value - loadingBounds.top.value, 1f)
        val loadingPixels = compose.onNodeWithTag("ledge")
            .captureToImage()
            .toPixelMap()
        // The approved no-art state is now an opaque 3:4 bound book. Sample the exposed OUTER floor,
        // not the centre of that fallback, to lock the neutral silhouette without assuming its artwork.
        val loadingFloorPixel = loadingPixels.outerFloorPixel()
        val loadingArtPixel = loadingPixels.bottomCentrePixel()
        assertTrue(
            "the cold 3:4 silhouette must leave the outer floor blue: $loadingFloorPixel",
            loadingFloorPixel.blue > 0.9f && loadingFloorPixel.red < 0.1f,
        )

        // Release a LANDSCAPE intrinsic. This used to remeasure the tagged body from 3:4 to 7:5 in one
        // composition. The new contract holds the outer seat while inner geometry + pixels interpolate.
        gate.complete(Unit)
        runBlocking { withTimeout(3_000L) { fetched.await() } }
        compose.waitForIdle()
        assertSameBounds(loadingBounds, compose.onNodeWithTag("ledge").getBoundsInRoot())

        compose.mainClock.advanceTimeBy(MimirMotion.FocusMs / 2L)
        compose.waitForIdle()
        val inFlightBounds = compose.onNodeWithTag("ledge").getBoundsInRoot()
        assertSameBounds(loadingBounds, inFlightBounds)
        val inFlightPixel = compose.onNodeWithTag("ledge")
            .captureToImage()
            .toPixelMap()
            .bottomCentrePixel()
        assertTrue(
            "decoded art must be fading over the bound-book fallback by mid-flight: " +
                "before=$loadingArtPixel, during=$inFlightPixel",
            inFlightPixel.red > loadingArtPixel.red + 0.1f &&
                inFlightPixel.red > inFlightPixel.green + 0.1f &&
                inFlightPixel.red > inFlightPixel.blue + 0.1f,
        )

        compose.mainClock.advanceTimeBy(MimirMotion.FocusMs.toLong())
        compose.waitForIdle()

        val revealedBounds = compose.onNodeWithTag("ledge").getBoundsInRoot()
        assertSameBounds(loadingBounds, revealedBounds)
        val revealedPixel = compose.onNodeWithTag("ledge")
            .captureToImage()
            .toPixelMap()
            .bottomCentrePixel()
        assertTrue("resolved art must finish opaque", revealedPixel.red > 0.9f && revealedPixel.blue < 0.1f)
    }

    @Test
    fun focusRingMorphsFromLedgeToSquareInsideOneFixedAnchor() {
        var square by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirLedgeFocusRingShell(
                model = null,
                progress = { 1f },
                square = square,
                palette = MimirThemePalette.all.first(),
                modifier = Modifier
                    .height(200.dp)
                    .background(Color.Black)
                    .testTag("ring"),
            )
        }
        compose.waitForIdle()
        val ledgeBounds = compose.onNodeWithTag("ring").getBoundsInRoot()
        assertEquals(200f, ledgeBounds.right.value - ledgeBounds.left.value, 1f)
        val ledgePixels = compose.onNodeWithTag("ring").captureToImage().toPixelMap()
        val ledgeEdge = ledgePixels.ledgeLeftRingPixel()
        assertTrue(
            "neutral ledge ring must paint its left edge: $ledgeEdge",
            ledgeEdge.rgbEnergy() > 0.1f,
        )

        compose.runOnIdle { square = true }
        compose.waitForIdle()
        assertSameBounds(ledgeBounds, compose.onNodeWithTag("ring").getBoundsInRoot())

        compose.mainClock.advanceTimeBy(MimirMotion.FocusMs / 2L)
        compose.waitForIdle()
        assertSameBounds(ledgeBounds, compose.onNodeWithTag("ring").getBoundsInRoot())

        compose.mainClock.advanceTimeBy(MimirMotion.FocusMs.toLong())
        compose.waitForIdle()
        assertSameBounds(ledgeBounds, compose.onNodeWithTag("ring").getBoundsInRoot())
        val squarePixels = compose.onNodeWithTag("ring").captureToImage().toPixelMap()
        val squareEdge = squarePixels.ledgeLeftRingPixel()
        assertTrue(
            "the old ledge edge must be clear after the ring reaches the square: " +
                "before=$ledgeEdge, after=$squareEdge",
            squareEdge.rgbEnergy() < ledgeEdge.rgbEnergy() * 0.15f,
        )
    }

    private fun assertSameBounds(expected: DpRect, actual: DpRect) {
        assertEquals(expected.left.value, actual.left.value, 0.5f)
        assertEquals(expected.right.value, actual.right.value, 0.5f)
        assertEquals(expected.top.value, actual.top.value, 0.5f)
        assertEquals(expected.bottom.value, actual.bottom.value, 0.5f)
    }

    private fun androidx.compose.ui.graphics.PixelMap.bottomCentrePixel(): Color =
        this[width / 2, height - 10]

    private fun androidx.compose.ui.graphics.PixelMap.outerFloorPixel(): Color =
        // The neutral fallback is 3:4 inside a square envelope, leaving 1/8 of the width per side.
        this[width / 16, height - 10]

    private fun androidx.compose.ui.graphics.PixelMap.ledgeLeftRingPixel(): Color =
        // Neutral inner width is 75% of the square: left = 1/8 of the envelope. The bright ring's
        // innermost stroke is centred 2dp outside that edge; 2dp = 1/100 of this 200dp fixture.
        this[((width * 0.115f).toInt()), height / 2]

    private fun Color.rgbEnergy(): Float = red + green + blue

    private class GatedFetcherFactory(
        private val context: Context,
        private val gate: CompletableDeferred<Unit>,
        private val fetched: CompletableDeferred<Unit>,
    ) : Fetcher.Factory<ColdModel> {
        override fun create(data: ColdModel, options: Options, imageLoader: ImageLoader): Fetcher {
            return Fetcher {
                gate.await()
                val bitmap = Bitmap.createBitmap(140, 100, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.RED)
                }
                val result: FetchResult = DrawableResult(
                    drawable = BitmapDrawable(context.resources, bitmap),
                    isSampled = false,
                    dataSource = DataSource.MEMORY,
                )
                fetched.complete(Unit)
                result
            }
        }
    }

    // A String is mapped to Uri by Coil before fetcher lookup, so a Factory<String> never owns the
    // deterministic gate. A dedicated model type reaches this factory directly on every device.
    private object ColdModel
}
