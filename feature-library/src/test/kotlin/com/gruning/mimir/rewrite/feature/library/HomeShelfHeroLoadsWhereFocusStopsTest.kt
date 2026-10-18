package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hero art could remain absent after focus stopped on a tile.
 *
 * The still host gates its request behind a [HomeBackdropSettleMs] dwell on every context change, so a traverse never
 * decodes the tiles it passes over. The tile the host was born on was exempt from that dwell by comparing the epoch
 * against the birth epoch -- and a move back onto it inside the window hit that exemption with the gate still raised
 * and no coroutine left to lower it. The tile Home lands on (cold start, every return from the Library) is the birth
 * tile, so a traverse that comes back to where it began and stops there showed the bare scene for good. Reproduced on
 * emulator-5554 with two D-pad presses 353 ms apart (`build/test-captures/herofix-evidence/23-trap-quick-return-to-birth-tile.png`).
 *
 * The host is driven on the paused clock as the screen drives it: one [HomeShelfStaticBackdrop], its image, epoch and
 * wake signal following the focused tile. What is asserted is the observed failure condition: whether the tile focus
 * stopped on is ever asked of the image loader once it has dwelt there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShelfHeroLoadsWhereFocusStopsTest {

    @get:Rule
    val compose = createComposeRule()

    private val tiles = List(4) { index -> "file:///media_art/tile-$index-hero.png" }
    private val focused = mutableStateOf(tiles[0])
    private val requested = mutableListOf<String>()

    /** A held Retroid D-pad repeats at ~150 ms (walk-32g logcat: NavFocusMove every 150-180 ms). */
    private val heldRepeatMs = 150L

    @Before
    fun recordEveryHeroRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context)
            .components {
                add(
                    Interceptor { chain ->
                        requested += chain.request.data.toString()
                        SuccessResult(
                            drawable = ColorDrawable(Color.WHITE),
                            request = chain.request,
                            dataSource = DataSource.MEMORY,
                        )
                    },
                )
            }
            .build()
        Coil.setImageLoader(loader)
    }

    @After
    fun releaseLoader() = Coil.reset()

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(720.dp, 720.dp)) {
                    val geometry = remember { solveHomeShelfGeometry(720f, 720f) }
                    val uri = focused.value
                    HomeShelfStaticBackdrop(
                        imageUri = uri,
                        geometry = geometry,
                        launching = false,
                        launchReturnRestoring = false,
                        // HomeShelfScreen keys the settle gate on the mount itself, and wakes on the focus state.
                        contextEpoch = HomeBackdropMount.Static(uri).hashCode(),
                        wakeSignal = uri,
                        heroSlotOffset = { 0f },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // The landing tile's hero is fully on screen before the player moves, as it is on the device. Moving a frame
        // after the mount would instead meet the host's "nothing to fade yet" path, which is not the captured case.
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + 100L)
        compose.waitForIdle()
    }

    private fun focus(tile: String, dwellMs: Long) {
        focused.value = tile
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(dwellMs)
        compose.waitForIdle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(HomeBackdropSettleMs + 200L)
        compose.waitForIdle()
    }

    @Test
    fun `a traverse that comes back to the tile it started on and stops there loads that hero`() {
        host()
        assertEquals("the tile Home lands on is requested at once", listOf(tiles[0]), requested)
        // Out three tiles and back, held: the captured traversal shape, ending where it began.
        listOf(tiles[1], tiles[2], tiles[3], tiles[2], tiles[1]).forEach { focus(it, heldRepeatMs) }
        focus(tiles[0], heldRepeatMs)
        assertEquals("nothing passed over is decoded", listOf(tiles[0]), requested)
        settle()
        assertEquals(
            "the tile focus stopped on is requested once it has dwelt there, whichever tile it is",
            listOf(tiles[0], tiles[0]),
            requested,
        )
    }

    @Test
    fun `one step out and straight back inside the settle window loads the hero`() {
        host()
        focus(tiles[1], heldRepeatMs)
        focus(tiles[0], heldRepeatMs)
        settle()
        assertEquals(listOf(tiles[0], tiles[0]), requested)
    }

    @Test
    fun `a traverse that stops on another tile loads that hero and nothing it passed over`() {
        host()
        listOf(tiles[1], tiles[2]).forEach { focus(it, heldRepeatMs) }
        focus(tiles[3], heldRepeatMs)
        assertEquals(listOf(tiles[0]), requested)
        settle()
        assertEquals(listOf(tiles[0], tiles[3]), requested)
    }
}
