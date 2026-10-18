package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Update puts an etched chip into two fixed bands that cannot grow to 48 dp (the gamelist detail band's Get Art chip and the Files header's Queue plate), and promises them a
 * 48 dp tap area anyway, "an invisible hit-box expansion, not a taller plate". The mechanism is Compose's own minimum touch target: a pointer-input node smaller than the
 * viewport's 48 dp touch target still takes a touch that lands within it, with no layout change. This pins that on the node the chips use (`mimirActivatable`), at the plate
 * height they keep: a plate 26 dp tall takes a tap 9 dp above its top edge and 9 dp below its bottom edge, and takes none 20 dp out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtchedChipTouchTargetTest {

    @get:Rule
    val compose = createComposeRule()

    private var taps = 0

    private fun mountPlate() {
        taps = 0
        compose.setContent {
            Box(Modifier.size(300.dp).testTag("host"), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 90.dp, height = 26.dp)
                        .testTag("plate")
                        .mimirActivatable(label = "chip", onActivate = { taps++ }),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun tapAt(dxDp: Float, dyDp: Float) {
        compose.onNodeWithTag("host").performTouchInput {
            click(Offset(center.x + dxDp.dp.toPx(), center.y + dyDp.dp.toPx()))
        }
        compose.waitForIdle()
    }

    @Test
    fun `a plate shorter than 48 dp takes a tap just outside its edge and none far outside it`() {
        mountPlate()
        tapAt(0f, 0f)
        assertEquals("a tap on the plate", 1, taps)
        tapAt(0f, -(13f + 9f))
        assertEquals("a tap 9 dp above the plate's top edge", 2, taps)
        tapAt(0f, 13f + 9f)
        assertEquals("a tap 9 dp below the plate's bottom edge", 3, taps)
        tapAt(0f, -(13f + 20f))
        assertEquals("a tap 20 dp above the plate is outside the 48 dp target", 3, taps)
        tapAt(0f, 13f + 20f)
        assertEquals("and 20 dp below", 3, taps)
    }
}
