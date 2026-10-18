package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FUX-05 — SGDB search feeds the game IDENTITY, not the file label. */
class SgdbSearchCandidatesTest {

    @Test
    fun autocompleteValidationRejectsAdjacentGamesAndAcceptsEditionNoise() {
        assertTrue(sgdbTitleMatches("Corpse Party", "Corpse Party"))
        assertFalse(sgdbTitleMatches("Corpse Party", "Corpse Party - Book of Shadows"))
        assertTrue(sgdbTitleMatches("Resident Evil 2 - DualShock", "Resident Evil 2"))
        assertFalse(sgdbTitleMatches("Resident Evil 2 - Nemesis", "Resident Evil 2"))
        assertFalse(sgdbTitleMatches("Corpse Party - Book of Shadows", "Corpse Party"))
        assertFalse(sgdbTitleMatches("Castlevania - Symphony of the Night", "Castlevania"))
        assertFalse(sgdbTitleMatches("Final Fantasy IV", "Final Fantasy III"))
        assertFalse(
            sgdbTitleMatches(
                "Shin Megami Tensei Devil Summoner Soul Hackers",
                "Shin Megami Tensei Devil Summoner Soul Hackers 2",
            ),
        )
    }
    @Test
    fun editionSuffixStripsAfterTheFullTitleMisses() {
        assertEquals(
            listOf("Resident Evil 2 - DualShock", "Resident Evil 2"),
            sgdbSearchCandidates("Resident Evil 2 - DualShock (USA)"),
        )
    }

    @Test
    fun realSubtitleDashesKeepTheFullTitleFirst() {
        assertEquals(
            listOf("Castlevania - Symphony of the Night", "Castlevania"),
            sgdbSearchCandidates("Castlevania - Symphony of the Night [SLUS-00067]"),
        )
    }

    @Test
    fun plainTitlesPassThroughOnce() {
        assertEquals(listOf("Chrono Cross"), sgdbSearchCandidates("Chrono Cross (USA) (Disc 1)"))
    }
}
