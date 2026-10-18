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
 * BUG-A characterization: while the Add pill is focused, only DPAD
 * DOWN may settle into the library shelf ring. LEFT/RIGHT must not drop focus into the ring via the
 * geometric `moveFocus` fallback — the pill's physical bounds and the ring's synthetic virtual bounds
 * are mixed coordinate spaces, so horizontal presses from the pill land on the neighbour of the
 * previously focused tile rather than nowhere.
 */
class ShelfAddPillHorizontalNavCharacterizationTest {

    private val addKey = FocusKey("library/shelf/add")

    @Test
    fun `right from Add pill must not settle into the shelf ring`() = runBlocking {
        val h = Harness()
        h.start()
        val neighbour = h.ringKeys[1]

        h.dispatch(ControllerAction.NAV_RIGHT)
        h.renderPass()
        assertEquals(neighbour, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_UP)
        h.renderPass()
        assertEquals(addKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_RIGHT)
        assertEquals(
            "RIGHT from the Add pill must not drop focus into the ring " +
                "(expected focus: lands on the next tile of the previously focused point)",
            addKey,
            h.authority.state.value.focused,
        )
        // DOWN must still be the sole vertical entry from the pill.
        h.dispatch(ControllerAction.NAV_DOWN)
        h.renderPass()
        assertEquals(neighbour, h.authority.state.value.focused)
        assertEquals(neighbour, h.viewModel.centeredShelfKey.value)
    }

    @Test
    fun `left from Add pill must not settle into the shelf ring`() = runBlocking {
        val h = Harness()
        h.start()
        val origin = h.ringKeys[0]

        h.dispatch(ControllerAction.NAV_UP)
        h.renderPass()
        assertEquals(addKey, h.authority.state.value.focused)

        h.dispatch(ControllerAction.NAV_LEFT)
        assertEquals(
            "LEFT from the Add pill must not drop focus into the ring",
            addKey,
            h.authority.state.value.focused,
        )

        h.dispatch(ControllerAction.NAV_DOWN)
        h.renderPass()
        assertEquals(origin, h.authority.state.value.focused)
    }

    private inner class Harness {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository)
        val authority = DefaultFocusAuthority()
        var platformRows: List<PlatformRow> = emptyList()
        lateinit var ringKeys: List<FocusKey>

        suspend fun start() {
            platformRows = repository.observePlatforms().first()
            assertTrue(platformRows.size >= 2)
            ringKeys = platformRows.map { FocusKey("library/shelf/${it.platformId.value}") }
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
                ),
            )
        }

        fun renderPass() {
            val focused = authority.state.value.focused ?: return
            if (focused in ringKeys) {
                authority.rememberFocusForScope(LibraryShelfScope, focused)
                viewModel.setCenteredShelfKey(focused)
            }
        }
    }

    private companion object {
        fun ringSlotBounds(index: Int) =
            FocusBounds(index * 1000f, 0f, index * 1000f + 500f, 1000f)

        val AddPillBounds = FocusBounds(1500f, 40f, 1720f, 104f)
    }
}
