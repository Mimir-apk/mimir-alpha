package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The title rail shortens at a word boundary, never inside a word ("Unbel..." was the defect). One character is one unit of room. */
class ShelfTitleEllipsisTest {

    private fun cut(text: String, room: Int) = ellipsizeAtWord(text) { it.length <= room }

    @Test
    fun `a title that fits is returned whole`() {
        assertEquals("The Legend of Zelda", cut("The Legend of Zelda", 19))
        assertEquals("The Legend of Zelda", cut("The Legend of Zelda", 40))
        assertEquals("", cut("", 0))
    }

    @Test
    fun `a title that does not fit keeps whole words and ends in an ellipsis`() {
        // "The Unbelievably Long Title" cut for 12 units: "The" + ellipsis (4) fits, "The Unbelievably" does not.
        assertEquals("The\u2026", cut("The Unbelievably Long Title", 12))
        assertEquals("The Unbelievably\u2026", cut("The Unbelievably Long Title", 17))
        // The longest run that fits wins.
        assertEquals("The Unbelievably Long\u2026", cut("The Unbelievably Long Title", 22))
    }

    @Test
    fun `the cut never falls inside a word, whatever the room`() {
        val title = "Supercalifragilisticexpialidocious Adventures of the Unbelievably Long Titled Hedgehog Collector's Edition"
        for (room in 1..title.length) {
            val shown = cut(title, room)
            if (shown == title) continue
            if (!shown.endsWith("\u2026")) {
                // No whole word fits at all: the title comes back whole for the caller's own overflow, the one cut a word cannot avoid.
                assertEquals("room $room", title, shown)
                continue
            }
            val head = shown.removeSuffix("\u2026")
            assertTrue("room $room: <$shown> starts the title", title.startsWith(head))
            assertTrue("room $room: <$shown> is cut between words", title[head.length].isWhitespace())
            assertTrue("room $room: <$shown> fits", shown.length <= room)
        }
    }

    @Test
    fun `a separator the cut would leave dangling is dropped`() {
        assertEquals("Zelda\u2026", cut("Zelda: Breath of the Wild", 9))
        assertEquals("RPG\u2026", cut("RPG \u00B7 Dolphin \u00B7 Played today", 7))
        assertEquals("Mario\u2026", cut("Mario & Luigi Superstar Saga", 9))
        assertEquals("Fire\u2026", cut("Fire (Remastered Edition)", 9))
    }

    @Test
    fun `a first word wider than the room has no whole-word answer and comes back whole`() {
        assertEquals("Supercalifragilisticexpialidocious Adventures", cut("Supercalifragilisticexpialidocious Adventures", 10))
        assertEquals("Unbelievable", cut("Unbelievable", 6))
    }
}
