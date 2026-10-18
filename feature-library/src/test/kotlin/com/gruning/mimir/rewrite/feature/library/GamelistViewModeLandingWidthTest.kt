package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Carousel/List body sits in the gamelist Column under the header with only weight(1f). A weighted Column child
 * is measured with minWidth 0, and MimirPageHandoff sizes to its minimum (all its layers are matchParentSize), so
 * the body once laid out 0 dp wide: header drawn, no games. This lays the transition out exactly as the call site
 * does and holds the body at the Column's full width and remaining height, before and after a mode swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GamelistViewModeLandingWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private val mode = mutableStateOf(GamelistViewMode.CAROUSEL)
    private var body = IntSize.Zero

    private fun frames(count: Int) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    @Test
    fun weightedBodyFillsTheColumnInBothModes() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Column(Modifier.size(width = 300.dp, height = 200.dp)) {
                    Box(Modifier.height(40.dp)) // the gamelist header
                    GamelistViewModeLandingTransition(
                        mode = mode.value,
                        modifier = Modifier.weight(1f),
                        reducedMotion = false,
                    ) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { body = it.size })
                    }
                }
            }
        }
        frames(2)
        assertEquals("carousel body", IntSize(300, 160), body)

        body = IntSize.Zero
        mode.value = GamelistViewMode.LIST
        Snapshot.sendApplyNotifications()
        frames(60)
        assertEquals("list body after the swap", IntSize(300, 160), body)
    }
}
