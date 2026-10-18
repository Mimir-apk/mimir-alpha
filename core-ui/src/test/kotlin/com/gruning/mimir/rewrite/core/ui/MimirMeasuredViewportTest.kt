package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirMeasuredViewportTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `valid measurement is current and every zero shape holds the last valid viewport`() {
        var width by mutableStateOf(900.dp)
        var height by mutableStateOf(400.dp)
        lateinit var decision: MimirMeasuredViewportDecision

        compose.setContent {
            decision = rememberMeasuredViewport(
                maxWidth = width,
                maxHeight = height,
                inDomain = ::positiveFiniteDomain,
                beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
            )
        }
        compose.waitForIdle()

        assertDecision(MimirMeasuredViewportStatus.VALID, 900.dp, 400.dp, decision)
        assertFalse(decision.suppressDrawing)

        listOf(0.dp to 400.dp, 900.dp to 0.dp, 0.dp to 0.dp).forEach { measurement ->
            compose.runOnIdle {
                width = measurement.first
                height = measurement.second
            }
            compose.waitForIdle()

            assertDecision(MimirMeasuredViewportStatus.TRANSIENT_ZERO, 900.dp, 400.dp, decision)
            assertTrue("drawing is suppressed exactly while a zero holds valid dimensions", decision.suppressDrawing)
        }
    }

    @Test
    fun `zero before the first valid measurement follows render-nothing policy`() {
        lateinit var decision: MimirMeasuredViewportDecision
        compose.setContent {
            decision = rememberMeasuredViewport(
                maxWidth = 0.dp,
                maxHeight = 400.dp,
                inDomain = ::positiveFiniteDomain,
                beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
            )
        }
        compose.waitForIdle()

        assertEquals(MimirMeasuredViewportStatus.TRANSIENT_ZERO, decision.status)
        assertNull(decision.dimensions)
        assertFalse(decision.shouldRender)
        assertFalse("there is no held subtree to suppress", decision.suppressDrawing)
    }

    @Test
    fun `zero before the first valid measurement follows fatal policy`() {
        lateinit var decision: MimirMeasuredViewportDecision
        compose.setContent {
            decision = rememberMeasuredViewport(
                maxWidth = 0.dp,
                maxHeight = 400.dp,
                inDomain = ::positiveFiniteDomain,
                beforeFirstValid = MimirBeforeFirstValidViewport.FATAL,
            )
        }
        compose.waitForIdle()

        assertDecision(MimirMeasuredViewportStatus.FATAL, 0.dp, 400.dp, decision)
        assertFalse(decision.suppressDrawing)
    }

    @Test
    fun `positive out-of-domain measurement is fatal even after a valid viewport`() {
        var width by mutableStateOf(900.dp)
        lateinit var decision: MimirMeasuredViewportDecision
        compose.setContent {
            decision = rememberMeasuredViewport(
                maxWidth = width,
                maxHeight = 400.dp,
                inDomain = { widthDp, heightDp -> widthDp >= 320f && heightDp >= 300f },
                beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
            )
        }
        compose.waitForIdle()
        assertDecision(MimirMeasuredViewportStatus.VALID, 900.dp, 400.dp, decision)

        compose.runOnIdle { width = 319.dp }
        compose.waitForIdle()

        assertDecision(MimirMeasuredViewportStatus.FATAL, 319.dp, 400.dp, decision)
        assertFalse(decision.suppressDrawing)
    }

    @Test
    fun `positive infinity is fatal`() {
        lateinit var decision: MimirMeasuredViewportDecision
        compose.setContent {
            decision = rememberMeasuredViewport(
                maxWidth = Float.POSITIVE_INFINITY.dp,
                maxHeight = 400.dp,
                inDomain = ::positiveFiniteDomain,
                beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
            )
        }
        compose.waitForIdle()

        assertDecision(MimirMeasuredViewportStatus.FATAL, Float.POSITIVE_INFINITY.dp, 400.dp, decision)
    }

    @Test
    fun `NaN throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            compose.setContent {
                rememberMeasuredViewport(
                    maxWidth = Float.NaN.dp,
                    maxHeight = 400.dp,
                    inDomain = ::positiveFiniteDomain,
                    beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
                )
            }
        }
    }

    @Test
    fun `negative dimension throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            compose.setContent {
                rememberMeasuredViewport(
                    maxWidth = 900.dp,
                    maxHeight = (-1).dp,
                    inDomain = ::positiveFiniteDomain,
                    beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
                )
            }
        }
    }

    @Test
    fun `valid A then zero then valid B uses B immediately and reenables drawing`() {
        var measurement by mutableStateOf(900.dp to 400.dp)
        lateinit var decision: MimirMeasuredViewportDecision
        compose.setContent {
            decision = rememberMeasuredViewport(
                maxWidth = measurement.first,
                maxHeight = measurement.second,
                inDomain = ::positiveFiniteDomain,
                beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
            )
        }
        compose.waitForIdle()
        assertDecision(MimirMeasuredViewportStatus.VALID, 900.dp, 400.dp, decision)

        compose.runOnIdle { measurement = 0.dp to 0.dp }
        compose.waitForIdle()
        assertDecision(MimirMeasuredViewportStatus.TRANSIENT_ZERO, 900.dp, 400.dp, decision)
        assertTrue(decision.suppressDrawing)

        compose.runOnIdle { measurement = 832.5f.dp to 468.3f.dp }
        compose.waitForIdle()
        assertDecision(MimirMeasuredViewportStatus.VALID, 832.5f.dp, 468.3f.dp, decision)
        assertFalse(decision.suppressDrawing)
    }

    private fun assertDecision(
        status: MimirMeasuredViewportStatus,
        width: Dp,
        height: Dp,
        actual: MimirMeasuredViewportDecision,
    ) {
        assertEquals(status, actual.status)
        assertEquals(width, actual.width)
        assertEquals(height, actual.height)
        assertTrue(actual.shouldRender)
    }

    private fun positiveFiniteDomain(widthDp: Float, heightDp: Float): Boolean =
        widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
}
