package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceOverlayGameNativeSourceTest {
    @Test
    fun `GameNative foreground can present Games Only without a prior library game session`() {
        val signal = PerformanceOverlayForegroundSignal.ForeignForeground("app.gamenative")

        assertTrue(
            performanceOverlayShouldPresentCard(
                visibility = PerformanceOverlayVisibility.GAMES_ONLY,
                dispatchedGamePackageName = null,
                foregroundSignal = signal,
            ),
        )
    }

    @Test
    fun `unrelated native app cannot open the Games Only card`() {
        assertFalse(
            performanceOverlayShouldPresentCard(
                visibility = PerformanceOverlayVisibility.GAMES_ONLY,
                dispatchedGamePackageName = null,
                foregroundSignal = PerformanceOverlayForegroundSignal.ForeignForeground("com.example.native"),
            ),
        )
    }

    @Test
    fun `Mimir primary foreground is never accepted as GameNative`() {
        assertFalse(
            performanceOverlayGameNativeForegroundProven(
                PerformanceOverlayForegroundSignal.NeutralForeground,
            ),
        )
    }

    @Test
    fun `only a current foreign signal can prove GameNative`() {
        assertTrue(
            performanceOverlayGameNativeForegroundProven(
                PerformanceOverlayForegroundSignal.ForeignForeground("app.gamenative"),
            ),
        )
        assertFalse(
            performanceOverlayGameNativeForegroundProven(
                PerformanceOverlayForegroundSignal.AwaitingObservation,
            ),
        )
    }

    @Test
    fun `approved emulator foreground can prove renderer route without a dispatch session`() {
        assertEquals(
            "org.dolphinemu.dolphinemu",
            performanceOverlayForegroundRenderPackage(
                usageAccessGranted = true,
                mimirPrimaryResumed = false,
                interactive = true,
                currentForegroundPackageName = "org.dolphinemu.dolphinemu",
            ),
        )
    }

    @Test
    fun `renderer route rejects unrelated or stale foreground state`() {
        assertNull(
            performanceOverlayForegroundRenderPackage(
                usageAccessGranted = true,
                mimirPrimaryResumed = false,
                interactive = true,
                currentForegroundPackageName = "com.example.native",
            ),
        )
        assertNull(
            performanceOverlayForegroundRenderPackage(
                usageAccessGranted = true,
                mimirPrimaryResumed = true,
                interactive = true,
                currentForegroundPackageName = "org.dolphinemu.dolphinemu",
            ),
        )
    }

    @Test
    fun `X1 BOX private renderer package is accepted only from current foreground proof`() {
        assertEquals(
            "com.izzy2lost.x1box",
            performanceOverlayForegroundRenderPackage(
                usageAccessGranted = true,
                mimirPrimaryResumed = false,
                interactive = true,
                currentForegroundPackageName = "com.izzy2lost.x1box",
            ),
        )
        assertNull(
            performanceOverlayForegroundRenderPackage(
                usageAccessGranted = true,
                mimirPrimaryResumed = false,
                interactive = true,
                currentForegroundPackageName = null,
            ),
        )
    }
}
