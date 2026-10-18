package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.ExtractDestinationOptions
import com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Commit-row insertion/removal — the ring gate's THIRD arming case, driven through the real controller.**
 *
 * `FileBrowserRingGateTest` pins the gate's two pure functions against hand-built motions. This file pins
 * the half that could regress silently instead: that the controller **produces** a motion at all when the
 * pinned commit row appears or disappears under a stationary player, and — just as load-bearing — that it
 * produces none when nothing structural moved.
 *
 * ## The defect, and why the existing gate could not see it
 *
 * `FileBrowserController.beginDestinationPick` sets the pick, reloads, and lands focus on the commit row.
 * In [FileBrowserMode.MANAGE] that row **did not exist a line earlier**, so the reload inserts it at index
 * 0 and every tile behind it shifts down by a whole row. Before this change the reload armed nothing —
 * correctly, by the old rule: the player had not navigated — so [fileBrowserNavigationGlideApplies] was
 * false, [fileBrowserRingEligible] answered `true` on that very frame, and the ring painted at full
 * strength on geometry created that frame. `mimirFocusRing` animates through `animateFloatAsState`, which
 * starts *at* its target on a composition that is itself new, so there was not even a fade to hide it.
 *
 * `endDestinationPick` and `abandonDestinationPick` run the same change backwards.
 *
 * ## What a passing run here does and does not prove
 *
 * It drives a real [DefaultFocusAuthority], a real [FileBrowserController] and real [ControllerAction]
 * dispatches, so the arming, the focus landing and the registration are genuinely exercised. It composes
 * **nothing** — this module configures no Compose and no Robolectric harness. Nothing here is evidence
 * that the ring is un-clipped, that the 22 dp glide reads well for a row appearing, or that the
 * `MimirMotion.ModeTransitionMs` window has the length the tokens claim. **Those are device-only, and the
 * direction the glide travels is pending review.** What is pinned is the DECISION: which state changes make
 * the ring wait, and which must never make it wait.
 */
class FileBrowserRingGateStructuralTest {

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true),
            FileItem(name = "Deep", path = "/root/Deep", isDirectory = true),
            FileItem(name = "mystery.zip", path = "/root/mystery.zip", extension = "zip"),
        ),
        "/root/Roms" to listOf(
            FileItem(name = "GBA", path = "/root/Roms/GBA", isDirectory = true),
        ),
        "/root/Roms/GBA" to listOf(
            FileItem(name = "existing.gba", path = "/root/Roms/GBA/existing.gba", extension = "gba"),
        ),
        // A branch with an archive of its own, so a pick can BEGIN below the root -- the only arrangement
        // in which abandoning has a back-stack to restore and a real directory change to glide.
        "/root/Deep" to listOf(
            FileItem(name = "deep.zip", path = "/root/Deep/deep.zip", extension = "zip"),
            FileItem(name = "sibling.gba", path = "/root/Deep/sibling.gba", extension = "gba"),
        ),
    )

    /** Recognised as nothing at all, so its chooser always carries the CHOOSE_FOLDER row a pick needs. */
    private val unrecognised = "/root/mystery.zip"
    private val deepArchive = "/root/Deep/deep.zip"

    private val extracted = mutableListOf<Pair<String, String>>()

    private fun options(archivePath: String): ExtractDestinationOptions = ExtractDestinationPolicy.options(
        archivePath = archivePath,
        platformId = null,
        platformFolders = listOf("/root/Roms/GBA"),
    )

    private fun open(
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
        commitAllowed: (String) -> Boolean = { true },
    ): Pair<FileBrowserController, DefaultFocusAuthority> {
        val authority = DefaultFocusAuthority()
        val c = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = mode,
            listDirectory = { path -> tree[path].orEmpty() },
            onChoose = {},
            onDismiss = {},
            resolveExtractOptions = ::options,
            onExtractTo = { archive, destination -> extracted += archive to destination },
            commitAllowed = commitAllowed,
        )
        c.open()
        return c to authority
    }

    /**
     * SELECT on an archive tile (the overlay's EXTRACT row, in MANAGE) or X directly (PICK, where
     * [fileBrowserActionsAvailable] is false by construction and the overlay never opens -- this file's
     * own "beginning a pick in PICK mode" test exercises exactly that mode), then A on "Choose folder…" —
     * the only route into a destination pick.
     *
     * **F-1/R-13, 2026-08-18: via SELECT's action overlay in MANAGE, not a direct X** --
     * `FileBrowserExtractWiringTest.pressExtractOn`'s identical pattern and KDoc, including the same
     * scope-guarded fallback: requesting an unregistered overlay key would be a silent no-op (BUG#01's own
     * mechanism), and CONFIRMing straight into PICK's already-open chooser on the wrong row is worse than
     * a no-op -- it would extract HERE before this helper ever reaches CHOOSE_FOLDER.
     */
    private fun beginPick(c: FileBrowserController, authority: DefaultFocusAuthority, archive: String) {
        authority.requestFocus(FileBrowserFocus.tileKey(archive))
        c.handleInspectorSecondaryTap()
        if (authority.state.value.scope == c.actionOverlayScope) {
            authority.requestFocus(
                FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.EXTRACT),
            )
            c.dispatch(ControllerAction.CONFIRM)
        }
        authority.requestFocus(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )
        c.dispatch(ControllerAction.CONFIRM)
    }

    /**
     * The gate's answer on the FIRST frame after a state change, driven exactly as the surface drives it:
     * `glideSettled` is state re-created on the epoch and initialised from whether this arrival glides at
     * all, so it is already `true` for anything that does not animate.
     */
    private fun ringEligibleOnArrival(c: FileBrowserController): Boolean {
        val settled = !fileBrowserNavigationGlideApplies(c.currentPath, c.navigationMotion)
        return fileBrowserRingEligible(c.currentPath, c.navigationMotion, settled)
    }

    /**
     * The armed motion, or a NAMED failure. Deliberately not `!!`: every use below is downstream of the
     * one line this file exists to pin, and a differential that reverts that line must read as the reason
     * it mattered rather than as `NullPointerException`.
     */
    private fun armed(c: FileBrowserController, why: String): FileBrowserNavigationMotion {
        val motion = c.navigationMotion
        assertNotNull(why, motion)
        return motion!!
    }

    // ───────────────────────────────────────── 1 · the row appears under a stationary player ──

    @Test
    fun `beginning a pick in MANAGE arms the gate -- the ring waits for the row it just grew`() {
        val (c, authority) = open()
        assertNull("MANAGE has no commit row to begin with", c.commitPurpose)
        assertNull("and open() nulls the motion, so nothing is in flight", c.navigationMotion)

        beginPick(c, authority, unrecognised)

        assertTrue("the row now exists -- that is the structural change", c.commitAvailable)
        val motion = c.navigationMotion
        assertNotNull(
            "a row inserted at index 0 shifts every tile behind it; with no motion armed the gate would " +
                "answer eligible on that very frame and the ring would paint on geometry created this " +
                "frame -- O-7",
            motion,
        )
        assertEquals("armed for the directory on screen, so the glide's predicate matches", "/root", motion!!.path)
        assertEquals("the row appeared: the browser descended into a question", FileBrowserGlideEnter, motion.direction)
        assertFalse(
            "and therefore the RING WAITS on the arrival frame -- the whole point of the case",
            ringEligibleOnArrival(c),
        )
        c.close()
    }

    @Test
    fun `the commit row is REGISTERED before focus is asked for it -- BUG#01 cannot recur here`() {
        // `DefaultFocusAuthority.requestFocus` returns silently at `entries[key] ?: return`, so a landing
        // onto a key the graph has never heard of is a no-op with no signal. The assertion that focus IS
        // on the key proves it only by inference; this one proves the registration directly, which is the
        // property that makes the landing GUARANTEED rather than hoped for.
        //
        // **The directory must be one the player has BEEN in, and that is not decoration.** A differential
        // that moved the `requestFocus` ahead of the reload -- BUG#01's exact shape, asking for a key the
        // graph has not heard of yet -- came back GREEN on a first visit, because `load`'s own landing
        // falls to `keys.firstOrNull()`, which in a live pick IS the commit row. The explicit landing was
        // doing nothing there, so nothing could see it break. It only does work where `focusByDirectory`
        // already holds a tile: then `load` restores the ARCHIVE (right for a navigation, wrong for a
        // question the browser has just asked) and only the explicit request moves the ring to the row.
        // `refresh()` seeds that memory the same way an extraction's reload does.
        val (c, authority) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(unrecognised))
        c.refresh()

        beginPick(c, authority, unrecognised)

        val commitKey = FileBrowserFocus.commitKey("/root")
        assertTrue(
            "the reload registers the commit key BEFORE beginDestinationPick asks for it, in the same " +
                "synchronous call -- load() computes commitKeyForCurrentMode() after destinationPick is " +
                "set and hands it to tracker.update",
            commitKey in authority.registeredKeysInScope(c.scope),
        )
        assertEquals(
            "and the landing therefore LANDS, over load()'s own restore of the archive tile. Ask before " +
                "the registration and requestFocus returns silently at `entries[key] ?: return`, leaving " +
                "the ring on the archive while the browser asks 'extract where?'",
            commitKey,
            authority.state.value.focused,
        )
        assertEquals("in the browse scope; a pick is not a screen", c.scope, authority.state.value.scope)
        c.close()
    }

    // ────────────────────────────────────────────── 2 · the row leaves, the same way backwards ──

    @Test
    fun `committing the destination arms the gate too -- the tiles shift UP behind the ring`() {
        val (c, authority) = open()
        beginPick(c, authority, unrecognised)
        val armedByBegin = armed(c, "the pick must have armed before this test can say anything").sequence

        // A on the commit row: extract into the current directory and retire the row.
        authority.requestFocus(FileBrowserFocus.commitKey("/root"))
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals("the press did what it says", listOf(unrecognised to "/root"), extracted)
        assertFalse("and the row is gone in MANAGE", c.commitAvailable)
        val motion = c.navigationMotion
        assertNotNull("removing index 0 is the same structural change run backwards", motion)
        assertEquals("the row left: the browser backed out of the question", FileBrowserGlideReturn, motion!!.direction)
        assertTrue(
            "a NEW arrival, or the surface's remember() would never be re-created and the gate would " +
                "never re-arm",
            motion.sequence > armedByBegin,
        )
        assertFalse("the ring waits for the listing that just closed up", ringEligibleOnArrival(c))
        c.close()
    }

    @Test
    fun `abandoning at the origin arms the gate -- no directory changed, but the listing did`() {
        val (c, authority) = open()
        beginPick(c, authority, unrecognised)
        val armedByBegin = armed(c, "beginning the pick must arm -- see the MANAGE test above").sequence

        // B at the pick's own origin: unwind to the chooser without moving directory.
        assertEquals(FileBrowserDestinationPickPhase.SETTLED, c.destinationPickPhase())
        c.dispatch(ControllerAction.CANCEL)

        assertNull("the pick is abandoned", c.destinationPick)
        assertFalse("so the row is gone", c.commitAvailable)
        val motion = c.navigationMotion
        assertNotNull(
            "the old comment claimed abandoning at the origin left 'a listing that did not change'. In " +
                "MANAGE it changes structurally: the row leaves and every tile shifts up",
            motion,
        )
        assertEquals("/root", motion!!.path)
        assertEquals(
            "the row LEFT, so the browser backed out of the question it was asking",
            FileBrowserGlideReturn,
            motion.direction,
        )
        assertTrue("and it is a new arrival", motion.sequence > armedByBegin)
        assertFalse(ringEligibleOnArrival(c))
        c.close()
    }

    @Test
    fun `abandoning from ANOTHER directory is ONE arrival, not two`() {
        // The abandon that actually changes directory is a real navigation and already had its own RETURN
        // glide; the commit row leaving on the same press must not add a SECOND bump. Two arrivals for one
        // event is what `FileBrowserArrivalHistory`'s precedence rule exists to prevent, arriving here by
        // a different door -- and the surface would play the second one over a listing that had already
        // finished arriving.
        //
        // **Reaching it needs a search reveal**, for the reason `FileBrowserExtractWiringTest."abandoning
        // from a directory the pick never came from"` documents: CANCEL unwinds the player's own
        // navigation first and abandons at the origin, so no ordinary sequence of presses ends an abandon
        // anywhere but where the pick began. `revealPath` rebuilds the whole back-stack into another
        // branch, which is the one arrangement that does.
        val (c, authority) = open()
        c.setSearchAvailable(true)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        beginPick(c, authority, deepArchive)
        assertEquals("/root/Deep", c.destinationPick?.originPath)

        c.openSearch()
        val hit = FileItem(name = "existing.gba", path = "/root/Roms/GBA/existing.gba", extension = "gba")
        c.publishSearchResults(listOf(hit), FileBrowserSearchPhase.SETTLED)
        authority.requestFocus(FileBrowserSearchFocus.resultKey(hit.path))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("the reveal landed in the other branch", "/root/Roms/GBA", c.currentPath)
        c.dispatch(ControllerAction.CANCEL)
        c.dispatch(ControllerAction.CANCEL)
        assertTrue("at the root, so the next CANCEL abandons rather than going up", c.atRoot)
        val beforeAbandon = armed(c, "the reveal is a navigation and must have armed its own glide").sequence

        c.dispatch(ControllerAction.CANCEL)

        assertEquals("the abandon returns the player to where the pick began", "/root/Deep", c.currentPath)
        val motion = armed(c, "the abandon changes directory, so the navigation's own RETURN must be armed")
        assertEquals(
            "exactly ONE bump for the press -- the navigation's own RETURN, never a second structural " +
                "one on top of it. loadAfterPickChange's `path == currentPath` guard is what makes the " +
                "two branches mutually exclusive",
            beforeAbandon + 1L,
            motion.sequence,
        )
        assertEquals("/root/Deep", motion.path)
        assertEquals(FileBrowserGlideReturn, motion.direction)
        c.close()
    }

    // ──────────────────────────────────── 3 · what must NEVER arm ("unless movement is exercised") ──

    @Test
    fun `beginning a pick in PICK mode arms NOTHING -- the row was already there`() {
        // The row exists in PICK before and during; only commitPurpose changes, which is COPY, not
        // geometry. Nothing shifts, so nothing may blink the ring off. Arming here would be exactly the
        // lag the stable-geometry contract forbids.
        val (c, authority) = open(mode = FileBrowserMode.PICK)
        assertEquals(FileBrowserCommitPurpose.CHOOSE_SOURCE, c.commitPurpose)

        beginPick(c, authority, unrecognised)

        assertEquals(
            "the question changed, not the listing",
            FileBrowserCommitPurpose.EXTRACT_DESTINATION,
            c.commitPurpose,
        )
        assertNull(
            "no arrival: the commit row's EXISTENCE is the arming condition, and it did not change",
            c.navigationMotion,
        )
        assertTrue("so the ring stays lit through the whole transition", ringEligibleOnArrival(c))
        c.close()
    }

    @Test
    fun `at the drive chooser no row exists in either state, so nothing arms`() {
        // Contract C suppresses the commit row where `commitAllowed` is false. With no row on either side
        // of the change there is no geometry to shift and no reason to gate anything.
        val (c, authority) = open(commitAllowed = { false })
        assertFalse(c.commitAvailable)

        beginPick(c, authority, unrecognised)

        assertNotNull("the pick is open", c.destinationPick)
        assertFalse("but still no row", c.commitAvailable)
        assertNull("and no arrival", c.navigationMotion)
        assertTrue(ringEligibleOnArrival(c))
        c.close()
    }

    @Test
    fun `a directional press during an open pick never re-arms the gate`() {
        // The stable-geometry contract, at the controller level rather than the predicate level: continuous
        // travel must not make the ring wait. Nothing a D-pad press touches can change the commit row's
        // existence, so the epoch cannot move.
        val (c, authority) = open()
        beginPick(c, authority, unrecognised)
        val armedMotion = armed(c, "beginning the pick must arm, or this test proves nothing about travel")
        val epoch = fileBrowserRingEpoch(c.currentPath, armedMotion)

        repeat(4) { c.dispatch(ControllerAction.NAV_DOWN) }
        c.dispatch(ControllerAction.NAV_UP)

        assertEquals(
            "movement is being exercised -- the ring must keep up, never lag",
            epoch,
            fileBrowserRingEpoch(c.currentPath, c.navigationMotion),
        )
        assertEquals("and the armed motion is untouched", armedMotion, c.navigationMotion)
        c.close()
    }

    @Test
    fun `a refresh during an open pick never re-arms the gate`() {
        // Contract A: `FileBrowserStore.runExtraction` reloads in place when an extraction finishes. The
        // listing's CONTENTS change; its structure does not, so the ring must not blink off.
        val (c, authority) = open()
        beginPick(c, authority, unrecognised)
        val armedMotion = armed(c, "beginning the pick must arm, or this test proves nothing about refresh")

        c.refresh()

        assertEquals("a reload in place is not an arrival", armedMotion, c.navigationMotion)
        c.close()
    }

    @Test
    fun `a first mount never arms, even in a mode that has a commit row from frame one`() {
        // PICK's row exists on the very first load, so a naive "the row is here now and was not before"
        // rule would gate the ring on every mount. open() is not a state change under a stationary
        // player; there is no previous listing to have arrived from.
        val (c, _) = open(mode = FileBrowserMode.PICK)

        assertTrue("the row is there", c.commitAvailable)
        assertNull("and nothing is in flight", c.navigationMotion)
        assertTrue("so the ring is eligible from frame one", ringEligibleOnArrival(c))
        c.close()
    }
}
