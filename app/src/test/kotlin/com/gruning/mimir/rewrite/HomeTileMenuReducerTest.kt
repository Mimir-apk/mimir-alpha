package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UXP-01 : the Edit menu is a pure reducer, so the exact per-type option ORDER and the
 * whole controller grammar are pinned here and cannot drift from the ruling.
 */
class HomeTileMenuReducerTest {
    private fun tomeId(label: String) = com.gruning.mimir.rewrite.feature.library.TomeId.Custom(
        java.util.UUID.nameUUIDFromBytes(label.toByteArray()),
    )

    private fun row(itemId: HomeShelfItemId, title: String = "Conker's Bad Fur Day") = HomeShelfRow(
        itemId = itemId,
        rank = 1L,
        addedAtMillis = 10L,
        title = title,
    )

    private val emulatedRow = row(HomeShelfItemId.Emulated(GameId("game-1")))
    private val androidGameRow = row(HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")), "Game")
    private val androidAppRow = row(HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app")), "App")

    private val candidates = listOf(
        SteamGridDbTileFetcher.Candidate(url = "https://cdn/a.png", thumbUrl = "https://cdn/a-thumb.png"),
        SteamGridDbTileFetcher.Candidate(url = "https://cdn/b.png", thumbUrl = "https://cdn/b-thumb.png"),
        SteamGridDbTileFetcher.Candidate(url = "https://cdn/c.png", thumbUrl = "https://cdn/c-thumb.png"),
    )

    private val artChoices = candidates.map { HomeTileArtChoice.Art(it) }

    @Test
    fun `Customize media opens the local picker straight from the menu without invoking eager provider load`() {
        var providerLoads = 0
        // no submenu stands between the Edit menu's Customize media row and the page.
        val menu = HomeTileMenuState(row = emulatedRow, removable = true)
        assertEquals(HomeTileMenuAction.CUSTOM_ENRICH, homeTileMenuActions(menu)[menu.menuFocus])
        val local = HomeCustomEnrichState(
            sessionId = "local",
            kind = EnrichMediaKind.ICON,
            cacheKey = "game-1",
            gameTitle = emulatedRow.title,
            queryDraft = emulatedRow.title,
        )
        var askedFor: Pair<EnrichMediaKind, MediaState>? = null

        val opened = reduceHomeTileMenu(
            state = menu,
            action = ControllerAction.CONFIRM,
            onRemove = {},
            onLoadArt = { providerLoads++ },
            onApplyPick = { _, _ -> },
            onOpenCustomEnrich = { kind, mediaState -> askedFor = kind to mediaState; local },
        )

        assertEquals(0, providerLoads)
        assertEquals(HomeTileMenuPhase.CustomEnrichPicker(local), opened?.phase)
        assertEquals("Home Edit opens on Icon art", EnrichMediaKind.ICON to MediaState.SHELF, askedFor)
        assertEquals(CustomMediaSelection(EnrichMediaKind.ICON, MediaState.SHELF), opened?.customEnrichFocus)
    }

    @Test
    fun `local upload slots are controller-selectable without shifting provider candidate indexes`() {
        var uploaded = false
        var imported = false
        var providerIndex = -1
        val choices = listOf(
            HomeTileArtChoice.UseDefault,
            HomeTileArtChoice.UploadedImage("/user/icon.png"),
            HomeTileArtChoice.ImportImage,
        ) + artChoices

        val uploadedState = reduce(
            HomeTileMenuState(
                row = emulatedRow,
                removable = true,
                phase = HomeTileMenuPhase.ArtPick(choices, focusedIndex = 1),
            ),
            ControllerAction.CONFIRM,
            onApplyUploadedArt = { uploaded = true },
        )
        assertTrue(uploaded)
        assertEquals(HomeTileMenuPhase.Applying, uploadedState?.phase)

        val importState = reduceHomeTileMenu(
            state = HomeTileMenuState(
                row = emulatedRow,
                removable = true,
                phase = HomeTileMenuPhase.ArtPick(choices, focusedIndex = 2),
            ),
            action = ControllerAction.CONFIRM,
            onRemove = {},
            onLoadArt = {},
            onApplyPick = { _, _ -> },
            onImportArt = { imported = true },
        )
        assertTrue(imported)
        assertTrue(importState?.phase is HomeTileMenuPhase.ArtPick)

        reduce(
            HomeTileMenuState(
                row = emulatedRow,
                removable = true,
                phase = HomeTileMenuPhase.ArtPick(choices, focusedIndex = 3),
            ),
            ControllerAction.CONFIRM,
            onApplyPick = { _, index -> providerIndex = index },
        )
        assertEquals(0, providerIndex)
    }

    @Test
    fun `art picker reopens on the active upload or provider candidate`() {
        val choices = listOf(
            HomeTileArtChoice.UseDefault,
            HomeTileArtChoice.UploadedImage("/user/icon.png"),
            HomeTileArtChoice.ImportImage,
        ) + artChoices

        assertEquals(
            1,
            homeArtPickerFocusedIndex(choices, hasActivePick = true, uploadedActive = true, providerIndex = null),
        )
        assertEquals(
            4,
            homeArtPickerFocusedIndex(choices, hasActivePick = true, uploadedActive = false, providerIndex = 1),
        )
        assertEquals(
            0,
            homeArtPickerFocusedIndex(choices, hasActivePick = false, uploadedActive = false, providerIndex = 1),
        )
    }

    @Test
    fun `successful import settles back on the retained image instead of closing blind`() {
        val art = homeArtPickAfterImport(
            HomeTileMenuPhase.ArtPick(
                choices = listOf(HomeTileArtChoice.UseDefault, HomeTileArtChoice.ImportImage) + artChoices,
                focusedIndex = 0,
                footerFocused = true,
            ),
            retainedPath = "/retained/icon.png",
        )
        assertEquals(HomeTileArtChoice.UploadedImage("/retained/icon.png"), art.choices[1])
        assertEquals(1, art.focusedIndex)
        assertFalse(art.footerFocused)

        val hero = homeHeroPickAfterImport(
            HomeTileMenuPhase.HeroPick(
                choices = listOf(
                    HomeTileHeroChoice.UseDefault,
                    HomeTileHeroChoice.ImportImage,
                    HomeTileHeroChoice.Hero(heroCandidate),
                ),
                footerFocused = true,
            ),
            retainedPath = "/retained/hero.jpg",
        )
        assertEquals(HomeTileHeroChoice.UploadedImage("/retained/hero.jpg"), hero.choices[1])
        assertEquals(1, hero.focusedIndex)
        assertFalse(hero.footerFocused)
    }

    @Test
    fun `upload is a separate footer lane and never a strip thumbnail`() {
        val choices = listOf(
            HomeTileArtChoice.UseDefault,
            HomeTileArtChoice.UploadedImage("/user/icon.png"),
            HomeTileArtChoice.ImportImage,
        ) + artChoices
        val base = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.ArtPick(choices, focusedIndex = 3),
        )

        assertEquals(listOf(0, 1, 3, 4, 5), homeArtStripIndices(choices))
        val beforeDown = MimirNavFeedback.epoch
        val footer = reduce(base, ControllerAction.NAV_DOWN)!!
        val footerPhase = footer.phase as HomeTileMenuPhase.ArtPick
        assertTrue(footerPhase.footerFocused)
        assertEquals(3, footerPhase.focusedIndex)
        assertEquals(beforeDown + 1, MimirNavFeedback.epoch)

        val beforeInert = MimirNavFeedback.epoch
        assertEquals(footer, reduce(footer, ControllerAction.NAV_RIGHT))
        assertEquals(beforeInert, MimirNavFeedback.epoch)

        var imported = false
        val importing = reduce(footer, ControllerAction.CONFIRM, onImportArt = { imported = true })!!
        assertTrue(imported)
        assertEquals(footer.phase, importing.phase)

        val strip = reduce(footer, ControllerAction.NAV_UP)!!
        val stripPhase = strip.phase as HomeTileMenuPhase.ArtPick
        assertFalse(stripPhase.footerFocused)
        assertEquals(3, stripPhase.focusedIndex)
        assertEquals(
            homeTileMenuPhaseTransitionKey(footerPhase),
            homeTileMenuPhaseTransitionKey(stripPhase),
        )
    }

    @Test
    fun `strip navigation skips the footer import identity`() {
        val choices = listOf(
            HomeTileArtChoice.UseDefault,
            HomeTileArtChoice.UploadedImage("/user/icon.png"),
            HomeTileArtChoice.ImportImage,
        ) + artChoices
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.ArtPick(choices, focusedIndex = 1),
        )

        assertEquals(
            3,
            (reduce(state, ControllerAction.NAV_RIGHT)!!.phase as HomeTileMenuPhase.ArtPick).focusedIndex,
        )
        assertEquals(
            0,
            (reduce(state, ControllerAction.NAV_LEFT)!!.phase as HomeTileMenuPhase.ArtPick).focusedIndex,
        )
    }

    private val heroCandidate = CustomHeroCandidate(
        source = CustomHeroSource.STEAMGRIDDB,
        mediaType = CustomHeroMediaType.STATIC,
        remoteUrl = "https://cdn2.steamgriddb.com/hero.png",
    )
    private val clipCandidate = CustomHeroCandidate(
        source = CustomHeroSource.SCREENSCRAPER,
        mediaType = CustomHeroMediaType.VIDEO,
        remoteUrl = "https://media.screenscraper.fr/clip.mp4",
    )

    private fun reduce(
        state: HomeTileMenuState,
        action: ControllerAction,
        onRemove: (HomeShelfItemId) -> Unit = { },
        onLoadArt: () -> Unit = { },
        onApplyPick: (SteamGridDbTileFetcher.Candidate, Int) -> Unit = { _, _ -> },
        collectionsFor: () -> List<HomeTileCollectionEntry> = { emptyList() },
        onToggleCollection: (com.gruning.mimir.rewrite.feature.library.TomeId.Custom) -> Unit = {},
        onRemoveFromTome: (com.gruning.mimir.rewrite.feature.library.TomeId.Custom) -> Unit = {},
        onLoadHero: () -> Unit = {},
        onApplyHero: (CustomHeroCandidate) -> Unit = {},
        onLoadClip: () -> Unit = {},
        onApplyClip: (CustomHeroCandidate) -> Unit = {},
        onResetArt: () -> Unit = {},
        onResetHero: () -> Unit = {},
        onResetClip: () -> Unit = {},
        onOpenAchievements: () -> Unit = {},
        onImportArt: () -> Unit = {},
        onApplyUploadedArt: () -> Unit = {},
        onImportHero: () -> Unit = {},
        onApplyUploadedHero: () -> Unit = {},
        onOpenCustomEnrich: (EnrichMediaKind, MediaState) -> HomeCustomEnrichState? = { _, _ -> null },
        onEnrich: () -> Unit = {},
        onDownloadGameMusic: () -> Unit = {},
    ) = reduceHomeTileMenu(
        state, action, onRemove, onLoadArt, onApplyPick, collectionsFor, onToggleCollection, onRemoveFromTome,
        onLoadHero, onApplyHero, onLoadClip, onApplyClip, onResetArt, onResetHero, onResetClip,
        onOpenAchievements, onImportArt, onApplyUploadedArt, onImportHero, onApplyUploadedHero,
        onOpenCustomEnrich, onEnrich, onDownloadGameMusic,
    )

    // ── Exact per-type ordering ──────────────────────────────────────────────────────

    @Test
    fun `an emulated member gets five spacious top-level options in the approved order`() {
        assertEquals(
            listOf(
                "Customize media",
                // Music left the Customize media page and is a row of its own, right after it.
                "Download music for this game",
                "Add to Tome",
                "Achievements",
                "Remove favorite",
            ),
            homeTileMenuOptions(HomeTileMenuState(row = emulatedRow, removable = true)),
        )
    }

    @Test
    fun `an Android game gets exactly three options and no dead emulated-only rows`() {
        val options = homeTileMenuOptions(HomeTileMenuState(row = androidGameRow, removable = true))

        assertEquals(listOf("Customize media", "Add to Tome", "Remove favorite"), options)
        // The ruling is explicit: do NOT render disabled/dead Android rows.
        assertFalse(options.contains("Hero art"))
        assertFalse(options.contains("Achievements"))
    }

    @Test
    fun `custom Tome edit removes once and closes instead of reopening collection picker`() {
        val activeTome = tomeId("cabinet")
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            activeCustomTomeId = activeTome,
            menuFocus = 2,
        )
        var removed: com.gruning.mimir.rewrite.feature.library.TomeId.Custom? = null

        assertEquals(
            listOf(
                "Customize media",
                "Download music for this game",
                "Remove from Tome",
                "Achievements",
                "Remove favorite",
            ),
            homeTileMenuOptions(state),
        )
        assertNull(
            reduce(
                state = state,
                action = ControllerAction.CONFIRM,
                onRemoveFromTome = { removed = it },
            ),
        )
        assertEquals(activeTome, removed)
    }

    @Test
    fun `an Android app defensively omits dead Tome and Favorite actions`() {
        assertEquals(
            listOf("Customize media"),
            homeTileMenuOptions(HomeTileMenuState(row = androidAppRow, removable = true)),
        )
    }

    @Test
    fun `Remove favorite is always last and is omitted when the row is not a durable member`() {
        listOf(emulatedRow, androidGameRow).forEach { row ->
            val member = homeTileMenuOptions(HomeTileMenuState(row = row, removable = true))
            assertEquals("Remove favorite", member.last())

            val recencyOnly = homeTileMenuOptions(HomeTileMenuState(row = row, removable = false))
            assertFalse(recencyOnly.contains("Remove favorite"))
        }
    }

    @Test
    fun `Custom Enrich leads for every one of the three item types`() {
        listOf(emulatedRow, androidGameRow, androidAppRow).forEach { row ->
            assertEquals(
                "Customize media",
                homeTileMenuOptions(HomeTileMenuState(row = row, removable = true)).first(),
            )
        }
    }

    @Test
    fun `Custom Enrich keeps physical and clips emulated-only while Android gets Icon and Hero`() {
        assertEquals(
            // Hero names the face a game wears, so Cartridge Hero beside Hero art is deliberate.
            // The face is one medium with two wirings, and the menu lists those wirings.
            // Music has its own top-level Edit row. Icon art is the unified shelf-face type;
            // the two face rows are Icon art and Launch card.
            listOf("Icon art", "Launch card", "Hero art", "Hero Motion"),
            homeTileCustomEnrichOptions(HomeTileMenuState(row = emulatedRow, removable = true)),
        )
        listOf(androidGameRow, androidAppRow).forEach { row ->
            assertEquals(
                listOf("Icon art", "Launch card", "Hero art"),
                homeTileCustomEnrichOptions(HomeTileMenuState(row = row, removable = true)),
            )
        }
    }

    @Test
    fun `Custom Enrich keeps four full targets inside certified square geometry`() {
        assertTrue(homeTileCustomEnrichRequiredHeightDp(4, compactSquare = true, fontScale = 1.3f) <= 360)
    }

    @Test
    fun `Edit menu geometry normalizes RP5 Odin and common aspect ratios`() {
        assertEquals(
            HomeTileMenuGeometry(compactLayout = false, compactTypography = true),
            homeTileMenuGeometry(853f, 480f, 1f),
        )
        assertEquals(
            HomeTileMenuGeometry(compactLayout = true, compactTypography = true),
            homeTileMenuGeometry(833f, 468f, 1f),
        )
        listOf(
            360f to 360f, // 1:1
            540f to 360f, // 3:2
            480f to 360f, // 4:3
            360f to 640f, // vertical
        ).forEach { (width, height) ->
            assertEquals(
                "$width x $height",
                HomeTileMenuGeometry(compactLayout = true, compactTypography = true),
                homeTileMenuGeometry(width, height, 1f),
            )
        }
        assertEquals(
            HomeTileMenuGeometry(compactLayout = false, compactTypography = true),
            homeTileMenuGeometry(853f, 480f, 1.3f),
        )
    }

    @Test
    fun `menu focus clamps so Up from Custom Enrich cannot arm Remove`() {
        val state = HomeTileMenuState(row = emulatedRow, removable = true)

        assertEquals(1, reduce(state, ControllerAction.NAV_DOWN)!!.menuFocus)
        assertEquals(0, reduce(state, ControllerAction.NAV_UP)!!.menuFocus)
    }

    // ── Menu activation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Customize media opens the page directly and the top level routes the rest`() {
        val base = HomeTileMenuState(row = emulatedRow, removable = true)
        var downloadedMusic = false
        var openedAchievements = false
        var removed: HomeShelfItemId? = null
        val asked = mutableListOf<Pair<EnrichMediaKind, MediaState>>()
        val picker = HomeCustomEnrichState(
            sessionId = "routes",
            kind = EnrichMediaKind.ICON,
            cacheKey = "game-1",
            gameTitle = emulatedRow.title,
            queryDraft = emulatedRow.title,
        )

        // Customize media (row 0) opens the page at once: no submenu phase exists between it and the picker.
        val page = reduce(
            base.copy(menuFocus = 0),
            ControllerAction.CONFIRM,
            onOpenCustomEnrich = { kind, mediaState -> asked += kind to mediaState; picker },
        )!!
        assertEquals(HomeTileMenuPhase.CustomEnrichPicker(picker), page.phase)
        assertEquals(listOf(EnrichMediaKind.ICON to MediaState.SHELF), asked)
        // 2026-10-10: Home Edit opens on Icon art, the one type for the shelf's face (ICON + SHELF). The Launch card is the
        // other face row (COVER + LAUNCH_CARD) and is reached by the Media chip.

        // Music (row 1) fires the engine's per-game fetch and closes, exactly as it did from the submenu.
        assertNull(reduce(base.copy(menuFocus = 1), ControllerAction.CONFIRM, onDownloadGameMusic = { downloadedMusic = true }))
        assertTrue(downloadedMusic)
        assertTrue(
            reduce(base.copy(menuFocus = 2), ControllerAction.CONFIRM)!!.phase
                is HomeTileMenuPhase.CollectionPick,
        )
        // Achievements opens the existing read-only card and closes the menu.
        assertNull(
            reduce(
                base.copy(menuFocus = 3),
                ControllerAction.CONFIRM,
                onOpenAchievements = { openedAchievements = true },
            ),
        )
        val armedRemove = reduce(base.copy(menuFocus = 4), ControllerAction.CONFIRM)!!
        assertEquals(HomeTileMenuPhase.ConfirmRemove, armedRemove.phase)
        assertNull(reduce(armedRemove, ControllerAction.CONFIRM, onRemove = { removed = it }))

        assertTrue(openedAchievements)
        assertEquals(emulatedRow.itemId, removed)
    }

    @Test
    fun `Remove requires a second confirm, and only Cancel and B disarm it`() {
        val base = HomeTileMenuState(row = emulatedRow, removable = true, menuFocus = 4)
        val armed = reduce(base, ControllerAction.CONFIRM)!!
        assertEquals(HomeTileMenuPhase.ConfirmRemove, armed.phase)
        assertEquals(HomeTileMenuPhase.Menu, reduce(armed, ControllerAction.CANCEL)!!.phase)
        assertEquals(HomeTileMenuPhase.Menu, reduce(armed, ControllerAction.SYSTEM_BACK)!!.phase)
    }

    /** Reported 10-09: RIGHT closed the confirm instead of moving to Cancel. LEFT and RIGHT move between Remove and Cancel; nothing else but B or Cancel leaves it. */
    @Test
    fun `the remove confirm opens on Remove, LEFT and RIGHT move between Remove and Cancel, and neither dismisses it`() {
        val base = HomeTileMenuState(row = emulatedRow, removable = true, menuFocus = 4)
        val armed = reduce(base, ControllerAction.CONFIRM)!!
        assertFalse("it opens on Remove", armed.confirmCancelFocused)

        val onCancel = reduce(armed, ControllerAction.NAV_RIGHT)!!
        assertEquals("RIGHT keeps the dialog open", HomeTileMenuPhase.ConfirmRemove, onCancel.phase)
        assertTrue("and lights Cancel", onCancel.confirmCancelFocused)

        val backOnRemove = reduce(onCancel, ControllerAction.NAV_LEFT)!!
        assertEquals(HomeTileMenuPhase.ConfirmRemove, backOnRemove.phase)
        assertFalse("LEFT lights Remove again", backOnRemove.confirmCancelFocused)

        // Clamped at both ends: LEFT on Remove and RIGHT on Cancel stay where they are, still open.
        assertEquals(armed, reduce(armed, ControllerAction.NAV_LEFT))
        assertEquals(onCancel, reduce(onCancel, ControllerAction.NAV_RIGHT))
        // UP and DOWN no longer dismiss it: only B or Cancel does.
        listOf(ControllerAction.NAV_UP, ControllerAction.NAV_DOWN).forEach { action ->
            assertEquals("$action leaves the dialog as it is", armed, reduce(armed, action))
            assertEquals("$action leaves the lit Cancel as it is", onCancel, reduce(onCancel, action))
        }
    }

    @Test
    fun `A answers the lit answer of the remove confirm, and B dismisses it from either`() {
        val base = HomeTileMenuState(row = emulatedRow, removable = true, menuFocus = 4)
        val armed = reduce(base, ControllerAction.CONFIRM)!!
        var removed: HomeShelfItemId? = null
        assertNull("A on Remove removes and closes", reduce(armed, ControllerAction.CONFIRM, onRemove = { removed = it }))
        assertEquals(emulatedRow.itemId, removed)

        removed = null
        val onCancel = reduce(armed, ControllerAction.NAV_RIGHT)!!
        val dismissed = reduce(onCancel, ControllerAction.CONFIRM, onRemove = { removed = it })!!
        assertNull("A on Cancel removes nothing", removed)
        assertEquals("and returns to the menu", HomeTileMenuPhase.Menu, dismissed.phase)
        assertFalse("with the confirm's focus reset", dismissed.confirmCancelFocused)

        listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { action ->
            listOf(armed, onCancel).forEach { from ->
                val back = reduce(from, action, onRemove = { removed = it })!!
                assertEquals(HomeTileMenuPhase.Menu, back.phase)
                assertFalse(back.confirmCancelFocused)
            }
        }
        assertNull("nothing was removed by a dismissal", removed)

        // Opening the confirm again lands on Remove, not on the Cancel last lit.
        val again = reduce(dismissed.copy(menuFocus = 4), ControllerAction.CONFIRM)!!
        assertFalse(again.confirmCancelFocused)
    }

    @Test
    fun `a tap on Remove lights Remove first, so a Cancel the pad had lit cannot turn it into a dismissal`() {
        val base = HomeTileMenuState(row = emulatedRow, removable = true, menuFocus = 4)
        val onCancel = reduce(reduce(base, ControllerAction.CONFIRM)!!, ControllerAction.NAV_RIGHT)!!
        val tapped = homeTileMenuWithFocusedPick(onCancel, 0)
        assertFalse(tapped.confirmCancelFocused)
        var removed: HomeShelfItemId? = null
        assertNull(reduce(tapped, ControllerAction.CONFIRM, onRemove = { removed = it }))
        assertEquals(emulatedRow.itemId, removed)
    }

    @Test
    fun `the page reopens on the type last edited and a legacy pick page backs into it`() {
        val hero = CustomMediaSelection(EnrichMediaKind.HERO, MediaState.SHELF)
        val state = HomeTileMenuState(row = emulatedRow, removable = true, customEnrichFocus = hero)
        val asked = mutableListOf<Pair<EnrichMediaKind, MediaState>>()
        val heroPicker = HomeCustomEnrichState(
            sessionId = "hero",
            kind = EnrichMediaKind.HERO,
            cacheKey = "game-1",
            gameTitle = emulatedRow.title,
            queryDraft = emulatedRow.title,
        )
        val open = { kind: EnrichMediaKind, mediaState: MediaState -> asked += kind to mediaState; heroPicker }

        // Reopening: the remembered type wins over the entry point's default (Icon art on Home Edit, since the 2026-10-10 merge of Shelves into it).
        val reopened = reduce(state, ControllerAction.CONFIRM, onOpenCustomEnrich = open)!!
        assertEquals(HomeTileMenuPhase.CustomEnrichPicker(heroPicker), reopened.phase)
        assertEquals(listOf(EnrichMediaKind.HERO to MediaState.SHELF), asked)

        // A legacy pick page (the strip a provider-less picker used to fall back to) backs INTO the page, on the
        // remembered type, rather than into the submenu this change retired.
        val loading = state.copy(phase = HomeTileMenuPhase.LoadingHero)
        val returned = reduce(loading, ControllerAction.CANCEL, onOpenCustomEnrich = open)!!
        assertEquals(HomeTileMenuPhase.CustomEnrichPicker(heroPicker), returned.phase)
        // With no page to open (a host that cannot build one) B lands on the Edit menu, never on a dead end.
        assertEquals(HomeTileMenuPhase.Menu, reduce(loading, ControllerAction.CANCEL)!!.phase)
    }

    @Test
    fun `an Android row's second option is Add to Tome, never Custom Hero`() {
        var loadedHero = false
        val state = HomeTileMenuState(row = androidGameRow, removable = true, menuFocus = 1)

        val next = reduce(state, ControllerAction.CONFIRM, onLoadHero = { loadedHero = true })!!

        assertTrue(next.phase is HomeTileMenuPhase.CollectionPick)
        assertFalse("an Android row must never reach the hero path", loadedHero)
    }

    @Test
    fun `Home Android package Customize Media includes Icon then Hero`() {
        listOf(androidGameRow, androidAppRow).forEach { row ->
            // #256: Hero is a value of the Media chip, reached by the type the page was last on (or L1/R1 / the panel).
            val state = HomeTileMenuState(
                row = row,
                removable = false,
                customEnrichFocus = CustomMediaSelection(EnrichMediaKind.HERO, MediaState.SHELF),
            )

            assertEquals(
                // Both face rows reach here too: Icon art (the shelf's face, one type since 2026-10-10) and the
                // Launch card. An Android game has a face like any other row and can be looked at in both
                // states; what it has no lane for is a clip.
                listOf(
                    HomeTileMenuAction.CUSTOM_ART_ICON,
                    HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD,
                    HomeTileMenuAction.CUSTOM_HERO,
                ),
                homeTileCustomEnrichActions(state),
            )

            val heroPicker = HomeCustomEnrichState(
                sessionId = "home-android-hero",
                kind = EnrichMediaKind.HERO,
                cacheKey = homeTileHeroStoreId(row.itemId).value,
                gameTitle = row.title,
                queryDraft = row.title,
            )
            val opened = reduceHomeTileMenu(
                state = state,
                action = ControllerAction.CONFIRM,
                onRemove = {},
                onLoadArt = {},
                onApplyPick = { _, _ -> },
                onOpenCustomEnrich = { kind, _ -> heroPicker.takeIf { kind == EnrichMediaKind.HERO } },
            )

            // The Android menu now lands on Icon and reaches Hero one lane later than it used to, because
            // Cover art sits between them (R5, 2026-08-25). Resolved by lane rather than by index so the
            // next insertion does not quietly retarget this.
            assertEquals(HomeTileMenuPhase.CustomEnrichPicker(heroPicker), opened?.phase)
            assertEquals(homeTileMenuCacheKey(row.itemId), homeTileHeroStoreId(row.itemId).value)
        }
    }

    /**
     * **This test previously asserted the opposite, and the change is deliberate.** It was
     * `Library emulated Hero shortcut remains Hero-only`, pinning `listOf(CUSTOM_HERO)`. The updated gamelist shortcut offers Hero art and Hero motion: the gamelist entry offers both
     * hero media now, and that old assertion is what made Hero Motion unreachable from the gamelist at all.
     *
     * Rewritten rather than deleted: the narrowness it protected is still real and still asserted below —
     * this entry is NOT Home Edit, and must not grow an icon or cartridge slot.
     */
    @Test
    fun `Library emulated Hero shortcut offers both hero media, and only those`() {
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = false,
            entryPoint = HomeTileMenuEntryPoint.LIBRARY_HERO,
        )

        assertEquals(
            "The shelf's face leads, then the two hero " +
                "media it already offered. The gamelist is where he looks at covers, so it was the least " +
                "sensible place to be unable to change one. Since 2026-10-10 that lane is Icon art (it was " +
                "Shelves), which takes a cover and a square alike.",
            listOf(
                HomeTileMenuAction.CUSTOM_ART_ICON,
                HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD,
                HomeTileMenuAction.CUSTOM_HERO,
                HomeTileMenuAction.CUSTOM_PREVIEW_CLIP,
            ),
            homeTileCustomEnrichActions(state),
        )
    }

    /** The narrowness the old assertion was really protecting: this is not Home Edit. */
    @Test
    fun `the Library hero entry offers the shelf's Icon art in place of Shelves, and no second shelf type`() {
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = false,
            entryPoint = HomeTileMenuEntryPoint.LIBRARY_HERO,
        )
        val actions = homeTileCustomEnrichActions(state)

        assertTrue(
            "Shelves and Icon art are ONE type, so the door that offered Shelves offers " +
                "Icon art -- the same page, the same pick lanes, the same Default",
            HomeTileMenuAction.CUSTOM_ART_ICON in actions,
        )
        assertTrue(
            "both face states are reachable here -- the gamelist is where he LOOKS at faces",
            HomeTileMenuAction.CUSTOM_ART_ICON in actions &&
                HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD in actions,
        )
        assertEquals(
            "and there is one entry per type, never Icon art twice",
            1,
            actions.count { it == HomeTileMenuAction.CUSTOM_ART_ICON },
        )
    }

    /**
     * The gamelist entry and the Home tile menu derive their action lists from one function:
     * the same game receives the same actions at both entry points. [homeTileMenuActions] takes no [HomeTileMenuEntryPoint] parameter
     * at all, so this is structurally guaranteed rather than coincidental -- this test pins that fact so a
     * future edit that starts branching the TOP-LEVEL list on entry point (reintroducing a second list) is
     * caught here, not just in the gamelist-shaped tests above.
     */
    @Test
    fun `top-level menu actions are one list for every entry point`() {
        val perEntryPoint = HomeTileMenuEntryPoint.entries.associateWith { entryPoint ->
            homeTileMenuActions(
                HomeTileMenuState(row = emulatedRow, removable = true, entryPoint = entryPoint),
            )
        }
        assertEquals(HomeTileMenuEntryPoint.entries.size, perEntryPoint.size)
        val shared = listOf(
            HomeTileMenuAction.CUSTOM_ENRICH,
            HomeTileMenuAction.ADD_TO_COLLECTION,
            HomeTileMenuAction.ACHIEVEMENTS,
            HomeTileMenuAction.REMOVE_FAVORITE,
        )
        // #256 deliberately ended "no entry point in the list": Music became a top-level row, right after Customize
        // media, on Home Edit and  the Library hero menu. Everything else is still ONE list.
        HomeTileMenuEntryPoint.entries.forEach { entryPoint ->
            val actions = perEntryPoint.getValue(entryPoint)
            assertEquals(
                shared.take(1) + HomeTileMenuAction.DOWNLOAD_GAME_MUSIC + shared.drop(1),
                actions,
            )
        }
        // Emulated games only: an Android row has no GameId to fetch music for.
        assertFalse(
            HomeTileMenuAction.DOWNLOAD_GAME_MUSIC in
                homeTileMenuActions(HomeTileMenuState(row = androidGameRow, removable = true)),
        )
    }

    /**
     * the Library hero menu gets the same Music row as Home Edit, right after Customize media, for an
     * emulated game; an Android row has no GameId so never does.
     */
    @Test
    fun `the Library hero menu lists Download music right after Customize media for an emulated row`() {
        val state = HomeTileMenuState(row = emulatedRow, removable = false, entryPoint = HomeTileMenuEntryPoint.LIBRARY_HERO, offerFavorite = true)
        assertEquals(
            listOf(
                HomeTileMenuAction.CUSTOM_ENRICH,
                HomeTileMenuAction.DOWNLOAD_GAME_MUSIC,
                HomeTileMenuAction.ADD_TO_COLLECTION,
                HomeTileMenuAction.ACHIEVEMENTS,
                // A Library game that is not a favourite is offered the add row, last (favourite lives in this menu).
                HomeTileMenuAction.ADD_FAVORITE,
            ),
            homeTileMenuActions(state),
        )
        assertFalse(
            HomeTileMenuAction.DOWNLOAD_GAME_MUSIC in
                homeTileMenuActions(state.copy(row = androidGameRow)),
        )
    }

    // ── Enrich returns for VERIFIED_PARTIAL library games  ─────────────────

    @Test
    fun `offerEnrich leads the top-level list ahead of Customize media`() {
        val state = HomeTileMenuState(row = emulatedRow, removable = true, offerEnrich = true)
        assertEquals(
            listOf(
                HomeTileMenuAction.ENRICH,
                HomeTileMenuAction.CUSTOM_ENRICH,
                HomeTileMenuAction.DOWNLOAD_GAME_MUSIC,
                HomeTileMenuAction.ADD_TO_COLLECTION,
                HomeTileMenuAction.ACHIEVEMENTS,
                HomeTileMenuAction.REMOVE_FAVORITE,
            ),
            homeTileMenuActions(state),
        )
    }

    @Test
    fun `offerEnrich defaults false and leaves the top-level list exactly as before`() {
        val state = HomeTileMenuState(row = emulatedRow, removable = true)
        assertFalse(state.offerEnrich)
        assertFalse(HomeTileMenuAction.ENRICH in homeTileMenuActions(state))
    }

    @Test
    fun `CONFIRM on Enrich fires onEnrich once and closes the menu`() {
        val state = HomeTileMenuState(row = emulatedRow, removable = true, offerEnrich = true)
        assertEquals(HomeTileMenuAction.ENRICH, homeTileMenuActions(state)[state.menuFocus])

        var enrichCalls = 0
        val next = reduce(state, ControllerAction.CONFIRM, onEnrich = { enrichCalls++ })

        assertEquals(1, enrichCalls)
        assertNull(next)
    }

    // ── Custom Art Icon picker, including the reset slot ─────────────────────────────────────────

    @Test
    fun `art pick navigates with wrap and applies the focused candidate`() {
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.ArtPick(artChoices),
        )
        assertEquals(2, (reduce(state, ControllerAction.NAV_LEFT)!!.phase as HomeTileMenuPhase.ArtPick).focusedIndex)
        val right = reduce(state, ControllerAction.NAV_RIGHT)!!

        var applied: Pair<SteamGridDbTileFetcher.Candidate, Int>? = null
        val next = reduce(right, ControllerAction.CONFIRM, onApplyPick = { c, i -> applied = c to i })!!

        assertEquals(candidates[1] to 1, applied)
        assertEquals(HomeTileMenuPhase.Applying, next.phase)
    }

    @Test
    fun `the reset slot clears the override and does not shift candidate indices`() {
        // "Automatic / Use default" leads the strip, so the candidate at slot 2 is still candidate 1.
        val withReset = listOf(HomeTileArtChoice.UseDefault) + artChoices
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.ArtPick(withReset, focusedIndex = 0),
        )
        // a refused reset HOLDS THE MENU OPEN WITH A NOTE, so the reducer can no
        // longer close synchronously before onResetArt()'s suspend clear even runs -- it must transition
        // to Applying (same as the sibling Art-apply arm) and let the coordinator decide close vs note
        // once the actual result is known. Previously this asserted `assertNull(...)`.
        var reset = false
        val next = reduce(state, ControllerAction.CONFIRM, onResetArt = { reset = true })
        assertTrue(reset)
        assertEquals(HomeTileMenuPhase.Applying, next?.phase)

        var applied: Pair<SteamGridDbTileFetcher.Candidate, Int>? = null
        reduce(
            state.copy(phase = HomeTileMenuPhase.ArtPick(withReset, focusedIndex = 2)),
            ControllerAction.CONFIRM,
            onApplyPick = { c, i -> applied = c to i },
        )
        assertEquals("slot 2 is candidate index 1, not 2", candidates[1] to 1, applied)
    }

    @Test
    fun `an empty art strip cannot apply and stays put`() {
        var applied = false
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.ArtPick(emptyList()),
        )

        val next = reduce(state, ControllerAction.CONFIRM, onApplyPick = { _, _ -> applied = true })!!

        assertFalse(applied)
        // Previously this fell through to Applying, which showed "Applying…" forever with nothing
        // in flight. An empty strip now simply holds.
        assertEquals(state.phase, next.phase)
    }

    // ── Custom Hero picker ──────────────────────────────────────────────────────────────────────

    @Test
    fun `hero pick applies the focused candidate and resets from the default slot`() {
        val choices = listOf(HomeTileHeroChoice.UseDefault, HomeTileHeroChoice.Hero(heroCandidate))
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.HeroPick(choices, focusedIndex = 1),
        )

        var applied: CustomHeroCandidate? = null
        assertEquals(
            HomeTileMenuPhase.Applying,
            reduce(state, ControllerAction.CONFIRM, onApplyHero = { applied = it })!!.phase,
        )
        assertEquals(heroCandidate, applied)

        // same as the icon-art reset above -- must hold open (Applying), not
        // close synchronously. Previously this asserted `assertNull(...)`.
        var reset = false
        val resetNext = reduce(
            state.copy(phase = HomeTileMenuPhase.HeroPick(choices, focusedIndex = 0)),
            ControllerAction.CONFIRM,
            onResetHero = { reset = true },
        )
        assertTrue(reset)
        assertEquals(HomeTileMenuPhase.Applying, resetNext?.phase)
    }

    @Test
    fun `hero upload footer has the same lane grammar while clip has no footer`() {
        val heroChoices = listOf(
            HomeTileHeroChoice.UseDefault,
            HomeTileHeroChoice.ImportImage,
            HomeTileHeroChoice.Hero(heroCandidate),
        )
        val heroState = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.HeroPick(heroChoices, focusedIndex = 2),
        )
        assertEquals(listOf(0, 2), homeHeroStripIndices(heroChoices))
        val footer = reduce(heroState, ControllerAction.NAV_DOWN)!!
        assertTrue((footer.phase as HomeTileMenuPhase.HeroPick).footerFocused)
        var imported = false
        reduce(footer, ControllerAction.CONFIRM, onImportHero = { imported = true })
        assertTrue(imported)
        val restored = reduce(footer, ControllerAction.NAV_UP)!!.phase as HomeTileMenuPhase.HeroPick
        assertFalse(restored.footerFocused)
        assertEquals(2, restored.focusedIndex)

        val clipState = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.ClipPick(listOf(HomeTileClipChoice.Clip(clipCandidate))),
        )
        val before = MimirNavFeedback.epoch
        assertEquals(clipState, reduce(clipState, ControllerAction.NAV_DOWN))
        assertEquals(before, MimirNavFeedback.epoch)
    }

    @Test
    fun `clip pick applies and resets independently`() {
        val choices = listOf(HomeTileClipChoice.UseDefault, HomeTileClipChoice.Clip(clipCandidate))
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.ClipPick(choices, focusedIndex = 1),
        )
        var applied: CustomHeroCandidate? = null
        assertEquals(
            HomeTileMenuPhase.Applying,
            reduce(state, ControllerAction.CONFIRM, onApplyClip = { applied = it })!!.phase,
        )
        assertEquals(clipCandidate, applied)

        // same as the icon-art reset above -- must hold open (Applying), not
        // close synchronously. Previously this asserted `assertNull(...)`.
        var reset = false
        val resetNext = reduce(
            state.copy(phase = HomeTileMenuPhase.ClipPick(choices, focusedIndex = 0)),
            ControllerAction.CONFIRM,
            onResetClip = { reset = true },
        )
        assertTrue(reset)
        assertEquals(HomeTileMenuPhase.Applying, resetNext?.phase)
    }

    @Test
    fun `an unavailable provider explains itself and B returns to the Edit menu when no page can open`() {
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.ProviderUnavailable("Add a SteamGridDB key in Settings."),
        )

        // Confirm must not close or advance — there is nothing to pick.
        assertEquals(state, reduce(state, ControllerAction.CONFIRM))
        assertEquals(HomeTileMenuPhase.Menu, reduce(state, ControllerAction.CANCEL)!!.phase)
    }

    // ── Collections ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `add-to-collection opens the pick phase with current membership`() {
        val state = HomeTileMenuState(row = emulatedRow, removable = true, menuFocus = 2)

        val next = reduce(
            state,
            ControllerAction.CONFIRM,
            collectionsFor = {
                listOf(
                    HomeTileCollectionEntry("RPGs", isMember = true, id = tomeId("RPGs")),
                    HomeTileCollectionEntry("Couch", isMember = false, id = tomeId("Couch")),
                )
            },
        )!!

        val phase = next.phase as HomeTileMenuPhase.CollectionPick
        assertEquals(listOf("RPGs", "Couch"), phase.entries.map { it.name })
        assertTrue(phase.entries[0].isMember)
    }

    @Test
    fun `collection pick toggles in place and backs out to menu`() {
        var toggled: com.gruning.mimir.rewrite.feature.library.TomeId.Custom? = null
        val entry = HomeTileCollectionEntry("RPGs", isMember = false, id = tomeId("RPGs"))
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.CollectionPick(
                listOf(entry),
            ),
        )

        val next = reduce(state, ControllerAction.CONFIRM, onToggleCollection = { toggled = it })!!

        assertEquals(entry.id, toggled)
        assertTrue((next.phase as HomeTileMenuPhase.CollectionPick).entries[0].isMember)
        assertEquals(HomeTileMenuPhase.Menu, reduce(next, ControllerAction.CANCEL)!!.phase)
    }

    @Test
    fun `empty collection pick swallows confirm and nav`() {
        val state = HomeTileMenuState(
            row = emulatedRow,
            removable = false,
            phase = HomeTileMenuPhase.CollectionPick(emptyList()),
        )
        assertEquals(state, reduce(state, ControllerAction.CONFIRM))
        assertEquals(state, reduce(state, ControllerAction.NAV_DOWN))
    }

    @Test
    fun `touch focus updates every picker phase before activation`() {
        val phases = listOf<HomeTileMenuPhase>(
            HomeTileMenuPhase.ArtPick(artChoices),
            HomeTileMenuPhase.HeroPick(
                listOf(HomeTileHeroChoice.UseDefault, HomeTileHeroChoice.Hero(heroCandidate)),
            ),
            HomeTileMenuPhase.ClipPick(
                listOf(HomeTileClipChoice.UseDefault, HomeTileClipChoice.Clip(clipCandidate)),
            ),
            HomeTileMenuPhase.CollectionPick(
                listOf(
                    HomeTileCollectionEntry("A", false, tomeId("A")),
                    HomeTileCollectionEntry("B", false, tomeId("B")),
                ),
            ),
        )

        phases.forEach { phase ->
            val updated = homeTileMenuWithFocusedPick(
                HomeTileMenuState(row = emulatedRow, removable = true, phase = phase),
                index = 1,
            )
            val focused = when (val next = updated.phase) {
                is HomeTileMenuPhase.ArtPick -> next.focusedIndex
                is HomeTileMenuPhase.HeroPick -> next.focusedIndex
                is HomeTileMenuPhase.ClipPick -> next.focusedIndex
                is HomeTileMenuPhase.CollectionPick -> next.focusedIndex
                else -> -1
            }
            assertEquals(1, focused)
        }
    }

    // ── Back grammar ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `back preserves the Customize media hierarchy and closes only from top level`() {
        val base = HomeTileMenuState(row = emulatedRow, removable = true)
        val page = HomeCustomEnrichState(
            sessionId = "back",
            kind = EnrichMediaKind.COVER,
            cacheKey = "game-1",
            gameTitle = emulatedRow.title,
            queryDraft = emulatedRow.title,
        )

        // The legacy pick pages back into the page itself (ruling 9), not into a submenu that no longer exists.
        listOf(
            HomeTileMenuPhase.ArtPick(artChoices, focusedIndex = 2),
            HomeTileMenuPhase.HeroPick(listOf(HomeTileHeroChoice.Hero(heroCandidate))),
            HomeTileMenuPhase.ClipPick(listOf(HomeTileClipChoice.Clip(clipCandidate))),
        ).forEach { phase ->
            assertEquals(
                HomeTileMenuPhase.CustomEnrichPicker(page),
                reduce(
                    base.copy(phase = phase),
                    ControllerAction.CANCEL,
                    onOpenCustomEnrich = { _, _ -> page },
                )!!.phase,
            )
        }
        assertEquals(
            HomeTileMenuPhase.Menu,
            reduce(
                base.copy(
                    phase = HomeTileMenuPhase.CollectionPick(
                        listOf(HomeTileCollectionEntry("RPGs", false, tomeId("RPGs"))),
                    ),
                ),
                ControllerAction.CANCEL,
            )!!.phase,
        )

        assertNull(reduce(base, ControllerAction.CANCEL))
    }

    @Test
    fun `loading backs out to the Edit menu while applying backs out of the in-flight surface`() {
        listOf(
            HomeTileMenuPhase.LoadingArt,
            HomeTileMenuPhase.LoadingHero,
            HomeTileMenuPhase.LoadingClip,
        ).forEach { phase ->
            val state = HomeTileMenuState(row = emulatedRow, removable = true, phase = phase)
            assertEquals(state, reduce(state, ControllerAction.CONFIRM))
            assertEquals(state, reduce(state, ControllerAction.NAV_LEFT))
            assertEquals(HomeTileMenuPhase.Menu, reduce(state, ControllerAction.SYSTEM_BACK)!!.phase)
        }
        val applying = HomeTileMenuState(
            row = emulatedRow,
            removable = true,
            phase = HomeTileMenuPhase.Applying,
        )
        assertNull(reduce(applying, ControllerAction.SYSTEM_BACK))
    }

    @Test
    fun `phase fade key ignores focus movement but changes between submenu pages`() {
        val first = HomeTileMenuPhase.ArtPick(artChoices, focusedIndex = 0)
        val moved = first.copy(focusedIndex = 2)

        assertEquals(homeTileMenuPhaseTransitionKey(first), homeTileMenuPhaseTransitionKey(moved))
        assertTrue(homeTileMenuPhaseTransitionKey(first) != homeTileMenuPhaseTransitionKey(HomeTileMenuPhase.LoadingArt))
    }
}
