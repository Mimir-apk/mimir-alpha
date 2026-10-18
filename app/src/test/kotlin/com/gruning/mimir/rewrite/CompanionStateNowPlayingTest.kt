package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 *  every adapter-produced NOW_PLAYING is stamped isLoading, so the stable-snapshot slots
 * never retained it and a quick-launch away-and-back restored the stale pre-launch detail. These tests pin
 * the retained-hero lane: capture regardless of isLoading, republish as a STABLE card on owner return, and
 * from then on the RUNNING_APP clear path restores the hero instead of the pre-launch detail.
 */
class CompanionStateNowPlayingTest {

    @Before
    fun reset() {
        CompanionState.clearRetainedNowPlaying()
        CompanionState.set(CompanionPayload.idle())
    }

    @Test
    fun `republished now-playing becomes the stable card the running-app clear restores`() {
        val detail = CompanionPayload(
            mode = CompanionMode.HOME,
            gameId = GameId("chrono"),
            title = "Chrono Cross",
        )
        CompanionState.set(detail)
        CompanionState.set(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("chrono"),
                title = "Chrono Cross",
                statusLabel = "Launching…",
                isLoading = true,
            ),
        )

        // The O-1 hole, still true until the owner-return re-derive fires: a quick-launch to Main and a
        // tab-switch back restores the PRE-LAUNCH detail, because the loading hero was never retained.
        CompanionState.showRunningApp(CompanionRunningApp("org.example.browser", "Browser"))
        CompanionState.clearRunningApp()
        assertEquals(CompanionMode.HOME, CompanionState.payload.value.mode)

        // Owner-package foreground match → republish the retained hero as a stable, honest card.
        assertTrue(CompanionState.republishRetainedNowPlaying())
        val republished = CompanionState.payload.value
        assertEquals(CompanionMode.NOW_PLAYING, republished.mode)
        assertFalse(republished.isLoading)
        assertNull(republished.statusLabel)
        assertEquals("Chrono Cross", republished.title)

        // From here the running-app clear lane converges on the hero card, not the stale detail.
        CompanionState.showRunningApp(CompanionRunningApp("org.example.browser", "Browser"))
        CompanionState.clearRunningApp()
        assertEquals(CompanionMode.NOW_PLAYING, CompanionState.payload.value.mode)
        assertEquals("Chrono Cross", CompanionState.payload.value.title)
    }

    @Test
    fun `republish dedups when the same stable hero is already showing`() {
        CompanionState.set(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("chrono"),
                title = "Chrono Cross",
                isLoading = true,
            ),
        )
        assertTrue(CompanionState.republishRetainedNowPlaying())
        assertFalse(CompanionState.republishRetainedNowPlaying())
    }

    @Test
    fun `republish is inert with nothing retained or after the session-over clear`() {
        assertFalse(CompanionState.republishRetainedNowPlaying())

        CompanionState.set(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("chrono"),
                title = "Chrono Cross",
                isLoading = true,
            ),
        )
        CompanionState.clearRetainedNowPlaying()
        assertFalse(CompanionState.republishRetainedNowPlaying())
    }

    @Test
    fun `reclaim guard requires live main dual-screen session and exact retained identity`() {
        val gameId = GameId("chrono")
        val session = MimirGameSession(
            operation = MimirGameSessionOperation("game:chrono"),
            sequence = 1L,
            gameId = gameId,
            platformId = PlatformId("nds"),
            returnAnchor = MimirGameReturnAnchor.Home(
                HomeLaunchReturnTarget(HomeShelfItemId.Emulated(gameId), rank = 1L),
            ),
            startedAtEpochMs = 1L,
            placement = MimirGamePlacement.MAIN,
            dualScreen = true,
            phase = MimirSessionPhase.FOREGROUND_PROVEN,
        )
        CompanionState.set(
            CompanionPayload(mode = CompanionMode.NOW_PLAYING, gameId = gameId, title = "Chrono"),
        )

        assertTrue(CompanionState.hasRetainedNowPlaying(gameId))
        assertTrue(companionNowPlayingReclaimGuard(session, CompanionState.lastNowPlayingGameId()))
        assertFalse(companionNowPlayingReclaimGuard(session, GameId("other")))
        assertFalse(
            companionNowPlayingReclaimGuard(
                session.copy(phase = MimirSessionPhase.ENDED),
                CompanionState.lastNowPlayingGameId(),
            ),
        )
        for (phase in listOf(
            MimirSessionPhase.PREPARING,
            MimirSessionPhase.RETURNING,
            MimirSessionPhase.RECOVERING,
        )) {
            assertFalse(companionNowPlayingReclaimGuard(session.copy(phase = phase), gameId))
        }
        assertFalse(companionNowPlayingReclaimGuard(session.copy(placement = MimirGamePlacement.BOTTOM), gameId))
        assertFalse(companionNowPlayingReclaimGuard(session.copy(dualScreen = false), gameId))
        CompanionState.clearRetainedNowPlaying()
        assertFalse(companionNowPlayingReclaimGuard(session, CompanionState.lastNowPlayingGameId()))
    }
}
