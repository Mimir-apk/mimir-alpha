package com.gruning.mimir.rewrite.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FocusAuthority.unregisterVirtualAll]: a registry clearing thousands of slots (the onboarding favourites Tome holds
 * 5,500 on an RG Rotate) re-homes focus once, not once per slot.
 */
class UnregisterVirtualAllTest {

    private val scope = FocusScope("grid")

    private fun cell(index: Int, columns: Int = 4): FocusBounds {
        val left = (index % columns) * 100f
        val top = (index / columns) * 100f
        return FocusBounds(left, top, left + 90f, top + 90f)
    }

    private fun grid(count: Int): Pair<DefaultFocusAuthority, List<FocusKey>> {
        val fa = DefaultFocusAuthority()
        fa.pushScope(scope)
        val keys = (0 until count).map { FocusKey("grid/$it") }
        keys.forEachIndexed { i, key -> fa.registerVirtual(key, scope, cell(i)) }
        return fa to keys
    }

    @Test
    fun removingTheFocusedSlotWithItsNeighboursLandsOnTheSurvivorNearestToWhereFocusWas() {
        val (fa, keys) = grid(16)
        fa.requestFocus(keys[5])
        // Remove the focused cell, every cell around it and the cell two rows down. Cell 7, two columns right,
        // is the nearest survivor to cell 5; cells 3, 11, 12 and 14 are all farther.
        fa.unregisterVirtualAll(listOf(0, 1, 2, 4, 5, 6, 8, 9, 10, 13).map(keys::get))
        assertEquals(keys[7], fa.state.value.focused)
    }

    @Test
    fun removingEverySlotLeavesFocusNull() {
        val (fa, keys) = grid(64)
        fa.requestFocus(keys[20])
        fa.unregisterVirtualAll(keys)
        assertNull(fa.state.value.focused)
        assertTrue(fa.registeredKeysInScope(scope).isEmpty())
    }

    @Test
    fun aComposedCellKeepsItsPhysicalRegistrationAndFocus() {
        val (fa, keys) = grid(8)
        fa.register(keys[2], scope, cell(2))
        fa.requestFocus(keys[2])
        fa.unregisterVirtualAll(keys)
        assertEquals(keys[2], fa.state.value.focused)
        assertEquals(listOf(keys[2]), fa.registeredKeysInScope(scope))
    }

    @Test
    fun removingSlotsThatDoNotHoldFocusLeavesFocusWhereItIs() {
        val (fa, keys) = grid(12)
        fa.requestFocus(keys[11])
        val before = fa.state.value
        fa.unregisterVirtualAll(keys.take(8))
        assertEquals(before, fa.state.value)
    }

    @Test
    fun aBatchAndOneByOneAgreeWhenTheFocusedSlotIsTheOnlyOneRemoved() {
        val (batch, batchKeys) = grid(16)
        val (single, singleKeys) = grid(16)
        batch.requestFocus(batchKeys[6])
        single.requestFocus(singleKeys[6])
        batch.unregisterVirtualAll(listOf(batchKeys[6]))
        single.unregisterVirtual(singleKeys[6])
        assertEquals(single.state.value.focused?.let { singleKeys.indexOf(it) },
            batch.state.value.focused?.let { batchKeys.indexOf(it) })
    }

    @Test
    fun clearingAFiveThousandSlotGridFromTheMiddleEndsWithNoFocusAndNoSlots() {
        val (fa, keys) = grid(5_529)
        fa.requestFocus(keys[2_000])
        fa.unregisterVirtualAll(keys)
        assertNull(fa.state.value.focused)
        assertTrue(fa.registeredKeysInScope(scope).isEmpty())
    }
}
