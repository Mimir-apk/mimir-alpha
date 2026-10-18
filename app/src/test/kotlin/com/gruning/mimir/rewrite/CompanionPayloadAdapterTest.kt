package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.CompanionHomeShelfDetail
import com.gruning.mimir.rewrite.core.model.CompanionShelfRow
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import com.gruning.mimir.rewrite.feature.library.formatSizeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven tests for the primary→companion payload adapter (B11 / WP-4 F10) — the mode selection,
 * row mapping, and selection resolution that previously lived untested inside MimirApp.kt.
 */
class CompanionPayloadAdapterTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    private fun game(
        id: String,
        title: String = id.uppercase(),
        platform: String = "dreamcast",
        favorite: Boolean = false,
        override: Boolean = false,
        genre: String? = null,
        members: Int = 1,
        art: String? = null,
        flatArt: String? = null,
    ) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId(platform),
        memberCount = members,
        duplicateKind = null,
        isFavorite = favorite,
        hasEmulatorOverride = override,
        genre = genre,
        artUri = art,
        flatArtUri = flatArt,
    )

    private fun nativeRow(pkg: String, label: String = pkg, game: Boolean = true, favorite: Boolean = false) =
        NativeShelfRow(packageName = pkg, label = label, launchActivity = null, isGame = game, isFavorite = favorite)

    private val platforms = listOf(
        PlatformRow(PlatformId("dreamcast"), "Dreamcast", 1),
        PlatformRow(PlatformId("gba"), "Game Boy Advance", 2),
    )

    private fun shelfRowSet(
        mode: LibraryShelfMode = LibraryShelfMode.EMULATION,
        rows: List<PlatformRow> = platforms,
        snapshot: NativeShelfSnapshot = NativeShelfSnapshot(),
    ) = companionRowSet(
        openPlatform = null,
        visibleGames = emptyList(),
        platforms = rows,
        nativeSnapshot = snapshot,
        shelfMode = mode,
        favoriteSegment = FavoriteSegment.GAMES,
    )

    private fun openPlatformRowSet(games: List<GameRow>, platform: String = "dreamcast") = companionRowSet(
        openPlatform = PlatformId(platform),
        visibleGames = games,
        platforms = platforms,
        nativeSnapshot = NativeShelfSnapshot(),
        shelfMode = LibraryShelfMode.EMULATION,
        favoriteSegment = FavoriteSegment.GAMES,
    )

    private fun payload(
        rowSet: CompanionRowSet,
        detailGame: GameRow? = null,
        focusedKey: FocusKey? = null,
        launchState: GameLaunchUiState = GameLaunchUiState.Idle,
    ) = buildCompanionPayload(
        rowSet = rowSet,
        detailGame = detailGame,
        focusedKey = focusedKey,
        launchState = launchState,
        themeId = "midnight",
        themeSigilRef = "sigils/midnight.png",
    )

    // ── FUX-E: HOME two-zone detail (HOME_SHELF_DETAIL) ────────────────────────────────────────────

    private fun homeRow(
        id: String,
        title: String = id.uppercase(),
        flatArt: String? = null,
        art: String? = null,
    ) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId(id)),
        rank = 0L,
        addedAtMillis = 0L,
        title = title,
        artworkUri = art,
        flatArtUri = flatArt,
    )

    private fun homeFocus(
        row: HomeShelfRow,
        detail: CompanionHomeShelfDetail = CompanionHomeShelfDetail(),
        gameId: GameId? = (row.itemId as? HomeShelfItemId.Emulated)?.gameId,
        status: String? = MimirCopy.StatusReady,
        launcher: String? = null,
    ) = CompanionHomeShelfFocus(
        row = row,
        detail = detail,
        gameId = gameId,
        platformId = PlatformId("dreamcast"),
        statusLabel = status,
        statusReady = status == MimirCopy.StatusReady,
        launcherLabel = launcher,
    )

    private fun homePayload(
        destination: MimirDestination = MimirDestination.HOME,
        rowSet: CompanionRowSet = shelfRowSet(),
    ) = buildCompanionPayload(
        rowSet = rowSet,
        detailGame = null,
        focusedKey = null,
        launchState = GameLaunchUiState.Idle,
        themeId = "midnight",
        themeSigilRef = "sigils/midnight.png",
        destination = destination,
    )

    @Test
    fun `Companion platform fallback is friendly and never an uppercased resolver id`() {
        assertEquals(
            "Some System",
            platformLabelForCompanion(PlatformId("some_system"), emptyList()),
        )
        assertEquals("Super Nintendo", platformLabelForCompanion(PlatformId("snes"), emptyList()))
    }

    @Test
    fun `HOME with no focused row falls through to today's HOME lanes`() {
        assertEquals(CompanionMode.HOME, homePayload().mode)
    }

    @Test
    fun `HOME empty shelf with no focus idles identically to the library path`() {
        val emptyShelf = companionRowSet(
            openPlatform = null,
            visibleGames = emptyList(),
            platforms = emptyList(),
            nativeSnapshot = NativeShelfSnapshot(),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
        )
        val home = homePayload(rowSet = emptyShelf)
        val library = payload(emptyShelf)
        assertEquals(CompanionMode.IDLE, home.mode)
        assertEquals(library.mode, home.mode)
    }

    @Test
    fun `homeLaunchSeedPayload builds a logo-first NOW_PLAYING loading card from the launching row`() {
        // The G-2 seed is now a pure, focus-independent function keyed on the launching row (review #2/#3).
        val seed = homeLaunchSeedPayload(
            row = homeRow("sonic", title = "Sonic", flatArt = "content://art/sonic-logo.png"),
            themeId = "midnight",
        )
        assertEquals(CompanionMode.NOW_PLAYING, seed.mode)
        assertTrue(seed.isLoading)
        assertEquals(GameId("sonic"), seed.gameId)
        assertEquals("Sonic", seed.title)
        assertEquals("content://art/sonic-logo.png", seed.heroArtRef)
        assertTrue(seed.heroArtIsLogo)
        assertNull(seed.homeShelfDetail)
    }

    @Test
    fun `homeLaunchSeedPayload with a remote flat art is not a logo`() {
        val seed = homeLaunchSeedPayload(
            row = homeRow("sonic", flatArt = "https://cdn/sonic.png", art = "content://art/sonic-cover.png"),
            themeId = "midnight",
        )
        assertFalse(seed.heroArtIsLogo)
        assertEquals("content://art/sonic-cover.png", seed.heroArtRef)
    }

    @Test
    fun `running app projection borrows retained Home lanes only when it has none`() {
        val retainedHome = homePayload()
        val runningApp = CompanionPayload(
            mode = CompanionMode.RUNNING_APP,
            runningApp = CompanionRunningApp("org.azahar_emu.azahar", "Azahar"),
        )

        assertEquals(
            retainedHome.homeLanes,
            companionPayloadWithRetainedHomeLanes(runningApp, retainedHome).homeLanes,
        )

        val alreadyFull = runningApp.copy(homeLanes = retainedHome.homeLanes)
        assertSame(
            alreadyFull,
            companionPayloadWithRetainedHomeLanes(alreadyFull, retainedHome),
        )
    }

    // ── Mode selection ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `shelf resting surface is the static 3DS-shelf HOME`() {
        val p = payload(shelfRowSet(), focusedKey = FocusKey("library/shelf/gba"))
        assertEquals(CompanionMode.HOME, p.mode)
        assertEquals("2 platforms · 3 games", p.statsLabel)
        // **This line used to assert "gba" — screen 1's focused shelf tile, mirrored into the payload
        // for screen 2's ring (I1, 2026-07-11). repealed it: "no mirror screen
        // focus following."** The assertion is INVERTED rather than deleted, because null here is the
        // ruling: a HOME payload must be identical across focus moves, or `CompanionState.publish`'s
        // equality dedup cannot hold and screen 2 redraws on every d-pad press for a field nobody
        // draws. The ring now reads screen 2's own persisted cursor (`CompanionOrderStore.cursor`).
        assertNull(
            "a focused key on screen 1 must not reach screen 2's payload",
            p.selectedShelfId,
        )
        // ONE interleaved order (policy 3) — consoles and favorited apps are equal citizens.
        assertEquals(listOf("shelf"), p.homeLanes.map { it.id })
        assertEquals(listOf("dreamcast", "gba"), p.homeLanes[0].tiles.map { it.id })
        assertEquals("2 games", p.homeLanes[0].tiles[1].countLabel)
        // Tiles wear the full platform name, as Main does (policy R1); the lattice gives it two lines.
        assertEquals("Game Boy Advance", p.homeLanes[0].tiles[1].label)
    }

    @Test
    fun `platform tiles carry the label Main shows, never an acronym`() {
        val rows = listOf(
            PlatformRow(PlatformId("gamecube"), "GameCube", 1),
            PlatformRow(PlatformId("dreamcast"), "Dreamcast", 1),
            PlatformRow(PlatformId("megadrive"), "Genesis / Mega Drive", 1),
            PlatformRow(PlatformId("nes"), "NES", 1),
        )
        val lanes = buildCompanionHomeLanes(rows, NativeShelfSnapshot())
        assertEquals(
            listOf("GameCube", "Dreamcast", "Genesis / Mega Drive", "NES"),
            lanes[0].tiles.map { it.label },
        )
        assertEquals(rows.map { it.label }, lanes[0].tiles.map { it.label })
    }

    @Test
    fun `android tiles are favorites-only, appended after the consoles by default`() {
        // Policy 1: only favorited Android games/apps are pinned to the shelf.
        val lanes = buildCompanionHomeLanes(
            platforms,
            NativeShelfSnapshot(
                games = listOf(nativeRow("com.fav", favorite = true), nativeRow("com.not", favorite = false)),
            ),
        )
        assertEquals(listOf("dreamcast", "gba", "com.fav"), lanes[0].tiles.map { it.id })
    }

    @Test
    fun `the user's filed order wins, and an arrival appends at the tail`() {
        // Policy 3 + S4: filed tiles take their cells; anything the order doesn't know appends —
        // never inserted mid-grid. Spatial memory survives the library breathing.
        val lanes = buildCompanionHomeLanes(
            platforms,
            NativeShelfSnapshot(games = listOf(nativeRow("com.fav", favorite = true))),
            homeOrder = listOf("com.fav", "gba"),
        )
        assertEquals(listOf("com.fav", "gba", "dreamcast"), lanes[0].tiles.map { it.id })
    }

    @Test
    fun `a bound tome takes its cell and swallows its children`() {
        // S6: dropping a tile onto a tile binds a tome. The children leave the top level; the folder tile
        // stands where the target stood, and the order names the folder id.
        val lanes = buildCompanionHomeLanes(
            platforms,
            NativeShelfSnapshot(games = listOf(nativeRow("com.fav", favorite = true))),
            homeOrder = listOf("folder:1", "com.fav"),
            homeFolders = listOf(CompanionFolder("folder:1", "Sega", listOf("dreamcast", "gba"))),
        )
        assertEquals(listOf("folder:1", "com.fav"), lanes[0].tiles.map { it.id })
        val tome = lanes[0].tiles.first()
        assertEquals(CompanionHomeTileKind.FOLDER, tome.kind)
        assertEquals("Sega", tome.label)
        assertEquals("2 items", tome.countLabel)
        assertEquals(listOf("dreamcast", "gba"), tome.children.map { it.id })
    }

    @Test
    fun `a tome whose children all vanished does not render`() {
        // The tome is retained in the store (like an order entry) but has nothing left to show.
        val lanes = buildCompanionHomeLanes(
            platforms = listOf(PlatformRow(PlatformId("gba"), "Game Boy Advance", 2)),
            nativeSnapshot = NativeShelfSnapshot(),
            homeFolders = listOf(CompanionFolder("folder:1", "Gone", listOf("com.uninstalled"))),
        )
        assertEquals(listOf("gba"), lanes[0].tiles.map { it.id })
    }

    @Test
    fun `an intentionally-empty tome renders, waiting to be filled (CR-17)`() {
        // A folder the user just created holds NO children — it is NOT a vanished folder, and it must show
        // so they can drop tiles into it. The discriminator is childCount==0 vs childrenResolveToNone.
        val lanes = buildCompanionHomeLanes(
            platforms = listOf(PlatformRow(PlatformId("gba"), "Game Boy Advance", 2)),
            nativeSnapshot = NativeShelfSnapshot(),
            homeFolders = listOf(CompanionFolder("folder:new", "Folder", emptyList())),
        )
        val tome = lanes[0].tiles.first { it.kind == CompanionHomeTileKind.FOLDER }
        assertEquals("folder:new", tome.id)
        assertEquals(0, tome.children.size)
        assertEquals(listOf("gba", "folder:new"), lanes[0].tiles.map { it.id })
    }

    @Test
    fun `an order entry for a platform that lost its games is simply skipped, not lost`() {
        // The retained entry lives in the store; the grid just renders what still has games.
        val lanes = buildCompanionHomeLanes(
            platforms = listOf(PlatformRow(PlatformId("gba"), "Game Boy Advance", 2)),
            nativeSnapshot = NativeShelfSnapshot(),
            homeOrder = listOf("dreamcast", "gba"),
        )
        assertEquals(listOf("gba"), lanes[0].tiles.map { it.id })
    }

    @Test
    fun `empty shelf idles but retains an explicit authoritative home snapshot`() {
        val empty = payload(shelfRowSet(rows = emptyList()))

        assertEquals(CompanionMode.IDLE, empty.mode)
        assertEquals(listOf("shelf"), empty.homeLanes.map { it.id })
        assertTrue(empty.homeLanes.all { it.tiles.isEmpty() })
        assertNull(empty.selectedShelfId)
    }

    @Test
    fun `open platform with no games rests at HOME -- the library still has a shelf`() {
        // An empty gamelist is not an empty library: with neither a game nor a job, show HOME.
        assertEquals(CompanionMode.HOME, payload(openPlatformRowSet(emptyList())).mode)
    }

    @Test
    fun `an open platform over an empty library still idles -- HOME needs a shelf to be`() {
        // The empty-lane guard lives in ONE shared return, so this cannot drift away from the shelf
        // route's answer. Asserting byte-equality rather than just the mode is what pins that.
        val emptyLibrary = companionRowSet(
            openPlatform = PlatformId("dreamcast"),
            visibleGames = emptyList(),
            platforms = emptyList(),
            nativeSnapshot = NativeShelfSnapshot(),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
        )
        val library = payload(emptyLibrary)

        assertEquals(CompanionMode.IDLE, library.mode)
        val emptyShelf = companionRowSet(
            openPlatform = null,
            visibleGames = emptyList(),
            platforms = emptyList(),
            nativeSnapshot = NativeShelfSnapshot(),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
        )
        assertEquals(
            "the Library idle and the shelf idle are the same value, not two that agree",
            payload(emptyShelf),
            library,
        )
    }

    @Test
    fun `native favorites view rests on HOME with favorites-only app tiles`() {
        val rowSet = companionRowSet(
            openPlatform = LibraryViewModel.FAVORITES_PLATFORM_ID,
            visibleGames = emptyList(),
            platforms = platforms,
            nativeSnapshot = NativeShelfSnapshot(
                apps = listOf(nativeRow("com.a", favorite = true), nativeRow("com.b", favorite = false)),
            ),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.ANDROID_APPS,
        )
        val p = payload(rowSet)
        // The favorites view also rests on the static HOME; only favorited apps are pinned.
        assertEquals(CompanionMode.HOME, p.mode)
        assertTrue(p.homeLanes[0].tiles.any { it.id == "com.a" })
        assertTrue(p.homeLanes[0].tiles.none { it.id == "com.b" })
        // The Bottom's header reads this line: the shelf's own count, not the segment's "1 app".
        assertEquals("2 platforms · 3 games", p.statsLabel)
        assertFalse(p.statusReady)
    }

    // ── Status vocabulary + the statusReady contract ─────────────────────────────────────────────

    // ── the mirrors are retired. These replace the suite that pinned their contents. ──

    @Test
    fun `a focused HOME shelf row no longer mirrors -- HOME is the resting surface`() {
        // Stronger than when this was written: the adapter no longer ACCEPTS a focused row at all.
        // The card and its per-focus Room read were retired,
        // and both are gone -- so "HOME does not vary with the focused row" is now structural rather
        // than a behaviour that has to be re-checked.
        val p = homePayload()

        assertEquals(CompanionMode.HOME, p.mode)
        assertNull("no mirrored row identity may survive the retirement", p.gameId)
    }

    @Test
    fun `browsing an open platform no longer mirrors the focused row -- it rests at HOME`() {
        // HOME needs the same route call as its sibling branches; no additional home-lane plumbing is needed.
        val p = payload(openPlatformRowSet(listOf(game("sonic"))), focusedKey = FocusKey("library/gamelist/sonic"))

        assertEquals(CompanionMode.HOME, p.mode)
        // The retirement half is untouched: resting at HOME must not smuggle the focused row back.
        assertNull(p.gameId)
        assertNull("a retired card carries no status -- readiness is no longer surfaced while browsing", p.statusLabel)
        assertNull(
            "a library/gamelist key is not a home tile -- the ring must NOT backfill with the open " +
                "platform, which would duplicate the primary display",
            p.selectedShelfId,
        )
        assertNotEquals(
            "the resting shelf must not wear the gamelist header screen 1 is already showing",
            "Dreamcast · 1 game",
            p.statsLabel,
        )
    }

    @Test
    fun `the Library rest and the Home rest are the same payload, not two that agree`() {
        // This is the dedup argument made enforceable. Sharing one resting return means walking the
        // library produces a value-EQUAL payload, which the StateFlow drops -- so screen 2 does not
        // repaint while screen 1 moves between platforms.
        val library = payload(openPlatformRowSet(listOf(game("sonic"))))
        val otherPlatform = payload(openPlatformRowSet(listOf(game("chrono")), platform = "snes"))
        val home = homePayload()

        assertEquals(home, library)
        assertEquals("hopping platforms must not repaint screen 2", library, otherPlatform)
    }

    @Test
    fun `launching flips the card to NOW_PLAYING with the dispatch-only claim`() {
        // Policy R11 / companion review F1: a launch in flight (or the game away running — the state
        // resets on return) shows the hero card, not the browsing detail.
        val p = payload(openPlatformRowSet(listOf(game("sonic", art = "art/sonic.png"))), launchState = GameLaunchUiState.Launching)
        assertEquals(CompanionMode.NOW_PLAYING, p.mode)
        assertEquals("art/sonic.png", p.heroArtRef)
        assertEquals(MimirCopy.StatusLaunching, p.statusLabel)
        assertFalse(p.statusReady)
        assertTrue(p.isLoading)
    }

    @Test
    fun `a game with no logo falls back to the box cover on the surviving companion surface`() {
        // Was "on both companion surfaces". One of the two surfaces was retired, so this now pins the fallback
        // where it still renders -- NOW_PLAYING. The behaviour is unchanged; only the surface count is.
        val game = game(id = "sonic", art = "file:///art/sonic-cover.png")
        val rowSet = openPlatformRowSet(listOf(game))

        val nowPlaying = payload(rowSet, launchState = GameLaunchUiState.IntentSent)
        assertEquals(CompanionMode.NOW_PLAYING, nowPlaying.mode)
        assertEquals("file:///art/sonic-cover.png", nowPlaying.heroArtRef)
        assertFalse(nowPlaying.heroArtIsLogo)
    }

    @Test
    fun `open primary launch card pins the passive companion to the selected non-first game`() {
        val first = game("first")
        val selected = game("selected")
        val rowSet = openPlatformRowSet(listOf(first, selected))

        val pinned = companionSelectedOrOpenGame(
            rowSet = rowSet,
            openGameDetail = selected,
            detailGame = null,
            focusedKey = FocusKey("library/detail/play"),
        )

        assertEquals(selected.gameId, pinned?.gameId)
    }

    @Test
    fun `remote clear logo is never exposed to the offline companion renderer`() {
        val game = game(
            id = "sonic",
            art = "file:///art/sonic-cover.png",
            flatArt = "https://example.com/sonic-logo.png",
        )
        val nowPlaying = payload(
            openPlatformRowSet(listOf(game)),
            launchState = GameLaunchUiState.Launching,
        )

        assertEquals("file:///art/sonic-cover.png", nowPlaying.heroArtRef)
        assertFalse(nowPlaying.heroArtIsLogo)
    }

    @Test
    fun `intent-sent stays NOW_PLAYING until the state resets on return`() {
        val running = payload(openPlatformRowSet(listOf(game("sonic"))), launchState = GameLaunchUiState.IntentSent)
        assertEquals(CompanionMode.NOW_PLAYING, running.mode)
        val returned = payload(openPlatformRowSet(listOf(game("sonic"))), launchState = GameLaunchUiState.Idle)
        // What this pins is that IntentSent does not survive the return -- not which mode it falls to.
        // The resting mode changed from IDLE to HOME; asserting the negative
        // keeps the pin about the launch state rather than about the resting surface.
        assertNotEquals(CompanionMode.NOW_PLAYING, returned.mode)
    }

    @Test
    fun `running Android app wins over home and stale game launch state`() {
        val app = CompanionRunningApp("com.android.deskclock", "Clock", "/icons/clock.png")
        val p = buildCompanionPayload(
            rowSet = shelfRowSet(),
            detailGame = game("sonic"),
            focusedKey = FocusKey("library/shelf/gba"),
            launchState = GameLaunchUiState.Launching,
            themeId = "midnight",
            themeSigilRef = "sigils/midnight.png",
            runningApp = app,
        )

        assertEquals(CompanionMode.RUNNING_APP, p.mode)
        assertEquals(app, p.runningApp)
        assertEquals("midnight", p.themeId)
        assertFalse(p.isLoading)
        assertNull(p.gameId)
    }

    // ── Launcher label (policy R13: real labels only) ──────────────────────────────────────

    @Test
    fun `launcher-line cleaning keeps real targets and drops the sentinels`() {
        assertEquals("RetroArch 64 / mGBA", companionLauncherLabel("Launch · RetroArch 64 / mGBA"))
        assertNull(companionLauncherLabel("Launch · Needs emulator"))
        assertNull(companionLauncherLabel("Launch · Unsupported"))
        assertNull(companionLauncherLabel(null))
        assertNull(companionLauncherLabel("Launch · "))
    }

    // ── Row mapping ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `game row carries genre, favorite, and override markers`() {
        val row = companionGameRow(game("sonic", genre = "Platformer", favorite = true, override = true, members = 2))
        assertEquals("sonic", row.id)
        assertEquals("Platformer · Favorite · Open with", row.countLabel)
        assertEquals(listOf("Platformer", "2 files", "Open with override", "Favorite"), row.detailLines)
    }

    @Test
    fun `plain game row falls back to Ready`() {
        assertEquals(MimirCopy.StatusReady, companionGameRow(game("sonic")).countLabel)
    }

    @Test
    fun `native row names its kind`() {
        val row = companionNativeRow(nativeRow("com.retroarch", label = "RetroArch", game = true, favorite = true))
        assertEquals("Android game · Favorite", row.countLabel)
        assertEquals("com.retroarch", row.detailLines.first())
    }

    @Test
    fun `codes compress multi-word labels to initials`() {
        assertEquals("GBA", companionCode("Game Boy Advance"))
        assertEquals("PLA", companionCode("PlayStation"))
        assertEquals("M", companionCode("···"))
    }

    // ── Selection resolution ─────────────────────────────────────────────────────────────────────

    @Test
    fun `selection decodes all three focus namespaces and falls back to the first row`() {
        // Explicit fixtures: the SHELF rowSet no longer carries mirror rows (HOME, Slice 1) — the
        // decoder itself still serves the gamelist-detail selection path.
        val rows = listOf(
            CompanionShelfRow(id = "dreamcast", code = "DRE", name = "Dreamcast", countLabel = "1 game"),
            CompanionShelfRow(id = "gba", code = "GBA", name = "Game Boy Advance", countLabel = "2 games"),
        )
        assertEquals("gba", companionSelectedShelfId(FocusKey("library/shelf/gba"), rows))
        assertEquals("dreamcast", companionSelectedShelfId(FocusKey("browse/whatever"), rows))
        assertEquals("dreamcast", companionSelectedShelfId(null, rows))
        assertNull(companionSelectedShelfId(FocusKey("library/shelf/gba"), emptyList()))
    }
}
