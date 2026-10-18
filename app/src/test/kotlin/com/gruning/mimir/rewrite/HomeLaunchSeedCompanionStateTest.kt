package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FUX-E G-2: a Home launch seeds a NOW_PLAYING(isLoading) card through [CompanionState.set] (the E6
 * launchingRow → CompanionPayloadPush path). It must be captured into the retained-hero lane so the
 * armed-owner republish works for Home launches, and a FAILED launch's clearRetainedNowPlaying must stop
 * that seed from resurrecting as a stable card.
 */
class HomeLaunchSeedCompanionStateTest {

    @Before
    fun reset() {
        CompanionState.clearRetainedNowPlaying()
        CompanionState.set(CompanionPayload.idle())
    }

    @Test
    fun `home NOW_PLAYING loading seed is retained and republishes as a stable hero`() {
        CompanionState.set(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("sonic"),
                title = "Sonic",
                isLoading = true,
            ),
        )
        // Away game observed foreground again → republish the retained hero as a STABLE card.
        assertTrue(CompanionState.republishRetainedNowPlaying())
        val p = CompanionState.payload.value
        assertEquals(CompanionMode.NOW_PLAYING, p.mode)
        assertEquals(GameId("sonic"), p.gameId)
        assertFalse(p.isLoading)
    }

    @Test
    fun `a failed home launch clears the retained seed so it cannot resurrect`() {
        CompanionState.set(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("sonic"),
                title = "Sonic",
                isLoading = true,
            ),
        )
        CompanionState.clearRetainedNowPlaying()
        assertFalse(CompanionState.republishRetainedNowPlaying())
    }
}
