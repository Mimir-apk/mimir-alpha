package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileOperationStaging
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 *  -- worst in the image-upload picker. Files
 * no longer fades anything; every kind of file has a card and a word of its own.
 *
 * The mapping below distinguishes file types (mp4 is video, BIOS and PDF get their own words, Mimir's folders are labelled).
 * This replaces `FileBrowserSupportDimRenderTest` and `FileBrowserDimClipTest`, which pinned the 0.55 alpha
 * removed after device review and the offscreen-layer clip it caused.
 *
 * What this cannot prove, stated: the washes are drawn by private composables, pinned here as source text;
 * how Folkvangr's silver, Niflheim's ice-white and Hel's red washes read is a device look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserTypeCardsTest {
    private val english = filesEnglish()

    private fun file(name: String, mime: String = "application/octet-stream") = FileItem(
        name = name,
        path = "/root/$name",
        extension = name.substringAfterLast('.', ""),
        mimeType = mime,
    )

    private fun tile(
        name: String,
        mime: String = "application/octet-stream",
        kind: FileBrowserTileKind = FileBrowserTileKind.FILE,
        platformIdValue: String? = null,
        gameLike: Boolean = false,
        bios: Boolean = false,
    ) = FileBrowserTile(file(name, mime), kind, machinery = false, platformIdValue = platformIdValue, gameLike = gameLike, bios = bios)

    private fun folder(name: String, platformIdValue: String? = null, machinery: Boolean = false) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = true),
        kind = FileBrowserTileKind.DIRECTORY,
        machinery = machinery,
        platformIdValue = platformIdValue,
    )

    private fun kindOf(name: String, mime: String = "application/octet-stream") = fileBrowserFileKind(tile(name, mime))

    // ── the mapping ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a folder is a platform folder only when its name claims a nameable platform`() {
        assertEquals(FileBrowserFileKind.PLATFORM_FOLDER, fileBrowserFileKind(folder("GBA", platformIdValue = "gba")))
        assertEquals(FileBrowserFileKind.FOLDER, fileBrowserFileKind(folder("Holiday")))
        assertEquals(
            "an id the shelfmark cannot name is no platform, exactly as the cover and the inspector read it",
            FileBrowserFileKind.FOLDER,
            fileBrowserFileKind(folder("Odd", platformIdValue = "___")),
        )
    }

    @Test
    fun `a recognised file is a GAME and wears the platform's own plate`() {
        assertEquals(FileBrowserFileKind.GAME, fileBrowserFileKind(tile("Metroid.gba", platformIdValue = "gba")))
        assertEquals(
            "a recognised archive is still a game: its plate is what the player scans for",
            FileBrowserFileKind.GAME,
            fileBrowserFileKind(tile("Metroid.gba.zip", kind = FileBrowserTileKind.ARCHIVE, platformIdValue = "gba")),
        )
    }

    @Test
    fun `a game whose platform is unsettled is still a game, with the word GAME and no plate`() {
        val unsettled = tile("Mystery Disc.iso", gameLike = true)
        assertEquals(FileBrowserFileKind.GAME_UNKNOWN, fileBrowserFileKind(unsettled))
        assertEquals("Game", fileBrowserBadgeLabel(english, unsettled))
        assertEquals(
            "an id nothing can name is the same answer: a game whose platform cannot be said",
            FileBrowserFileKind.GAME_UNKNOWN,
            fileBrowserFileKind(tile("x.rom", platformIdValue = "___")),
        )
    }

    @Test
    fun `a BIOS is a BIOS, even where a platform also resolved`() {
        assertEquals(FileBrowserFileKind.BIOS, fileBrowserFileKind(tile("gba_bios.bin", bios = true)))
        assertEquals(
            "gba_bios.bin in a gba folder resolves to GBA; calling it a GBA game is the mislabel BIOS prevents",
            FileBrowserFileKind.BIOS,
            fileBrowserFileKind(tile("gba_bios.bin", platformIdValue = "gba", bios = true)),
        )
        assertEquals("BIOS", fileBrowserBadgeLabel(english, tile("gba_bios.bin", bios = true)))
    }

    @Test
    fun `archives are archives, and name their own format`() {
        val zip = tile("pack.zip", kind = FileBrowserTileKind.ARCHIVE)
        val sevenZip = tile("pack.7z", kind = FileBrowserTileKind.ARCHIVE)
        assertEquals(FileBrowserFileKind.ARCHIVE, fileBrowserFileKind(zip))
        assertEquals("ZIP", fileBrowserBadgeLabel(english, zip))
        assertEquals("7Z", fileBrowserBadgeLabel(english, sevenZip))
        assertEquals(
            "a recognised archive has a plate on its card, but its tag still says what the player can do with it",
            "ZIP",
            fileBrowserBadgeLabel(english, tile("Metroid.gba.zip", kind = FileBrowserTileKind.ARCHIVE, platformIdValue = "gba")),
        )
        // Mimir cannot extract these, but they are archives all the same -- and say which kind.
        assertEquals(FileBrowserFileKind.ARCHIVE, kindOf("backup.tar"))
        assertEquals("TAR", fileBrowserBadgeLabel(english, tile("backup.tar")))
        assertEquals("RAR", fileBrowserBadgeLabel(english, tile("roms.rar")))
        assertEquals(
            "an unrecognised zip is first of all an archive, even when the registry thinks it could be a game",
            FileBrowserFileKind.ARCHIVE,
            fileBrowserFileKind(tile("thing.zip", kind = FileBrowserTileKind.ARCHIVE, gameLike = true)),
        )
    }

    @Test
    fun `saves, data and documents are told apart`() {
        listOf("Pokemon.srm", "slot.sav", "quick.state", "slot.st0", "slot.st9").forEach {
            assertEquals(it, FileBrowserFileKind.SAVE, kindOf(it))
        }
        listOf("library.dat", "cache.db", ".nomedia").forEach {
            assertEquals(it, FileBrowserFileKind.DATA, kindOf(it))
        }
        listOf("notes.txt", "gamelist.xml", "retroarch.cfg", "emu.ini", "run.log").forEach {
            assertEquals("$it is readable", FileBrowserFileKind.DOC, kindOf(it))
        }
        assertEquals("Save", fileBrowserBadgeLabel(english, tile("Pokemon.srm")))
        assertEquals("Data", fileBrowserBadgeLabel(english, tile("library.dat")))
        assertEquals("Doc", fileBrowserBadgeLabel(english, tile("notes.txt")))
    }

    @Test
    fun `media is music, image or video -- and mp4 is video, whatever core-audio indexes`() {
        assertEquals(FileBrowserFileKind.MUSIC, kindOf("theme.mp3"))
        assertEquals(FileBrowserFileKind.VIDEO, kindOf("snap.mp4"))
        listOf("box.png", "box.jpg", "box.jpeg", "box.webp").forEach {
            assertEquals(it, FileBrowserFileKind.IMAGE, kindOf(it))
        }
        assertEquals("Music", fileBrowserBadgeLabel(english, tile("theme.mp3")))
        assertEquals("Video", fileBrowserBadgeLabel(english, tile("snap.mp4")))
        assertEquals("Image", fileBrowserBadgeLabel(english, tile("box.png")))
    }

    @Test
    fun `formats the extension sets do not name are typed by MIME, which only a device fills in`() {
        assertEquals(FileBrowserFileKind.MUSIC, kindOf("track.flac", mime = "audio/flac"))
        assertEquals(FileBrowserFileKind.VIDEO, kindOf("clip.mkv", mime = "video/x-matroska"))
        assertEquals(FileBrowserFileKind.IMAGE, kindOf("anim.gif", mime = "image/gif"))
        assertEquals(
            "without a MIME type the same name is just a file: the host cannot guess what only a device knows",
            FileBrowserFileKind.MISC,
            kindOf("track.flac"),
        )
    }

    @Test
    fun `a PDF is a document, and an apk is an app even though the registry knows the extension`() {
        assertEquals(FileBrowserFileKind.DOC, kindOf("manual.pdf"))
        assertEquals(FileBrowserFileKind.DOC, kindOf("manual", mime = "application/pdf"))
        assertEquals(FileBrowserFileKind.APP, kindOf("tool.apk"))
        assertEquals(
            "the game-like test must not turn every downloaded app into a game",
            FileBrowserFileKind.APP,
            fileBrowserFileKind(tile("tool.apk", gameLike = true)),
        )
        assertEquals("App", fileBrowserBadgeLabel(english, tile("tool.apk")))
    }

    @Test
    fun `a file Mimir has no type for is still a card with a word`() {
        assertEquals(FileBrowserFileKind.MISC, kindOf("mystery.xyz"))
        assertEquals("File", fileBrowserBadgeLabel(english, tile("mystery.xyz")))
    }

    @Test
    fun `no kind has an empty badge, and none is the old dash`() {
        val samples = listOf(
            folder("a"), folder("GBA", platformIdValue = "gba"), tile("m.gba", platformIdValue = "gba"),
            tile("m.iso", gameLike = true),
            tile("x.srm"), tile("x.dat"), tile("x.mp3"), tile("x.png"), tile("x.mp4"), tile("x.tar"),
            tile("x.txt"), tile("x.apk"), tile("b.bin", bios = true), tile("x.xyz"),
            tile("x.zip", kind = FileBrowserTileKind.ARCHIVE),
        )
        assertEquals(
            "the sample set must reach every kind, or this test proves less than it says",
            FileBrowserFileKind.entries.toSet(),
            samples.map(::fileBrowserFileKind).toSet(),
        )
        samples.forEach {
            val label = fileBrowserBadgeLabel(english, it)
            assertTrue("${it.name} must wear a word", label.isNotBlank())
            assertNotEquals("${it.name}: a dash says nothing", "—", label)
        }
    }

    // ── the cards ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a game is struck in the accent, media in the counter-accent, and the pairing never swaps`() {
        val accent = FileBrowserCardStyle(FileBrowserCardHue.ACCENT, FileBrowserCardHue.ACCENT, scanlines = false)
        val media = FileBrowserCardStyle(FileBrowserCardHue.COUNTER_ACCENT, FileBrowserCardHue.COUNTER_ACCENT, scanlines = false)
        assertEquals(accent, fileBrowserCardStyle(FileBrowserFileKind.GAME))
        assertEquals("a game with no platform is the same card as one with", accent, fileBrowserCardStyle(FileBrowserFileKind.GAME_UNKNOWN))
        listOf(FileBrowserFileKind.MUSIC, FileBrowserFileKind.IMAGE, FileBrowserFileKind.VIDEO).forEach {
            assertEquals(it.name, media, fileBrowserCardStyle(it))
        }
    }

    @Test
    fun `saves, data and BIOS carry the accent as a rule only, an archive the leather's, a document scanlines`() {
        listOf(FileBrowserFileKind.SAVE, FileBrowserFileKind.DATA, FileBrowserFileKind.BIOS).forEach {
            assertEquals(it.name, FileBrowserCardStyle(FileBrowserCardHue.NONE, FileBrowserCardHue.ACCENT, false), fileBrowserCardStyle(it))
        }
        assertEquals(
            FileBrowserCardStyle(FileBrowserCardHue.NONE, FileBrowserCardHue.COVER, false),
            fileBrowserCardStyle(FileBrowserFileKind.ARCHIVE),
        )
        assertEquals(
            FileBrowserCardStyle(FileBrowserCardHue.NONE, FileBrowserCardHue.NONE, scanlines = true),
            fileBrowserCardStyle(FileBrowserFileKind.DOC),
        )
        listOf(FileBrowserFileKind.APP, FileBrowserFileKind.MISC).forEach {
            assertEquals("${it.name} is plain, at full presence", FileBrowserCardStyle(FileBrowserCardHue.NONE, FileBrowserCardHue.NONE, false), fileBrowserCardStyle(it))
        }
    }

    @Test
    fun `the wash is a tint, not a flood`() {
        assertEquals(0.14f, FileBrowserCardWashFraction, 0f)
        assertEquals(2f, FileBrowserCardRuleDp, 0f)
    }

    @Test
    fun `Mimir's own folders are labelled, a held delete says Deleting, and a plain folder says Folder`() {
        assertEquals("Folder", fileBrowserTomeEyebrow(english, folder("Roms")))
        assertEquals("Mimir", fileBrowserTomeEyebrow(english, folder("saves", machinery = true)))
        assertEquals("Mimir", fileBrowserTomeEyebrow(english, folder("media", machinery = true)))
        assertEquals("Mimir", fileBrowserTomeEyebrow(english, folder(".mimir", machinery = true)))
        assertEquals("Deleting…", fileBrowserTomeEyebrow(english, folder(FileOperationStaging.ROOT_DIRECTORY, machinery = true)))
        assertEquals(
            "the label follows the machinery flag the partition decides, not the bare name",
            "Folder",
            fileBrowserTomeEyebrow(english, folder("saves", machinery = false)),
        )
    }

    // ── the image picker's view ──────────────────────────────────────────────────────────────

    private fun partitionOf(vararg names: String): FileBrowserPartition =
        fileBrowserPartition(
            names.map { name ->
                if (name.endsWith("/")) {
                    FileItem(name = name.trimEnd('/'), path = "/root/$name", isDirectory = true)
                } else {
                    file(name)
                }
            },
        ) { FileBrowserRecognition.None }

    @Test
    fun `images only keeps folders and images and counts what it leaves out`() {
        val full = partitionOf("Pictures/", "cover.png", "disc.iso", "pack.zip", "THEME.JPG")

        val view = fileBrowserImagePickListing(FileBrowserMode.PICK_IMAGE, showAll = false, full = full)

        assertEquals(listOf("Pictures"), view.partition.tomes.map { it.name })
        assertEquals(listOf("cover.png", "THEME.JPG"), view.partition.files.map { it.name })
        assertEquals(2, view.hiddenCount)
    }

    @Test
    fun `all files keeps everything, and still counts the files the picker cannot take`() {
        val full = partitionOf("cover.png", "disc.iso")

        val view = fileBrowserImagePickListing(FileBrowserMode.PICK_IMAGE, showAll = true, full = full)

        assertEquals(full, view.partition)
        assertEquals(1, view.hiddenCount)
    }

    @Test
    fun `every other mode sees the listing untouched, whatever the flag says`() {
        val full = partitionOf("cover.png", "disc.iso")
        FileBrowserMode.entries.filter { it != FileBrowserMode.PICK_IMAGE }.forEach { mode ->
            listOf(true, false).forEach { showAll ->
                val view = fileBrowserImagePickListing(mode, showAll, full)
                assertEquals("$mode/$showAll", full, view.partition)
                assertEquals("$mode/$showAll", 0, view.hiddenCount)
            }
        }
    }

    @Test
    fun `search hits follow the same filter, and folders always survive`() {
        val hits = listOf(
            FileItem(name = "Covers", path = "/v/Covers", isDirectory = true),
            file("cover.png"),
            file("cover.iso"),
        )

        assertEquals(
            listOf("Covers", "cover.png"),
            fileBrowserImagePickSearchResults(FileBrowserMode.PICK_IMAGE, showAll = false, results = hits).map { it.name },
        )
        assertEquals(hits, fileBrowserImagePickSearchResults(FileBrowserMode.PICK_IMAGE, showAll = true, results = hits))
        assertEquals(hits, fileBrowserImagePickSearchResults(FileBrowserMode.MANAGE, showAll = false, results = hits))
    }

    @Test
    fun `the inspector gives the reason a file cannot be chosen, and only for a file that cannot`() {
        assertEquals("Not an image", fileBrowserImagePickNote(english, FileBrowserMode.PICK_IMAGE, tile("disc.iso")))
        assertEquals(null, fileBrowserImagePickNote(english, FileBrowserMode.PICK_IMAGE, tile("cover.png")))
        assertEquals(null, fileBrowserImagePickNote(english, FileBrowserMode.PICK_IMAGE, folder("Pictures")))
        assertEquals(null, fileBrowserImagePickNote(english, FileBrowserMode.PICK_IMAGE, null))
        assertEquals(null, fileBrowserImagePickNote(english, FileBrowserMode.MANAGE, tile("disc.iso")))
    }

    @Test
    fun `the pinned switch says what is hidden, then how to go back -- and A names its result`() {
        val hidden = fileBrowserCommitCopy(english, FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER, imagePickHiddenCount = 7)
        assertEquals("Show", hidden.eyebrow)
        assertEquals("All files · 7 hidden", hidden.label)
        val all = fileBrowserCommitCopy(english, FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER, imagePickHiddenCount = 7, imagePickShowsAll = true)
        assertEquals("Images only", all.label)

        assertEquals(
            listOf("A" to "Show all", "B" to "Back"),
            fileBrowserFooterHints(english,
                null, atRoot = false, commitFocused = true,
                commitPurpose = FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER,
            ),
        )
        assertEquals(
            listOf("A" to "Images only", "B" to "Back"),
            fileBrowserFooterHints(english,
                null, atRoot = false, commitFocused = true,
                commitPurpose = FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER,
                imageFilterShowsAll = true,
            ),
        )
    }

    @Test
    fun `an images-only listing with files hidden never calls the folder empty`() {
        assertEquals("This folder is empty.", fileBrowserEmptyNote(english, hiddenCount = 0))
        assertEquals("No images in this folder.", fileBrowserEmptyNote(english, hiddenCount = 3))
    }

    // ── nothing is faded ─────────────────────────────────────────────────────────────────────

    private val alphaTokens = Regex("""graphicsLayer|ModulateAlpha|DimAlpha|\.alpha\(|alpha = if""")

    @Test
    fun `no card in the folio, the search or the picker is drawn with alpha`() {
        val surface = sourceOf("FileBrowserSurface.kt")
        val slices = mapOf(
            "TomeTile" to surface.substringAfter("private fun TomeTile(").substringBefore("private fun TomeGlyph("),
            "FileTile" to surface.substringAfter("private fun FileTile(").substringBefore("internal fun fileBrowserDisplayName("),
            "search row" to sourceOf("FileBrowserSearchSurface.kt")
                .substringAfter("private fun FileBrowserSearchResultRow(")
                .substringBefore("private fun FileBrowserSearchScopeCard("),
        )
        slices.forEach { (name, slice) ->
            assertTrue("$name slice is empty -- an anchor moved", slice.length > 200)
            assertFalse("$name applies alpha: ${alphaTokens.find(slice)?.value}", alphaTokens.containsMatchIn(slice))
        }
    }

    @Test
    fun `the 0_55 demotion constant is gone from every source in the module`() {
        val dir = listOf(
            File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files"),
        ).first(File::isDirectory)
        val offenders = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
            .filter { it.readText().contains("FileBrowserDimAlpha") }
            .map { it.name }
        assertEquals("these files still name the retired constant", emptyList<String>(), offenders)
    }

    @Test
    fun `names read in textPrimary and meta in textSecondary, never the second grey`() {
        val tileSource = sourceOf("FileBrowserSurface.kt")
            .substringAfter("private fun FileTile(")
            .substringBefore("internal fun fileBrowserDisplayName(")
        assertTrue(tileSource.contains("color = palette.textPrimary"))
        assertTrue(tileSource.contains("color = palette.textSecondary"))
        assertFalse("textMuted on a tinted wash is under 4.5:1 at 10sp", tileSource.contains("textMuted"))
    }

    @Test
    fun `no badge is filled, an extractable archive's is told by the ember of its word and outline`() {
        val tileSource = sourceOf("FileBrowserSurface.kt")
            .substringAfter("private fun FileTile(")
            .substringBefore("internal fun fileBrowserDisplayName(")
        assertTrue(tileSource.contains("archive = tile.kind == FileBrowserTileKind.ARCHIVE"))
        val badge = tileSource.substringAfter("internal fun FileBrowserBadge(")
        assertTrue("every badge is outlined, the archive's in the ember and the rest in the theme's outline", badge.contains(".border(1.dp, if (archive) palette.emberHot else palette.outline, pill)"))
        assertTrue("the archive's word is ember too", badge.contains("color = if (archive) palette.emberHot else palette.textSecondary"))
        assertFalse("no filled or tinted chips", badge.contains("background(") || badge.contains("emberSoft"))
    }

    private fun sourceOf(name: String): String = listOf(
        File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
    ).first(File::isFile).readText()
}
