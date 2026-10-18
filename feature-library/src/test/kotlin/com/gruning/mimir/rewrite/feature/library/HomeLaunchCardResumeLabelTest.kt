package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parked-game copy: the launch card's primary button says "Resume" for the one game HOME parked. */
class HomeLaunchCardResumeLabelTest {
    @After
    fun unpark() {
        HomeLaunchCardResume.set(null)
    }

    private fun state(itemId: HomeShelfItemId) = HomeLaunchCardUiState(
        row = HomeShelfRow(itemId = itemId, rank = 0L, addedAtMillis = 0L, title = "Title"),
        platformLabel = "Platform",
    )

    @Test
    fun `the parked game's card says Resume and every other game's card still says Play`() {
        HomeLaunchCardResume.set(GameId("xenogears"))

        assertEquals("Resume", state(HomeShelfItemId.Emulated(GameId("xenogears"))).primaryActionLabel)
        assertEquals("Play", state(HomeShelfItemId.Emulated(GameId("crash"))).primaryActionLabel)
    }

    @Test
    fun `nothing parked leaves Play, and the label goes back to Play when the game is no longer parked`() {
        assertNull(HomeLaunchCardResume.parkedGameId())
        assertEquals("Play", state(HomeShelfItemId.Emulated(GameId("xenogears"))).primaryActionLabel)

        HomeLaunchCardResume.set(GameId("xenogears"))
        assertEquals("Resume", state(HomeShelfItemId.Emulated(GameId("xenogears"))).primaryActionLabel)

        HomeLaunchCardResume.set(null)
        assertEquals("Play", state(HomeShelfItemId.Emulated(GameId("xenogears"))).primaryActionLabel)
    }

    @Test
    fun `an Android game or app is never Resume, even with the same name parked`() {
        HomeLaunchCardResume.set(GameId("com.example.game"))

        assertEquals("Play", state(HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game"))).primaryActionLabel)
        assertEquals("Open", state(HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app"))).primaryActionLabel)
    }
}
