package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.ui.mimirShelfmarkEmphasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserTileTest {
    private val english = filesEnglish()

    private fun dir(name: String) = FileItem(name = name, path = "/root/$name", isDirectory = true)
    private fun file(name: String, extension: String = name.substringAfterLast('.', "")) =
        FileItem(name = name, path = "/root/$name", extension = extension)

    // ── machinery demotion ──

    @Test
    fun `saves, media and dot-mimir directories are machinery, case-insensitively`() {
        assertTrue(fileBrowserIsMachineryFolder(dir("saves")))
        assertTrue(fileBrowserIsMachineryFolder(dir("SAVES")))
        assertTrue(fileBrowserIsMachineryFolder(dir("media")))
        assertTrue(fileBrowserIsMachineryFolder(dir(".mimir")))
    }

    @Test
    fun `a folder that merely contains a machinery word is not swept in -- exact match only`() {
        assertFalse(fileBrowserIsMachineryFolder(dir("my saves")))
        assertFalse(fileBrowserIsMachineryFolder(dir("media backup")))
    }

    @Test
    fun `a plain file named saves is never machinery -- demotion is a tome treatment`() {
        assertFalse(fileBrowserIsMachineryFolder(file("saves")))
    }

    @Test
    fun `an ordinary directory is not machinery`() {
        assertFalse(fileBrowserIsMachineryFolder(dir("Roms")))
    }

    // ── badge label ──

    @Test
    fun `fileBrowserBadgeLabel names ZIP vs 7Z by the tile's own extension`() {
        val zip = FileBrowserTile(file("bundle.zip"), FileBrowserTileKind.ARCHIVE, machinery = false, platformIdValue = null)
        val sevenZ = FileBrowserTile(file("bundle.7z"), FileBrowserTileKind.ARCHIVE, machinery = false, platformIdValue = null)
        assertEquals("ZIP", fileBrowserBadgeLabel(english, zip))
        assertEquals("7Z", fileBrowserBadgeLabel(english, sevenZ))
    }

    @Test
    fun `fileBrowserBadgeLabel names a plain file by its type and a directory DIR -- never a dash`() {
        val plain = FileBrowserTile(file("readme.txt"), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)
        val unknown = FileBrowserTile(file("mystery.xyz"), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)
        val directory = FileBrowserTile(dir("Roms"), FileBrowserTileKind.DIRECTORY, machinery = false, platformIdValue = null)
        assertEquals("Doc", fileBrowserBadgeLabel(english, plain))
        assertEquals("a file Mimir has no type for still gets a word", "File", fileBrowserBadgeLabel(english, unknown))
        assertEquals("Dir", fileBrowserBadgeLabel(english, directory))
    }

    // ── tile meta ──

    private fun sized(name: String, size: Long) =
        FileItem(name = name, path = "/root/$name", extension = name.substringAfterLast('.', ""), size = size)

    // Sizes here stay under 1 KB on purpose. FileItem.formatSize uses "%.1f".format(...) for anything
    // larger, which takes the JVM's DEFAULT LOCALE and yields "16,0 MB" on a comma-decimal machine --
    // a locale-flaky assertion in a test whose actual subject is the "EXT · " composition, not the size
    // formatting (core-files owns and tests that). The one MB case below builds its expectation through
    // the same public helper for exactly that reason.
    @Test
    fun `a plain file's meta is its extension and size -- the only identity it has without a classifier`() {
        val tile = FileBrowserTile(sized("Pokemon Emerald.gba", 512L), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)

        assertEquals("GBA · 512 B", fileBrowserTileMeta(english, tile))
    }

    @Test
    fun `the extension is uppercased and read from the item, not sliced out of the tile name`() {
        val tile = FileBrowserTile(sized("Metroid - Zero Mission.gba", 640L), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)

        assertEquals("GBA · 640 B", fileBrowserTileMeta(english, tile))
    }

    @Test
    fun `a large file's meta is still EXT then the engine's own size string`() {
        val bytes = 16L * 1024 * 1024
        val tile = FileBrowserTile(sized("Fire Emblem.gba", bytes), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)

        assertEquals("GBA · " + FileItem.formatSize(bytes), fileBrowserTileMeta(english, tile))
    }

    @Test
    fun `an archive's meta is its size ALONE -- the badge already names the format, one filled thing per column`() {
        val tile = FileBrowserTile(sized("Golden Sun (USA).zip", 900L), FileBrowserTileKind.ARCHIVE, machinery = false, platformIdValue = null)

        // Deliberately NOT "ZIP · 900 B": fileBrowserBadgeLabel is already drawing ZIP one line above.
        assertEquals("900 B", fileBrowserTileMeta(english, tile))
        assertEquals("ZIP", fileBrowserBadgeLabel(english, tile))
    }

    @Test
    fun `an extensionless file degrades to the size alone, never a leading separator`() {
        val tile = FileBrowserTile(sized("LICENSE", 900L), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)

        assertEquals("900 B", fileBrowserTileMeta(english, tile))
    }

    @Test
    fun `a directory's meta is its child count, and unknown still draws nothing rather than zero`() {
        // UPDATED 2026-08-14 (policy 1): `FileItem.childCount` is now WRITTEN --
        // `LocalFileRepository.pathToFileItem` populates it from `File.list()?.size`, pinned in
        // `core-files` by `LocalFileRepositoryTest`. This test's subject is unchanged: the RENDER rule.
        // The null case is no longer "core-files never fills it" -- it is the real, ongoing case of a
        // directory the app cannot read, which core-files deliberately reports as null rather than 0,
        // and which must still draw nothing rather than claiming "0 items".
        val unknown = FileBrowserTile(dir("Roms"), FileBrowserTileKind.DIRECTORY, machinery = false, platformIdValue = null)
        assertEquals("", fileBrowserTileMeta(english, unknown))

        val counted = FileBrowserTile(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true, childCount = 146),
            FileBrowserTileKind.DIRECTORY,
            machinery = false,
            platformIdValue = null,
        )
        assertEquals("146 items", fileBrowserTileMeta(english, counted))

        val empty = FileBrowserTile(
            FileItem(name = "Empty", path = "/root/Empty", isDirectory = true, childCount = 0),
            FileBrowserTileKind.DIRECTORY,
            machinery = false,
            platformIdValue = null,
        )
        assertEquals(
            "a readable but empty folder genuinely says 0; only UNKNOWN draws nothing",
            "0 items",
            fileBrowserTileMeta(english, empty),
        )
    }

    // ── File-type vocabulary: which files are sidecars (no longer dimmed -- see FileBrowserTypeCardsTest) ──

    private fun tile(
        item: FileItem,
        kind: FileBrowserTileKind = FileBrowserTileKind.FILE,
        platformIdValue: String? = null,
    ) = FileBrowserTile(item, kind, machinery = false, platformIdValue = platformIdValue)

    /**
     * POLICY 5 pin (2026-08-14), vocabulary only. The design gives support FILES `dim: 1`
     * (`Mimir Files.dc.html` lines 470-471, 492). The 0.55 dim itself was retired on 2026-10-08; which files are sidecars is still a fact the type mapping uses.
     */
    @Test
    fun `the files the design dims are support tiles`() {
        assertTrue(tile(file("Pokemon Emerald.srm")).support)
        assertTrue(tile(file("gamelist.xml")).support)
        assertTrue(tile(file("notes.txt")).support)
    }

    @Test
    fun `the files the design leaves at full presence are not support tiles`() {
        assertFalse("k: 'OTHER' in the design, undimmed", tile(file("Fire Emblem.ips")).support)
        assertFalse("k: 'BIOS' in the design, undimmed", tile(file("gba_bios.bin")).support)
        assertFalse(tile(file("Pokemon Emerald.gba")).support)
    }

    /**
     * Recognition wins over demotion, mirroring `BrowserListing.row`'s own ordering (`platform != null ->
     * GAME` is decided before its support branch) and the design's fixture, where every `k: 'GAME'` row
     * is undimmed. Without this, a platform whose ROMs carry a support-looking extension would have its
     * whole folder greyed out.
     */
    @Test
    fun `a recognised platform tile is never dimmed, whatever its extension looks like`() {
        val unrecognised = tile(file("save.dat"))
        val recognised = tile(file("save.dat"), platformIdValue = "some-platform")

        assertTrue("sanity: this extension IS support vocabulary", unrecognised.support)
        assertFalse("recognition wins over demotion", recognised.support)
    }

    @Test
    fun `an archive is never support -- it is the one filled thing in its column`() {
        assertFalse(tile(file("bundle.zip"), kind = FileBrowserTileKind.ARCHIVE).support)
        assertFalse(tile(file("bundle.7z"), kind = FileBrowserTileKind.ARCHIVE).support)
    }

    /**
     * A tome is demoted by the machinery rule, never by this one, even when its NAME would classify as
     * support material. The two demotions must not both be able to claim the same row, or a folder
     * called `artwork.png` would be dimmed twice over by two different rules.
     */
    @Test
    fun `a directory is never a support tile and machinery uses folder classification`() {
        assertFalse(tile(dir("saves"), kind = FileBrowserTileKind.DIRECTORY).support)
        assertFalse(tile(dir("gamelist.xml"), kind = FileBrowserTileKind.DIRECTORY).support)
        assertFalse(tile(dir("Roms"), kind = FileBrowserTileKind.DIRECTORY).support)
    }

    // ── F-07: media art is a kind, not "misc" ──

    /**
     * The vocabulary is `core-files`' `LocalListingPolicy.isMediaArtFile`, never a second list here.
     * Note the last assertion: media art stays DEMOTED — this refines what Mimir calls the file, it does
     * not promote it out of the dimmed set.
     */
    @Test
    fun `mp4, mp3 and png are media art -- and stay demoted, which is the point of a refinement`() {
        listOf("snap.mp4", "theme.mp3", "boxart.png").forEach { name ->
            val media = tile(file(name))
            assertTrue("$name must classify as media art", media.mediaArt)
            assertTrue("$name is still a sidecar -- F-07 renames the kind, it does not promote it", media.support)
        }
    }

    @Test
    fun `a rom, a save and an archive are not media art`() {
        assertFalse(tile(file("Pokemon Emerald.gba")).mediaArt)
        assertFalse(tile(file("Pokemon Emerald.srm")).mediaArt)
        assertFalse(tile(file("bundle.zip"), kind = FileBrowserTileKind.ARCHIVE).mediaArt)
        assertFalse(tile(dir("media"), kind = FileBrowserTileKind.DIRECTORY).mediaArt)
    }

    /** Same ordering [support] uses, and for the same reason: a platform whose ROMs happen to carry a
     * media extension is game content, not artwork. */
    @Test
    fun `recognition wins over the media-art kind`() {
        assertFalse(
            "a platform the classifier named is game content whatever its extension looks like -- " +
                "without this gate a platform whose ROMs ship as .png would be catalogued as artwork",
            tile(file("boxart.png"), platformIdValue = "some-platform").mediaArt,
        )
    }

    // ── F-04: the platform mark's size ──

    /**
     * **Repeated oversized platform-label regression**, after UI-04 moved
     * the paint-time scales from 1.8x/1.6x to 34/20 and 30/20 and it was still too big.
     *
     * The number that was being matched is the side of a SQUARE MASKED ICON: the design's
     * `glyph(g, size)` returns `{ width: size, height: size, maskImage: url(.../platforms/<g>.svg) }`
     * (`Mimir Files.dc.html:559-562`), drawn at `tome ? 34 : 30` (line 661) — no text, no plate.
     * `MimirPlatformShelfmark` is a struck plate carrying a TEXT call number, so its width tracks its
     * ink; matching the icon's HEIGHT dragged the plate to ~60dp wide (51% of a 118dp cover) and the
     * code to ~18.7sp.
     *
     * **11sp is the number this test exists to defend.** `MimirNorseBookCover` sizes its title
     * `(coverWidth * 0.085f).coerceIn(11f, 18f)`, which on the folio's own 118dp tome
     * (`FileBrowserFocusRegistration.kt:108-122` derives the 118) clamps to **11sp** — so the scaled
     * platform code was 70% larger than the folder name it captions, and the largest type on the tile.
     * That is what "reads too big" means, and it is why trimming 6% off the height changed nothing.
     *
     * The 11f below RESTATES a `core-ui` `internal` constant this module cannot import, under the same
     * contract `FileBrowserFocusRegistrationTest` already carries for `FileBrowserSurface`'s own dp
     * literals: if `MimirNorseBookTitleFraction` or its clamp moves, this restatement must move with it.
     */
    @Test
    fun `the platform mark is struck at the shelfmark's own tier, never louder than the title it captions`() {
        val tome = mimirShelfmarkEmphasis(FileBrowserTomeGlyphTier)
        val file = mimirShelfmarkEmphasis(FileBrowserFileGlyphTier)
        val tomeTitleSp = 11f // MimirNorseBookCover: (118 * 0.085).coerceIn(11f, 18f) -> 11sp

        assertEquals(
            "the tome mark is PICKER -- the shelfmark's own loudest tier, at its own size",
            20f,
            tome.height.value,
            0f,
        )
        assertTrue(
            "the platform code must never outshout the folder name it captions: a 34/20 paint scale " +
                "put it at 18.7sp against an 11sp title, which is the repeatedly observed oversized label",
            tome.fontSize.value <= tomeTitleSp,
        )
        assertTrue(
            "the design strikes the tome mark louder than the file mark (34 : 30); the tier ladder " +
                "must keep that ordering rather than flatten the two together",
            tome.height.value > file.height.value,
        )
        assertEquals(
            "and by roughly the design's own ratio -- 34/30 = 1.133 against the ladder's 20/18 = 1.111",
            34f / 30f,
            tome.height.value / file.height.value,
            0.03f,
        )
    }

    /**
     * The wiring half, and it exists for the same reason [FileBrowserSupportDimRenderTest] does: the
     * tiers above can be perfectly correct while both call sites still wrap the component in a
     * `graphicsLayer` scale, and no assertion on the constants would notice. Source-text, because both
     * tiles are private `@Composable`s and this module runs plain JUnit.
     *
     * **Deliberately windowed to the shelfmark call sites rather than asserted file-wide.** A blanket
     * "no `scaleX` in this file" would fail the day a concurrent lane's motion work legitimately scales
     * something else, which would make this test a tripwire on someone else's correct change instead of
     * on this defect.
     */
    @Test
    fun `neither tile wraps its shelfmark in a paint-time scale`() {
        val source = surfaceSource()
        listOf(
            "TomeGlyph" to "FileBrowserTomeGlyphTier",
            "FileTile" to "FileBrowserFileGlyphTier",
        ).forEach { (site, constant) ->
            assertTrue(
                "$site must ask for `tier = $constant`, or the measurement lives in one place and the " +
                    "render obeys another",
                source.contains("tier = $constant"),
            )
        }
        assertFalse(
            "the pre-F-04 scale constants must be gone -- a paint-time scale over the shelfmark IS " +
                "the defect, in every version it has had (1.8x, then 34/20)",
            source.contains("GlyphScale") || tileSource().contains("GlyphScale"),
        )

        val sites = generateSequence(source.indexOf(ShelfmarkCall)) { previous ->
            source.indexOf(ShelfmarkCall, previous + 1).takeIf { it >= 0 }
        }.toList()
        assertEquals("both tiles must still draw the shelfmark", 2, sites.size)
        sites.forEach { at ->
            // Comments stripped: this window is preceded by KDoc that NAMES the scale it forbids, and a
            // scanner that cannot tell prose from code fails on its own documentation. Same discipline
            // `tools/mimir_ui_invariants_gate.py` applies, for the same reason.
            val window = source.substring(maxOf(0, at - ShelfmarkWindowChars), at).withoutComments()
            assertFalse(
                "no `scaleX`/`scaleY` may appear in the ${ShelfmarkWindowChars} characters before a " +
                    "MimirPlatformShelfmark call -- that window is the enclosing composable's modifier " +
                    "chain, and a scale there is exactly how the plate reached 34dp and 18.7sp",
                window.contains("scaleX") || window.contains("scaleY"),
            )
        }
    }

    private companion object {
        const val ShelfmarkCall = "MimirPlatformShelfmark("
        const val ShelfmarkWindowChars = 400
    }

    private fun tileSource(): String = moduleSource("FileBrowserTile.kt")

    /** See [neither tile wraps its shelfmark in a paint-time scale]. */
    private fun String.withoutComments(): String = lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private fun surfaceSource(): String = moduleSource("FileBrowserSurface.kt")

    private fun moduleSource(name: String): String {
        val candidates = listOf(
            java.io.File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}
