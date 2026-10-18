package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionSwapPlanTest {
    @Test
    fun `two occupied screens exchange apps`() {
        assertEquals(
            CompanionSwapPlan.EXCHANGE,
            companionSwapPlan(
                bottomHost = true, bottomGameBlocked = false, mainRunning = true,
                mainGameActive = false, launchBlocked = false,
            ),
        )
    }

    @Test
    fun `bottom app moves to an empty Main screen`() {
        assertEquals(
            CompanionSwapPlan.BOTTOM_TO_MAIN,
            companionSwapPlan(
                bottomHost = true, bottomGameBlocked = false, mainRunning = false,
                mainGameActive = false, launchBlocked = false,
            ),
        )
    }

    @Test
    fun `Main app moves to an empty Bottom screen`() {
        assertEquals(
            CompanionSwapPlan.MAIN_TO_BOTTOM,
            companionSwapPlan(
                bottomHost = false, bottomGameBlocked = false, mainRunning = true,
                mainGameActive = false, launchBlocked = false,
            ),
        )
    }

    @Test
    fun `Bottom game blocks swapping even with a Main app`() {
        assertEquals(
            CompanionSwapPlan.BLOCKED_BOTTOM_GAME,
            companionSwapPlan(
                bottomHost = true, bottomGameBlocked = true, mainRunning = true,
                mainGameActive = false, launchBlocked = false,
            ),
        )
    }

    @Test
    fun `blocked Main launch has no swap`() {
        assertEquals(
            CompanionSwapPlan.NONE,
            companionSwapPlan(
                bottomHost = false, bottomGameBlocked = false, mainRunning = true,
                mainGameActive = false, launchBlocked = true,
            ),
        )
    }

    @Test
    fun `a dock-launched Main app is an occupant and still exchanges`() {
        // The plan input is only a Boolean; the defect this case documents was in what FED it. A Main app
        // the player launched from the Bottom dock exists solely as a PRIMARY session — the display-0
        // transfer card (`CompanionRunningAppState.running`) is null for it — so the caller read an empty
        // main screen and planned BOTTOM_TO_MAIN over an occupied one (device log 2026-09-11,
        // `swap plan=BOTTOM_TO_MAIN … mainRunning=null` with Chrome on Main). Since O7 / I3 (2026-09-12)
        // `mainOccupant` is read from `MimirSessionSnapshot.primaryOccupant()` alone, which makes this input true.
        assertEquals(
            CompanionSwapPlan.EXCHANGE,
            companionSwapPlan(
                bottomHost = true, bottomGameBlocked = false, mainRunning = true,
                mainGameActive = false, launchBlocked = false,
            ),
        )
    }

    @Test
    fun `both occupied with a Main game keeps Bottom to Main behavior`() {
        assertEquals(
            CompanionSwapPlan.BOTTOM_TO_MAIN,
            companionSwapPlan(
                bottomHost = true, bottomGameBlocked = false, mainRunning = true,
                mainGameActive = true, launchBlocked = false,
            ),
        )
    }
}
