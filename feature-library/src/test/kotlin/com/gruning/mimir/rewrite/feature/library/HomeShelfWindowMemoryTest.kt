package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B from the Library to Home: Home is composed afresh and its window used to be re-derived from 0,
 * putting the focused tile in a different seat. It is seeded from where this surface's window last stood.
 */
class HomeShelfWindowMemoryTest {

    @Test
    fun `the window is seeded only for the surface it was recorded on`() {
        HomeShelfWindowMemory.record(surfaceEpoch = 4, surfaceLabel = "Editor's Choice", windowStart = 6)
        assertEquals(6, HomeShelfWindowMemory.seed(4, "Editor's Choice"))
        assertNull("another surface starts afresh", HomeShelfWindowMemory.seed(5, "Editor's Choice"))
        assertNull(HomeShelfWindowMemory.seed(4, "Pinned Tomes"))
    }

    @Test
    fun `the shelf seeds its window holder and records every resolved start`() {
        // The window moved from HomeShelfScreen.kt into the shared strip (GameShelf.kt, 2026-10-10): the strip reads and
        // records on the memory it is handed, and Home hands it Home's own memory under Home's own key.
        val source = shelfSource("GameShelf.kt")
        assertTrue(source.contains("(windowMemory.seed(windowMemoryKey) ?: 0)"))
        assertTrue(source.contains("SideEffect { windowMemory.record(windowMemoryKey, windowStart) }"))
        // The unresolved-selection rule (UI-02) still holds the last resolved start.
        assertTrue(source.contains("?: windowStartHolder.value"))
        val home = shelfSource("HomeShelfScreen.kt")
        // The memory and its key are given once, to the state Home builds; the shelf is handed that state and no copy of either.
        assertEquals(
            "the state is given Home's memory, once",
            1,
            Regex("""windowMemory = HomeShelfWindowMemory\.memory,""").findAll(home).count(),
        )
        assertEquals(
            1,
            Regex("""windowMemoryKey = HomeShelfWindowMemory\.key\(surfaceEpoch, surfaceLabel\),""").findAll(home).count(),
        )
    }

    private fun shelfSource(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    ).first(File::isFile).readText()
}
