package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The glance pill's width at a density and a font scale ([mimirLiquidStatusPillWidthDp]), against the pill itself drawn with the widest label it can carry: the Library's header, on
 * the same row, leaves room for exactly this (combined33 sweep: at font 1.3 the pill is 164 dp, not the 146 dp a constant measured at 1x stood for, and Search ran under it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirLiquidStatusPillWidthTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var measured = 0f
    private var drawn = 0f

    private fun at(density: Float, fontScale: Float) {
        compose.setContent {
            val at = Density(density, fontScale)
            CompositionLocalProvider(LocalDensity provides at) {
                MimirTheme {
                    val measurer = rememberTextMeasurer()
                    measured = mimirLiquidStatusPillWidthDp(measurer, at)
                    Box(Modifier.requiredSize(400.dp, 80.dp)) {
                        MimirLiquidStatusPill(
                            clockLabel = "88:88",
                            batteryPercent = 100,
                            charging = false,
                            wifiConnected = true,
                            modifier = Modifier.onGloballyPositioned { drawn = it.size.width / density },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        println("STATUSPILL density=$density font=$fontScale measured=$measured drawn=$drawn")
    }

    private fun check(density: Float, fontScale: Float) {
        at(density, fontScale)
        assertEquals("the measured width is the pill's own at $density x $fontScale", drawn, measured, 1f)
    }

    @Test fun `at density 2_306 and font 1_0`() = check(2.306f, 1.0f)

    @Test fun `at density 2_306 and font 1_3`() = check(2.306f, 1.3f)

    @Test fun `at density 2_0 and font 1_0`() = check(2.0f, 1.0f)

    @Test fun `at density 2_0 and font 1_3`() = check(2.0f, 1.3f)

    @Test
    fun `the pill grows with the font, which a constant measured at font 1_0 does not follow`() {
        var one = 0f
        var big = 0f
        compose.setContent {
            val measurer = rememberTextMeasurer()
            one = mimirLiquidStatusPillWidthDp(measurer, Density(2.306f, 1.0f))
            big = mimirLiquidStatusPillWidthDp(measurer, Density(2.306f, 1.3f))
        }
        compose.waitForIdle()
        println("STATUSPILL grows: 1.0x=$one 1.3x=$big")
        assertTrue("the pill at 1.3x ($big dp) is wider than at 1x ($one dp)", big > one + 10f)
    }
}
