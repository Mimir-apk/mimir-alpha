package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disc / version menu —
 *
 * This file **replaces** the inline-disc-row pins written earlier the same day. The mechanism they
 * pinned was overruled, so they are gone rather than adapted; what survives is [libraryDetailDiscs],
 * because the menu is built from exactly the same deduped disc list.
 *
 * What these pins protect:
 *
 *  1. The dedupe. `ScanRecognizer.discNumberOf` reads the disc token off the FILENAME, so a `.cue` and
 *     all of its `.bin` tracks carry the SAME `discNumber`, and `GAME_MEMBERS_READ_SQL` filters none of
 *     them. Without [libraryDetailDiscs] the menu offers "Disc 2" three times.
 *  2. The trigger. A single-file game must never meet a modal on Play.
 *  3. The default row, first and always — without it Chrono Cross's `.m3u` whole-set handoff becomes
 *     unreachable and Play silently downgrades to booting one disc.
 *  4. The two seams: a DISC crosses as a number, a VERSION as an [EntryKey].
 */
class LaunchChoiceMenuTest {

    private fun member(disc: Int, name: String) = GameMemberRow(
        entryKey = EntryKey("ff8/$name"),
        discNumber = disc,
        displayName = name,
        sizeBytes = 0L,
    )

    // ── 1. Dedupe ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun oneRowPerPhysicalDiscEvenWhenEveryTrackCarriesTheDiscNumber() {
        val members = listOf(
            member(1, "Final Fantasy VIII (Disc 1) (Track 01).bin"),
            member(1, "Final Fantasy VIII (Disc 1) (Track 02).bin"),
            member(1, "Final Fantasy VIII (Disc 1).cue"),
            member(2, "Final Fantasy VIII (Disc 2) (Track 01).bin"),
            member(2, "Final Fantasy VIII (Disc 2).cue"),
        )

        val discs = libraryDetailDiscs(members)

        assertEquals(listOf(1, 2), discs.map { it.number })
    }

    /**
     * The surviving row names the LAUNCHABLE ROOT, not whichever track sorts first. `displayName ASC`
     * alone picks the `.bin`, because a space (0x20) sorts before a period (0x2E) — so
     * `"... (Disc 2) (Track 01).bin"` beats `"... (Disc 2).cue"`. Mirrors `multiDiscLaunchMembers`.
     */
    @Test
    fun discRowNamesTheManifestNotTheTrackThatSortsFirst() {
        val members = listOf(
            member(2, "Final Fantasy VIII (Disc 2) (Track 01).bin"),
            member(2, "Final Fantasy VIII (Disc 2).cue"),
        )

        val row = libraryDetailDiscs(members).single()

        assertEquals("Final Fantasy VIII (Disc 2).cue", row.displayName)
        assertNotEquals("Final Fantasy VIII (Disc 2) (Track 01).bin", row.displayName)
    }

    @Test
    fun nonDiscMembersNeverBecomeRows() {
        val members = listOf(
            member(0, "Chrono Trigger.sfc"),
            member(0, "Chrono Trigger.srm"),
        )

        assertEquals(emptyList<LibraryDetailDisc>(), libraryDetailDiscs(members))
    }

    @Test
    fun discRowsAreOrderedByDiscNumberNotByArrivalOrder() {
        val members = listOf(
            member(3, "Game (Disc 3).cue"),
            member(1, "Game (Disc 1).cue"),
            member(2, "Game (Disc 2).cue"),
        )

        assertEquals(listOf(1, 2, 3), libraryDetailDiscs(members).map { it.number })
    }

    // ── The trigger ──────────────────────────────────────────────────────────────────────────────

    private fun discs(vararg numbers: Int) = numbers.map { LibraryDetailDisc(it, "Game (Disc $it).iso") }

    /**
     * **A single-file game must never meet a modal on Play.** The menu's emptiness IS the trigger test
     * (`openLaunchChoice` returns false on an empty item list), so this pin is what keeps the everyday
     * press — the overwhelming majority of the example library — exactly as it was.
     */
    @Test
    fun aGameWithNothingToChooseOffersNoMenu() {
        assertTrue(launchChoiceItems(emptyList(), emptyList()).isEmpty())
        assertTrue(launchChoiceItems(discs(1), emptyList()).isEmpty())
    }

    @Test
    fun aMultiDiscGameOffersTheDefaultThenEveryDisc() {
        val items = launchChoiceItems(discs(1, 2), emptyList())

        assertEquals(
            listOf("Play", "Discs", "Disc 1", "Disc 2"),
            items.map {
                when (it) {
                    is MimirSelectItem.Group -> it.label
                    is MimirSelectItem.Option -> it.label
                    is MimirSelectItem.Note -> it.text
                }
            },
        )
    }

    /**
     * **The default row is first, and that is load-bearing.** Play on a multi-disc group hands the
     * emulator the `.m3u` when one exists — the whole set, with the emulator's own swap list. A menu of
     * bare disc entries would make that unreachable and silently downgrade Chrono Cross from "swap
     * between 3 discs" to "boot one disc".
     */
    @Test
    fun theGroupDefaultIsTheFirstAndRecommendedRow() {
        val first = launchChoiceItems(discs(1, 2, 3), emptyList()).first()

        assertTrue(first is MimirSelectItem.Option)
        first as MimirSelectItem.Option
        assertEquals(LaunchChoiceDefaultKey, first.key)
        assertTrue("the default must be the recommended pick", first.recommended)
        assertTrue("and must read as the current one", first.current)
        assertEquals("Starts the whole set", first.subtitle)
    }

    // ── The two seams ────────────────────────────────────────────────────────────────────────────

    /** A DISC crosses as a number; `preferredDisc` cannot express "the PAL copy". */
    @Test
    fun aDiscRowResolvesToItsNumber() {
        assertEquals(LaunchChoice.Disc(2), launchChoiceFor(launchChoiceDiscKey(2)))
        assertEquals(LaunchChoice.GroupDefault, launchChoiceFor(LaunchChoiceDefaultKey))
    }

    /** A VERSION crosses as an EntryKey — the exact member file, which a number cannot name. */
    @Test
    fun aVersionRowResolvesToItsEntryKey() {
        val entry = "p2:23:34:/storage/6A7F-FCFE/ROMs/ps1/Game (Europe).chd"

        assertEquals(
            LaunchChoice.Version(EntryKey(entry)),
            launchChoiceFor(launchChoiceVersionKey(entry)),
        )
    }

    @Test
    fun anUnrelatedKeyIsNotAMenuChoice() {
        listOf(LibraryDetailPlayKey, LibraryDetailOpenWithKey, LibraryDetailRestartKey).forEach {
            assertNull("$it must not resolve to a launch choice", launchChoiceFor(it))
        }
        assertNull(launchChoiceFor(null))
    }

    // ── Versions ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Built generically over group members and pinned here, but **device-unverifiable on the reference hardware**:
     * region siblings only collapse into one group through a HARD identity key, `contentHash` is null
     * until a later hashing stage, and no catalog key resolves — so `(USA)` and `(Europe)` files land in
     * different groups entirely. Stated rather than implied, so nobody reads these greens as device proof.
     */
    @Test
    fun severalNonDiscMembersInOneGroupBecomeVersionRows() {
        val members = listOf(
            member(0, "Game (USA).chd"),
            member(0, "Game (Europe).chd"),
        )

        val items = launchChoiceItems(emptyList(), launchChoiceVersions(members))

        assertEquals(
            listOf("Play", "Versions", "Game (USA).chd", "Game (Europe).chd"),
            items.map {
                when (it) {
                    is MimirSelectItem.Group -> it.label
                    is MimirSelectItem.Option -> it.label
                    is MimirSelectItem.Note -> it.text
                }
            },
        )
    }

    @Test
    fun aLoneMemberIsNotAVersion() {
        assertEquals(emptyList<Any>(), launchChoiceVersions(listOf(member(0, "Game (USA).chd"))))
    }

    /** Discs are not versions: a disc set must not also list every disc under a "Versions" heading. */
    @Test
    fun discMembersNeverAppearAsVersions() {
        val members = listOf(
            member(1, "Game (Disc 1).chd"),
            member(2, "Game (Disc 2).chd"),
        )

        assertEquals(emptyList<Any>(), launchChoiceVersions(members))
    }

    // ── A track set is ONE version (Thor drive 2026-10-08, Sonic Adventure 2) ───────────────────────

    private val sonicAdventure2 = "Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es)"

    /** The Thor's own file names: a `.cue` and three `(Track N).bin` files, none of which carries a disc token, so every member is disc 0. */
    private fun sonicAdventure2Members() = listOf(
        member(0, "$sonicAdventure2 (Track 1).bin"),
        member(0, "$sonicAdventure2 (Track 2).bin"),
        member(0, "$sonicAdventure2 (Track 3).bin"),
        member(0, "$sonicAdventure2.cue"),
    )

    @Test
    fun aCueAndItsTrackBinsAreOneVersionSoPlayAsksNothing() {
        val members = sonicAdventure2Members()

        assertEquals("the tracks are not versions", emptyList<Any>(), launchChoiceVersions(members))
        assertEquals("one version is nothing to choose between", emptyList<Any>(), launchChoiceItems(libraryDetailDiscs(members), launchChoiceVersions(members)))
        assertEquals("so Play goes straight to the game, on Home and in the Library", false, launchChoiceAvailable(members))
    }

    @Test
    fun aGdiAndItsTrackBinsAreOneVersionToo() {
        val members = listOf(
            member(0, "Game (USA).gdi"),
            member(0, "Game (USA) (Track 01).bin"),
            member(0, "Game (USA) (Track 02).bin"),
            member(0, "Game (USA) (Track 03).bin"),
        )

        assertEquals(emptyList<Any>(), launchChoiceVersions(members))
        assertEquals(false, launchChoiceAvailable(members))
    }

    /**
     * The grouper strips bracketed parts and buckets by folder, so a standalone `Game (Europe).bin` shares the group of `Game (USA).cue` and its tracks. Only the
     * tracks that belong to a manifest by name go; the Europe dump is a real version and stays, so the picker still asks.
     */
    @Test
    fun aStandaloneBinBesideACueSetIsStillAVersion() {
        val members = listOf(
            member(0, "Game (USA).cue"),
            member(0, "Game (USA) (Track 1).bin"),
            member(0, "Game (USA) (Track 2).bin"),
            member(0, "Game (USA) (Track 3).bin"),
            member(0, "Game (Europe).bin"),
        )

        assertEquals(
            listOf("Game (USA).cue", "Game (Europe).bin"),
            launchChoiceVersions(members).map { it.displayName }.sortedByDescending { it.endsWith(".cue") },
        )
        assertEquals(true, launchChoiceAvailable(members))
    }

    /** A single-track cue and its one `.bin` carry the same name, so they are one version. */
    @Test
    fun aSingleTrackBinNamedLikeItsCueIsNotAVersion() {
        val members = listOf(member(0, "Some Game (USA).cue"), member(0, "Some Game (USA).bin"))

        assertEquals(emptyList<Any>(), launchChoiceVersions(members))
    }

    @Test
    fun theExtensionIsReadWhateverItsCase() {
        val members = listOf(member(0, "GAME (TRACK 1).BIN"), member(0, "GAME.CUE"))

        assertEquals(emptyList<Any>(), launchChoiceVersions(members))
    }

    /** The manifest is what is listed, not its tracks: a track set beside a second, different file is two versions, named by the launchable files. */
    @Test
    fun aTrackSetBesideAnotherFileIsTwoVersionsAndTheTracksAreNotAmongThem() {
        val members = sonicAdventure2Members() + member(0, "$sonicAdventure2.chd")

        assertEquals(
            listOf("$sonicAdventure2.cue", "$sonicAdventure2.chd"),
            launchChoiceVersions(members).map { it.displayName }.sortedByDescending { it.endsWith(".cue") },
        )
        assertEquals(true, launchChoiceAvailable(members))
    }

    /** Without a manifest a `.bin` is a dump of its own: two of them in one group are still two versions. */
    @Test
    fun bareBinsWithNoManifestStayVersions() {
        val members = listOf(member(0, "Game (USA).bin"), member(0, "Game (Europe).bin"))

        assertEquals(listOf("Game (USA).bin", "Game (Europe).bin"), launchChoiceVersions(members).map { it.displayName })
    }

    /** Disc sets are unchanged: the discs are the Discs rows, the tracks are not versions, and the picker still opens for the set. */
    @Test
    fun aMultiDiscTrackSetStillOffersItsDiscsAndNoVersions() {
        val members = listOf(
            member(1, "Final Fantasy VIII (Disc 1) (Track 01).bin"),
            member(1, "Final Fantasy VIII (Disc 1).cue"),
            member(2, "Final Fantasy VIII (Disc 2) (Track 01).bin"),
            member(2, "Final Fantasy VIII (Disc 2).cue"),
        )

        assertEquals(emptyList<Any>(), launchChoiceVersions(members))
        assertEquals(listOf(1, 2), libraryDetailDiscs(members).map { it.number })
        assertEquals(true, launchChoiceAvailable(members))
    }

    // ── What a press actually commits ────────────────────────────────────────────────────────────

    private fun menuAuthority(): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        authority.pushScope(GameDetailScope)
        authority.pushScope(LaunchChoiceScope)
        return authority
    }

    private fun menuGame() = GameRow(
        gameId = GameId("gamecube/resident-evil"),
        title = "Resident Evil",
        platformId = PlatformId("gamecube"),
        memberCount = 2,
        duplicateKind = DuplicateGroupKind.MULTI_DISC,
        discCount = 2,
    )

    private fun commit(authority: DefaultFocusAuthority, viewModel: LibraryViewModel): Boolean =
        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
            activeDetailTarget = LibraryDetailTarget.Emulated(menuGame()),
        )

    @Test
    fun pickingADiscLaunchesThatDiscByNumber() = runBlocking {
        val port = ChoiceRecordingLaunchPort()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), port).apply { openGameDetail(menuGame()) }
        val authority = menuAuthority()
        authority.registerVirtualChoice(launchChoiceDiscKey(2))
        authority.requestFocus(launchChoiceDiscKey(2))

        assertTrue(commit(authority, viewModel))

        port.awaitLaunch()
        assertEquals(listOf(2 to null), port.calls)
    }

    @Test
    fun pickingAVersionLaunchesThatExactMemberFile() = runBlocking {
        val entry = "p2:23:34:/storage/6A7F-FCFE/ROMs/ps1/Game (Europe).chd"
        val port = ChoiceRecordingLaunchPort()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), port).apply { openGameDetail(menuGame()) }
        val authority = menuAuthority()
        authority.registerVirtualChoice(launchChoiceVersionKey(entry))
        authority.requestFocus(launchChoiceVersionKey(entry))

        assertTrue(commit(authority, viewModel))

        port.awaitLaunch()
        assertEquals("a version must NOT travel as a disc number", listOf(0 to EntryKey(entry)), port.calls)
    }

    /** The default row commits the untouched Play call — no disc, no member, the whole set. */
    @Test
    fun pickingTheDefaultLaunchesExactlyAsPlainPlayAlwaysDid() = runBlocking {
        val port = ChoiceRecordingLaunchPort()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), port).apply { openGameDetail(menuGame()) }
        val authority = menuAuthority()
        authority.registerVirtualChoice(LaunchChoiceDefaultKey)
        authority.requestFocus(LaunchChoiceDefaultKey)

        commit(authority, viewModel)

        port.awaitLaunch()
        assertEquals(listOf(0 to null), port.calls)
    }

    /** The menu closes on commit; it must not stay up over the launching card. */
    @Test
    fun committingAChoicePopsTheMenu() = runBlocking {
        val port = ChoiceRecordingLaunchPort()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), port).apply { openGameDetail(menuGame()) }
        val authority = menuAuthority()
        authority.registerVirtualChoice(launchChoiceDiscKey(1))
        authority.requestFocus(launchChoiceDiscKey(1))

        commit(authority, viewModel)

        assertEquals(GameDetailScope, authority.state.value.scope)
    }

    /** Back leaves the card exactly as it was, launching nothing. */
    @Test
    fun dismissingTheMenuLaunchesNothing() = runBlocking {
        val port = ChoiceRecordingLaunchPort()
        val sessions = LibrarySessionEventLog()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), port, sessions.observer)
            .apply { openGameDetail(menuGame()) }
        val authority = menuAuthority()
        authority.registerVirtualChoice(launchChoiceDiscKey(1))
        authority.requestFocus(launchChoiceDiscKey(1))

        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CANCEL,
            onExit = {},
            onRescanFolder = {},
            activeDetailTarget = LibraryDetailTarget.Emulated(menuGame()),
        )

        // Round 5e: this used to wait a fixed 120 ms, so a launch landing later passed as "nothing launched". The
        // dispatch is synchronous and launch() is the only way to the port, so once every launch admitted so far has
        // settled, the port has seen everything this Back could have sent it.
        sessions.awaitAdmittedLaunchesSettled()
        assertEquals(GameDetailScope, authority.state.value.scope)
        assertEquals(emptyList<Pair<Int, EntryKey?>>(), port.calls)
        assertEquals("and no launch was even admitted", emptyList<LibraryGameSessionEvent>(), sessions.events())
    }

    private fun DefaultFocusAuthority.registerVirtualChoice(key: com.gruning.mimir.rewrite.core.input.FocusKey) {
        registerVirtual(key, LaunchChoiceScope, com.gruning.mimir.rewrite.core.input.FocusBounds(0f, 0f, 100f, 40f))
    }

    /** Suspends until the port has recorded a launch, bounded: launches reach the port on the view model's dispatcher. */
    private suspend fun ChoiceRecordingLaunchPort.awaitLaunch() {
        awaitWithin("a launch reaching the port") { recorded.first { it.isNotEmpty() } }
    }

    /** Records the (disc, member) pair of every launch — 0/null meaning "the group default". */
    private class ChoiceRecordingLaunchPort : LibraryLaunchPort {
        /**
         * Written on the view model's launch dispatcher and read on the test thread, so every read is an immutable
         * snapshot. Round 5e: the plain list this replaced was shared between those threads without a guard.
         */
        val recorded = MutableStateFlow<List<Pair<Int, EntryKey?>>>(emptyList())
        val calls: List<Pair<Int, EntryKey?>> get() = recorded.value

        override suspend fun openWithOptions(game: GameRow): OpenWithOptions =
            OpenWithOptions(hasOverride = false, candidates = emptyList<OpenWithCandidate>())

        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) = Unit
        override suspend fun clearEmulatorOverride(game: GameRow) = Unit

        override suspend fun launch(
            game: GameRow,
            preferredDisc: Int,
            launchMemberEntryKey: EntryKey?,
        ): LaunchResult {
            recorded.update { it + (preferredDisc to launchMemberEntryKey) }
            return LaunchResult(LaunchReadiness.Unsupported(game.platformId))
        }
    }
}
