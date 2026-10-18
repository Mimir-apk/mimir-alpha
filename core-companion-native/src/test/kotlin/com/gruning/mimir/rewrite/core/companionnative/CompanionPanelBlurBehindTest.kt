package com.gruning.mimir.rewrite.core.companionnative

import android.view.WindowManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q14 : the ᛗ music panel's window blurs what is behind it. The radius arithmetic is behaviour; the
 * window-manager calls have no JVM-reachable observable, so their shape is pinned as source: the flag and radius travel together,
 * only on API 31+ and only while the platform says cross-window blur is enabled (watched, so it follows a change), a window is
 * never handed the previous window's blur, and the blur is the primary panel's alone.
 */
class CompanionPanelBlurBehindTest {

    private val controllerFile = "src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"

    private val code: String by lazy {
        val file = File(controllerFile)
        assertTrue("AndroidCompanionOverlayController.kt must be readable from the module dir", file.isFile)
        file.readText().lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    @Test
    fun `the radius is a whole-pixel fraction of the full strength, clear at 0 and full at 1`() {
        assertEquals(0, companionPanelBlurRadius(0f, 63))
        assertEquals(63, companionPanelBlurRadius(1f, 63))
        assertEquals(32, companionPanelBlurRadius(0.5f, 63))
        assertEquals("clamped below", 0, companionPanelBlurRadius(-0.4f, 63))
        assertEquals("clamped above", 63, companionPanelBlurRadius(1.7f, 63))
        val ramp = (0..20).map { companionPanelBlurRadius(it / 20f, 63) }
        assertEquals("monotone", ramp.sorted(), ramp)
    }

    @Test
    fun `the primary role blurs at the menu's strength and the Bottom role asks for nothing`() {
        assertEquals(24, companionPanelBlurRadiusDp(CompanionOverlayRole.PRIMARY_SINGLE))
        assertEquals(companionMenuBlurRadiusDp(CompanionOverlayRole.PRIMARY_SINGLE), companionPanelBlurRadiusDp(CompanionOverlayRole.PRIMARY_SINGLE))
        assertEquals(0, companionPanelBlurRadiusDp(CompanionOverlayRole.SECONDARY_COMPANION))
    }

    @Test
    fun `the blur is set and cleared as one flag and radius, only where the platform allows`() {
        val apply = code.substringAfter("private fun applyPrimaryPanelBlur(target: PanelWindow? = primaryPanelWindow) {").substringBefore("\n    private fun watchCrossWindowBlur")
        assertTrue(apply.contains("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return"))
        assertTrue("the platform's own switch is asked each time", apply.contains("panel.windowManager.isCrossWindowBlurEnabled"))
        assertTrue("a refusal is zero radius, not a crash", apply.contains("runCatching { panel.windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)"))
        assertTrue("the flag goes on with a radius", apply.contains("params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND"))
        assertTrue("and off with none", apply.contains("params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()"))
        assertTrue(apply.contains("params.blurBehindRadius = radius"))
        assertTrue("an unchanged value costs no relayout", apply.contains("if (params.blurBehindRadius == radius && flagged == radius > 0) return"))
        assertTrue(apply.contains("updateViewLayout(panel.view, params)"))
    }

    @Test
    fun `the platform's availability is watched for the life of the primary window, and the fraction does not outlive it`() {
        assertTrue(code.contains("addCrossWindowBlurEnabledListener("))
        assertTrue(code.contains("removeCrossWindowBlurEnabledListener(listener)"))
        val watch = code.substringAfter("private fun watchCrossWindowBlur(").substringBefore("\n    private fun unwatchCrossWindowBlur")
        assertTrue("a change re-applies the stored fraction to the window that is current then", watch.contains("applyPrimaryPanelBlur()"))
        val attach = code.substringAfter("primaryPanelWindow = candidate").substringBefore("}\n                    }")
        assertTrue("a new window starts clear", attach.contains("primaryPanelBlurFraction = 0f"))
        assertTrue("and is watched", attach.contains("watchCrossWindowBlur(candidate)"))
        val remove = code.substringAfter("remove = { owned ->").substringBefore("onRevoked = ")
        assertTrue("the departing window drops its fraction", remove.contains("primaryPanelBlurFraction = 0f"))
        assertTrue("and its watch", remove.contains("unwatchCrossWindowBlur(owned)"))
    }

    @Test
    fun `W1C 3 the blur is applied in the call to the window that is current now, never queued onto a successor`() {
        val entry = code.substringAfter("override fun setPrimaryPanelBlurBehind(fraction: Float) {").substringBefore("\n    }\n")
        assertTrue("a non-finite value is clear", entry.contains("fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f"))
        assertTrue("the window is captured at call time", entry.contains("val panel = primaryPanelWindow ?: return"))
        val onMain = entry.substringAfter("if (Looper.myLooper() == Looper.getMainLooper()) {").substringBefore("} else {")
        assertTrue("on the main looper it applies in this call", onMain.contains("applyPrimaryPanelBlur(panel)") && !onMain.contains("post"))
        val offMain = entry.substringAfter("} else {")
        assertTrue("off it, a post lands only if that window is still the current one", offMain.contains("if (primaryPanelWindow !== panel) return@post"))
        val apply = code.substringAfter("private fun applyPrimaryPanelBlur(target: PanelWindow? = primaryPanelWindow) {").substringBefore("\n    private fun watchCrossWindowBlur")
        assertTrue("and the apply itself refuses a window that is not the current one", apply.contains("if (primaryPanelWindow !== panel) return"))
        val contract = File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/CompanionNativeContracts.kt").readText()
        assertTrue("a default body, so a window-less fake needs none", contract.contains("fun setPrimaryPanelBlurBehind(fraction: Float) = Unit"))
    }

    @Test
    fun `a panel window is created clear, and only the primary window is ever watched or blurred`() {
        val params = code.substringAfter("fun companionPanelWindowParams(").substringBefore("\n}\n")
        assertFalse("the shared window params name no blur: every panel starts clear", params.contains("BLUR") || params.contains("blurBehind"))
        assertTrue(
            "the watch and the fraction belong to the primary role's window alone",
            code.contains("if (attached && role == CompanionOverlayRole.PRIMARY_SINGLE) {\n                            primaryPanelWindow = candidate"),
        )
        assertEquals("the blur is applied to the primary window and to nothing else", 1, code.substringAfter("private fun applyPrimaryPanelBlur(target: PanelWindow? = primaryPanelWindow) {").substringBefore("private fun watchCrossWindowBlur").split("if (primaryPanelWindow !== panel) return").size - 1)
    }

    @Test
    fun `W1C 1 the released flags keep every other flag and make the window neither focusable nor touchable`() {
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val released = companionPanelInputReleasedFlags(base)
        assertEquals("every flag it had it keeps", base, released and base)
        assertTrue("not focusable", released and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue("not touchable", released and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals("nothing else is added", base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, released)
        assertEquals("idempotent", released, companionPanelInputReleasedFlags(released))
        assertEquals("a window that was already not focusable gains only the touch flag", WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, companionPanelInputReleasedFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
    }

    @Test
    fun `W1C 1 the release edits the same params the blur re-reads, in the call, on the current window only`() {
        val release = code.substringAfter("override fun releasePrimaryPanelInput() {").substringBefore("\n    /** Puts [primaryPanelBlurFraction]")
        assertTrue("the window is captured at call time", release.contains("val panel = primaryPanelWindow ?: return"))
        assertTrue("on the main looper it is done in this call", release.contains("if (Looper.myLooper() == Looper.getMainLooper()) {\n            releaseInput(panel)"))
        val inner = release.substringAfter("private fun releaseInput(panel: PanelWindow) {")
        assertTrue("only the current window", inner.contains("if (primaryPanelWindow !== panel) return"))
        assertTrue("the SAME layoutParams object the blur code edits", inner.contains("val params = panel.view.layoutParams as? WindowManager.LayoutParams ?: return"))
        assertTrue(inner.contains("params.flags = released"))
        assertTrue("relaid out, so the window manager moves focus", inner.contains("updateViewLayout(panel.view, params)"))
        val blur = code.substringAfter("private fun applyPrimaryPanelBlur(target: PanelWindow? = primaryPanelWindow) {").substringBefore("\n    private fun watchCrossWindowBlur")
        assertTrue("the blur edits params.flags in place, so it keeps the released flags", blur.contains("params.flags = if (radius > 0) {") && blur.contains("params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND") && blur.contains("params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()"))
        val contract = File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/CompanionNativeContracts.kt").readText()
        assertTrue("a default body, so a window-less fake needs none", contract.contains("fun releasePrimaryPanelInput() = Unit"))
    }
}
