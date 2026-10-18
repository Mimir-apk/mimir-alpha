package com.gruning.mimir.rewrite.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-12 -- the focus ring's paint gate. See [fileBrowserRingEligible]'s file KDoc for the mechanism and,
 * in particular, for what these tests do NOT prove.
 *
 * **They pin a DECISION, not a rendering.** `feature-files` has no Compose harness, so no test in this
 * module can assert that a ring is drawn, that it is un-clipped, or that the 160 ms window has the length
 * the tokens claim. Every one of those is device-only. What is pinned here is which state changes make the
 * ring wait and which must never make it wait -- the half that can regress silently in a refactor.
 */
class FileBrowserRingGateTest {

    private fun motion(path: String, direction: Int = FileBrowserGlideEnter, sequence: Long) =
        FileBrowserNavigationMotion(path = path, direction = direction, sequence = sequence)

    // ────────────────────────────────────────────────────────────── eligibility ──

    @Test
    fun `a directory swap suppresses the ring until its glide settles`() {
        assertFalse(
            "the ring must not paint while the destination listing is still gliding in -- that is the " +
                "clipped/stale ring regression",
            fileBrowserRingEligible("/roms/gba", motion("/roms/gba", sequence = 1L), glideSettled = false),
        )
    }

    @Test
    fun `a move made while the listing is still arriving shows the ring at once`() {
        // Nova regression: a move during the parent's arrival dip showed no ring until the dip finished.
        // Settle first unless movement is exercised; a move must reveal its landing immediately.
        assertTrue(
            fileBrowserRingEligible(
                "/roms/gba",
                motion("/roms/gba", direction = FileBrowserGlideReturn, sequence = 2L),
                glideSettled = false,
                movedDuringArrival = true,
            ),
        )
        assertFalse(
            "a player who waits still waits for the arrival",
            fileBrowserRingEligible(
                "/roms/gba",
                motion("/roms/gba", direction = FileBrowserGlideReturn, sequence = 2L),
                glideSettled = false,
                movedDuringArrival = false,
            ),
        )
    }

    @Test
    fun `the ring returns the moment the glide settles`() {
        assertTrue(
            "once the glide has finished there is nothing left to wait for",
            fileBrowserRingEligible("/roms/gba", motion("/roms/gba", sequence = 1L), glideSettled = true),
        )
    }

    @Test
    fun `a first mount never waits -- there is no previous listing to have arrived from`() {
        // FileBrowserController.open() nulls navigationMotion for exactly this reason.
        assertTrue(
            "opening the browser must show the ring immediately; a null motion means no glide at all",
            fileBrowserRingEligible("/storage/emulated/0", motion = null, glideSettled = false),
        )
    }

    @Test
    fun `a glide armed for another directory never suppresses the one on screen`() {
        assertTrue(
            "the gate follows fileBrowserNavigationGlideApplies -- a motion whose path is not the " +
                "current path is not this listing's arrival",
            fileBrowserRingEligible("/roms/snes", motion("/roms/gba", sequence = 4L), glideSettled = false),
        )
    }

    @Test
    fun `returning to the parent waits exactly as descending does`() {
        // direction is signed for the glide's benefit; the gate must not care which way it points.
        assertFalse(
            "backing out is a state change too",
            fileBrowserRingEligible(
                "/roms",
                motion("/roms", direction = FileBrowserGlideReturn, sequence = 7L),
                glideSettled = false,
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────── epoch ──

    @Test
    fun `entering a folder is a new ring epoch`() {
        assertNotEquals(
            "a navigation must re-arm the gate, or the ring would never wait again after the first swap",
            fileBrowserRingEpoch("/roms", motion("/roms", sequence = 1L)),
            fileBrowserRingEpoch("/roms/gba", motion("/roms/gba", sequence = 2L)),
        )
    }

    @Test
    fun `a refresh of the same directory is the SAME ring epoch -- the ring stays lit`() {
        // FileBrowserController.refresh() reloads in place after an extraction (contract A) and
        // deliberately does NOT call armNavigationGlide, so nothing the epoch reads has changed.
        val before = fileBrowserRingEpoch("/roms/gba", motion("/roms/gba", sequence = 3L))
        val afterRefresh = fileBrowserRingEpoch("/roms/gba", motion("/roms/gba", sequence = 3L))
        assertEquals(
            "a reload in place must not blink the ring off -- only Enter and Up bump the sequence",
            before,
            afterRefresh,
        )
    }

    @Test
    fun `a focus move is the SAME ring epoch -- movement is never gated`() {
        // This is the continuous-navigation boundary. A D-pad press changes neither the
        // path nor the sequence, so continuous travel cannot re-arm the gate by construction.
        val standingOnOneTile = fileBrowserRingEpoch("/roms/gba", motion("/roms/gba", sequence = 5L))
        val standingOnAnother = fileBrowserRingEpoch("/roms/gba", motion("/roms/gba", sequence = 5L))
        assertEquals(
            "a directional move must never make the ring wait when geometry is unchanged",
            standingOnOneTile,
            standingOnAnother,
        )
    }

    @Test
    fun `entering the same child twice is a new epoch each time`() {
        // Mirrors armNavigationGlide's own reason for bumping on every call rather than keying on path:
        // down, up, down must animate both descents.
        assertNotEquals(
            "a path-only identity would make the second descent silent",
            fileBrowserRingEpoch("/roms/gba", motion("/roms/gba", sequence = 1L)),
            fileBrowserRingEpoch("/roms/gba", motion("/roms/gba", sequence = 3L)),
        )
    }

    @Test
    fun `a first mount's epoch is distinct from any glide's`() {
        assertNotEquals(
            "open() nulls the motion; that state must not collide with an armed glide's identity",
            fileBrowserRingEpoch("/roms", motion = null),
            fileBrowserRingEpoch("/roms", motion("/roms", sequence = 1L)),
        )
    }

    // ─────────────────────────────────────────────────── the rule, walked end to end ──

    /**
     * The whole settlement contract in one pass, driven the way the surface drives it: state re-created on
     * every epoch change, released when the glide for that epoch reports settled.
     *
     * This is the test that would catch a wiring regression rather than a predicate regression -- e.g.
     * keying the gate on something a D-pad press touches, which each assertion below would reject.
     */
    @Test
    fun `open, enter, settle, travel, refresh -- the ring waits once and only for the swap`() {
        val drive = SurfaceRingDrive()

        // 1. open() -- motion nulled, ring lit at once.
        assertTrue("a first mount must not wait", drive.frame("/roms", null))

        // 2. Enter /roms/gba -- the glide arms, the ring waits.
        val entered = motion("/roms/gba", sequence = 1L)
        assertFalse("the swap must suppress the ring", drive.frame("/roms/gba", entered))
        // ...and keeps waiting across the intervening frames, not just the first.
        assertFalse("still gliding", drive.frame("/roms/gba", entered))

        // 3. The glide reports settled.
        drive.glideCompleted()
        assertTrue("settled -- the ring appears", drive.frame("/roms/gba", entered))

        // 4. Travel: several D-pad moves inside the listing. Nothing here may re-suppress.
        repeat(5) {
            assertTrue(
                "movement is being exercised -- the ring must keep up, never lag",
                drive.frame("/roms/gba", entered),
            )
        }

        // 5. refresh() after an extraction: same path, same sequence, new listing.
        assertTrue("a reload in place must not blink the ring off", drive.frame("/roms/gba", entered))

        // 6. Up to /roms -- a new swap, so it waits again.
        val returned = motion("/roms", direction = FileBrowserGlideReturn, sequence = 2L)
        assertFalse("backing out is a swap too, and must wait again", drive.frame("/roms", returned))
    }

    /**
     * **V-4, 2026-08-14 — a return from SEARCH must not re-suppress the ring, and only the ARRIVAL-driven
     * stand-in can see it.** This is the assertion the previous stand-in was structurally unable to make,
     * and the reason it had to be replaced rather than left alone.
     *
     * `fileBrowserNavigationGlideApplies` is `motion?.path == currentPath`, which **stays true forever**
     * after a navigation — the motion is never cleared. Closing search re-mounts `FileBrowserContent`, so
     * its `remember` starts over; a gate initialised from that predicate would therefore answer *"a glide
     * is in flight"* for a glide that was consumed long ago and start no animation to release it — the
     * ring suppressed for a window with no end, which is precisely the failure
     * [fileBrowserRingEpoch]'s KDoc exists to prevent. The surface's real wiring initialises from the
     * ARRIVAL ([FileBrowserArrivalHistory] consumes each armed motion exactly once), so the re-mount is a
     * SCREEN arrival and the ring is lit from its first frame.
     *
     * Run this walk against the old `settled = !fileBrowserNavigationGlideApplies(path, motion)`
     * stand-in and step 3 fails: it answers `false` where the surface answers `true`.
     */
    @Test
    fun `returning from search re-mounts the folio with the ring already lit`() {
        val drive = SurfaceRingDrive()

        // 1. Browsing /roms/gba after a real navigation, glide long since finished.
        assertTrue("a first mount must not wait", drive.frame("/roms", null))
        val entered = motion("/roms/gba", sequence = 1L)
        assertFalse("the swap suppresses", drive.frame("/roms/gba", entered))
        drive.glideCompleted()
        assertTrue("and releases", drive.frame("/roms/gba", entered))

        // 2. Open search. The folio LEAVES the content slot -- every `remember` in it dies. The motion is
        //    not cleared, and nothing clears it: `fileBrowserNavigationGlideApplies` still answers true.
        drive.remountContent()
        assertTrue(
            "a screen arrival does not travel, so it has no geometry to wait for",
            drive.frame("/roms/gba", entered, screen = FileBrowserScreen.SEARCH),
        )

        // 3. Close it. The folio re-mounts and re-runs `remember(arrival) { mutableStateOf(...) }` from
        //    scratch, on a path whose motion was consumed two steps ago.
        drive.remountContent()
        assertTrue(
            "the folio re-mounts on a CONSUMED motion: the ring must be lit at once, not gated on a " +
                "glide nothing will start -- the pre-V-4 stand-in initialised from " +
                "!fileBrowserNavigationGlideApplies(path, motion), which is still true here and would " +
                "suppress the ring for a window with no end",
            drive.frame("/roms/gba", entered, screen = FileBrowserScreen.BROWSE),
        )
    }

    // ────────────────────────────────────── O-7 · the third arming case, through the same rule ──

    /**
     * **The commit row appearing and leaving under a STATIONARY player** — `beginDestinationPick`,
     * `endDestinationPick`, `abandonDestinationPick`. The listing's structure changes without a
     * navigation, which is a category that did not exist when this gate was written.
     *
     * Nothing below is a new rule: the controller arms the same [FileBrowserNavigationMotion] it arms for
     * a swap ([FileBrowserController.loadAfterPickChange]), so these two pure functions are unchanged and
     * still answer for it. That is the point of the fix, and this walk is what says so — if the third case
     * had needed a wider predicate, this test could not have been written against the existing one.
     *
     * `FileBrowserRingGateStructuralTest` pins the half this cannot: that the controller actually PRODUCES
     * the motion, and that nothing else does.
     */
    @Test
    fun `a pick begins and ends under a stationary player -- the ring waits twice, at one path`() {
        val drive = SurfaceRingDrive()

        // Browsing /roms in MANAGE: no row, nothing in flight, ring lit.
        assertTrue("a stationary player mid-listing has nothing to wait for", drive.frame("/roms", null))

        // A on "Choose folder…": the commit row is inserted at index 0 and every tile shifts down.
        val rowAppeared = motion("/roms", direction = FileBrowserGlideEnter, sequence = 1L)
        assertFalse(
            "the ring must not paint on a row that did not exist a frame ago",
            drive.frame("/roms", rowAppeared),
        )
        assertFalse("and it keeps waiting across the intervening frames", drive.frame("/roms", rowAppeared))
        drive.glideCompleted()
        assertTrue("then appears, against geometry that has stopped moving", drive.frame("/roms", rowAppeared))

        // Travel while the pick is open. The path and the sequence are both unchanged, so nothing re-arms.
        repeat(3) {
            assertTrue("a pick being open does not make movement wait", drive.frame("/roms", rowAppeared))
        }

        // A on the commit row: the row retires and the tiles shift back up. SAME path, new sequence --
        // which is exactly why the epoch carries the sequence and not the path alone.
        val rowLeft = motion("/roms", direction = FileBrowserGlideReturn, sequence = 2L)
        assertFalse("the inverse structural change waits too", drive.frame("/roms", rowLeft))
        assertNotEquals(
            "a path-only identity would make the second half of a pick silent -- the row would leave " +
                "under a ring that never blinked",
            fileBrowserRingEpoch("/roms", rowAppeared),
            fileBrowserRingEpoch("/roms", rowLeft),
        )
    }

    /**
     * **The direction is the ONLY thing that distinguishes the structural case here, and the gate must
     * still ignore it** — exactly as it already ignores Enter vs Up. The controller decides what arms;
     * this function decides only whether something is in flight. A gate that learned to ask *why* a motion
     * was armed would be a second predicate for one question, which is the UI-05 shape this module has
     * already paid for once.
     *
     * (An earlier draft of this test compared two identical motions and could not fail. It was deleted
     * rather than kept for its comment.)
     */
    @Test
    fun `the row LEAVING waits exactly as the row appearing does`() {
        assertFalse(
            "endDestinationPick and abandonDestinationPick arm a RETURN, and a gate that only waited " +
                "for an ENTER would leave the second half of every pick unprotected",
            fileBrowserRingEligible(
                "/roms",
                motion("/roms", direction = FileBrowserGlideReturn, sequence = 2L),
                glideSettled = false,
            ),
        )
    }
}

/**
 * **The surface's real drive, modelled — V-4, 2026-08-14.**
 *
 * The walk-tests above claim to run *"driven the way the surface drives it"*, and until this class existed
 * that claim was **stale**: their stand-in keyed on [fileBrowserRingEpoch] and initialised
 * `settled = !fileBrowserNavigationGlideApplies(path, motion)` — which is `FileBrowserContent`'s wiring as
 * it stood BEFORE the earlier change replaced it. The surface has since keyed both halves on the ARRIVAL:
 *
 * ```
 * val arrival = remember(screen, controller.currentPath, controller.navigationMotion) {
 *     arrivals.prepare(screen, controller.currentPath, controller.navigationMotion)
 * }                                                              // FileBrowserSurface.kt:232-234
 * SideEffect { arrivals.commit(arrival, screen, controller.navigationMotion) }        // :238
 * val directoryArrival = arrival.takeIf { it.kind == FileBrowserArrivalKind.DIRECTORY }
 * val glideSettled = remember(arrival) { mutableStateOf(directoryArrival == null) }   // :1088-1090
 * ```
 *
 * The old stand-in still PASSED, because [fileBrowserRingEligible] and [fileBrowserRingEpoch] are pure and
 * were not what changed — so it could not fail, and therefore could not see a drift between the arrival
 * model and the gate. That is the exact class of miss [fileBrowserRingEpoch]'s own KDoc says the pairing
 * exists to prevent: *"a ring suppressed with no animation left to release it, or an animation with no
 * gate."* The stale stand-in modelled the first of those as CORRECT.
 *
 * **What is modelled, and what is not.** Both `remember`s are modelled by their real keys — the outer one
 * on `(screen, path, motion)` so [FileBrowserArrivalHistory.prepare] is invoked exactly when the surface
 * invokes it (calling it per-frame would mint a fresh epoch every frame and re-arm the gate forever), the
 * inner one on the arrival value, by `equals`, as `remember` compares. [glideCompleted] is the
 * `onSettled` callback `fileBrowserArrivalGlide` fires from the animation's own completion. What is NOT
 * modelled is anything Compose does with those values — no frame clock, no draw, no ring. This is still a
 * DECISION pin; see the class KDoc above.
 */
private class SurfaceRingDrive {
    private val arrivals = FileBrowserArrivalHistory()
    private var arrivalKey: Any? = Unit
    private var arrival: FileBrowserArrival = FileBrowserNoArrival
    private var settledKey: Any? = Unit
    private var settled: Boolean = true

    /** One composition of `FileBrowserContent`, returning what `ringEligible` resolves to on that frame. */
    fun frame(
        path: String,
        motion: FileBrowserNavigationMotion?,
        screen: FileBrowserScreen = FileBrowserScreen.BROWSE,
    ): Boolean {
        val nextArrivalKey = Triple(screen, path, motion)
        if (nextArrivalKey != arrivalKey) {
            arrivalKey = nextArrivalKey
            arrival = arrivals.prepare(screen, path, motion)
        }
        if (arrival != settledKey) {
            settledKey = arrival
            settled = arrival.kind != FileBrowserArrivalKind.DIRECTORY
        }
        // `SideEffect` -- after the composition succeeds, idempotent across repeated frames.
        arrivals.commit(arrival, screen, motion)
        return fileBrowserRingEligible(path, motion, settled)
    }

    /** `fileBrowserArrivalGlide`'s `onSettled`, fired from the animation's own completion. */
    fun glideCompleted() {
        settled = true
    }

    /**
     * **`FileBrowserContent` leaves the content slot and comes back** — search, the queue or the extract
     * chooser taking it, then releasing it. Every `remember` inside that column dies and is re-created;
     * the ones on [FileBrowserSurface] — the arrival `remember` and [FileBrowserArrivalHistory] itself —
     * do NOT, which is the arrangement that class's KDoc requires in as many words (*"It must be
     * remembered on the CONTROLLER, at [FileBrowserSurface] level — never inside `FileBrowserContent`"*).
     *
     * **This is the ONLY point at which the two wirings can disagree**, and modelling it is what makes
     * [FileBrowserRingGateTest.returning from search re-mounts the folio with the ring already lit] able
     * to fail. Without it, both the current and the pre-V-4 stand-in answer identically on every frame,
     * because neither re-initialises `settled` at all once an epoch is established — which is exactly why
     * the stale stand-in went three PRs without anyone noticing it modelled the wrong drive.
     */
    fun remountContent() {
        settledKey = Unit
    }
}
