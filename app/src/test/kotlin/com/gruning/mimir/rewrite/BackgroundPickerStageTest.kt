package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Background picker's stage with the real shelf on it (background preview, reviewed 2026-09-30): the shelf's header and
 * the player's first three platforms as the coverflow's own book tiles, the middle one centred, and its title band --
 * none of it taking focus or touch (the picker's tiles hold every focus key), and the lanes solved to the stage.
 *
 * The ground under it is the picker's own bake and is pinned by the picker's tests; what a tile paints is the shelf's.
 * The stage's pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundPickerStageTest {

    @get:Rule
    val compose = createComposeRule()

    private val shelf = listOf(
        PlatformRow(PlatformId("snes"), "Super Nintendo", 12),
        PlatformRow(PlatformId("dreamcast"), "Dreamcast", 1),
        PlatformRow(PlatformId("gba"), "Game Boy Advance", 7),
        PlatformRow(PlatformId("psx"), "PlayStation", 30),
    )

    @Test
    fun `the stage shows the shelf's header and its first three platforms as real tiles, the middle one centred, out of focus`() {
        val authority = DefaultFocusAuthority()
        val platforms = mutableStateOf(shelf)
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(608.dp, 208.dp)) {
                    BackgroundPickerLiveStageChrome(authority = authority, platforms = platforms.value, gameCount = 50)
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(BackgroundStageHeaderTestTag).assertIsDisplayed()
        compose.onNodeWithTag("appearance-background-stage-tile:snes").assertIsDisplayed()
        compose.onNodeWithTag(BackgroundStageCentredTileTestTag).assertIsDisplayed()
        compose.onNodeWithTag("appearance-background-stage-tile:gba").assertIsDisplayed()
        assertEquals("the fourth platform is not on the stage", 0, compose.onAllNodesWithTag("appearance-background-stage-tile:psx").fetchSemanticsNodes().size)
        // The centred tile's title band: the middle platform, with its own count.
        compose.onNodeWithText("Dreamcast").assertIsDisplayed()
        compose.onNodeWithText("1 game").assertIsDisplayed()
        // Out of focus and touch: no tile registered a key in the picker's scope, and none takes a tap.
        assertEquals(emptyList<Any>(), authority.registeredKeysInScope(BackgroundPickerScope))
        listOf("appearance-background-stage-tile:snes", BackgroundStageCentredTileTestTag, "appearance-background-stage-tile:gba").forEach { tag ->
            compose.onNodeWithTag(tag).assert(!hasClickAction())
        }

        // A shelf of two shows two, the first centred; an empty shelf shows the header alone, no invented tile.
        platforms.value = shelf.take(2)
        compose.waitForIdle()
        compose.onNodeWithTag(BackgroundStageCentredTileTestTag).assertIsDisplayed()
        compose.onNodeWithTag("appearance-background-stage-tile:dreamcast").assertIsDisplayed()
        compose.onNodeWithText("Super Nintendo").assertIsDisplayed()
        platforms.value = emptyList()
        compose.waitForIdle()
        compose.onNodeWithTag(BackgroundStageHeaderTestTag).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(BackgroundStageCentredTileTestTag).fetchSemanticsNodes().size)
        assertEquals(emptyList<Any>(), authority.registeredKeysInScope(BackgroundPickerScope))
    }

    @Test
    fun `the header is compact on a compact shelf, measured or not`() {
        // Measured: the profile's own answer, the shelf's width being its own.
        assertTrue(backgroundStageShelfIsCompact(mimirViewportProfile(360f, 360f), 360f))
        assertTrue(!backgroundStageShelfIsCompact(mimirViewportProfile(832f, 468f), 832f))
        // Unmeasured (the default profile, 0 x 0, is not compact): the stage stands in for the shelf at its own width, and a narrow one is compact, as a measured 360 dp square is.
        val unmeasured = com.gruning.mimir.rewrite.core.ui.MimirViewportProfile(com.gruning.mimir.rewrite.core.ui.MimirViewportPosture.STANDARD, 0f, 0f)
        assertTrue("a 328 dp stage is a compact shelf", backgroundStageShelfIsCompact(unmeasured, 328f))
        assertTrue("a Main-wide stage is not", !backgroundStageShelfIsCompact(unmeasured, 800f))
    }

    @Test
    fun `the shelf is scaled to the stage's width, never up, and its lanes fill the stage's height`() {
        // The Nova: a 608 x 208 stage on a 640 dp shelf. Scaled 0.95; the header keeps its 45, the title its floor,
        // the tiles get the rest -- a 114 dp book, not the old 36-44 dp silhouette.
        val nova = solveBackgroundStageGeometry(608f, 208f, 640f)
        assertEquals(0.95f, nova.scale, 1e-3f)
        assertEquals(640f, nova.layoutWidthDp, 1e-2f)
        assertEquals(218.9f, nova.layoutHeightDp, 0.1f)
        assertEquals(38f, nova.titleBandDp, 0f)
        assertEquals(nova.layoutHeightDp - 8f - 45f - 10f - 38f, nova.artBandDp, 1e-3f)
        assertEquals((nova.artBandDp - 20f) / 1.03f, nova.seatHeightDp, 1e-3f)
        assertEquals(nova.seatHeightDp * 144f / 164f, nova.seatWidthDp, 1e-3f)
        assertEquals(nova.seatWidthDp * 20f / 144f, nova.gapDp, 1e-3f)
        assertTrue("three books and two gaps fit the shelf's width", 3 * nova.seatWidthDp + 2 * nova.gapDp < nova.layoutWidthDp)

        // A 16:9 handheld: a 699 x 139 stage on a 731 dp shelf. The lanes squeeze the tiles to about the old size.
        val wide = solveBackgroundStageGeometry(699f, 139f, 731f)
        assertEquals(0.956f, wide.scale, 1e-3f)
        assertEquals(38f, wide.titleBandDp, 0f)
        assertTrue("about the old silhouettes' size, never a speck", wide.seatHeightDp in 32f..48f)

        // A stage wider than its shelf (a test's bare rule) is shown at 1, never up; a tall stage caps the book at the
        // ruled 144 x 164 and the title band at 48.
        val bare = solveBackgroundStageGeometry(600f, 200f, 0f)
        assertEquals(1f, bare.scale, 0f)
        val tall = solveBackgroundStageGeometry(600f, 600f, 600f)
        assertEquals(48f, tall.titleBandDp, 0f)
        assertEquals(164f, tall.seatHeightDp, 0f)
        assertEquals(144f, tall.seatWidthDp, 1e-3f)
        assertEquals(20f, tall.gapDp, 1e-3f)
        // Nothing degenerate for a zero stage.
        val zero = solveBackgroundStageGeometry(0f, 0f, 640f)
        assertTrue(zero.seatHeightDp >= 32f && zero.titleBandDp >= 38f)
    }
}
