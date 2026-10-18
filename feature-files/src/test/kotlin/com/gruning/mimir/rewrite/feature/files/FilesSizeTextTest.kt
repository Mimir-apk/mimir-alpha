package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A drive's free space and the Search filter chips on display (localization step 4b). core-files still writes the English
 * (`FileItem.volumeCapacity`, `volumeCapacityCompact`, `FileSearchFilter.label`); these tests walk the cases against it so the resource English stays word for
 * word the same, and read the Spanish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesSizeTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    private val gib = 1024L * 1024L * 1024L

    private fun drive(free: Long?, total: Long?) =
        FileItem(name = "SD card", path = "/storage/ABCD", isDirectory = true, childCount = 7, volumeFreeBytes = free, volumeTotalBytes = total)

    private val volumes = listOf(
        drive(free = 119L * gib + 400L * 1024L * 1024L, total = 238L * gib + 300L * 1024L * 1024L),
        drive(free = 512L, total = 64L * gib),
        drive(free = 317L * gib + gib / 2, total = 435L * gib),
        drive(free = gib - 1L, total = 2L * gib),
        drive(free = 0L, total = 0L),
        drive(free = -1L, total = gib),
        drive(free = gib, total = null),
        drive(free = null, total = gib),
    )

    @Test
    fun `a drive's free space reads as core-files wrote it in English`() {
        volumes.forEach { drive ->
            assertEquals("full: free=${drive.volumeFreeBytes} total=${drive.volumeTotalBytes}", drive.volumeCapacity, fileBrowserVolumeCapacityText(english, drive, compact = false))
            assertEquals("compact: free=${drive.volumeFreeBytes} total=${drive.volumeTotalBytes}", drive.volumeCapacityCompact, fileBrowserVolumeCapacityText(english, drive, compact = true))
            assertEquals("the row's size line", drive.displaySize, fileBrowserSizeText(english, drive))
        }
    }

    @Test
    fun `a drive's free space reads in Spanish`() {
        val full = drive(free = 119L * gib, total = 238L * gib)
        assertEquals("${FileItem.formatSize(119L * gib)} libres de ${FileItem.formatSize(238L * gib)}", fileBrowserVolumeCapacityText(spanish, full, compact = false))
        assertEquals("119 GB libres", fileBrowserVolumeCapacityText(spanish, full, compact = true))
        assertEquals("512 B libres", fileBrowserVolumeCapacityText(spanish, drive(free = 512L, total = gib), compact = true))
        assertNull("a volume whose figures failed says nothing", fileBrowserVolumeCapacityText(spanish, drive(free = 0L, total = 0L), compact = false))
        assertNull("a folder is not a drive", fileBrowserVolumeCapacityText(spanish, FileItem(name = "Roms", path = "/r", isDirectory = true), compact = false))
    }

    @Test
    fun `the drive tile's caption keeps the short form and the inspector the whole`() {
        val tile = FileBrowserTile(drive(free = 317L * gib, total = 435L * gib), FileBrowserTileKind.DIRECTORY, machinery = false, platformIdValue = null)
        assertEquals("317 GB free", fileBrowserTileMeta(english, tile))
        assertEquals("317 GB libres", fileBrowserTileMeta(spanish, tile))
        assertEquals("${FileItem.formatSize(317L * gib)} libres de ${FileItem.formatSize(435L * gib)}", fileBrowserSizeText(spanish, tile.item))
    }

    @Test
    fun `the Search chips read as core-files wrote them in English and in Spanish`() {
        FileSearchFilter.entries.forEach { filter ->
            assertEquals(filter.name, filter.label, fileBrowserSearchFilterLabel(english, filter))
        }
        assertEquals(
            listOf("Todos", "Juegos", "Comprimidos", "BIOS", "Guardados"),
            FileSearchFilter.entries.map { fileBrowserSearchFilterLabel(spanish, it) },
        )
    }
}
