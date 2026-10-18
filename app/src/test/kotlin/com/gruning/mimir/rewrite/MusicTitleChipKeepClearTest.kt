package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.gruning.mimir.rewrite.core.ui.LocalMimirGlanceClearance
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirGlanceClearance
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirGlanceKeepClear
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.io.File
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
 * Sweep 10-09 (F4): the song-title chip covered the gamelist's SEARCH GAMES pill whenever a track began. The glance row ([MusicTitleGlanceRow]: the chip, then the status pill) at Main's
 * 832.8 x 468.3 dp and 369 dpi, with a stand-in for the Search pill standing where the SNES list puts it (right edge near 500 dp) and registered the way the real pill is
 * ([mimirGlanceKeepClear]; the real one's registration is pinned in feature-library's `GamelistSearchPillFitTest`). A long title now ends in an ellipsis before the pill, at font 1.0 and
 * 1.3; with no control to keep clear of the chip is as it was. PNGs land in `build/test-captures/sweep28b/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicTitleChipKeepClearTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rootDensity = 369f / 160f
    private val longTitle = "Shared Title With A Very Long Name That Cannot Fit Beside The Pill"

    /** [pillRightDp] is where the stand-in's right edge stands: ~500 dp is the SNES gamelist's Search pill; null leaves no control to keep clear of. */
    private fun mount(fontScale: Float, pillRightDp: Float?, withClearance: Boolean = true, title: String = longTitle): MusicTitleChipState {
        val state = MusicTitleChipState()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(rootDensity, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(832.8f, 468.3f),
                LocalMimirGlanceClearance provides if (withClearance) MimirGlanceClearance() else null,
            ) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(832.8.dp, 468.3.dp).background(palette.surfaceBase)) {
                        if (pillRightDp != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = (pillRightDp - 134f).dp, top = 12.dp)
                                    .size(134.dp, 44.dp)
                                    .testTag(PillTag)
                                    .mimirGlanceKeepClear("test-search-pill")
                                    .mimirEtchedChipSurface(palette, warmed = false, focused = false)
                                    .clip(RoundedCornerShape(MimirSpacing.radiusPill)),
                                contentAlignment = Alignment.Center,
                            ) {
                                MimirChromeText("SEARCH GAMES", style = MimirTypeScale.Pill, color = palette.textSecondary, maxLines = 1)
                            }
                        }
                        MusicTitleGlanceRow(Modifier.align(Alignment.TopEnd).padding(16.dp), chipState = state)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        state.show(title, "Music on this device")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + 64L)
        return state
    }

    private fun chipBounds() = compose.onNode(hasText("\u266a", substring = true), useUnmergedTree = true)

    private fun save(fileName: String, dirName: String = "build/test-captures/sweep28b") {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val top = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, (bitmap.height * 0.2f).toInt())
        val dir = File(dirName).apply { if (!isDirectory) mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { top.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("CHIPKEEP wrote ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun assertChipStaysRightOfThePill(fontScale: Float, fileName: String) {
        mount(fontScale, pillRightDp = 500f)
        val chip = chipBounds().getUnclippedBoundsInRoot()
        val pill = compose.onNodeWithTag(PillTag).getUnclippedBoundsInRoot()
        println("CHIPKEEP font $fontScale: chip ${chip.left}..${chip.right}, pill ${pill.left}..${pill.right}")
        assertTrue("the chip starts right of the pill's edge, with a gap: chip ${chip.left}, pill ${pill.right}", chip.left.value >= pill.right.value + MimirSpacing.sm.value - 0.6f)
        assertTrue("the chip is still there, with room to read: ${chip.right.value - chip.left.value} dp", chip.right.value - chip.left.value >= MusicTitleChipMinRoom.value)
        save(fileName)
    }

    @Test
    fun `font 1 - a long title ends before the Search pill and not over it`() {
        assertChipStaysRightOfThePill(1f, "f4-chip-1.0.png")
    }

    @Test
    fun `font 1_3 - the same at the larger type`() {
        assertChipStaysRightOfThePill(1.3f, "f4-chip-1.3.png")
    }

    /** Sweep 10-09 (f4): the chip said "♪ Tiny 190 · Music on this device" and ended in an ellipsis. It names the song and nothing after it. */
    private fun assertTitleOnly(fontScale: Float, fileName: String) {
        mount(fontScale, pillRightDp = 500f, title = "Tiny 190")
        val chip = chipBounds()
        chip.assertTextEquals("♪ Tiny 190")
        val box = chip.getUnclippedBoundsInRoot()
        println("CHIPKEEP title only, font $fontScale: chip ${box.left}..${box.right}")
        assertTrue("the chip names the song and nothing after it", compose.onAllNodes(hasText("Music on this device", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        save(fileName, "build/test-captures/fix29b")
    }

    @Test
    fun `font 1 - a short title stands whole, with no source after it`() = assertTitleOnly(1f, "h2-chip-title-only-1.0.png")

    @Test
    fun `font 1_3 - a short title stands whole, with no source after it`() = assertTitleOnly(1.3f, "h2-chip-title-only-1.3.png")

    @Test
    fun `with no control to keep clear of the chip is as it was - it reaches back over the same span`() {
        mount(1f, pillRightDp = 500f, withClearance = false)
        val chip = chipBounds().getUnclippedBoundsInRoot()
        val pill = compose.onNodeWithTag(PillTag).getUnclippedBoundsInRoot()
        println("CHIPKEEP before: chip ${chip.left}..${chip.right}, pill ${pill.left}..${pill.right}")
        assertTrue("without the registry the chip covers the pill, which is the defect", chip.left.value < pill.right.value)
        save("f4-chip-before-1.0.png")
    }

    @Test
    fun `a pill that leaves hardly any room takes the chip away rather than showing a stub`() {
        mount(1f, pillRightDp = 640f)
        val shown = runCatching { chipBounds().getUnclippedBoundsInRoot() }.getOrNull()
        println("CHIPKEEP stub case: $shown")
        assertTrue("under ${MusicTitleChipMinRoom.value} dp of room there is no chip", shown == null || !shown.left.isSpecified || shown.right.value - shown.left.value < 1f)
        save("f4-chip-noroom-1.0.png")
    }

    @Test
    fun `the room is from the control's edge to the pill, with a gap at each end, and none when nothing is registered`() {
        assertEquals(null, musicTitleChipRoomPx(pillLeftPx = 1_000f, clearRightPx = 0f, gapPx = 18f))
        assertEquals(1_000 - 18 - (400 + 18), musicTitleChipRoomPx(pillLeftPx = 1_000f, clearRightPx = 400f, gapPx = 18f))
        assertEquals("never negative", 0, musicTitleChipRoomPx(pillLeftPx = 300f, clearRightPx = 400f, gapPx = 18f))
        assertFalse(MusicTitleChipMinRoom.value <= 0f)
    }

    private companion object {
        const val PillTag = "stand-in-search-pill"
    }
}
