package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * `companionOverrideLabel` and `companionLaunchStateForSelection`
 * are live and unchanged, but their only tests lived in `CompanionIdentityFirstPolicyTest`, which was
 * deleted whole because most of it covered the retired mirror. That deletion was collateral
 * damage on two functions that had nothing to do with the retirement — found by review, not by the
 * build, because deleting a test never fails one.
 *
 * These are written against the behaviour, not copied from the originals.
 */
class CompanionPushPolicyTest {

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        platformId = PlatformId("dreamcast"),
        title = id,
        memberCount = 1,
        duplicateKind = null,
    )

    // ── companionOverrideLabel: a cancellation must not be swallowed as a failure ────────────────

    @Test
    fun `a resolved override wins over the fallback`() = runBlocking {
        assertEquals("Flycast", companionOverrideLabel(fallback = "RetroArch") { "Flycast" })
    }

    @Test
    fun `a null resolution falls back`() = runBlocking {
        assertEquals("RetroArch", companionOverrideLabel(fallback = "RetroArch") { null })
    }

    @Test
    fun `an ordinary failure falls back rather than propagating`() = runBlocking {
        assertEquals("RetroArch", companionOverrideLabel(fallback = "RetroArch") { error("room blew up") })
    }

    @Test
    fun `a CANCELLATION is rethrown, not swallowed into the fallback`() = runBlocking {
        // The distinction the generic catch would destroy. A focus-keyed effect is cancelled by a NEWER
        // focus; swallowing that would let a stale arm publish its label over the newer one, which is
        // exactly the latest-wins guarantee this push path is built on.
        try {
            companionOverrideLabel(fallback = "RetroArch") { throw CancellationException("newer focus") }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("newer focus", expected.message)
        }
        Unit
    }

    // ── companionLaunchStateForSelection: feedback belongs to the game that armed it ─────────────

    @Test
    fun `launch feedback survives only for the game that armed it`() {
        val armed = game("sonic")
        val state = companionLaunchStateForSelection(
            selectedGame = armed,
            detailGame = armed,
            openGameDetail = null,
            launchState = GameLaunchUiState.IntentSent,
        )
        assertEquals(GameLaunchUiState.IntentSent, state)
    }

    @Test
    fun `a newer direct-focus selection does not inherit the previous game's launch state`() {
        val state = companionLaunchStateForSelection(
            selectedGame = game("shenmue"),
            detailGame = game("sonic"),
            openGameDetail = null,
            launchState = GameLaunchUiState.IntentSent,
        )
        assertEquals(
            "a launch armed by sonic must not decorate shenmue",
            GameLaunchUiState.Idle,
            state,
        )
    }

    @Test
    fun `openGameDetail owns the launch ahead of detailGame`() {
        val state = companionLaunchStateForSelection(
            selectedGame = game("sonic"),
            detailGame = game("shenmue"),
            openGameDetail = game("sonic"),
            launchState = GameLaunchUiState.IntentSent,
        )
        assertEquals(GameLaunchUiState.IntentSent, state)
    }

    @Test
    fun `no selection means no launch feedback at all`() {
        assertEquals(
            GameLaunchUiState.Idle,
            companionLaunchStateForSelection(
                selectedGame = null,
                detailGame = game("sonic"),
                openGameDetail = null,
                launchState = GameLaunchUiState.IntentSent,
            ),
        )
    }
}
