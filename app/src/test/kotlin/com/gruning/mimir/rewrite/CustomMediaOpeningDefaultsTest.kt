package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where Customize media opens and what its chips say when it does. The page opens on Icon art from every door, the Library hero's included ( this retires the 2026-08-14 Hero-art
 * landing); and every filter of every type opens on its all/any value, the first of its list, so a first search narrows nothing the player did not ask for.
 */
class CustomMediaOpeningDefaultsTest {

    private fun menu(
        entryPoint: HomeTileMenuEntryPoint = HomeTileMenuEntryPoint.EDIT,
        platformId: PlatformId = PlatformId.SWITCH,
    ) = HomeTileMenuState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("switch/totk")),
            rank = 1L,
            addedAtMillis = 10L,
            title = "The Legend of Zelda Tears of the Kingdom",
            platformId = platformId,
        ),
        removable = true,
        entryPoint = entryPoint,
    )

    private val icon = CustomMediaSelection(EnrichMediaKind.ICON, MediaState.SHELF)

    @Test
    fun `Customize media opens on Icon art from the game card and the Edit menu`() {
        assertEquals(icon, customMediaOpening(menu(HomeTileMenuEntryPoint.EDIT))?.selection)
        assertEquals("Icon art leads the Media chip's list", "Icon art", homeTileCustomMediaTypes(menu()).first().label)
        assertEquals(icon, customMediaEntryDefault(HomeTileMenuEntryPoint.EDIT))
    }

    @Test
    fun `the Library hero door opens on Icon art too`() {
        assertEquals(icon, customMediaOpening(menu(HomeTileMenuEntryPoint.LIBRARY_HERO))?.selection)
        assertEquals(icon, customMediaEntryDefault(HomeTileMenuEntryPoint.LIBRARY_HERO))
    }

    /** What each chip of [type] reads when the page opens: "Style · Any". */
    private fun openingChips(type: String, state: HomeTileMenuState = menu()): List<String> {
        val types = homeTileCustomMediaTypes(state)
        val selection = types.first { it.label == type }
        return customMediaSeatingChips(selection, customMediaTypeFilters(state, types).getValue(selection.selection))
            .map { "${it.label} · ${it.selectedDisplay}" }
    }

    @Test
    fun `Icon art opens with every filter on all or any`() {
        // Style opened on "Clean" and so narrowed the first search; it opens on its all value, drawn "Any".
        assertEquals(listOf("Type · All", "Region · Any", "Style · Any", "Size · Any"), openingChips("Icon art"))
        assertEquals(listOf("All", "Any", "All", "Any"), homeCustomEnrichIconArtFilters().map { it.selected })
        // A PC game's list is shorter (no ScreenScraper, so no Region) and is just as open.
        assertEquals(
            listOf("Type · All", "Style · Any", "Size · Any"),
            openingChips("Icon art", menu(platformId = PlatformId.PC_GAMES)),
        )
    }

    @Test
    fun `Hero art opens on both sources, any size and any fit, and the other types are as open`() {
        // Fit opened on "Mimir" (drawn "Best fit"): the aspect range is a value to pick, not the opening one.
        assertEquals(listOf("Source · Both", "Size · Any", "Fit · Any"), openingChips("Hero art"))
        assertEquals(listOf("Source · Both", "Format · All"), openingChips("Hero Motion"))
        assertEquals(listOf("Type · All", "Region · Any"), openingChips("Launch card"))
    }

    @Test
    fun `every filter of every kind opens on its first value, which is its all or any one`() {
        val open = setOf("All", "Any", "Both")
        val everything = EnrichMediaKind.entries.flatMap { homeCustomEnrichDefaultFilters(it) } + homeCustomEnrichIconArtFilters()
        everything.forEach { filter ->
            assertEquals("${filter.key} opens on its first value", 0, filter.selectedIndex)
            assertEquals("${filter.key} opens on an all/any value, not '${filter.selected}'", true, filter.selected in open)
        }
        // The one exception is PC provider routing: a PC game's Source opens on SteamGridDB, the only provider that answers for it.
        assertEquals("SteamGridDB", homeCustomEnrichDefaultFilters(EnrichMediaKind.HERO, preferSteamGridDb = true).first { it.key == "source" }.selected)
        assertEquals(listOf("All", "Clean", "Material", "Alternate"), homeCustomEnrichIconArtFilters().first { it.key == "style" }.values)
        assertEquals(listOf("Any", "Mimir", "Wide"), homeCustomEnrichDefaultFilters(EnrichMediaKind.HERO).first { it.key == "fit" }.values)
    }
}
