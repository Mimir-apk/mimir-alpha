package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the in-place Quick Launch settle sequence defect-by-defect (2026-08-11 review of the first
 * in-place branch, eight confirmed integration defects — all absences of the old navigate path's
 * settling). Each test names the review defect it pins. The recording harness asserts ORDER, not
 * just occurrence: settling must complete before the panel opens.
 */
class QuickLaunchInPlaceExecutionTest {

    private class Recorder {
        val calls = mutableListOf<String>()
        var obstruction: MimirOverlayKind? = null
        var pickerClosed = false

        /** A kept surface (folder browser, media flow) is live; deliberately NOT recorded in [calls]. */
        var keptSurfaceLive = false

        fun run(
            panelOpen: Boolean = false,
            startupOriginApplied: Boolean = true,
        ): QuickLaunchInPlaceOutcome = executeQuickLaunchInPlace(
            panelOpen = panelOpen,
            startupOriginApplied = startupOriginApplied,
            closePicker = { pickerClosed = true; calls += "closePicker" },
            sweepConflictingOverlays = { calls += "sweep" },
            remainingObstruction = { calls += "obstruction?"; obstruction },
            keptSurfaceLive = { keptSurfaceLive },
            hardCloseTransientMenus = { calls += "hardCloseMenus" },
            cancelTomeReturn = { calls += "cancelTomeReturn" },
            disarmLaunchReturns = { calls += "disarmLaunchReturns" },
            closeUtilityPanel = { calls += "closeUtility" },
            openPanel = { calls += "openPanel" },
        )
    }

    @Test
    fun `open settles everything the old navigate path settled, then opens`() {
        val r = Recorder()
        assertEquals(QuickLaunchInPlaceOutcome.OPENED, r.run())
        // Review defects: tome replay (cancelTomeReturn), launch-return scope pop
        // (disarmLaunchReturns), phase-stepped menus (hardCloseMenus) — all BEFORE openPanel.
        assertEquals(
            listOf(
                "hardCloseMenus",
                "cancelTomeReturn",
                "disarmLaunchReturns",
                "closeUtility",
                "sweep",
                "obstruction?",
                "openPanel",
            ),
            r.calls,
        )
    }

    @Test
    fun `over a kept surface the open skips the menus and Utility beneath it and still opens`() {
        val r = Recorder()
        r.keptSurfaceLive = true
        assertEquals(QuickLaunchInPlaceOutcome.OPENED, r.run())
        // walk-32k: the press settled Utility (closed it beneath the Files browser, logged as
        // `MimirUtility: close surface=UTILITY` on each of the three presses) and the transient Home menus,
        // which belong to the stack the player returns to when the panel closes. The two steps that do not
        // alter that stack still run: a deferred Tome replay and a launch-return anchor would otherwise
        // fire into the panel.
        assertEquals(
            listOf("cancelTomeReturn", "disarmLaunchReturns", "sweep", "obstruction?", "openPanel"),
            r.calls,
        )
    }

    @Test
    fun `an obstruction that refuses CANCEL refuses the open instead of burying the panel`() {
        val r = Recorder()
        r.obstruction = MimirOverlayKind.RETROACHIEVEMENTS_KEY
        assertEquals(QuickLaunchInPlaceOutcome.REFUSED_OBSTRUCTED, r.run())
        // The panel must never open beneath an input-swallowing modal only to pop up unprompted
        // later (review defect: buried-open + phantom panel).
        assertTrue("openPanel" !in r.calls)
    }

    @Test
    fun `a still-armed return cover refuses the open the same way`() {
        val r = Recorder()
        r.obstruction = MimirOverlayKind.RETURN_COVER
        assertEquals(QuickLaunchInPlaceOutcome.REFUSED_OBSTRUCTED, r.run())
        assertTrue("openPanel" !in r.calls)
        // The disarms still ran first — a library/native anchor is torn down by the press even when
        // a residual (home/settings) cover then refuses this particular open.
        assertTrue("disarmLaunchReturns" in r.calls)
    }

    @Test
    fun `cold-start restore in flight refuses before touching any state`() {
        val r = Recorder()
        assertEquals(
            QuickLaunchInPlaceOutcome.REFUSED_STARTUP_RESTORE,
            r.run(startupOriginApplied = false),
        )
        // Review defect: opening mid-restore strands MimirStartupOriginEffect for the session.
        assertEquals(emptyList<String>(), r.calls)
    }

    @Test
    fun `re-press recovers the existing panel without re-settling`() {
        val r = Recorder()
        assertEquals(QuickLaunchInPlaceOutcome.RECOVERED_EXISTING, r.run(panelOpen = true))
        // Review defect: the old guard made M a no-op while the panel was buried or its picker was
        // up. Recovery closes the panel's own picker and sweeps foreign obstructions — and must NOT
        // re-run the settle (the panel's context was settled at its original open).
        assertTrue(r.pickerClosed)
        assertEquals(listOf("closePicker", "sweep"), r.calls)
    }
}
