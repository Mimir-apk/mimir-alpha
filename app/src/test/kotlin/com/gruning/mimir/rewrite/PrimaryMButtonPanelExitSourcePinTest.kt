package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-38 (audit 2026-10-14, X-17 / B-97 / B-97a / B-97b / B-97c / U-04 / M-01 (1)): the primary ᛗ panel host used to take its window
 * down on the frame `hide()` was called (`removeViewImmediate`, no exit ramp). It now fades the whole window body out on OverlayMs
 * with input dead and detaches when the fade has ended. The window has no JVM-reachable observable, so the sequencing is pinned as
 * source (the behaviour of each piece is proved in [NativePanelExitTest], [NativePanelExitFadeRenderTest]); the old code fails every
 * one of these (hide() detached and disposed inline).
 */
class PrimaryMButtonPanelExitSourcePinTest {

    private val code: String by lazy {
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/PrimaryMButtonPanelHost.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/PrimaryMButtonPanelHost.kt"),
        ).first(File::isFile)
        file.readText().lineSequence().joinToString("\n") { it.substringBefore("//") }
    }

    private fun body(signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("$signature is found", start >= 0)
        return code.substring(start, code.indexOf("\n    }\n", start))
    }

    @Test
    fun `hide begins an exit and no longer detaches, disposes or removes the window`() {
        val hide = body("    fun hide() {")
        assertTrue(hide.contains("beginExit(current)"))
        assertFalse("the window is not detached on the frame hide() is called", hide.contains("detach()"))
        assertFalse(hide.contains("dispose()"))
        assertFalse(hide.contains("removeView"))
        assertTrue("it still nulls the mounted panel at once (idempotence)", hide.contains("mounted = null"))
        assertTrue("and it marks the panel as leaving", hide.contains("leaving = current"))
    }

    @Test
    fun `the end of the exit is the one place the window is detached`() {
        val finish = body("    private fun finishExit(")
        assertTrue(finish.contains("current.lease?.detach()"))
        assertTrue(finish.contains("current.markDetached()"))
        assertTrue(finish.contains("current.dispose()"))
        assertEquals("only finishExit detaches", 1, Regex("""\.lease\?\.detach\(\)""").findAll(code).count())
    }

    @Test
    fun `show finishes any panel still leaving at once, so two full-screen windows never overlap`() {
        val show = body("    override fun show(command: MButtonPrimaryCommand): Boolean {")
        assertTrue(show.contains("hideNow()"))
        assertTrue("it is the first thing show does once the command is accepted", show.indexOf("hideNow()") < show.indexOf("val focusAuthority"))
        assertFalse("show() does not start an exit on the panel it replaces", show.contains("beginExit("))
        val hideNow = body("    private fun hideNow() {")
        assertTrue(hideNow.contains("leaving?.let(::finishExit)"))
        assertTrue(hideNow.contains("finishExit(current)"))
    }

    @Test
    fun `a revoked lease clears the leaving panel before it is disposed`() {
        val revoke = code.substringAfter("attachPrimaryPanel(view) { leaseId, _ ->").substringBefore("}) {")
        assertTrue(revoke.indexOf("if (leaving === candidate) leaving = null") in 0 until revoke.indexOf("candidate.dispose()"))
    }

    @Test
    fun `every face of the panel is drawn inside the fade, and the Utility face is retained for it`() {
        val content = code.substringAfter("setContent {").substringBefore("candidate = MountedPanel(")
        val glassFade = content.indexOf("NativePanelGlassExitFade(")
        val fade = content.indexOf("NativePanelExitFade(exiting = exit.exiting, onFullyHidden = exit::onFullyHidden)")
        assertTrue("the fade host wraps the content", fade >= 0 && glassFade >= 0)
        assertTrue("the music face is inside the glass fade", content.indexOf("MusicPanelHost(") > glassFade && content.indexOf("MusicPanelHost(") < fade)
        for (face in listOf("AppBrightnessCoordinatorHost(", "HomeQuickLaunchPickerOverlay(")) {
            assertTrue("$face is inside the fade", content.indexOf(face) > fade)
        }
        assertTrue(
            "the Utility face stays composed after its model closes, or there would be nothing to fade",
            content.substringAfter("AppBrightnessCoordinatorHost(").substringBefore("LaunchedEffect").contains("retainedForExit = true"),
        )
    }

    @Test
    fun `input is dead from the first frame of the exit`() {
        assertTrue("keys", code.contains("if (exit.exiting) {\n                    true"))
        assertTrue("motion", code.contains("exit.exiting || inputRouter.onMotionEvent(event)"))
        val begin = body("    private fun beginExit(")
        assertTrue("held router state is released", begin.contains("current.inputRouter.reset()"))
        assertTrue("touch is shielded by the fade host", File("src/main/kotlin/com/gruning/mimir/rewrite/NativePanelExit.kt").let {
            val f = if (it.isFile) it else File("app/src/main/kotlin/com/gruning/mimir/rewrite/NativePanelExit.kt")
            f.readText().contains("mimirPageTouchShield()")
        })
    }

    @Test
    fun `Q14 and 08-15 only the music panel has a glass exit, and its window goes when the glass is gone`() {
        val content = code.substringAfter("setContent {").substringBefore("candidate = MountedPanel(")
        val music = content.substringAfter("if (musicController != null) {").substringBefore("} else NativePanelExitFade(")
        assertTrue(music.contains("NativePanelGlassExitFade("))
        assertTrue("the exit ends on the glass, through the same exit object", music.contains("onFullyHidden = exit::onFullyHidden,"))
        assertTrue("the blur is handed to the controller of the host that owns the window", music.contains("applyBlur = this@PrimaryMButtonPanelHost.overlay::setPrimaryPanelBlurBehind,"))
        assertEquals("no other face asks for it", 1, code.split("NativePanelGlassExitFade(").size - 1)
        val exitFile = File("src/main/kotlin/com/gruning/mimir/rewrite/NativePanelExit.kt").let {
            if (it.isFile) it else File("app/src/main/kotlin/com/gruning/mimir/rewrite/NativePanelExit.kt")
        }.readText().lineSequence().joinToString("\n") { it.substringBefore("//") }
        val glass = exitFile.substringAfter("internal fun NativePanelGlassExitFade(")
        assertTrue("the glass holds, it does not clear with the request", glass.contains("rememberMimirGlassHold(requested = !exiting)"))
        assertTrue(
            "the window is reported gone only when the body has faded AND the glass is at 0",
            glass.contains("snapshotFlow { bodyHidden && !latestHeld && glass() <= 0f }.first { it }"),
        )
        assertTrue("the scrim is drawn outside the body's fade", glass.indexOf("MimirGlassScrim(progress = glass)") < glass.indexOf("NativePanelExitFade("))
    }

    @Test
    fun `W1C 1 the exiting window gives up focus and touch in the frame the exit begins`() {
        val begin = body("    private fun beginExit(")
        assertTrue(begin.contains("overlay.releasePrimaryPanelInput()"))
        assertTrue("released before the router is reset", begin.indexOf("overlay.releasePrimaryPanelInput()") < begin.indexOf("current.inputRouter.reset()"))
        assertTrue("only a real begin releases (a second hide changes nothing)", begin.indexOf("if (!current.exit.begin()) return") < begin.indexOf("overlay.releasePrimaryPanelInput()"))
        assertTrue("the swallowing stays as the backstop", code.contains("if (exit.exiting) {\n                    true") && code.contains("exit.exiting || inputRouter.onMotionEvent(event)"))
    }

    @Test
    fun `a panel disposed by another road cannot be finished again`() {
        val dispose = body("        fun dispose() {")
        assertTrue(dispose.contains("exit.cancel()"))
    }
}
