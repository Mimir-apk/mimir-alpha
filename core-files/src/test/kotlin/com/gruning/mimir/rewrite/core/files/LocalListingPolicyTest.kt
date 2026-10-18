package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins for LocalListingPolicy listing/classification rules, alongside zip-slip containment coverage. */
class LocalListingPolicyTest {

    private fun item(name: String, isDirectory: Boolean = false) = FileItem(
        name = name,
        path = "/tmp/$name",
        isDirectory = isDirectory,
    )

    @Test
    fun `zip and 7z are extractable, matching what ArchiveHelper can actually do`() {
        assertTrue(LocalListingPolicy.isExtractableArchive(item("game.zip")))
        assertTrue(LocalListingPolicy.isExtractableArchive(item("game.7z")))

        // Cross-check direction, one-way only: extractable implies ArchiveHelper agrees it is an
        // archive at all. The converse does not hold -- see the tar-family test below -- so it is
        // deliberately not asserted here.
        val helper = ArchiveHelper()
        assertTrue(helper.isArchive("game.zip"))
        assertTrue(helper.isArchive("game.7z"))
    }

    @Test
    fun `rar is never extractable, per the licence exclusion`() {
        assertFalse(
            "rar remains excluded -- already pinned at the ArchiveHelper " +
                "level by ArchiveHelperExtractionTest.noRarPathExistsAnywhereInTheVendoredSurface; this " +
                "is the matching classification rule",
            LocalListingPolicy.isExtractableArchive(item("game.rar")),
        )
    }

    @Test
    fun `tar family is not offered even though ArchiveHelper can still extract it`() {
        // Deliberately narrower than ArchiveHelper.isArchive() -- see LocalListingPolicy.kt's
        // header: the CURRENT policy is zip + 7z only. Removing tar support from
        // ArchiveHelper itself is separate, still-owed cleanup that this classification does not get ahead of by silently advertising it first.
        assertTrue(
            "sanity check: ArchiveHelper really can still extract tar.gz today, so this is a " +
                "deliberate narrowing, not accidental agreement",
            ArchiveHelper().isArchive("bundle.tar.gz"),
        )
        assertFalse(LocalListingPolicy.isExtractableArchive(item("bundle.tar.gz")))
    }

    @Test
    fun `a directory is never extractable regardless of its name`() {
        assertFalse(LocalListingPolicy.isExtractableArchive(item("looks-like-an-archive.zip", isDirectory = true)))
    }

    @Test
    fun `the five known plumbing paths are excluded`() {
        assertTrue(LocalListingPolicy.isPlumbingPath("/proc"))
        assertTrue(LocalListingPolicy.isPlumbingPath("/sys"))
        assertTrue(LocalListingPolicy.isPlumbingPath("/dev"))
        assertTrue(LocalListingPolicy.isPlumbingPath("/storage/self"))
        assertTrue(LocalListingPolicy.isPlumbingPath("/storage/emulated"))
    }

    @Test
    fun `plumbing exclusion is exact-path, not substring or basename -- no false positives`() {
        assertFalse(
            "a folder a player genuinely named 'dev' must not be hidden -- BrowserListing's own " +
                "documented principle is that a browser that lies about what is on disk is worse " +
                "than none",
            LocalListingPolicy.isPlumbingPath("/storage/emulated/0/Roms/dev"),
        )
        assertFalse(
            "children of a plumbing root are not themselves pinned as plumbing paths -- only the " +
                "root entry is kept out of a listing",
            LocalListingPolicy.isPlumbingPath("/proc/self/status"),
        )
        assertFalse(
            "prefix match must not fire on an unrelated name that merely starts the same",
            LocalListingPolicy.isPlumbingPath("/devious"),
        )
    }

    // ── Ruling 5: support files are dimmed, like the design does ──────────────

    /**
     * The three support-file fixtures must classify as support here, using the existing
     * `k: 'SUPPORT', dim: 1` behavior as the acceptance case.
     */
    @Test
    fun `the three files the design dims are exactly the ones classified as support`() {
        assertTrue("save RAM beside a ROM", LocalListingPolicy.isSupportFile(item("Pokemon Emerald.srm")))
        assertTrue("scraper metadata", LocalListingPolicy.isSupportFile(item("gamelist.xml")))
        assertTrue("a note the player left", LocalListingPolicy.isSupportFile(item("notes.txt")))
    }

    /**
     * The other half of the design's own fixture, and the more important half: the two files it
     * deliberately does NOT dim. `Fire Emblem.ips` is `k: 'OTHER'` and `gba_bios.bin` is `k: 'BIOS'` —
     * neither carries `dim: 1`. A BIOS payload is load-bearing for launching (the design's inspector
     * says so in player-facing copy, line 818: "Emulators need this file where it is"), so demoting it
     * would be actively misleading, and this assertion is what stops the set from being widened into a
     * blanket "anything unrecognised" sweep.
     */
    @Test
    fun `the files the design leaves at full presence are not support`() {
        assertFalse("a patch file is OTHER in the design, not SUPPORT", LocalListingPolicy.isSupportFile(item("Fire Emblem.ips")))
        assertFalse("a BIOS payload is never demoted", LocalListingPolicy.isSupportFile(item("gba_bios.bin")))
        assertFalse(LocalListingPolicy.isSupportFile(item("scph5501.bin")))
        assertFalse("game content", LocalListingPolicy.isSupportFile(item("Pokemon Emerald.gba")))
        assertFalse(LocalListingPolicy.isSupportFile(item("Final Fantasy VII.m3u")))
        assertFalse(LocalListingPolicy.isSupportFile(item("Castlevania - Symphony of the Night.chd")))
    }

    /**
     * A hidden file is support material by the same rule `BrowserListing.row` already applied
     * (`BrowserListing.kt:112`, `entry.name.startsWith(".")`) — dimmed, still visible. Note this is the
     * FILE rule; a hidden DIRECTORY like `.mimir` is demoted by the separate machinery rule in
     * `feature-files`, which is why directories are excluded here rather than swept in by the dot.
     */
    @Test
    fun `a dotfile is support but directory machinery is classified separately`() {
        assertTrue(LocalListingPolicy.isSupportFile(item(".nomedia")))

        assertFalse(
            "a directory's demotion uses exact machinery folder names, not the dotfile rule",
            LocalListingPolicy.isSupportFile(item(".mimir", isDirectory = true)),
        )
        assertFalse(LocalListingPolicy.isSupportFile(item("saves", isDirectory = true)))
        assertFalse(
            "a directory whose NAME ends in a support extension is still not a support file",
            LocalListingPolicy.isSupportFile(item("backup.dat", isDirectory = true)),
        )
    }

    /**
     * An extractable archive is the one filled thing in its column (adoption spec §4) and is the row the
     * player is meant to act on. The two classifications must never both claim a row, so this asserts
     * the exclusion directly rather than relying on the two extension sets happening not to overlap.
     */
    @Test
    fun `an extractable archive is never support, whatever its extension`() {
        LocalListingPolicy.EXTRACTABLE_ARCHIVE_EXTENSIONS.forEach { extension ->
            val archive = item("bundle.$extension")
            assertTrue("sanity: $extension must be extractable", LocalListingPolicy.isExtractableArchive(archive))
            assertFalse(
                "an extractable archive must never be dimmed as support: bundle.$extension",
                LocalListingPolicy.isSupportFile(archive),
            )
        }
        assertTrue(
            "the two sets are disjoint by construction, not by coincidence",
            LocalListingPolicy.SUPPORT_FILE_EXTENSIONS.intersect(
                LocalListingPolicy.EXTRACTABLE_ARCHIVE_EXTENSIONS,
            ).isEmpty(),
        )
    }

    @Test
    fun `support classification is case-insensitive, as a real filesystem demands`() {
        assertTrue(LocalListingPolicy.isSupportFile(item("GAMELIST.XML")))
        assertTrue(LocalListingPolicy.isSupportFile(item("Pokemon Emerald.SRM")))
    }

    /**
     * The vocabulary is `core-storage`'s own `BrowserListing.SUPPORT_EXTENSIONS`, copied because
     * neither this module nor `feature-files` may depend on `core-storage`. Pinned as a whole set, not
     * sampled, so a future edit that quietly widens it into a broad extension sweep — an unintended change in the support vocabulary — fails here instead of silently dimming half a ROM folder.
     *
     * **The assertion checks the combined support and media-art vocabulary.** The old
     * assertion compared [LocalListingPolicy.SUPPORT_FILE_EXTENSIONS] alone against `BrowserListing`'s
     * 25 and would now fail, because `png`/`jpg`/`jpeg`/`webp` moved to
     * [LocalListingPolicy.MEDIA_ART_EXTENSIONS]. The property it was protecting — *this vocabulary is
     * `BrowserListing`'s and has not been widened by stealth* — is unchanged and now checks the UNION,
     * which is strictly harder to satisfy accidentally: an extension can neither be lost from the two
     * sets nor invented into them.
     */
    @Test
    fun `the support vocabulary is BrowserListing's, split by F-07 but not widened`() {
        val browserListingSupportExtensions = setOf(
            "srm", "sav", "state", "st0", "st1", "st2", "st3", "st4", "st5", "st6", "st7", "st8", "st9",
            "txt", "dat", "xml", "cfg", "ini", "log", "nfo", "db", "png", "jpg", "jpeg", "webp",
        )

        assertEquals(
            "core-storage/BrowserListing.kt:70-73's set must survive F-07's split intact -- every one " +
                "of its 25 extensions still classifies, none silently dropped on the floor",
            browserListingSupportExtensions,
            LocalListingPolicy.SUPPORT_FILE_EXTENSIONS + LocalListingPolicy.MEDIA_ART_EXTENSIONS -
                setOf("mp4", "mp3"),
        )
        assertEquals(
            "the only additions F-07 makes are the two specified media extensions",
            setOf("mp4", "mp3"),
            (LocalListingPolicy.SUPPORT_FILE_EXTENSIONS + LocalListingPolicy.MEDIA_ART_EXTENSIONS) -
                browserListingSupportExtensions,
        )
        assertTrue(
            "the two classes must stay disjoint, or one row could be claimed by both",
            LocalListingPolicy.SUPPORT_FILE_EXTENSIONS
                .intersect(LocalListingPolicy.MEDIA_ART_EXTENSIONS).isEmpty(),
        )
    }

    // ---- F-07: media art is its own kind ----

    /**
     * `mp4`, `mp3` and `png` classify as media art rather than generic support files.
     *
     * **The available row kinds are asserted:**
     * `core-storage`'s `BrowserListing.RowKind` has no media/art value, so this vocabulary genuinely had
     * to be added rather than reused. That fact is asserted here against a literal copy of its six
     * values so the claim cannot rot silently if `RowKind` later grows one.
     */
    @Test
    fun `mp4, mp3 and png are media art -- a kind BrowserListing did not have`() {
        assertEquals(FileContentClass.MEDIA_ART, LocalListingPolicy.classifyContent(item("snap.mp4")))
        assertEquals(FileContentClass.MEDIA_ART, LocalListingPolicy.classifyContent(item("theme.mp3")))
        assertEquals(FileContentClass.MEDIA_ART, LocalListingPolicy.classifyContent(item("boxart.png")))
        assertTrue(LocalListingPolicy.isMediaArtFile(item("BOXART.PNG")))

        // `BrowserListing` has no media/art category. Assert that against the real source rather
        // than a copy of it: a copy compared to itself cannot detect drift. `core-files` cannot
        // depend on `core-storage`, so this reads the file, like the no-network source check.
        val browserListing = File("../core-storage/src/main/kotlin/com/gruning/mimir/rewrite/core/storage/scan/BrowserListing.kt")
        assertTrue(
            "sanity: the reference vocabulary must be readable from here, or this proves nothing",
            browserListing.isFile,
        )
        val rowKind = browserListing.readText()
            .substringAfter("enum class RowKind {")
            .substringBefore("}")
        listOf("MEDIA", "ART", "ARTWORK").forEach { candidate ->
            assertFalse(
                "BrowserListing.RowKind has no $candidate value today, which is why F-07's vocabulary " +
                    "had to be added rather than reused. If this fails, RowKind grew one and core-files " +
                    "should adopt ITS name instead of keeping a second one.",
                rowKind.contains(candidate),
            )
        }
        assertTrue(
            "sanity: the parse must have found the real enum body",
            rowKind.contains("SUPPORT") && rowKind.contains("BIOS"),
        )
    }

    @Test
    fun `a save is still SUPPORT and not media art, so the two kinds stay distinguishable`() {
        assertEquals(FileContentClass.SUPPORT, LocalListingPolicy.classifyContent(item("Pokemon Emerald.srm")))
        assertEquals(FileContentClass.SUPPORT, LocalListingPolicy.classifyContent(item("gamelist.xml")))
        assertEquals(FileContentClass.SUPPORT, LocalListingPolicy.classifyContent(item(".nomedia")))
        assertFalse(LocalListingPolicy.isMediaArtFile(item("Pokemon Emerald.srm")))
    }

    /**
     * **Dimming remains stable when media art is classified separately.** `FileBrowserTile.support` (in feature-files) reads [LocalListingPolicy.isSupportFile] to decide dimming, so moving `png` out of the
     * support SET must not change whether a `png` DIMS. It does not, because `isSupportFile` now answers
     * true for MEDIA_ART as well.
     *
     * `mp4`/`mp3` newly dim, which is a real behaviour delta from the previous classification — they are not game content, and leaving them at full presence beside the ROMs would be the
     * same classification inaccuracy, in the other direction.
     */
    @Test
    fun `media art still dims, so F-07 changes the kind and not the presence`() {
        assertTrue(
            "a png dimmed before F-07 and must dim after it -- FileBrowserTile.support reads this " +
                "predicate and is outside this lane",
            LocalListingPolicy.isSupportFile(item("boxart.png")),
        )
        listOf("jpg", "jpeg", "webp").forEach { extension ->
            assertTrue("$extension must keep dimming", LocalListingPolicy.isSupportFile(item("art.$extension")))
        }
        listOf("mp4", "mp3").forEach { extension ->
            assertTrue(
                "$extension newly dims -- disclosed delta, not an accident",
                LocalListingPolicy.isSupportFile(item("clip.$extension")),
            )
        }
    }

    @Test
    fun `an archive is never media art, and a directory is never either`() {
        assertEquals(
            "the one filled thing in its column must not be demoted by a classification order slip",
            FileContentClass.ARCHIVE,
            LocalListingPolicy.classifyContent(item("Golden Sun.zip")),
        )
        assertFalse(LocalListingPolicy.isSupportFile(item("Golden Sun.zip")))
        assertEquals(
            FileContentClass.DIRECTORY,
            LocalListingPolicy.classifyContent(item("media", isDirectory = true)),
        )
        assertFalse(
            "a folder's demotion is the separate machinery rule, matched by name, not by this",
            LocalListingPolicy.isSupportFile(item("media", isDirectory = true)),
        )
    }

    @Test
    fun `an unrecognised file is OTHER, not swept into a kind it does not belong to`() {
        assertEquals(
            "the design's own fixture has `Fire Emblem.ips` as k:'OTHER' and undimmed",
            FileContentClass.OTHER,
            LocalListingPolicy.classifyContent(item("Fire Emblem.ips")),
        )
        assertEquals(FileContentClass.OTHER, LocalListingPolicy.classifyContent(item("gba_bios.bin")))
        assertFalse(LocalListingPolicy.isSupportFile(item("gba_bios.bin")))
    }
}
