package com.gruning.mimir.rewrite.core.ui

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Observed on Thor: the system's gesture handle is drawn over the bottom 24 dp while the platform reports no bottom inset (the app is immersive-sticky), so the clearance a footer keeps is the larger of the
 * insets the platform does report and, in gesture navigation, the handle's own height. See [mimirGestureHandleClearance]; `GestureHandleClearanceFooterTest` holds the pages that use it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
class MimirGestureClearanceTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun navigationMode(mode: Int?) {
        if (mode != null) Settings.Secure.putInt(context.contentResolver, "navigation_mode", mode)
    }

    @Test
    fun `the clearance is the larger of the reported inset and the handle`() {
        assertEquals(55, mimirGestureClearancePx(insetBottomPx = 0, handlePx = 55))
        assertEquals(111, mimirGestureClearancePx(insetBottomPx = 111, handlePx = 55))
        assertEquals("a visible three-button bar is cleared as the platform says", 144, mimirGestureClearancePx(insetBottomPx = 144, handlePx = 0))
        assertEquals("nothing reported, no handle: nothing reserved", 0, mimirGestureClearancePx(insetBottomPx = 0, handlePx = 0))
        assertEquals("a negative reading is nothing", 0, mimirGestureClearancePx(insetBottomPx = -4, handlePx = -1))
    }

    @Test
    fun `gesture navigation has a handle strip of 24 dp`() {
        navigationMode(MimirNavigationModeGestural)
        val px = mimirGestureHandleHeightPx(context)
        val density = context.resources.displayMetrics.density
        println("GESTURECLEARANCE gesture handle $px px at density $density")
        assertTrue("a real strip ($px)", px > 0)
        assertEquals("24 dp: the Thor's 55 px at 369 dpi", 24f * density, px.toFloat(), 0.5f)
        assertEquals(55, Math.round(24f * 369f / 160f))
    }

    @Test
    fun `three-button navigation and an unnamed mode have no handle`() {
        navigationMode(0)
        assertEquals(0, mimirGestureHandleHeightPx(context))
        navigationMode(1)
        assertEquals("two-button is not the handle either", 0, mimirGestureHandleHeightPx(context))
    }

    @Test
    fun `a platform that says nothing reads as not gestural`() {
        assertEquals(0, mimirGestureHandleHeightPx(context))
    }
}
