package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The sheet tier (motion audit 2026-09-28, batch F): one OverlayMs clock on the slide and the fade, the house easing. */
class MimirSheetMotionTest {

    @Test
    fun `a sheet rides one house clock on every channel`() {
        val spec = mimirSheetSpec<Float>(reducedMotion = false) as TweenSpec
        assertEquals(MimirMotion.OverlayMs, spec.durationMillis)
        assertEquals(0, spec.delay)
        assertEquals(MimirMotion.Easing, spec.easing)
        val reduced = mimirSheetSpec<Float>(reducedMotion = true) as TweenSpec
        assertEquals("reduced motion snaps", 0, reduced.durationMillis)
    }

    @Test
    fun `the enter and exit transitions exist for both anchored edges`() {
        assertNotNull(mimirSheetEnter(reducedMotion = false))
        assertNotNull(mimirSheetEnter(reducedMotion = false, fromTop = true))
        assertNotNull(mimirSheetExit(reducedMotion = false))
        assertNotNull(mimirSheetExit(reducedMotion = true, fromTop = true))
    }
}
