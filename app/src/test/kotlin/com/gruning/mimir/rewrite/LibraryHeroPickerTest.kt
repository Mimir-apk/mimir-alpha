package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.ProviderEnrichmentState
import com.gruning.mimir.rewrite.feature.library.providerEnrichmentState
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptOutcome
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.input.ControllerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryHeroPickerTest {

    /**
     * with no Enrich pill on screen, Y opens this menu for a game the provider has
     * never answered, and Enrich must lead it -- the menu is where Enrich lives on compact.
     */
    @Test
    fun aNeverEnrichedGameFindsEnrichLeadingTheMenu() {
        val neverAsked = corpseParty().copy(description = null, descriptionSource = null)
        val state = libraryHeroPickerMenuState(neverAsked)
        assertTrue(state.offerEnrich)
        assertEquals(HomeTileMenuAction.ENRICH, homeTileMenuActions(state).first())
    }

    @Test
    fun openLibraryHeroPickerOpensTheMenuForEveryGame() {
        val coordinator = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
        ).first(java.io.File::isFile).readText()
        val block = coordinator.substringAfter("val openLibraryHeroPicker: (GameRow) -> Unit = { game ->")
            .substringBefore("\n    val onHomeTomeLibraryAction")
        assertFalse("compact's Y must never open nothing", block.contains("shouldOfferEnrich"))
    }

    private fun corpseParty() = GameRow(
        gameId = GameId("ps2/corpse-party"),
        title = "Corpse Party",
        platformId = PlatformId.PS2,
        memberCount = 1,
        duplicateKind = null,
        description = "A school-bound horror story.",
        descriptionSource = "screenscraper",
    )

    /**
     * **This test previously asserted the opposite, and the change is deliberate.** It pinned
     * `state.phase as HomeTileMenuPhase.CustomEnrich` and one CONFIRM reaching the media picker directly.
     * the
     * gamelist's renamed Settings affordance now lands on the TOP-LEVEL menu first — that old assertion is
     * exactly the defect the spec closes (Add to Tome / Achievements were unreachable from the gamelist
     * because this entry point skipped straight past them). Rewritten rather than deleted: the media
     * sub-menu's own untouched behavior (S-2: "Customize media then behaves exactly as it does today") is
     * still asserted below, one CONFIRM deeper than before.
     *
     * Updated again 2026-08-19: `corpseParty()` has a description and `descriptionSource` but no cover/flat
     * art, so it is `VERIFIED_PARTIAL` — exactly the record policy 2026-08-19
     *  restores Enrich for. The top-level list
     * now leads with Enrich, so reaching Customize media takes one extra NAV_DOWN before the first CONFIRM;
     * everything from Customize media onward is untouched from the 2026-08-14 shape.
     */
    @Test
    fun directLibraryHeroEntryPreservesGameTruthAndTargetsHeroWithoutMakingItAHomeFavorite() {
        // a partial record offers the retry only after an attempt that FAILED, so this game's last Enrich failed.
        val game = corpseParty().copy(enrichAttempt = EnrichAttemptOutcome.FAILED)

        val state = libraryHeroPickerMenuState(game)

        assertEquals(HomeShelfItemId.Emulated(game.gameId), state.row.itemId)
        assertEquals(game, state.sourceGame)
        assertEquals(HomeTileMenuEntryPoint.LIBRARY_HERO, state.entryPoint)
        assertFalse(state.removable)
        // corpseParty() is VERIFIED_PARTIAL (has a synopsis/source, no art at all) -- the exact record the
        // 2026-08-19 ruling targets.
        assertTrue(state.offerEnrich)

        // S-2: lands on the top-level menu, not the media sub-menu -- and that menu already offers Add to
        // Tome / Achievements alongside Customize media (homeTileMenuActions is the one shared list; this
        // entry point does not restate it). 2026-08-19: Enrich now leads, ahead of Customize media, for a
        // verified-partial game.
        assertEquals(HomeTileMenuPhase.Menu, state.phase)
        assertEquals(
            listOf(
                HomeTileMenuAction.ENRICH,
                HomeTileMenuAction.CUSTOM_ENRICH,
                HomeTileMenuAction.DOWNLOAD_GAME_MUSIC,
                HomeTileMenuAction.ADD_TO_COLLECTION,
                HomeTileMenuAction.ACHIEVEMENTS,
                // Favourite lives in this menu : a game that is not one is offered the add row, last.
                HomeTileMenuAction.ADD_FAVORITE,
            ),
            homeTileMenuActions(state),
        )
        // Not pre-aimed any more (Icon art on both doors): the Customize media page opens on Icon art, as Home's does.
        assertNull(state.customEnrichFocus)

        val iconPicker = HomeCustomEnrichState(
            sessionId = "library-hero",
            kind = EnrichMediaKind.ICON,
            cacheKey = game.gameId.value,
            gameTitle = game.title,
            queryDraft = game.title,
        )
        val reduce = { s: HomeTileMenuState, action: ControllerAction ->
            reduceHomeTileMenu(
                state = s,
                action = action,
                onRemove = {},
                onLoadArt = {},
                onApplyPick = { _, _ -> },
                onOpenCustomEnrich = { kind, _ -> iconPicker.takeIf { kind == EnrichMediaKind.ICON } },
            )
        }

        // menuFocus defaults to 0, which is now Enrich for this record -- confirm that lands separately
        // below (enrichMenuEntryFiresOnEnrichAndClosesTheMenu). Reach Customize media the way a real
        // controller would: one NAV_DOWN off the default landing spot.
        val onCustomizeMedia = reduce(state, ControllerAction.NAV_DOWN)!!
        assertEquals(HomeTileMenuAction.CUSTOM_ENRICH, homeTileMenuActions(onCustomizeMedia)[onCustomizeMedia.menuFocus])

        // First CONFIRM: top-level menu -> Customize media, on Icon art (previously this door was pre-aimed at Hero art).
        //
        // The literal 0 this asserted until 2026-08-25 was only ever right by coincidence: Hero art
        // happened to be the first lane. libraryHeroPickerMenuState resolves the aim with
        // `indexOf(CUSTOM_HERO)`, so inserting the Cover lane above it moved the index to 1 and the aim
        // followed on its own. Asserting the resolved focus instead of a digit is what makes that
        // property visible -- and it means the press count did NOT move: this entry still lands on Hero
        // art in exactly the two presses it always took.
        //
        // there is no second press any more. Customize media opens the page itself, and every door's default type is
        // Icon art , so this door's single CONFIRM lands on the Icon art picker: the stub answers only for Icon art.
        val opened = reduce(onCustomizeMedia, ControllerAction.CONFIRM)
        assertEquals(HomeTileMenuPhase.CustomEnrichPicker(iconPicker), opened?.phase)
    }

    /**
     * the top-level
     * menu's new Enrich entry must actually fire the same idempotent fetch the seat's own direct tap uses,
     * and close the menu afterward -- exactly the ACHIEVEMENTS grammar, not a state transition.
     */
    @Test
    fun enrichMenuEntryFiresOnEnrichAndClosesTheMenu() {
        val state = libraryHeroPickerMenuState(corpseParty().copy(enrichAttempt = EnrichAttemptOutcome.FAILED))
        assertEquals(HomeTileMenuAction.ENRICH, homeTileMenuActions(state)[state.menuFocus])

        var enrichCalls = 0
        val next = reduceHomeTileMenu(
            state = state,
            action = ControllerAction.CONFIRM,
            onRemove = {},
            onLoadArt = {},
            onApplyPick = { _, _ -> },
            onEnrich = { enrichCalls++ },
        )

        assertEquals(1, enrichCalls)
        assertNull("selecting Enrich closes the menu; the seat's own GetArtUi overlay takes over", next)
    }

    /**
     * the reversal is scoped to VERIFIED_PARTIAL only -- a fully verified record
     * (cover, logo, and synopsis all present and distinct) keeps today's Settings-only shape, unchanged.
     */
    @Test
    fun verifiedCompleteLibraryGameNeverOffersEnrichFromTheMenu() {
        val complete = corpseParty().copy(
            artUri = "file:///cover.png",
            flatArtUri = "file:///logo.png",
        )
        val state = libraryHeroPickerMenuState(complete)

        assertFalse(state.offerEnrich)
        assertFalse(HomeTileMenuAction.ENRICH in homeTileMenuActions(state))
        assertEquals(
            listOf(
                HomeTileMenuAction.CUSTOM_ENRICH,
                HomeTileMenuAction.DOWNLOAD_GAME_MUSIC,
                HomeTileMenuAction.ADD_TO_COLLECTION,
                HomeTileMenuAction.ACHIEVEMENTS,
                HomeTileMenuAction.ADD_FAVORITE,
            ),
            homeTileMenuActions(state),
        )
    }

    // ---- favourite lives in this menu, on both doors  ---------------------------------------------------------------------

    /** A favourite game's Library menu offers Remove favorite (the existing row, in its slot); any other game's offers Add favorite there instead; never both. */
    @Test
    fun theLibraryMenuOffersRemoveFavoriteForAFavouriteAndAddFavoriteOtherwise() {
        val favourite = libraryHeroPickerMenuState(corpseParty().copy(isFavorite = true))
        assertTrue(favourite.removable)
        assertEquals(HomeTileMenuAction.REMOVE_FAVORITE, homeTileMenuActions(favourite).last())
        assertFalse(HomeTileMenuAction.ADD_FAVORITE in homeTileMenuActions(favourite))
        assertEquals("Remove favorite", homeTileMenuOptions(favourite).last())

        val other = libraryHeroPickerMenuState(corpseParty().copy(isFavorite = false))
        assertFalse(other.removable)
        assertEquals(HomeTileMenuAction.ADD_FAVORITE, homeTileMenuActions(other).last())
        assertFalse(HomeTileMenuAction.REMOVE_FAVORITE in homeTileMenuActions(other))
        assertEquals("Add favorite", homeTileMenuOptions(other).last())
    }

    /** Home's own menus never show the add row: Edit is opened on favourites, and a non-removable Edit row stays as it was. */
    @Test
    fun onlyTheLibraryEntryPointOffersAddFavorite() {
        val row = libraryHeroPickerRow(corpseParty())
        for (entry in listOf(HomeTileMenuEntryPoint.EDIT)) {
            val state = HomeTileMenuState(row = row, removable = false, entryPoint = entry)
            assertFalse("$entry never offers Add favorite", HomeTileMenuAction.ADD_FAVORITE in homeTileMenuActions(state))
        }
        assertTrue(HomeTileMenuAction.REMOVE_FAVORITE in homeTileMenuActions(HomeTileMenuState(row = row, removable = true)))
    }

    /** A on Add favorite adds the game and closes the menu, with no confirm; Remove favorite still asks first. */
    @Test
    fun addFavoriteFiresAndClosesWhileRemoveFavoriteStillConfirms() {
        val other = libraryHeroPickerMenuState(corpseParty().copy(isFavorite = false))
        val added = mutableListOf<HomeShelfItemId>()
        val atAdd = other.copy(menuFocus = homeTileMenuActions(other).indexOf(HomeTileMenuAction.ADD_FAVORITE))

        val afterAdd = reduceHomeTileMenu(
            state = atAdd,
            action = ControllerAction.CONFIRM,
            onRemove = { error("adding never removes") },
            onLoadArt = {},
            onApplyPick = { _, _ -> },
            onAddFavorite = { added += it },
        )

        assertNull("the menu closes", afterAdd)
        assertEquals(listOf<HomeShelfItemId>(HomeShelfItemId.Emulated(corpseParty().gameId)), added)

        val favourite = libraryHeroPickerMenuState(corpseParty().copy(isFavorite = true))
        val atRemove = favourite.copy(menuFocus = homeTileMenuActions(favourite).indexOf(HomeTileMenuAction.REMOVE_FAVORITE))
        val afterRemove = reduceHomeTileMenu(
            state = atRemove,
            action = ControllerAction.CONFIRM,
            onRemove = { error("asks before removing") },
            onLoadArt = {},
            onApplyPick = { _, _ -> },
            onAddFavorite = { error("removing never adds") },
        )
        assertEquals(HomeTileMenuPhase.ConfirmRemove, afterRemove?.phase)
    }

    // ---- the same menu on both doors  -------------------------------------------------------------------------------------

    /**
     * The same game's Y menu lists the same rows in the same order from either door: Enrich follows the GAME (the one question [tileMenuOffersEnrich] answers), not which door
     * asked. Home's rows are always favourites, so the Library's menu for the favourite is the one compared; for a game that is not one the Library's differs by its last row only
     * (Add favorite where Home has Remove), which is the favourite state and not the door.
     */
    @Test
    fun theSameGameHasTheSameMenuRowsInTheSameOrderFromBothDoors() {
        val partial = corpseParty()
        val failedPartial = corpseParty().copy(enrichAttempt = EnrichAttemptOutcome.FAILED)
        val neverAsked = corpseParty().copy(description = null, descriptionSource = null)
        val attempted = neverAsked.copy(enrichAttempt = EnrichAttemptOutcome.NO_MATCH)
        val complete = corpseParty().copy(artUri = "file:///cover.png", flatArtUri = "file:///flat.png")
        assertEquals(ProviderEnrichmentState.VERIFIED_PARTIAL, partial.providerEnrichmentState())
        assertEquals(ProviderEnrichmentState.NEVER_REQUESTED, neverAsked.providerEnrichmentState())
        assertEquals(ProviderEnrichmentState.ATTEMPTED, attempted.providerEnrichmentState())
        assertEquals(ProviderEnrichmentState.VERIFIED_COMPLETE, complete.providerEnrichmentState())

        for ((name, game, leadsWithEnrich) in listOf(
            // a record proves a run finished, so a partial one offers the retry only after a FAILED attempt.
            Triple("verified partial", partial, false),
            Triple("verified partial after a failed attempt", failedPartial, true),
            Triple("never asked", neverAsked, true),
            Triple("attempted, no record", attempted, false),
            Triple("complete", complete, false),
        )) {
            val library = libraryHeroPickerMenuState(game.copy(isFavorite = true))
            val home = homeTileMenuState(libraryHeroPickerRow(game), game, removable = true)
            assertEquals("$name: the same rows in the same order", homeTileMenuActions(library), homeTileMenuActions(home))
            assertEquals("$name: the same words", homeTileMenuOptions(library), homeTileMenuOptions(home))
            assertEquals("$name: Enrich leads exactly when the game can be enriched", leadsWithEnrich, homeTileMenuActions(home).first() == HomeTileMenuAction.ENRICH)

            val libraryNotFavourite = homeTileMenuActions(libraryHeroPickerMenuState(game.copy(isFavorite = false)))
            assertEquals("$name: a game that is not a favourite differs by the favourite row only", homeTileMenuActions(home).dropLast(1), libraryNotFavourite.dropLast(1))
        }

        // Home's rows for an Android game carry no game: no Enrich, as the Library's menu for one has none.
        val android = homeTileMenuState(nativeAppTileMenuState("com.example.game", "Game", null, isGame = true).row, game = null, removable = true)
        assertFalse(HomeTileMenuAction.ENRICH in homeTileMenuActions(android))
    }

    /**
     * An Android game's menu, opened by Y on its Library card, carries favourite too (it had a Favorite button on that card before this change): Add favorite for a game that
     * is not one, Remove favorite for one that is. An Android app is never a favourite and is offered neither. A on Add hands the reducer's own item id to the write.
     */
    @Test
    fun anAndroidGamesLibraryMenuCarriesTheFavouriteRowToo() {
        val notFavourite = nativeAppTileMenuState("com.example.game", "Game", null, isGame = true, favorite = false)
        assertFalse(notFavourite.removable)
        assertTrue(HomeTileMenuAction.ADD_FAVORITE in homeTileMenuActions(notFavourite))
        assertFalse(HomeTileMenuAction.REMOVE_FAVORITE in homeTileMenuActions(notFavourite))

        val favourite = nativeAppTileMenuState("com.example.game", "Game", null, isGame = true, favorite = true)
        assertTrue(favourite.removable)
        assertTrue(HomeTileMenuAction.REMOVE_FAVORITE in homeTileMenuActions(favourite))
        assertFalse(HomeTileMenuAction.ADD_FAVORITE in homeTileMenuActions(favourite))

        val app = nativeAppTileMenuState("com.example.app", "App", null, isGame = false, favorite = true)
        assertFalse("an app cannot be a favourite", app.removable)
        assertFalse(HomeTileMenuAction.ADD_FAVORITE in homeTileMenuActions(app))
        assertFalse(HomeTileMenuAction.REMOVE_FAVORITE in homeTileMenuActions(app))

        val added = mutableListOf<HomeShelfItemId>()
        val atAdd = notFavourite.copy(menuFocus = homeTileMenuActions(notFavourite).indexOf(HomeTileMenuAction.ADD_FAVORITE))
        val after = reduceHomeTileMenu(
            state = atAdd,
            action = ControllerAction.CONFIRM,
            onRemove = { error("adding never removes") },
            onLoadArt = {},
            onApplyPick = { _, _ -> },
            onAddFavorite = { added += it },
        )
        assertNull("the menu closes", after)
        assertEquals(listOf<HomeShelfItemId>(HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game"))), added)

        // The Android shelf's own Y menu (no card open for the package) keeps the menu it always had: no favourite row either way.
        val shelf = nativeAppTileMenuState("com.example.game", "Game", null, isGame = true)
        assertFalse(HomeTileMenuAction.ADD_FAVORITE in homeTileMenuActions(shelf))
        assertFalse(HomeTileMenuAction.REMOVE_FAVORITE in homeTileMenuActions(shelf))
    }

    /**
     * Grounds the removal of `openLibraryHeroPicker`'s old single-medium auto-confirm
     * (`MimirAppCoordinator.kt`): `libraryHeroPickerRow` always builds `HomeShelfItemId.Emulated`
     * (`GameRow` has no native/package-name variant — core-model's `libraryRepository.GameRow` models only
     * emulated titles), so `homeTileCustomEnrichActions`' LIBRARY_HERO branch always adds
     * CUSTOM_PREVIEW_CLIP beside CUSTOM_HERO here. The old `size <= 1` guard was therefore always false —
     * dead code — from this call site. If this ever goes false, `MimirAppCoordinator.kt`'s
     * `openLibraryHeroPicker` comment (and the dead-code claim it makes) must be revisited.
     */
    @Test
    fun openLibraryHeroPickerCustomEnrichActionsAreAlwaysTwoWide() {
        val menu = libraryHeroPickerMenuState(corpseParty())

        assertEquals(
            "WIDENED 2026-08-25 (R5/OWED-5) from two lanes to three: the shelf's face leads. Since 2026-10-10 " +
                "it is Icon art, the merged type that took Shelves' place at this door.",
            listOf(
                HomeTileMenuAction.CUSTOM_ART_ICON,
                HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD,
                HomeTileMenuAction.CUSTOM_HERO,
                HomeTileMenuAction.CUSTOM_PREVIEW_CLIP,
            ),
            homeTileCustomEnrichActions(menu),
        )
    }
}
