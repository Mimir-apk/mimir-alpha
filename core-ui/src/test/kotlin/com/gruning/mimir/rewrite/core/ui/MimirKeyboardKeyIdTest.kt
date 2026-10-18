package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirKeyboardKeyIdTest {

    @Test
    fun actionKeysUseStableSemanticIds() {
        assertEquals("action/done", MimirKeyboardKeyIds.Done.value)
        assertEquals("action/shift", MimirKeyboardKeyIds.Shift.value)
        assertEquals("action/space", MimirKeyboardKeyIds.Space.value)
        assertEquals("char/q", MimirKeyboardKeyIds.character("Q").value)
    }

    @Test
    fun letterLayoutPreservesCharacterIdsAcrossShiftToggle() {
        val lower = mimirKeyboardLayoutKeyIds(shiftOn = false, characterSet = MimirKeyboardCharacterSet.ALPHA)
        val upper = mimirKeyboardLayoutKeyIds(shiftOn = true, characterSet = MimirKeyboardCharacterSet.ALPHA)
        assertEquals(lower.filter { it.value.startsWith("char/") }, upper.filter { it.value.startsWith("char/") })
        assertTrue(lower.contains(MimirKeyboardKeyIds.character("q")))
        assertTrue(upper.contains(MimirKeyboardKeyIds.Shift))
    }

    @Test
    fun symbolLayoutRetainsSharedActionIds() {
        val letters = mimirKeyboardLayoutKeyIds(shiftOn = false, characterSet = MimirKeyboardCharacterSet.ALPHA)
        val symbols = mimirKeyboardLayoutKeyIds(shiftOn = false, characterSet = MimirKeyboardCharacterSet.SYMBOLS)
        val sharedActions = setOf(
            MimirKeyboardKeyIds.Back,
            MimirKeyboardKeyIds.Paste,
            MimirKeyboardKeyIds.Space,
            MimirKeyboardKeyIds.Done,
        )
        sharedActions.forEach { id ->
            assertTrue("$id missing from letters", letters.contains(id))
            assertTrue("$id missing from symbols", symbols.contains(id))
        }
        assertTrue(symbols.contains(MimirKeyboardKeyIds.Abc))
        assertTrue(letters.contains(MimirKeyboardKeyIds.Symbols))
    }

    @Test
    fun numberLayoutIsAThirdDistinctSetSharingTheActionIds() {
        val numbers = mimirKeyboardLayoutKeyIds(shiftOn = false, characterSet = MimirKeyboardCharacterSet.NUMBERS)
        val symbols = mimirKeyboardLayoutKeyIds(shiftOn = false, characterSet = MimirKeyboardCharacterSet.SYMBOLS)
        val sharedActions = setOf(
            MimirKeyboardKeyIds.Back,
            MimirKeyboardKeyIds.Paste,
            MimirKeyboardKeyIds.Space,
            MimirKeyboardKeyIds.Done,
        )
        sharedActions.forEach { id -> assertTrue("$id missing from numbers", numbers.contains(id)) }
        assertTrue(numbers.contains(MimirKeyboardKeyIds.character("1")))
        assertTrue(numbers.contains(MimirKeyboardKeyIds.Symbols))
        assertTrue(numbers.contains(MimirKeyboardKeyIds.Abc))
        // NUMBERS is a genuinely distinct set from both ALPHA (no letters) and SYMBOLS (no @/#/$/&/!/?).
        assertTrue(numbers.none { it.value.startsWith("char/") && it.value.removePrefix("char/").any(Char::isLetter) })
        assertTrue(symbols.contains(MimirKeyboardKeyIds.character("@")))
        assertTrue(numbers.none { it == MimirKeyboardKeyIds.character("@") })
    }

    @Test
    fun characterSetCyclesAlphaToNumbersToSymbolsAndWrapsBack() {
        assertEquals(MimirKeyboardCharacterSet.NUMBERS, MimirKeyboardCharacterSet.ALPHA.next())
        assertEquals(MimirKeyboardCharacterSet.SYMBOLS, MimirKeyboardCharacterSet.NUMBERS.next())
        assertEquals(MimirKeyboardCharacterSet.ALPHA, MimirKeyboardCharacterSet.SYMBOLS.next())
    }

    // ── mimirKeyboardLayoutRows (row-based LEFT/RIGHT): pins the RAW ROW
    // DATA that core-input's MimirKeyboardFocusBinding.registerRowLayout consumes for row-index
    // navigation, independent of the dispatch mechanism itself (covered in core-input's
    // MimirKeyboardFocusTest.kt). ──

    @Test
    fun alphaLayoutRowsPlacePasteImmediatelyBeforeSpaceInTheSameRow_ownersExample() {
        val rows = mimirKeyboardLayoutRows(MimirKeyboardCharacterSet.ALPHA)
        val pasteRow = rows.firstOrNull { MimirKeyboardKeyIds.Paste.value in it }
        assertEquals(
            "paste and space must sit in the SAME row for RIGHT-from-paste to reach spacebar",
            pasteRow,
            rows.firstOrNull { MimirKeyboardKeyIds.Space.value in it },
        )
        requireNotNull(pasteRow)
        val pasteIndex = pasteRow.indexOf(MimirKeyboardKeyIds.Paste.value)
        assertEquals(
            "space must be the very next key after paste",
            MimirKeyboardKeyIds.Space.value,
            pasteRow[pasteIndex + 1],
        )
    }

    @Test
    fun numberLayoutRowsAlsoPlacePasteImmediatelyBeforeSpace() {
        // All keyboard layouts need the same correction; pin the
        // same relationship on NUMBER_ROWS, whose last row is [paste, space, done], a different shape
        // (3 keys, not 4) from ALPHA's [symbols, paste, space, done].
        val rows = mimirKeyboardLayoutRows(MimirKeyboardCharacterSet.NUMBERS)
        val pasteRow = rows.last { MimirKeyboardKeyIds.Paste.value in it }
        val pasteIndex = pasteRow.indexOf(MimirKeyboardKeyIds.Paste.value)
        assertEquals(MimirKeyboardKeyIds.Space.value, pasteRow[pasteIndex + 1])
        assertEquals(3, pasteRow.size)
    }

    @Test
    fun everyRowIsNonEmptyAndEveryLayoutKeyAppearsInExactlyOneRow() {
        MimirKeyboardCharacterSet.entries.forEach { characterSet ->
            val rows = mimirKeyboardLayoutRows(characterSet)
            rows.forEach { row -> assertTrue("$characterSet has an empty row", row.isNotEmpty()) }
            val flattened = rows.flatten()
            assertEquals(
                "$characterSet: every key must appear in exactly one row (no duplicates across rows)",
                flattened.size,
                flattened.toSet().size,
            )
            assertEquals(
                "$characterSet: mimirKeyboardLayoutRows must cover the same key set as mimirKeyboardLayoutKeyIds",
                mimirKeyboardLayoutKeyIds(shiftOn = false, characterSet = characterSet).map { it.value }.toSet(),
                flattened.toSet(),
            )
        }
    }

    @Test
    fun rowStructureIsShiftInvariant() {
        // mimirKeyboardLayoutRows takes no shiftOn parameter by design (fixed to false internally) —
        // this pins the reason why that is safe: a key's ID (hence its row/position) never changes with
        // shift, only its rendered label/value does (mimirKeyboardKeyId uses the pre-uppercase `value`).
        val rows = mimirKeyboardLayoutRows(MimirKeyboardCharacterSet.ALPHA)
        val lowerIds = mimirKeyboardLayoutKeyIds(shiftOn = false, characterSet = MimirKeyboardCharacterSet.ALPHA).map { it.value }
        val upperIds = mimirKeyboardLayoutKeyIds(shiftOn = true, characterSet = MimirKeyboardCharacterSet.ALPHA).map { it.value }
        assertEquals(lowerIds, upperIds)
        assertEquals(lowerIds, rows.flatten())
    }
}
