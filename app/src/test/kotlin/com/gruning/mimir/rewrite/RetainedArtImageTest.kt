package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.Options
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirCoverCacheKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Customize-media painters keep the last good image and paint a known one on their first frame (audit 2026-10-14, RC-28: H-08c, H-09c,
 * H-09d, L-08). A REAL Coil loader with a fake source: each art decodes to one solid colour, over an opaque black ground, and a gate holds a
 * decode open so the frames in between can be read. Frames are the JVM's software draw of the window; the handheld's are device-owed.
 *
 *  - a model the cache holds paints on frame 0 (the shared cover request's placeholder key), with nothing decoded, and is never faded;
 *  - a COLD first show (Q13) draws nothing on frame 0 -- no placeholder -- and when the decode lands the art fades in
 *    from nothing, reaching full strength by FocusMs, never through a full-strength frame first;
 *  - moving to a model whose decode is still running leaves the previous image on the stage -- no frame is blank -- and the new one
 *    dissolves in over it on FocusMs, so the frames between are blends, ending whole;
 *  - a model that arrives while the one before is still dissolving stacks on it, and nothing steps back;
 *  - no model dissolves the art out on FocusMs (never a cut in one frame) and forgets it, so the next art does not dissolve in over a candidate that had no part in it, and a model that
 *    arrives mid-fade is not cleared with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetainedArtImageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class FakeArt(val name: String, val color: Int)

    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()

    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val fetches = AtomicInteger()
    private val failing = ConcurrentHashMap.newKeySet<String>()
    private lateinit var context: Context
    private lateinit var loader: ImageLoader

    private fun solid(color: Int): Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        loader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build() }
            .components {
                add(
                    object : Fetcher.Factory<FakeArt> {
                        override fun create(data: FakeArt, options: Options, imageLoader: ImageLoader): Fetcher =
                            object : Fetcher {
                                override suspend fun fetch(): FetchResult {
                                    fetches.incrementAndGet()
                                    gates[data.name]?.await()
                                    if (data.name in failing) error("unreadable")
                                    return DrawableResult(BitmapDrawable(context.resources, solid(data.color)), false, DataSource.DISK)
                                }
                            }
                    },
                    FakeArt::class.java,
                )
            }
            .build()
    }

    /**
     * The state after a decode: the art in Coil's memory cache under the shared cover key. Written directly -- a `loader.execute` blocked on
     * the main thread never returns, because the fetch resumes on the interceptor's main dispatcher, which that thread is not serving.
     */
    private fun warm(art: FakeArt) {
        loader.memoryCache!![MemoryCache.Key(mimirCoverCacheKey(art))] = MemoryCache.Value(solid(art.color))
    }

    private fun mount(model: () -> FakeArt?, alpha: Float = 1f, onError: (() -> Unit)? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    RetainedArtImage(
                        model = model(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                        alpha = alpha,
                        imageLoader = loader,
                        onError = onError,
                    )
                }
            }
        }
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** The window's centre pixel, drawn in software. */
    private fun centre(): Int {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap.getPixel(view.width / 2, view.height / 2)
    }

    private fun redOf(pixel: Int) = (pixel shr 16) and 0xFF
    private fun greenOf(pixel: Int) = (pixel shr 8) and 0xFF
    private fun blueOf(pixel: Int) = pixel and 0xFF

    /** Steps frames until the stage wears [predicate] or the decode thread has had its five seconds. */
    private fun stepUntil(predicate: (Int) -> Boolean): List<Int> {
        val frames = mutableListOf<Int>()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            step(16L)
            frames += centre()
            if (predicate(frames.last())) return frames
            Thread.sleep(10)
        }
        return frames
    }

    private fun hex(pixels: List<Int>) = pixels.map { Integer.toHexString(it) }

    @Test
    fun `a model the cache holds paints on the first frame, whole, and decodes nothing`() {
        val a = FakeArt("a", red)
        warm(a)
        val before = fetches.get()
        mount({ a })
        step(0L)
        assertEquals("frame 0 wears the cached art, whole", red, centre())
        assertEquals("nothing was fetched for it", before, fetches.get())
        repeat(12) {
            step(16L)
            assertEquals("a warm show is never faded: every later frame is whole too", red, centre())
        }
        assertEquals(before, fetches.get())
    }

    @Test
    fun `a cold first show draws nothing, then fades in from nothing and is whole by FocusMs`() {
        val a = FakeArt("a", red)
        val gate = CompletableDeferred<Unit>().also { gates["a"] = it }
        mount({ a })
        step(0L)
        assertEquals("frame 0 of a cold load draws nothing: no placeholder", black, centre())
        repeat(3) {
            step(16L)
            assertEquals("and nothing while the decode is out", black, centre())
        }
        gate.complete(Unit)

        val frames = stepUntil { it == red }
        val reds = frames.map(::redOf)
        assertEquals("whole at the end: ${hex(frames)}", red, frames.last())
        assertTrue("it never wore anything but the art over the ground: ${hex(frames)}", frames.all { greenOf(it) == 0 && blueOf(it) == 0 })
        val first = reds.indexOfFirst { it > 0 }
        assertTrue("the art appeared: $reds", first >= 0)
        assertTrue("it starts from nothing, not at full strength: $reds", reds[first] < 128)
        assertEquals("the fade only ever rises: $reds", reds, reds.take(first) + reds.drop(first).sorted())
        assertTrue("it passes through intermediate strengths, a fade and not a cut: $reds", reds.count { it in 1..254 } >= 3)
        val toWhole = (reds.size - 1 - first) * 16
        assertTrue("whole within FocusMs (${MimirMotion.FocusMs} ms) of the first visible frame: $toWhole ms, $reds", toWhole <= MimirMotion.FocusMs + 16)
    }

    @Test
    fun `the previous image stays while the next decodes, then dissolves into it`() {
        val a = FakeArt("a", red)
        val b = FakeArt("b", blue)
        warm(a)
        val gate = CompletableDeferred<Unit>().also { gates["b"] = it }
        var model by mutableStateOf(a)
        mount({ model })
        step(0L)
        assertEquals(red, centre())

        Snapshot.withMutableSnapshot { model = b }
        val held = mutableListOf<Int>()
        repeat(6) {
            step(16L)
            held += centre()
        }
        assertTrue("no frame is blank while b decodes: ${hex(held)}", held.none { it == black })
        assertTrue("and every one still wears a: ${hex(held)}", held.all { it == red })

        gate.complete(Unit)
        val frames = stepUntil { it == blue }
        assertEquals("b is whole at the end (fetches=${fetches.get()}): ${hex(frames)}", blue, frames.last())
        assertTrue("no frame in between is blank: ${hex(frames)}", frames.none { it == black })
        val blends = frames.filter { blueOf(it) in 1..254 }
        assertTrue("the frames between are blends of a and b, not a cut: ${hex(frames)}", blends.isNotEmpty())
        val blueChannel = frames.map(::blueOf)
        assertEquals("the blend only ever moves toward b", blueChannel, blueChannel.sorted())
    }

    @Test
    fun `a model that arrives while the one before is dissolving stacks on it, and nothing steps back`() {
        val a = FakeArt("a", red)
        val b = FakeArt("b", blue)
        val c = FakeArt("c", green)
        warm(a)
        warm(b)
        warm(c)
        var model by mutableStateOf(a)
        mount({ model })
        step(0L)

        Snapshot.withMutableSnapshot { model = b }
        val toB = stepUntil { blueOf(it) > 0 }
        assertTrue("b began to dissolve in: ${hex(toB)}", blueOf(toB.last()) > 0)
        step(16L)
        step(16L)
        val midway = centre()
        assertTrue("b is part-way in: ${Integer.toHexString(midway)}", blueOf(midway) in 1..254)
        Snapshot.withMutableSnapshot { model = c }
        val frames = stepUntil { it == green }
        assertEquals("c is whole at the end: ${hex(frames)}", green, frames.last())
        assertTrue("no frame is blank: ${hex(frames)}", frames.none { it == black })
    }

    @Test
    fun `no model fades the art out on FocusMs and forgets it, and nothing is cleared in one frame`() {
        val a = FakeArt("a", red)
        val c = FakeArt("c", green)
        warm(a)
        warm(c)
        var model by mutableStateOf<FakeArt?>(a)
        mount({ model })
        step(0L)
        assertEquals(red, centre())

        Snapshot.withMutableSnapshot { model = null }
        val leaving = mutableListOf<Int>()
        repeat(MimirMotion.FocusMs / 16 + 4) {
            step(16L)
            leaving += centre()
        }
        val reds = leaving.map(::redOf)
        println("RETAINED-ART null-model fade, red per 16 ms: $reds")
        assertTrue("the art dissolves out: it passes through intermediate strengths, a fade and not a cut: $reds", reds.count { it in 1..254 } >= 3)
        assertTrue("it only ever fades: $reds", reds.zipWithNext().all { (x, y) -> y <= x })
        assertTrue("no single frame drops from whole to nothing: $reds", reds.zipWithNext().all { (x, y) -> x - y < 200 })
        assertEquals("a candidate with no art shows no art once it has gone: ${hex(leaving)}", black, leaving.last())

        Snapshot.withMutableSnapshot { model = c }
        val frames = stepUntil { it == green }
        assertEquals("c is on the stage whole: ${hex(frames)}", green, frames.last())
        assertTrue("and nothing of a came back with it: ${hex(frames)}", frames.none { redOf(it) > 0 })
    }

    @Test
    fun `a model that arrives while the art is fading out is not cleared with it`() {
        val a = FakeArt("a", red)
        val c = FakeArt("c", green)
        warm(a)
        warm(c)
        var model by mutableStateOf<FakeArt?>(a)
        mount({ model })
        step(0L)

        Snapshot.withMutableSnapshot { model = null }
        repeat(3) { step(16L) }
        assertTrue("part way out", redOf(centre()) in 1..254)

        Snapshot.withMutableSnapshot { model = c }
        val frames = stepUntil { it == green }
        assertEquals("c is whole at the end: ${hex(frames)}", green, frames.last())
        // The fade out of a finishes (or is cut short by c covering it) and neither takes c with it.
        repeat(MimirMotion.FocusMs / 16 + 4) {
            step(16L)
            assertEquals("c stays up after a's fade-out has run its course", green, centre())
        }
    }

    @Test
    fun `the same model back while its art is fading out rises from where it is, with no dip`() {
        // A fade out and a fade in of the SAME image at once composite to less than whole at the crossing, 1 - (1 - a)(1 - b): a new layer over its own leaving copy dips
        // (audit W2A the earlier update). The leaving layer is brought back instead, so from the frame the art lands again it only rises from the level it was at.
        val a = FakeArt("a", red)
        warm(a)
        var model by mutableStateOf<FakeArt?>(a)
        mount({ model })
        step(0L)
        assertEquals(red, centre())
        val frameCount = MimirMotion.FocusMs / 16 + 10

        // Control: the art fading out on its own, nothing coming back.
        Snapshot.withMutableSnapshot { model = null }
        val plain = List(frameCount) { step(16L); redOf(centre()) }
        assertEquals("the control fades out completely: $plain", 0, plain.last())
        Snapshot.withMutableSnapshot { model = a }
        val shown = stepUntil { it == red }
        assertEquals("and a is whole again: ${hex(shown)}", red, shown.last())
        // Whole is not yet at rest: the layer that just reached it prunes the one beneath on the frame after.
        repeat(3) { step(16L) }

        // The sequence: out for three frames, then the same model again.
        Snapshot.withMutableSnapshot { model = null }
        val back = mutableListOf<Int>()
        repeat(3) { step(16L); back += redOf(centre()) }
        assertEquals("the first frames are the plain fade-out: $back vs $plain", plain.take(3), back)
        Snapshot.withMutableSnapshot { model = a }
        repeat(frameCount - 3) { step(16L); back += redOf(centre()) }
        println("RETAINED-ART null-then-same-model, red per 16 ms: $back (plain fade-out $plain)")

        // The request takes some frames to land again, and the art keeps fading out meanwhile (the same frames as the control). The first frame it is above the control
        // is the first frame the returning art shows; from the level just before it, nothing may fall.
        val departs = back.indices.firstOrNull { back[it] > plain[it] }
        assertTrue("the returning art leaves the plain fade-out: $back vs $plain", departs != null && departs > 0)
        val level = back[departs!! - 1]
        val after = back.drop(departs - 1)
        assertTrue("no dip below the level it came back at ($level): $back", after.all { it >= level })
        assertTrue("and it only rises from there: $after", after.zipWithNext().all { (x, y) -> y >= x })
        assertEquals("the art is whole again at the end: $back", 255, back.last())
    }

    @Test
    fun `an unreadable image reports once and leaves the previous one up`() {
        val a = FakeArt("a", red)
        val bad = FakeArt("bad", blue)
        warm(a)
        failing += "bad"
        val errors = AtomicInteger()
        var model by mutableStateOf(a)
        mount({ model }, onError = { errors.incrementAndGet() })
        step(0L)

        Snapshot.withMutableSnapshot { model = bad }
        val deadline = System.currentTimeMillis() + 5_000
        while (errors.get() == 0 && System.currentTimeMillis() < deadline) {
            step(16L)
            Thread.sleep(10)
        }
        assertEquals(1, errors.get())
        assertEquals("the stage still wears a", red, centre())
    }

    @Test
    fun `the art is drawn at the alpha it was asked for`() {
        val a = FakeArt("a", red)
        warm(a)
        mount({ a }, alpha = 0.5f)
        step(0L)
        step(16L)
        val pixel = centre()
        assertTrue("half strength over black: ${Integer.toHexString(pixel)}", redOf(pixel) in 120..135)
    }
}
