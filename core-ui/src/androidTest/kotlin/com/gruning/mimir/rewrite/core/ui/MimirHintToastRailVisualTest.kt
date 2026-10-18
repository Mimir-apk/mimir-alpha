package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MimirHintToastRailVisualTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyRailReservesGeometryWithoutPainting() {
        var includeRail by mutableStateOf(true)
        var hint by mutableStateOf<String?>(null)

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MimirTheme {
                    Box(
                        Modifier
                            .size(360.dp, MimirHintToastRailDefaults.CompactHeight)
                            .background(Backdrop)
                            .testTag(RootTag),
                    ) {
                        if (includeRail) {
                            MimirHintToastRail(
                                hint = hint,
                                modifier = Modifier.testTag(RailTag),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val railBounds = compose.onNodeWithTag(RailTag).fetchSemanticsNode().boundsInRoot
        assertEquals(360f, railBounds.width, 1f)
        assertEquals(32f, railBounds.height, 1f)
        val emptyHash = compose.onNodeWithTag(RootTag).captureToImage().pixelHash()

        compose.runOnIdle { includeRail = false }
        compose.waitForIdle()
        val absentHash = compose.onNodeWithTag(RootTag).captureToImage().pixelHash()
        assertEquals("an idle rail must paint no pixels", absentHash, emptyHash)

        compose.runOnIdle {
            includeRail = true
            hint = "Open Library"
        }
        compose.waitForIdle()
        val visibleHash = compose.onNodeWithTag(RootTag).captureToImage().pixelHash()
        assertNotEquals("the visual assertion must detect a populated rail", emptyHash, visibleHash)
    }

    @Test
    fun crossfadeKeepsOneStableSemanticOwner() {
        val first = MimirMessage(1L, "Saved", MimirMessageClass.Confirmation)
        val second = MimirMessage(2L, "Could not save", MimirMessageClass.Warning)
        var message by mutableStateOf(first)

        compose.setContent {
            MimirTheme {
                MimirHintToastRail(
                    hint = null,
                    toast = message,
                    reducedMotion = false,
                )
            }
        }
        compose.waitForIdle()

        val firstNodes = compose.onAllNodes(
            hasContentDescription(first.accessibilityText),
            useUnmergedTree = true,
        )
        firstNodes.assertCountEquals(1)
        val stableNodeId = firstNodes.fetchSemanticsNodes().single().id

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { message = second }
        compose.mainClock.advanceTimeByFrame()

        compose.onAllNodes(
            hasContentDescription(first.accessibilityText),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        val secondNodes = compose.onAllNodes(
            hasContentDescription(second.accessibilityText),
            useUnmergedTree = true,
        )
        secondNodes.assertCountEquals(1)
        assertEquals(stableNodeId, secondNodes.fetchSemanticsNodes().single().id)
    }

    @Test
    fun standaloneMessageCardRetainsItsSemantics() {
        val message = MimirMessage(
            eventId = 1L,
            text = "SD card removed",
            messageClass = MimirMessageClass.Warning,
            supportingText = "2 games unavailable",
        )
        compose.setContent {
            MimirTheme {
                MimirRunicMessageCard(message = message, compact = false)
            }
        }

        compose.onAllNodes(
            hasContentDescription(message.accessibilityText),
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    private fun ImageBitmap.pixelHash(): Int {
        val pixels = toPixelMap()
        var hash = 17
        for (y in 0 until height) {
            for (x in 0 until width) hash = 31 * hash + pixels[x, y].hashCode()
        }
        return hash
    }

    private companion object {
        val Backdrop = Color(0xFF175E72)
        const val RootTag = "hint_toast_visual_root"
        const val RailTag = "hint_toast_visual_rail"
    }
}
