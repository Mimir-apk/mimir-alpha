package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.ui.mimirPlatformShelfmarkCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

class HomeShelfModelsTest {
    @Test
    fun metadataCopyKeepsUnknownTruthDistinctFromZeroAndProviderNone() {
        assertEquals("SESSION TIME", HomeLaunchExternalSessionFactLabel)
        assertEquals("NOT TRACKED", homeShelfExternalSessionElapsedLabel(null))
        assertEquals("NO MEASURED ELAPSED", homeShelfExternalSessionElapsedLabel(0L))
        // A game never launched (no last-played) says so, with no store entry or a zero; a played game keeps the two lines above, and a measured total is a fact.
        assertEquals("NOT PLAYED YET", homeShelfExternalSessionElapsedLabel(null, neverPlayed = true))
        assertEquals("NOT PLAYED YET", homeShelfExternalSessionElapsedLabel(0L, neverPlayed = true))
        assertEquals("1 min", homeShelfExternalSessionElapsedLabel(30_000L, neverPlayed = true))
        assertEquals("Less than a minute", homeShelfExternalSessionElapsedLabel(29_000L))
        assertEquals("1 min", homeShelfExternalSessionElapsedLabel(30_000L))
        assertEquals("1 h 31 min", homeShelfExternalSessionElapsedLabel(5_430_000L))
        assertEquals(
            "No English synopsis was available for this game.",
            homeShelfDescriptionText(null, "screenscraper", screenScraperConnected = true),
        )
        assertEquals(
            "No English synopsis was available for this game.",
            homeShelfDescriptionText(null, "screenscraper", screenScraperConnected = false),
        )
        assertEquals(
            "No description yet.",
            homeShelfDescriptionText(null, null, screenScraperConnected = true),
        )
        assertEquals(
            "No description yet. Connect ScreenScraper in Settings › Appearance › Enrichment & artwork.",
            homeShelfDescriptionText(null, null, screenScraperConnected = false),
        )
    }

    /**
     * Thor drive 2026-10-08 (D43): the launch card's About showed `&quot;Pop-Up Duel.&quot;` as written. The stored synopsis stays raw; the text the card shows is decoded.
     */
    @Test
    fun `the About text shows decoded character references, and a double-escaped one only once`() {
        assertEquals(
            "Chocobo Tales' \"Pop-Up Duel.\" & more",
            homeShelfDescriptionText(
                "  Chocobo Tales&#39; &quot;Pop-Up Duel.&quot; &amp; more ",
                "screenscraper",
                screenScraperConnected = true,
            ),
        )
        assertEquals(
            "&quot;Pop-Up Duel.&quot;",
            homeShelfDescriptionText("&amp;quot;Pop-Up Duel.&amp;quot;", "screenscraper", screenScraperConnected = true),
        )
        // Plain text, and the no-description copy, are untouched.
        assertEquals("A plain synopsis.", homeShelfDescriptionText("A plain synopsis.", null, screenScraperConnected = false))
        // A synopsis that was only references is no synopsis at all, not an empty About.
        assertEquals(
            "No description yet.",
            homeShelfDescriptionText("&nbsp;&#32;", null, screenScraperConnected = true),
        )
    }

    @Test
    fun `pc_games rows never see ScreenScraper-attributed no-description copy`() {
        // R-24: ScreenScraper has no system id for pc_games and structurally never verifies it, so
        // neither the "No English synopsis" language-policy copy nor the "Connect ScreenScraper" copy is
        // ever true for this platform -- regardless of descriptionSource/screenScraperConnected.
        assertEquals(
            "No description yet. Add a SteamGridDB key in Settings › Appearance › Enrichment & artwork.",
            homeShelfDescriptionText(null, null, screenScraperConnected = false, isPcGames = true),
        )
        assertEquals(
            "No description yet. Add a SteamGridDB key in Settings › Appearance › Enrichment & artwork.",
            homeShelfDescriptionText(null, null, screenScraperConnected = true, isPcGames = true),
        )
        // A real Steam-verified synopsis still renders as plain trusted text, same as any other provider.
        assertEquals(
            "A Steam synopsis.",
            homeShelfDescriptionText("A Steam synopsis.", "steam:store", screenScraperConnected = false, isPcGames = true),
        )
    }

    /**
     * a `pc_games` row with no description was always sent to
     * "Use Enrich in Library" regardless of SteamGridDB key presence -- Enrich for that platform is
     * SteamGridDB's alone (R-24), so a keyless row was pointed at a button that returns `GateClosed`. Pins
     * the branch on `steamGridDbKeyPresent`, and that every other platform's copy is byte-identical to
     * before regardless of the new parameter (the differential guard: this signal must never leak past
     * the `isPcGames` gate).
     */
    @Test
    fun `pc_games description copy branches on SteamGridDB key presence, never leaking to other platforms`() {
        assertEquals(
            "No description yet. Add a SteamGridDB key in Settings › Appearance › Enrichment & artwork.",
            homeShelfDescriptionText(
                null,
                null,
                screenScraperConnected = false,
                isPcGames = true,
                steamGridDbKeyPresent = false,
            ),
        )
        assertEquals(
            "No description yet.",
            homeShelfDescriptionText(
                null,
                null,
                screenScraperConnected = false,
                isPcGames = true,
                steamGridDbKeyPresent = true,
            ),
        )
        // Differential guard: a non-pc_games row is byte-identical to today regardless of the new
        // steamGridDbKeyPresent parameter -- it must never reach a branch that only isPcGames can select.
        assertEquals(
            homeShelfDescriptionText(null, null, screenScraperConnected = true, steamGridDbKeyPresent = false),
            homeShelfDescriptionText(null, null, screenScraperConnected = true, steamGridDbKeyPresent = true),
        )
        assertEquals(
            "No description yet.",
            homeShelfDescriptionText(null, null, screenScraperConnected = true, steamGridDbKeyPresent = true),
        )
        assertEquals(
            homeShelfDescriptionText(null, null, screenScraperConnected = false, steamGridDbKeyPresent = false),
            homeShelfDescriptionText(null, null, screenScraperConnected = false, steamGridDbKeyPresent = true),
        )
        assertEquals(
            "No description yet. Connect ScreenScraper in Settings › Appearance › Enrichment & artwork.",
            homeShelfDescriptionText(null, null, screenScraperConnected = false, steamGridDbKeyPresent = true),
        )
    }

    @Test
    fun quickRailIsTheFiveRunicHomeCommandsThenTheFilesEndCap() {
        // FUX-01 §4.1: Home, Library, Brightness, Settings, Quick Launch — in that order.
        // adds a SIXTH entry, and it is deliberately last and deliberately not
        // a peer: the five above move between surfaces, Files opens one. It terminates the rail.
        assertEquals(
            listOf(
                MimirQuickRailAction.HOME,
                MimirQuickRailAction.LIBRARY,
                MimirQuickRailAction.BRIGHTNESS,
                MimirQuickRailAction.SETTINGS,
                MimirQuickRailAction.QUICK_LAUNCH,
                MimirQuickRailAction.FILES,
            ),
            HomeShelfQuickActions,
        )
        assertEquals(6, HomeShelfQuickActions.map(::homeShelfQuickRailKey).distinct().size)
        // The end-cap is last and stays last. This list is the rail's DRAW order and its FOCUS order at
        // once, so an entry inserted mid-list silently reorders controller travel as well as pixels.
        assertEquals(MimirQuickRailAction.FILES, HomeShelfQuickActions.last())
    }

    @Test
    fun typedIdentitiesProduceDistinctEscapedFocusKeys() {
        val emulated = HomeShelfItemId.Emulated(GameId("same/id with space"))
        val android = HomeShelfItemId.AndroidGame(AndroidPackageName("same/id with space"))

        assertEquals("home/shelf/emulated/same%2Fid%20with%20space", homeShelfFocusKey(emulated).value)
        assertEquals("home/shelf/android-game/same%2Fid%20with%20space", homeShelfFocusKey(android).value)
    }

    @Test
    fun canonicalRowsUseDurableOrderAndRemoveDuplicateFocusIdentity() {
        val a = row("a", rank = 2_048L, addedAt = 20L)
        val b = row("b", rank = 1_024L, addedAt = 30L)
        val duplicateB = b.copy(title = "Duplicate", addedAtMillis = 40L)
        val c = row("c", rank = 1_024L, addedAt = 10L)

        assertEquals(
            listOf("c", "b", "a"),
            canonicalHomeShelfRows(listOf(a, duplicateB, b, c)).map { it.title },
        )
    }

    @Test
    fun fortyStepTraversalAndImmediateReversalNeverBlock() {
        val rows = (0 until 50).map { row("game-$it", rank = it * 1_024L, addedAt = it.toLong()) }
        val authority = registeredHomeAuthority(rows)

        repeat(40) {
            assertTrue(dispatch(authority, rows, ControllerAction.NAV_RIGHT))
        }
        assertEquals(rows[40].focusKey, authority.state.value.focused)

        repeat(40) {
            assertTrue(dispatch(authority, rows, ControllerAction.NAV_LEFT))
        }
        assertEquals(rows.first().focusKey, authority.state.value.focused)
    }

    @Test
    fun itemListClampsAtBothEdges() {
        val rows = listOf(row("a", 0L, 0L), row("b", 1L, 1L), row("c", 2L, 2L))
        val authority = registeredHomeAuthority(rows)

        assertTrue(dispatch(authority, rows, ControllerAction.NAV_LEFT))
        assertEquals(rows.first().focusKey, authority.state.value.focused)
        authority.requestFocus(rows.last().focusKey)
        assertTrue(dispatch(authority, rows, ControllerAction.NAV_RIGHT))
        assertEquals(rows.last().focusKey, authority.state.value.focused)
    }

    @Test
    fun downAndBackRestoreExactContentFocusThroughScopeMemory() {
        val rows = listOf(row("a", 0L, 0L), row("b", 1L, 1L))
        val authority = registeredHomeAuthority(rows)
        registerRail(authority)
        authority.requestFocus(rows.last().focusKey)

        assertTrue(dispatch(authority, rows, ControllerAction.NAV_DOWN))
        assertEquals(HomeShelfQuickRailScope, authority.state.value.scope)
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.HOME), authority.state.value.focused)

        assertTrue(dispatch(authority, rows, ControllerAction.CANCEL))
        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals(rows.last().focusKey, authority.state.value.focused)
    }

    @Test
    fun railWrapsAndCurrentHomeClosesWithoutNavigationCallback() {
        val rows = listOf(row("a", 0L, 0L))
        val authority = registeredHomeAuthority(rows)
        registerRail(authority)
        val activated = mutableListOf<MimirQuickRailAction>()

        dispatch(authority, rows, ControllerAction.NAV_DOWN, activated)
        dispatch(authority, rows, ControllerAction.NAV_LEFT, activated)
        // Wrap from HOME lands on the LAST action. That was QUICK_LAUNCH since FUX-D and is FILES since
        // the 2026-08-15 end-cap — and the change of expectation here is the useful part: it is the
        // proof that the end-cap joined the CONTROLLER travel ring, not just the pixels. A dock icon
        // reachable only by touch would be a defect on a handheld.
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.FILES), authority.state.value.focused)
        dispatch(authority, rows, ControllerAction.NAV_RIGHT, activated)
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.HOME), authority.state.value.focused)

        dispatch(authority, rows, ControllerAction.CONFIRM, activated)
        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertTrue(activated.isEmpty())
    }

    @Test
    fun confirmLaunchesExactTypedRowAndEmptyActionOpensLibrary() {
        val emulated = row("same", 0L, 0L)
        val android = HomeShelfRow(
            itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("same")),
            rank = 1L,
            addedAtMillis = 1L,
            title = "Android same",
        )
        val rows = listOf(emulated, android)
        val authority = registeredHomeAuthority(rows)
        var launched: HomeShelfRow? = null
        var openedLibrary = false

        authority.requestFocus(android.focusKey)
        dispatchToHomeShelf(
            authority = authority,
            rows = rows,
            action = ControllerAction.CONFIRM,
            onLaunch = { launched = it },
            onOpenLibrary = { openedLibrary = true },
            onQuickAction = {},
            onExit = {},
        )
        assertEquals(android, launched)
        assertFalse(openedLibrary)

        val emptyAuthority = DefaultFocusAuthority().apply {
            pushScope(HomeShelfScope)
            register(HomeShelfOpenLibraryKey, HomeShelfScope, Bounds)
        }
        dispatchToHomeShelf(
            authority = emptyAuthority,
            rows = emptyList(),
            action = ControllerAction.CONFIRM,
            onLaunch = { launched = it },
            onOpenLibrary = { openedLibrary = true },
            onQuickAction = {},
            onExit = {},
        )
        assertTrue(openedLibrary)
    }

    @Test
    fun `Tome confirm opens Tome and can never reach game launch dispatch`() {
        val game = row("game", 0L, 0L)
        val entries = listOf(HomeShelfEntry.Tome(testTomeId("RPGs")), HomeShelfEntry.Game(game.itemId))
        val authority = DefaultFocusAuthority().apply {
            pushScope(HomeShelfScope)
            entries.forEachIndexed { index, entry ->
                registerVirtual(
                    entry.focusKey,
                    HomeShelfScope,
                    FocusBounds(index * 1_000f, 0f, index * 1_000f + 500f, 500f),
                )
            }
            requestFocus(entries.first().focusKey)
        }
        var launched = 0
        var opened: TomeId? = null

        dispatchToHomeShelf(
            authority = authority,
            rows = listOf(game),
            entries = entries,
            action = ControllerAction.CONFIRM,
            onLaunch = { launched++ },
            onOpenTome = { opened = it },
            onOpenLibrary = {},
            onQuickAction = {},
            onExit = {},
        )

        assertEquals(testTomeId("RPGs"), opened)
        assertEquals(0, launched)
        assertTrue(homeShelfTomeFocusKey(testTomeId("RPGs")) != game.focusKey)
    }

    @Test
    fun `typed hint and tertiary dispatch never advertise Tome edit`() {
        assertEquals(
            listOf(HomeTileHintAction("Y", R.string.library_home_hint_edit)),
            homeTileFocusHintActions(HomeTileHintKind.GAME),
        )
        assertEquals(
            listOf(HomeTileHintAction("A", R.string.library_home_hint_open)),
            homeTileFocusHintActions(HomeTileHintKind.TOME),
        )
        // compact truthful directional copy — never destination copy.
        assertEquals(
            listOf(
                HomeTileHintAction("Y", R.string.library_home_hint_edit),
                HomeTileHintAction("L/R", R.string.library_home_hint_mode),
            ),
            homeTileFocusHintActions(HomeTileHintKind.GAME, viewModeSelectAvailable = true),
        )
        assertEquals(
            listOf(
                HomeTileHintAction("A", R.string.library_home_hint_open),
                HomeTileHintAction("L/R", R.string.library_home_hint_mode),
            ),
            homeTileFocusHintActions(HomeTileHintKind.TOME, viewModeSelectAvailable = true),
        )
        assertTrue(
            homeTileFocusHintActions(HomeTileHintKind.GAME, viewModeSelectAvailable = true).size <= 2,
        )

        val game = row("game", 0L, 0L)
        val tome = HomeShelfEntry.Tome(testTomeId("RPGs"))
        val entries = listOf(tome, HomeShelfEntry.Game(game.itemId))
        val authority = DefaultFocusAuthority().apply {
            pushScope(HomeShelfScope)
            entries.forEachIndexed { index, entry ->
                registerVirtual(
                    entry.focusKey,
                    HomeShelfScope,
                    FocusBounds(index * 1_000f, 0f, index * 1_000f + 500f, 500f),
                )
            }
            requestFocus(tome.focusKey)
        }
        val edited = mutableListOf<HomeShelfRow>()

        assertTrue(
            dispatchToHomeShelf(
                authority = authority,
                rows = listOf(game),
                entries = entries,
                action = ControllerAction.TERTIARY,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
                onTileMenu = edited::add,
            ),
        )
        assertTrue(edited.isEmpty())

        authority.requestFocus(game.focusKey)
        assertTrue(
            dispatchToHomeShelf(
                authority = authority,
                rows = listOf(game),
                entries = entries,
                action = ControllerAction.TERTIARY,
                onLaunch = {},
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
                onTileMenu = edited::add,
            ),
        )
        assertEquals(listOf(game), edited)
    }

    @Test
    fun `Tome return transaction restores exact key and settles only current generation`() {
        val target = homeShelfTomeFocusKey(testTomeId("RPGs"))
        val started = beginHomeShelfTomeReturn(HomeShelfTomeReturnState(), target)
        val generation = started.pending!!.generation

        assertEquals(HomeShelfTomeReturnGate.READY, gateHomeShelfTomeReturn(started, generation, listOf(target)))
        val settled = finishHomeShelfTomeReturn(
            started,
            generation,
            HomeShelfTomeReturnOutcome.SETTLED,
        )
        assertNull(settled.pending)
    }

    @Test
    fun `newer D-pad or shoulder interaction cancels Tome return and stale generation cannot settle`() {
        val target = homeShelfTomeFocusKey(testTomeId("RPGs"))
        val back = beginHomeShelfTomeReturn(HomeShelfTomeReturnState(), target)
        val staleGeneration = back.pending!!.generation
        val afterDpad = cancelHomeShelfTomeReturn(back)

        assertEquals(
            HomeShelfTomeReturnGate.STALE,
            gateHomeShelfTomeReturn(afterDpad, staleGeneration, listOf(target)),
        )
        assertEquals(
            afterDpad,
            finishHomeShelfTomeReturn(
                afterDpad,
                staleGeneration,
                HomeShelfTomeReturnOutcome.SETTLED,
            ),
        )

        val laterBack = beginHomeShelfTomeReturn(afterDpad, target)
        val afterShoulder = cancelHomeShelfTomeReturn(laterBack)
        assertNull(afterShoulder.pending)
        assertTrue(afterShoulder.generation > staleGeneration)
    }

    @Test
    fun `idle Home cancellation is the identical state and generation wrap stays usable`() {
        val idle = HomeShelfTomeReturnState(generation = 41L)
        assertTrue(idle === cancelHomeShelfTomeReturn(idle))

        val wrapped = beginHomeShelfTomeReturn(
            HomeShelfTomeReturnState(generation = Long.MAX_VALUE),
            homeShelfTomeFocusKey(testTomeId("Wrap")),
        )
        assertEquals(Long.MIN_VALUE, wrapped.generation)
        assertEquals(Long.MIN_VALUE, wrapped.pending!!.generation)
    }

    @Test
    fun `Back touch cancellation cannot activate the still-painted Tome member lane`() {
        val back = beginHomeShelfTomeReturn(
            HomeShelfTomeReturnState(),
            homeShelfTomeFocusKey(testTomeId("RPGs")),
        )
        val touchCanceled = cancelHomeShelfTomeReturn(back)

        assertFalse(
            canActivateHomeShelfContent(
                state = touchCanceled,
                visibleContextKey = "collection/RPGs",
                logicalContextKey = "last-played",
            ),
        )
        assertTrue(
            canActivateHomeShelfContent(
                state = touchCanceled,
                visibleContextKey = "last-played",
                logicalContextKey = "last-played",
            ),
        )
    }

    @Test
    fun `settled non-first Tome replays NAV_RIGHT from the exact Tome`() {
        val tome = HomeShelfEntry.Tome(testTomeId("RPGs"))
        val leadingGame = row("item-zero", 0L, 0L)
        val firstGame = row("top-level-game", 0L, 0L)
        var transaction = beginHomeShelfTomeReturn(HomeShelfTomeReturnState(), tome.focusKey)
        transaction = deferHomeShelfTomeReturnAction(transaction, ControllerAction.NAV_RIGHT)
        transaction = deferHomeShelfTomeReturnAction(transaction, ControllerAction.CONFIRM)
        assertEquals(
            listOf(ControllerAction.NAV_RIGHT, ControllerAction.CONFIRM),
            transaction.pending!!.deferredActions,
        )
        val authority = DefaultFocusAuthority().apply {
            pushScope(HomeShelfScope)
            registerVirtual(leadingGame.focusKey, HomeShelfScope, Bounds)
            registerVirtual(tome.focusKey, HomeShelfScope, FocusBounds(1_000f, 0f, 1_100f, 100f))
            registerVirtual(firstGame.focusKey, HomeShelfScope, FocusBounds(2_000f, 0f, 2_100f, 100f))
            requestFocus(tome.focusKey)
        }
        var oldMemberLaunches = 0
        var launchedTitle: String? = null
        var openedTome: TomeId? = null
        val currentRows = listOf(leadingGame, firstGame)
        val currentEntries = listOf(
            HomeShelfEntry.Game(leadingGame.itemId),
            tome,
            HomeShelfEntry.Game(firstGame.itemId),
        )
        transaction.pending!!.deferredActions.forEach { action ->
            dispatchToHomeShelf(
                authority = authority,
                rows = currentRows,
                entries = currentEntries,
                action = action,
                onLaunch = {
                    launchedTitle = it.title
                    if (it.title == "old-member") oldMemberLaunches++
                },
                onOpenTome = { openedTome = it },
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            )
        }

        assertEquals(firstGame.focusKey, authority.state.value.focused)
        assertTrue(authority.state.value.focused != leadingGame.focusKey)
        assertEquals(0, oldMemberLaunches)
        assertEquals("top-level-game", launchedTitle)
        assertNull(openedTome)
    }

    @Test
    fun `missing unpinned or deleted Tome fails closed without resurrecting same key`() {
        val target = homeShelfTomeFocusKey(testTomeId("RPGs"))
        val started = beginHomeShelfTomeReturn(HomeShelfTomeReturnState(), target)
        val generation = started.pending!!.generation
        assertEquals(HomeShelfTomeReturnGate.MISSING, gateHomeShelfTomeReturn(started, generation, emptyList()))
        val failed = finishHomeShelfTomeReturn(started, generation, HomeShelfTomeReturnOutcome.FAILED)
        assertNull(failed.pending)

        val unpinnedOrDeleted = cancelHomeShelfTomeReturn(failed)
        val sameKeyLater = beginHomeShelfTomeReturn(unpinnedOrDeleted, target)
        assertTrue(sameKeyLater.pending!!.generation > generation)
        assertEquals(
            HomeShelfTomeReturnGate.STALE,
            gateHomeShelfTomeReturn(sameKeyLater, generation, listOf(target)),
        )
    }

    @Test
    fun `empty Tome opens collection but remains nonlaunchable`() {
        val entry = HomeShelfEntry.Tome(testTomeId("Empty"))
        val authority = DefaultFocusAuthority().apply {
            pushScope(HomeShelfScope)
            registerVirtual(entry.focusKey, HomeShelfScope, Bounds)
            requestFocus(entry.focusKey)
        }
        var launches = 0
        var opened: TomeId? = null

        dispatchToHomeShelf(
            authority = authority,
            rows = emptyList(),
            entries = listOf(entry),
            action = ControllerAction.CONFIRM,
            onLaunch = { launches++ },
            onOpenTome = { opened = it },
            onOpenLibrary = {},
            onQuickAction = {},
            onExit = {},
        )

        assertEquals(testTomeId("Empty"), opened)
        assertEquals(0, launches)
    }

    @Test
    fun selectOpensAchievementsForTheExactFocusedHomeRow() {
        val rows = listOf(row("a", 0L, 0L), row("b", 1L, 1L))
        val authority = registeredHomeAuthority(rows)
        authority.requestFocus(rows[1].focusKey)
        var opened: HomeShelfRow? = null

        val consumed = dispatchToHomeShelf(
            authority = authority,
            rows = rows,
            action = ControllerAction.SYSTEM_SELECT,
            onLaunch = {},
            onOpenAchievements = { opened = it },
            onOpenLibrary = {},
            onQuickAction = {},
            onExit = {},
        )

        assertTrue(consumed)
        assertEquals(rows[1], opened)
    }

    @Test
    fun selectWithNoFocusedHomeRowIsConsumedWithoutOpeningAnything() {
        val authority = DefaultFocusAuthority().apply { pushScope(HomeShelfScope) }
        var opens = 0

        assertTrue(
            dispatchToHomeShelf(
                authority = authority,
                rows = emptyList(),
                action = ControllerAction.SYSTEM_SELECT,
                onLaunch = {},
                onOpenAchievements = { opens++ },
                onOpenLibrary = {},
                onQuickAction = {},
                onExit = {},
            ),
        )
        assertEquals(0, opens)
    }

    @Test
    fun unrelatedScopeIsNotConsumed() {
        val authority = DefaultFocusAuthority()
        assertFalse(dispatch(authority, emptyList(), ControllerAction.NAV_RIGHT))
        assertNull(authority.state.value.focused)
    }

    // ── FUX-01 §3.7 : fixed-layout shelves clamp focus at the ends ──────────

    @Test
    fun fixedLayoutShelfClampsFocusAndEdgePressesNoOp() {
        val rows = listOf(row("a", rank = 1_024L, addedAt = 1L), row("b", rank = 2_048L, addedAt = 2L))
        val authority = registeredHomeAuthority(rows)
        authority.requestFocus(rows[0].focusKey)
        // Edge press left on the first item: no wrap, focus stays put.
        assertTrue(dispatch(authority, rows, ControllerAction.NAV_LEFT))
        assertEquals(rows[0].focusKey, authority.state.value.focused)

        // Right advances a→b; right again at the end is a no-op — NAV_LEFT and NAV_RIGHT can never
        // collapse into the same motion at n=2.
        assertTrue(dispatch(authority, rows, ControllerAction.NAV_RIGHT))
        assertEquals(rows[1].focusKey, authority.state.value.focused)
        assertTrue(dispatch(authority, rows, ControllerAction.NAV_RIGHT))
        assertEquals(rows[1].focusKey, authority.state.value.focused)

        // And back: left returns to a — direction always matches layout.
        assertTrue(dispatch(authority, rows, ControllerAction.NAV_LEFT))
        assertEquals(rows[0].focusKey, authority.state.value.focused)
    }

    @Test
    fun longShelfClampsWhenItemsExceedTheWindow() {
        val rows = (0 until 6).map { row("game-$it", rank = it * 1_024L, addedAt = it.toLong()) }
        val authority = registeredHomeAuthority(rows)
        authority.requestFocus(rows[0].focusKey)

        assertTrue(dispatch(authority, rows, ControllerAction.NAV_LEFT))
        assertEquals(rows[0].focusKey, authority.state.value.focused)
        authority.requestFocus(rows.last().focusKey)
        assertTrue(dispatch(authority, rows, ControllerAction.NAV_RIGHT))
        assertEquals(rows.last().focusKey, authority.state.value.focused)
    }

    @Test
    fun `destination logical focus accepts HAT navigation and confirm before rings register`() {
        val outgoing = row("outgoing", rank = 0L, addedAt = 0L)
        val first = row("first", rank = 1L, addedAt = 1L)
        val second = row("second", rank = 2L, addedAt = 2L)
        val entries = listOf(
            HomeShelfEntry.Game(first.itemId),
            HomeShelfEntry.Game(second.itemId),
        )
        val authority = registeredHomeAuthority(listOf(outgoing)).apply {
            requestFocus(outgoing.focusKey)
        }
        var logicalFocus = first.focusKey
        var launched: HomeShelfRow? = null

        dispatchToHomeShelf(
            authority = authority,
            rows = listOf(first, second),
            entries = entries,
            action = ControllerAction.NAV_RIGHT,
            logicalFocusKey = logicalFocus,
            onLogicalFocusChanged = { logicalFocus = it },
            onLaunch = {},
            onOpenLibrary = {},
            onQuickAction = {},
            onExit = {},
        )
        dispatchToHomeShelf(
            authority = authority,
            rows = listOf(first, second),
            entries = entries,
            action = ControllerAction.CONFIRM,
            logicalFocusKey = logicalFocus,
            onLogicalFocusChanged = { logicalFocus = it },
            onLaunch = { launched = it },
            onOpenLibrary = {},
            onQuickAction = {},
            onExit = {},
        )

        assertEquals(second.focusKey, logicalFocus)
        assertEquals(second, launched)
        assertEquals(outgoing.focusKey, authority.state.value.focused)
    }

    private fun dispatch(
        authority: DefaultFocusAuthority,
        rows: List<HomeShelfRow>,
        action: ControllerAction,
        activated: MutableList<MimirQuickRailAction> = mutableListOf(),
    ): Boolean = dispatchToHomeShelf(
        authority = authority,
        rows = rows,
        action = action,
        onLaunch = {},
        onOpenLibrary = {},
        onQuickAction = activated::add,
        onExit = {},
    )

    private fun registeredHomeAuthority(rows: List<HomeShelfRow>) = DefaultFocusAuthority().apply {
        pushScope(HomeShelfScope)
        rows.forEachIndexed { index, row ->
            registerVirtual(
                key = row.focusKey,
                scope = HomeShelfScope,
                bounds = FocusBounds(index * 1_000f, 0f, index * 1_000f + 500f, 500f),
            )
        }
    }

    private fun registerRail(authority: DefaultFocusAuthority) {
        HomeShelfQuickActions.forEachIndexed { index, action ->
            authority.register(
                key = homeShelfQuickRailKey(action),
                scope = HomeShelfQuickRailScope,
                bounds = FocusBounds(index * 100f, 1_000f, index * 100f + 50f, 1_050f),
            )
        }
    }

    private fun row(id: String, rank: Long, addedAt: Long) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId(id)),
        rank = rank,
        addedAtMillis = addedAt,
        title = id,
    )

    private companion object {
        val Bounds = FocusBounds(0f, 0f, 100f, 100f)
    }
}

private fun testTomeId(value: String): TomeId.Custom = TomeId.Custom(
    UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)),
)

class MimirDirectionalPaceTest {

    @Test
    fun `bursts are dropped and paced presses pass`() {
        var now = 0L
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.clock = { now }
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.reset()
        val interval = com.gruning.mimir.rewrite.core.input.MimirDirectionalPaceMs
        val step = interval / 3

        org.junit.Assert.assertTrue(com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.tryAccept())
        now += step
        org.junit.Assert.assertFalse(com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.tryAccept())
        now += step
        org.junit.Assert.assertFalse(com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.tryAccept())
        now += interval
        org.junit.Assert.assertTrue(com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.tryAccept())
    }

    @Test
    fun `rejected presses do not stretch the window`() {
        var now = 0L
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.clock = { now }
        com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.reset()
        val interval = com.gruning.mimir.rewrite.core.input.MimirDirectionalPaceMs
        val step = interval / 3
        org.junit.Assert.assertTrue(com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.tryAccept())
        // Two sub-interval rejects, then land exactly ON the interval — a stretching window would
        // still reject here because the rejects would have pushed the next accept out.
        repeat(2) {
            now += step
            org.junit.Assert.assertFalse(com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.tryAccept())
        }
        now += interval - step * 2
        org.junit.Assert.assertTrue(com.gruning.mimir.rewrite.core.input.MimirDirectionalPace.tryAccept())
    }
}

class LinearHomeShelfWindowTest {

    @Test
    fun `buffered viewport never mounts an opposite-edge identity`() {
        val first = linearHomeShelfBufferedWindow(('a'..'j').toList(), centerIndex = 2, slotRadius = 2)
        val last = linearHomeShelfBufferedWindow(('a'..'j').toList(), centerIndex = 7, slotRadius = 2)
        org.junit.Assert.assertEquals(listOf('a', 'b', 'c', 'd', 'e', 'f'), first.map { it.item })
        org.junit.Assert.assertEquals(listOf('e', 'f', 'g', 'h', 'i', 'j'), last.map { it.item })
        org.junit.Assert.assertEquals(listOf(-2, -1, 0, 1, 2, 3), first.map { it.slotOffset })
        org.junit.Assert.assertEquals(listOf(-3, -2, -1, 0, 1, 2), last.map { it.slotOffset })
    }

    @Test
    fun `viewport holds still while focus walks inside its safe seats`() {
        val count = 10
        val safeRadius = 1
        var center = 0
        center = linearHomeShelfCenterIndex(center, 1, count, safeRadius)
        org.junit.Assert.assertEquals(1, center)
        center = linearHomeShelfCenterIndex(center, 2, count, safeRadius)
        org.junit.Assert.assertEquals(1, center)
        center = linearHomeShelfCenterIndex(center, 3, count, safeRadius)
        org.junit.Assert.assertEquals(2, center)
        center = linearHomeShelfCenterIndex(center, 2, count, safeRadius)
        org.junit.Assert.assertEquals(2, center)
        center = linearHomeShelfCenterIndex(center, 1, count, safeRadius)
        org.junit.Assert.assertEquals(2, center)
        center = linearHomeShelfCenterIndex(center, 0, count, safeRadius)
        org.junit.Assert.assertEquals(1, center)
    }

    @Test
    fun `terminal focus remains in a full safe seat`() {
        val count = 10
        val safeRadius = 1
        val leftCenter = linearHomeShelfCenterIndex(9, 0, count, safeRadius)
        val rightCenter = linearHomeShelfCenterIndex(0, 9, count, safeRadius)
        org.junit.Assert.assertEquals(1, leftCenter)
        org.junit.Assert.assertEquals(8, rightCenter)
        org.junit.Assert.assertEquals(-1, 0 - leftCenter)
        org.junit.Assert.assertEquals(1, 9 - rightCenter)
    }

    @Test
    fun `terminal pages fill every visible seat in content order`() {
        val items = ('a'..'h').toList()
        val radius = 1
        val firstCenter = linearHomeShelfCenterIndex(0, 0, items.size, radius)
        val lastCenter = linearHomeShelfCenterIndex(items.lastIndex, items.lastIndex, items.size, radius)

        val firstPainted = linearHomeShelfBufferedWindow(items, firstCenter, radius)
            .filter { kotlin.math.abs(it.slotOffset) <= radius }
        val lastPainted = linearHomeShelfBufferedWindow(items, lastCenter, radius)
            .filter { kotlin.math.abs(it.slotOffset) <= radius }

        org.junit.Assert.assertEquals(listOf('a', 'b', 'c'), firstPainted.map { it.item })
        org.junit.Assert.assertEquals(listOf(-1, 0, 1), firstPainted.map { it.slotOffset })
        org.junit.Assert.assertEquals(listOf('f', 'g', 'h'), lastPainted.map { it.item })
        org.junit.Assert.assertEquals(listOf(-1, 0, 1), lastPainted.map { it.slotOffset })
    }

    // ── The platform SHELFMARK's mount rule  ──────────────────────────────
    // HomeShelfInteractiveTile derives the plate from `row.supportingLabel`. These pin the DERIVATION,
    // which is the part that can silently break; the Compose mount itself has no UI-test harness here.

    @Test
    fun emulatedHomeRowsCarryThePlatformIdTheShelfmarkNeeds() {
        // HomeShelfCoordinator projects platformId.value into supportingLabel for emulated entries.
        val row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("g1")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Sonic",
            supportingLabel = "genesis",
        )
        assertEquals("MD", mimirPlatformShelfmarkCode(row.supportingLabel.orEmpty()))
    }

    @Test
    fun androidGameTilesGetNoShelfmarkAtAll() {
        // An Android game has no console, so there is nothing honest to strike on the plate. The tile must
        // render NOTHING rather than a placeholder — the same discipline MediaArtFrame uses.
        val row = HomeShelfRow(
            itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Some Android Game",
        )
        assertNull(row.supportingLabel)
        assertNull(mimirPlatformShelfmarkCode(row.supportingLabel.orEmpty()))
    }

}
