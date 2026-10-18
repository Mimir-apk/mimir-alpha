package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * How the console and the most recent award date reach the Activity page's snapshots: the bulk read carries them, a launch-card check of the same game inherits them, and a
 * bulk answer that agrees with a richer snapshot refreshes them without losing its rows. Nothing here touches a network.
 */
class CompanionActivityFilterPlumbingTest {

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    private fun lane(earned: Int, total: Int = 40) = MimirAchievementLaneState.Loaded(
        RetroAchievementsProgress(
            gameId = 1,
            title = "Super Metroid",
            achievements = (1..total).map { RetroAchievementsAchievement(it, "A$it", "a", 1, unlocked = it <= earned, unlockedHardcore = false) },
        ),
    )

    private fun bulk(earned: Int, console: String?, awarded: Long?) = CompanionActivityAchievementSnapshot(
        gameId = "g1",
        account = "p",
        unlocked = earned,
        total = 40,
        gameTitle = "Super Metroid",
        checkedAtEpochMillis = 5L,
        rowsKnown = false,
        raGameId = 1,
        consoleName = console,
        lastAwardedAtMillis = awarded,
    )

    @Test
    fun `the bulk match carries the console, trimmed, and the award date`() {
        val matched = matchActivityBulk(
            games = listOf(
                RetroAchievementsUserGame(1, "Super Metroid", "  SNES/Super Famicom ", earned = 12, total = 40, lastAwardedAtMillis = 123L),
                RetroAchievementsUserGame(2, "Odd Game", "   ", earned = 1, total = 4),
            ),
            local = emptyList(),
            platformIdFor = { null },
            knownLocalByRaId = emptyMap(),
            account = "p",
            nowMillis = 5L,
        ).associateBy { it.gameId }
        assertEquals("SNES/Super Famicom", matched.getValue("ra:1").consoleName)
        assertEquals(123L, matched.getValue("ra:1").lastAwardedAtMillis)
        assertNull("a blank console is no console", matched.getValue("ra:2").consoleName)
        assertNull(matched.getValue("ra:2").lastAwardedAtMillis)
    }

    @Test
    fun `a launch-card check inherits the console and the award date, and a new award moves the date to the check`() {
        CompanionActivityAchievements.publishBulk("p", listOf(bulk(earned = 12, console = "SNES", awarded = 1_000L)), totalPoints = null)

        // The same count with its rows: a richer snapshot of the same game. It carries neither console nor date, and keeps the bulk read's.
        CompanionActivityAchievements.publish("g1", "p", lane(earned = 12))
        val same = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(12, same.earned.size)
        assertEquals("SNES", same.consoleName)
        assertEquals(1_000L, same.lastAwardedAtMillis)

        // One more earned since: the award happened after the bulk read's date, so the check's own time is the newest date there is.
        CompanionActivityAchievements.publish("g1", "p", lane(earned = 13))
        val grown = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(13, grown.unlocked)
        assertEquals("SNES", grown.consoleName)
        assertTrue("the date moved to the check", grown.lastAwardedAtMillis == grown.checkedAtEpochMillis && grown.lastAwardedAtMillis!! > 1_000L)
    }

    @Test
    fun `a bulk answer that agrees with a richer snapshot refreshes its console and date and keeps its rows`() {
        CompanionActivityAchievements.publishBulk("p", listOf(bulk(earned = 12, console = null, awarded = null)), totalPoints = null)
        CompanionActivityAchievements.publish("g1", "p", lane(earned = 12))
        val rich = CompanionActivityAchievements.snapshots.value.single()
        assertNull(rich.consoleName)

        CompanionActivityAchievements.publishBulk("p", listOf(bulk(earned = 12, console = "SNES", awarded = 2_000L)), totalPoints = null)
        val refreshed = CompanionActivityAchievements.snapshots.value.single()
        assertEquals("the rows stay", 12, refreshed.earned.size)
        assertEquals("SNES", refreshed.consoleName)
        assertEquals(2_000L, refreshed.lastAwardedAtMillis)

        // The same answer again changes nothing, so nothing is emitted and nothing is derived again.
        val before = CompanionActivityAchievements.snapshots.value
        CompanionActivityAchievements.publishBulk("p", listOf(bulk(earned = 12, console = "SNES", awarded = 2_000L)), totalPoints = null)
        assertTrue(before.single() === CompanionActivityAchievements.snapshots.value.single())
    }

    @Test
    fun `the console and the date take no part in whether two snapshots are the same content`() {
        val a = bulk(earned = 12, console = "SNES", awarded = 1L)
        val b = bulk(earned = 12, console = "GB", awarded = 2L)
        assertTrue(a.sameContentAs(b))
    }
}
