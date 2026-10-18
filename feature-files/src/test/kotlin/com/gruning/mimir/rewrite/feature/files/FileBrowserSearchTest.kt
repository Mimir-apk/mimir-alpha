package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPaceMs
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.MimirKeyboardInitialKeyId
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **F-08 — search.**
 *
 * **What these tests can and cannot prove — stated plainly rather than implied.** `feature-files` has no
 * Compose harness, so **nothing here renders anything.** Not asserted anywhere below, and not claimed:
 * that the query field draws, that the five chips draw, that the keyboard appears when A is pressed on
 * the field, that a key press types a character, that the Scope card's sentence is legible, or that the
 * result list scrolls. Those are device-only. What IS proven is every decision behind them — the focus
 * keys, the navigation, the dispatch table, the launch gate, the scope lifecycle and the reveal — each
 * of which is a pure function or a state transition over a real `DefaultFocusAuthority`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserSearchTest {
    private val english = filesEnglish()

    private fun file(path: String, name: String = path.substringAfterLast('/')) =
        FileItem(name = name, path = path, isDirectory = false)

    /**
     * F-S2: the stride and the flush cadence are no longer frozen constants, so every call site below
     * names the box it is reasoning about. [FileBrowserGrid.Authored] is the Odin's own solve — the same
     * device these cases were originally calibrated against when the viewport was hard-coded at 307dp.
     */
    private val grid: FileBrowserGrid = FileBrowserGrid.Authored

    // ───────────────────────────────────────────────── the launch gate ──

    @Test
    fun `a blank query under ALL never launches - it would stream the entire volume`() {
        assertFalse(
            "blank + ALL means \"match everything, filter nothing\" -- a walk of every file on the " +
                "card that the player never asked for. This gate is the reason the search screen can " +
                "open without touching the SD card at all.",
            fileBrowserSearchLaunches(query = "", filter = FileSearchFilter.ALL),
        )
        assertFalse("whitespace is blank", fileBrowserSearchLaunches("   ", FileSearchFilter.ALL))
    }

    @Test
    fun `a blank query WITH a chip does launch - the chip is the query`() {
        FileSearchFilter.entries.filter { it != FileSearchFilter.ALL }.forEach { filter ->
            assertTrue(
                "\"show me every ${filter.label} on this card\" is a real request, and " +
                    "FileSearchPolicy's own KDoc blesses it (\"an empty search box with a filter chip " +
                    "selected still browses that kind\")",
                fileBrowserSearchLaunches("", filter),
            )
        }
    }

    @Test
    fun `a two-character query launches - no invented minimum length`() {
        assertTrue(
            "R4 and FF are real ROM names. A length floor would refuse a legitimate search to solve a " +
                "cost problem the debounce already solves.",
            fileBrowserSearchLaunches("R4", FileSearchFilter.ALL),
        )
    }

    @Test
    fun `the debounce is derived from the input pace floor, not picked`() {
        assertEquals(
            "FileBrowserSearchDebounceMs must stay a multiple of core-input's own MimirDirectionalPaceMs " +
                "so it tracks that constant. A bare literal here would be an ungrounded number on the " +
                "one path this tree is most performance-sensitive about.",
            3 * MimirDirectionalPaceMs,
            FileBrowserSearchDebounceMs,
        )
        assertTrue(
            "the window must be long enough to coalesce at least two controller keystrokes, or it is " +
                "decoration -- one keystroke costs at least one accepted directional move plus a CONFIRM",
            FileBrowserSearchDebounceMs >= 2 * MimirDirectionalPaceMs,
        )
    }

    // ───────────────────────────────────────────────── focus key namespace ──

    /**
     * **The single most load-bearing test in this file.**
     *
     * `DefaultFocusAuthority.entries` is a `LinkedHashMap<FocusKey, Entry>` — keyed by `FocusKey`
     * GLOBALLY, with the scope stored as a field of the entry. So if a search result and a browse tile
     * for the same file produced the same key, registering the result would OVERWRITE the tile's entry
     * and move it into the search scope; closing search would then clear it, and the browse listing
     * would come back one tile short of a focus graph — a hole in the D-pad with no visible cause.
     */
    @Test
    fun `a search result key can never collide with a browse tile key for the same file`() {
        val path = "/storage/emulated/0/Roms/gba/Sonic.gba"
        assertNotEquals(
            "search keys must live in a namespace FileBrowserFocus.tileKey cannot produce -- the focus " +
                "authority keys its entry map by FocusKey globally, so a collision silently steals the " +
                "browse tile's registration",
            FileBrowserFocus.tileKey(path),
            FileBrowserSearchFocus.resultKey(path),
        )
        assertNotEquals(
            "and not with the PICK commit key either",
            FileBrowserFocus.commitKey(path),
            FileBrowserSearchFocus.resultKey(path),
        )
        assertEquals("the path round-trips out of the key", path, FileBrowserSearchFocus.resultPath(FileBrowserSearchFocus.resultKey(path)))
        assertNull("a tile key is not a result key", FileBrowserSearchFocus.resultPath(FileBrowserFocus.tileKey(path)))
        assertNull("the query field is not a result", FileBrowserSearchFocus.resultPath(FileBrowserSearchFocus.QueryKey))
    }

    @Test
    fun `the chips are keyed by enum name, so re-wording a chip cannot invalidate a focus key`() {
        assertEquals(
            FileSearchFilter.entries.size,
            FileSearchFilter.entries.map(FileBrowserSearchFocus::filterKey).distinct().size,
        )
        FileSearchFilter.entries.forEach { filter ->
            assertTrue(
                "the key must be built from the enum NAME, never from the player-facing label -- " +
                    "re-wording a chip must not invalidate a focus key mid-session. (Note BIOS's label " +
                    "happens to equal its name, which is exactly why a `key does not contain label` " +
                    "assertion would be unsound here: it passes for four chips by luck and fails for " +
                    "the fifth by coincidence. This asserts the construction rule instead.)",
                FileBrowserSearchFocus.filterKey(filter).value.endsWith("/" + filter.name),
            )
        }
    }

    @Test
    fun `the control row is the design's own chip order`() {
        assertEquals(
            "the design lists ['All','Games','Archives','BIOS','Saves'] and FileSearchFilter declares " +
                "them in that order, so `entries` IS the design's order -- no second list to drift",
            listOf("All", "Games", "Archives", "BIOS", "Saves"),
            FileSearchFilter.entries.map { it.label },
        )
        assertEquals(
            "query field first, then one key per chip",
            1 + FileSearchFilter.entries.size,
            fileBrowserSearchControlRow().size,
        )
        assertEquals(FileBrowserSearchFocus.QueryKey, fileBrowserSearchControlRow().first())
    }

    // ───────────────────────────────────────────────── navigation ──

    private val results = listOf(file("/v/a.gba"), file("/v/b.gba"), file("/v/c.gba"))

    @Test
    fun `LEFT and RIGHT move within the control row and clamp at both ends`() {
        val row = fileBrowserSearchControlRow()
        assertEquals(
            "RIGHT from the query field reaches the first chip",
            row[1],
            fileBrowserSearchNavigate(FileBrowserSearchFocus.QueryKey, ControllerAction.NAV_RIGHT, results),
        )
        assertNull(
            "LEFT from the query field clamps -- there is nothing to its left",
            fileBrowserSearchNavigate(FileBrowserSearchFocus.QueryKey, ControllerAction.NAV_LEFT, results),
        )
        assertNull(
            "RIGHT off the last chip clamps rather than wrapping back to the query field, which would " +
                "teleport the player across the screen",
            fileBrowserSearchNavigate(row.last(), ControllerAction.NAV_RIGHT, results),
        )
    }

    @Test
    fun `DOWN from anywhere in the control row enters the results`() {
        fileBrowserSearchControlRow().forEach { key ->
            assertEquals(
                "the results are one column, so there is exactly one answer from every control",
                FileBrowserSearchFocus.resultKey(results.first().path),
                fileBrowserSearchNavigate(key, ControllerAction.NAV_DOWN, results),
            )
        }
    }

    @Test
    fun `UP from the first result lands on the query field, deterministically`() {
        assertEquals(
            "not on whichever chip was last touched -- the same press must do the same thing on two " +
                "identical screens",
            FileBrowserSearchFocus.QueryKey,
            fileBrowserSearchNavigate(
                FileBrowserSearchFocus.resultKey(results[0].path),
                ControllerAction.NAV_UP,
                results,
            ),
        )
        assertEquals(
            FileBrowserSearchFocus.resultKey(results[0].path),
            fileBrowserSearchNavigate(
                FileBrowserSearchFocus.resultKey(results[1].path),
                ControllerAction.NAV_UP,
                results,
            ),
        )
    }

    @Test
    fun `LEFT and RIGHT on a result move nothing - a full-width row has no horizontal neighbour`() {
        listOf(ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT).forEach { action ->
            assertNull(
                "same rule the pinned commit row already follows; letting geometry answer anyway is " +
                    "what teleported the player into the middle of the tome grid",
                fileBrowserSearchNavigate(
                    FileBrowserSearchFocus.resultKey(results[1].path),
                    action,
                    results,
                ),
            )
        }
    }

    @Test
    fun `DOWN off the last result clamps`() {
        assertNull(
            fileBrowserSearchNavigate(
                FileBrowserSearchFocus.resultKey(results.last().path),
                ControllerAction.NAV_DOWN,
                results,
            ),
        )
    }

    @Test
    fun `a shoulder press pages a screenful of results and clamps at the ends`() {
        val many = (0 until 40).map { file("/v/$it.gba") }
        val stride = fileBrowserSearchPageStride(grid)
        assertTrue("a page must be more than one row or it is not a page", stride > 1)
        assertEquals(
            FileBrowserSearchFocus.resultKey(many[stride].path),
            fileBrowserSearchPageTarget(
                FileBrowserSearchFocus.resultKey(many[0].path), forward = true, results = many, grid = grid,
            ),
        )
        assertEquals(
            "backward from the top clamps to index 0, and index 0 is where we already are -> null, so " +
                "a held shoulder button does not re-emit the same FocusState per repeat",
            null,
            fileBrowserSearchPageTarget(
                FileBrowserSearchFocus.resultKey(many[0].path), forward = false, results = many, grid = grid,
            ),
        )
        assertEquals(
            FileBrowserSearchFocus.resultKey(many.last().path),
            fileBrowserSearchPageTarget(
                FileBrowserSearchFocus.resultKey(many[38].path), forward = true, results = many, grid = grid,
            ),
        )
    }

    @Test
    fun `the page stride is derived from the search screen's own shorter viewport -- SOLVED, not frozen`() {
        // F-S2. This case used to compare one frozen constant against another frozen constant, which is
        // the shape that let both of them stay wrong: the search viewport was 307f, derived from the
        // folio's 350f, while the folio itself had been solving its own viewport since RC-12.
        MimirCertifiedViewports.standard.forEach { viewport ->
            val box = viewport.solverInputDp
            val solved = fileBrowserSolveGrid(box.widthDp, box.heightDp)
            assertTrue(
                "${viewport.name}: the search screen loses a control row the folio does not have, so " +
                    "its viewport must be SHORTER than the folio's at the SAME box",
                fileBrowserSearchViewportDp(solved) < solved.contentViewportDp,
            )
        }
        // Hand-transcribed, NOT re-derived through the production chain -- an expression asserted
        // against itself proves only that Kotlin is deterministic. 350 (the Odin's own solved folio
        // viewport) - 48 (the control row, on the touch floor since F-S4) - 9 (the Column's gap) = 293.
        assertEquals(
            "the Odin's solved search viewport",
            293.0f,
            fileBrowserSearchViewportDp(FileBrowserGrid.Authored),
            0.01f,
        )
        // 14 (the row's own vertical padding) + 24 + 24 (its two line boxes) = 62dp painted, MEASURED on
        // the Nova 2026-08-22 across four consecutive rows, + 9 (the list's gap) = 71dp of pitch. The old
        // 55 assumed a single-line row that this screen has never drawn.
        assertEquals(
            "row pitch = the row's real painted two-line height + the list's gap",
            71.0f,
            FileBrowserSearchResultRowPitchDp,
            0.01f,
        )
    }

    // ───────────────────────────────────────────────── dispatch ──

    @Test
    fun `CONFIRM does the right one of three things`() {
        assertEquals(
            FileBrowserSearchCommand.OpenKeyboard,
            fileBrowserSearchCommand(ControllerAction.CONFIRM, FileBrowserSearchFocus.QueryKey, results, grid),
        )
        assertEquals(
            FileBrowserSearchCommand.SelectFilter(FileSearchFilter.ARCHIVES),
            fileBrowserSearchCommand(
                ControllerAction.CONFIRM,
                FileBrowserSearchFocus.filterKey(FileSearchFilter.ARCHIVES),
                results,
                grid,
            ),
        )
        assertEquals(
            FileBrowserSearchCommand.Reveal("/v/b.gba"),
            fileBrowserSearchCommand(
                ControllerAction.CONFIRM,
                FileBrowserSearchFocus.resultKey("/v/b.gba"),
                results,
                grid,
            ),
        )
    }

    @Test
    fun `CANCEL leaves search`() {
        listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { action ->
            assertEquals(
                FileBrowserSearchCommand.Close,
                fileBrowserSearchCommand(action, FileBrowserSearchFocus.QueryKey, results, grid),
            )
        }
    }

    @Test
    fun `SECONDARY on a search result is consumed, never extracted`() {
        assertEquals(
            "extraction refreshes the directory the player is STANDING in, which during a search is " +
                "not the directory the archive lives in -- it would extract correctly and then refresh " +
                "the wrong listing, so the player sees nothing happen. Reveal first, then extract.",
            FileBrowserSearchCommand.Consumed,
            fileBrowserSearchCommand(
                ControllerAction.SECONDARY,
                FileBrowserSearchFocus.resultKey("/v/b.gba"),
                results,
                grid,
            ),
        )
    }

    @Test
    fun `every action produces a command - the search screen is never a hole in the modal`() {
        ControllerAction.entries.forEach { action ->
            fileBrowserSearchControlRow().plus(FileBrowserSearchFocus.resultKey("/v/a.gba")).forEach { key ->
                // The assertion is that this call returns at all: `fileBrowserSearchCommand` is total,
                // which is what lets FileBrowserController.dispatch answer `true` unconditionally while
                // search is open. A modal that returns false leaks the press to the destination beneath.
                fileBrowserSearchCommand(action, key, results, grid)
            }
        }
    }

    // ───────────────────────────────────────────────── copy ──

    @Test
    fun `the count line names the volume when it can and refuses to guess when it cannot`() {
        assertEquals(
            "3 matches on SD Card",
            fileBrowserSearchStatusLabel(english, 3, FileBrowserSearchPhase.SETTLED, "SD Card"),
        )
        assertEquals(
            "1 match on SD Card",
            fileBrowserSearchStatusLabel(english, 1, FileBrowserSearchPhase.SETTLED, "SD Card"),
        )
        assertEquals(
            "the design hardcodes 'SD card' because its mock has one fake volume; a real device may be " +
                "searching internal storage, and naming the wrong card is an unverified assertion",
            "0 matches on this volume",
            fileBrowserSearchStatusLabel(english, 0, FileBrowserSearchPhase.SETTLED, null),
        )
        assertEquals(
            "a blank label is the same as none -- a Context-backed volume lookup that finds nothing " +
                "returns \"\" at least as readily as null",
            "0 matches on this volume",
            fileBrowserSearchStatusLabel(english, 0, FileBrowserSearchPhase.SETTLED, "  "),
        )
    }

    @Test
    fun `a running walk says so, because a settled-looking count would be a lie about completeness`() {
        assertEquals(
            "3 that becomes 40 a second later, presented as final, is the defect this phase exists for",
            "Searching on SD Card · 3 so far",
            fileBrowserSearchStatusLabel(english, 3, FileBrowserSearchPhase.RUNNING, "SD Card"),
        )
        assertNull(
            "no count before a query exists -- the caller draws the scope card's prompt, not a zero",
            fileBrowserSearchStatusLabel(english, 0, FileBrowserSearchPhase.IDLE, "SD Card"),
        )
    }

    @Test
    fun `empty results get three honest states, never a spinner`() {
        assertEquals("Type a name, or pick a filter.", fileBrowserSearchEmptyMessage(english, 0, FileBrowserSearchPhase.IDLE))
        assertEquals("Looking…", fileBrowserSearchEmptyMessage(english, 0, FileBrowserSearchPhase.RUNNING))
        assertEquals(
            "the walk has a real end and SETTLED is what says so -- this is the sentence a spinner " +
                "that never resolves would have replaced",
            "Nothing here by that name.",
            fileBrowserSearchEmptyMessage(english, 0, FileBrowserSearchPhase.SETTLED),
        )
        assertNull("with results there is nothing empty to announce", fileBrowserSearchEmptyMessage(english, 1, FileBrowserSearchPhase.SETTLED))
    }

    // ───────────────────────────────────────────────── reveal ──

    @Test
    fun `the reveal stack is every ancestor from the root down to but not including the target`() {
        assertEquals(
            listOf("/storage", "/storage/emulated", "/storage/emulated/0"),
            fileBrowserRevealStack("/storage", "/storage/emulated/0/Roms"),
        )
        assertEquals(
            "revealing the root itself means an empty stack, which is what atRoot reads",
            emptyList<String>(),
            fileBrowserRevealStack("/storage/emulated/0", "/storage/emulated/0"),
        )
    }

    /**
     * **This test was WRONG on its first writing and a differential caught it.** It originally passed
     * `root = "/storage/emulated/01"`, `target = "/storage/emulated/0/Roms"` — which a plain
     * `target.startsWith(root)` also rejects, so replacing the segment-aware check with `startsWith`
     * left it GREEN. The hazard runs the other way: it is the ROOT that must not be a bare prefix of
     * the TARGET. Written correctly below, `startsWith` builds a stack rooted in a volume that does not
     * contain the file and drops the player there.
     */
    @Test
    fun `prefix matching is segment-aware, so a sibling with a longer name is not an ancestor`() {
        assertNull(
            "/storage/emulated/0 is NOT an ancestor of /storage/emulated/01/Roms -- a raw startsWith " +
                "says it is, and the reveal would drop the player into a volume that does not contain " +
                "their file, with a back-stack that cannot walk out of it",
            fileBrowserRevealStack("/storage/emulated/0", "/storage/emulated/01/Roms"),
        )
        assertNull(
            "and the reverse direction is refused too",
            fileBrowserRevealStack("/storage/emulated/01", "/storage/emulated/0/Roms"),
        )
        assertNull(
            "a target outside the root is refused, never approximated",
            fileBrowserRevealStack("/storage/emulated/0", "/storage/1A2B/Roms"),
        )
    }

    @Test
    fun `trailing slashes do not change which ancestors the stack contains`() {
        val plain = fileBrowserRevealStack("/storage/emulated/0", "/storage/emulated/0/Roms/gba")!!
        val slashed = fileBrowserRevealStack("/storage/emulated/0/", "/storage/emulated/0/Roms/gba/")!!
        assertEquals("same depth", plain.size, slashed.size)
        assertEquals("same descendants", plain.drop(1), slashed.drop(1))
        assertEquals(
            "the HEAD is deliberately the rootPath exactly as the caller passed it, slash and all -- " +
                "FileBrowserController.load(rootPath) sets currentPath to that same string on open(), " +
                "so an Up walk that returns a normalised variant would compare unequal to it",
            "/storage/emulated/0/",
            slashed.first(),
        )
    }

    @Test
    fun `the parent of a hit is the directory the reveal lands in`() {
        assertEquals("/v/Roms", fileBrowserSearchParentPath("/v/Roms/Sonic.gba"))
    }

    // ───────────────────────────────────────────────── batching ──

    @Test
    fun `a burst flushes on count and a trickle flushes on time`() {
        assertTrue(
            "a full screenful arriving at once must draw rather than wait for a clock",
            fileBrowserSearchShouldFlush(pending = fileBrowserSearchFlushItems(grid), elapsedMs = 0, grid = grid),
        )
        assertTrue(
            "one result found on a big card must not sit invisible until 22 more arrive",
            fileBrowserSearchShouldFlush(pending = 1, elapsedMs = FileBrowserSearchFlushMs, grid = grid),
        )
        assertFalse(
            "and neither term alone fires early -- that is the recomposition storm this exists to stop",
            fileBrowserSearchShouldFlush(pending = 1, elapsedMs = 0, grid = grid),
        )
        assertFalse(
            "nothing pending is never a publish, however long it has been",
            fileBrowserSearchShouldFlush(pending = 0, elapsedMs = 10_000, grid = grid),
        )
    }

    @Test
    fun `results are never re-sorted, because the row under the player's focus must not change identity`() {
        val unsorted = listOf(file("/v/zeta.gba"), file("/v/alpha.gba"), file("/v/mid.gba"))
        assertEquals(
            "fileBrowserPartition sorts by name and splits directories out; both are wrong for a " +
                "streaming list, where a re-sort on every batch moves the row the player is standing on",
            unsorted.map { it.path },
            fileBrowserSearchTiles(unsorted) { FileBrowserRecognition.None }.map { it.path },
        )
    }

    @Test
    fun `directories survive as results - the engine emits them and the policy pins that they match`() {
        val tiles = fileBrowserSearchTiles(
            listOf(FileItem(name = "Pokemon", path = "/v/Pokemon", isDirectory = true)),
        ) { FileBrowserRecognition.None }
        assertEquals(
            "the design mock skips folders (its collector filters !e.d) but that is the mock's " +
                "simplification -- a folder called Pokemon is a legitimate answer to a search for pokemon",
            FileBrowserTileKind.DIRECTORY,
            tiles.single().kind,
        )
    }

    // ───────────────────────────────────────────────── incremental classification (R6-P2-07) ──

    @Test
    fun `a file already turned into a tile is not reclassified when a later, larger batch republishes it`() {
        var classifyCalls = 0
        val classify: (FileItem) -> FileBrowserRecognition = { classifyCalls++; FileBrowserRecognition.None }
        val cache = mutableMapOf<String, FileBrowserTile>()
        val a = file("/v/a.gba")
        val b = file("/v/b.gba")
        val c = file("/v/c.gba")

        // Three growing batches, exactly as FileBrowserSearchCollector publishes them: each flush hands
        // fileBrowserSearchTiles the WHOLE accumulated list so far, not just what is new.
        fileBrowserSearchTiles(listOf(a), cache, classify)
        fileBrowserSearchTiles(listOf(a, b), cache, classify)
        fileBrowserSearchTiles(listOf(a, b, c), cache, classify)

        assertEquals(
            "reclassifying the whole accumulated list on every flush costs 1 + 2 + 3 = 6 " +
                "classifyPlatform calls for 3 real files -- quadratic in the number of batches. Keyed " +
                "by path, a file already classified must be skipped when a later batch republishes it: " +
                "1 + 1 + 1 = 3.",
            3,
            classifyCalls,
        )
    }

    // ───────────────────────────────────────────────── the volume label ──

    @Test
    fun `the volume label is the deepest labelled ancestor, not the shallowest`() {
        val labels = mapOf(
            "/storage" to "Drives",
            "/storage/1A2B-3C4D" to "SD Card",
            "/storage/emulated/0" to "Internal Storage",
        )
        assertEquals(
            "shallowest-match would say \"Drives\", which names the chooser rather than the volume",
            "SD Card",
            fileBrowserSearchVolumeLabel("/storage/1A2B-3C4D/Roms/gba", labels),
        )
        assertEquals("Internal Storage", fileBrowserSearchVolumeLabel("/storage/emulated/0/Roms", labels))
        assertNull("nothing labelled -> refuse to guess", fileBrowserSearchVolumeLabel("/storage/1A2B/x", emptyMap()))
        assertNull(
            "a sibling that merely shares a prefix is not an ancestor",
            fileBrowserSearchVolumeLabel("/storage2/x", labels),
        )
    }
}

/**
 * The search session against a **real** [DefaultFocusAuthority] — the scope lifecycle, the never-false
 * dispatch, the teardown unwind and the reveal.
 *
 * Nothing is composed here either. What these prove is the state machine the composables drive.
 */
class FileBrowserSearchSessionTest {

    private val root = "/v"

    private fun dir(path: String) = FileItem(name = path.substringAfterLast('/'), path = path, isDirectory = true)
    private fun file(path: String) = FileItem(name = path.substringAfterLast('/'), path = path, isDirectory = false)

    private val listing = mapOf(
        "/v" to listOf(dir("/v/Roms"), file("/v/readme.txt")),
        "/v/Roms" to listOf(dir("/v/Roms/gba")),
        "/v/Roms/gba" to listOf(file("/v/Roms/gba/Sonic.gba"), file("/v/Roms/gba/Mario.gba")),
    )

    private fun controller(
        authority: DefaultFocusAuthority,
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
    ) = FileBrowserController(
        authority = authority,
        rootPath = root,
        mode = mode,
        listDirectory = { listing[it].orEmpty() },
        onChoose = {},
        onDismiss = {},
    )

    @Test
    fun `opening search pushes its own scope and lands on the query field`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        assertEquals(c.searchScope, authority.state.value.scope)
        assertEquals(
            "search must land on the query field, deterministically. NOTE, disclosed: this pins the " +
                "LANDING, not the register-before-push ordering -- a differential that pushed the scope " +
                "first left this green, because openSearch's explicit requestFocus is what decides the " +
                "landing and the ordering is only defence in depth. Said here rather than left to look " +
                "like coverage it is not.",
            FileBrowserSearchFocus.QueryKey,
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `the search scope is a child of the browse scope, never the same one`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        assertNotEquals(c.scope, c.searchScope)
        val custom = FileBrowserController(
            authority = authority,
            rootPath = root,
            listDirectory = { emptyList() },
            onChoose = {},
            onDismiss = {},
            scope = FocusScope("other"),
        )
        assertEquals(
            "derived from the browse scope so two mounted browsers get two distinct search graphs " +
                "rather than sharing one global constant",
            FocusScope("other/search"),
            custom.searchScope,
        )
    }

    /** The brief's *"CANCEL leaves search and returns the player to the listing they came from, with
     * their place intact"* — which is a property of the scope stack, not of bookkeeping in this module. */
    @Test
    fun `CANCEL out of search restores the exact tile the player was standing on`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        // Stand somewhere that is NOT index 0, so a fallback to index 0 could not be mistaken for a
        // restore -- the whole point is that the player's own place comes back.
        authority.requestFocus(FileBrowserFocus.tileKey("/v/readme.txt"))
        val standingOn = authority.state.value.focused
        assertEquals(FileBrowserFocus.tileKey("/v/readme.txt"), standingOn)

        c.openSearch()
        assertNotEquals(standingOn, authority.state.value.focused)
        c.dispatch(ControllerAction.CANCEL)

        assertEquals("back in the browse scope", c.scope, authority.state.value.scope)
        assertEquals(
            "and on the same tile, not at index 0 -- popScope restores focusMemory[files]",
            standingOn,
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `dispatch never returns false while search is open`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        c.publishSearchResults(listOf(file("/v/Roms/gba/Sonic.gba")), FileBrowserSearchPhase.SETTLED)
        ControllerAction.entries.forEach { action ->
            if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) return@forEach
            assertTrue(
                "a modal that answers false leaks the press to the destination underneath -- " +
                    "dispatchFolderBrowser's last arm refuses, and the browser is still on screen. " +
                    "Action that leaked: $action",
                c.dispatch(action),
            )
        }
        c.close()
    }

    /**
     * The advisor-flagged hazard, pinned: `dispatchMimirKeyboardInput` returns **false** for TERTIARY, for
     * an unwired trigger, and for a CONFIRM whose focused key has no activator. If that false were allowed
     * to fall through the scope guards below it, `dispatch` would answer false while a KEYBOARD is on
     * screen and the press would move the destination underneath.
     */
    @Test
    fun `dispatch absorbs even the actions the keyboard itself refuses`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val binding = createMimirKeyboardFocusBindingForTests(authority)
        c.attachSearchKeyboard(binding)
        c.openSearchKeyboard()
        binding.enter()
        assertEquals(MimirKeyboardFocusScope, authority.state.value.scope)

        // No keyboard is composed, so no activator is registered for any key: CONFIRM cannot be handled,
        // and TERTIARY/triggers are refused outright by dispatchMimirKeyboardInput.
        listOf(ControllerAction.TERTIARY, ControllerAction.CONFIRM, ControllerAction.TRIGGER_LEFT).forEach { action ->
            assertTrue("leaked while the keyboard was up: $action", c.dispatch(action))
        }
        c.close()
    }

    @Test
    fun `CANCEL closes the keyboard even when no binding was ever attached`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        // The mount frame: the flag is up but the composable has not handed its binding down yet. Without
        // the explicit escape hatch this branch absorbs every press forever.
        c.openSearchKeyboard()
        authority.pushScope(MimirKeyboardFocusScope)
        assertTrue(c.dispatch(ControllerAction.CANCEL))
        assertFalse("the keyboard must be down again", c.searchKeyboardOpen)
        authority.popScope()
        c.close()
    }

    /** Teardown from the deepest possible state. Without the unwind in `close()` the browse scope is
     * buried two deep, its own guard matches nothing, and NOTHING is popped. */
    @Test
    fun `close from a search-plus-keyboard state unwinds every scope this controller pushed`() {
        val authority = DefaultFocusAuthority()
        val before = authority.state.value.scope
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val binding = createMimirKeyboardFocusBindingForTests(authority)
        c.attachSearchKeyboard(binding)
        c.openSearchKeyboard()
        binding.enter()
        assertEquals(MimirKeyboardFocusScope, authority.state.value.scope)

        c.close()

        assertEquals(
            "three scopes deep in, zero out -- a stack left corrupted here breaks whatever surface " +
                "mounts next, not this one",
            before,
            authority.state.value.scope,
        )
        c.close() // idempotent
        assertEquals(before, authority.state.value.scope)
    }

    @Test
    fun `TERTIARY opens search only when a provider was wired`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        assertTrue("still a hard modal", c.dispatch(ControllerAction.TERTIARY))
        assertFalse(
            "with no provider, a search screen would answer every query with \"nothing here\" over a " +
                "card full of games -- worse than its absence",
            c.searchOpen,
        )
        c.setSearchAvailable(true)
        c.dispatch(ControllerAction.TERTIARY)
        assertTrue(c.searchOpen)
        c.close()
    }

    @Test
    fun `losing the provider mid-session closes an open search rather than stranding it`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        c.setSearchAvailable(false)
        assertFalse(c.searchOpen)
        assertEquals("and its scope is popped, not orphaned", c.scope, authority.state.value.scope)
        c.close()
    }

    @Test
    fun `revealing a hit lands in its directory with the file focused and the crumb trail correct`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val hit = file("/v/Roms/gba/Sonic.gba")
        c.publishSearchResults(listOf(hit), FileBrowserSearchPhase.SETTLED)
        authority.requestFocus(FileBrowserSearchFocus.resultKey(hit.path))
        c.dispatch(ControllerAction.CONFIRM)

        assertFalse("search is left behind", c.searchOpen)
        assertEquals("/v/Roms/gba", c.currentPath)
        assertEquals(
            "the found file holds focus -- seeded through load()'s own per-directory memory rather " +
                "than a requestFocus that would fight it",
            FileBrowserFocus.tileKey(hit.path),
            authority.state.value.focused,
        )
        assertFalse("the back-stack is rebuilt, so B walks back up naturally", c.atRoot)
        c.dispatch(ControllerAction.CANCEL)
        assertEquals("/v/Roms", c.currentPath)
        c.dispatch(ControllerAction.CANCEL)
        assertEquals("/v", c.currentPath)
        assertTrue(c.atRoot)
        c.close()
    }

    @Test
    fun `a result whose file vanished before the reveal degrades to the listing, never to a dead focus`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val ghost = file("/v/Roms/gba/Deleted.gba") // not in `listing`
        c.publishSearchResults(listOf(ghost), FileBrowserSearchPhase.SETTLED)
        authority.requestFocus(FileBrowserSearchFocus.resultKey(ghost.path))
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals("/v/Roms/gba", c.currentPath)
        assertNotEquals(
            "load() validates the remembered key against the incoming listing, so a vanished file " +
                "falls back to index 0 rather than leaving focus pointing at nothing",
            FileBrowserFocus.tileKey(ghost.path),
            authority.state.value.focused,
        )
        assertEquals(FileBrowserFocus.tileKey("/v/Roms/gba/Mario.gba"), authority.state.value.focused)
        c.close()
    }

    @Test
    fun `every result is registered, including ones no LazyColumn would have composed`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val many = (0 until 200).map { file("/v/$it.gba") }
        c.publishSearchResults(many, FileBrowserSearchPhase.SETTLED)
        val far = FileBrowserSearchFocus.resultKey(many[199].path)
        authority.requestFocus(far)
        assertEquals(
            "requestFocus is a silent no-op for an unregistered key (entries[key] ?: return), so " +
                "without virtual registration a page jump past the visible window would do nothing",
            far,
            authority.state.value.focused,
        )
        c.close()
    }

    /**
     * **LOUD DISCLOSURE — the cascade guard in `publishSearchResults` is NOT pinned, and this test does
     * not pin it.** Stated here rather than left for a reader to discover.
     *
     * `publishSearchResults` clears focus before the tracker retires a departed focused key, because
     * `DefaultFocusAuthority.removeEntryAndFallback` otherwise falls focus onto a surviving sibling —
     * which is then retired too, cascading once per remaining result. A differential proved that
     * removing that guard leaves every test in this file GREEN, and it is worth saying why rather than
     * inventing a test that only appears to cover it:
     *
     *  - the END state is identical either way, because the guarded path finishes with an explicit
     *    `requestFocus(QueryKey)` and the cascade would be overwritten by that same call;
     *  - the difference is emission COUNT and cost — `removeEntryAndFallback` scans every entry, so the
     *    cascade is O(results²) — and `MutableStateFlow` conflates, so even a coroutine-driven collector
     *    could not reliably count the intermediate emissions;
     *  - `feature-files` has no coroutines-test dependency at all (`testImplementation(libs.junit)`).
     *
     * So the guard is kept on the strength of `FileBrowserController.close`'s already-documented
     * pop-before-clear mechanism, which is the same defect in the same authority, and it is carried on
     * an unverified obligation rather than counted as covered. What this test DOES pin is the
     * observable half: after a query change, focus is on the query field and not on a retired key.
     */
    @Test
    fun `a query change lands focus on the query field, never on a retired result`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val first = (0 until 50).map { file("/v/old$it.gba") }
        c.publishSearchResults(first, FileBrowserSearchPhase.SETTLED)
        authority.requestFocus(FileBrowserSearchFocus.resultKey(first[25].path))

        c.publishSearchResults(emptyList(), FileBrowserSearchPhase.RUNNING)

        assertEquals(
            "focus must land on the query field, not on whatever key a fallback happened to stop on",
            FileBrowserSearchFocus.QueryKey,
            authority.state.value.focused,
        )
        assertEquals("and in the search scope, not stranded elsewhere", c.searchScope, authority.state.value.scope)
        c.close()
    }

    /**
     * Registers a couple of keyboard keys the way a composed `MimirKeyboard` would — its `decorateKey`
     * returns a `Modifier.mimirFocusable(...)` per key, which registers into `MimirKeyboardFocusScope`.
     * Without this, `binding.enter()`'s `requestFocus` returns at `entries[key] ?: return` and the
     * keyboard has no focus to protect, which would make the test below pass vacuously.
     */
    private fun registerFakeKeyboardKeys(authority: DefaultFocusAuthority) {
        listOf(MimirKeyboardInitialKeyId, "char/a", "char/b").forEachIndexed { index, id ->
            authority.registerVirtual(
                key = mimirKeyboardFocusKey(id),
                scope = MimirKeyboardFocusScope,
                bounds = FocusBounds(left = index * 100f, top = 0f, right = index * 100f + 90f, bottom = 90f),
            )
        }
    }

    /**
     * **The defect this pins was live and would have shipped.** It is the exact failure a JVM test can
     * see and a code review cannot: typing ONE character bricked the on-screen keyboard.
     *
     * Chain: `MimirKeyboard.onValueChange` -> `setSearchQuery` -> `resetSearchResults` ->
     * `publishSearchResults`, which ran its focus repair against whatever scope was active — the
     * KEYBOARD's. `DefaultFocusAuthority.clearFocus` acts on the active scope, so it nulled the
     * keyboard's focused key and parked the cleared sentinel in the keyboard's memory; the follow-up
     * `requestFocus(QueryKey)` was then refused outright by `if (entry.scope != activeScope) return`.
     * The player would have typed one letter and lost the D-pad and the A button, apparently reviving
     * only on L2/R2 (which change the key set and make `reconcileFocus` re-seed).
     *
     * No other test in this file could see it: every one of them publishes while the SEARCH scope is
     * active, which is the one case the old code got right.
     */
    @Test
    fun `publishing results while the keyboard is up must not touch the keyboard's focus`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val binding = createMimirKeyboardFocusBindingForTests(authority)
        c.attachSearchKeyboard(binding)
        c.openSearchKeyboard()
        registerFakeKeyboardKeys(authority)
        binding.enter()

        val beforeScope = authority.state.value.scope
        val beforeFocus = authority.state.value.focused
        assertEquals(MimirKeyboardFocusScope, beforeScope)
        assertNotNull("precondition: the keyboard landed on a key", beforeFocus)

        // One character typed.
        c.setSearchQuery("s")
        assertEquals("the keyboard still owns the scope", beforeScope, authority.state.value.scope)
        assertEquals(
            "and its focused KEY is untouched -- clearFocus() acts on the ACTIVE scope, so an " +
                "unguarded repair here nulls the keyboard's own focus and requestFocus cannot put it " +
                "back across the scope boundary",
            beforeFocus,
            authority.state.value.focused,
        )

        // And a batch flush arriving mid-typing must not do it either.
        c.publishSearchResults(listOf(file("/v/Roms/gba/Sonic.gba")), FileBrowserSearchPhase.RUNNING)
        assertEquals(beforeScope, authority.state.value.scope)
        assertEquals(beforeFocus, authority.state.value.focused)

        // The keyboard still works: a directional move resolves rather than dying on a null focus.
        assertTrue("the keyboard must still be drivable", c.dispatch(ControllerAction.NAV_LEFT))
        assertNotNull("focus survives the move", authority.state.value.focused)
        c.close()
    }

    @Test
    fun `closing the keyboard hands focus back to the search screen, not to nothing`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val binding = createMimirKeyboardFocusBindingForTests(authority)
        c.attachSearchKeyboard(binding)
        c.openSearchKeyboard()
        registerFakeKeyboardKeys(authority)
        binding.enter()
        c.setSearchQuery("sonic")
        c.publishSearchResults(listOf(file("/v/Roms/gba/Sonic.gba")), FileBrowserSearchPhase.SETTLED)

        c.closeSearchKeyboard()

        assertEquals("back on the search screen", c.searchScope, authority.state.value.scope)
        assertNotNull(
            "popScope restores focusMemory[searchScope] validated against live entries -- a null here " +
                "would be a search screen with no focus and therefore no D-pad",
            authority.state.value.focused,
        )
        assertEquals("and the typed query survived", "sonic", c.searchQuery)
        c.close()
    }

    @Test
    fun `re-opening the browser does not inherit the previous session's search screen`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        assertEquals(c.searchScope, authority.state.value.scope)
        // No close() -- simulate a re-open over a live search session.
        c.open()
        assertFalse("the stale session is gone", c.searchOpen)
        assertEquals(
            "and the browse scope is active -- a stranded search scope would sit above it and swallow " +
                "every press the folio was supposed to get",
            c.scope,
            authority.state.value.scope,
        )
        c.close()
    }

    @Test
    fun `PICK mode gets search too - it reads, it does not write`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority, mode = FileBrowserMode.PICK)
        c.open()
        c.setSearchAvailable(true)
        c.dispatch(ControllerAction.TERTIARY)
        assertTrue(
            "the destructive half is MANAGE-only, but search only ever lists names -- refusing it in " +
                "the add-ROMs picker would be a restriction with no safety behind it",
            c.searchOpen,
        )
        c.close()
    }
}
