package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slices L5 and L6, the parts that need no layout: the look's storage words and default, and the Journal's fact wording for every presence and every absence.
 */
class CompanionLaunchLookTest {

    // ── The look ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the default is Cinematic`() {
        assertEquals(CompanionLaunchLook.CINEMATIC, CompanionLaunchLook.Default)
    }

    @Test
    fun `the storage words are stable and distinct, and are never the ordinal`() {
        assertEquals(listOf("cinematic", "journal", "logo_only"), CompanionLaunchLook.values().map { it.storageKey })
        assertEquals(CompanionLaunchLook.values().size, CompanionLaunchLook.values().map { it.storageKey }.distinct().size)
        assertEquals(listOf("Cinematic", "Journal", "Logo only"), CompanionLaunchLook.values().map { it.label })
    }

    @Test
    fun `every look round-trips through its storage word`() {
        CompanionLaunchLook.values().forEach { assertEquals(it, CompanionLaunchLook.fromStorageKey(it.storageKey)) }
    }

    @Test
    fun `a missing, unknown or corrupt word reads as the default`() {
        assertEquals(CompanionLaunchLook.Default, CompanionLaunchLook.fromStorageKey(null))
        assertEquals(CompanionLaunchLook.Default, CompanionLaunchLook.fromStorageKey(""))
        assertEquals(CompanionLaunchLook.Default, CompanionLaunchLook.fromStorageKey("sepia"))
        assertEquals(CompanionLaunchLook.Default, CompanionLaunchLook.fromStorageKey("\u0000journal"))
        assertEquals(CompanionLaunchLook.Default, CompanionLaunchLook.fromStorageKey("JOURNAL"))
    }

    @Test
    fun `the switch cycles the three looks in turn and comes round`() {
        assertEquals(CompanionLaunchLook.JOURNAL, CompanionLaunchLook.CINEMATIC.next)
        assertEquals(CompanionLaunchLook.LOGO_ONLY, CompanionLaunchLook.JOURNAL.next)
        assertEquals(CompanionLaunchLook.CINEMATIC, CompanionLaunchLook.LOGO_ONLY.next)
    }

    // ── The Journal's facts ────────────────────────────────────────────────────────────────────────

    private val utc = ZoneOffset.UTC

    // 2023-11-14 22:13:20 UTC
    private val now = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    private fun game(lastPlayed: Long? = now - dayMs, plays: Int = 14) = GameRow(
        gameId = GameId("g1"),
        title = "Sonic the Hedgehog 2",
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        lastPlayedMillis = lastPlayed,
        playCount = plays,
    )

    private fun facts(game: GameRow = game(), card: CompanionLaunchCardFacts = CompanionLaunchCardFacts.None) = companionJournalFacts(game, card, now, utc)

    @Test
    fun `last played is a capitalised relative day, and Never played when there is none`() {
        assertEquals("Yesterday", facts().lastPlayed.value)
        assertEquals("Today", facts(game(lastPlayed = now - 1_000L)).lastPlayed.value)
        assertEquals("3 days ago", facts(game(lastPlayed = now - 3 * dayMs)).lastPlayed.value)
        assertFalse(facts().lastPlayed.absent)
        val never = facts(game(lastPlayed = null)).lastPlayed
        assertEquals("Never played", never.value)
        assertTrue(never.absent)
        assertEquals("Last played", never.label)
    }

    @Test
    fun `launches is the count of launches, and Never played when there are none`() {
        assertEquals("14", facts().launches.value)
        assertEquals("1", facts(game(plays = 1)).launches.value)
        val none = facts(game(plays = 0)).launches
        assertEquals("Never played", none.value)
        assertTrue(none.absent)
        assertEquals("Launches", none.label)
    }

    @Test
    fun `session time is the elapsed total in the house wording, and Not tracked when the app has none`() {
        assertEquals("18 h 40 min", facts(card = CompanionLaunchCardFacts(sessionTotalMillis = (18L * 60 + 40) * 60_000L)).sessionTime.value)
        assertEquals("40 min", facts(card = CompanionLaunchCardFacts(sessionTotalMillis = 40L * 60_000L)).sessionTime.value)
        assertEquals("Less than a minute", facts(card = CompanionLaunchCardFacts(sessionTotalMillis = 10_000L)).sessionTime.value)
        for (missing in listOf<Long?>(null, 0L, -5L)) {
            val fact = facts(card = CompanionLaunchCardFacts(sessionTotalMillis = missing)).sessionTime
            assertEquals("Not tracked for $missing", "Not tracked", fact.value)
            assertTrue(fact.absent)
        }
    }

    @Test
    fun `session time is labelled Session time and the word playtime is never used`() {
        assertEquals("Session time", facts().sessionTime.label)
        val everyWord = listOf(
            facts().lastPlayed, facts().launches, facts().sessionTime, facts().achievements,
            facts(card = CompanionLaunchCardFacts(sessionTotalMillis = 60_000L, achievementsDone = 1, achievementsTotal = 2)).sessionTime,
        ).flatMap { listOf(it.label, it.value) }
        assertTrue(everyWord.none { it.contains("playtime", ignoreCase = true) || it.contains("play time", ignoreCase = true) })
    }

    @Test
    fun `achievements are the counts with a ring, and Progress unavailable without a valid snapshot`() {
        val full = facts(card = CompanionLaunchCardFacts(achievementsDone = 12, achievementsTotal = 40))
        assertEquals("12 of 40", full.achievements.value)
        assertFalse(full.achievements.absent)
        assertEquals(12 to 40, full.ring)
        assertEquals("Achievements", full.achievements.label)
        // A count past the total is clamped as Main's label clamps it, never "41 of 40".
        assertEquals("40 of 40", facts(card = CompanionLaunchCardFacts(achievementsDone = 41, achievementsTotal = 40)).achievements.value)
        for (card in listOf(
            CompanionLaunchCardFacts.None,
            CompanionLaunchCardFacts(achievementsDone = 3),
            CompanionLaunchCardFacts(achievementsTotal = 40),
            CompanionLaunchCardFacts(achievementsDone = 0, achievementsTotal = 0),
            CompanionLaunchCardFacts(achievementsDone = 0, achievementsTotal = -1),
        )) {
            val result = facts(card = card)
            assertEquals("Progress unavailable for $card", "Progress unavailable", result.achievements.value)
            assertTrue(result.achievements.absent)
            assertNull("no ring for $card", result.ring)
        }
    }

    @Test
    fun `an unplayed game with nothing tracked states each absence plainly`() {
        val all = facts(game(lastPlayed = null, plays = 0))
        assertEquals(
            listOf("Never played", "Never played", "Not tracked", "Progress unavailable"),
            listOf(all.lastPlayed.value, all.launches.value, all.sessionTime.value, all.achievements.value),
        )
        assertTrue(listOf(all.lastPlayed, all.launches, all.sessionTime, all.achievements).all { it.absent })
    }
}
