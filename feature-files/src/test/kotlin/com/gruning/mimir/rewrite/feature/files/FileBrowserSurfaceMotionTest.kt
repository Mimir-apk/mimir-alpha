package com.gruning.mimir.rewrite.feature.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **File-manager arrival coverage:** transitions are wired for opening and returning through every
 * surface path, with arrival decisions tested separately from the visual result.
 *
 * ## What a passing test here does and does not prove
 *
 * `feature-files` has **no Compose harness and no Robolectric**. Nothing below composes anything, so
 * nothing below can assert that a pixel moved, that an animation lasted 160 ms, that a focus ring painted
 * clean, or that any of this *looks* graceful. What is proven is the DECISION: which arrival is produced
 * for which state change, which one wins when two are true at once, how far each one travels, and that
 * the surface's call sites are wired to the answers. **Whether the result reads as graceful is a device-level visual judgment, and nothing here substitutes for that.**
 */
class FileBrowserSurfaceMotionTest {

    private fun enter(path: String, sequence: Long) =
        FileBrowserNavigationMotion(path, FileBrowserGlideEnter, sequence)

    private fun up(path: String, sequence: Long) =
        FileBrowserNavigationMotion(path, FileBrowserGlideReturn, sequence)

    // ───────────────────────────────────────────────────────────── which screen is on the slot ──

    @Test
    fun `the screen is read in the surface's own branch order`() {
        assertEquals(FileBrowserScreen.BROWSE, fileBrowserScreenOf(queueOpen = false, searchOpen = false))
        assertEquals(FileBrowserScreen.SEARCH, fileBrowserScreenOf(queueOpen = false, searchOpen = true))
        assertEquals(FileBrowserScreen.QUEUE, fileBrowserScreenOf(queueOpen = true, searchOpen = false))
        assertEquals(
            "the surface tests queueOpen FIRST, so if that invariant is ever broken upstream the motion " +
                "model must name the same screen the render draws -- never fade a page that is not on screen",
            FileBrowserScreen.QUEUE,
            fileBrowserScreenOf(queueOpen = true, searchOpen = true),
        )
    }

    @Test
    fun `the extract chooser, the transfer chooser, the destination list, the disc-set prompt and the action overlay are now visible to this model`() {
        // R6-F-05, spec §5: fileBrowserScreenOf saw only queue and search, so closing one of the five
        // screens that ALSO replace the folio's slot re-mounted FileBrowserContent with the
        // surface-level arrival unchanged -- a stale DIRECTORY arrival replayed its glide, or a stale
        // NONE flashed the folio at the top. Same defect class as the Y-toggle jitter (R6-P2-04).
        assertEquals(
            "one value stands for all five: this model only ever needs to know THAT the slot changed, " +
                "never which of the five took it -- the controller keeps them mutually exclusive by " +
                "construction, the same way it already does for queue and search",
            FileBrowserScreen.OVERLAY,
            fileBrowserScreenOf(queueOpen = false, searchOpen = false, overlayOpen = true),
        )
        assertEquals(
            "and it is tested FIRST, matching the surface's own branch order -- the five sit ahead of " +
                "the queue and the search screen in FileBrowserSurface's if/else chain",
            FileBrowserScreen.OVERLAY,
            fileBrowserScreenOf(queueOpen = true, searchOpen = true, overlayOpen = true),
        )
    }

    // ───────────────────────────────────────────────────────────────────── the arrival itself ──

    @Test
    fun `a first frame is never an arrival`() {
        val history = FileBrowserArrivalHistory()
        assertEquals(
            "there is no predecessor to have arrived from -- the same reason open() nulls the motion",
            FileBrowserArrivalKind.NONE,
            history.frame(FileBrowserScreen.BROWSE, "/roms", null).kind,
        )
    }

    @Test
    fun `entering a folder is a directory arrival carrying the motion's own direction`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)

        val entered = history.frame(FileBrowserScreen.BROWSE, "/roms/gba", enter("/roms/gba", 1L))
        assertEquals(FileBrowserArrivalKind.DIRECTORY, entered.kind)
        assertEquals(FileBrowserGlideEnter, entered.direction)

        val returned = history.frame(FileBrowserScreen.BROWSE, "/roms", up("/roms", 2L))
        assertEquals(FileBrowserArrivalKind.DIRECTORY, returned.kind)
        assertEquals(
            "entering and returning must read as opposite movements, not as one symmetric fade",
            FileBrowserGlideReturn,
            returned.direction,
        )
    }

    @Test
    fun `a refresh in place is not an arrival`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        val motion = enter("/roms/gba", 1L)
        history.frame(FileBrowserScreen.BROWSE, "/roms/gba", motion)

        assertEquals(
            "contract A's post-extraction refresh reloads the same directory without bumping the " +
                "sequence -- the listing must not glide for a change of contents, and the ring must not " +
                "blink off",
            FileBrowserArrivalKind.NONE,
            history.frame(FileBrowserScreen.BROWSE, "/roms/gba", motion).kind,
        )
    }

    /**
     * **The defect V-1 found while mapping.** `fileBrowserNavigationGlideApplies` is
     * `motion?.path == currentPath`, and the motion is never cleared, so the predicate stays true forever
     * after a navigation. Once search and the queue began REPLACING the folio, closing either one
     * re-mounted the content column with that predicate still saying yes — replaying the last unrelated
     * navigation's glide, in its direction, or not animating at all for a player who had never left the
     * root. A transition that fires by accident is the same defect class as one that never fires.
     */
    @Test
    fun `one armed motion is played exactly once, however often it is seen`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        val motion = enter("/roms/gba", 1L)

        assertEquals(
            FileBrowserArrivalKind.DIRECTORY,
            history.frame(FileBrowserScreen.BROWSE, "/roms/gba", motion).kind,
        )
        // The content column is unmounted (search takes the slot) and re-mounted (search closes). The
        // motion object has not changed; it has already been played.
        history.frame(FileBrowserScreen.SEARCH, "/roms/gba", motion)
        val remount = history.frame(FileBrowserScreen.BROWSE, "/roms/gba", motion)
        assertEquals(
            "returning from search must be a SCREEN arrival, never a replay of whatever navigation " +
                "happened to precede it",
            FileBrowserArrivalKind.SCREEN,
            remount.kind,
        )
        assertEquals("and a screen arrival carries no direction", 0, remount.direction)
    }

    @Test
    fun `entering the same child twice arms twice`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        history.frame(FileBrowserScreen.BROWSE, "/roms/gba", enter("/roms/gba", 1L))
        history.frame(FileBrowserScreen.BROWSE, "/roms", up("/roms", 2L))

        assertEquals(
            "down, up, down must animate both descents -- armNavigationGlide bumps on every call for " +
                "exactly this reason, and consume-once must not undo it",
            FileBrowserArrivalKind.DIRECTORY,
            history.frame(FileBrowserScreen.BROWSE, "/roms/gba", enter("/roms/gba", 3L)).kind,
        )
    }

    @Test
    fun `a motion for a directory that is not on screen is not an arrival`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        assertEquals(
            "fileBrowserNavigationGlideApplies is still the predicate -- a motion whose path is not the " +
                "one being shown describes an arrival that has not happened",
            FileBrowserArrivalKind.NONE,
            history.frame(FileBrowserScreen.BROWSE, "/roms", enter("/roms/snes", 4L)).kind,
        )
    }

    /**
     * **An abandoned composition must not consume an arrival.** Compose may run a `remember`
     * calculation and then throw the composition away; `MimirTransitionEpochLedger`'s KDoc names this
     * exactly — *"a prepared frame may be abandoned; only commit changes the presented target."* If the
     * recording lived in the calculation, the motion would be marked played for a frame that never
     * reached the screen and its animation would never run: a folder entered with no glide at all.
     */
    @Test
    fun `preparing without committing changes nothing`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        val motion = enter("/roms/gba", 1L)

        // Three abandoned attempts.
        repeat(3) {
            assertEquals(
                "each abandoned frame must still describe the same arrival",
                FileBrowserArrivalKind.DIRECTORY,
                history.prepare(FileBrowserScreen.BROWSE, "/roms/gba", motion).kind,
            )
        }
        // ...and the one that lands still plays.
        val landed = history.frame(FileBrowserScreen.BROWSE, "/roms/gba", motion)
        assertEquals(FileBrowserArrivalKind.DIRECTORY, landed.kind)
        assertEquals(FileBrowserGlideEnter, landed.direction)
    }

    /** A `SideEffect` runs on every successful recomposition, so the same frame is committed many times
     * over its life. Doing so must not consume the next arrival or fabricate one. */
    @Test
    fun `committing the same frame repeatedly is idempotent`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        val motion = enter("/roms/gba", 1L)
        val arrival = history.prepare(FileBrowserScreen.BROWSE, "/roms/gba", motion)
        repeat(5) { history.commit(arrival, FileBrowserScreen.BROWSE, motion) }

        assertEquals(
            "the motion is played, once, however many times its frame was committed",
            FileBrowserArrivalKind.NONE,
            history.frame(FileBrowserScreen.BROWSE, "/roms/gba", motion).kind,
        )
        assertEquals(
            "and the next real change is still seen",
            FileBrowserArrivalKind.SCREEN,
            history.frame(FileBrowserScreen.SEARCH, "/roms/gba", motion).kind,
        )
    }

    /**
     * A motion is marked played only when its arrival is the one being shown.
     *
     * `armNavigationGlide` runs immediately before `load` inside one synchronous dispatch, so a frame
     * carrying a motion for a directory that is not on screen yet should not be reachable — the
     * controller's own comment says the arming order exists to guarantee it. **Pinned anyway**, because
     * the alternative is a glide silently consumed before the listing it describes ever appears, and this
     * is the assertion that would name it rather than leaving a folder entering with no motion at all.
     */
    @Test
    fun `a motion is not consumed by a frame that is not yet showing its directory`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        val motion = enter("/roms/gba", 1L)

        assertEquals(
            "the listing has not caught up: nothing arrived",
            FileBrowserArrivalKind.NONE,
            history.frame(FileBrowserScreen.BROWSE, "/roms", motion).kind,
        )
        assertEquals(
            "and when it does catch up, the glide is still there to play",
            FileBrowserArrivalKind.DIRECTORY,
            history.frame(FileBrowserScreen.BROWSE, "/roms/gba", motion).kind,
        )
    }

    /** A controller re-mounted mid-session can present a motion this surface never witnessed. It belongs
     * to a session that is over, and a first frame must never glide. */
    @Test
    fun `a motion inherited by a first frame is never played`() {
        val history = FileBrowserArrivalHistory()
        val stale = enter("/roms/gba", 9L)
        assertEquals(
            FileBrowserArrivalKind.NONE,
            history.frame(FileBrowserScreen.BROWSE, "/roms/gba", stale).kind,
        )
        assertEquals(
            "and it must not surface one frame later either",
            FileBrowserArrivalKind.NONE,
            history.frame(FileBrowserScreen.BROWSE, "/roms/gba", stale).kind,
        )
    }

    // ─────────────────────────────────────────────────────────────────── every screen path ──

    @Test
    fun `every screen change in both directions is an arrival`() {
        val pairs = listOf(
            FileBrowserScreen.BROWSE to FileBrowserScreen.SEARCH,
            FileBrowserScreen.SEARCH to FileBrowserScreen.BROWSE,
            FileBrowserScreen.BROWSE to FileBrowserScreen.QUEUE,
            FileBrowserScreen.QUEUE to FileBrowserScreen.BROWSE,
            // The two LATERALS. Reachable, not hypothetical: the header stays composed above both
            // screens, so the magnifier is tappable from the queue and the QUEUE plate from search --
            // which is why `openSearch` closes the queue and the surface's `onQueue` closes search.
            FileBrowserScreen.SEARCH to FileBrowserScreen.QUEUE,
            FileBrowserScreen.QUEUE to FileBrowserScreen.SEARCH,
        )
        pairs.forEach { (from, to) ->
            val history = FileBrowserArrivalHistory()
            history.frame(FileBrowserScreen.BROWSE, "/roms", null)
            if (from != FileBrowserScreen.BROWSE) history.frame(from, "/roms", null)
            assertEquals(
                "$from -> $to must land gracefully; a path nobody listed is the defect V-1 exists to close",
                FileBrowserArrivalKind.SCREEN,
                history.frame(to, "/roms", null).kind,
            )
        }
    }

    @Test
    fun `staying on one screen is not an arrival`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        history.frame(FileBrowserScreen.SEARCH, "/roms", null)
        repeat(5) {
            assertEquals(
                "typing, filtering and result batches all recompose the search screen -- none of them is " +
                    "an arrival, and re-fading the page on each would be a strobe",
                FileBrowserArrivalKind.NONE,
                history.frame(FileBrowserScreen.SEARCH, "/roms", null).kind,
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────── the precedence ──

    /**
     * **The collision, reached by an ordinary press.** `revealPath` — CONFIRM on a search result — calls
     * `closeSearch()`, `armNavigationGlide(parent, Enter)` and `load(parent)` inside one synchronous
     * dispatch, so the next composition sees a screen change AND a fresh motion. Without a stated
     * precedence the surface plays both: an outer page fading from 0 while the listing inside it
     * translates 22 dp — two animations describing one event.
     */
    @Test
    fun `a reveal is one arrival, and it is the directory's`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        history.frame(FileBrowserScreen.SEARCH, "/roms", null)

        val revealed = history.frame(
            FileBrowserScreen.BROWSE,
            "/roms/gba",
            FileBrowserNavigationMotion("/roms/gba", FileBrowserGlideEnter, 1L, reveal = true),
        )
        assertEquals(
            "the player pressed something and WENT somewhere; the signed travel is what says which way",
            FileBrowserArrivalKind.DIRECTORY,
            revealed.kind,
        )
        assertEquals(FileBrowserGlideEnter, revealed.direction)
        assertTrue("and the arrival says so, for the row settle", revealed.reveal)
    }

    @Test
    fun `abandoning a destination pick from another directory is one arrival too`() {
        // abandonDestinationPick: arms a RETURN for the origin, loads it, then re-opens the chooser --
        // which renders nothing, so the screen does not change. Same shape as the reveal when the pick
        // had wandered; the directory still wins.
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        history.frame(FileBrowserScreen.BROWSE, "/roms/gba", enter("/roms/gba", 1L))

        val abandoned = history.frame(FileBrowserScreen.BROWSE, "/roms", up("/roms", 2L))
        assertEquals(FileBrowserArrivalKind.DIRECTORY, abandoned.kind)
        assertEquals(FileBrowserGlideReturn, abandoned.direction)
    }

    @Test
    fun `an arrival's epoch bumps once per arrival and never for a still frame`() {
        val history = FileBrowserArrivalHistory()
        history.frame(FileBrowserScreen.BROWSE, "/roms", null)
        val first = history.frame(FileBrowserScreen.BROWSE, "/roms/gba", enter("/roms/gba", 1L))
        val still = history.frame(FileBrowserScreen.BROWSE, "/roms/gba", enter("/roms/gba", 1L))
        val second = history.frame(FileBrowserScreen.SEARCH, "/roms/gba", enter("/roms/gba", 1L))

        assertNotEquals(
            "two arrivals must be two remember() identities, or the second animation is never armed",
            first,
            second,
        )
        assertEquals("an idle frame is the shared resting value", FileBrowserNoArrival, still)
        assertEquals(
            "and consecutive idle frames compare equal, so nothing keyed on an arrival is re-armed by a " +
                "recomposition that changed nothing",
            FileBrowserNoArrival,
            history.frame(FileBrowserScreen.SEARCH, "/roms/gba", enter("/roms/gba", 1L)),
        )
    }

    // ──────────────────────────────────────────────────────────────── how each arrival moves ──

    @Test
    fun `a directory arrival travels, signed, at the reference's own numbers`() {
        val entering = FileBrowserArrival(FileBrowserArrivalKind.DIRECTORY, FileBrowserGlideEnter, 1L)
        val returning = FileBrowserArrival(FileBrowserArrivalKind.DIRECTORY, FileBrowserGlideReturn, 2L)

        assertEquals(FileBrowserGlideTravelDp, fileBrowserArrivalTravelDp(entering), 0f)
        assertEquals(FileBrowserGlideStartAlpha, fileBrowserArrivalStartAlpha(entering), 0f)
        // Batch F: going up is a page return; the CHILD departs (fileBrowserArrivalDeparts). The parent stands at rest under it:
        // no travel and no dip (Thor, 2026-10-15), so the pane is never less than one whole page.
        assertEquals("the parent does not travel", 0f, fileBrowserArrivalTravelDp(returning), 0f)
        assertEquals("the parent is opaque from its first frame, under the clearing child", 1f, fileBrowserArrivalStartAlpha(returning), 0f)
        assertTrue(fileBrowserArrivalDeparts(returning))
        assertFalse(fileBrowserArrivalDeparts(entering))
        assertEquals(
            "PR #138's numbers, unchanged by V-1 -- this geometry was already observed on device",
            22f,
            FileBrowserGlideTravelDp,
            0f,
        )
        assertEquals(0.88f, FileBrowserGlideStartAlpha, 0f)
    }

    @Test
    fun `a screen SWAP dips, because there is nothing underneath it to fade up over`() {
        val screen = FileBrowserArrival(FileBrowserArrivalKind.SCREEN, epoch = 1L)
        assertEquals("still no travel -- a moving entrance can clip a ring", 0f, fileBrowserArrivalTravelDp(screen), 0f)
        assertEquals(
            "**this used to be 0f and caused a transition snap.** The value " +
                "was borrowed from MimirAppHost.mimirIncomingLayerStartAlpha, whose own KDoc names the " +
                "condition it assumes -- an incoming scene that HAS AN OUTGOING ONE TO REPLACE. " +
                "FileBrowserSurface's if/else chain does not meet it: it swaps composables directly, so " +
                "the outgoing screen leaves composition in one frame and an alpha-0 start leaves the " +
                "slot LITERALLY EMPTY for two frames. The screen materialised out of nothing because " +
                "there was nothing underneath for it to materialise over.",
            FileBrowserGlideStartAlpha,
            fileBrowserArrivalStartAlpha(screen),
            0f,
        )
    }

    @Test
    fun `the search keyboard has no arrival of its own, because it is a sheet`() {
        assertEquals(
            "the one SCREEN_OVER arrival, the keyboard's, is on " +
                "MimirSheetHost now, so the kind went with it and this model describes the browser's own pages",
            listOf(FileBrowserArrivalKind.NONE, FileBrowserArrivalKind.DIRECTORY, FileBrowserArrivalKind.SCREEN),
            FileBrowserArrivalKind.entries,
        )
        val source = surfaceSource()
        assertTrue(
            "the keyboard's visibility is the controller's own flag, on the shared sheet host, at the bottom edge",
            source.contains("visible = controller.searchKeyboardOpen,") &&
                source.contains("MimirSheetHost(") &&
                source.contains(".align(Alignment.BottomCenter)"),
        )
        assertFalse(
            "and it no longer plays a page arrival over the top of the sheet's own transition -- two clocks on one keyboard",
            source.contains("FileBrowserKeyboardArrival"),
        )
    }

    @Test
    fun `no arrival kind is left sharing another's start alpha by accident`() {
        val alphas = FileBrowserArrivalKind.entries.associateWith { kind ->
            fileBrowserArrivalStartAlpha(FileBrowserArrival(kind, epoch = 1L))
        }
        assertEquals(
            "one row per kind -- if this map and the enum ever disagree, a kind is being decided by an " +
                "`else` somewhere, which is exactly how SCREEN and the keyboard came to share a value " +
                "that had only ever been measured for one of them (the keyboard has since left for the sheet tier)",
            mapOf(
                FileBrowserArrivalKind.NONE to 1f,
                FileBrowserArrivalKind.DIRECTORY to FileBrowserGlideStartAlpha,
                FileBrowserArrivalKind.SCREEN to FileBrowserGlideStartAlpha,
            ),
            alphas,
        )
    }

    @Test
    fun `an idle arrival is literally an identity layer`() {
        listOf(FileBrowserNoArrival, null).forEach { idle ->
            assertEquals("no travel", 0f, fileBrowserArrivalTravelDp(idle), 0f)
            assertEquals("full alpha", 1f, fileBrowserArrivalStartAlpha(idle), 0f)
            assertFalse("and no animation is started at all", fileBrowserArrivalAnimates(idle))
        }
        assertTrue(fileBrowserArrivalAnimates(FileBrowserArrival(FileBrowserArrivalKind.SCREEN, epoch = 1L)))
        assertTrue(
            fileBrowserArrivalAnimates(
                FileBrowserArrival(FileBrowserArrivalKind.DIRECTORY, FileBrowserGlideEnter, 1L),
            ),
        )
    }

    /**
     * **The ring-safety invariant, stated as an assertion rather than as a comment.**
     *
     * PR #139's rule — repeated device reports — is that the focus ring must not paint before its geometry has
     * settled. The browse folio honours it with a gate ([fileBrowserRingEligible]); the search and queue
     * screens cannot be gated from this lane, because each feeds ONE `focusedKey` to both its settlement
     * scroll and its ring, so narrowing it would trade a clipped ring for an off-screen landing. An
     * arrival that never moves geometry needs no gate.
     *
     * This is the test that fails if someone later makes a screen swap slide without first splitting
     * those two parameters.
     */
    @Test
    fun `only the arrival this lane can ring-gate is allowed to move geometry`() {
        FileBrowserArrivalKind.entries.forEach { kind ->
            val arrival = FileBrowserArrival(kind, if (kind == FileBrowserArrivalKind.DIRECTORY) 1 else 0, 1L)
            if (fileBrowserArrivalTravelDp(arrival) != 0f) {
                assertEquals(
                    "$kind travels, so its ring must be gateable -- and DIRECTORY is the only arrival " +
                        "whose ring this surface owns the call site for. Making a screen swap travel " +
                        "reproduces the clipped ring repeatedly observed on device, on a new screen.",
                    FileBrowserArrivalKind.DIRECTORY,
                    kind,
                )
            }
        }
    }

    // ───────────────────────────────────────────────── the whole map, walked end to end ──

    /**
     * Every open path and every return path the map enumerates, driven in sequence exactly as the surface
     * drives it. This is the test that catches a WIRING regression rather than a predicate one — a screen
     * added to the enum but not to the render, or a motion consumed in the wrong place.
     */
    @Test
    fun `open, enter, search, queue, reveal, return -- one arrival each, none missed, none doubled`() {
        val history = FileBrowserArrivalHistory()
        fun frame(screen: FileBrowserScreen, path: String, motion: FileBrowserNavigationMotion?) =
            history.frame(screen, path, motion)

        // O-1 mount. open() nulls the motion; nothing arrived.
        assertEquals(FileBrowserArrivalKind.NONE, frame(FileBrowserScreen.BROWSE, "/storage", null).kind)

        // O-2 enter a folder.
        val entered = enter("/storage/Roms", 1L)
        assertEquals(
            FileBrowserArrivalKind.DIRECTORY,
            frame(FileBrowserScreen.BROWSE, "/storage/Roms", entered).kind,
        )

        // O-3 open search (Y, or the header magnifier).
        assertEquals(
            FileBrowserArrivalKind.SCREEN,
            frame(FileBrowserScreen.SEARCH, "/storage/Roms", entered).kind,
        )
        // O-4 the keyboard is a separate mount; its arrival is a constant, asserted above.

        // R-2 close search with B -- popScope restores the tile the player left.
        assertEquals(
            FileBrowserArrivalKind.SCREEN,
            frame(FileBrowserScreen.BROWSE, "/storage/Roms", entered).kind,
        )

        // O-5 open the queue (START, or the header plate).
        assertEquals(
            FileBrowserArrivalKind.SCREEN,
            frame(FileBrowserScreen.QUEUE, "/storage/Roms", entered).kind,
        )
        // R-4 close it again.
        assertEquals(
            FileBrowserArrivalKind.SCREEN,
            frame(FileBrowserScreen.BROWSE, "/storage/Roms", entered).kind,
        )

        // O-9 search, then CONFIRM a result: closeSearch + a fresh ENTER motion on one frame.
        assertEquals(
            FileBrowserArrivalKind.SCREEN,
            frame(FileBrowserScreen.SEARCH, "/storage/Roms", entered).kind,
        )
        val revealed = enter("/storage/Roms/gba", 2L)
        val reveal = frame(FileBrowserScreen.BROWSE, "/storage/Roms/gba", revealed)
        assertEquals("one event, one animation", FileBrowserArrivalKind.DIRECTORY, reveal.kind)

        // R-10 a post-extraction refresh: same path, same motion, new listing.
        assertEquals(
            FileBrowserArrivalKind.NONE,
            frame(FileBrowserScreen.BROWSE, "/storage/Roms/gba", revealed).kind,
        )

        // R-1 back out.
        val backedOut = up("/storage/Roms", 3L)
        val back = frame(FileBrowserScreen.BROWSE, "/storage/Roms", backedOut)
        assertEquals(FileBrowserArrivalKind.DIRECTORY, back.kind)
        assertEquals(FileBrowserGlideReturn, back.direction)
    }

    // ───────────────────────────────────────────────────────── the call sites (source pins) ──

    /**
     * **Source pins, and weak ones — said out loud.** They prove the surface names these functions at
     * these call sites. They prove nothing about what the player sees. `FileBrowserSupportDimRenderTest`
     * and `FileBrowserVerifyHonestyTest` disclose the same limit for the same reason: no Compose harness
     * exists in this module.
     *
     * They exist because the earlier change nearly shipped its whole feature dead: the rules were pinned and the
     * wiring was not, and three unpinned lines could be deleted with the suite still green.
     */
    @Test
    fun `the surface computes one arrival, at surface level, remembered on the controller`() {
        val source = surfaceSource()
        assertTrue(
            "the history must be remembered on the CONTROLLER, not inside FileBrowserContent -- that " +
                "column is unmounted on every screen swap and would forget which motions it had played. " +
                "This is the line that makes consume-once work at all.",
            source.contains("remember(controller) { FileBrowserArrivalHistory() }"),
        )
        assertTrue(
            "and consumed once per (screen, path, motion) change",
            source.contains("remember(screen, controller.currentPath, controller.navigationMotion)") &&
                source.contains("arrivals.prepare(screen, controller.currentPath, controller.navigationMotion)"),
        )
        assertTrue(
            "prepared in remember, COMMITTED in SideEffect -- MimirTransitionEpochLedger's split. A " +
                "recording made inside a remember block that Compose then abandons marks an arrival " +
                "played for a frame that never reached the screen, and its animation never runs.",
            source.contains("SideEffect { arrivals.commit(arrival, screen, controller.navigationMotion) }"),
        )
        assertTrue(
            "the screen is derived from the controller's own two flags, plus the five R6-F-05 added",
            source.contains("queueOpen = controller.queueOpen,") &&
                source.contains("searchOpen = controller.searchOpen,") &&
                source.contains("overlayOpen = overlayOpen,"),
        )
    }

    @Test
    fun `each call site plays only its own half of the arrival`() {
        val source = surfaceSource()
        assertTrue(
            "the page slot plays the SCREEN half -- folio and inspector together, header and footer left " +
                "standing. R6-F-05 review finding 1: EXCEPT while `screen == OVERLAY`, where each of the " +
                "five screens plays its own mount-owned SCREEN constant instead -- without this the page " +
                "layer's dip stacked on top of that one, two graphicsLayer alphas over the same pixels",
            source.contains(
                "arrival.takeIf { it.kind == FileBrowserArrivalKind.SCREEN && screen != FileBrowserScreen.OVERLAY }",
            ),
        )
        assertTrue(
            "and the listing column plays the DIRECTORY half",
            source.contains("val directoryArrival = arrival.takeIf { it.kind == FileBrowserArrivalKind.DIRECTORY }") &&
                source.contains("arrival = directoryArrival"),
        )
        assertFalse(
            "the keyboard plays none of these: it is a sheet (F4), on its own clock",
            source.contains(".fileBrowserArrivalGlide(FileBrowserKeyboardArrival)"),
        )
        assertFalse(
            "the old single-purpose applicator must be gone, not merely unused: two curves is exactly " +
                "what V-1 says not to author",
            source.contains("fun Modifier.fileBrowserNavigationGlide("),
        )
    }

    @Test
    fun `the ring gate is armed from the arrival, not from the sticky predicate`() {
        val source = surfaceSource()
        assertTrue(
            "glideSettled must start from the ARRIVAL. Initialising it from " +
                "fileBrowserNavigationGlideApplies is what gated the ring, on every re-mount, for a " +
                "glide that was never started.",
            source.contains("val glideSettled = remember(arrival) {") &&
                source.contains("mutableStateOf(directoryArrival == null)"),
        )
        assertTrue(
            "and the gate itself is still fileBrowserRingEligible -- untouched, another file's",
            source.contains("fileBrowserRingEligible("),
        )
        assertTrue(
            "the ring still narrows the KEY only, never the one the settlement scroll reads",
            source.contains("val ringKey = focusedKey.takeIf { ringEligible }"),
        )
    }

    @Test
    fun `every arrival that animates is seated before it paints -- a screen return included`() {
        // R6-P2-04,  Closing search re-mounts the listing at the top with a
        // fresh reveal history, so the seek lands one frame late; the gate hiding that frame was keyed to a
        // DIRECTORY arrival only.
        val source = surfaceSource()
        assertFalse(
            "the seat gate must not be decided by 'is it a directory' -- a SCREEN arrival back onto browse " +
                "paints one frame at the top before the late, unanimated seek snaps it into place",
            source.contains("val seatPending = remember(arrival) { mutableStateOf(directoryArrival != null) }"),
        )
        assertTrue(
            "it is decided by the predicate the glide already keys on: an arrival that animates is seated first",
            source.contains("val seatPending = remember(arrival) { mutableStateOf(fileBrowserArrivalAnimates(arrival)) }"),
        )
        assertTrue(
            "and that predicate already says yes to a screen swap",
            fileBrowserArrivalAnimates(FileBrowserArrival(FileBrowserArrivalKind.SCREEN, epoch = 1L)),
        )
    }

    @Test
    fun `travelPending survives a chooser or overlay round trip -- it is the caller's, not FileBrowserContent's own`() {
        // R6-P2-02, coordinator review 2026-09-15 (finding 1). A flag scoped to FileBrowserContent's
        // own remember is discarded and rebuilt every time a chooser or the action overlay takes and
        // releases its slot (fileBrowserScreenOf cannot see those five screens, spec §5/R6-F-05, so no
        // new arrival excuses the restart) -- which would replay a full-page travel, not merely a
        // glide, on every such close. Held one level up instead, keyed on the identical `arrival`.
        val source = surfaceSource()
        val travelPendingLine =
            "val travelPending = remember(arrival) { mutableStateOf(fileBrowserArrivalTravels(arrival)) }"
        assertTrue(
            "the caller (above the if/else chain, beside `arrival` itself) must hold it",
            source.substringBefore("private fun FileBrowserContent(").contains(travelPendingLine),
        )
        assertTrue(
            "and FileBrowserContent must receive it as a PARAMETER, not recompute its own",
            source.contains("travelPending: MutableState<Boolean>,"),
        )
        assertFalse(
            "FileBrowserContent's own body must not shadow the parameter with a second remember of it",
            source.substringAfter("private fun FileBrowserContent(").contains(travelPendingLine),
        )
    }

    @Test
    fun `a travelling reveal seeks to a defined origin before the layout it will measure is read`() {
        // R6-P2-02, coordinator review 2026-09-15 (finding 2). On a cache MISS this column mounts on
        // the OUTGOING directory first (a SCREEN arrival, seated on the tile the player left) and
        // `listState` is never re-created before the DIRECTORY-reveal arrival that follows once the
        // target directory's partition lands -- so without this the travel starts from wherever the
        // outgoing directory happened to sit, not from a place this effect chose.
        val source = surfaceSource()
        val chain = source.substringAfter("val restoring = revealHistory.consume(partition)")
            .substringBefore("val layout = listState.layoutInfo")
        assertTrue(
            "the origin must be pinned to a defined point -- the top -- before layoutInfo is read for " +
                "the AnimateTo/ScrollBy decision, or that decision is made against wherever the " +
                "outgoing listing was left",
            chain.contains("if (travelPending.value) listState.scrollToItem(0, 0)"),
        )
    }

    /** One composed frame that reached the screen: prepare, then commit — the pairing
     * [FileBrowserSurface] performs with `remember` + `SideEffect`. */
    private fun FileBrowserArrivalHistory.frame(
        screen: FileBrowserScreen,
        currentPath: String,
        motion: FileBrowserNavigationMotion?,
    ): FileBrowserArrival {
        val arrival = prepare(screen, currentPath, motion)
        commit(arrival, screen, motion)
        return arrival
    }

    private fun surfaceSource(): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("FileBrowserSurface.kt not found from ${File(".").absolutePath}")
    }
}
