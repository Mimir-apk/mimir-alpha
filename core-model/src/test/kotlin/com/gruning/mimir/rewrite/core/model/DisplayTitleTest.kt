package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases drawn from the reference Thor library (2026-07-07): DAT-matched games carry
 * verbose No-Intro/Redump region tags; un-matched PS2 games fall back to smashed camelCase filenames.
 */
class DisplayTitleTest {

    @Test fun `strips region and language parentheticals`() {
        assertEquals("Sonic Adventure 2", DisplayTitle.clean("Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es)"))
        assertEquals("maintainer's Inferno", DisplayTitle.clean("maintainer's Inferno (USA)"))
        assertEquals(
            "The Legend of Zelda - Oracle of Seasons", // article folded back
            DisplayTitle.clean("Legend of Zelda, The - Oracle of Seasons (USA, Australia)"),
        )
        assertEquals("Zelda II - The Adventure of Link", DisplayTitle.clean("Zelda II - The Adventure of Link (USA)"))
    }

    @Test fun `folds the No-Intro trailing article back to the front`() {
        assertEquals("The Legend of Zelda - Oracle of Ages", DisplayTitle.clean("Legend of Zelda, The - Oracle of Ages"))
        assertEquals("The Legend of Zelda - Majora's Mask 3D", DisplayTitle.clean("Legend of Zelda, The - Majora's Mask 3D"))
        assertEquals("The Legend of Zelda - A Link Between Worlds", DisplayTitle.clean("Legend of Zelda, The - A Link Between Worlds"))
        assertEquals("The Incredibles", DisplayTitle.clean("Incredibles, The"))
        assertEquals("A Boy and His Blob", DisplayTitle.clean("Boy and His Blob, A"))
        assertEquals("Les Chevaliers", DisplayTitle.clean("Chevaliers, Les"))
        // already natural / no article → untouched
        assertEquals("The Legend of Zelda - Oracle of Ages", DisplayTitle.clean("The Legend of Zelda - Oracle of Ages"))
        assertEquals("Metal Gear Solid 2 - Sons of Liberty", DisplayTitle.clean("Metal Gear Solid 2 - Sons of Liberty"))
        // not an article — a real trailing comma clause is left alone
        assertEquals("Ratchet & Clank, Going Commando", DisplayTitle.clean("Ratchet & Clank, Going Commando"))
    }

    @Test fun `clean is idempotent`() {
        for (raw in listOf(
            "Legend of Zelda, The - Oracle of Ages",
            "Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es)",
            "Incredibles, The",
            "The Legend of Zelda - Oracle of Ages",
        )) {
            val once = DisplayTitle.clean(raw)
            assertEquals(once, DisplayTitle.clean(once))
        }
    }

    @Test fun `strips revision and bracket tags`() {
        assertEquals("Some Game", DisplayTitle.clean("Some Game (USA) (Rev 2)"))
        assertEquals("Some Game", DisplayTitle.clean("Some Game (USA) [!]"))
        assertEquals("Pokemon Legends Z-A", DisplayTitle.clean("Pokemon Legends Z-A v1.0.0[0100F43008C44000][v0][APP]"))
    }

    @Test fun `strips a No-Intro numeric index but preserves a numeric game title`() {
        assertEquals(
            "Pokemon Dash",
            DisplayTitle.clean("0119 - Pokemon Dash (Europe) (En,Fr,De,Es,It)"),
        )
        assertEquals("The Legend of Zelda - Phantom Hourglass", DisplayTitle.clean("1234-The Legend of Zelda - Phantom Hourglass"))
        assertEquals("1942", DisplayTitle.clean("1942"))
    }

    @Test fun `strips known enhancement and collection noise parentheticals`() {
        assertEquals("Pokemon - Red Version", DisplayTitle.clean("Pokemon - Red Version (USA, Europe) (SGB Enhanced)"))
        assertEquals("Pokemon - Black Version", DisplayTitle.clean("Pokemon - Black Version (NDSi Enhanced)"))
        assertEquals("Some Game", DisplayTitle.clean("Some Game (Enhanced) (Rumble Version)"))
        assertEquals("Arcade Classics", DisplayTitle.clean("Arcade Classics (Collection)"))
    }

    @Test fun `canonical region vocabulary covers every display region token`() {
        assertEquals("USA, Europe", DisplayTitle.canonicalRegion("USA, Europe"))
        assertEquals("Asia", DisplayTitle.canonicalRegion("Asia"))
        assertEquals("Taiwan", DisplayTitle.canonicalRegion("Taiwan"))
        assertEquals("Netherlands", DisplayTitle.canonicalRegion("Netherlands"))
        assertEquals("Scandinavia", DisplayTitle.canonicalRegion("Scandinavia"))
        assertNull(DisplayTitle.canonicalRegion("En"))
        assertNull(DisplayTitle.canonicalRegion("Unknown"))
    }

    @Test fun `keeps a substantive parenthetical like disc`() {
        assertEquals("Final Fantasy VII (Disc 2)", DisplayTitle.clean("Final Fantasy VII (USA) (Disc 2)"))
    }

    @Test fun `spaces smashed camelCase filenames`() {
        assertEquals("Dark Cloud", DisplayTitle.clean("DarkCloud"))
        assertEquals("Dark Cloud 2", DisplayTitle.clean("DarkCloud2"))
        assertEquals("Devil May Cry 2 Disc 2", DisplayTitle.clean("DevilMayCry2Disc2"))
        assertEquals("Fatal Frame", DisplayTitle.clean("FatalFrame"))
        assertEquals("Kingdom Hearts 2 Final Mix", DisplayTitle.clean("KingdomHearts2FinalMix"))
        assertEquals("Resident Evil Outbreak File 2", DisplayTitle.clean("ResidentEvilOutbreakFile2"))
    }

    @Test fun `keeps acronym runs together`() {
        assertEquals("Dirge Of Cerberus FFVII", DisplayTitle.clean("DirgeOfCerberusFFVII"))
    }

    @Test fun `leaves an already-clean title untouched`() {
        assertEquals("The Legend of Zelda: The Wind Waker", DisplayTitle.clean("The Legend of Zelda: The Wind Waker"))
        assertEquals("Okami", DisplayTitle.clean("Okami"))
        assertEquals("Gurumin - A Monstrous Adventure", DisplayTitle.clean("Gurumin - A Monstrous Adventure (USA)"))
    }

    @Test fun `never returns blank`() {
        assertEquals("(USA)", DisplayTitle.clean("(USA)")) // pathological: nothing but a tag → keep raw
    }

    // ── stripDiscTag — SH-F1: a folded multi-disc GROUP row reads as ONE game ──────────────────────

    @Test fun `stripDiscTag removes a parenthetical disc token`() {
        assertEquals("Chrono Cross", DisplayTitle.stripDiscTag("Chrono Cross (Disc 2)"))
        assertEquals("Final Fantasy IX", DisplayTitle.stripDiscTag("Final Fantasy IX (Disc 3)"))
        assertEquals("Panzer Dragoon Saga", DisplayTitle.stripDiscTag("Panzer Dragoon Saga (CD 1)"))
        assertEquals("Riven", DisplayTitle.stripDiscTag("Riven (Disk 4)"))
        assertEquals("Metal Gear Solid", DisplayTitle.stripDiscTag("Metal Gear Solid (Disc 1 of 2)"))
        assertEquals("Lunar 2", DisplayTitle.stripDiscTag("Lunar 2 (Disc II)"))
    }

    @Test fun `stripDiscTag removes a bare trailing disc token`() {
        // The smashed-filename form clean() produces ("DevilMayCry2Disc2" → "Devil May Cry 2 Disc 2").
        assertEquals("Devil May Cry 2", DisplayTitle.stripDiscTag("Devil May Cry 2 Disc 2"))
        assertEquals("Chrono Cross", DisplayTitle.stripDiscTag("Chrono Cross - Disc 1"))
    }

    @Test fun `stripDiscTag never touches a real name`() {
        assertEquals("Discworld", DisplayTitle.stripDiscTag("Discworld"))         // "Disc" inside a word
        assertEquals("Discworld 2", DisplayTitle.stripDiscTag("Discworld 2"))     // no separator+token form
        assertEquals("CD-Man", DisplayTitle.stripDiscTag("CD-Man"))               // no trailing numeral
        assertEquals("Okami", DisplayTitle.stripDiscTag("Okami"))
    }

    @Test fun `disc tag detection distinguishes physical disc labels from real names`() {
        assertTrue(DisplayTitle.hasDiscTag("Resident Evil 2 (Disc 2)"))
        assertTrue(DisplayTitle.hasDiscTag("Chrono Cross - CD II"))
        assertTrue(DisplayTitle.hasDiscTag("D (Side B)"))
        assertTrue(DisplayTitle.hasDiscTag("D - Side A"))
        assertFalse(DisplayTitle.hasDiscTag("Discworld 2"))
        assertFalse(DisplayTitle.hasDiscTag("Resident Evil 2"))
    }

    @Test fun `folded side labels become disc neutral`() {
        assertEquals("D", DisplayTitle.stripDiscTag("D (Side B)"))
        assertEquals("D", DisplayTitle.stripDiscTag("D - Side A"))
    }

    @Test fun `stripDiscTag is idempotent and never blank`() {
        assertEquals("Chrono Cross", DisplayTitle.stripDiscTag(DisplayTitle.stripDiscTag("Chrono Cross (Disc 2)")))
        assertEquals("(Disc 2)", DisplayTitle.stripDiscTag("(Disc 2)")) // pathological: only a tag → keep raw
    }

    @Test fun `canonical group stamp is disc neutral only for folded multi-disc groups`() {
        assertEquals(
            "Chrono Cross (USA, Canada)",
            DisplayTitle.canonicalForGroup(
                "Chrono Cross (USA, Canada) (Disc 2)",
                DuplicateGroupKind.MULTI_DISC,
            ),
        )
        assertEquals(
            "Resident Evil 2 - Dual Shock Ver. (USA)",
            DisplayTitle.canonicalForGroup(
                "Resident Evil 2 - Dual Shock Ver. (USA) (Disc 1)",
                DuplicateGroupKind.MULTI_DISC,
            ),
        )
        assertEquals(
            "Xenogears (USA)",
            DisplayTitle.canonicalForGroup("Xenogears (USA) (Disc 1)", DuplicateGroupKind.MULTI_DISC),
        )

        val loneDisc = "Resident Evil - Code Veronica X (USA) (Disc 2)"
        assertEquals(loneDisc, DisplayTitle.canonicalForGroup(loneDisc, null))
        assertEquals(loneDisc, DisplayTitle.canonicalForGroup(loneDisc, DuplicateGroupKind.CATALOG_TITLE_ID))
        assertEquals(loneDisc, DisplayTitle.canonicalForGroup(loneDisc, DuplicateGroupKind.EXACT_HASH))
        assertEquals(loneDisc, DisplayTitle.canonicalForGroup(loneDisc, DuplicateGroupKind.CROSS_STORAGE))
    }

    @Test fun `folded-group display pipeline cleans the exact Thor canonicalTitles`() {
        // G-1 verification 2026-07-14: the EXACT canonicalTitle values from Thor's walk DB snapshot.
        // The DAT stamp puts ONE disc-tagged name on every member (m3u primary included), so the folded
        // row's display MUST run clean() + stripDiscTag() — this is the full toGameRow title pipeline.
        assertEquals(
            "Chrono Cross",
            DisplayTitle.stripDiscTag(DisplayTitle.clean("Chrono Cross (USA, Canada) (Disc 2)")),
        )
        assertEquals(
            "Resident Evil 2 - Dual Shock Ver.",
            DisplayTitle.stripDiscTag(DisplayTitle.clean("Resident Evil 2 - Dual Shock Ver. (USA) (Disc 1)")),
        )
        assertEquals(
            "Xenogears",
            DisplayTitle.stripDiscTag(DisplayTitle.clean("Xenogears (USA) (Disc 1)")),
        )
    }
}
