package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which music folders are a platform's (the earlier update review's P1): the answer is the platform registry's own folder index, the one the game scanner reads ROM folders by, so an
 * artist's folder is plain music whatever its name looks like. The scan's own rules are in [com.gruning.mimir.rewrite.core.audio.MusicLibraryIndexTest]; this pins the real
 * registry's answers for the names the Custom music folder meets.
 */
class MusicPlatformFolderNamesTest {
    private fun idFor(name: String) = AppMusicPlatformFolders.idFor(name)

    @Test
    fun `the names a platform's music lives under are platforms, in any letter case`() {
        assertEquals("snes", idFor("snes"))
        assertEquals("snes", idFor("SNES"))
        assertEquals("ps2", idFor("PS2"))
        assertEquals("ps2", idFor("ps2"))
        assertTrue("psp is a platform: ${idFor("psp")}", idFor("psp") != null)
        assertTrue("pc_games is a platform: ${idFor("pc_games")}", idFor("pc_games") != null)
        assertEquals("the same platform whichever way the folder is spelled", idFor("pc_games"), idFor("pcgames"))
    }

    @Test
    fun `an artist's folder is not a platform, however short or plain its name`() {
        val artists = listOf(
            "Queen", "ABBA", "U2", "Radiohead", "Jay-Z", "Nirvana", "Metallica", "Madonna", "Prince", "Eminem", "Adele", "Coldplay", "Muse", "Oasis", "Blur", "Pulp", "Cream",
            "Rush", "Tool", "Slayer", "Pantera", "Vangelis", "Enya", "Bjork", "Moby", "Sade", "Seal", "Cher", "Shakira", "Drake", "Lorde", "Sia", "Weezer", "Beatles", "The Beatles",
            "Pink Floyd", "Led Zeppelin", "Daft Punk", "Kraftwerk", "Air", "Portishead", "Massive Attack",
        )
        val hits = artists.filter { idFor(it) != null }
        assertTrue("artists the registry reads as platforms: $hits", hits.isEmpty())
    }

    @Test
    fun `nothing and nonsense are not platforms`() {
        assertNull(idFor(""))
        assertNull(idFor("   "))
        assertNull(idFor("Album 1"))
        assertNull(idFor("Rock (live)"))
        assertNull(idFor("_edge"))
        assertNull(idFor(".git"))
    }
}
