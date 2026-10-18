package com.gruning.mimir.rewrite.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the single-source-of-truth focus invariants. These need NO device: the geometry
 * and scope logic in [DefaultFocusAuthority] are pure. They lock the "one focus authority"
 * behaviors so a future change can't silently regress them.
 */
class DefaultFocusAuthorityTest {

    private fun box(l: Float, t: Float): FocusBounds = FocusBounds(l, t, l + 100f, t + 100f)

    @Test
    fun firstRegisteredElementInActiveScopeGetsFocus() {
        val fa = DefaultFocusAuthority()
        val a = FocusKey("a")
        fa.register(a, FocusScope.Root, box(0f, 0f))
        assertEquals(a, fa.state.value.focused)
    }

    @Test
    fun rightMoveChoosesTheElementToTheRightNotTheOneAbove() {
        val fa = DefaultFocusAuthority()
        val a = FocusKey("a"); val right = FocusKey("right"); val up = FocusKey("up")
        fa.register(a, FocusScope.Root, box(0f, 100f))
        fa.register(right, FocusScope.Root, box(200f, 100f))
        fa.register(up, FocusScope.Root, box(0f, 0f))
        fa.requestFocus(a)
        assertTrue(fa.moveFocus(ControllerAction.NAV_RIGHT))
        assertEquals(right, fa.state.value.focused)
    }

    @Test
    fun coverflowRightMoveStaysInRowWithSnappedHeroAndAddPillNearby() {
        val fa = DefaultFocusAuthority()
        val scope = FocusScope("library/shelf")
        val left = FocusKey("shelf/left")
        val hero = FocusKey("shelf/hero")
        val right = FocusKey("shelf/right")
        val add = FocusKey("shelf/add")

        fa.pushScope(scope)
        // Shipping-style coverflow geometry: a 224px focused hero, 86px resting neighbors, and
        // an Add pill above/right in the same shelf scope. Directional scoring must keep horizontal
        // D-pad movement on the immediate row neighbor, not the larger nearby Add action.
        fa.register(left, scope, FocusBounds(left = 196f, top = 124f, right = 282f, bottom = 216f))
        fa.register(hero, scope, FocusBounds(left = 300f, top = 100f, right = 524f, bottom = 240f))
        fa.register(right, scope, FocusBounds(left = 542f, top = 124f, right = 628f, bottom = 216f))
        fa.register(add, scope, FocusBounds(left = 650f, top = 0f, right = 850f, bottom = 80f))

        fa.requestFocus(hero)
        assertTrue(fa.moveFocus(ControllerAction.NAV_RIGHT))
        assertEquals(right, fa.state.value.focused)
    }

    @Test
    fun moveAtEdgeReturnsFalseAndKeepsFocus() {
        val fa = DefaultFocusAuthority()
        val a = FocusKey("a")
        fa.register(a, FocusScope.Root, box(0f, 0f))
        fa.requestFocus(a)
        assertFalse(fa.moveFocus(ControllerAction.NAV_RIGHT)) // nothing to the right
        assertEquals(a, fa.state.value.focused)
    }

    @Test
    fun virtualRowsLetLazyListsMovePastTheVisibleWindow() {
        val fa = DefaultFocusAuthority()
        val scope = FocusScope("list")
        val rows = (0..6).map { FocusKey("list/row/$it") }

        fa.pushScope(scope)
        rows.forEachIndexed { index, key ->
            fa.registerVirtual(
                key = key,
                scope = scope,
                bounds = FocusBounds(
                    left = 0f,
                    top = index * 1000f,
                    right = 1000f,
                    bottom = index * 1000f + 500f,
                ),
            )
        }
        // A visible lazy item may have very different window coordinates. Directional list traversal
        // still follows the virtual ordered slots so an offscreen next row can receive focus and scroll in.
        fa.register(rows[5], scope, FocusBounds(left = 75f, top = 815f, right = 1398f, bottom = 926f))
        fa.requestFocus(rows[5])

        assertTrue(fa.moveFocus(ControllerAction.NAV_DOWN))
        assertEquals(rows[6], fa.state.value.focused)
    }

    @Test
    fun longLazyRowStackTraversesByVirtualOrderDespiteFocusedRowLift() {
        val fa = DefaultFocusAuthority()
        val scope = FocusScope("list")
        val rows = (0 until 40).map { FocusKey("list/row/$it") }

        fa.pushScope(scope)
        rows.forEachIndexed { index, key ->
            val top = index * 1000f
            fa.registerVirtual(
                key = key,
                scope = scope,
                bounds = FocusBounds(left = 0f, top = top, right = 1000f, bottom = top + 500f),
            )
        }
        // Model a composed LazyColumn window after row-shell polish: visible rows report compact,
        // stacked window bounds, and the focused row is lifted 1px. Controller traversal must still
        // use the long virtual column order instead of jumping toward early virtual rows.
        (18..24).forEach { index ->
            val windowTop = (index - 18) * 72f
            val focusedLift = if (index == 21) -1f else 0f
            fa.register(
                key = rows[index],
                scope = scope,
                bounds = FocusBounds(
                    left = 24f,
                    top = windowTop + focusedLift,
                    right = 624f,
                    bottom = windowTop + 64f + focusedLift,
                ),
            )
        }
        fa.requestFocus(rows[21])

        repeat(12) { step ->
            assertTrue("down step ${step + 1}", fa.moveFocus(ControllerAction.NAV_DOWN))
            assertEquals(rows[22 + step], fa.state.value.focused)
        }
        repeat(12) { step ->
            assertTrue("up step ${step + 1}", fa.moveFocus(ControllerAction.NAV_UP))
            assertEquals(rows[32 - step], fa.state.value.focused)
        }
    }

    /**
     * Mixed coordinate spaces: a scope that legitimately mixes a virtual-only list (synthetic 1000f-pitch bounds, e.g.
     * `BoundedVirtualFocusRegistration.kt`'s `VirtualFocusRowPitch`, used by every gamelist row in
     * `feature-library/.../LibraryScreen.kt`'s `GamelistView` / `GamelistRow`) with a real-pixel-only
     * sibling registered by plain `mimirFocusable` (e.g. `GamelistGetArtKey`, registered by
     * `GamelistMediaActionChip` in `feature-library/.../GamelistCarousel.kt`) puts BOTH unit systems into
     * ONE `moveFocus` candidate set. `directionalScore` has no unit awareness, so a real-pixel sibling can
     * out-score the geometrically-correct virtual neighbor purely because its real Y coordinate happens to
     * fall numerically closer to the departing row's synthetic Y than the next virtual row does — the
     * "closer" answer is an artifact of incompatible units, not real screen proximity. MEASURED here
     * against the real [DefaultFocusAuthority], not asserted from inspection.
     */
    @Test
    fun mixedVirtualListAndRealPixelSiblingCanMisdirectDirectionalMove() {
        val fa = DefaultFocusAuthority()
        val scope = FocusScope("library/gamelist")
        val row0 = FocusKey("library/gamelist/game0")
        val row1 = FocusKey("library/gamelist/game1")
        val getArt = FocusKey("library/gamelist/getArt")

        fa.pushScope(scope)
        // Same formula as the real BoundedVirtualFocusRegistration.kt (VirtualFocusRowPitch = 1000f):
        // left=0, right=1000, top=index*1000, bottom=index*1000+500.
        fa.registerVirtual(row0, scope, FocusBounds(left = 0f, top = 0f, right = 1000f, bottom = 500f))
        fa.registerVirtual(row1, scope, FocusBounds(left = 0f, top = 1000f, right = 1000f, bottom = 1500f))
        // A real on-screen action chip near the bottom of a ~480dp-tall panel (RP5-scale dp, matching the
        // order of magnitude of GamelistCarousel.kt's GamelistDetailBandHeight/GamePreviewPanel geometry) —
        // ordinary real pixel/dp bounds, nothing synthetic about them.
        fa.register(getArt, scope, FocusBounds(left = 520f, top = 880f, right = 780f, bottom = 930f))

        fa.requestFocus(row1)
        fa.moveFocus(ControllerAction.NAV_UP)

        // The geometrically "correct" answer is row0 (the only other row, directly above in the virtual
        // list). The real-pixel getArt chip's Y (880-930) is numerically much closer to row1's synthetic Y
        // (1000-1500) than row0's synthetic Y (0-500) is, so it wins the score despite being an unrelated
        // control, not a list neighbor. This assertion pins the CURRENT (defective) behavior as measured
        // fact, not a recommendation.
        assertEquals(getArt, fa.state.value.focused)
    }

    /**
     * Focus-coordinate audit: TWO scoring sites read the same mixed
     * virtual-or-physical `Entry.bounds` — `moveFocus` (proven defective above) and
     * `removeEntryAndFallback`, which lacked coverage for this case.
     * `LibrarySearchOverlay.kt`'s `RegisterLibrarySearchVirtualColumn` (line ~790-812) registers
     * search-result rows with the EXACT production formula reproduced here (`topOffset=800f`,
     * `top = topOffset + index*100f`, `left=0f`/`right=100f`), and its `LibrarySearchClearKey`
     * ("CLEAR FILTERS", `.mimirFocusable` at line ~533) is a real-physical control, conditionally
     * composed only while `criteria != LibrarySearchCriteria()`, that structurally sits BELOW the
     * results `LazyColumn` in the same `LibrarySearchScope` (`LibrarySearchOverlay.kt:304-360`) — so
     * pressing CONFIRM on it (`onClear`) can unregister the FOCUSED key the same frame criteria resets.
     *
     * MEASURED, not assumed: with a representative real-control X range (100-300, from the top control
     * row) this does NOT misfire — `query` wins over the synthetic list purely because the virtual
     * column's X range (0-100, fixed by `RegisterLibrarySearchVirtualColumn`) sits outside the real
     * controls' X range, adding a cross-axis penalty large enough to save it. That margin is
     * COINCIDENTAL (an accident of the virtual column defaulting to X 0-100, not a designed guard —
     * nothing in `removeEntryAndFallback` is unit-aware), not structural: the real Y bounds here are a
     * representative estimate, not a device measurement, and the same `distance()` call that resolves
     * this correctly is the one `mixedVirtualListAndRealPixelSiblingCanMisdirectRemovalFallback`'s
     * sibling test (above, same class, real production geometry) proves DOES misfire for
     * `GamelistScope`. Cannot-settle without the real on-screen coordinates whether `library/search`'s
     * production layout keeps this margin — pinned here as the currently-safe baseline so a future
     * layout change that narrows it regresses loudly instead of silently.
     */
    @Test
    fun survivesMixedVirtualListAndRealPixelSiblingOnRemovalFallbackWithRepresentativeGeometry() {
        val fa = DefaultFocusAuthority()
        val scope = FocusScope("library/search")
        val query = FocusKey("library/search/query")
        val clear = FocusKey("library/search/clear")
        val result0 = FocusKey("library/search/result/game/0")
        val result1 = FocusKey("library/search/result/game/1")

        fa.pushScope(scope)
        // Real top-control row, same X column as Clear, near the top of the panel.
        fa.register(query, scope, FocusBounds(left = 100f, top = 90f, right = 300f, bottom = 138f))
        // Real Clear control: same production shape (LibrarySearchControl), sitting below a results
        // column that fills the remaining panel height — representative, not device-measured.
        fa.register(clear, scope, FocusBounds(left = 100f, top = 500f, right = 300f, bottom = 548f))
        // Exact production formula: topOffset=800f, pitch=100f (RegisterLibrarySearchVirtualColumn).
        fa.registerVirtual(result0, scope, FocusBounds(left = 0f, top = 800f, right = 100f, bottom = 880f))
        fa.registerVirtual(result1, scope, FocusBounds(left = 0f, top = 900f, right = 100f, bottom = 980f))

        fa.requestFocus(clear)
        fa.unregister(clear) // models onClear's own control vanishing on the frame it fires

        assertEquals(query, fa.state.value.focused)
    }

    @Test
    fun physicalUnregisterKeepsVirtualLazySlotFocused() {
        val fa = DefaultFocusAuthority()
        val scope = FocusScope("list")
        val row = FocusKey("list/row/0")

        fa.pushScope(scope)
        fa.registerVirtual(row, scope, box(0f, 0f))
        fa.register(row, scope, FocusBounds(left = 50f, top = 800f, right = 200f, bottom = 900f))
        fa.requestFocus(row)
        fa.unregister(row)

        assertEquals(row, fa.state.value.focused)
    }

    @Test
    fun unregisteringTheFocusedElementFallsBackAndNeverLeavesItFocused() {
        val fa = DefaultFocusAuthority()
        val a = FocusKey("a"); val b = FocusKey("b")
        fa.register(a, FocusScope.Root, box(0f, 0f))
        fa.register(b, FocusScope.Root, box(200f, 0f))
        fa.requestFocus(a)
        fa.unregister(a)
        assertEquals("focus must fall to the surviving sibling", b, fa.state.value.focused)
    }

    @Test
    fun pushAndPopScopeRestoresFocusMemory() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusKey("shelf/ps1")
        fa.register(shelf, FocusScope.Root, box(0f, 0f))
        fa.requestFocus(shelf)

        val picker = FocusScope("picker")
        val opt = FocusKey("picker/opt0")
        fa.pushScope(picker)
        fa.register(opt, picker, box(0f, 0f))
        assertEquals(picker, fa.state.value.scope)
        assertEquals(opt, fa.state.value.focused)

        fa.popScope()
        assertEquals(FocusScope.Root, fa.state.value.scope)
        assertEquals("popping the modal restores the shelf focus", shelf, fa.state.value.focused)
    }

    @Test
    fun parkedReturnFocusWinsOverThePushStampedKeyOnPop() {
        // Mirrors the Add-app return path (RCP-02): the picker opens FROM the Add pill, so pushScope stamps
        // the pill as the shelf's focus memory. Parking the captured content tile via rememberFocusForScope
        // while the picker is open must override that stamp, so popScope restores the tile — not the pill.
        val fa = DefaultFocusAuthority()
        val addPill = FocusKey("library/shelf/add")
        val tileP = FocusKey("library/native/com.example.p")
        fa.register(addPill, FocusScope.Root, box(0f, 0f))
        fa.register(tileP, FocusScope.Root, box(200f, 0f))
        fa.requestFocus(addPill) // the user is on the Add pill when they open the picker

        val picker = FocusScope("picker")
        fa.pushScope(picker) // stamps focusMemory[Root] = addPill
        fa.register(FocusKey("picker/opt0"), picker, box(0f, 0f))
        assertEquals(picker, fa.state.value.scope)

        fa.rememberFocusForScope(FocusScope.Root, tileP) // PARK the captured tile while the picker is open
        fa.popScope()

        assertEquals(FocusScope.Root, fa.state.value.scope)
        assertEquals("parked return tile wins over the push-stamped Add pill", tileP, fa.state.value.focused)
    }

    @Test
    fun modalPickerCyclesKeepTraversalInsidePickerAndRestoreCallingFocus() {
        val fa = DefaultFocusAuthority()
        val rail = FocusKey("root/rail")
        val shelf = FocusKey("root/shelf")
        val picker = FocusScope("picker/platform")
        val first = FocusKey("picker/platform/first")
        val second = FocusKey("picker/platform/second")

        fa.register(rail, FocusScope.Root, box(0f, 0f))
        fa.register(shelf, FocusScope.Root, box(600f, 0f))
        fa.requestFocus(shelf)

        fa.pushScope(picker)
        fa.register(first, picker, box(0f, 0f))
        fa.register(second, picker, box(180f, 0f))

        assertTrue(fa.moveFocus(ControllerAction.NAV_RIGHT))
        assertEquals(second, fa.state.value.focused)
        assertFalse("right edge in picker must not leak to root shelf", fa.moveFocus(ControllerAction.NAV_RIGHT))
        assertEquals(second, fa.state.value.focused)

        fa.popScope()
        assertEquals(FocusScope.Root, fa.state.value.scope)
        assertEquals(shelf, fa.state.value.focused)

        fa.pushScope(picker)
        assertEquals("reopening picker restores its remembered option", second, fa.state.value.focused)
        assertTrue(fa.moveFocus(ControllerAction.NAV_LEFT))
        assertEquals(first, fa.state.value.focused)

        fa.popScope()
        assertEquals("closing picker again restores the caller focus", shelf, fa.state.value.focused)
    }

    @Test
    fun directionalMoveResolvesOnlyWithinTheActiveScope() {
        val fa = DefaultFocusAuthority()
        val rootRight = FocusKey("root/right")
        fa.register(FocusKey("root/a"), FocusScope.Root, box(0f, 0f))
        fa.register(rootRight, FocusScope.Root, box(200f, 0f))

        val picker = FocusScope("picker")
        val only = FocusKey("picker/only")
        fa.pushScope(picker)
        fa.register(only, picker, box(0f, 0f))
        // A right move inside the picker must NOT jump to the root element to the right.
        assertFalse(fa.moveFocus(ControllerAction.NAV_RIGHT))
        assertEquals(only, fa.state.value.focused)
    }

    @Test
    fun moveFocusRejectsNonDirectionalActions() {
        val fa = DefaultFocusAuthority()
        fa.register(FocusKey("a"), FocusScope.Root, box(0f, 0f))
        var threw = false
        try { fa.moveFocus(ControllerAction.CONFIRM) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("CONFIRM is not directional and must be rejected", threw)
    }

    @Test
    fun emptyScopeReportsNullFocus() {
        val fa = DefaultFocusAuthority()
        assertNull(fa.state.value.focused)
    }
}
