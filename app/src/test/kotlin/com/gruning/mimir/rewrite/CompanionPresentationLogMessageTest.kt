package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionPresentationLogMessageTest {

    /**
     * QA D5 (2026-09-11): every flagged `destination=gbc shown=gbc` line was a re-print or the mode gate re-admitting
     * a route nobody wrote. The sequence is the device's own, pid 26506 13:28:53 -> 13:38:10: a touch opens GBC, a
     * MainActivity resume re-prints, quick launch hides the shelf, the RetroArch quit restores it, the SHELF back.
     */
    @Test
    fun `D5 the shelf line says whether the destination moved and what moved it`() {
        val lattice = CompanionShelfDiagnosticSnapshot(
            route = null,
            destination = null,
            shown = null,
            sceneRevision = 15L,
            sceneRoute = "HOME",
            mode = "HOME",
        )
        val opened = lattice.copy(route = "gbc", destination = "gbc", shown = "gbc")
        val resumed = opened.copy(sceneRevision = 16L)
        val appRunning = resumed.copy(destination = null, sceneRevision = 17L, mode = "RUNNING_APP")
        val exitSettled = appRunning.copy(shown = null)
        val quit = exitSettled.copy(destination = "gbc", shown = "gbc", sceneRevision = 18L, mode = "HOME")
        val backedOut = quit.copy(route = null, destination = null)

        assertEquals(
            "destination=none shown=none sceneRevision=15 sceneRoute=HOME effectiveMode=HOME " +
                "destinationChanged=false cause=compose",
            companionShelfDiagnosticMessage(null, lattice),
        )
        assertEquals(
            "destination=gbc shown=gbc sceneRevision=15 sceneRoute=HOME effectiveMode=HOME " +
                "destinationChanged=true cause=route",
            companionShelfDiagnosticMessage(lattice, opened),
        )
        assertEquals(
            "destination=gbc shown=gbc sceneRevision=16 sceneRoute=HOME effectiveMode=HOME " +
                "destinationChanged=false cause=scene",
            companionShelfDiagnosticMessage(opened, resumed),
        )
        assertEquals(
            "destination=none shown=gbc sceneRevision=17 sceneRoute=HOME effectiveMode=RUNNING_APP " +
                "destinationChanged=true cause=mode",
            companionShelfDiagnosticMessage(resumed, appRunning),
        )
        assertEquals(
            "destination=none shown=none sceneRevision=17 sceneRoute=HOME effectiveMode=RUNNING_APP " +
                "destinationChanged=false cause=shown",
            companionShelfDiagnosticMessage(appRunning, exitSettled),
        )
        assertEquals(
            "destination=gbc shown=gbc sceneRevision=18 sceneRoute=HOME effectiveMode=HOME " +
                "destinationChanged=true cause=mode",
            companionShelfDiagnosticMessage(exitSettled, quit),
        )
        assertEquals(
            "destination=none shown=gbc sceneRevision=18 sceneRoute=HOME effectiveMode=HOME " +
                "destinationChanged=true cause=route",
            companionShelfDiagnosticMessage(quit, backedOut),
        )
    }

    @Test
    fun `D17 the display chooser and the per-game preference edit carry the game and the choice`() {
        val game = "catalog:gbc:zelda-oracle-of-ages"
        assertEquals(
            "display chooser open game=$game editing=false",
            gameDisplayChooserOpenedLogMessage(game, editing = false),
        )
        assertEquals(
            "display chooser choose game=$game destination=BOTTOM",
            gameDisplayChooserChosenLogMessage(game, CompanionQuickLaunchDestination.BOTTOM),
        )
        assertEquals(
            "display chooser cancel game=$game editing=true",
            gameDisplayChooserCancelledLogMessage(game, editing = true),
        )
        assertEquals(
            "display preference edit game=$game from=MAIN to=ASK_NEXT_TIME",
            gameDisplayPreferenceEditedLogMessage(
                game,
                CompanionGameDisplayPreference.MAIN,
                CompanionQuickLaunchDestination.ASK_NEXT_TIME,
            ),
        )
        assertEquals(
            "display preference edit game=$game from=ASK_NEXT_TIME to=MAIN",
            gameDisplayPreferenceEditedLogMessage(game, null, CompanionQuickLaunchDestination.MAIN),
        )
    }
}
