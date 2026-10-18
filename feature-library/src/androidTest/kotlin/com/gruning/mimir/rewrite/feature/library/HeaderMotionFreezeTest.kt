package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeaderMotionFreezeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun identityHeaderUsesTheFinalLibraryTitle() {
        compose.setContent {
            MimirTheme(MimirThemeId.DEFAULT) {
                MimirIdentityHeader(
                    platformCount = 5,
                    gameCount = 42,
                    timeLabel = "9:41 PM",
                    modifier = Modifier.width(600.dp),
                )
            }
        }

        compose.onNodeWithText("Library", ignoreCase = true).assertExists()
        compose.onNodeWithText("MIMIR").assertDoesNotExist()
        compose.onNodeWithText("Identifying", substring = true).assertDoesNotExist()
    }

    @Test
    fun moltenHeaderMovesWhileRecentThenFreezesAtItsCurrentIdleFrame() {
        compose.mainClock.autoAdvance = false
        var phase by mutableStateOf(AmbientMotionPhase.RECENT)
        compose.setContent {
            MimirTheme(MimirThemeId.SURTR) {
                CompositionLocalProvider(LocalMimirAmbientMotionPhase provides phase) {
                    MimirIdentityHeader(
                        platformCount = 5,
                        gameCount = 42,
                        timeLabel = "9:41 PM",
                        modifier = Modifier.width(600.dp).testTag(HeaderTag),
                    )
                }
            }
        }

        compose.mainClock.advanceTimeBy(700L)
        val movingA = compose.onNodeWithTag(HeaderTag).captureToImage().pixelHash()
        compose.mainClock.advanceTimeBy(700L)
        val movingB = compose.onNodeWithTag(HeaderTag).captureToImage().pixelHash()
        assertNotEquals("the molten rule must actually move before the gate idles", movingA, movingB)

        compose.runOnIdle { phase = AmbientMotionPhase.IDLE }
        compose.mainClock.advanceTimeByFrame()
        val frozen = compose.onNodeWithTag(HeaderTag).captureToImage().pixelHash()
        compose.mainClock.advanceTimeBy(4_000L)
        val fourSecondsLater = compose.onNodeWithTag(HeaderTag).captureToImage().pixelHash()

        assertEquals("idle must retain the exact current frame", frozen, fourSecondsLater)
    }

    private fun ImageBitmap.pixelHash(): Int {
        val pixels = toPixelMap()
        var hash = 17
        for (y in 0 until height) {
            for (x in 0 until width) hash = 31 * hash + pixels[x, y].toArgb()
        }
        return hash
    }

    private companion object {
        const val HeaderTag = "mimir_header_motion"
    }
}
