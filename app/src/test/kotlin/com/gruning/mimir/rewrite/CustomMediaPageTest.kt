package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.resolve
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one Customize media page : its Media chip's values and gating, where it opens, how L1/R1 and the Media panel
 * step through the types, how the search behaves across all of it, and how B unwinds. All pure: the reducers, the
 * authority and the plan are driven directly. The drawing is [CustomMediaTopRowRenderTest]'s.
 */
class CustomMediaPageTest {
    private fun row(itemId: HomeShelfItemId, title: String = "Metroid Prime") = HomeShelfRow(
        itemId = itemId,
        rank = 1L,
        addedAtMillis = 10L,
        title = title,
    )

    private val emulatedRow = row(HomeShelfItemId.Emulated(GameId("game-1")))
    private val androidGameRow = row(HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")), "Game")

    // Icon art : the one type for the shelf's face. It was two, "Shelves" (COVER + SHELF) and "Icon art".
    private val icon = CustomMediaSelection(EnrichMediaKind.ICON, MediaState.SHELF)
    private val launchCard = CustomMediaSelection(EnrichMediaKind.COVER, MediaState.LAUNCH_CARD)
    private val hero = CustomMediaSelection(EnrichMediaKind.HERO, MediaState.SHELF)
    private val clip = CustomMediaSelection(EnrichMediaKind.CLIP, MediaState.SHELF)

    private fun menu(
        row: HomeShelfRow = emulatedRow,
        entryPoint: HomeTileMenuEntryPoint = HomeTileMenuEntryPoint.EDIT,
        remembered: CustomMediaSelection? = null,
    ) = HomeTileMenuState(row = row, removable = true, entryPoint = entryPoint, customEnrichFocus = remembered)

    private fun picker(
        selection: CustomMediaSelection,
        types: List<CustomMediaType> = homeTileCustomMediaTypes(menu()),
    ): HomeCustomEnrichState {
        // Icon art is the merged picker of a game  unless the test hands it no types, which is the
        // platform picker's shape. It opens here with Type on 3D, so its row is Type and Region: two chips, the number the
        // row tests below walk.
        val merged = selection == icon && types.isNotEmpty()
        val base = HomeCustomEnrichState(
            sessionId = "session-${selection.kind.wireName}-${selection.mediaState}",
            kind = selection.kind,
            cacheKey = mediaStorageKey("game-1", selection.mediaState),
            gameTitle = "Metroid Prime",
            queryDraft = "Metroid Prime",
            mediaState = selection.mediaState,
            mediaTypes = types,
        )
        return if (merged) {
            base.copy(
                shelfIconArt = true,
                filters = homeCustomEnrichIconArtFilters().map {
                    if (it.key == IconArtTypeFilterKey) it.copy(selectedIndex = it.values.indexOf(IconArtType.ThreeD)) else it
                },
            )
        } else {
            base
        }
    }

    private fun authorityFor(state: HomeCustomEnrichState, initial: FocusKey): DefaultFocusAuthority =
        DefaultFocusAuthority().also { authority ->
            registerAll(authority, state)
            authority.pushScope(HomeCustomEnrichScope)
            authority.requestFocus(initial)
        }

    private fun registerAll(authority: DefaultFocusAuthority, state: HomeCustomEnrichState) {
        homeCustomEnrichLaneKeys(state).values.flatten().forEachIndexed { index, key ->
            authority.registerVirtual(
                key,
                HomeCustomEnrichScope,
                FocusBounds(index * 100f, 0f, index * 100f + 80f, 80f),
            )
        }
    }

    // ── The Media chip's values ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the Media chip lists the gated lanes in the existing wording and never Music`() {
        val emulated = homeTileCustomMediaTypes(menu())
        assertEquals(listOf("Icon art", "Launch card", "Hero art", "Hero Motion"), emulated.map { it.label })
        assertEquals(listOf(icon, launchCard, hero, clip), emulated.map { it.selection })
        assertTrue("Shelves is not a type any more", emulated.none { it.label == "Shelves" })
        assertTrue("Music is a row of the Edit menu now, not a value here", emulated.none { it.label.contains("music", true) })

        // Per-entry gating is homeTileCustomEnrichActions', untouched: an Android game has no clip...
        assertEquals(
            listOf("Icon art", "Launch card", "Hero art"),
            homeTileCustomMediaTypes(menu(androidGameRow)).map { it.label },
        )
        // ...the Library hero door offers Icon art where it offered Shelves (the same four as Home Edit)...
        assertEquals(
            listOf("Icon art", "Launch card", "Hero art", "Hero Motion"),
            homeTileCustomMediaTypes(menu(entryPoint = HomeTileMenuEntryPoint.LIBRARY_HERO)).map { it.label },
        )
    }

    @Test
    fun `each value is a kind and a state, and Icon art is the one shelf type`() {
        val byLabel = homeTileCustomMediaTypes(menu()).associateBy { it.label }
        assertEquals(EnrichMediaKind.COVER to MediaState.LAUNCH_CARD, byLabel.getValue("Launch card").let { it.kind to it.mediaState })
        assertEquals(EnrichMediaKind.ICON to MediaState.SHELF, byLabel.getValue("Icon art").let { it.kind to it.mediaState })
        assertEquals(EnrichMediaKind.HERO to MediaState.SHELF, byLabel.getValue("Hero art").let { it.kind to it.mediaState })
        assertEquals(EnrichMediaKind.CLIP to MediaState.SHELF, byLabel.getValue("Hero Motion").let { it.kind to it.mediaState })
    }

    // ── Where the page opens ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the page opens on Icon art from every door, the Library hero included`() {
        assertEquals(icon, customMediaOpening(menu())?.selection)
        assertEquals(icon, customMediaOpening(menu(entryPoint = HomeTileMenuEntryPoint.LIBRARY_HERO))?.selection)
        assertEquals(
            "libraryHeroPickerMenuState seeds no type, so the Library door falls through to Icon art",
            null,
            libraryHeroPickerMenuState(
                com.gruning.mimir.rewrite.core.model.library.GameRow(
                    gameId = GameId("ps2/corpse-party"),
                    title = "Corpse Party",
                    platformId = com.gruning.mimir.rewrite.core.model.PlatformId.PS2,
                    memberCount = 1,
                    duplicateKind = null,
                ),
            ).customEnrichFocus,
        )
    }

    @Test
    fun `reopening returns to the last type edited, and a type the row does not offer falls back to the default`() {
        assertEquals(launchCard, customMediaOpening(menu(remembered = launchCard))?.selection)
        assertEquals(clip, customMediaOpening(menu(remembered = clip))?.selection)
        // An Android game has no clip: a remembered one cannot be honoured, so the entry point's default stands.
        assertEquals(icon, customMediaOpening(menu(androidGameRow, remembered = clip))?.selection)
    }

    @Test
    fun `opening and switching remember the type, and the page asks the host for exactly that type`() {
        val asked = mutableListOf<Pair<EnrichMediaKind, MediaState>>()
        val opened = openCustomMedia(menu(remembered = hero)) { kind, state ->
            asked += kind to state
            picker(CustomMediaSelection(kind, state))
        }
        assertNotNull(opened)
        assertEquals(listOf(EnrichMediaKind.HERO to MediaState.SHELF), asked)
        assertEquals(hero, opened!!.customEnrichFocus)
        assertTrue(opened.phase is HomeTileMenuPhase.CustomEnrichPicker)

        // No page to open: nothing is invented.
        assertNull(openCustomMedia(menu()) { _, _ -> null })
    }

    // ── L1 / R1 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `stepping walks the types and stops at both ends`() {
        val types = homeTileCustomMediaTypes(menu())
        // The fixed order: Icon art, Launch card, Hero art, Hero Motion.
        assertEquals(launchCard, customMediaStep(types, icon, +1)?.selection)
        assertEquals(hero, customMediaStep(types, launchCard, +1)?.selection)
        assertEquals(icon, customMediaStep(types, launchCard, -1)?.selection)
        assertEquals(clip, customMediaStep(types, hero, +1)?.selection)
        assertEquals(launchCard, customMediaStep(types, hero, -1)?.selection)
        assertNull("L1 at the first type does nothing", customMediaStep(types, icon, -1))
        assertNull("R1 at the last type does nothing", customMediaStep(types, clip, +1))
        assertNull("a type the row does not offer has no neighbours", customMediaStep(types.take(2), clip, -1))
    }

    @Test
    fun `L1 and R1 on the page ask for the neighbouring type, never fetch, and land on its current choice`() {
        val state = picker(launchCard)
        val authority = authorityFor(state, homeCustomEnrichInitialKey(state))

        val next = reduceHomeCustomEnrich(authority, state, ControllerAction.PAGE_RIGHT)
        assertEquals(HomeCustomEnrichCommand.SwitchMedia(hero), next.command)
        val previous = reduceHomeCustomEnrich(authority, state, ControllerAction.PAGE_LEFT)
        assertEquals(HomeCustomEnrichCommand.SwitchMedia(icon), previous.command)
        listOf(next, previous).forEach {
            assertFalse("a type switch is never a fetch", it.command is HomeCustomEnrichCommand.Search)
            assertEquals(HomeCustomEnrichSearchStatus.Idle, it.state.searchStatus)
            assertEquals(0, it.state.generation)
        }

        // The switched page's own initial focus is its current choice (the overlay requests it on mount).
        val switched = picker(hero)
        assertEquals(
            HomeCustomEnrichAsset.Automatic,
            switched.assets.first { homeCustomEnrichAssetKey(switched, it) == homeCustomEnrichInitialKey(switched) },
        )
    }

    @Test
    fun `L1 and R1 stop at the ends of the types`() {
        val first = picker(icon)
        val last = picker(clip)
        assertEquals(
            HomeCustomEnrichCommand.None,
            reduceHomeCustomEnrich(authorityFor(first, homeCustomEnrichInitialKey(first)), first, ControllerAction.PAGE_LEFT).command,
        )
        assertEquals(
            HomeCustomEnrichCommand.None,
            reduceHomeCustomEnrich(authorityFor(last, homeCustomEnrichInitialKey(last)), last, ControllerAction.PAGE_RIGHT).command,
        )
        // And they do step inward from the ends.
        assertEquals(
            HomeCustomEnrichCommand.SwitchMedia(launchCard),
            reduceHomeCustomEnrich(authorityFor(first, homeCustomEnrichInitialKey(first)), first, ControllerAction.PAGE_RIGHT).command,
        )
        assertEquals(
            HomeCustomEnrichCommand.SwitchMedia(hero),
            reduceHomeCustomEnrich(authorityFor(last, homeCustomEnrichInitialKey(last)), last, ControllerAction.PAGE_LEFT).command,
        )
    }

    @Test
    fun `L1 and R1 do nothing while a search runs, a panel or the keyboard is open, or an apply is staged`() {
        val base = picker(icon)
        val blocked = listOf(
            base.copy(searchStatus = HomeCustomEnrichSearchStatus.Searching("Community media", 0)),
            base.copy(mediaPanelOpen = true),
            base.copy(openFilterKey = "box"),
            base.copy(keyboardOpen = true),
            base.copy(operation = HomeCustomEnrichOperation.APPLY_COMMITTING),
            base.copy(previewExpanded = true),
        )
        blocked.forEach { state ->
            val authority = authorityFor(state, homeCustomEnrichInitialKey(state))
            listOf(ControllerAction.PAGE_LEFT, ControllerAction.PAGE_RIGHT).forEach { action ->
                val transition = reduceHomeCustomEnrich(authority, state, action)
                assertEquals("$action over $state", HomeCustomEnrichCommand.None, transition.command)
            }
        }
        assertTrue(customMediaSwitchAllowed(base))
        // The coordinator's own gate (the panel and the chip's value panel close before they ask) holds the same line.
        listOf(
            base.copy(searchStatus = HomeCustomEnrichSearchStatus.Searching("Community media", 0)),
            base.copy(keyboardOpen = true),
            base.copy(operation = HomeCustomEnrichOperation.APPLY_COMMITTING),
            base.copy(previewExpanded = true),
        ).forEach { assertFalse("$it", customMediaSwitchAllowed(it)) }
    }

    @Test
    fun `a platform picker has no Media chip, so the shoulders and the chip stay out of its way`() {
        val platform = picker(icon, types = emptyList())
        assertTrue(customMediaTopKeys(platform).isEmpty())
        val authority = authorityFor(platform, homeCustomEnrichInitialKey(platform))
        assertEquals(
            HomeCustomEnrichCommand.None,
            reduceHomeCustomEnrich(authority, platform, ControllerAction.PAGE_RIGHT).command,
        )
    }

    // ── The status line ─────────────────────────────────────────────────────────────────────────────

    private fun savedChoice() = EnrichSavedAsset(
        assetId = "saved-1",
        source = EnrichSavedSource.SCREENSCRAPER,
        label = "ScreenScraper · 600×800",
        localPath = "/local/saved-1",
        previewPath = "/local/saved-1",
        width = 600,
        height = 800,
        bytes = 1L,
        addedAt = 1L,
    )

    @Test
    fun `the idle status says downloaded choices are ready only when there are some, for every type`() {
        listOf(icon, launchCard, hero, clip).forEach { selection ->
            val none = picker(selection)
            assertEquals("${selection.kind}/${selection.mediaState}: only Default is in the gallery", listOf(HomeCustomEnrichAsset.Automatic), none.assets)
            val said = homeCustomEnrichIdleStatusIntent(none).resolve()
            assertEquals("${selection.kind}/${selection.mediaState}", CustomMediaNoChoicesVisual, said.visualText)
            assertEquals(CustomMediaNoChoicesSpoken, said.spokenText)
            assertFalse("nothing ready is claimed over a gallery of Default alone: ${said.spokenText}", said.spokenText.contains("ready", ignoreCase = true))
            assertTrue("and it names the key that fetches some: ${said.visualText}", said.visualText.contains("Y SEARCH"))

            val some = none.copy(saved = listOf(savedChoice()))
            assertEquals("DOWNLOADED CHOICES READY", homeCustomEnrichIdleStatusIntent(some).resolve().visualText)
        }
        assertEquals("Preparing saved choices…", homeCustomEnrichIdleStatusIntent(picker(icon).copy(catalogReady = false)).resolve().spokenText)
    }

    // ── The Media chip and its panel on the pad ─────────────────────────────────────────────────────

    @Test
    fun `the top row is Search then Media then the type's chips, and the pad walks it in that order`() {
        val state = picker(icon)
        val chips = state.visibleFilters().map { homeCustomEnrichFilterKey(state, it) }
        val media = homeCustomEnrichMediaKey(state)
        val search = homeCustomEnrichSearchKey(state)
        assertEquals(listOf(media) + chips, homeCustomEnrichLaneKeys(state).getValue(HomeCustomEnrichLane.FILTERS))
        assertEquals(listOf(search), homeCustomEnrichLaneKeys(state).getValue(HomeCustomEnrichLane.SEARCH))
        assertEquals(2, chips.size)

        //  Right walks Search, Media, the chips; the last stop holds.
        val authority = authorityFor(state, search)
        val walked = mutableListOf<FocusKey?>()
        repeat(4) {
            reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_RIGHT)
            walked += authority.state.value.focused
        }
        assertEquals("the last stop holds", listOf(media, chips[0], chips[1], chips[1]), walked)
        walked.clear()
        repeat(4) {
            reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_LEFT)
            walked += authority.state.value.focused
        }
        assertEquals("and the first stop, Search, holds on the left", listOf(chips[0], media, search, search), walked)
    }

    @Test
    fun `Up from the gallery lands on Media the first time, and then restores whichever stop of the row the pad left`() {
        val state = picker(icon)
        val search = homeCustomEnrichSearchKey(state)
        val chips = state.visibleFilters().map { homeCustomEnrichFilterKey(state, it) }
        // Never on the row yet: the way up lands on the type control (Media), not on the search that now stands first.
        val fresh = authorityFor(state, homeCustomEnrichInitialKey(state))
        reduceHomeCustomEnrich(fresh, state, ControllerAction.NAV_UP)
        assertEquals(homeCustomEnrichMediaKey(state), fresh.state.value.focused)

        // Left on the row's own stop, then Down and Up: the row remembers where the pad was, search or chip alike.
        listOf(search, chips[1]).forEach { stop ->
            val authority = authorityFor(state, stop)
            val down = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_DOWN)
            reduceHomeCustomEnrich(authority, down.state, ControllerAction.NAV_UP)
            assertEquals(stop, authority.state.value.focused)
        }
    }

    @Test
    fun `Up from the gallery reaches the top row on the Media chip, and Down returns to the current choice`() {
        val state = picker(icon)
        val authority = authorityFor(state, homeCustomEnrichInitialKey(state))
        reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_UP)
        assertEquals(homeCustomEnrichMediaKey(state), authority.state.value.focused)
        reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_DOWN)
        assertEquals(homeCustomEnrichInitialKey(state), authority.state.value.focused)
    }

    @Test
    fun `A on the Media chip opens its panel, the panel lands on the current type, and B closes it onto the chip`() {
        val state = picker(launchCard)
        val media = homeCustomEnrichMediaKey(state)
        val authority = authorityFor(state, media)

        val opened = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM)
        assertTrue(opened.state.mediaPanelOpen)
        assertEquals(HomeCustomEnrichCommand.None, opened.command)
        assertEquals(
            "the chip's stop is replaced by one stop per type while the panel is open",
            state.mediaTypes.indices.map { homeCustomEnrichMediaOptionKey(opened.state, it) },
            homeCustomEnrichLaneKeys(opened.state).getValue(HomeCustomEnrichLane.FILTERS).take(state.mediaTypes.size),
        )
        assertEquals(homeCustomEnrichMediaOptionKey(opened.state, 1), customMediaPanelLanding(opened.state))

        registerAll(authority, opened.state)
        authority.requestFocus(customMediaPanelLanding(opened.state)!!)
        val closed = reduceHomeCustomEnrich(authority, opened.state, ControllerAction.CANCEL)
        assertFalse(closed.state.mediaPanelOpen)
        assertEquals(media, authority.state.value.focused)
        assertEquals(launchCard, closed.state.mediaSelection())
    }

    @Test
    fun `picking another type in the panel asks for the switch, and picking the current one only closes it`() {
        val state = picker(icon)
        val authority = authorityFor(state, homeCustomEnrichMediaKey(state))
        val opened = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM).state
        registerAll(authority, opened)

        authority.requestFocus(homeCustomEnrichMediaOptionKey(opened, 2))
        val moved = reduceHomeCustomEnrich(authority, opened, ControllerAction.CONFIRM)
        assertEquals(HomeCustomEnrichCommand.SwitchMedia(hero), moved.command)
        assertFalse(moved.state.mediaPanelOpen)

        authority.requestFocus(homeCustomEnrichMediaOptionKey(opened, 0))
        val same = reduceHomeCustomEnrich(authority, opened, ControllerAction.CONFIRM)
        assertEquals(HomeCustomEnrichCommand.None, same.command)
        assertFalse(same.state.mediaPanelOpen)
        assertEquals(
            "a pick of the same type lands on the page's current choice, as a different one does",
            homeCustomEnrichInitialKey(opened),
            authority.state.value.focused,
        )

        // Up and Down walk the panel's rows through the authority, not past them.
        authority.requestFocus(homeCustomEnrichMediaOptionKey(opened, 0))
        reduceHomeCustomEnrich(authority, opened, ControllerAction.NAV_UP)
        assertEquals(homeCustomEnrichMediaOptionKey(opened, 0), authority.state.value.focused)
        reduceHomeCustomEnrich(authority, opened, ControllerAction.NAV_DOWN)
        assertEquals(homeCustomEnrichMediaOptionKey(opened, 1), authority.state.value.focused)
    }

    @Test
    fun `A on the Media chip does nothing while a search runs`() {
        val state = picker(icon).copy(searchStatus = HomeCustomEnrichSearchStatus.Searching("Community media", 0))
        val authority = authorityFor(state, homeCustomEnrichMediaKey(state))
        val transition = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM)
        assertFalse(transition.state.mediaPanelOpen)
        assertEquals(HomeCustomEnrichCommand.None, transition.command)
    }

    // ── Search ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the search box starts with the game's title, A opens the keyboard and fetches nothing, and only Y fetches`() {
        val state = picker(icon)
        assertEquals("the box starts with the game's title", "Metroid Prime", state.queryDraft)
        val authority = authorityFor(state, homeCustomEnrichSearchKey(state))

        val a = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM)
        assertTrue(a.state.keyboardOpen)
        assertEquals(HomeCustomEnrichCommand.OpenKeyboard, a.command)
        assertEquals(HomeCustomEnrichSearchStatus.Idle, a.state.searchStatus)
        assertTrue(a.state.results.isEmpty())
        assertEquals(0, a.state.generation)

        val y = reduceHomeCustomEnrich(authority, state, ControllerAction.TERTIARY)
        val search = y.command as HomeCustomEnrichCommand.Search
        assertEquals("Metroid Prime", search.query)
        assertEquals(1, search.generation)
        assertTrue(y.state.searchStatus is HomeCustomEnrichSearchStatus.Searching)
    }

    @Test
    fun `switching type keeps the typed name, resets nothing else, and never fetches`() {
        val typed = picker(icon).copy(queryDraft = "Metroid Prime Echoes", submittedQuery = "Metroid Prime Echoes")
        val live = menu(remembered = icon).copy(phase = HomeTileMenuPhase.CustomEnrichPicker(typed))
        val next = picker(hero)

        assertTrue(customMediaSwitchAllowed(typed))
        val switched = customMediaSwitched(live, typed, next, hero)
        val page = (switched.phase as HomeTileMenuPhase.CustomEnrichPicker).picker
        assertEquals("Metroid Prime Echoes", page.queryDraft)
        assertEquals(EnrichMediaKind.HERO, page.kind)
        assertEquals(HomeCustomEnrichSearchStatus.Idle, page.searchStatus)
        assertTrue(page.results.isEmpty())
        assertNull("nothing was submitted for the new type", page.submittedQuery)
        assertEquals(hero, switched.customEnrichFocus)
    }

    // ── B ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `B closes the panel, then a running search, then the page, in that order`() {
        val running = HomeCustomEnrichSearchStatus.Searching("Community media", 0)
        val state = picker(icon).copy(searchStatus = running, mediaPanelOpen = true)
        val authority = authorityFor(state, homeCustomEnrichMediaOptionKey(state, 1))

        val first = reduceHomeCustomEnrich(authority, state, ControllerAction.CANCEL)
        assertFalse("the panel goes first", first.state.mediaPanelOpen)
        assertEquals(HomeCustomEnrichCommand.None, first.command)
        assertEquals(running, first.state.searchStatus)

        val second = reduceHomeCustomEnrich(authority, first.state, ControllerAction.CANCEL)
        assertEquals("then the running search", HomeCustomEnrichCommand.CancelSearch, second.command)

        val third = reduceHomeCustomEnrich(authority, second.state, ControllerAction.CANCEL)
        assertEquals("then the page", HomeCustomEnrichCommand.Back, third.command)
    }

    @Test
    fun `B closes the keyboard before anything else`() {
        val state = picker(icon).copy(keyboardOpen = true, searchStatus = HomeCustomEnrichSearchStatus.Searching("x", 0))
        val authority = authorityFor(state, homeCustomEnrichSearchKey(state))
        val closed = reduceHomeCustomEnrich(authority, state, ControllerAction.CANCEL)
        assertFalse(closed.state.keyboardOpen)
        assertEquals(HomeCustomEnrichCommand.None, closed.command)
        assertTrue("the search keeps running under the keyboard", closed.state.searchStatus is HomeCustomEnrichSearchStatus.Searching)
    }

    @Test
    fun `the page's own B returns to the Edit menu, and closes everything from the doors with no menu`() {
        val page = HomeTileMenuPhase.CustomEnrichPicker(picker(hero))
        val edit = menu(remembered = hero).copy(phase = page)
        val back = customMediaBack(edit)
        assertEquals(HomeTileMenuPhase.Menu, back?.phase)
        assertEquals("the Edit menu remembers the type for the next visit", hero, back?.customEnrichFocus)

        assertNull(customMediaBack(edit.copy(entryPoint = HomeTileMenuEntryPoint.LIBRARY_HERO)))
    }

    // ── The top row's seating ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the row has no Back chip, so it seats Media, the chips and Search, then gives up words in a fixed order`() {
        val media = 100f
        val full = listOf(144f, 118f, 130f)
        val values = listOf(73f, 65f, 106f)
        fun plan(available: Float, chipsFull: List<Float> = full, chipsValue: List<Float> = values) =
            customMediaRowPlan(available, media, chipsFull, chipsValue)

        // 100 + 392 + the search's 48 = 540, plus four 4 dp gaps between five pieces = 556.
        assertEquals(CustomMediaRowPlan(CustomMediaChipForm.FULL, foldChips = false), plan(556f))
        // One short: the chips lose their labels.
        assertEquals(CustomMediaRowPlan(CustomMediaChipForm.VALUE, foldChips = false), plan(555f))
        // 100 + 244 + 48 + 16 = 408; below it the chips FOLD into the one 48 dp Filters chip. Nothing else gives: there is no Back
        // chip to shorten, and Media, Filters and Search at 48 are the row's floor (100 + 48 + 48 + 8 = 204).
        assertEquals(CustomMediaRowPlan(CustomMediaChipForm.VALUE, foldChips = false), plan(408f))
        assertEquals(CustomMediaRowPlan(CustomMediaChipForm.VALUE, foldChips = true), plan(407f))
        assertEquals(CustomMediaRowPlan(CustomMediaChipForm.VALUE, foldChips = true), plan(204f))
        // A type with one chip keeps it, in full, at the compact width: 100 + 130 + 48 + 8 = 286.
        assertEquals(CustomMediaRowPlan(CustomMediaChipForm.FULL, foldChips = false), plan(322f, chipsFull = listOf(130f), chipsValue = listOf(65f)))
    }

    // ── The Filters chip: the type's chips folded into one ──────────────────────────────────────────

    private fun withFold(state: HomeCustomEnrichState, block: () -> Unit) {
        CustomMediaFold.set(state.sessionId, true)
        try {
            block()
        } finally {
            CustomMediaFold.set(state.sessionId, false)
        }
    }

    @Test
    fun `folded, the top row has a Filters stop in place of the chips' stops, and unfolded it has the chips`() {
        val state = picker(icon)
        val media = homeCustomEnrichMediaKey(state)
        val chips = state.visibleFilters().map { homeCustomEnrichFilterKey(state, it) }
        val filters = homeCustomEnrichFiltersKey(state)
        assertEquals(listOf(media) + chips, homeCustomEnrichLaneKeys(state).getValue(HomeCustomEnrichLane.FILTERS))
        assertEquals(listOf(media, filters), homeCustomEnrichLaneKeys(state, folded = true).getValue(HomeCustomEnrichLane.FILTERS))
        withFold(state) {
            // The reducer's own graph reads the drawn row's decision.
            assertEquals(listOf(media, filters), homeCustomEnrichLaneKeys(state).getValue(HomeCustomEnrichLane.FILTERS))
            val authority = authorityFor(state, homeCustomEnrichSearchKey(state))
            val walked = mutableListOf<FocusKey?>()
            repeat(3) {
                reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_RIGHT)
                walked += authority.state.value.focused
            }
            assertEquals("Search, Media, Filters, and the last stop holds", listOf(media, filters, filters), walked)
        }
        // A platform picker has no top row to fold.
        assertTrue(customMediaTopKeys(picker(icon, types = emptyList()), folded = true).isEmpty())
    }

    @Test
    fun `A on the Filters chip opens its panel of rows, B closes it onto the chip, and a row opens that chip's value panel`() {
        val state = picker(icon)
        withFold(state) {
            val filters = homeCustomEnrichFiltersKey(state)
            val authority = authorityFor(state, filters)

            val opened = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM)
            assertTrue(opened.state.filtersPanelOpen)
            assertEquals(HomeCustomEnrichCommand.None, opened.command)
            val rows = state.visibleFilters().indices.map { homeCustomEnrichFiltersOptionKey(opened.state, it) }
            assertEquals(
                "the chip's stop is replaced by one stop per row while the panel is open",
                rows,
                homeCustomEnrichLaneKeys(opened.state).getValue(HomeCustomEnrichLane.FILTERS).drop(1),
            )
            assertEquals(rows.first(), customMediaPanelLanding(opened.state))

            // B: closes the panel and gives focus back to the chip.
            registerAll(authority, opened.state)
            authority.requestFocus(rows.first())
            val closed = reduceHomeCustomEnrich(authority, opened.state, ControllerAction.CANCEL)
            assertFalse(closed.state.filtersPanelOpen)
            assertEquals(filters, authority.state.value.focused)

            // A on the second row ("Region · Any"): the panel closes and that chip's own value panel opens.
            authority.requestFocus(rows[1])
            val picked = reduceHomeCustomEnrich(authority, opened.state, ControllerAction.CONFIRM)
            assertFalse(picked.state.filtersPanelOpen)
            assertEquals(state.visibleFilters()[1].key, picked.state.openFilterKey)
            assertEquals(HomeCustomEnrichCommand.None, picked.command)

            // B on the value panel returns to the Filters chip (the chips themselves have no stops), not to a chip that is not drawn.
            val valuePanel = picked.state
            registerAll(authority, valuePanel)
            val option = homeCustomEnrichFilterOptionKey(valuePanel, valuePanel.visibleFilters()[1], 0)
            authority.requestFocus(option)
            val back = reduceHomeCustomEnrich(authority, valuePanel, ControllerAction.CANCEL)
            assertNull(back.state.openFilterKey)
            assertEquals(filters, authority.state.value.focused)
        }
    }

    @Test
    fun `A on the Filters chip does nothing while a search runs, and L1 and R1 stay out of its panel`() {
        val state = picker(icon).copy(searchStatus = HomeCustomEnrichSearchStatus.Searching("Community media", 0))
        withFold(state) {
            val authority = authorityFor(state, homeCustomEnrichFiltersKey(state))
            assertFalse(reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM).state.filtersPanelOpen)
        }
        val open = picker(icon).copy(filtersPanelOpen = true)
        withFold(open) {
            val authority = authorityFor(open, homeCustomEnrichFiltersOptionKey(open, 0))
            listOf(ControllerAction.PAGE_LEFT, ControllerAction.PAGE_RIGHT).forEach {
                assertEquals(HomeCustomEnrichCommand.None, reduceHomeCustomEnrich(authority, open, it).command)
            }
        }
    }

    @Test
    fun `a chip says its label and value in full, and its value alone when the row is tight`() {
        val opening = homeCustomEnrichDefaultFilters(EnrichMediaKind.HERO).first { it.key == "fit" }
        assertEquals("Fit · Any ▾", customMediaFilterChipText(opening, CustomMediaChipForm.FULL))
        assertEquals("Any ▾", customMediaFilterChipText(opening, CustomMediaChipForm.VALUE))
        // The Mimir aspect range is a value to pick, worded "Best fit".
        val filter = opening.copy(selectedIndex = opening.values.indexOf("Mimir"))
        assertEquals("Fit · Best fit ▾", customMediaFilterChipText(filter, CustomMediaChipForm.FULL))
        assertEquals("Best fit ▾", customMediaFilterChipText(filter, CustomMediaChipForm.VALUE))
        assertEquals("Icon art ▾", customMediaTypeChipText(homeTileCustomMediaTypes(menu()).first { it.label == "Icon art" }))
        assertEquals(48f, customMediaChipLayoutDp(10f), 0f)
        assertEquals(124f, customMediaChipLayoutDp(100f), 0f)
    }
}
