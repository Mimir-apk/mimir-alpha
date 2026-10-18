package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Tweaker page describes system-wide tuning and previews the actual FPS graph overlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerformanceOverlayPicturePlateTest {

    @get:Rule
    val compose = createComposeRule()

    // The Tweaker page's line and picture tests went with the page (OR-12, 2026-09-29). The plate itself stays and
    // is still pinned below; nothing in onboarding draws it now.

    @Test
    fun `the sample readings are a full graph of a 60 FPS game on a tuned profile`() {
        assertEquals(PerformanceOverlayHistoryLimit, PerformanceOverlayPictureFpsHistory.size)
        assertEquals(60, PerformanceOverlayPictureFpsHistory.last())
        assertTrue(PerformanceOverlayPictureFpsHistory.filterNotNull().min() < 50)
        assertNotEquals(TweakerBridge.STOCK_PROFILE, PerformanceOverlayPictureProfileId)
        assertNotEquals("ᛗ", performanceOverlayProfileMark(PerformanceOverlayPictureProfileId))
    }

    @Test
    fun `the plate keeps its own size where the picture has room, and scales down whole where it does not`() {
        val room = mutableStateOf(1_000)
        var size = IntSize.Zero
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(room.value.dp)) {
                        PerformanceOverlayPicturePlate(reveal = { 1f }, modifier = Modifier.onSizeChanged { size = it })
                    }
                }
            }
        }
        compose.waitForIdle()
        val natural = size
        assertTrue("about 160 dp: $natural", natural.width in 130..200)
        assertTrue("one line: $natural", natural.height in 20..40)

        // The widest access picture (the 132 dp cap, 16:9) holds it at its own size.
        room.value = 235
        compose.waitForIdle()
        assertEquals(natural, size)

        // The narrowest (the 64 dp minimum) scales it down whole, never wrapping a cell.
        room.value = 114
        compose.waitForIdle()
        assertEquals(114, size.width)
        assertEquals(natural.height * 114f / natural.width, size.height.toFloat(), 1f)
    }
}
