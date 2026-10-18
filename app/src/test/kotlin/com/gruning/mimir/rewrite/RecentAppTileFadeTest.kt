package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The recent-app tile on the Activity page steps aside on alpha while a launch card or a picker is over the page, instead of unmounting (audit B-50, RC-35): it neither pops out
 * the frame they open nor pops back the frame they close. It stands on a white ground and each stepped frame is rasterised; the pixel inside the tile's left edge says how much of
 * it shows. The frame after the cover goes up still has the tile; it falls to nothing by [MimirMotion.OverlayMs] with the tile still composed (so its icon is not decoded again),
 * inert to touch while it is faded; and it rises the same way when the cover goes. Pixels are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecentAppTileFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private var overlay by mutableStateOf(true)
    private var tile: Rect = Rect.Zero
    private val chrome = CompanionAppEntry(packageName = "com.android.chrome", label = "Chrome")

    private fun write(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).background(Color.White)) {
                        CompanionWorkspaceSurface(
                            page = CompanionWorkspacePage.ACTIVITY,
                            navigationEnabled = true,
                            recentApp = chrome,
                            game = null,
                            media = CompanionMediaUiState.NoActiveMedia,
                            onRecentApp = {},
                            onReturnToGame = null,
                            onMediaAccess = {},
                            onMediaAction = { _, _, _ -> },
                            bento = EmptyActivityBentoData,
                            showRecentAppOverlay = overlay,
                        )
                    }
                }
            }
        }
        step(0L)
        step(300L)
    }

    private fun nodes() = compose.onAllNodesWithTag(ActivityRecentAppTileTag, useUnmergedTree = true).fetchSemanticsNodes()

    private fun gray(): Float {
        nodes().firstOrNull()?.let { tile = it.boundsInWindow }
        return compose.activity.shootWindow().gray((tile.left + 5f * density369).toInt(), tile.center.y.toInt())
    }

    @Test
    fun `the tile fades out under a cover and back after it, composed throughout, and is inert while it is faded`() {
        mount()
        assertTrue("the tile is up", nodes().isNotEmpty())
        val whole = gray()
        assertTrue("the tile is darker than the white ground (whole=$whole)", 255f - whole > 40f)
        assertTrue("and it takes a tap while it is whole", nodes().first().config.contains(SemanticsActions.OnClick))

        write { overlay = false }
        val out = ArrayList<Float>()
        var t = 0L
        while (t <= MimirMotion.OverlayMs + 64L) {
            step(16L)
            t += 16L
            out += presenceOverWhite(gray(), whole)
            assertTrue("composed in frame ${t / 16}: it is not unmounted", nodes().isNotEmpty())
            assertFalse("inert from the first frame of the cover (frame ${t / 16})", nodes().first().config.contains(SemanticsActions.OnClick))
        }
        println("RECENT-TILE-OUT presence per 16 ms frame: ${out.joinToString { "%.2f".format(it) }}")
        assertTrue("the first frame after the cover goes up still has the tile: ${out.first()}", out.first() > 0.85f)
        assertTrue("it falls and never rises: $out", out.zipWithNext().all { (a, b) -> b <= a + 0.03f })
        assertTrue("through a real fade: $out", out.count { it in 0.1f..0.9f } >= 3)
        val gone = out.indexOfFirst { it < 0.03f }
        assertTrue("it is gone: $out", gone >= 0)
        assertTrue("by the overlay clock (frame $gone)", (gone + 1) * 16L <= MimirMotion.OverlayMs + 32L)

        write { overlay = true }
        val back = ArrayList<Float>()
        t = 0L
        while (t <= MimirMotion.OverlayMs + 64L) {
            step(16L)
            t += 16L
            back += presenceOverWhite(gray(), whole)
        }
        println("RECENT-TILE-BACK presence per 16 ms frame: ${back.joinToString { "%.2f".format(it) }}")
        assertTrue("the first frame after the cover goes is not the whole tile: ${back.first()}", back.first() < 0.5f)
        assertTrue("it rises and never falls: $back", back.zipWithNext().all { (a, b) -> b >= a - 0.03f })
        assertTrue("through a real fade: $back", back.count { it in 0.1f..0.9f } >= 3)
        assertTrue("whole again at the end: ${back.last()}", back.last() > 0.95f)
        assertTrue("and takes a tap again", nodes().first().config.contains(SemanticsActions.OnClick))
    }

    @Test
    fun `a surface that never shows the tile composes none`() {
        write { overlay = false }
        mount()
        assertTrue("no tile composed for the page's own copy", nodes().isEmpty())
    }
}
