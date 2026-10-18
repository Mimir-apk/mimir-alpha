package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Checking" must never outlive every probe for its game. Opening X's card supersedes X's ambient probe (Home's effect keeps its key for X, so it
 * does not restart); if the card goes away before its own answer, X has to resolve without a refocus: restore what was last published for X, or with
 * nothing published, ask again. This drives the lane's real bookkeeping ([RetroAchievementsLaneBook]) the way the coordinator does.
 */
class RetroAchievementsCheckingRecoveryTest {

    private val account = "recovery-player"
    private val book = RetroAchievementsLaneBook()

    /** The one published slot, as the coordinator keeps it. */
    private var slot: DerivedLane? = null

    private fun loaded(gameId: String) = DerivedLane.of(
        gameId,
        account,
        MimirAchievementLaneState.Loaded(
            RetroAchievementsProgress(
                gameId = 77,
                title = "Recovery Game",
                achievements = listOf(RetroAchievementsAchievement(1, "A", "a", 5, unlocked = true, unlockedHardcore = false)),
            ),
        ),
    )

    private fun publish(derived: DerivedLane) {
        slot = derived
        book.noteSettled(derived, book.accountEpoch())
    }

    private fun slotIsChecking(gameId: String) =
        slot?.published?.let { it.gameIdValue == gameId && it.state == retroAchievementsCheckingState() } == true

    /** openCardFromHome: supersede the game's probes, show Checking. */
    private fun openCard(gameId: String) {
        book.fence.begin(gameId, null)
        publish(DerivedLane.of(gameId, account, retroAchievementsCheckingState()))
    }

    private fun backBeforeAnswer(gameId: String) = book.recoveryAfterCardGone(gameId, slotIsChecking(gameId))

    @Test
    fun `back before the answer restores the last published snapshot when there is one`() {
        val earlier = loaded("x")
        publish(earlier) // the ambient probe answered for X
        openCard("x")
        assertTrue(slotIsChecking("x"))

        val recovery = backBeforeAnswer("x") as CheckingRecovery.Restore
        assertSame(earlier, recovery.lane)
        publish(recovery.lane)
        assertSame(earlier, slot)
        assertTrue(!slotIsChecking("x"))
    }

    @Test
    fun `back before the answer asks again when nothing was ever published for the game`() {
        openCard("x")
        assertEquals(CheckingRecovery.Reprobe, backBeforeAnswer("x"))
    }

    @Test
    fun `the checking placeholder is never remembered as an answer`() {
        openCard("x")
        assertNull(book.settledFor("x"))
        assertEquals(CheckingRecovery.Reprobe, backBeforeAnswer("x"))
    }

    @Test
    fun `an ambient probe that holds the newest request answers by itself so nothing is recovered`() {
        publish(loaded("x"))
        openCard("x")
        // Something re-asked ambiently after the card opened (a re-mounted gamelist card): it is the newest request for X.
        val ambient = book.fence.begin("x", Job())
        book.ambientBegan("x", ambient)
        assertEquals(CheckingRecovery.None, backBeforeAnswer("x"))

        // Once that probe is over, Checking would be orphaned again, and recovery says so.
        book.ambientEnded("x", ambient)
        book.fence.finish("x", ambient)
        assertTrue(backBeforeAnswer("x") is CheckingRecovery.Restore)
    }

    @Test
    fun `opening the card supersedes the ambient probe so it no longer counts as answering`() {
        val ambientJob = Job()
        val ambient = book.fence.begin("x", ambientJob)
        book.ambientBegan("x", ambient)
        assertTrue(book.ambientHoldsNewest("x"))

        openCard("x")
        assertTrue(ambientJob.isCancelled)
        assertTrue(!book.ambientHoldsNewest("x"))
        assertEquals(CheckingRecovery.Reprobe, backBeforeAnswer("x"))
    }

    @Test
    fun `an answered card needs no recovery`() {
        openCard("x")
        publish(loaded("x")) // the card's answer landed
        assertEquals(CheckingRecovery.None, backBeforeAnswer("x"))
    }

    @Test
    fun `settled answers are kept per game and an account change forgets them`() {
        val x = loaded("x")
        val y = loaded("y")
        publish(x)
        publish(y)
        assertSame(x, book.settledFor("x"))
        assertSame(y, book.settledFor("y"))
        book.accountChanged()
        assertNull(book.settledFor("x"))
    }
}
