package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Customize media's Search sits. The seat is the PAGE's: the widest type's
 * Media chip and chips are seated first and Search takes what they leave, so choosing another type never resizes or moves it. And the name is cut AT A WORD when it does not fit,
 * the 48 dp magnifier only when not even the first word and its ellipsis can stand. All pure: the drawing is [CustomMediaTopRowRenderTest]'s and [CustomMediaSearchSeatRenderTest]'s.
 */
class CustomMediaSearchSeatTest {

    private fun row(itemId: HomeShelfItemId, platformId: PlatformId?, title: String = TotkTitle) =
        HomeShelfRow(itemId = itemId, rank = 1L, addedAtMillis = 10L, title = title, platformId = platformId)

    private val switchRow = row(HomeShelfItemId.Emulated(GameId("switch/totk")), PlatformId.SWITCH)
    private val pcRow = row(HomeShelfItemId.Emulated(GameId("pc/portal")), PlatformId.PC_GAMES, "Portal")
    private val androidRow = row(HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")), null, "Game")

    private fun menu(row: HomeShelfRow) = HomeTileMenuState(row = row, removable = true)

    /** A chip's width in dp for its words, at font [scale]: every glyph the same width, as the row lays chips out (plate padding and the 48 dp floor). */
    private fun chipDpAt(scale: Float): (String) -> Float = { text -> customMediaChipLayoutDp(text.length * 6.4f * scale) }

    /** The page's state as the builder makes it, with [type] chosen. */
    private fun stateOn(menu: HomeTileMenuState, type: CustomMediaType): HomeCustomEnrichState {
        val types = homeTileCustomMediaTypes(menu)
        val chips = customMediaTypeFilters(menu, types)
        return HomeCustomEnrichState(
            sessionId = "seat-${type.label}",
            kind = type.kind,
            filters = chips.getValue(type.selection),
            shelfIconArt = type.isMergedIconArt,
            cacheKey = mediaStorageKey("game-1", type.mediaState),
            gameTitle = menu.row.title,
            queryDraft = menu.row.title,
            mediaState = type.mediaState,
            mediaTypes = types,
            mediaTypeFilters = chips,
        )
    }

    // ── One seat for the page ───────────────────────────────────────────────────────────────────────

    @Test
    fun `Search is seated at one width whichever type is chosen`() {
        listOf(switchRow, pcRow, androidRow).forEach { game ->
            val menu = menu(game)
            val types = homeTileCustomMediaTypes(menu)
            assertTrue("${game.title}: the page offers several types: $types", types.size >= 3)
            listOf(652f, 322f, 560f).forEach { available ->
                listOf(1.0f, 1.3f).forEach { font ->
                    val seatings = types.map { type -> customMediaPageSeating(available, stateOn(menu, type).customMediaPageRooms(chipDpAt(font))) }
                    val where = "${game.title} at ${available}dp, font $font"
                    assertEquals("$where: the same seat from every type: ${seatings.map { it.searchDp }}", 1, seatings.map { it.searchDp }.distinct().size)
                    assertEquals("$where: the plan is the same from every type on the page", 1, seatings.distinct().size)
                }
            }
        }
    }

    @Test
    fun `the seat is narrower than the one the current type would give Search alone`() {
        // Hero art alone (three chips) leaves more than Icon art (the merged picker's four chips) does: this is what shrank Search under the player's finger.
        val menu = menu(switchRow)
        val types = homeTileCustomMediaTypes(menu)
        val chipDp = chipDpAt(1.0f)
        val pageSeat = customMediaPageSeating(652f, stateOn(menu, types.first()).customMediaPageRooms(chipDp)).searchDp
        val alone = types.map { type -> customMediaPageSeating(652f, listOf(customMediaTypeRoom(type, customMediaSeatingChips(type, stateOn(menu, type).filters), chipDp))).searchDp }
        assertTrue("some type alone leaves Search more room than the page does: $alone vs $pageSeat", alone.any { it > pageSeat + 1f })
        assertEquals("and the page's seat is the narrowest of them (the widest group wins), less the rounding", alone.min(), pageSeat, 0.01f)
    }

    @Test
    fun `the seat is what the widest type leaves, and each type keeps its own chip form`() {
        val wide = CustomMediaTypeRoom(mediaDp = 80f, chipFullDp = listOf(100f, 100f, 100f), chipValueDp = listOf(60f, 60f, 60f))
        val narrow = CustomMediaTypeRoom(mediaDp = 90f, chipFullDp = listOf(100f), chipValueDp = listOf(60f))
        // 652 dp: both in full. The wide group is 80 + 300 + three gaps of 4 dp = 392; Search is 652 - 4 - 392 - the 2 dp rounding.
        val roomy = customMediaPageSeating(652f, listOf(narrow, wide))
        assertEquals(listOf(CustomMediaChipForm.FULL, CustomMediaChipForm.FULL), roomy.plans.map { it.chipForm })
        assertEquals(254f, roomy.searchDp, 0.01f)
        assertEquals("the order of the types changes nothing", roomy, customMediaPageSeating(652f, listOf(wide, narrow)).let { it.copy(plans = it.plans.reversed()) })
        // 400 dp: the wide type must drop its labels (80 + 180 + three 4 dp gaps = 272 of the 400 - 4 - 48 left), the narrow keeps its in full, and the seat is still ONE: what the wide one leaves.
        val tight = customMediaPageSeating(400f, listOf(narrow, wide))
        assertEquals(listOf(CustomMediaChipForm.FULL, CustomMediaChipForm.VALUE), tight.plans.map { it.chipForm })
        assertEquals(400f - 4f - 272f - 2f, tight.searchDp, 0.01f)
        // Never below the 48 dp mark: a row too narrow for anything gives Search its floor.
        assertEquals(48f, customMediaPageSeating(150f, listOf(narrow, wide)).searchDp, 0.01f)
    }

    @Test
    fun `a spare room sits beside Search, so the widest group is the only one that touches it`() {
        val wide = CustomMediaTypeRoom(mediaDp = 80f, chipFullDp = listOf(100f, 100f, 100f), chipValueDp = listOf(60f, 60f, 60f))
        val narrow = CustomMediaTypeRoom(mediaDp = 90f, chipFullDp = listOf(100f), chipValueDp = listOf(60f))
        val seating = customMediaPageSeating(652f, listOf(narrow, wide))
        val gap = 4f
        val groups = listOf(narrow, wide).mapIndexed { index, room -> customMediaGroupDp(room, seating.plans[index]) }
        listOf(narrow, wide).indices.forEach { index ->
            val spare = 652f - (seating.searchDp + gap + groups[index])
            assertTrue("room is never taken from Search: spare $spare", spare >= 0f)
        }
        assertEquals("the widest group leaves only the rounding", 2f, 652f - (seating.searchDp + gap + groups.max()), 0.01f)
        assertTrue("a narrower group leaves more", 652f - (seating.searchDp + gap + groups.min()) > 100f)
    }

    @Test
    fun `the seat does not move when the player changes a chip's value`() {
        val menu = menu(switchRow)
        val types = homeTileCustomMediaTypes(menu)
        val chipDp = chipDpAt(1.0f)
        // Source on ScreenScraper is a wider word than Both, and a Type of Square changes which chips the merged picker shows: the seat reads what each type OPENS with.
        val hero = stateOn(menu, types.first { it.kind == EnrichMediaKind.HERO })
        val heroChanged = hero.copy(filters = hero.filters.map { if (it.key == "source") it.copy(selectedIndex = 2) else it })
        assertEquals(hero.customMediaPageRooms(chipDp), heroChanged.customMediaPageRooms(chipDp))
        val icon = stateOn(menu, types.first { it.isMergedIconArt })
        val iconChanged = icon.copy(filters = icon.filters.map { if (it.key == IconArtTypeFilterKey) it.copy(selectedIndex = it.values.indexOf(IconArtType.Square)) else it })
        assertEquals(icon.customMediaPageRooms(chipDp), iconChanged.customMediaPageRooms(chipDp))
    }

    // ── The chips each type opens with ──────────────────────────────────────────────────────────────

    @Test
    fun `an emulated game offers four chips on Icon art, three on Hero art, and the seat is for the most each shows`() {
        val menu = menu(switchRow)
        val chips = customMediaTypeFilters(menu)
        fun keys(label: String) = chips.getValue(homeTileCustomMediaTypes(menu).first { it.label == label }.selection).map { it.key }
        assertEquals(listOf(IconArtTypeFilterKey, "region", "style", "size"), keys("Icon art"))
        assertEquals(listOf("box", "region"), keys("Launch card"))
        assertEquals(listOf("source", "size", "fit"), keys("Hero art"))
        assertEquals(listOf("source", "encode"), keys("Hero Motion"))
        val iconArt = homeTileCustomMediaTypes(menu).first { it.isMergedIconArt }
        assertEquals(
            "at a Type of All the merged picker shows every chip it has",
            listOf(IconArtTypeFilterKey, "region", "style", "size"),
            customMediaSeatingChips(iconArt, chips.getValue(iconArt.selection)).map { it.key },
        )
    }

    @Test
    fun `a PC game and an Android row have no Region chip, and a PC game opens Hero art on SteamGridDB`() {
        val pc = menu(pcRow)
        val pcIcon = homeTileCustomMediaTypes(pc).first { it.isMergedIconArt }
        assertEquals(listOf(IconArtTypeFilterKey, "style", "size"), customMediaSeatingChips(pcIcon, customMediaTypeFilters(pc).getValue(pcIcon.selection)).map { it.key })
        val pcHero = homeTileCustomMediaTypes(pc).first { it.kind == EnrichMediaKind.HERO }
        assertEquals("SteamGridDB", customMediaTypeFilters(pc).getValue(pcHero.selection).first { it.key == "source" }.selected)
        val android = menu(androidRow)
        val androidIcon = homeTileCustomMediaTypes(android).first { it.isMergedIconArt }
        assertEquals(listOf(IconArtTypeFilterKey, "style", "size"), customMediaSeatingChips(androidIcon, customMediaTypeFilters(android).getValue(androidIcon.selection)).map { it.key })
        assertFalse("an Android row has no Hero Motion", homeTileCustomMediaTypes(android).any { it.kind == EnrichMediaKind.CLIP })
    }

    @Test
    fun `a state built without the builder's chips still seats, reading its own type live`() {
        val menu = menu(switchRow)
        val types = homeTileCustomMediaTypes(menu)
        val hero = types.first { it.kind == EnrichMediaKind.HERO }
        val bare = HomeCustomEnrichState(
            sessionId = "bare",
            kind = hero.kind,
            cacheKey = "game-1",
            gameTitle = "Game",
            queryDraft = "Game",
            mediaState = hero.mediaState,
            mediaTypes = types,
        )
        val rooms = bare.customMediaPageRooms(chipDpAt(1.0f))
        assertEquals("one room per type", types.size, rooms.size)
        assertEquals("Hero art reads its own three chips", 3, rooms[types.indexOf(hero)].chipFullDp.size)
    }

    // ── The name: whole, cut at a word, or the magnifier ────────────────────────────────────────────

    /** Text that is [perCharDp] wide a character, standing if it is no wider than [roomDp]. */
    private fun fits(roomDp: Float, perCharDp: Float = 6f): (String) -> Boolean = { text -> text.length * perCharDp <= roomDp }

    @Test
    fun `a name that fits is shown whole`() {
        assertEquals(CustomMediaSearchFace.Name("Metroid Fusion"), customMediaSearchFace("Metroid Fusion", fits(14 * 6f)))
        assertEquals(CustomMediaSearchFace.Name(TotkTitle), customMediaSearchFace(TotkTitle, fits(500f)))
    }

    @Test
    fun `a long name is cut at a word with an ellipsis instead of collapsing to the magnifier`() {
        // "The Legend of Zelda Tears…" is 26 characters, and the next word would make it 29: rooms of 26 to 28 characters hold exactly this.
        (26..28).forEach { chars ->
            assertEquals("room for $chars characters", CustomMediaSearchFace.Name("The Legend of Zelda Tears…"), customMediaSearchFace(TotkTitle, fits(chars * 6f)))
        }
        // One character less and the last word goes, never half of it.
        assertEquals(CustomMediaSearchFace.Name("The Legend of Zelda…"), customMediaSearchFace(TotkTitle, fits(25 * 6f)))
        // The cut never ends on a dangling separator.
        assertEquals(CustomMediaSearchFace.Name("The Legend of Zelda…"), customMediaSearchFace("The Legend of Zelda: Tears of the Kingdom", fits(25 * 6f)))
    }

    @Test
    fun `the magnifier appears only when not even the first word and its ellipsis can stand`() {
        // "The…" is four characters.
        assertEquals(CustomMediaSearchFace.Name("The…"), customMediaSearchFace(TotkTitle, fits(4 * 6f)))
        assertEquals(CustomMediaSearchFace.Mark, customMediaSearchFace(TotkTitle, fits(3 * 6f)))
        // A first word too wide for the room: the magnifier, though the later words are short.
        assertEquals(CustomMediaSearchFace.Mark, customMediaSearchFace("Extraordinarily so", fits(10 * 6f)))
        // One word that does not fit has no word boundary to cut at, and a name is never cut inside a word.
        assertEquals(CustomMediaSearchFace.Mark, customMediaSearchFace("Supercalifragilistic", fits(10 * 6f)))
        assertEquals(CustomMediaSearchFace.Name("Metroid"), customMediaSearchFace("Metroid", fits(7 * 6f)))
    }

    @Test
    fun `the name's room is the seat less the plate's chrome and a margin`() {
        // 12 dp padding each side, the 16 dp magnifier and the 4 dp between it and the name: 44 dp of chrome.
        assertEquals(192f - 44f - 2f, customMediaSearchNameRoomDp(192f), 0.01f)
        assertTrue("the 48 dp seat holds no name at all", customMediaSearchNameRoomDp(48f) < 6f)
    }

    private companion object {
        const val TotkTitle = "The Legend of Zelda Tears of the Kingdom"
    }
}
