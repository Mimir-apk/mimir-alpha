package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focus correctness after moving up to Add and back, distinct from settlement timing.
 *
 * The return anchor for DOWN-off-the-Add-pill is `viewModel.centeredShelfKey`, stamped ONLY by the
 * shelf's `onCenter` (`LibraryScreen.kt:1172`), which runs from `ShelfCoverflow`'s
 * `LaunchedEffect(focusedInRow, …)` (`ShelfCoverflow.kt:151`) — i.e. POST-composition. A coalesced
 * controller burst lands several dispatches inside one frame (device-established: shelf navigation
 * coalesces during animation), so RIGHT+UP in one frame moves authority focus tile→next→Add with a
 * single recomposition whose `focusedInRow` is already null (Add is not a ring stop) — the new tile
 * is never stamped, and DOWN returns to the STALE centre. The harness's renderPass() placement IS
 * the frame model: between every dispatch for the paced control, absent inside the burst.
 */
class ShelfAddPillReturnFocusCharacterizationTest {

    private val addKey = FocusKey("library/shelf/add")

    @Test
    fun `paced control - up to Add and back returns to the tile you left`() = runBlocking {
        val h = Harness()
        h.start()
        val origin = h.ringKeys[0]
        val next = h.ringKeys[1]
        assertEquals(origin, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_RIGHT)
        h.renderPass()
        assertEquals(next, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_UP)
        h.renderPass()
        assertEquals(addKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_DOWN)
        h.renderPass()
        assertEquals(next, h.authority.state.value.focused)
    }

    /**
     * 2026-08-11 review hardening: the centre branch must reject the Add key by GUARD, not by
     * writer statement order — a future stamp of the pill into centeredShelfKey must never latch
     * DOWN-off-Add onto the pill itself.
     */
    @Test
    fun `a poisoned Add-key centre can never latch focus onto the pill`() = runBlocking {
        val h = Harness()
        h.start()

        h.dispatch(ControllerAction.NAV_UP)
        assertEquals(addKey, h.authority.state.value.focused)
        h.viewModel.setCenteredShelfKey(addKey)

        h.dispatch(ControllerAction.NAV_DOWN)
        assertTrue(
            "DOWN off the Add pill must leave the pill even with a poisoned centre",
            h.authority.state.value.focused != addKey,
        )
    }

    /**
     * 2026-08-11 review: native shelf modes run the same Add-pill anchor through the same arms but
     * were uncharacterized — the burst round trip is pinned for the native key family too.
     */
    @Test
    fun `native mode coalesced burst also returns to the tile you left`() = runBlocking {
        val h = Harness()
        h.startNative()
        val next = h.ringKeys[1]

        h.dispatch(ControllerAction.NAV_RIGHT)
        h.dispatch(ControllerAction.NAV_UP)
        h.renderPass()
        assertEquals(addKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_DOWN)
        h.renderPass()
        assertEquals(next, h.authority.state.value.focused)
    }

    /** The captured D-pad stimulus: the same three-press sequence, with RIGHT+UP landing inside one frame. */
    @Test
    fun `coalesced burst - up to Add and back must still return to the tile you left`() = runBlocking {
        val h = Harness()
        h.start()
        val next = h.ringKeys[1]

        // One frame: RIGHT and UP dispatch before the next recomposition ever sees the new tile.
        h.dispatch(ControllerAction.NAV_RIGHT)
        h.dispatch(ControllerAction.NAV_UP)
        h.renderPass()
        assertEquals(addKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_DOWN)
        h.renderPass()
        assertEquals(
            "DOWN off the Add pill must return to the tile focus left the ring from " +
                "(regression: up to Add and back lands on the wrong tile)",
            next,
            h.authority.state.value.focused,
        )
    }

    /** Two ring moves in the burst: the anchor must be the LAST tile focus actually left. */
    @Test
    fun `coalesced double move - anchor is the last ring tile before Add`() = runBlocking {
        val h = Harness()
        h.start()
        val third = h.ringKeys[2]

        h.dispatch(ControllerAction.NAV_RIGHT)
        h.dispatch(ControllerAction.NAV_RIGHT)
        h.dispatch(ControllerAction.NAV_UP)
        h.renderPass()
        assertEquals(addKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_DOWN)
        h.renderPass()
        assertEquals(third, h.authority.state.value.focused)
    }

    private inner class Harness {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository)
        val authority = DefaultFocusAuthority()
        var platformRows: List<PlatformRow> = emptyList()
        var nativeGameRows: List<NativeShelfRow> = emptyList()
        lateinit var ringKeys: List<FocusKey>

        /** Shelf surface: active LibraryShelfScope, ring registered, Add pill physical, tile 0 focused. */
        suspend fun start() {
            platformRows = repository.observePlatforms().first()
            assertTrue(platformRows.size >= 3)
            ringKeys = platformRows.map { FocusKey("library/shelf/${it.platformId.value}") }
            mountRing()
        }

        /** The same surface in ANDROID_GAMES mode with the native key family. */
        fun startNative() {
            viewModel.setShelfMode(LibraryShelfMode.ANDROID_GAMES)
            nativeGameRows = listOf("com.a.one", "com.a.two", "com.a.three").map { pkg ->
                NativeShelfRow(
                    packageName = pkg,
                    label = pkg.substringAfterLast('.'),
                    launchActivity = null,
                    isGame = true,
                    isFavorite = false,
                )
            }
            ringKeys = nativeGameRows.map { nativeShelfKey(it.packageName) }
            mountRing()
        }

        private fun mountRing() {
            authority.pushScope(LibraryShelfScope)
            ringKeys.forEachIndexed { index, key ->
                authority.registerVirtual(key, LibraryShelfScope, ringSlotBounds(index))
            }
            authority.register(addKey, LibraryShelfScope, AddPillBounds)
            authority.requestFocus(ringKeys[0])
            renderPass()
        }

        fun dispatch(action: ControllerAction) {
            assertTrue(
                dispatchToLibrary(
                    authority = authority,
                    viewModel = viewModel,
                    action = action,
                    onExit = {},
                    onRescanFolder = {},
                    platformRows = platformRows,
                    nativeGameRows = nativeGameRows,
                ),
            )
        }

        /**
         * One recomposition's worth of the shelf's centre-stamping duty — the exact mirror of
         * `ShelfCoverflow`'s `LaunchedEffect(focusedInRow) { onCenter(...) }` feeding the
         * `onCenter` lambda at `LibraryScreen.kt:1172` (scope memory + view-model centre).
         * Fires only when the authority focus IS a ring stop, exactly like `focusedInRow != null`.
         */
        fun renderPass() {
            val focused = authority.state.value.focused ?: return
            if (focused in ringKeys) {
                authority.rememberFocusForScope(LibraryShelfScope, focused)
                viewModel.setCenteredShelfKey(focused)
            }
        }
    }

    private companion object {
        /** RegisterVirtualFocusRow geometry: columns at x = index * 1000, half-pitch wide. */
        fun ringSlotBounds(index: Int) =
            FocusBounds(index * 1000f, 0f, index * 1000f + 500f, 1000f)

        /** The Add pill's real registration is physical pixels (LibraryScreen.kt:2277). */
        val AddPillBounds = FocusBounds(1500f, 40f, 1720f, 104f)
    }
}
