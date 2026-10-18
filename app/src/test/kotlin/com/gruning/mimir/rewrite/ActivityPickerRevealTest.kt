package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared picker's list scrolls a lit row that shows only in part, and only for the host that lends its lit key (the Customize media page).
 * The hub passes none: it brings its own row into view with a glide of its own, and a second scroll from here would cancel or replace it.
 */
class ActivityPickerRevealTest {

    @Test
    fun `a row that is whole with its air clear needs no scroll`() {
        // A viewport of 0..667 px, a 49 px row, 28 px of air.
        assertEquals(0f, activityPickerRevealDelta(200, 49, 0, 667, 28f), 0f)
        assertEquals("flush against the clear line below is whole", 0f, activityPickerRevealDelta(667 - 28 - 49, 49, 0, 667, 28f), 0f)
        assertEquals("flush against the clear line above is whole", 0f, activityPickerRevealDelta(28, 49, 0, 667, 28f), 0f)
    }

    @Test
    fun `a row cut by the end scrolls forward by exactly what is cut, air included`() {
        // The seventh row at font 1.3: its foot is 10 px past the viewport's end, and the ring wants 28 px more.
        assertEquals(38f, activityPickerRevealDelta(628, 49, 0, 667, 28f), 0f)
        assertEquals("with no air, just the overhang", 10f, activityPickerRevealDelta(628, 49, 0, 667, 0f), 0f)
    }

    @Test
    fun `a row cut by the start scrolls back by what is cut, air included`() {
        // The viewport starts at -28 (the list's top padding), so a row at 0 stands exactly its air from the clip.
        assertEquals(0f, activityPickerRevealDelta(0, 49, -28, 667, 28f), 0f)
        assertEquals(-2f, activityPickerRevealDelta(-2, 49, -28, 667, 28f), 0f)
        assertEquals(-30f, activityPickerRevealDelta(-30, 49, -28, 667, 28f), 0f)
        assertEquals("with no air, just the overhang", -9f, activityPickerRevealDelta(-9, 49, 0, 667, 0f), 0f)
    }

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()

    private fun code(text: String) = text.lines()
        .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
        .joinToString(System.lineSeparator())

    @Test
    fun `the part-visible scroll runs only when the host lends a lit key, so the hub's own glide is not replaced`() {
        val rows = code(
            source("CompanionActivityAchievementFilterHeader.kt")
                .substringAfter("private fun ColumnScope.ActivityPickerRows(")
                .substringBefore("internal fun activityPickerRevealDelta("),
        )
        val scrollCalls = rows.split("scrollBy(").size - 1
        assertEquals("one scrollBy in the list's effect", 1, scrollCalls)
        val guard = rows.indexOf("litKey != null")
        assertTrue("the guard is there", guard >= 0)
        assertTrue("and the scroll is under it", rows.indexOf("scrollBy(") > guard)
        assertTrue("the scroll to an uncomposed row stays for both hosts", rows.indexOf("scrollToItem(litIndex)") in 0 until guard)
    }

    @Test
    fun `no hub caller passes a lit key`() {
        val dir = listOf(File("app/src/main/kotlin/com/gruning/mimir/rewrite"), File("src/main/kotlin/com/gruning/mimir/rewrite")).first(File::isDirectory)
        val passing = dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "CompanionActivityAchievementFilterHeader.kt" }!!
            .filter { code(it.readText()).contains("litKey = ") }
            .map { it.name }
            .sorted()
        assertEquals("only the Customize media page's panels lend a lit key", listOf("CustomMediaTopRow.kt"), passing)
        // The picker itself only forwards what it is given.
        val header = code(source("CompanionActivityAchievementFilterHeader.kt"))
        assertEquals("one forwarding call, and no value of its own", 1, header.split("litKey = ").size - 1)
        assertTrue(header.contains("litKey = litKey"))
        assertFalse(code(source("CompanionActivityAchievementHub.kt")).contains("litKey"))
    }
}
