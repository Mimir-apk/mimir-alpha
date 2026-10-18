package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf-window memory: Home keeps one state (a single slot, as ever), the Library keeps many so that paging PS1, N64, PS1 puts
 * each platform back in the seat it was left in. Least recently used goes first, and a state being returned to counts as a use.
 */
class GameShelfWindowMemoryTest {

    @Test
    fun `the default memory is one slot, Home's, and the latest state displaces the last`() {
        val memory = GameShelfWindowMemory()
        memory.record("a", 3)
        memory.record("b", 5)
        assertNull("one slot: a is gone", memory.seed("a"))
        assertEquals(5, memory.seed("b"))
    }

    @Test
    fun `a memory of several states returns each to the seat it was left in`() {
        val memory = GameShelfWindowMemory(capacity = 3)
        memory.record("ps1", 4)
        memory.record("n64", 1)
        memory.record("dc", 7)
        assertEquals(4, memory.seed("ps1"))
        assertEquals(1, memory.seed("n64"))
        assertEquals(7, memory.seed("dc"))
        memory.record("ps1", 6)
        assertEquals("a state is overwritten by its own latest window", 6, memory.seed("ps1"))
    }

    @Test
    fun `the least recently used state goes first, and a state returned to counts as used`() {
        val memory = GameShelfWindowMemory(capacity = 2)
        memory.record("a", 1)
        memory.record("b", 2)
        assertEquals(1, memory.seed("a"))
        memory.record("c", 3)
        assertNull("b was the least recently used", memory.seed("b"))
        assertEquals(1, memory.seed("a"))
        assertEquals(3, memory.seed("c"))
    }

    @Test
    fun `a state never recorded starts afresh`() {
        assertNull(GameShelfWindowMemory(capacity = 4).seed(Triple("ps1", false, "")))
    }

    @Test
    fun `the Library's memory holds a state for each platform with room for searches, and Home's is a single slot`() {
        assertTrue(LibraryGameShelfWindowCapacity >= 16)
        val library = LibraryGameShelfWindowMemory.memory
        val platforms = listOf("ps1", "n64", "dc", "gba", "psp", "saturn", "snes", "nes")
        platforms.forEachIndexed { index, id -> library.record(Triple(id, false, "memory-test"), index + 1) }
        platforms.forEachIndexed { index, id -> assertEquals(index + 1, library.seed(Triple(id, false, "memory-test"))) }
        val home = HomeShelfWindowMemory.memory
        home.record("memory-test-a", 2)
        home.record("memory-test-b", 3)
        assertNull("Home remembers its one state", home.seed("memory-test-a"))
    }

    @Test
    fun `typing a search is one remembered state however long it grows, and leaves the browsed platforms where they were left`() {
        val memory = GameShelfWindowMemory(capacity = LibraryGameShelfWindowCapacity)
        // Thirty-one platforms browsed, each left in its own seat; with the search's one state that is exactly the memory's capacity.
        val platforms = (1 until LibraryGameShelfWindowCapacity).map { PlatformId("platform-$it") }
        platforms.forEachIndexed { index, id -> memory.record(libraryShelfWindowMemoryKey(id, false, searching = false), index + 1) }
        // A seven-letter search, one letter at a time, then a second one: a state per typed prefix would be fourteen new entries.
        for (query in listOf("zelda64", "mariokt")) {
            for (typed in query.indices.map { query.substring(0, it + 1) }) {
                memory.record(libraryShelfWindowMemoryKey(PlatformId("platform-1"), false, searching = typed.isNotBlank()), 0)
            }
        }
        // Leaving the search lands every browsed platform where it was left, the first one browsed included.
        platforms.forEachIndexed { index, id ->
            assertEquals("${id.value} is still where it was left", index + 1, memory.seed(libraryShelfWindowMemoryKey(id, false, searching = false)))
        }
    }

    @Test
    fun `a search is told apart from no search, a platform from another, and the favourites filter from the list`() {
        val ps1 = PlatformId.PS1
        val typed = libraryShelfWindowMemoryKey(ps1, false, searching = true)
        // The screen says "searching" from the criteria (a query typed, or a genre or platform filter), never from the query's text, so every prefix of a query is this one state.
        assertEquals("a search is one state whatever is typed", typed, libraryShelfWindowMemoryKey(ps1, false, searching = true))
        assertNotEquals("a search is not the platform's own list", libraryShelfWindowMemoryKey(ps1, false, searching = false), typed)
        assertNotEquals("another platform is another state", libraryShelfWindowMemoryKey(PlatformId.GBA, false, searching = true), typed)
        assertNotEquals("the favourites filter is another state", libraryShelfWindowMemoryKey(ps1, true, searching = true), typed)
    }
}
