package com.gruning.mimir.rewrite.core.input

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour pins for the optional `interactionSource` on [mimirActivatable]: a touch reports
 * Press -> Release (activating) or Press -> Cancel (not activating), and a Press is never left dangling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirActivatableInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val seen = ArrayList<Interaction>()
    private var activations = 0

    private fun kinds(): List<String> = seen.map {
        when (it) {
            is PressInteraction.Press -> "Press"
            is PressInteraction.Release -> "Release"
            is PressInteraction.Cancel -> "Cancel"
            else -> it::class.simpleName.orEmpty()
        }
    }

    /** Subscribes before any touch lands: the source is a replay-less flow, so a late collector sees nothing. */
    private fun setContent(
        source: MutableInteractionSource?,
        enabled: Boolean = true,
        shown: () -> Boolean = { true },
    ) {
        compose.setContent {
            if (source != null) LaunchedEffect(source) { source.interactions.collect { seen += it } }
            if (shown()) {
                Box(
                    Modifier
                        .size(100.dp)
                        .testTag("t")
                        .mimirActivatable(
                            label = "go",
                            enabled = enabled,
                            interactionSource = source,
                        ) {
                            activations++
                        },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun downReportsPressAndUpReportsReleaseWithOneActivation() {
        val source = MutableInteractionSource()
        setContent(source)

        compose.onNodeWithTag("t").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf("Press"), kinds())
        assertEquals(0, activations)

        compose.onNodeWithTag("t").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf("Press", "Release"), kinds())
        assertEquals(1, activations)
        // The Release closes the very Press it followed.
        assertSame(seen[0], (seen[1] as PressInteraction.Release).press)
    }

    @Test
    fun aCancelledGestureReportsCancelAndDoesNotActivate() {
        val source = MutableInteractionSource()
        setContent(source)

        compose.onNodeWithTag("t").performTouchInput {
            down(center)
            cancel()
        }
        compose.waitForIdle()
        assertEquals(listOf("Press", "Cancel"), kinds())
        assertSame(seen[0], (seen[1] as PressInteraction.Cancel).press)
        assertEquals(0, activations)
    }

    @Test
    fun draggingOutOfBoundsBeforeLiftingReportsCancelAndDoesNotActivate() {
        val source = MutableInteractionSource()
        setContent(source)

        compose.onNodeWithTag("t").performTouchInput {
            down(center)
            moveTo(Offset(-500f, -500f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("Press", "Cancel"), kinds())
        assertEquals(0, activations)
    }

    @Test
    fun disablingMidPressCancelsTheOpenPressInsteadOfLeakingIt() {
        val source = MutableInteractionSource()
        var enabled by mutableStateOf(true)
        compose.setContent {
            LaunchedEffect(source) { source.interactions.collect { seen += it } }
            Box(
                Modifier
                    .size(100.dp)
                    .testTag("t")
                    .mimirActivatable(label = "go", enabled = enabled, interactionSource = source) { activations++ },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("t").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf("Press"), kinds())

        enabled = false
        compose.waitForIdle()
        assertEquals(listOf("Press", "Cancel"), kinds())
        assertEquals(0, activations)
    }

    @Test
    fun leavingCompositionMidPressCancelsTheOpenPress() {
        val source = MutableInteractionSource()
        var shown by mutableStateOf(true)
        setContent(source, shown = { shown })

        compose.onNodeWithTag("t").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf("Press"), kinds())

        shown = false
        compose.waitForIdle()
        assertEquals(listOf("Press", "Cancel"), kinds())
        assertEquals(0, activations)
    }

    @Test
    fun aDisabledElementReportsNothingAndDoesNotActivate() {
        val source = MutableInteractionSource()
        setContent(source, enabled = false)

        compose.onNodeWithTag("t").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
        assertTrue("a disabled element must emit no interactions, got ${kinds()}", seen.isEmpty())
        assertEquals(0, activations)
    }

    @Test
    fun aNullSourceStillActivatesExactlyOncePerTap() {
        setContent(source = null)

        compose.onNodeWithTag("t").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
        assertEquals(1, activations)
        assertTrue(seen.isEmpty())
    }
}
