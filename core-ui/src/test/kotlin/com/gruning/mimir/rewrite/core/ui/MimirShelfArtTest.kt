package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirShelfArtTest {
    @Test
    fun authoredShelfEffectsStayOnTheirShippingColors() {
        assertEquals(Color(0xFFD8B24E), MimirShelfArt.BookBindingGold)
        assertEquals(Color(0xFFFF6A00), MimirShelfArt.SurtrMoltenFloor)
        assertEquals(Color(0xFF7C3A7E), MimirShelfArt.NiflheimMoltenFloor)
        assertEquals(Color(0xFFFFCF6B), MimirShelfArt.SurtrMoltenCrestStops[5].second)
        assertEquals(Color(0xFF7C3A7E), MimirShelfArt.NiflheimMoltenCrestStops[5].second)
        assertEquals(Color.White, MimirShelfArt.SurtrMoltenCoreStops[2].second)
        assertEquals(Color(0x99C8B4DC), MimirShelfArt.NiflheimMoltenCoreStops[2].second)
    }

    @Test
    fun moltenLookupCoversAllNineThemes() {
        // Design update 2026-07-12 (+A2 molten on ALL 9 themes); FUX-D promoted the parked sets.
        MimirThemeId.entries.forEach { id ->
            val art = MimirShelfArt.moltenSeparatorFor(id)
            assertEquals(id.name, true, art.crestStops.isNotEmpty())
            assertEquals(id.name, true, art.coreStops.isNotEmpty())
        }
    }

    @Test
    fun moltenLookupPinsEachThemeToItsOwnAuthoredMaterial() {
        // Non-emptiness alone would let a copy-paste when-branch hand every theme Surtr's stops. The
        // FLOOR is each set's signature color: pin it per theme and require the nine to stay distinct.
        assertEquals(MimirShelfArt.SurtrMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.SURTR).floor)
        assertEquals(MimirShelfArt.NiflheimMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.NIFLHEIM).floor)
        assertEquals(MimirShelfArt.DefaultMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.DEFAULT).floor)
        assertEquals(MimirShelfArt.FreyrMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.FREYR).floor)
        assertEquals(MimirShelfArt.ManiMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.MANI).floor)
        assertEquals(MimirShelfArt.HelMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.HEL).floor)
        assertEquals(MimirShelfArt.YggdrasilMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.YGGDRASIL).floor)
        assertEquals(MimirShelfArt.FolkvangrMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.FOLKVANGR).floor)
        assertEquals(MimirShelfArt.BaldrMoltenFloor, MimirShelfArt.moltenSeparatorFor(MimirThemeId.BALDR).floor)

        val floors = MimirThemeId.entries.map { MimirShelfArt.moltenSeparatorFor(it).floor }
        assertTrue("nine molten floors must stay pairwise distinct", floors.distinct().size == floors.size)
    }
}
