package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaMatchOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaNoAnswerReason
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsMatch
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirMedalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroAchievementsProgressLaneTest {

    @Test
    fun `Home probe cannot publish after close or into another card`() {
        assertTrue(homeProbeIsCurrent(7, 7, "game-a", "game-a", cardOpen = true))
        assertFalse(homeProbeIsCurrent(7, 7, "game-a", null, cardOpen = false))
        assertFalse(homeProbeIsCurrent(7, 8, "game-a", "game-a", cardOpen = true))
        assertFalse(homeProbeIsCurrent(7, 7, "game-a", "game-b", cardOpen = true))
    }

    @Test
    fun `rejected preflight wins on every later probe posture`() {
        repeat(3) {
            val state = retroAchievementsPreflightState(
                supported = true,
                keyState = com.gruning.mimir.rewrite.core.model.RaKeyState.REJECTED_THIS_SESSION,
                hasUsableCredentialsForSession = false,
                connectedServicesOptedIn = true,
            )
            assertEquals(
                MimirAchievementUnresolvedReason.CREDENTIALS_REJECTED,
                (state as MimirAchievementLaneState.Unresolved).reason,
            )
        }
    }
    @Test
    fun `the ceiling admits every cartridge and refuses focus-stalling reads`() {
        assertTrue(retroAchievementsHashable(RETRO_ACHIEVEMENTS_HASH_CEILING_BYTES))
        assertFalse(retroAchievementsHashable(RETRO_ACHIEVEMENTS_HASH_CEILING_BYTES + 1))
        assertEquals(67_108_864L, RETRO_ACHIEVEMENTS_HASH_CEILING_BYTES)
        assertTrue(retroAchievementsHashable(1L))
        assertFalse(retroAchievementsHashable(0L))
        assertFalse(retroAchievementsHashable(-1L))
        assertFalse(retroAchievementsHashable(700L * 1024 * 1024))
        assertTrue("seek readers admit disc-sized files", retroAchievementsHashable(700L * 1024 * 1024, seekReader = true))
        assertFalse(retroAchievementsHashable(0L, seekReader = true))
        assertTrue(mimirAchievementCardState(retroAchievementsOversizeState()) is MimirAchievementCardState.Unknown)
    }

    @Test
    fun `the cache key is per file and invalidates when bytes could change`() {
        val base = retroAchievementsFileKey("content://roms/a.sfc", null, 4_194_304L, 1_700_000_000_000L)
        assertEquals(base, retroAchievementsFileKey("content://roms/a.sfc", null, 4_194_304L, 1_700_000_000_000L))
        assertNotEquals(base, retroAchievementsFileKey("content://roms/b.sfc", null, 4_194_304L, 1_700_000_000_000L))
        assertNotEquals(base, retroAchievementsFileKey("content://roms/a.sfc", null, 4_194_305L, 1_700_000_000_000L))
        assertNotEquals(base, retroAchievementsFileKey("content://roms/a.sfc", null, 4_194_304L, 1_700_000_000_001L))
        // A file whose time is unknown is never keyed by its size alone (a same-size replacement would keep the old key): it has no key, so it is hashed afresh.
        assertNull(retroAchievementsFileKey("content://roms/a.sfc", null, 4_194_304L, 0L))
        assertNull(retroAchievementsFileKey(null, "/storage/rom.sfc", 1024L, -1L))
        assertNull(retroAchievementsFileKey(null, null, 1024L, 1L))
    }

    @Test
    fun `CHD engine routes bypass cache until the complete verified chain is witnessed`() {
        val file = retroAchievementsFileKey(
            "content://roms/game.m3u",
            null,
            64L,
            1_700_000_000_000L,
        )

        assertNull(retroAchievementsCacheKeyForRoute(file, usesChdChainEngine = true))
        assertEquals(file, retroAchievementsCacheKeyForRoute(file, usesChdChainEngine = false))
        assertNull(retroAchievementsCacheKeyForRoute(null, usesChdChainEngine = false))
    }

    @Test
    fun `verdict cache is bounded LRU and remembers misses`() {
        val cache = RetroAchievementsVerdictCache(maxEntries = 3)
        cache.put("a", RetroAchievementsVerdict.NotMatched)
        cache.put("b", RetroAchievementsVerdict.Matched(7))
        cache.put("c", RetroAchievementsVerdict.RecognizedNoSet)
        assertEquals(RetroAchievementsVerdict.NotMatched, cache.get("a"))
        cache.put("d", RetroAchievementsVerdict.NotMatched)
        assertEquals(3, cache.size())
        assertEquals(RetroAchievementsVerdict.NotMatched, cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `only answered file claims are cacheable`() {
        assertEquals(
            RetroAchievementsVerdict.Matched(4111),
            retroAchievementsVerdictOf(
                RaMatchOutcome.Answered(RetroAchievementsMatch.Matched(4111)),
            ),
        )
        assertEquals(
            RetroAchievementsVerdict.NotMatched,
            retroAchievementsVerdictOf(RaMatchOutcome.Answered(RetroAchievementsMatch.NotMatched)),
        )
        assertEquals(
            RetroAchievementsVerdict.RecognizedNoSet,
            retroAchievementsVerdictOf(RaMatchOutcome.Answered(RetroAchievementsMatch.RecognizedNoSet)),
        )
        assertNull(retroAchievementsVerdictOf(RaMatchOutcome.CredentialsRejected))
        assertNull(retroAchievementsVerdictOf(RaMatchOutcome.NoAnswer(RaNoAnswerReason.TRANSPORT)))
    }

    @Test
    fun `every unresolved posture is unknown and has no count`() {
        MimirAchievementUnresolvedReason.entries.forEach { reason ->
            val state = MimirAchievementLaneState.Unresolved(reason)
            assertTrue("$reason", mimirAchievementCardState(state) is MimirAchievementCardState.Unknown)
            assertNull("$reason", mimirAchievementLaneLabel(state))
        }
        assertEquals(
            MimirAchievementCopy.NoAnswerYet,
            (mimirAchievementCardState(null) as MimirAchievementCardState.Unknown).reason,
        )
    }

    @Test
    fun `credential rejection and network silence never become file misses`() {
        val rejected = retroAchievementsLaneState(
            RaMatchOutcome.CredentialsRejected,
            RaProgressOutcome.Unavailable,
        )
        val unreachable = retroAchievementsLaneState(
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.TRANSPORT),
            RaProgressOutcome.Unavailable,
        )
        assertEquals(MimirAchievementUnresolvedReason.CREDENTIALS_REJECTED, (rejected as MimirAchievementLaneState.Unresolved).reason)
        assertEquals(MimirAchievementUnresolvedReason.UNREACHABLE, (unreachable as MimirAchievementLaneState.Unresolved).reason)
        assertFalse(mimirAchievementCardState(rejected) is MimirAchievementCardState.NotMatched)
        assertFalse(mimirAchievementCardState(unreachable) is MimirAchievementCardState.NotMatched)
        val progressRejected = retroAchievementsLaneState(
            RaMatchOutcome.Answered(RetroAchievementsMatch.NotMatched),
            RaProgressOutcome.CredentialsRejected,
        )
        assertEquals(
            MimirAchievementUnresolvedReason.CREDENTIALS_REJECTED,
            (progressRejected as MimirAchievementLaneState.Unresolved).reason,
        )
    }

    @Test
    fun `every no-answer reason keeps its own honest app posture`() {
        val expected = mapOf(
            RaNoAnswerReason.UNCONFIGURED to MimirAchievementUnresolvedReason.NO_ANSWER_YET,
            RaNoAnswerReason.UNSUPPORTED_SYSTEM to MimirAchievementUnresolvedReason.SYSTEM_UNSUPPORTED,
            RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE to
                MimirAchievementUnresolvedReason.IDENTIFICATION_UNAVAILABLE,
            RaNoAnswerReason.CONSOLE_UNMAPPED to MimirAchievementUnresolvedReason.SYSTEM_UNMAPPED,
            RaNoAnswerReason.FILE_UNAVAILABLE to MimirAchievementUnresolvedReason.FILE_UNAVAILABLE,
            RaNoAnswerReason.TRANSPORT to MimirAchievementUnresolvedReason.UNREACHABLE,
            RaNoAnswerReason.PROVIDER_DATA to MimirAchievementUnresolvedReason.PROVIDER_DATA_INCOMPLETE,
        )

        expected.forEach { (reason, unresolvedReason) ->
            val state = retroAchievementsLaneState(
                RaMatchOutcome.NoAnswer(reason),
                RaProgressOutcome.Unavailable,
            ) as MimirAchievementLaneState.Unresolved
            assertEquals(reason.name, unresolvedReason, state.reason)
        }
    }

    @Test
    fun `unsupported no-credential opted-out and rejected remain distinct`() {
        val reasons = listOf(
            retroAchievementsUnsupportedState(),
            retroAchievementsIdentificationUnavailableState(),
            retroAchievementsNoCredentialsState(),
            retroAchievementsOptedOutState(),
            retroAchievementsRejectedState(),
        ).map { (it as MimirAchievementLaneState.Unresolved).reason }
        assertEquals(
            listOf(
                MimirAchievementUnresolvedReason.SYSTEM_UNSUPPORTED,
                MimirAchievementUnresolvedReason.IDENTIFICATION_UNAVAILABLE,
                MimirAchievementUnresolvedReason.NO_CREDENTIALS,
                MimirAchievementUnresolvedReason.OPTED_OUT,
                MimirAchievementUnresolvedReason.CREDENTIALS_REJECTED,
            ),
            reasons,
        )
    }

    @Test
    fun `recognized no set says exactly that and differs from an ordinary miss`() {
        val recognized = retroAchievementsLaneState(
            RaMatchOutcome.Answered(RetroAchievementsMatch.RecognizedNoSet),
            RaProgressOutcome.Unavailable,
        )
        val miss = retroAchievementsLaneState(
            RaMatchOutcome.Answered(RetroAchievementsMatch.NotMatched),
            RaProgressOutcome.Unavailable,
        )
        assertEquals(
            MimirAchievementCopy.RecognizedNoSet,
            (mimirAchievementCardState(recognized) as MimirAchievementCardState.NotMatched).detail,
        )
        assertEquals(
            MimirAchievementCopy.NoSetForGame,
            (mimirAchievementCardState(miss) as MimirAchievementCardState.NotMatched).detail,
        )
        assertNotEquals(recognized, miss)
        assertNull(mimirAchievementLaneLabel(recognized))
    }

    @Test
    fun `matched progress shows a count and orders earned medals first`() {
        val progress = RetroAchievementsProgress(
            gameId = 4111,
            title = "Super Metroid",
            achievements = listOf(
                achievement(1, "Locked big", 50, false),
                achievement(2, "Earned small", 5, true),
                achievement(3, "Hardcore", 10, true, true),
                achievement(4, "Locked small", 1, false),
            ),
        )
        val state = retroAchievementsLaneState(
            RaMatchOutcome.Answered(RetroAchievementsMatch.Matched(4111)),
            RaProgressOutcome.Read(progress),
        )
        assertEquals("2/4", mimirAchievementLaneLabel(state))
        val card = mimirAchievementCardState(state) as MimirAchievementCardState.Loaded
        assertEquals(listOf(3, 2, 1, 4), card.rows.map { it.id })
        assertEquals(MimirMedalState.EARNED_HARDCORE, card.rows[0].medal)
        assertEquals(15, card.earnedPoints)
        assertEquals(66, card.totalPoints)
    }

    @Test
    fun `zero unlocked in a known nonempty set is real progress`() {
        val progress = RetroAchievementsProgress(
            gameId = 9,
            title = "Untouched",
            achievements = listOf(achievement(1, "First", 5, false)),
        )
        val state = retroAchievementsLaneState(
            RaMatchOutcome.Answered(RetroAchievementsMatch.Matched(9)),
            RaProgressOutcome.Read(progress),
        )
        assertEquals("0/1", mimirAchievementLaneLabel(state))
    }

    @Test
    fun `empty wrong-game unavailable and rejected progress never fabricate a count`() {
        val matched = RaMatchOutcome.Answered(RetroAchievementsMatch.Matched(9))
        val empty = RetroAchievementsProgress(gameId = 9, title = "Empty", achievements = emptyList())
        val wrong = RetroAchievementsProgress(
            gameId = 10,
            title = "Wrong",
            achievements = listOf(achievement(1, "First", 5, false)),
        )
        val states = listOf(
            retroAchievementsLaneState(matched, RaProgressOutcome.Read(empty)),
            retroAchievementsLaneState(matched, RaProgressOutcome.Read(wrong)),
            retroAchievementsLaneState(matched, RaProgressOutcome.Unavailable),
            retroAchievementsLaneState(matched, RaProgressOutcome.CredentialsRejected),
        )
        states.forEach { assertNull(mimirAchievementLaneLabel(it)) }
        assertEquals(
            MimirAchievementUnresolvedReason.CREDENTIALS_REJECTED,
            (states.last() as MimirAchievementLaneState.Unresolved).reason,
        )
    }

    private fun achievement(
        id: Int,
        title: String,
        points: Int,
        unlocked: Boolean,
        hardcore: Boolean = false,
    ) = RetroAchievementsAchievement(
        id = id,
        title = title,
        description = "desc $id",
        points = points,
        unlocked = unlocked,
        unlockedHardcore = hardcore,
    )
}
