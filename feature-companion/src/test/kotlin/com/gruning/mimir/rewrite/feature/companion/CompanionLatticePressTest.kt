package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPressScale
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * a PRODUCTION lattice tile has no tap node (`onTap = null`), so its press response is driven from the
 * drag root's detector through the state machine. This composes the REAL [CompanionSurface] at the Thor Bottom's size (the same tiles, the same `onTap = null`, the same
 * drag root) and proves the dip from real touches on a paused clock: a held tile is drawn in to [MimirPressScale]; a tap lets go (and activates); a press the pager takes
 * is released while the finger is still down.
 *
 * The scale is read from where the tile's own icon says it is (`CompanionLatticeIconTag`'s bounds in the root, which a layer's scale moves) rather than from pixels,
 * because the whole surface cannot be rasterised under Robolectric (its glass backdrop draws a render node a software canvas refuses). The lift is the state machine's
 * and `CompanionTilePressTest` pins its Cancel on the real reducer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h445dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionLatticePressTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1025f / Density369

    private val iconFile: File = File.createTempFile("b5-lattice-icon", ".png").also { file ->
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(0xFFFF00FF.toInt())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        companionAppIconCache.put(file.path, bitmap.asImageBitmap())
    }

    @After
    fun tearDown() {
        companionAppIconCache.remove(iconFile.path)
        iconFile.delete()
    }

    private val appTile = CompanionHomeTile("app.alpha", CompanionHomeTileKind.ANDROID_APP, "Alpha", iconPath = iconFile.path)
    private val platforms = (1..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private val payload = CompanionPayload(
        mode = CompanionMode.HOME,
        statsLabel = "20 tiles",
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = platforms.take(4) + appTile + platforms.drop(4))),
    )

    private var taps = 0

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, 1f), LocalBottomUiDisplay provides DISPLAY) {
                MimirTheme {
                    CompanionSurface(
                        payload = payload,
                        cursorId = "p3",
                        restoredPage = 0,
                        onAppTap = { _, _ -> taps += 1 },
                        onWorkspaceDrag = { _: Float -> 0f },
                        onWorkspaceDragEnd = { _: Float -> false },
                        onWorkspaceDragCancel = {},
                        showActivitySwipeHint = false,
                        // The lift's long press reads this clock; it is the paused compose clock, so a held finger lifts when the test says.
                        monotonicUptimeMillis = { compose.mainClock.currentTime },
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        frames(2_000L)
    }

    private fun frames(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun iconWidth(): Float =
        compose.onNodeWithTag(CompanionLatticeIconTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width

    /** Where the app tile's centre is, in the drag root's own coordinates. */
    private fun tileCentre(): Offset {
        val tile = compose.onNodeWithTag(companionTileTag("app.alpha"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return Offset(tile.center.x - root.left, tile.center.y - root.top)
    }

    private val beat = MimirMotion.FocusMs.toLong()

    @Test
    fun `a held production lattice tile is drawn in to the press scale, and a tap lets go and activates`() {
        mount()
        val rest = iconWidth()
        assertTrue("the probe icon is laid out: $rest px", rest > 40f)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)

        root.performTouchInput { down(tileCentre()) }
        // Held for the beat and a little more, and short of the 180 ms the lift waits.
        frames(160)
        val held = iconWidth() / rest
        println("B5-LATTICE held ratio=$held token=$MimirPressScale")
        assertEquals("a held tile is drawn at the press scale: $held", MimirPressScale, held, 0.004f)

        root.performTouchInput { up() }
        frames(beat + 64L)
        assertEquals("a released tile is whole", 1f, iconWidth() / rest, 0.003f)
        assertEquals("and the tap went ahead", 1, taps)
    }

    @Test
    fun `a quick tap on a production lattice tile still shows the whole dip`() {
        mount()
        val rest = iconWidth()
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput { down(tileCentre()) }
        frames(48)
        root.performTouchInput { up() }
        frames(beat - 32L)
        val dip = iconWidth() / rest
        println("B5-LATTICE quick tap dip ratio=$dip")
        assertEquals("a tap is seen, the whole dip: $dip", MimirPressScale, dip, 0.006f)
        frames(beat + 64L)
        assertEquals("and returns", 1f, iconWidth() / rest, 0.003f)
        assertEquals(1, taps)
    }

    @Test
    fun `a press the pager takes is released while the finger is still down, and does not activate`() {
        mount()
        val rest = iconWidth()
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput { down(tileCentre()) }
        frames(96)
        val held = iconWidth() / rest
        assertTrue("the press was on its way in before the pan: $held", held < 0.99f)

        // The finger turns into a pan: the pager takes the gesture, the machine hands the press to it, the tile lets go.
        root.performTouchInput { moveBy(Offset(-90f, 0f), delayMillis = 0) }
        frames(32)
        root.performTouchInput { repeat(4) { moveBy(Offset(-40f, 0f), delayMillis = 16) } }
        frames(beat + 64L)
        val panning = iconWidth() / rest
        println("B5-LATTICE panning ratio=$panning")
        assertEquals("a press the pager took is released, still down: $panning", 1f, panning, 0.004f)

        root.performTouchInput { up() }
        frames(1_000L)
        assertEquals("a pan is not a tap", 0, taps)
    }

    private companion object {
        const val Density369 = 369f / 160f
        const val DISPLAY = 4
    }
}
