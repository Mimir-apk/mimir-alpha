package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 *
 *
 * The drive chooser could only say **Folder · Modified Unknown · Not a platform folder** about a 256 GB
 * card. The numbers were never missing — `StorageVolumeHelper.getStorageVolumes()` runs a `StatFs` per
 * volume and fills [StorageVolume.totalBytes]/[StorageVolume.freeBytes] — [StorageVolumeListing.volumeRows]
 * simply discarded them when mapping to [FileItem]. These pins hold the values on the row and hold
 * [FileItem.displaySize]'s precedence, because a volume row is ALSO a directory and would otherwise report
 * its top-level child count: true, and not what anyone wants to read about a drive.
 */
class VolumeCapacityDisplayTest {

    private val gb = 1024L * 1024L * 1024L

    private fun volume(name: String, total: Long, free: Long, primary: Boolean = false) =
        StorageVolume(
            name = name,
            path = "/storage/$name",
            totalBytes = total,
            freeBytes = free,
            isRemovable = !primary,
            isPrimary = primary,
        )

    @Test
    fun `a drive row carries the capacity its StatFs already measured`() {
        val rows = StorageVolumeListing.volumeRows(
            listOf(volume("Internal", total = 238 * gb, free = 119 * gb, primary = true)),
        )

        assertEquals(1, rows.size)
        assertEquals(119 * gb, rows.single().volumeFreeBytes)
        assertEquals(238 * gb, rows.single().volumeTotalBytes)
    }

    @Test
    fun `and reads as free-of-total rather than as a child count`() {
        val row = StorageVolumeListing.volumeRows(
            listOf(volume("Thor", total = 238 * gb, free = 119 * gb)),
        ).single().copy(childCount = 17)

        assertEquals("119.00 GB free of 238.00 GB", row.volumeCapacity)
        assertEquals(
            "a volume row is also a directory, so childCount would win without the explicit precedence " +
                "in displaySize -- and \"17 items\" is a true statement nobody wants about a drive",
            "119.00 GB free of 238.00 GB",
            row.displaySize,
        )
    }

    @Test
    fun `an ordinary folder is untouched - no capacity, still its child count`() {
        val folder = FileItem(name = "Roms", path = "/storage/emulated/0/Roms", isDirectory = true, childCount = 146)

        assertNull(folder.volumeCapacity)
        assertEquals("146 items", folder.displaySize)
    }

    @Test
    fun `a volume whose StatFs failed says nothing rather than zero`() {
        val row = StorageVolumeListing.volumeRows(listOf(volume("Broken", total = 0L, free = 0L))).single()

        assertNull(
            "\"0 B free of 0 B\" is a worse answer than silence -- same null-vs-zero rule childCount " +
                "already follows, where \"0 items\" and \"cannot read this folder\" are different claims",
            row.volumeCapacity,
        )
        assertEquals("", row.displaySize)
    }

    @Test
    fun `the tile form drops the total and the decimals so it fits the caption lane`() {
        val row = StorageVolumeListing.volumeRows(
            listOf(volume("Thor", total = 238 * gb, free = 119 * gb)),
        ).single()

        assertEquals(
            "the full form truncated to \"317.49 GB free of 43...\" on the Odin the first time this " +
                "shipped -- a worse answer than the short one, and the exact figure is one focus away",
            "119 GB free",
            row.volumeCapacityCompact,
        )
        assertEquals("119.00 GB free of 238.00 GB", row.volumeCapacity)
    }

    @Test
    fun `a nearly full drive still reports honestly in the tile form`() {
        val row = StorageVolumeListing.volumeRows(
            listOf(volume("Full", total = 238 * gb, free = 512L * 1024L * 1024L)),
        ).single()

        assertEquals("512.0 MB free", row.volumeCapacityCompact)
    }

    @Test
    fun `half a pair is not a capacity`() {
        val onlyTotal = FileItem(name = "x", path = "/x", isDirectory = true, volumeTotalBytes = 238 * gb)
        val onlyFree = FileItem(name = "x", path = "/x", isDirectory = true, volumeFreeBytes = 119 * gb)

        assertNull(onlyTotal.volumeCapacity)
        assertNull(onlyFree.volumeCapacity)
        assertNull(onlyTotal.volumeCapacityCompact)
        assertNull(onlyFree.volumeCapacityCompact)
    }
}
