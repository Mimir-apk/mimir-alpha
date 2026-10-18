package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.compose.AsyncImagePainter
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.SuccessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Policy Q13 (2026-10-14): art that arrives COLD fades in from nothing on FocusMs with the house easing, with no placeholder; art that is warm when its site is born
 * is whole on the first frame, so only a truly cold first show fades. [rememberMimirColdArtReveal] is that rule as one primitive, read in the layer. The proof is the alpha
 * frame by frame at 16 ms, with the clock paused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirColdArtRevealTest {

    @get:Rule
    val compose = createComposeRule()

    private var identity by mutableStateOf<Any?>("a")
    private var warm by mutableStateOf(false)
    private var ready by mutableStateOf(false)
    private var reduced by mutableStateOf(false)
    private var bodies = 0
    private var reveal: (() -> Float)? = null

    private val alpha: Float get() = reveal!!()

    @Composable
    private fun Host() {
        val held = rememberMimirColdArtReveal(identity = identity, warm = warm, ready = ready, reducedMotion = reduced)
        reveal = held
        bodies += 1
        Box(Modifier.size(10.dp).graphicsLayer { this.alpha = held() })
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Host() }
        step(0L)
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun write(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    private fun frames(count: Int): List<Float> = List(count) { step(16L); alpha }

    @Test
    fun `cold art draws nothing while it loads, however long that takes`() {
        mount()
        assertEquals("nothing on the first frame: no placeholder", 0f, alpha, 0f)
        val waiting = frames(30)
        assertTrue("and nothing while it loads: $waiting", waiting.all { it == 0f })
    }

    @Test
    fun `cold art that lands rises from nothing to whole over FocusMs on the house easing`() {
        mount()
        frames(3)
        write { ready = true }
        step(0L)
        val rising = frames(MimirMotion.FocusMs / 16 + 3)
        println("COLD-ART-REVEAL alpha per 16 ms frame: $rising")
        assertTrue("it starts at nothing, not at whole: ${rising.first()}", rising.first() < 0.5f)
        assertTrue("it is mid-fade partway through: $rising", rising.any { it > 0.05f && it < 0.95f })
        assertTrue("it never goes backwards: $rising", rising.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("and it is whole after FocusMs: ${rising.last()}", 1f, rising.last(), 0.001f)
        // The clock is FocusMs: well short of it the art is not yet whole.
        recycledOntoOtherArt()
    }

    /** The same fade, timed in one step from a fresh host: 70 ms in it is part way, and it is whole by 160 ms. */
    private fun recycledOntoOtherArt() {
        write { identity = "b"; ready = false }
        step(16L)
        assertEquals("a site recycled onto other art starts again from nothing", 0f, alpha, 0f)
        write { ready = true }
        step(16L)
        step(64L)
        assertTrue("70 ms in it is neither nothing nor whole: $alpha", alpha > 0.02f && alpha < 0.98f)
        step(MimirMotion.FocusMs.toLong())
        assertEquals(1f, alpha, 0.001f)
    }

    @Test
    fun `warm art is whole on the first frame and never fades`() {
        write { warm = true }
        mount()
        assertEquals("whole at birth", 1f, alpha, 0f)
        write { ready = true }
        val after = frames(12)
        assertTrue("and it stays whole when the load reports in: $after", after.all { it == 1f })
    }

    @Test
    fun `reduced motion snaps a cold arrival in`() {
        write { reduced = true }
        mount()
        assertEquals(1f, alpha, 0f)
        write { ready = true }
        assertTrue(frames(3).all { it == 1f })
    }

    @Test
    fun `the fade is read in the layer, so a whole fade recomposes the host at most a couple of times`() {
        mount()
        val before = bodies
        write { ready = true }
        frames(MimirMotion.FocusMs / 16 + 3)
        val recomposed = bodies - before
        println("COLD-ART-REVEAL host bodies run for a whole fade: $recomposed")
        assertTrue("the host recomposed $recomposed times for the whole fade, not once per frame", recomposed <= 3)
    }

    // ---- the one rule's other pieces: the animation spec, and "ready" meaning ready FOR THIS model --------------------------------------------------------------------------------

    private var model by mutableStateOf<Any?>("a")

    /** The data the site's request is built with. Usually the model itself; a site may hand Coil another type than the identity it was given (a Uri model, a String request). */
    private var requestData by mutableStateOf<Any>("a")
    private var painterState by mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)

    @Composable
    private fun StateHost() {
        val request = remember(requestData) { requestFor(requestData) }
        val held = rememberMimirColdArtReveal(identity = model, warm = false, state = painterState, request = request, reducedMotion = false)
        reveal = held
        Box(Modifier.size(10.dp).graphicsLayer { this.alpha = held() })
    }

    private fun requestFor(data: Any): ImageRequest = ImageRequest.Builder(ApplicationProvider.getApplicationContext<Context>()).data(data).build()

    /** A finished image for [forModel], the way Coil reports one: the state carries the request it answered (a rebuilt copy of the site's, as the painter makes). */
    private fun landed(forModel: Any): AsyncImagePainter.State.Success =
        AsyncImagePainter.State.Success(ColorPainter(Color.Red), SuccessResult(ColorDrawable(0), requestFor(forModel).newBuilder().build(), DataSource.MEMORY))

    @Test
    fun `the one spec is FocusMs on the house easing, and a first show is born whole only when warm or reduced`() {
        assertEquals(MimirMotion.FocusMs, MimirColdArtRevealSpec.durationMillis)
        assertEquals(0, MimirColdArtRevealSpec.delay)
        assertEquals(MimirMotion.Easing, MimirColdArtRevealSpec.easing)
        assertEquals("a cold arrival is born at nothing", 0f, mimirColdArtStartAlpha(warm = false, reducedMotion = false), 0f)
        assertEquals("warm is born whole", 1f, mimirColdArtStartAlpha(warm = true, reducedMotion = false), 0f)
        assertEquals("reduced motion is born whole", 1f, mimirColdArtStartAlpha(warm = false, reducedMotion = true), 0f)
    }

    @Test
    fun `a state is landed only for the model its request was for`() {
        assertTrue("a different request object for the same data is the same request", mimirArtLanded(landed("a"), requestFor("a")))
        assertFalse("the previous request's Success is not the new model's", mimirArtLanded(landed("a"), requestFor("b")))
        assertFalse(mimirArtLanded(AsyncImagePainter.State.Empty, requestFor("a")))
        assertFalse(mimirArtLanded(AsyncImagePainter.State.Loading(null), requestFor("a")))
        assertFalse("no request, nothing to have landed for", mimirArtLanded(landed("a"), null))
    }

    @Test
    fun `a stale Success from the previous request never reveals the new art`() {
        write { painterState = landed("a") }
        compose.mainClock.autoAdvance = false
        compose.setContent { StateHost() }
        step(0L)
        val first = frames(MimirMotion.FocusMs / 16 + 3)
        assertEquals("the first art is whole: $first", 1f, first.last(), 0.001f)

        // The model moves on; the painter still holds a's Success until b's request starts, so a bare `state is Success` would be true here.
        write { model = "b"; requestData = "b" }
        step(16L)
        assertEquals("the new art starts from nothing", 0f, alpha, 0f)
        val stale = frames(30)
        println("COLD-ART-REVEAL stale-Success alpha per 16 ms: $stale")
        assertTrue("the old art's Success never reveals the new art: $stale", stale.all { it == 0f })

        write { painterState = landed("b") }
        step(16L)
        val rising = frames(MimirMotion.FocusMs / 16 + 3)
        assertTrue("it rises once b has landed: $rising", rising.any { it > 0.05f && it < 0.95f })
        assertEquals("and is whole by FocusMs: ${rising.last()}", 1f, rising.last(), 0.001f)
    }

    @Test
    fun `a Uri model against a String request still reveals, because readiness is the request's own data`() {
        // A site is handed a Uri as the art's identity and builds Coil's request from its string form. A bare `request.data == model` is never true for that pair, and
        // the art stayed hidden forever. Readiness compares the state's request with the site's remembered request, so the pair reveals.
        val uri = android.net.Uri.parse("content://mimir.test/art/1")
        write { model = uri; requestData = uri.toString(); painterState = AsyncImagePainter.State.Empty }
        compose.mainClock.autoAdvance = false
        compose.setContent { StateHost() }
        step(0L)
        assertTrue("nothing before it lands", frames(3).all { it == 0f })
        write { painterState = landed(uri.toString()) }
        step(16L)
        val rising = frames(MimirMotion.FocusMs / 16 + 3)
        println("COLD-ART-REVEAL Uri model / String request alpha per 16 ms: $rising")
        assertTrue("it rises, it is not hidden forever: $rising", rising.any { it > 0.05f && it < 0.95f })
        assertEquals("and is whole by FocusMs: ${rising.last()}", 1f, rising.last(), 0.001f)
    }
}
