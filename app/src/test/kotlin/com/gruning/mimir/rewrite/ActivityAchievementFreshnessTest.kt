package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which number the Bottom shows when two reads disagree, and how an unlock's date is read and printed:
 *  - a card's per-game detail can be five minutes old while a newer bulk read has counted more (the newer is shown);
 *  - the Now Playing chip follows a newer bulk count for its game;
 *  - an unlock's date is an instant, sorted as one and printed in the viewer's own time zone.
 * Everything here is pure: the clock is a number the test hands over.
 */
class ActivityAchievementFreshnessTest {

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    private fun achievement(id: Int, unlocked: Boolean, earnedAt: String? = null, hardcoreAt: String? = null, points: Int = 5) = RetroAchievementsAchievement(
        id = id,
        title = "Badge $id",
        description = "Do thing $id",
        points = points,
        unlocked = unlocked || hardcoreAt != null,
        unlockedHardcore = hardcoreAt != null,
        badgeName = "%05d".format(id),
        earnedAt = earnedAt ?: hardcoreAt,
        earnedHardcoreAt = hardcoreAt,
    )

    private fun progress(unlocked: Int, total: Int = 40) = RetroAchievementsProgress(
        gameId = 7,
        title = "Freshness Game",
        achievements = (1..total).map { achievement(it, unlocked = it <= unlocked, earnedAt = if (it <= unlocked) "2024-01-01 00:00:00" else null) },
    )

    private fun snapshot(unlocked: Int, at: Long?, total: Int = 40, gameId: String = "g", account: String = "me") = CompanionActivityAchievementSnapshot(
        gameId = gameId,
        account = account,
        unlocked = unlocked,
        total = total,
        gameTitle = "Freshness Game",
        checkedAtEpochMillis = at,
        rowsKnown = false,
        raGameId = 7,
    )

    // ── U4: the card's counts ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a bulk count read after the detail and higher is the count the card shows`() {
        val detail = ActivityCardDetail.Loaded(progress(6), atMillis = 1_000L)
        assertEquals(ActivityPanelCounts(7, 40), activityPanelCounts(snapshot(7, at = 2_000L), detail))
    }

    @Test
    fun `a detail read after the bulk count keeps its own count`() {
        val detail = ActivityCardDetail.Loaded(progress(6), atMillis = 3_000L)
        assertEquals(ActivityPanelCounts(6, 40), activityPanelCounts(snapshot(7, at = 2_000L), detail))
        assertEquals("the same instant is not newer", ActivityPanelCounts(6, 40), activityPanelCounts(snapshot(7, at = 3_000L), detail))
    }

    @Test
    fun `a newer bulk count that is LOWER is RA still catching up and does not take the number away`() {
        val detail = ActivityCardDetail.Loaded(progress(7), atMillis = 1_000L)
        assertEquals(ActivityPanelCounts(7, 40), activityPanelCounts(snapshot(6, at = 2_000L), detail))
    }

    @Test
    fun `a set of another size is the set as it is now, whichever way the unlocks went`() {
        val detail = ActivityCardDetail.Loaded(progress(7, total = 40), atMillis = 1_000L)
        assertEquals(ActivityPanelCounts(6, 44), activityPanelCounts(snapshot(6, at = 2_000L, total = 44), detail))
    }

    @Test
    fun `equal counts, no time on the snapshot, and no loaded detail all fall back sensibly`() {
        val detail = ActivityCardDetail.Loaded(progress(7), atMillis = 1_000L)
        assertEquals(ActivityPanelCounts(7, 40), activityPanelCounts(snapshot(7, at = 2_000L), detail))
        assertEquals("a snapshot with no time is not newer", ActivityPanelCounts(7, 40), activityPanelCounts(snapshot(9, at = null), detail))
        assertEquals(ActivityPanelCounts(9, 40), activityPanelCounts(snapshot(9, at = 2_000L), null))
        assertEquals(ActivityPanelCounts(9, 40), activityPanelCounts(snapshot(9, at = 2_000L), ActivityCardDetail.Loading))
        assertEquals(ActivityPanelCounts(9, 40), activityPanelCounts(snapshot(9, at = 2_000L), ActivityCardDetail.Unavailable))
    }

    @Test
    fun `the rule for a later count`() {
        assertTrue(activityLaterCountWins(olderAt = 1L, olderUnlocked = 5, olderTotal = 10, newerAt = 2L, newerUnlocked = 6, newerTotal = 10))
        assertFalse("not later", activityLaterCountWins(2L, 5, 10, 2L, 6, 10))
        assertFalse("earlier", activityLaterCountWins(2L, 5, 10, 1L, 6, 10))
        assertFalse("later but equal", activityLaterCountWins(1L, 5, 10, 2L, 5, 10))
        assertFalse("later but lower", activityLaterCountWins(1L, 5, 10, 2L, 4, 10))
        assertTrue("another size", activityLaterCountWins(1L, 5, 10, 2L, 4, 12))
        assertTrue("an older read with no time is older than any", activityLaterCountWins(null, 5, 10, 2L, 6, 10))
        assertFalse("a newer read with no time never wins", activityLaterCountWins(1L, 5, 10, null, 6, 10))
    }

    // ── a card check stands unless the bulk row really is newer ──────────────────────────────────────────────────────────────────

    private fun cardCheck(unlocked: Int, at: Long) = snapshot(unlocked, at, gameId = "chk", account = "chk-acct").copy(
        rowsKnown = true,
        earned = listOf(CompanionActivityEarnedAchievement(1, "Badge 1", "Do thing 1", 5, hardcore = false)),
    )

    @Test
    fun `a card check stands over a bulk row at the same count or lower, and a newer higher bulk row replaces it`() {
        CompanionActivityAchievements.publish("chk", "chk-acct", MimirAchievementLaneState.Loaded(progress(7)), cardCheck(7, at = 2_000L))
        val checked = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(7, checked.unlocked)

        // RA's denormalised count still catching up after the play: LOWER, and later. The check saw the unlock, and keeps it, rows and all.
        CompanionActivityAchievements.publishBulk("chk-acct", listOf(snapshot(6, at = 3_000L, gameId = "chk", account = "chk-acct")), null)
        var held = CompanionActivityAchievements.snapshots.value.single()
        assertEquals("a lagging bulk count does not take the check's number away", 7, held.unlocked)
        assertEquals("and the rows the check read stay", 1, held.earned.size)

        // The same count, later: the check stands (it carries the rows).
        CompanionActivityAchievements.publishBulk("chk-acct", listOf(snapshot(7, at = 4_000L, gameId = "chk", account = "chk-acct")), null)
        held = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(7, held.unlocked)
        assertEquals(1, held.earned.size)

        // More earned, and later: the bulk row is newer, so it is the count.
        CompanionActivityAchievements.publishBulk("chk-acct", listOf(snapshot(8, at = 5_000L, gameId = "chk", account = "chk-acct")), null)
        held = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(8, held.unlocked)
        assertTrue("a count with no rows behind it", held.earned.isEmpty())
    }

    @Test
    fun `a bulk row for a set of another size replaces the check, whichever way the count went`() {
        CompanionActivityAchievements.publish("chk", "chk-acct", MimirAchievementLaneState.Loaded(progress(7)), cardCheck(7, at = 2_000L))
        CompanionActivityAchievements.publishBulk("chk-acct", listOf(snapshot(6, at = 3_000L, total = 44, gameId = "chk", account = "chk-acct")), null)
        val held = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(44, held.total)
        assertEquals(6, held.unlocked)
    }

    // ── U9: the Now Playing chip's slot ─────────────────────────────────────────────────────────────────────────────────────────

    private fun publishSlot(unlocked: Int, at: Long, gameId: String = "slot-game", account: String = "slot-acct") {
        val checked = snapshot(unlocked, at, gameId = gameId, account = account).copy(rowsKnown = true)
        CompanionActivityAchievements.publish(gameId, account, MimirAchievementLaneState.Loaded(progress(unlocked)), checked)
    }

    @Test
    fun `a bulk read that counted more for the game in the slot moves the slot`() {
        publishSlot(unlocked = 6, at = 1_000L)
        val before = CompanionActivityAchievements.state.value
        assertEquals(6, before?.unlocked)

        val bulk = snapshot(7, at = 2_000L, gameId = "slot-game", account = "slot-acct")
        CompanionActivityAchievements.publishBulk("slot-acct", listOf(bulk), null)

        assertEquals("the chip follows the newer count", 7, CompanionActivityAchievements.state.value?.unlocked)
        assertSame(CompanionActivityAchievements.snapshots.value.single { it.gameId == "slot-game" }, CompanionActivityAchievements.state.value)
    }

    @Test
    fun `a bulk read with nothing new, an older one, a lower one or another game's leaves the slot exactly as it was`() {
        publishSlot(unlocked = 6, at = 1_000L)
        val held = CompanionActivityAchievements.state.value

        // The same count read later: the slot (and the card behind it) is not stirred.
        CompanionActivityAchievements.publishBulk("slot-acct", listOf(snapshot(6, at = 2_000L, gameId = "slot-game", account = "slot-acct")), null)
        assertSame(held, CompanionActivityAchievements.state.value)

        // Counts that do not beat the slot's.
        CompanionActivityAchievements.publishBulk("slot-acct", listOf(snapshot(9, at = 500L, gameId = "slot-game", account = "slot-acct")), null)
        assertSame("an older read", held, CompanionActivityAchievements.state.value)
        CompanionActivityAchievements.publishBulk("slot-acct", listOf(snapshot(5, at = 3_000L, gameId = "slot-game", account = "slot-acct")), null)
        assertSame("a lower count is RA catching up", held, CompanionActivityAchievements.state.value)

        // Another game's row never moves it.
        CompanionActivityAchievements.publishBulk("slot-acct", listOf(snapshot(30, at = 4_000L, gameId = "another-game", account = "slot-acct")), null)
        assertSame(held, CompanionActivityAchievements.state.value)
    }

    @Test
    fun `an empty slot stays empty and another account's slot is never moved`() {
        CompanionActivityAchievements.publishBulk("slot-acct", listOf(snapshot(7, at = 2_000L, gameId = "slot-game", account = "slot-acct")), null)
        assertEquals("the slot is a card check's, never created by a bulk read", null, CompanionActivityAchievements.state.value)

        publishSlot(unlocked = 6, at = 1_000L, account = "first-acct")
        val held = CompanionActivityAchievements.state.value
        CompanionActivityAchievements.publishBulk("second-acct", listOf(snapshot(9, at = 2_000L, gameId = "slot-game", account = "second-acct")), null)
        assertSame(held, CompanionActivityAchievements.state.value)
    }

    // ── T2: dates are instants ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `badges are ordered by the instant of the unlock, not by the text of RA's date`() {
        // "2023-05-02T00:10:00+02:00" is 22:10 UTC on the 1st: EARLIER than "2023-05-01 23:30:00", though it is the greater string.
        val offsetForm = achievement(1, unlocked = true, earnedAt = "2023-05-02T00:10:00+02:00")
        val plainForm = achievement(2, unlocked = true, earnedAt = "2023-05-01 23:30:00")
        val unreadable = achievement(3, unlocked = true, earnedAt = "yesterday")
        val locked = achievement(4, unlocked = false)
        val hardcoreNewer = achievement(5, unlocked = true, earnedAt = "2023-04-01 00:00:00").copy(earnedHardcoreAt = "2023-05-03 00:00:00", unlockedHardcore = true)

        val order = activityBadgeOrder(listOf(locked, unreadable, offsetForm, plainForm, hardcoreNewer))

        assertEquals(listOf(5, 2, 1, 3, 4), order.map { it.id })
    }

    @Test
    fun `a badge's earned date is printed in the viewer's own time zone in the existing style`() {
        val earned = achievement(1, unlocked = true, earnedAt = "2023-05-01 22:30:00")
        assertEquals("5 points · Earned 2023-05-01 22:30:00", activityBadgeDetail(earned, ZoneOffset.UTC).meta)
        // A local 00:30 unlock is the next day, as the player lived it.
        assertEquals("5 points · Earned 2023-05-02 00:30:00", activityBadgeDetail(earned, ZoneId.of("Europe/Berlin")).meta)
        assertEquals("5 points · Earned 2023-05-01 18:30:00", activityBadgeDetail(earned, ZoneId.of("America/New_York")).meta)

        val hardcore = achievement(2, unlocked = true, hardcoreAt = "2023-05-01 12:34:56")
        assertEquals("5 points · Earned in hardcore 2023-05-01 14:34:56", activityBadgeDetail(hardcore, ZoneId.of("Europe/Berlin")).meta)
        assertEquals("5 points · Locked", activityBadgeDetail(achievement(3, unlocked = false), ZoneOffset.UTC).meta)
    }

    @Test
    fun `a date that cannot be read is shown as RA gave it rather than guessed at`() {
        val odd = achievement(1, unlocked = true, earnedAt = "yesterday")
        assertEquals("5 points · Earned yesterday", activityBadgeDetail(odd, ZoneId.of("Europe/Berlin")).meta)
        assertEquals("1970-01-01 00:00:00", activityLocalDate(0L, ZoneOffset.UTC))
        assertEquals("the offset form reads as the same instant", activityLocalDate(0L, ZoneOffset.UTC), activityLocalDate(com.gruning.mimir.rewrite.core.metadata.online.retroAchievementsAwardMillis("1970-01-01T01:00:00+01:00")!!, ZoneOffset.UTC))
    }
}
