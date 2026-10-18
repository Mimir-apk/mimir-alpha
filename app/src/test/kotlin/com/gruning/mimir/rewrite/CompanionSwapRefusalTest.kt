package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 2026-09-11 D6 (S3M): Swap with a library game on Main and nothing on the Bottom logged `swap plan=NONE
 * bottomHost=null bottomGameBlocked=false mainRunning=null mainGameActive=true launchBlocked=false`, then told the
 * player "Could not move the app to the bottom screen" on the wrong screen and left the menu open. A plan that
 * moves nothing now has a name, a sentence, and a completion rule.
 */
class CompanionSwapRefusalTest {

    private fun refusal(
        bottomHost: Boolean = false,
        bottomGameBlocked: Boolean = false,
        mainRunning: Boolean = false,
        mainGameActive: Boolean = false,
        launchBlocked: Boolean = false,
    ) = companionSwapRefusal(bottomHost, bottomGameBlocked, mainRunning, mainGameActive, launchBlocked)

    @Test
    fun `a library game on Main with an empty Bottom is named as the game`() {
        assertEquals(CompanionSwapRefusal.GAME_ON_MAIN, refusal(mainGameActive = true))
        assertEquals(CompanionSwapRefusal.GAME_ON_MAIN, refusal(mainRunning = true, mainGameActive = true))
        assertEquals(
            "A game is running on the main screen. Nothing to swap yet.",
            CompanionSwapRefusal.GAME_ON_MAIN.message,
        )
    }

    @Test
    fun `two empty screens have nothing to swap`() {
        assertEquals(CompanionSwapRefusal.NOTHING_TO_SWAP, refusal())
        assertEquals(
            "Nothing to swap. Open an app on either screen first.",
            CompanionSwapRefusal.NOTHING_TO_SWAP.message,
        )
    }

    @Test
    fun `a game on the Bottom keeps the sentence it always had`() {
        assertEquals(CompanionSwapRefusal.BOTTOM_GAME_BLOCKED, refusal(bottomHost = true, bottomGameBlocked = true))
        assertEquals(
            CompanionSwapRefusal.BOTTOM_GAME_BLOCKED,
            refusal(bottomHost = true, bottomGameBlocked = true, mainRunning = true),
        )
        assertEquals("Game sessions stay on the bottom screen", CompanionSwapRefusal.BOTTOM_GAME_BLOCKED.message)
    }

    @Test
    fun `a launch or transfer in flight refuses only for now`() {
        assertEquals(CompanionSwapRefusal.LAUNCH_IN_FLIGHT, refusal(mainRunning = true, launchBlocked = true))
        assertEquals(CompanionSwapRefusal.LAUNCH_IN_FLIGHT, refusal(launchBlocked = true))
        assertEquals(
            "An app is still opening or moving. Try again in a moment.",
            CompanionSwapRefusal.LAUNCH_IN_FLIGHT.message,
        )
        // A game on Main blocks the reverse move whatever the lock says, so the game is the answer.
        assertEquals(CompanionSwapRefusal.GAME_ON_MAIN, refusal(mainGameActive = true, launchBlocked = true))
    }

    @Test
    fun `only a refusal that a second press could clear keeps the menu open`() {
        assertTrue(CompanionSwapRefusal.LAUNCH_IN_FLIGHT.retryable)
        assertFalse(CompanionSwapRefusal.NOTHING_TO_SWAP.retryable)
        assertFalse(CompanionSwapRefusal.GAME_ON_MAIN.retryable)
        assertFalse(CompanionSwapRefusal.BOTTOM_GAME_BLOCKED.retryable)
    }

    @Test
    fun `a refusal exists for exactly the plans that move nothing`() {
        val flags = listOf(false, true)
        for (bottomHost in flags) {
            for (bottomGameBlocked in flags) {
                for (mainRunning in flags) {
                    for (mainGameActive in flags) {
                        for (launchBlocked in flags) {
                            val plan = companionSwapPlan(
                                bottomHost, bottomGameBlocked, mainRunning, mainGameActive, launchBlocked,
                            )
                            val refused = companionSwapRefusal(
                                bottomHost, bottomGameBlocked, mainRunning, mainGameActive, launchBlocked,
                            )
                            val movesNothing = plan == CompanionSwapPlan.NONE ||
                                plan == CompanionSwapPlan.BLOCKED_BOTTOM_GAME
                            assertEquals(
                                "plan=$plan bottomHost=$bottomHost bottomGameBlocked=$bottomGameBlocked " +
                                    "mainRunning=$mainRunning mainGameActive=$mainGameActive launchBlocked=$launchBlocked",
                                movesNothing,
                                refused != null,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an accepted Bottom-to-Main move that stops early still tells the player why`() {
        // The menu has collapsed as accepted before these exits run, so silence here was a press that did nothing.
        assertEquals(
            CompanionSwapRefusal.LAUNCH_IN_FLIGHT.message,
            companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.TRANSFER_IN_FLIGHT, "YouTube"),
        )
        assertEquals(
            CompanionSwapRefusal.LAUNCH_IN_FLIGHT.message,
            companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.LAUNCH_LOCKED, null),
        )
        assertEquals(
            "Couldn’t move YouTube to the main screen",
            companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.HOST_NOT_YIELDED, "YouTube"),
        )
        assertEquals(
            "Couldn’t move that app to the main screen",
            companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.HOST_CHANGED, "  "),
        )
        assertEquals(
            "Couldn’t move that app to the main screen",
            companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.REGISTRY_MISSING, null),
        )
    }
}
