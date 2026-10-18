package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the seams the music panel hangs on: the app tells the overlay when there is music, and the router and host open and close it. */
class MusicPanelWiringTest {
    private fun source(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/$name")
        assertTrue("$name must be readable from the module dir", file.isFile)
        return file.readText()
    }

    @Test
    fun `the process runtime hands the overlay the facade's availability`() {
        val runtime = source("CompanionProcessRuntime.kt")
        assertTrue(runtime.contains("MimirMusicControl.musicPanelAvailable.collect(overlay::setMusicPanelAvailable)"))
    }

    @Test
    fun `the router opens the music panel in the host and never through the Activity, before any secondary routing`() {
        val router = source("MButtonRuntime.kt")
        val music = router.indexOf("action is CompanionOverlayAction.MusicPanel")
        val secondary = router.indexOf("val menu = action as? CompanionOverlayAction.QuickMenu")
        assertTrue("the music branch must exist", music >= 0)
        assertTrue("it must come before the menu and secondary routing", music < secondary)
        val branch = router.substring(music, secondary)
        assertTrue(branch.contains("primaryPanel.show(MButtonPrimaryCommand.MUSIC)"))
        assertTrue("no Activity fallback for a panel with no Activity route", !branch.contains("MButtonIntentAuthority"))
    }

    @Test
    fun `the host mounts the controller's input, key gate, content and screen-off close for MUSIC`() {
        val host = source("PrimaryMButtonPanelHost.kt")
        assertTrue(host.contains("command != MButtonPrimaryCommand.MUSIC"))
        assertTrue(host.contains("musicController.dispatch(action)"))
        assertTrue(host.contains("musicController.onKey("))
        assertTrue(host.contains("MusicPanelHost(controller = musicController, onDismiss = ::hide)"))
        assertTrue(host.contains("Intent.ACTION_SCREEN_OFF) hide()"))
        assertTrue("the controller is disposed with the panel", host.contains("musicController?.dispose()"))
    }
}
