package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [StorageVolumeListing], preventing a first-root-only browser from hiding other drives.
 *
 * Everything here is pure: [StorageVolumeHelper] itself needs a live Android `Context`
 * (`StorageManager`/`StatFs`) and this module configures no Robolectric, so the helper's own
 * enumeration is NOT covered by these tests — only the string arithmetic and the row/root policy it
 * delegates to. That boundary is the point of the split; it is not an omission being papered over.
 */
class StorageVolumeListingTest {

    private fun internal(path: String = "/storage/emulated/0") =
        StorageVolume(name = "Internal Storage", path = path, isPrimary = true, isRemovable = false)

    private fun sdCard(id: String = "1A2B-3C4D") =
        StorageVolume(name = "SD Card ($id)", path = "/storage/$id", isPrimary = false, isRemovable = true)

    // ── browserRoot: the defect itself ────────────────────────────────────────

    @Test
    fun `two volumes root the browser at the volume chooser rather than the first volume`() {
        val root = StorageVolumeListing.browserRoot(listOf(internal(), sdCard()))

        // The defect, stated as a test: rooting at the FIRST volume is exactly what made the SD card
        // unreachable, because the browser cannot navigate above its own root.
        assertEquals(
            "a device with two drives must open at the chooser, not at the first drive",
            StorageVolumeListing.VOLUME_ROOT_PATH,
            root,
        )
    }

    @Test
    fun `a single volume roots the browser directly at it, with no chooser`() {
        val root = StorageVolumeListing.browserRoot(listOf(internal()))

        assertEquals(
            "a one-drive device must behave exactly as it did before the chooser existed",
            "/storage/emulated/0",
            root,
        )
    }

    @Test
    fun `no enumerable volume falls back to primary external storage`() {
        assertEquals(
            StorageVolumeListing.PRIMARY_FALLBACK_PATH,
            StorageVolumeListing.browserRoot(emptyList()),
        )
    }

    @Test
    fun `browserRoot never shows a chooser that volumeRows would render with one row`() {
        // Two StorageVolumes, one surviving row: the same mount point reported twice (a plausible
        // getExternalFilesDirs shape) must not produce a chooser whose only choice is "here".
        val duplicated = listOf(internal(), internal().copy(name = "Internal Storage", isPrimary = false))

        val rows = StorageVolumeListing.volumeRows(duplicated)
        val root = StorageVolumeListing.browserRoot(duplicated)

        assertEquals(1, rows.size)
        assertEquals(
            "root and listing must agree: one row means root at that row, never at the chooser",
            rows.single().path,
            root,
        )
    }

    // ── volumeRows: what the chooser actually renders ─────────────────────────

    @Test
    fun `volume rows are directories at their real mount points`() {
        val rows = StorageVolumeListing.volumeRows(listOf(internal(), sdCard()))

        assertEquals(2, rows.size)
        assertTrue("every chooser row must be a directory", rows.all { it.isDirectory })
        assertEquals(listOf("/storage/emulated/0", "/storage/1A2B-3C4D"), rows.map { it.path })
        assertEquals(listOf("Internal Storage", "SD Card (1A2B-3C4D)"), rows.map { it.name })
    }

    @Test
    fun `primary storage is listed first regardless of enumeration order`() {
        val rows = StorageVolumeListing.volumeRows(listOf(sdCard("ZZZZ-0000"), internal()))

        assertEquals(
            "primary storage leads the chooser however the platform ordered the volumes",
            "/storage/emulated/0",
            rows.first().path,
        )
    }

    @Test
    fun `non-primary volumes are ordered by name, not by enumeration order`() {
        val rows = StorageVolumeListing.volumeRows(
            listOf(sdCard("ZZZZ-9999"), internal(), sdCard("AAAA-1111")),
        )

        assertEquals(
            listOf("Internal Storage", "SD Card (AAAA-1111)", "SD Card (ZZZZ-9999)"),
            rows.map { it.name },
        )
    }

    @Test
    fun `a volume label containing a dot is not given a file extension`() {
        // FileItem derives `extension` from the NAME by default. A volume name is a label, not a
        // filename, so a dotted label must not arrive downstream looking like "Card.2" -> extension "2".
        val rows = StorageVolumeListing.volumeRows(
            listOf(StorageVolume(name = "Card.2", path = "/storage/AAAA-2222")),
        )

        assertEquals("", rows.single().extension)
    }

    @Test
    fun `a plumbing mount is never offered as a drive`() {
        val rows = StorageVolumeListing.volumeRows(
            listOf(internal(), StorageVolume(name = "emulated", path = "/storage/emulated")),
        )

        assertEquals(listOf("/storage/emulated/0"), rows.map { it.path })
    }

    @Test
    fun `a volume with no path is dropped rather than rendered as an unenterable row`() {
        val rows = StorageVolumeListing.volumeRows(
            listOf(internal(), StorageVolume(name = "Unknown", path = "")),
        )

        assertEquals(listOf("/storage/emulated/0"), rows.map { it.path })
    }

    /**
     * Cross-check with [LocalListingPolicy], not a restatement of it: this is WHY the chooser has to be
     * synthesised instead of being a real `listFiles("/storage")`. Ruling 2 correctly hides
     * `/storage/emulated`, and internal storage lives one level below it, so a genuine listing of
     * `/storage` would show the SD card and hide internal storage — the exact inverse of the defect.
     */
    @Test
    fun `a real listing of the volume root would hide internal storage, which is why rows are synthesised`() {
        assertTrue(LocalListingPolicy.isPlumbingPath("/storage/emulated"))
        assertFalse(LocalListingPolicy.isPlumbingPath("/storage/emulated/0"))
        assertFalse(LocalListingPolicy.isPlumbingPath("/storage/1A2B-3C4D"))
    }

    // ── crumbLabels: policy 3, the breadcrumb trail's real names ────────

    /**
     * `FileBrowserSurface(crumbLabels = …)` shipped empty, so the trail
     * read `storage › emulated › 0 › Roms` — every crumb a plumbing basename. The target is
     * `Drives › SD Card › Roms`, and this is the map that produces it.
     */
    @Test
    fun `crumb labels name every drive the chooser offers, plus the chooser itself`() {
        val labels = StorageVolumeListing.crumbLabels(listOf(internal(), sdCard()))

        assertEquals(
            "each volume crumb must carry the volume's own name, keyed by its real mount point",
            "SD Card (1A2B-3C4D)",
            labels["/storage/1A2B-3C4D"],
        )
        assertEquals("Internal Storage", labels["/storage/emulated/0"])
        assertEquals(
            "the chooser's own crumb must read \"Drives\", never the basename \"storage\"",
            "Drives",
            labels[StorageVolumeListing.VOLUME_ROOT_PATH],
        )
        assertEquals(3, labels.size)
    }

    /**
     * The [VOLUME_ROOT_PATH] entry is unconditional, including on a single-drive device where the
     * chooser is never reachable. Harmless there (that path never enters the trail, so it is never
     * looked up) and required the moment a second drive appears — a conditional entry would be a second
     * place that has to agree with [browserRoot] about how many drives there are.
     */
    @Test
    fun `the chooser label is present even when one drive means no chooser is reachable`() {
        val labels = StorageVolumeListing.crumbLabels(listOf(internal()))

        assertEquals("Drives", labels[StorageVolumeListing.VOLUME_ROOT_PATH])
        assertEquals("Internal Storage", labels["/storage/emulated/0"])
        assertEquals(2, labels.size)
    }

    @Test
    fun `no enumerable volume still names the chooser, and invents no drive`() {
        val labels = StorageVolumeListing.crumbLabels(emptyList())

        assertEquals(mapOf(StorageVolumeListing.VOLUME_ROOT_PATH to "Drives"), labels)
    }

    /**
     * Labels must describe exactly the rows the chooser shows. A volume [volumeRows] filters out —
     * plumbing, or pathless — must not leave a label behind for a row that is not rendered, or the trail
     * would be able to name a drive the player can never reach.
     */
    @Test
    fun `a filtered-out volume leaves no label behind`() {
        val labels = StorageVolumeListing.crumbLabels(
            listOf(
                internal(),
                StorageVolume(name = "emulated", path = "/storage/emulated"),
                StorageVolume(name = "Unknown", path = ""),
            ),
        )

        assertFalse(
            "a plumbing mount is not offered as a drive, so it must not be named as one either",
            labels.containsKey("/storage/emulated"),
        )
        assertEquals(
            setOf("/storage/emulated/0", StorageVolumeListing.VOLUME_ROOT_PATH),
            labels.keys,
        )
    }

    // ── isVolumeRoot ──────────────────────────────────────────────────────────

    @Test
    fun `the volume root is recognised with or without a trailing slash`() {
        assertTrue("bare /storage is the volume chooser", StorageVolumeListing.isVolumeRoot("/storage"))
        assertTrue(
            "a trailing slash survives a File/Path round-trip and must not hide the chooser",
            StorageVolumeListing.isVolumeRoot("/storage/"),
        )
    }

    @Test
    fun `real directories are not the volume root`() {
        assertFalse(StorageVolumeListing.isVolumeRoot("/storage/emulated/0"))
        assertFalse(StorageVolumeListing.isVolumeRoot("/storage/1A2B-3C4D"))
        assertFalse("the filesystem root is a real, listable directory", StorageVolumeListing.isVolumeRoot("/"))
        assertFalse(StorageVolumeListing.isVolumeRoot(""))
        assertFalse(StorageVolumeListing.isVolumeRoot("/storagex"))
    }

    // ── mountPointForExternalFilesDir: the SD-card detection itself ───────────

    @Test
    fun `an app-private directory on a removable volume yields that volume's mount point`() {
        assertEquals(
            "/storage/1A2B-3C4D",
            StorageVolumeListing.mountPointForExternalFilesDir(
                externalFilesDir = "/storage/1A2B-3C4D/Android/data/com.gruning.mimir.rewrite/files",
                primaryStoragePath = "/storage/emulated/0",
            ),
        )
    }

    @Test
    fun `an app-private directory on primary storage yields nothing, since primary is already listed`() {
        assertNull(
            StorageVolumeListing.mountPointForExternalFilesDir(
                externalFilesDir = "/storage/emulated/0/Android/data/com.gruning.mimir.rewrite/files",
                primaryStoragePath = "/storage/emulated/0",
            ),
        )
    }

    @Test
    fun `a path too short to carry a mount point yields nothing`() {
        assertNull(
            StorageVolumeListing.mountPointForExternalFilesDir(
                externalFilesDir = "/storage",
                primaryStoragePath = "/storage/emulated/0",
            ),
        )
    }

    // ── externalVolumeName: G8, the custom card label over the FAT serial ────

    /**
     * Mimir showed the card as
     * `SD Card (6A7F-FCFE)`; the yardstick (EX File Manager) showed the same physical card as
     * `Thor` -- its custom label. Android's own label must win when [StorageVolumeHelper] has one.
     */
    @Test
    fun `Android's own label wins over the FAT-serial fallback`() {
        assertEquals(
            "the custom name for the card must win, exactly as EX File Manager shows it",
            "Thor",
            StorageVolumeListing.externalVolumeName("/storage/6A7F-FCFE", platformLabel = "Thor"),
        )
    }

    @Test
    fun `no platform label falls back to exactly today's FAT-serial name`() {
        assertEquals(
            "SD Card (6A7F-FCFE)",
            StorageVolumeListing.externalVolumeName("/storage/6A7F-FCFE", platformLabel = null),
        )
    }

    @Test
    fun `a blank platform label is treated as no label, not as an empty name`() {
        assertEquals(
            "an unnameable volume gets no plate rather than a meaningless one -- a blank string is not a name",
            "SD Card (1A2B-3C4D)",
            StorageVolumeListing.externalVolumeName("/storage/1A2B-3C4D", platformLabel = "   "),
        )
    }

    @Test
    fun `a platform label is trimmed before it is trusted`() {
        assertEquals(
            "Thor",
            StorageVolumeListing.externalVolumeName("/storage/6A7F-FCFE", platformLabel = "  Thor  "),
        )
    }

    @Test
    fun `the fallback name tracks whichever mount point is actually passed in`() {
        assertEquals(
            "SD Card (ZZZZ-0000)",
            StorageVolumeListing.externalVolumeName("/storage/ZZZZ-0000", platformLabel = null),
        )
    }

    /**
     * The "one answer" guarantee, pinned as a regression test rather than left as a code-reading
     * claim: [StorageVolume.name] is the only thing either consumer reads, so however
     * [StorageVolumeHelper] obtained a real label, it reaches the drive chooser and the breadcrumb
     * trail identically -- no second place re-derives a volume's name from its path.
     */
    @Test
    fun `a real platform label reaches both the drive chooser and the breadcrumb trail unchanged`() {
        val thor = StorageVolume(name = "Thor", path = "/storage/6A7F-FCFE", isRemovable = true)

        assertEquals("Thor", StorageVolumeListing.volumeRows(listOf(internal(), thor)).last().name)
        assertEquals("Thor", StorageVolumeListing.crumbLabels(listOf(internal(), thor))["/storage/6A7F-FCFE"])
    }
}
