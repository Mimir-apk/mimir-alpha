package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.resolver.library.PlatformLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A console is called by one name on every page : the achievements page's console chip and list say what the shelf's lattice says ("Genesis / Mega Drive", with the
 * spaces), through the lattice's own name function, and a console the lattice has no platform for keeps RetroAchievements' words.
 */
class ActivityConsoleLookTest {

    @Test
    fun `Genesis is called by the lattice's name, with the spaces, and drawn by the lattice's own id`() {
        val look = activityConsoleLook("Genesis/Mega Drive")
        assertEquals("Genesis / Mega Drive", look.display)
        assertEquals("the lattice's own label function says the same", PlatformLabels.labelFor(PlatformId("megadrive")), look.display)
        assertEquals("megadrive", look.artId)
    }

    @Test
    fun `each console is called by the name the lattice has for its platform`() {
        mapOf(
            "SNES" to "Super Nintendo",
            "SNES/Super Famicom" to "Super Nintendo",
            "Game Boy" to "Game Boy",
            "Game Boy Advance" to "Game Boy Advance",
            "Nintendo 64" to "Nintendo 64",
            "PlayStation" to "PlayStation",
            "PlayStation 2" to "PlayStation 2",
            "PlayStation Portable" to "PSP",
            "Saturn" to "Saturn",
            "Master System" to "Master System",
            "32X" to "Sega 32X",
            "Sega CD" to "Sega CD",
            "PC Engine/TurboGrafx-16" to "PC Engine / TurboGrafx-16",
            "PC Engine CD/TurboGrafx-CD" to "PC Engine CD",
        ).forEach { (ra, lattice) -> assertEquals("$ra is $lattice on the lattice", lattice, activityConsoleLook(ra).display) }
    }

    @Test
    fun `a console the lattice has no platform for keeps RetroAchievements' words and has no glyph of its own`() {
        val look = activityConsoleLook("Hubs")
        assertEquals("Hubs", look.display)
        assertNull(look.artId)
    }

    @Test
    fun `two RetroAchievements consoles that are one platform on the lattice keep their own words, so the list can tell them apart`() {
        val looks = activityConsoleLooks(listOf("NES/Famicom", "Famicom Disk System", "Nintendo DS", "Nintendo DSi", "Game Boy"))
        assertEquals("NES/Famicom", looks.getValue("NES/Famicom").display)
        assertEquals("Famicom Disk System", looks.getValue("Famicom Disk System").display)
        assertEquals("Nintendo DS", looks.getValue("Nintendo DS").display)
        assertEquals("Nintendo DSi", looks.getValue("Nintendo DSi").display)
        assertEquals("a console that is alone keeps the lattice's name", "Game Boy", looks.getValue("Game Boy").display)
        assertEquals("and the platform's glyph stays for the ones that kept their words", "nes", looks.getValue("NES/Famicom").artId)
    }

    @Test
    fun `a console that is the only one of its platform is called by the lattice's name even when another has a similar name`() {
        val looks = activityConsoleLooks(listOf("NES/Famicom", "SNES"))
        assertEquals("NES", looks.getValue("NES/Famicom").display)
        assertEquals("Super Nintendo", looks.getValue("SNES").display)
    }

    @Test
    fun `the index lists the consoles in RetroAchievements' order, each with the lattice's name beside it`() {
        fun game(n: Int, console: String) = CompanionActivityAchievementSnapshot(
            gameId = "g$n",
            account = "me",
            unlocked = 1,
            total = 10,
            gameTitle = "Game $n",
            checkedAtEpochMillis = 1L,
            rowsKnown = false,
            raGameId = 100 + n,
            consoleName = console,
        )
        val index = ActivityAchievementIndex(listOf(game(1, "SNES"), game(2, "Genesis/Mega Drive"), game(3, "Game Boy"), game(4, "Hubs")))
        assertEquals(
            "A to Z by RetroAchievements' names, which is what the filter matches by",
            listOf("Game Boy", "Genesis/Mega Drive", "Hubs", "SNES"),
            index.consoles.map { it.name },
        )
        assertEquals("Genesis / Mega Drive", index.consoleLooks.getValue("Genesis/Mega Drive").display)
        assertEquals("Super Nintendo", index.consoleLooks.getValue("SNES").display)
    }
}
