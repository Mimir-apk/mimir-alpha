package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedVirtualFocusRegistrationTest {

    @Test
    fun windowIsCenteredOnFocusedIndexAndBoundedForLargeLists() {
        val keys = focusKeys(1_000)
        val window = boundedVirtualFocusWindow(keys, focusedIndex = 500, radius = 4)

        assertEquals(500, window.anchorIndex)
        assertEquals(496, window.startIndex)
        assertEquals(505, window.endIndexExclusive)
        assertEquals((496..504).toList(), window.slots.map { it.index })
        assertEquals(keys[500], window.slots[4].key)
        assertEquals(9, window.slots.size)
    }

    @Test
    fun windowClampsAtListEdgesWithoutDuplicatingRows() {
        val keys = focusKeys(10)

        val first = boundedVirtualFocusWindow(keys, focusedIndex = 0, radius = 4)
        val last = boundedVirtualFocusWindow(keys, focusedIndex = 9, radius = 4)

        assertEquals((0..4).toList(), first.slots.map { it.index })
        assertEquals((5..9).toList(), last.slots.map { it.index })
        assertEquals(first.slots.map { it.index }.toSet().size, first.slots.size)
        assertEquals(last.slots.map { it.index }.toSet().size, last.slots.size)
    }

    @Test
    fun selectedIndexAnchorsWindowWhenFocusIsOnMetadataControl() {
        val keys = focusKeys(100)
        val window = boundedVirtualFocusWindow(
            keys = keys,
            focusedIndex = -1,
            selectedIndex = 42,
            radius = 3,
        )

        assertEquals(42, window.anchorIndex)
        assertEquals((39..45).toList(), window.slots.map { it.index })
        assertTrue(keys[42] in window.keys)
    }

    @Test
    fun defaultWindowDoesNotRegisterEveryRowInLargeLists() {
        val keys = focusKeys(2_000)
        val window = boundedVirtualFocusWindow(keys, focusedIndex = 1_000)

        assertEquals(DefaultVirtualFocusWindowRadius * 2 + 1, window.slots.size)
        assertTrue(window.slots.size < keys.size)
    }

    @Test
    fun boundedWindowStillTraversesLongListsWhenAdvancedWithFocus() {
        val keys = focusKeys(160)
        val scope = FocusScope("library/gamelist/test")
        val authority = DefaultFocusAuthority()
        val tracker = VirtualFocusRegistrationTracker()

        authority.pushScope(scope)
        updateWindow(tracker, authority, scope, keys, focusedIndex = 60, radius = 3)
        authority.requestFocus(keys[60])

        repeat(40) { step ->
            assertTrue("down step ${step + 1}", authority.moveFocus(ControllerAction.NAV_DOWN))
            val focusedIndex = keys.indexOf(authority.state.value.focused)
            assertEquals(61 + step, focusedIndex)
            updateWindow(tracker, authority, scope, keys, focusedIndex = focusedIndex, radius = 3)
        }

        repeat(40) { step ->
            assertTrue("up step ${step + 1}", authority.moveFocus(ControllerAction.NAV_UP))
            val focusedIndex = keys.indexOf(authority.state.value.focused)
            assertEquals(99 - step, focusedIndex)
            updateWindow(tracker, authority, scope, keys, focusedIndex = focusedIndex, radius = 3)
        }
    }

    @Test
    fun virtualFocusRowSlotsKeepShelfKeysInHorizontalOrder() {
        val keys = focusKeys(5)
        val slots = virtualFocusRowSlots(keys)

        assertEquals(keys, slots.map { it.key })
        assertEquals((0 until 5).toList(), slots.map { it.index })
        slots.zipWithNext().forEach { (left, right) ->
            assertTrue(right.bounds.centerX > left.bounds.centerX)
            assertEquals(left.bounds.centerY, right.bounds.centerY)
        }
    }

    @Test
    fun preferredRegistrationSeedsExactKeyWithoutDroppingOrMovingAnySlot() {
        val keys = focusKeys(8)
        val authored = virtualFocusRowSlots(keys)
        val ordered = preferredVirtualFocusRegistrationOrder(authored, keys[5])

        assertEquals(keys[5], ordered.first().key)
        assertEquals(keys.toSet(), ordered.map { it.key }.toSet())
        assertEquals(authored.size, ordered.size)
        authored.forEach { slot ->
            assertEquals(slot, ordered.single { it.key == slot.key })
        }
    }

    @Test
    fun trackerRegistersIncomingWindowBeforeRemovingStaleKeys() {
        val keys = focusKeys(20)
        val scope = FocusScope("library/gamelist/test")
        val authority = RecordingFocusAuthority()
        val tracker = VirtualFocusRegistrationTracker()

        tracker.update(
            authority = authority,
            scope = scope,
            slots = boundedVirtualFocusWindow(keys, focusedIndex = 8, radius = 2).slots,
        )
        authority.events.clear()

        tracker.update(
            authority = authority,
            scope = scope,
            slots = boundedVirtualFocusWindow(keys, focusedIndex = 10, radius = 2).slots,
        )

        val firstUnregister = authority.events.indexOfFirst { it.startsWith("unregister:") }
        val lastRegister = authority.events.indexOfLast { it.startsWith("register:") }
        assertTrue(firstUnregister > lastRegister)
        assertFalse("overlapping focused-window keys must not be removed", "unregister:row/8" in authority.events)
        assertFalse("overlapping focused-window keys must not be removed", "unregister:row/9" in authority.events)
        assertFalse("overlapping focused-window keys must not be removed", "unregister:row/10" in authority.events)
        assertTrue("unregister:row/6" in authority.events)
        assertTrue("unregister:row/7" in authority.events)
    }

    @Test
    fun trackerClearUnregistersOnlyCurrentWindow() {
        val keys = focusKeys(20)
        val scope = FocusScope("library/gamelist/test")
        val authority = RecordingFocusAuthority()
        val tracker = VirtualFocusRegistrationTracker()

        tracker.update(
            authority = authority,
            scope = scope,
            slots = boundedVirtualFocusWindow(keys, focusedIndex = 10, radius = 1).slots,
        )
        authority.events.clear()
        tracker.clear(authority)

        assertEquals(
            listOf("unregister:row/9", "unregister:row/10", "unregister:row/11"),
            authority.events,
        )
    }

    // ── Settings -> Home shelf return-focus fix () ──
    //
    // Mechanism: DefaultFocusAuthority.removeEntryAndFallback reassigns focus to the nearest still-
    // registered sibling whenever the key being unregistered IS the live focus -- correct for a single
    // item disappearing from an otherwise-live ring, but VirtualFocusRegistrationTracker.clear() removes
    // every key in registration (ascending index) order during a bulk scope teardown, so removing the
    // player's actual tile cascades: focus reassigns to the next tile, which is unregistered the very
    // next call, reassigning again, all the way to the ring's LAST key. The final removal has no sibling
    // left (setFocus(null)), and setFocus(null) never writes focusMemory, so the cascade's last non-null
    // stop -- the LAST tile -- is what a later popScope()/pushScope() restores. clearAndRestoreFocus
    // fixes this by snapshotting the true pre-clear focus and re-stamping it after the bulk clear.
    @Test
    fun bulkClearCascadesUnprotectedFocusMemoryToTheLastKey() {
        // Documents the raw, unprotected mechanism this fix guards against -- tracker.clear() alone,
        // exactly as it behaved before this lane, always lands scope memory on the ring's last key
        // whenever the player was focused anywhere other than that last key.
        val keys = focusKeys(6)
        val scope = FocusScope("home/shelf/test")
        val authority = DefaultFocusAuthority()
        val tracker = VirtualFocusRegistrationTracker()

        authority.pushScope(scope)
        tracker.update(authority = authority, scope = scope, slots = virtualFocusRowSlots(keys))
        authority.requestFocus(keys[2])
        assertEquals(keys[2], authority.state.value.focused)

        tracker.clear(authority)

        assertEquals(
            "raw clear() cascades focus-memory to the ring's last key, not the tile the player left from",
            keys.last(),
            authority.rememberedFocusIn(scope),
        )
    }

    @Test
    fun clearAndRestoreFocusPreservesTheTileThePlayerActuallyLeftFrom() {
        val keys = focusKeys(6)
        val scope = FocusScope("home/shelf/test")
        val authority = DefaultFocusAuthority()
        val tracker = VirtualFocusRegistrationTracker()

        authority.pushScope(scope)
        tracker.update(authority = authority, scope = scope, slots = virtualFocusRowSlots(keys))
        authority.requestFocus(keys[2])
        assertEquals(keys[2], authority.state.value.focused)

        tracker.clearAndRestoreFocus(authority, scope)

        assertEquals(keys[2], authority.rememberedFocusIn(scope))
    }

    @Test
    fun clearAndRestoreFocusHoldsAtBothRingEndsToo() {
        val keys = focusKeys(6)
        val scope = FocusScope("home/shelf/test")

        listOf(0, keys.size - 1).forEach { index ->
            val authority = DefaultFocusAuthority()
            val tracker = VirtualFocusRegistrationTracker()
            authority.pushScope(scope)
            tracker.update(authority = authority, scope = scope, slots = virtualFocusRowSlots(keys))
            authority.requestFocus(keys[index])

            tracker.clearAndRestoreFocus(authority, scope)

            assertEquals("index $index", keys[index], authority.rememberedFocusIn(scope))
        }
    }

    @Test
    fun clearAndRestoreFocusIsANoOpWhenAnotherScopeWasActive() {
        // The bulk-teardown hazard only exists when the tiles being cleared WERE the live focus. When a
        // sibling scope (e.g. the Home quick rail) owns focus instead, focusMemory for the shelf scope was
        // already stamped correctly by the earlier pushScope() into the rail -- clearAndRestoreFocus must
        // not disturb that.
        val keys = focusKeys(6)
        val scope = FocusScope("home/shelf/test")
        val railScope = FocusScope("home/shelf/rail/test")
        val authority = DefaultFocusAuthority()
        val tracker = VirtualFocusRegistrationTracker()

        authority.pushScope(scope)
        tracker.update(authority = authority, scope = scope, slots = virtualFocusRowSlots(keys))
        authority.requestFocus(keys[3])
        authority.pushScope(railScope) // stamps focusMemory[scope] = keys[3] on the way in

        tracker.clearAndRestoreFocus(authority, scope)

        assertEquals(keys[3], authority.rememberedFocusIn(scope))
    }

    private fun updateWindow(
        tracker: VirtualFocusRegistrationTracker,
        authority: FocusAuthority,
        scope: FocusScope,
        keys: List<FocusKey>,
        focusedIndex: Int,
        radius: Int,
    ) {
        tracker.update(
            authority = authority,
            scope = scope,
            slots = boundedVirtualFocusWindow(
                keys = keys,
                focusedIndex = focusedIndex,
                radius = radius,
            ).slots,
        )
    }

    private fun focusKeys(count: Int): List<FocusKey> =
        (0 until count).map { FocusKey("row/$it") }

    private class RecordingFocusAuthority : FocusAuthority {
        val events = mutableListOf<String>()
        override val state: StateFlow<FocusState> = MutableStateFlow(FocusState(FocusScope.Root, null))

        override fun register(key: FocusKey, scope: FocusScope, bounds: FocusBounds) = Unit

        override fun registerVirtual(key: FocusKey, scope: FocusScope, bounds: FocusBounds) {
            events += "register:${key.value}"
        }

        override fun unregister(key: FocusKey) = Unit

        override fun unregisterVirtual(key: FocusKey) {
            events += "unregister:${key.value}"
        }

        override fun requestFocus(key: FocusKey) = Unit

        override fun moveFocus(direction: ControllerAction): Boolean = false

        override fun pushScope(scope: FocusScope) = Unit

        override fun popScope() = Unit
    }
}
