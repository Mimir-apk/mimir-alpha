package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserInspectorTest {
    private val english = filesEnglish()

    private fun directoryTile(name: String = "GBA", platformIdValue: String? = null, machinery: Boolean = false) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = true),
        kind = FileBrowserTileKind.DIRECTORY,
        machinery = machinery,
        platformIdValue = platformIdValue,
    )

    private fun archiveTile(name: String = "bundle.zip") = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", extension = name.substringAfterLast('.')),
        kind = FileBrowserTileKind.ARCHIVE,
        machinery = false,
        platformIdValue = null,
    )

    private fun fileTile(
        name: String,
        platformIdValue: String? = null,
        lastModified: Long = 0L,
        gameLike: Boolean = false,
        bios: Boolean = false,
    ) = FileBrowserTile(
        item = FileItem(
            name = name,
            path = "/root/$name",
            extension = name.substringAfterLast('.', ""),
            lastModified = lastModified,
        ),
        kind = FileBrowserTileKind.FILE,
        machinery = false,
        platformIdValue = platformIdValue,
        gameLike = gameLike,
        bios = bios,
    )

    // ── modified formatting ──

    @Test
    fun `fileBrowserFormatModified renders a fixed instant deterministically in a given zone`() {
        // 2026-07-12T21:38:00Z
        val epochMillis = 1783892280000L
        assertEquals("12 Jul 2026 · 21:38", fileBrowserFormatModified(english, epochMillis, ZoneOffset.UTC))
    }

    @Test
    fun `fileBrowserFormatModified reports Unknown for FileItem's own zero default rather than 1970`() {
        assertEquals("Unknown", fileBrowserFormatModified(english, 0L, ZoneOffset.UTC))
    }

    // ── DIRECTORY ──

    @Test
    fun `an unrecognised directory chips as Folder and reports no platform`() {
        val fields = fileBrowserInspectorFields(english, directoryTile(platformIdValue = null), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC)

        assertEquals("Folder", fields.chipLabel)
        assertEquals(FileBrowserChipTone.NEUTRAL, fields.chipTone)
        assertEquals("Not a platform folder", fields.launches)
        assertTrue(fields.openable)
        assertFalse(fields.extractable)
    }

    @Test
    fun `a recognised directory chips as a Platform tome and names the platform's own short code`() {
        val fields = fileBrowserInspectorFields(english, directoryTile(platformIdValue = "game-boy-advance"), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC)

        assertEquals("Platform tome", fields.chipLabel)
        assertEquals(FileBrowserChipTone.RECOGNISED, fields.chipTone)
        assertEquals("GBA folder", fields.launches)
    }

    // ── ARCHIVE ──

    @Test
    fun `an archive chips Archive, is extractable, never openable`() {
        val fields = fileBrowserInspectorFields(english, archiveTile(), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC)

        assertEquals("Archive", fields.chipLabel)
        assertEquals(FileBrowserChipTone.PENDING, fields.chipTone)
        assertEquals("Nothing yet — extract it first", fields.launches)
        assertFalse(fields.openable)
        assertTrue(fields.extractable)
    }

    @Test
    fun `a 7z archive is extractable exactly like a zip -- no stale design-mockup note is ever produced`() {
        val fields = fileBrowserInspectorFields(english, archiveTile("bundle.7z"), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC)

        assertTrue(fields.extractable)
        assertEquals("7Z", fields.tag)
    }

    // ── FILE ──

    @Test
    fun `an unrecognised file chips Misc and states plainly it is not game content`() {
        val fields = fileBrowserInspectorFields(english, fileTile("mystery.xyz"), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC)

        assertEquals("Misc", fields.chipLabel)
        assertEquals(FileBrowserChipTone.MUTED, fields.chipTone)
        assertEquals("Not game content", fields.launches)
        assertFalse(fields.openable)
        assertFalse(fields.extractable)
    }

    /** the chip names the type [fileBrowserFileKind] decided, so only a file Mimir
     * genuinely has no type for is still a Misc. */
    @Test
    fun `every typed file chips its own type, not Misc`() {
        val expected = mapOf(
            "readme.txt" to "Document",
            "Pokemon Emerald.srm" to "Save data",
            "library.dat" to "Data",
            "tool.apk" to "App",
            "bundle.tar" to "Archive",
        )
        expected.forEach { (name, chip) ->
            val fields = fileBrowserInspectorFields(english, fileTile(name), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC)
            assertEquals("$name", chip, fields.chipLabel)
            assertEquals("$name is not game content", "Not game content", fields.launches)
        }
    }

    /** Design decision 9: a game with no platform is still a game, and says what is missing. */
    @Test
    fun `a game whose platform is unsettled chips Game and says no platform was recognised`() {
        val fields = fileBrowserInspectorFields(english,
            fileTile("Mystery Disc.iso", gameLike = true),
            currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC,
        )

        assertEquals("Game", fields.chipLabel)
        assertEquals("No platform recognised", fields.launches)
        assertEquals("Game", fields.tag)
    }

    /** Lead resolution 5: a BIOS is named as one -- even where a platform also resolved -- and says it must stay. */
    @Test
    fun `a BIOS chips BIOS and says emulators need it where it is, even when a platform also resolved`() {
        val fields = fileBrowserInspectorFields(english,
            fileTile("gba_bios.bin", platformIdValue = "gba", bios = true),
            currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC,
        )

        assertEquals("BIOS", fields.chipLabel)
        assertEquals(FileBrowserChipTone.RECOGNISED, fields.chipTone)
        assertEquals(english.getString(FileBrowserBiosLaunches), fields.launches)
        assertEquals("BIOS", fields.tag)
    }

    /** The image picker's reason line rides through to the field; the default is null. */
    @Test
    fun `the upload note is carried to the field, and absent by default`() {
        val tile = fileTile("disc.iso")
        assertNull(fileBrowserInspectorFields(english, tile, currentPath = "/root", rootPath = "/root").uploadNote)
        assertEquals(
            "Not an image",
            fileBrowserInspectorFields(english, tile, currentPath = "/root", rootPath = "/root", uploadNote = "Not an image").uploadNote,
        )
    }

    /**
     * "Misc" is what Mimir says when it does not know. It knows what a `.png` is, so saying "Misc" there
     * was under-reporting a fact it already holds. `mystery.xyz` above still chips Misc, and must: that
     * one genuinely is unclassified.
     */
    @Test
    fun `box art, a video snap and theme audio chip as Media art rather than Misc`() {
        listOf("boxart.png", "snap.mp4", "theme.mp3").forEach { name ->
            val fields = fileBrowserInspectorFields(english,
                fileTile(name), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC,
            )
            assertEquals("$name must be named, not filed under Misc", english.getString(FileBrowserMediaArtChip), fields.chipLabel)
            assertEquals(FileBrowserChipTone.MUTED, fields.chipTone)
        }
    }

    /** Recognition still wins: a platform the classifier named is game content whatever the extension
     * looks like. Same ordering [FileBrowserTile.support] uses. */
    @Test
    fun `a recognised file is never demoted to Media art by its extension`() {
        val fields = fileBrowserInspectorFields(english,
            fileTile("boxart.png", platformIdValue = "gba"),
            currentPath = "/root",
            rootPath = "/root",
            zone = ZoneOffset.UTC,
        )

        assertEquals("Recognised", fields.chipLabel)
    }

    @Test
    fun `a recognised file chips Recognised and names only what the classifier itself proved -- no fabricated emulator name`() {
        val fields = fileBrowserInspectorFields(english, fileTile("Metroid.gba", platformIdValue = "game-boy-advance"), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC)

        assertEquals("Recognised", fields.chipLabel)
        assertEquals(FileBrowserChipTone.RECOGNISED, fields.chipTone)
        assertEquals("Runs on GBA", fields.launches)
    }

    // ── fields that never fabricate data this slice does not have ──

    /**
     * **F-05, 2026-08-14.** This replaces `checksum always reads Not checked`, which asserted that a
     * permanently-constant string was permanently constant — the defect itself, pinned as a contract.
     * The checksum is no longer a field on this object at all (see [FileBrowserInspectorFields]'s KDoc:
     * the panel builds it inside a `remember`, so a live result could never have re-rendered it).
     *
     * What survives here is the one checksum-shaped fact that IS per-tile: whether the question means
     * anything. A directory has no digest, and the Verify button it used to carry could only ever fail.
     */
    @Test
    fun `only a file or an archive is checksumable -- a tome has no digest to offer`() {
        assertFalse(
            "a directory has no digest: offering Verify on a tome is a button whose only possible " +
                "outcome is a failure, which is the dead control this tree refuses everywhere else",
            fileBrowserInspectorFields(english, directoryTile(), currentPath = "/root", rootPath = "/root").checksumable,
        )
        assertTrue(
            "hashing a packaged ROM is exactly what a DAT comparison wants",
            fileBrowserInspectorFields(english, archiveTile(), currentPath = "/root", rootPath = "/root").checksumable,
        )
        assertTrue(
            "a plain file is the ordinary case",
            fileBrowserInspectorFields(english, fileTile("a.txt"), currentPath = "/root", rootPath = "/root").checksumable,
        )
    }

    // ── platform code: the shared "can Mimir name this out loud?" gate ──

    @Test
    fun `fileBrowserPlatformCode is null for no platform and for one the plate cannot name`() {
        assertNull(fileBrowserPlatformCode(null))
        // No letter or digit anywhere: the curated table misses and shelfmarkInitials returns blank, so
        // MimirPlatformShelfmark would draw NOTHING. Both tile call sites branch on this, not on the id,
        // precisely so the cover falls back to its Mannaz instead of rendering blank.
        assertNull(fileBrowserPlatformCode("___"))
        assertEquals("GBA", fileBrowserPlatformCode("gba"))
    }

    @Test
    fun `an uncodeable platform id reads as unrecognised everywhere, never as a half-recognised tile`() {
        val directory = fileBrowserInspectorFields(english,
            directoryTile(platformIdValue = "___"), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC,
        )
        assertEquals("Folder", directory.chipLabel)
        assertEquals("Not a platform folder", directory.launches)

        val game = fileBrowserInspectorFields(english,
            fileTile("x.rom", platformIdValue = "___"), currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC,
        )
        // an id nothing can name is a game whose platform Mimir cannot say, so it
        // reads "Game" / "No platform recognised" -- still unrecognised everywhere, never half-recognised.
        assertEquals("Game", game.chipLabel)
        assertEquals(FileBrowserChipTone.NEUTRAL, game.chipTone)
        assertEquals("No platform recognised", game.launches)
    }

    // ── Where ──

    @Test
    fun `Where reports the design's friendly trail, never the raw mount path previously exposed`() {
        // Observed on build d0b2c550: the surface showed a bare `/storage/emulated/0/Roms`. The
        // design's Where row defaults to friendly (`Mimir Files.dc.html:810`, `friendly()` at :619).
        val fields = fileBrowserInspectorFields(english,
            fileTile("a.txt"),
            currentPath = "/storage/emulated/0/Roms/GBA",
            rootPath = "/storage/emulated/0/Roms",
            zone = ZoneOffset.UTC,
        )

        assertEquals("Roms › GBA", fields.where)
    }

    @Test
    fun `Where still reports the directory currently open, not the focused item's own path`() {
        // Preserved from the pre-2026-08-14 pin, whose assertion (raw path) changed but whose SUBJECT
        // did not: a file named `a.txt` must never leak into the row that names its container.
        val fields = fileBrowserInspectorFields(english,
            fileTile("a.txt"),
            currentPath = "/root/Roms/GBA",
            rootPath = "/root",
            zone = ZoneOffset.UTC,
        )

        assertEquals("root › Roms › GBA", fields.where)
    }

    @Test
    fun `Where at the root is the root crumb alone -- no trailing separator`() {
        val fields = fileBrowserInspectorFields(english,
            fileTile("a.txt"),
            currentPath = "/storage/emulated/0/Roms",
            rootPath = "/storage/emulated/0/Roms",
            zone = ZoneOffset.UTC,
        )

        assertEquals("Roms", fields.where)
    }

    @Test
    fun `a threaded crumb label replaces the root crumb in Where -- the seam a real volume name lands on`() {
        val fields = fileBrowserInspectorFields(english,
            fileTile("a.txt"),
            currentPath = "/storage/691E-7BEA/GBA",
            rootPath = "/storage/691E-7BEA",
            crumbLabels = mapOf("/storage/691E-7BEA" to "SD card"),
            zone = ZoneOffset.UTC,
        )

        assertEquals("SD card › GBA", fields.where)
    }
}
