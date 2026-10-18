package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy: no scale on focus. A focused Tome in the Tome library once grew 1.03 (Home) / 1.02
 * (compact grid) through MimirNorseBookCover while the Create cell beside it only took the ring, so one
 * grid had two focus behaviours. Both HomeTomeLibraryCell call sites now pass `focusScale = 1f`.
 */
class HomeTomeLibraryNoFocusGrowTest {

    private val source: String by lazy {
        val root = listOf(File("."), File("..")).first { File(it, "settings.gradle.kts").isFile }
        File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/HomeTomeLibraryOverlay.kt").readText()
    }

    @Test
    fun `no tome cell passes a grow scale`() {
        assertFalse(Regex("""focusScale\s*=\s*HomeTomeLibrary(Compact)?FocusScale\b""").containsMatchIn(source))
    }

    @Test
    fun `both tome library cells pass focusScale 1f`() {
        // Call sites only: the declaration is `fun HomeTomeLibraryCell(`.
        val calls = Regex("""(?<!fun )HomeTomeLibraryCell\(""").findAll(source)
            .map { source.substring(it.range.last).substringBefore("onActivate") }
            .toList()
        assertEquals(2, calls.size)
        calls.forEach { args ->
            assertTrue(args, Regex("""\bfocusScale\s*=\s*1f\b""").containsMatchIn(args))
        }
    }
}
