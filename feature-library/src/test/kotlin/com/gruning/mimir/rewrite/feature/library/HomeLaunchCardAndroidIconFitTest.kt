package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import kotlin.math.sqrt
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
 * Audit 2026-10-15, F7 (combined43 sweep, `22-android-card.png`): the launch card of an Android app added to the Library (Calendar) painted its icon as a white disc 664 px (288 dp) wide, a
 * quarter larger in area than any cover on the same card. It was not off its slot (its centre is the slot's centre, its right edge the card's end margin) and not mid-handoff (the card
 * was settled; the blurred disc at the middle of the screen is the shelf's own tile under the card's frosted glass). It was the one art kind exempt from the card's equal-area law
 * ([homeLaunchCardArtSizeForAspect]): `homeLaunchCardRenderedArtSize` gave an Android row the bare `minOf(coverColumnWidth, coverHeight)` square, while a cartridge at the same square aspect
 * solves 66,000 dp² (257 dp).
 *
 * This renders the REAL [HomeLaunchCard] at Thor Main's geometry (1920 x 1080 px at density 369 = 832.5 x 468.3 dp, landscape) at font scales 1.0 and 1.3, and asserts the icon's box is the
 * cartridge's box (square, 257 dp, one visual weight), sits inside the card on the cover slot's centre, and is the same box the Library card and the Home card paint (one composable). Pixel values
 * are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardAndroidIconFitTest {

    @get:Rule
    val compose = createComposeRule()

    private var fontScale by mutableStateOf(1f)

    @After
    fun unpark() {
        HomeLaunchCardResume.set(null)
    }

    private val cardTag = "android-icon-fit-card"

    /** Thor Main: 1920 x 1080 px at density 369. */
    private val thorDensity = 369f / 160f
    private val thorWidthDp = 1920f / thorDensity
    private val thorHeightDp = 1080f / thorDensity

    private val calendar = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.google.android.calendar")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Calendar",
            artworkUri = "file:///no/such/calendar-icon.png",
            supportingLabel = "Android game",
        ),
        platformLabel = "Android",
    )

    private val cartridge = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Chrono Trigger",
            supportingLabel = "snes",
        ),
        platformLabel = "Super Nintendo",
        cartridgeArtUri = "file:///no/such/cartridge.png",
        cartridgeArtIsPhysical = true,
    )

    private var shown by mutableStateOf(calendar)

    private fun mount(widthDp: Float, heightDp: Float, profile: MimirViewportProfile) {
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
            CompositionLocalProvider(
                LocalDensity provides Density(density = thorDensity, fontScale = fontScale),
                LocalMimirViewportProfile provides profile,
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp).testTag(cardTag)) {
                        HomeLaunchCard(
                            state = shown,
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private class Art(val widthDp: Float, val heightDp: Float, val leftDp: Float, val rightGapDp: Float, val centreYDp: Float)

    /** The cover art's box on the card as it stands now, in dp: [fontScale] and [shown] are the test's to set first. */
    private fun measure(): Art {
        compose.waitForIdle()
        val card = compose.onAllNodes(hasTestTag(cardTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val art = compose.onAllNodes(hasTestTag(HomeLaunchCardCoverArtTestTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        return Art(
            widthDp = art.width / thorDensity,
            heightDp = art.height / thorDensity,
            leftDp = (art.left - card.left) / thorDensity,
            rightGapDp = (card.right - art.right) / thorDensity,
            centreYDp = ((art.top + art.bottom) / 2f - card.top) / thorDensity,
        )
    }

    private val thorProfile = MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f)

    @Test
    fun `at Thor Main an Android icon is the cartridge's square on the cover slot, at fonts 1 and 1_3`() {
        mount(thorWidthDp, thorHeightDp, thorProfile)
        val edge = sqrt(66_000f)
        listOf(1.0f, 1.3f).forEach { font ->
            fontScale = font
            shown = cartridge
            val disc = measure()
            shown = calendar
            val icon = measure()
            println("ANDROID-ICON font=$font: icon ${icon.widthDp} x ${icon.heightDp} dp (right gap ${icon.rightGapDp}, centre y ${icon.centreYDp}); cartridge ${disc.widthDp} x ${disc.heightDp}")
            assertEquals("font=$font: the icon's box is a square", icon.widthDp, icon.heightDp, 0.6f)
            assertEquals("font=$font: its weight is the card's one art law (257 dp), not the full 288 dp slot bound", edge, icon.widthDp, 0.6f)
            assertEquals("font=$font: it is a cartridge's box, width", disc.widthDp, icon.widthDp, 0.6f)
            assertEquals("font=$font: it is a cartridge's box, height", disc.heightDp, icon.heightDp, 0.6f)
            assertEquals("font=$font: on the cartridge's own centre line", disc.centreYDp, icon.centreYDp, 1f)
            assertEquals("font=$font: its right edge is the card's own end margin", 44f, icon.rightGapDp, 0.6f)
            assertTrue("font=$font: starts inside the card: ${icon.leftDp}", icon.leftDp >= 0f)
            assertTrue("font=$font: ends inside the card: ${icon.rightGapDp}", icon.rightGapDp >= 0f)
            assertTrue(
                "font=$font: sits inside the card's height: centre ${icon.centreYDp} of $thorHeightDp",
                icon.centreYDp - icon.heightDp / 2f >= -0.5f && icon.centreYDp + icon.heightDp / 2f <= thorHeightDp + 0.5f,
            )
        }
    }

    @Test
    fun `below the equal-area cap the icon is still the slot's own bound, so Nova and the compact card do not move`() {
        // At 640 x 480 the bound (221.41 dp) is narrower than the 257 dp the law would grant, so the bound wins: the icon was the bound before and still is.
        val nova = homeLaunchCardLayout(640.dp, 480.dp)
        val novaIcon = homeLaunchCardRenderedArtSize(emulated = false, platformId = "", maxWidth = nova.coverColumnWidth, maxHeight = nova.coverHeight, physicalMedia = false)
        assertEquals(minOf(nova.coverColumnWidth, nova.coverHeight).value, novaIcon.width.value, 0.01f)
        assertEquals(novaIcon.width.value, novaIcon.height.value, 0.01f)
        // At Thor Main the law caps it below the bound: the cap, not the 288 dp slot.
        val thor = homeLaunchCardLayout(thorProfile.widthDp.dp, thorProfile.heightDp.dp)
        val thorIcon = homeLaunchCardRenderedArtSize(emulated = false, platformId = "", maxWidth = thor.coverColumnWidth, maxHeight = thor.coverHeight, physicalMedia = false)
        assertTrue("the Thor's bound is wide enough that the law decides: ${thor.coverColumnWidth}", thor.coverColumnWidth.value > sqrt(66_000f))
        assertEquals(sqrt(66_000f), thorIcon.width.value, 0.01f)
        assertEquals("the box the card hugs is the same width the art paints", thorIcon.width, homeLaunchCardCoverBoxWidth(emulated = false, platformId = "", layout = thor, physicalMedia = false))
    }
}
