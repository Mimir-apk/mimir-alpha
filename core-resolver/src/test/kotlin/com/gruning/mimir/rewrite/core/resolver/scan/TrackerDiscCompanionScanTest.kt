package com.gruning.mimir.rewrite.core.resolver.scan

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.GameGroup
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.resolver.KeyingDedupGrouper
import com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DuckStation scan fixtures: an m3u with relative entries, entries under a hidden-style
 * folder, an ES-DE-style FOLDER named `Game.m3u/`, and the `.sbi` / `.sub` / track `.bin` companions.
 *
 * These run the REAL plain-path [SourceWalker] over a real temporary tree and then the same recognise ->
 * m3u-resolve -> group stages `LibraryRepositoryImpl.rescan` runs, so what is pinned is what a rescan
 * produces, not a stub.
 *
 * Why the launch side needs no rewrite of the playlist: Mimir hands DuckStation the playlist's own
 * ABSOLUTE PATH as `bootPath` (never a content URI, which is what broke Cocoon D#414), so DuckStation
 * resolves the relative entries against the playlist's own folder exactly as it does when opened by hand.
 * The group's primary [ResidentFile.managedPath] below is that path.
 */
class TrackerDiscCompanionScanTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class Scanned(
        val walked: List<WalkedFile>,
        val skipped: Map<String, SkipReason>,
        val residents: List<ResidentFile>,
        val groups: List<GameGroup>,
    )

    private fun write(root: File, relative: String, body: String = "x"): File {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText(body)
        return file
    }

    private fun scan(root: File): Scanned = runBlocking {
        val walker = SourceWalker(SourceWalkLimits())
        val walked = (walker.walk(root.path) as SourceWalkResult.Complete).files
        val recognizer = ScanRecognizer()
        val skipped = linkedMapOf<String, SkipReason>()
        val recognized = mutableListOf<ResidentFile>()
        for (file in walked) {
            when (val result = recognizer.recognize(file, root.path)) {
                is RecognitionResult.Recognized -> recognized += result.file
                is RecognitionResult.Skipped -> skipped[file.relativePath] = result.reason
            }
        }
        val resolution = M3uPlaylistResolver.resolve(walked, recognized) { file, max ->
            walker.readUtf8(file, max)
        }
        Scanned(
            walked = walked,
            skipped = skipped,
            residents = resolution.files,
            groups = KeyingDedupGrouper().group(resolution.files, resolution.groups),
        )
    }

    private fun sameFile(expected: File, actual: String?) {
        assertNotNull(actual)
        assertEquals(expected.canonicalFile, File(actual!!).canonicalFile)
    }

    // ---- row 1: relative entries ------------------------------------------------------------------

    @Test
    fun relativeEntriesGroupTheDiscsBehindThePlaylistAndTheLocatorIsThePlaylistsOwnPath() {
        val root = temporaryFolder.newFolder("roms")
        val playlist = write(
            root,
            "PSX/Final Fantasy VII.m3u",
            "#EXTM3U\nFinal Fantasy VII (Disc 1).chd\nFinal Fantasy VII (Disc 2).chd\n",
        )
        write(root, "PSX/Final Fantasy VII (Disc 1).chd")
        write(root, "PSX/Final Fantasy VII (Disc 2).chd")

        val scanned = scan(root)

        assertEquals("one shelf tile for the whole set", 1, scanned.groups.size)
        val group = scanned.groups.single()
        assertEquals(ContentKind.MULTI_DISC_PLAYLIST, group.primary.contentKind)
        assertEquals(3, group.memberCount)
        assertEquals(
            "disc order follows the playlist",
            listOf(1, 2),
            group.allMembers.filter { it.contentKind == ContentKind.DISC_MEMBER }.map { it.discNumber }.sorted(),
        )
        sameFile(playlist, group.primary.managedPath)
        assertTrue(
            "the locator is an absolute filesystem path, never a content URI",
            File(group.primary.managedPath!!).isAbsolute && group.primary.sourceDocumentUri == null,
        )
    }

    // ---- row 1: entries under a hidden-style folder ------------------------------------------------

    @Test
    fun entriesUnderAnUnderscoreHiddenFolderResolveAndDoNotBecomeTheirOwnTiles() {
        val root = temporaryFolder.newFolder("roms")
        write(root, "PSX/Game.m3u", "_hidden/Game (Disc 1).chd\n_hidden/Game (Disc 2).chd\n")
        write(root, "PSX/_hidden/Game (Disc 1).chd")
        write(root, "PSX/_hidden/Game (Disc 2).chd")

        val scanned = scan(root)

        assertEquals(
            "`_hidden` is a plain folder name, not a dot-folder, so the discs are recognised and then " +
                "folded behind the playlist that names them",
            1,
            scanned.groups.size,
        )
        val group = scanned.groups.single()
        assertEquals(ContentKind.MULTI_DISC_PLAYLIST, group.primary.contentKind)
        assertEquals(3, group.memberCount)
    }

    @Test
    fun entriesUnderADotHiddenFolderStillLeaveOneLaunchablePlaylistTile() {
        val root = temporaryFolder.newFolder("roms")
        val playlist = write(root, "PSX/Game.m3u", ".hidden/Game (Disc 1).chd\n.hidden/Game (Disc 2).chd\n")
        write(root, "PSX/.hidden/Game (Disc 1).chd")
        write(root, "PSX/.hidden/Game (Disc 2).chd")

        val scanned = scan(root)

        assertEquals(SkipReason.HIDDEN_PATH, scanned.skipped["PSX/.hidden/Game (Disc 1).chd"])
        assertEquals(SkipReason.HIDDEN_PATH, scanned.skipped["PSX/.hidden/Game (Disc 2).chd"])
        assertEquals("the playlist alone is the tile", 1, scanned.groups.size)
        val group = scanned.groups.single()
        assertEquals(ContentKind.MULTI_DISC_PLAYLIST, group.primary.contentKind)
        sameFile(playlist, group.primary.managedPath)
    }

    // ---- row 2: ES-DE-style folder named Game.m3u/ ---------------------------------------------------

    @Test
    fun aFolderNamedDotM3uIsDescendedAndTheLocatorIsTheFileInsideItNeverTheFolder() {
        val root = temporaryFolder.newFolder("roms")
        val inner = write(
            root,
            "PSX/Game.m3u/Game.m3u",
            "Game (Disc 1).chd\nGame (Disc 2).chd\n",
        )
        write(root, "PSX/Game.m3u/Game (Disc 1).chd")
        write(root, "PSX/Game.m3u/Game (Disc 2).chd")

        val scanned = scan(root)

        assertTrue(
            "directories are descended, never emitted as library files",
            scanned.walked.none { File(it.filePath!!).isDirectory },
        )
        assertEquals("one tile", 1, scanned.groups.size)
        val group = scanned.groups.single()
        assertEquals(ContentKind.MULTI_DISC_PLAYLIST, group.primary.contentKind)
        assertEquals(3, group.memberCount)
        sameFile(inner, group.primary.managedPath)
        assertTrue(
            "DuckStation is handed a FILE; handing it the folder is what produced 'not a valid m3u'",
            File(group.primary.managedPath!!).isFile,
        )
    }

    // ---- row 3: companions ----------------------------------------------------------------------------

    @Test
    fun sbiAndSubCompanionsNeverBecomeEntriesAndTrackBinsFoldBehindTheirCue() {
        val root = temporaryFolder.newFolder("roms")
        val disc = write(root, "PSX/Libcrypt Game (Europe).chd")
        val sbi = write(root, "PSX/Libcrypt Game (Europe).sbi")
        val sub = write(root, "PSX/Libcrypt Game (Europe).sub")
        write(root, "PSX/Tracked.cue", "FILE \"Tracked (Track 1).bin\" BINARY\n")
        write(root, "PSX/Tracked (Track 1).bin")
        write(root, "PSX/Tracked (Track 2).bin")

        val scanned = scan(root)

        assertEquals(SkipReason.SIDECAR_EXTENSION, scanned.skipped["PSX/Libcrypt Game (Europe).sbi"])
        assertEquals(SkipReason.SIDECAR_EXTENSION, scanned.skipped["PSX/Libcrypt Game (Europe).sub"])
        assertTrue(
            "no resident carries a companion extension",
            scanned.residents.none { it.displayName.substringAfterLast('.').lowercase() in setOf("sbi", "sub") },
        )
        assertEquals("the libcrypt disc and the cue set are the only two tiles", 2, scanned.groups.size)
        val cueSet = scanned.groups.single { it.primary.displayName == "Tracked.cue" }
        assertEquals(ContentKind.TRACK_MANIFEST, cueSet.primary.contentKind)
        assertEquals("the cue and both track files are one tile", 3, cueSet.memberCount)
        assertTrue(
            "no .bin track is a shelf tile of its own",
            scanned.groups.none { it.primary.displayName.endsWith(".bin", ignoreCase = true) },
        )

        assertTrue("the walk is read-only: the disc is untouched", disc.isFile)
        assertTrue("the .sbi is still beside the disc after a scan, which is where DuckStation reads it", sbi.isFile)
        assertEquals(disc.parentFile.canonicalFile, sbi.parentFile.canonicalFile)
        assertTrue("the .sub is untouched", sub.isFile)
    }
}
