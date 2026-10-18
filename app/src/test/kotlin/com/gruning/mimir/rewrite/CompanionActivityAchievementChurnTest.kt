package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirMedalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bottom's achievement sink must not churn: a Main focus move re-asks the lane, and the same answer must leave the
 * sink (and so the Bottom's whole Activity page, which collects it) exactly as it was. Each test owns a unique game id
 * and account because the sink is a process singleton.
 */
class CompanionActivityAchievementChurnTest {

    private fun progress(unlockedIds: Set<Int>, title: String = "Churn Game") = RetroAchievementsProgress(
        gameId = 7001,
        title = title,
        achievements = (1..40).map { id ->
            RetroAchievementsAchievement(
                id = id,
                title = "Achievement $id",
                description = "Do thing $id",
                points = id,
                unlocked = id in unlockedIds,
                unlockedHardcore = id in unlockedIds && id % 2 == 0,
            )
        },
    )

    private fun loaded(vararg unlockedIds: Int, title: String = "Churn Game") =
        MimirAchievementLaneState.Loaded(progress(unlockedIds.toSet(), title))

    private fun held(gameId: String, account: String) =
        CompanionActivityAchievements.snapshots.value.filter { it.gameId == gameId && it.account == account }

    @Test
    fun `the hub card for a snapshot is built once and read many times`() {
        CompanionActivityAchievements.publish("g-card", "acct-card", loaded(2, 3, 8))
        val snapshot = held("g-card", "acct-card").single()

        val first = snapshot.toAchievementCardState()
        repeat(50) { assertSame(first, snapshot.toAchievementCardState()) }

        // The values are the ones the card always showed: earned rows only, in the snapshot's own order, no points line.
        assertEquals(3, first.unlocked)
        assertEquals(40, first.total)
        assertEquals(2 + 3 + 8, first.earnedPoints)
        assertEquals(0, first.totalPoints)
        assertEquals(false, first.pointsKnown)
        assertEquals(listOf(2, 3, 8), first.rows.map { it.id })
        assertEquals(MimirMedalState.EARNED_HARDCORE, first.rows[0].medal)
        assertEquals(MimirMedalState.EARNED, first.rows[1].medal)
        assertEquals("Churn Game", first.gameTitle)
    }

    @Test
    fun `the same answer published again leaves the sink untouched`() {
        CompanionActivityAchievements.publish("g-same", "acct-same", loaded(1, 2))
        val first = held("g-same", "acct-same").single()
        val firstList = CompanionActivityAchievements.snapshots.value

        // A new answer object carrying the same data, as every Main focus commit produces.
        repeat(50) { CompanionActivityAchievements.publish("g-same", "acct-same", loaded(1, 2)) }

        assertSame(first, held("g-same", "acct-same").single())
        assertSame(first, CompanionActivityAchievements.state.value)
        assertSame(firstList, CompanionActivityAchievements.snapshots.value)
    }

    @Test
    fun `a changed answer replaces its entry once and keeps one entry per game and account`() {
        CompanionActivityAchievements.publish("g-change", "acct-change", loaded(1))
        val before = held("g-change", "acct-change").single()

        CompanionActivityAchievements.publish("g-change", "acct-change", loaded(1, 4))
        val after = held("g-change", "acct-change").single()

        assertEquals(1, before.unlocked)
        assertEquals(2, after.unlocked)
        assertEquals(listOf(1, 4), after.earned.map { it.id })
        assertSame(after, CompanionActivityAchievements.state.value)
    }

    @Test
    fun `the checking placeholder never clears the held snapshot`() {
        CompanionActivityAchievements.publish("g-check", "acct-check", loaded(5))
        val snapshot = held("g-check", "acct-check").single()

        CompanionActivityAchievements.publish("g-check", "acct-check", retroAchievementsCheckingState())

        assertSame(snapshot, CompanionActivityAchievements.state.value)
        assertSame(snapshot, held("g-check", "acct-check").single())
    }

    @Test
    fun `a real non-answer still clears the current snapshot and a no-set removes the entry`() {
        CompanionActivityAchievements.publish("g-clear", "acct-clear", loaded(5))
        val snapshot = held("g-clear", "acct-clear").single()

        CompanionActivityAchievements.publish("g-clear", "acct-clear", retroAchievementsUnreachableState())
        assertNull(CompanionActivityAchievements.state.value)
        assertSame(snapshot, held("g-clear", "acct-clear").single())

        CompanionActivityAchievements.publish(
            "g-clear", "acct-clear", MimirAchievementLaneState.NoSet(MimirAchievementNoSetReason.NOT_MATCHED),
        )
        assertTrue(held("g-clear", "acct-clear").isEmpty())
    }

    @Test
    fun `an empty earned list is not an empty set`() {
        CompanionActivityAchievements.publish("g-none", "acct-none", loaded())
        val card = held("g-none", "acct-none").single().toAchievementCardState()
        assertEquals(MimirAchievementCopy.NoneEarnedYet, card.emptyLine)
        assertEquals(0, card.unlocked)
        assertNotNull(card.rows)
    }
}
