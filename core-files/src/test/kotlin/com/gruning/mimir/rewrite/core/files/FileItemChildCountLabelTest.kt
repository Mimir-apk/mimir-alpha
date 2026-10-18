package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Child-count regression: a folder holding one thing read "1 items" in Files and in the file picker,
 * both of which draw [FileItem.displaySize]. The singular is the only change; the null-vs-zero rule stands.
 */
class FileItemChildCountLabelTest {

    private fun folder(children: Int?) =
        FileItem(name = "Roms", path = "/storage/emulated/0/Roms", isDirectory = true, childCount = children)

    @Test
    fun `a folder with one child reads 1 item`() {
        assertEquals("1 item", folder(1).displaySize)
    }

    @Test
    fun `a folder with two children keeps the plural`() {
        assertEquals("2 items", folder(2).displaySize)
    }

    @Test
    fun `an empty folder is still 0 items and an unreadable one still says nothing`() {
        assertEquals("0 items", folder(0).displaySize)
        assertEquals("", folder(null).displaySize)
    }
}
