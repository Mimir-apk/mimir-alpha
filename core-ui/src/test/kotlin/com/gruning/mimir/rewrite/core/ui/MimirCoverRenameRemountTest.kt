package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import coil.request.Options
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
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
 * A card's title changes without its art changing, by a rename or by enrichment, and the cover must not be torn down for it (audit 2026-10-14, RC-09: H-04, L-20, the one-frame
 * blink). The painter and the reveal of each cover composable live in a Compose group keyed on the art's identity; that key used to include the title, so a new title REMOUNTED the
 * group: a new painter, a new request, a frame without the art.
 *
 * The proof counts requests. A real Coil loader with a fake source runs every request through a counting interceptor, which sees one request per painter built (a memory-cache hit
 * included). A cover that survives its rename makes exactly one; one that remounts makes two. The harness fails on the old keys by construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirCoverRenameRemountTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class FakeArt(val name: String)

    private val requests = AtomicInteger()
    private var title by mutableStateOf("Chrono Trigger")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build() }
            .components {
                add(
                    object : Interceptor {
                        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                            requests.incrementAndGet()
                            return chain.proceed(chain.request)
                        }
                    },
                )
                add(
                    object : Fetcher.Factory<FakeArt> {
                        override fun create(data: FakeArt, options: Options, imageLoader: ImageLoader): Fetcher =
                            object : Fetcher {
                                override suspend fun fetch(): FetchResult {
                                    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFF0000.toInt()) }
                                    return DrawableResult(BitmapDrawable(context.resources, bitmap), false, DataSource.DISK)
                                }
                            }
                    },
                    FakeArt::class.java,
                )
            }
            .build()
        Coil.setImageLoader(loader)
    }

    @After
    fun tearDown() {
        Coil.reset()
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** Runs frames, letting the loader's own threads work, until [done] or five seconds. */
    private fun stepUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !done()) {
            step(16L)
            Thread.sleep(10)
        }
    }

    private fun checkRenameKeepsThePainter(cover: @Composable (String) -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MimirTheme { Box(Modifier.size(120.dp)) { cover(title) } } }
        step(0L)
        stepUntil { requests.get() >= 1 }
        assertEquals("the cover asked for its art once", 1, requests.get())
        // Let the decode land and the reveal run: the rename must come to a cover that is up, not one still loading.
        repeat(20) { step(16L); Thread.sleep(5) }

        Snapshot.withMutableSnapshot { title = "Chrono Trigger (Director's Cut)" }
        repeat(30) { step(16L); Thread.sleep(5) }
        assertEquals("a rename does not rebuild the painter: still one request", 1, requests.get())
        assertTrue(title.endsWith("Cut)"))
    }

    @Test
    fun `box art keeps its painter when the title changes`() = checkRenameKeepsThePainter { t ->
        MimirBoxArt(model = FakeArt("a"), title = t, focused = false)
    }

    @Test
    fun `the ledge cover keeps its painter and its reveal when the title changes`() = checkRenameKeepsThePainter { t ->
        MimirLedgeCoverArt(model = FakeArt("a"), title = t, focused = false, caretProgress = 0f)
    }

    @Test
    fun `the switch tile keeps its painter when the title changes`() = checkRenameKeepsThePainter { t ->
        MimirSwitchTileArt(model = FakeArt("a"), title = t, focused = false, focusRingProgress = { 0f })
    }
}
