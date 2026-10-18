package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTaglineTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTrailingTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the Trackpad plate at the trailing edge of the Bottom's lattice header, on the REAL [CompanionSurface]
 * at the Thor Bottom's real size (1240 x 1080 px at 369 dpi, 537.7 x 468.3 dp: the Presentation draws the full 1080 px, not the 445 dp window; ledger D18/D19). The geometry is printed with the tag `PAD3-RENDER`.
 * Pixel values are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionTrackpadPlateRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val tiles = (1..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private val payload = CompanionPayload(
        mode = CompanionMode.HOME,
        statsLabel = "19 platforms · 1,234 games",
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
    )
    private var density = 0f
    private var opened = 0

    private fun mount(fontScale: Float, offered: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompositionLocalProvider(LocalBottomTrackpadOpen provides if (offered) ({ opened++ }) else null) {
                        CompanionSurface(
                            payload = payload,
                            cursorId = "p1",
                            showActivitySwipeHint = false,
                            modifier = Modifier.requiredSize((1240f / (369f / 160f)).dp, (1080f / (369f / 160f)).dp),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private fun boundsDp(tag: String): FloatArray {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return floatArrayOf(b.left / density, b.top / density, b.width / density, b.height / density)
    }

    private fun assertFits(fontScale: Float) {
        mount(fontScale, offered = true)
        val row = boundsDp(MimirCompactHeaderRowTag)
        val plate = boundsDp(BottomTrackpadPlateTag)
        val trailing = boundsDp(MimirCompactHeaderTrailingTag)
        val title = boundsDp(MimirCompactHeaderTitleTag)
        val tagline = boundsDp(MimirCompactHeaderTaglineTag)
        println(
            "PAD3-RENDER scale=$fontScale header row ${row.toList()} plate ${plate.toList()} title ${title.toList()} tagline ${tagline.toList()}",
        )
        assertTrue("the plate is at least a 48 dp target: ${plate[3]} x ${plate[2]}", plate[3] >= 47.9f && plate[2] >= 47.9f)
        assertTrue("the plate sits inside the header row (right edge ${plate[0] + plate[2]} <= ${row[0] + row[2] + 0.5f})", plate[0] + plate[2] <= row[0] + row[2] + 0.5f)
        assertEquals("it is the trailing slot's content, at the trailing edge", trailing[0] + trailing[2], plate[0] + plate[2], 0.6f)
        assertTrue("the title is whole and ends before the plate begins", title[0] + title[2] <= plate[0] + 0.5f)
        assertTrue("the tagline never runs under the plate", tagline[0] + tagline[2] <= plate[0] + 0.5f)
        assertTrue("the plate is as wide as its label needs, not more than 40 percent of the header: ${plate[2]}", plate[2] <= row[2] * 0.4f)
    }

    @Test
    fun theTrackpadPlateFitsTheHeaderNextToTheTitleAndTagline() = assertFits(1.0f)

    @Test
    fun theTrackpadPlateStillFitsAtTheCertifiedLargerFontScale() = assertFits(1.3f)

    @Test
    fun aTapOnThePlateOpensThePadThroughTheHostsAction() {
        mount(1.0f, offered = true)
        compose.onNodeWithTag(BottomTrackpadPlateTag).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withNoTrackpadOfferedTheHeaderHasNoPlate() {
        mount(1.0f, offered = false)
        compose.onAllNodesWithTag(BottomTrackpadPlateTag).assertCountEquals(0)
    }
}
