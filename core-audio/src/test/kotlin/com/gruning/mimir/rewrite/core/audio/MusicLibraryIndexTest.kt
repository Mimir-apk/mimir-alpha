package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Custom music folder's scan, over both shapes of folder the player can choose:
 * a system-picker tree (documents with ids and a MIME type) and a plain folder from Mimir's own browser (a `file://` URI and
 * a directory). The same rules run on both, so both are exercised here without a device.
 */
class MusicLibraryIndexTest {
    // ── what counts as a track, and what counts as a platform folder ─────────────────────────────────

    @Test
    fun `the supported audio formats are tracks in any letter case, and nothing else is`() {
        listOf("a.mp3", "b.M4A", "c.Ogg", "d.FLAC", "e.wav", "f.opus", "g.OGA", "h.aac", "i.adts", "j.m4b", "k.Mp4", "Song With Spaces.mp3", "a.b.c.mp3").forEach {
            assertTrue(it, MusicLibraryScanner.isSupportedMusicFile(it))
        }
        listOf("a.txt", "cover.jpg", "playlist.m3u", "a.mp3.bak", "mp3", ".mp3", "noextension", "trailingdot.", "a.wma", "a.aif", "a.ape", "a.wv", "a.dsf", "a.alac").forEach {
            assertFalse(it, MusicLibraryScanner.isSupportedMusicFile(it))
        }
    }

    @Test
    fun `hidden files and the resource forks a Mac leaves beside every file are not music`() {
        assertFalse(MusicLibraryScanner.isSupportedMusicFile("._song.mp3"))
        assertFalse(MusicLibraryScanner.isSupportedMusicFile(".hidden.flac"))
    }

    /**
     * What the app tells the scan: the platform names the game scanner knows, by folder name (any case, punctuation ignored, as the registry's folder index does) to the
     * platform's id. A stand-in for `ExtensionPlatformClassifier.platformHintFromFolder`; the scan itself knows no platform.
     */
    private val platforms = MusicPlatformFolders { name ->
        val ids = mapOf("snes" to "snes", "ps2" to "ps2", "psp" to "psp", "pcgames" to "pc_games", "genesis" to "megadrive")
        ids[name.lowercase().filter { it.isLetterOrDigit() }]
    }

    @Test
    fun `an artist's folder is plain music however short or capitalised its name`() {
        // The review's P1: any one-word folder used to read as a platform's, so a library of artist folders was skipped by general playback and shuffle never reached Queen.
        val artists = listOf("Queen", "ABBA", "U2", "Radiohead", "Jay-Z", "beatles", "Bjork")
        val folders = buildList {
            add("" to (artists.map { dir("root/$it", it) } + dir("root/snes", "snes") + doc("root/loose.mp3", "loose.mp3")))
            artists.forEach { add("root/$it" to listOf(dir("root/$it/Album", "Album"), doc("root/$it/single.mp3", "single.mp3"))) }
            artists.forEach { add("root/$it/Album" to listOf(doc("root/$it/Album/01.mp3", "01.mp3"))) }
            add("root/snes" to listOf(doc("root/snes/zelda.flac", "zelda.flac")))
        }
        val index = scanOf(listing(*folders.toTypedArray()))

        for (artist in artists) {
            val theirs = index.tracks.filter { it.id.startsWith("root/$artist/") }
            assertEquals(artist, 2, theirs.size)
            assertEquals("$artist is not a platform", setOf(""), theirs.map { it.folder }.toSet())
        }
        assertEquals("snes", index.tracks.single { it.name == "zelda.flac" }.folder)
        val general = FrontendMusicSourceResolver.generalTracks(index.tracks)
        assertTrue("shuffle reaches Queen", general.any { it.id == "root/Queen/Album/01.mp3" })
        assertTrue("and U2 and ABBA", general.any { it.id == "root/U2/single.mp3" } && general.any { it.id == "root/ABBA/single.mp3" })
        assertFalse("the platform's own music is still left to the platform", general.any { it.folder == "snes" })
    }

    @Test
    fun `only a name the platforms know is a platform folder, and it keeps the platform's id however it is spelled`() {
        val index = scanOf(
            listing(
                "" to listOf(dir("p1", "PCGames"), dir("p2", "pc_games"), dir("p3", "PS2"), dir("p4", " Genesis "), dir("p5", "Album 1"), dir("p6", "Rock (live)")),
                "p1" to listOf(doc("p1/a.mp3", "a.mp3")),
                "p2" to listOf(doc("p2/b.mp3", "b.mp3")),
                "p3" to listOf(doc("p3/c.mp3", "c.mp3")),
                "p4" to listOf(doc("p4/d.mp3", "d.mp3")),
                "p5" to listOf(doc("p5/e.mp3", "e.mp3")),
                "p6" to listOf(doc("p6/f.mp3", "f.mp3")),
            ),
        )
        assertEquals(
            mapOf("a.mp3" to "pc_games", "b.mp3" to "pc_games", "c.mp3" to "ps2", "d.mp3" to "megadrive", "e.mp3" to "", "f.mp3" to ""),
            index.tracks.associate { it.name to it.folder },
        )
    }

    @Test
    fun `with no platform names given, no folder is a platform's`() {
        val list = listing("" to listOf(dir("snes", "snes")), "snes" to listOf(doc("z.mp3", "z.mp3")))
        val index = (MusicLibraryScanner.scan("t", "", 1L, list) as MusicScanResult.Scanned).index
        assertEquals(listOf("" to "z.mp3"), index.tracks.map { it.folder to it.name })
    }

    @Test
    fun `the platform names are asked about the picked folder's own sub-folders and nothing deeper`() {
        val asked = ArrayList<String>()
        val recording = MusicPlatformFolders { name -> asked += name; null }
        val list = listing(
            "" to listOf(dir("a", "Artist"), dir("b", "snes")),
            "a" to listOf(dir("a/x", "Album")),
            "a/x" to listOf(doc("a/x/1.mp3", "1.mp3")),
            "b" to listOf(dir("b/y", "deeper")),
            "b/y" to listOf(doc("b/y/2.mp3", "2.mp3")),
        )
        MusicLibraryScanner.scan("t", "", 1L, list, platformFolders = recording)
        assertEquals(listOf("Artist", "snes"), asked.sortedBy { it.lowercase() })
    }

    // ── a system-picker tree: documents with ids, listed by id ───────────────────────────────────────

    private fun doc(id: String, name: String) = MusicEntry(id = id, name = name, isDirectory = false)

    private fun dir(id: String, name: String) = MusicEntry(id = id, name = name, isDirectory = true)

    private val pickerTree = mapOf(
        "primary:music" to listOf(
            doc("primary:music/b-intro.mp3", "b-intro.mp3"),
            doc("primary:music/a-theme.ogg", "a-theme.ogg"),
            doc("primary:music/readme.txt", "readme.txt"),
            dir("primary:music/snes", "snes"),
            dir("primary:music/PS2", "PS2"),
            dir("primary:music/Album 1", "Album 1"),
            dir("primary:music/empty", "empty"),
        ),
        "primary:music/snes" to listOf(
            doc("primary:music/snes/zelda.flac", "zelda.flac"),
            doc("primary:music/snes/cover.jpg", "cover.jpg"),
            dir("primary:music/snes/deeper", "deeper"),
        ),
        "primary:music/PS2" to listOf(doc("primary:music/PS2/ico.m4a", "ico.m4a"), doc("primary:music/PS2/ff.wav", "ff.wav")),
        "primary:music/Album 1" to listOf(doc("primary:music/Album 1/x.mp3", "x.mp3")),
        "primary:music/empty" to emptyList(),
        "primary:music/snes/deeper" to listOf(doc("primary:music/snes/deeper/nested.mp3", "nested.mp3")),
    )

    private fun scanPicker(list: (String) -> List<MusicEntry>? = { pickerTree[it] }) =
        MusicLibraryScanner.scan(tree = "content://tree/primary%3Amusic", rootId = "primary:music", nowMillis = 1_000L, list = list, platformFolders = platforms)

    @Test
    fun `a picker tree gives root files as general tracks, each platform folder its own, and reads every other folder too`() {
        val index = (scanPicker() as MusicScanResult.Scanned).index

        assertEquals("content://tree/primary%3Amusic", index.tree)
        assertEquals(1_000L, index.scannedAtMillis)
        assertEquals(
            listOf(
                MusicTrack("", "a-theme.ogg", "primary:music/a-theme.ogg"),
                MusicTrack("", "b-intro.mp3", "primary:music/b-intro.mp3"),
                MusicTrack("", "x.mp3", "primary:music/Album 1/x.mp3"),
                MusicTrack("ps2", "ff.wav", "primary:music/PS2/ff.wav"),
                MusicTrack("ps2", "ico.m4a", "primary:music/PS2/ico.m4a"),
                MusicTrack("snes", "nested.mp3", "primary:music/snes/deeper/nested.mp3"),
                MusicTrack("snes", "zelda.flac", "primary:music/snes/zelda.flac"),
            ),
            index.tracks.sortedWith(compareBy({ it.folder }, { it.name })),
        )
        assertEquals(7, index.trackCount)
    }

    @Test
    fun `an unreadable root ends the scan and says why, while an unreadable sub-folder is only skipped`() {
        assertEquals(MusicScanResult.Unreadable, scanPicker { null })
        assertEquals(MusicScanResult.GrantLost, scanPicker { throw SecurityException("grant revoked") })
        assertEquals(MusicScanResult.Unreadable, scanPicker { throw IllegalStateException("provider died") })

        val partial = scanPicker { id -> if (id == "primary:music/snes") null else pickerTree[id] } as MusicScanResult.Scanned
        assertEquals("the rest is still indexed", 5, partial.index.trackCount)

        val throwing = scanPicker { id -> if (id == "primary:music/PS2") throw IllegalStateException("one folder") else pickerTree[id] } as MusicScanResult.Scanned
        assertEquals(5, throwing.index.trackCount)
    }

    @Test
    fun `an empty folder is a scanned folder with no tracks, not an error`() {
        val index = (scanPicker { emptyList() } as MusicScanResult.Scanned).index
        assertEquals(0, index.trackCount)
    }

    @Test
    fun `a folder with more tracks than the index keeps stops at the cap`() {
        val many = (1..MusicLibraryScanner.MaxIndexedTracks + 500).map { doc("id$it", "track$it.mp3") }
        val index = (scanPicker { many } as MusicScanResult.Scanned).index
        assertEquals(MusicLibraryScanner.MaxIndexedTracks, index.trackCount)
    }

    // ── a plain folder from Mimir's own browser: a file:// URI and a directory ───────────────────────

    private fun makeFile(parent: File, name: String) = File(parent, name).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    @Test
    fun `a plain folder is scanned with the same rules and its tracks are opened by path`() {
        val root = Files.createTempDirectory("mimir-music").toFile()
        try {
            makeFile(root, "overworld.mp3")
            makeFile(root, "notes.txt")
            makeFile(root, "._overworld.mp3")
            makeFile(File(root, "snes"), "zelda.flac")
            makeFile(File(root, "SNES2"), "a.ogg")
            makeFile(File(root, "Album 1"), "read-now.mp3")
            makeFile(File(root, "snes/deeper"), "nested.mp3")
            File(root, "empty").mkdirs()

            val tree = "file://" + root.toURI().path.trimEnd('/')
            val result = MusicLibraryScanner.scanPlainDirectory(tree, root.absolutePath, nowMillis = 7L, platformFolders = platforms)
            val index = (result as MusicScanResult.Scanned).index

            assertEquals(tree, index.tree)
            assertEquals(
                "SNES2 is no platform's name, so its track is plain music",
                listOf("" to "a.ogg", "" to "overworld.mp3", "" to "read-now.mp3", "snes" to "nested.mp3", "snes" to "zelda.flac"),
                index.tracks.sortedWith(compareBy({ it.folder }, { it.name })).map { it.folder to it.name },
            )
            assertEquals(5, index.trackCount)
            assertEquals(
                "a plain folder's tracks are opened by their absolute path",
                File(root, "overworld.mp3").absolutePath,
                index.tracks.single { it.name == "overworld.mp3" }.id,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a plain folder that is missing or is a file is unreadable`() {
        val root = Files.createTempDirectory("mimir-music").toFile()
        try {
            assertEquals(MusicScanResult.Unreadable, MusicLibraryScanner.scanPlainDirectory("file:///x", File(root, "gone").absolutePath, 1L))
            val file = makeFile(root, "a.mp3")
            assertEquals(MusicScanResult.Unreadable, MusicLibraryScanner.scanPlainDirectory("file:///x", file.absolutePath, 1L))
        } finally {
            root.deleteRecursively()
        }
    }

    // ── the two shapes of stored folder ──────────────────────────────────────────────────────────────

    @Test
    fun `a file URI is a plain folder with a path and no persistable grant, and anything else is a picker tree`() {
        val plain = "file:///storage/emulated/0/ROMs/music"
        assertTrue(MusicTreeUri.isPlainFolder(plain))
        assertEquals("/storage/emulated/0/ROMs/music", MusicTreeUri.plainFolderPath(plain))
        assertFalse("taking a grant on a file URI throws, so it is skipped", MusicTreeUri.takesPersistableGrant(plain))

        val picker = "content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fmusic"
        assertFalse(MusicTreeUri.isPlainFolder(picker))
        assertNull(MusicTreeUri.plainFolderPath(picker))
        assertTrue(MusicTreeUri.takesPersistableGrant(picker))
    }

    @Test
    fun `a file URI as the browser builds it decodes its path, and one with a raw space still reads`() {
        assertEquals("/storage/6E0E-FBB0/My Music", MusicTreeUri.plainFolderPath("file:///storage/6E0E-FBB0/My%20Music"))
        assertEquals("a path the URI parser refuses is still taken as the text after the scheme", "/storage/6E0E-FBB0/My Music", MusicTreeUri.plainFolderPath("file:///storage/6E0E-FBB0/My Music"))
        assertEquals("/sdcard/Music", MusicTreeUri.plainFolderPath("FILE:///sdcard/Music"))
    }

    // ── the index file ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the index file round-trips, names with tabs, newlines and backslashes included`() {
        val index = MusicIndex(
            tree = "content://tree/primary%3Amusic",
            scannedAtMillis = 1_700_000_000_000L,
            tracks = listOf(
                MusicTrack("", "plain.mp3", "primary:music/plain.mp3"),
                MusicTrack("snes", "tab\there.mp3", "primary:music/snes/tab\there.mp3"),
                MusicTrack("ps2", "new\nline.wav", "id\\with\\backslashes"),
                MusicTrack("", "carriage\rreturn.ogg", "x"),
            ),
        )
        val text = MusicIndexCodec.encode(index)
        assertEquals("one header, then one line per track", 3 + index.tracks.size + 1, text.split('\n').size)
        // Every track of a format-3 file has a meta entry, an unread one when nothing was read.
        assertEquals(index.copy(meta = index.tracks.associate { it.id to MusicTrackMeta() }), MusicIndexCodec.decode(text))
    }

    @Test
    fun `anything that is not a well-formed index decodes to nothing and never throws`() {
        assertNull(MusicIndexCodec.decode(""))
        assertNull(MusicIndexCodec.decode("not an index"))
        assertNull("a format from the future", MusicIndexCodec.decode("MIMIRMUSIC4\n1\ntree\n"))
        assertNull(MusicIndexCodec.decode("MIMIRMUSIC1\nnot-a-number\ntree\n"))
        assertNull("a track line with the wrong number of fields", MusicIndexCodec.decode("MIMIRMUSIC1\n1\ntree\nonly-two\tfields\n"))
        assertNull("a dangling escape", MusicIndexCodec.decode("MIMIRMUSIC1\n1\ntree\n\\\tname\tid\n"))
        assertEquals(MusicIndex("t", 5L, emptyList()), MusicIndexCodec.decode("MIMIRMUSIC1\n5\nt\n"))
    }

    // ── nested folders: Artist/Album/track, .nomedia and the bounds  ───────────────────────────

    /** A tree given as `folder path -> children`, listed by path; a path with no entry is an unreadable folder. */
    private fun listing(vararg folders: Pair<String, List<MusicEntry>>): (String) -> List<MusicEntry>? {
        val map = folders.toMap()
        return { map[it] }
    }

    private fun scanOf(list: (String) -> List<MusicEntry>?) =
        (MusicLibraryScanner.scan("t", "", 1L, list, platformFolders = platforms) as MusicScanResult.Scanned).index

    @Test
    fun `an Artist and Album tree is read to its tracks, and each track keeps its own id`() {
        val index = scanOf(
            listing(
                "" to listOf(dir("Cert Artist", "Cert Artist"), doc("loose.mp3", "loose.mp3")),
                "Cert Artist" to listOf(dir("Cert Artist/Album One", "Album One"), dir("Cert Artist/Album Two", "Album Two")),
                "Cert Artist/Album One" to listOf(doc("a1/02.mp3", "02 - Second.mp3"), doc("a1/01.mp3", "01 - First.mp3"), doc("a1/cover.jpg", "cover.jpg")),
                "Cert Artist/Album Two" to listOf(dir("Cert Artist/Album Two/Disc 2", "Disc 2")),
                "Cert Artist/Album Two/Disc 2" to listOf(doc("a2/d2/01.flac", "01 - Deep.flac")),
            ),
        )
        assertEquals(
            listOf("01 - First.mp3", "02 - Second.mp3", "01 - Deep.flac", "loose.mp3"),
            index.tracks.map { it.name },
        )
        assertEquals("a folder that is not a platform id is a general folder", setOf(""), index.tracks.map { it.folder }.toSet())
        assertEquals("a2/d2/01.flac", index.tracks.single { it.name == "01 - Deep.flac" }.id)
    }

    @Test
    fun `a folder under a platform folder carries the platform id however deep it is`() {
        val index = scanOf(
            listing(
                "" to listOf(dir("snes", "SNES")),
                "snes" to listOf(dir("snes/zelda", "Zelda")),
                "snes/zelda" to listOf(dir("snes/zelda/ost", "OST")),
                "snes/zelda/ost" to listOf(doc("z.mp3", "z.mp3")),
            ),
        )
        assertEquals(listOf("snes" to "z.mp3"), index.tracks.map { it.folder to it.name })
    }

    @Test
    fun `a folder with a nomedia file is left out with everything under it`() {
        val index = scanOf(
            listing(
                "" to listOf(dir("Hidden", "Hidden"), dir("Shown", "Shown")),
                "Hidden" to listOf(doc("h/.nomedia", ".nomedia"), doc("h/a.mp3", "a.mp3"), dir("Hidden/Sub", "Sub")),
                "Hidden/Sub" to listOf(doc("h/s/b.mp3", "b.mp3")),
                "Shown" to listOf(doc("s/c.mp3", "c.mp3"), dir("Shown/Inner", "Inner")),
                "Shown/Inner" to listOf(doc("s/i/.NOMEDIA", ".NOMEDIA"), doc("s/i/d.mp3", "d.mp3")),
            ),
        )
        assertEquals("only the track outside both marked folders", listOf("c.mp3"), index.tracks.map { it.name })
    }

    @Test
    fun `a nomedia file in the picked folder itself leaves the whole library empty, and is not an error`() {
        val result = MusicLibraryScanner.scan(
            "t",
            "",
            1L,
            listing("" to listOf(doc(".nomedia", ".nomedia"), doc("a.mp3", "a.mp3"), dir("Sub", "Sub")), "Sub" to listOf(doc("b.mp3", "b.mp3"))),
        )
        assertEquals(0, (result as MusicScanResult.Scanned).index.trackCount)
    }

    @Test
    fun `a nomedia that is a folder is not the marker`() {
        val index = scanOf(listing("" to listOf(dir(".nomedia", ".nomedia"), doc("a.mp3", "a.mp3")), ".nomedia" to emptyList()))
        assertEquals(listOf("a.mp3"), index.tracks.map { it.name })
    }

    @Test
    fun `the scan stops at the depth cap and still reads everything above it`() {
        // A chain of folders deeper than the cap, with a track at every level.
        val levels = MusicLibraryScanner.MaxScanDepth + 3
        val folders = (0..levels).map { depth ->
            val here = (0 until depth).joinToString("/") { "d$it" }
            val children = buildList {
                add(doc("$here/t$depth.mp3", "t$depth.mp3"))
                if (depth < levels) add(dir((0..depth).joinToString("/") { "d$it" }, "d$depth"))
            }
            here to children
        }
        val index = scanOf(listing(*folders.toTypedArray()))
        assertEquals(
            "the picked folder is level 0, so tracks at levels 0..MaxScanDepth are read and no deeper one",
            (0..MusicLibraryScanner.MaxScanDepth).map { "t$it.mp3" }.toSet(),
            index.tracks.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a tree that lists itself again is read once, not once per level`() {
        // A provider that lists a link back to its own parent: every folder has the same two entries (the same folder id, the same document id).
        val loop = listOf(dir("same", "again"), doc("t.mp3", "t.mp3"))
        var listings = 0
        val index = scanOf { listings += 1; loop }
        assertEquals("the track is indexed once", 1, index.trackCount)
        assertEquals("the root, and the one folder it links to", 2, listings)
    }

    @Test
    fun `a link back to the picked folder is not read again`() {
        val index = scanOf(
            listing(
                "" to listOf(dir("Artist", "Artist"), doc("r.mp3", "r.mp3")),
                "Artist" to listOf(dir("", "Back to the root"), doc("a.mp3", "a.mp3")),
            ),
        )
        assertEquals(listOf("a.mp3", "r.mp3"), index.tracks.map { it.name }.sorted())
    }

    @Test
    fun `a folder reached by two parents is read once`() {
        var readings = 0
        val index = scanOf(
            listing(
                "" to listOf(dir("A", "A"), dir("B", "B")),
                "A" to listOf(dir("shared", "Shared")),
                "B" to listOf(dir("shared", "Shared")),
                "shared" to listOf(doc("s.mp3", "s.mp3")),
            ).let { inner -> { id: String -> if (id == "shared") readings += 1; inner(id) } },
        )
        assertEquals(1, index.trackCount)
        assertEquals(1, readings)
    }

    @Test
    fun `a plain folder reached through a link is one folder by its canonical path`() {
        // The plain lister gives the same directory a different absolute path each way round a symlink loop, and one canonical path.
        val index = (
            MusicLibraryScanner.scan(
                "t",
                "/m/a",
                1L,
                list = { id -> listOf(MusicEntry("$id/link", "link", true, canonicalId = "/m/a"), doc("$id/t.mp3", "t.mp3")) },
                rootKey = "/m/a",
            ) as MusicScanResult.Scanned
            ).index
        assertEquals(1, index.trackCount)
    }

    @Test
    fun `a provider that mints a new id at every level still ends at the depth cap`() {
        val index = scanOf { id -> listOf(dir("$id/x", "x"), doc("$id/t.mp3", "t.mp3")) }
        assertEquals("one track per level, the picked folder's included", MusicLibraryScanner.MaxScanDepth + 1, index.trackCount)
    }

    @Test
    fun `a document listed twice is one track`() {
        val index = scanOf(listing("" to listOf(doc("same-id", "a.mp3"), doc("same-id", "a.mp3"), doc("other", "b.mp3"))))
        assertEquals(listOf("a.mp3", "b.mp3"), index.tracks.map { it.name })
    }

    @Test
    fun `a cancelled scan stops at its next entry and says so`() {
        var asks = 0
        val many = (1..50).map { doc("x$it", "t$it.mp3") }
        try {
            MusicLibraryScanner.scan("t", "", 1L, list = { many }, isCancelled = { asks += 1; asks > 5 })
            fail("a cancelled scan must not return an index")
        } catch (_: CancellationException) {
            // Stopped where the job was cancelled.
        }
        assertTrue("it looked at no more than a few entries", asks <= 6)
    }

    @Test
    fun `the scan stops after the entry budget and keeps what it has`() {
        val many = (1..MusicLibraryScanner.MaxVisitedEntries + 50).map { doc("x$it", "note$it.txt") } + doc("late.mp3", "zzz.mp3")
        val index = scanOf { many }
        assertEquals("the music file sorts after the budget is spent", 0, index.trackCount)
    }

    @Test
    fun `a scan carries each file's size and time for the tag cache`() {
        val index = scanOf(
            listing("" to listOf(MusicEntry("a", "a.mp3", false, sizeBytes = 123L, modifiedMillis = 456L), MusicEntry("b", "b.mp3", false))),
        )
        assertEquals(MusicFileStamp(123L, 456L), index.meta.getValue("a").stamp)
        assertEquals("a provider that says nothing leaves the stamp unknown", MusicFileStamp.Unknown, index.meta.getValue("b").stamp)
        assertTrue(index.meta.values.all { it.state == MusicTagState.UNREAD })
    }

    // ── the rescan floor (rescan could be smashed) ─────────────────────────────────────

    @Test
    fun `a rescan holds Scanning for the rest of the floor and never longer`() {
        assertEquals(900L, musicRescanHoldMs(MusicRescanFloorMs, elapsedMs = 0L))
        assertEquals(600L, musicRescanHoldMs(MusicRescanFloorMs, elapsedMs = 300L))
        assertEquals(0L, musicRescanHoldMs(MusicRescanFloorMs, elapsedMs = 900L))
        assertEquals(0L, musicRescanHoldMs(MusicRescanFloorMs, elapsedMs = 5_000L))
        assertEquals(900L, musicRescanHoldMs(MusicRescanFloorMs, elapsedMs = -5L))
        assertEquals(0L, musicRescanHoldMs(0L, elapsedMs = 0L))
    }

    @Test
    fun `a rescan runs on the floor and publishes only after the hold`() {
        val library = File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/MimirMusicLibrary.kt").readText()
        val rescan = library.substringAfter("fun rescan(context: Context) {").substringBefore("fun forget(")
        assertTrue(rescan, rescan.contains("if (scanJob?.isActive == true) return"))
        assertTrue(rescan, rescan.contains("scanAndPublish(appContext, tree, floorMs = MusicRescanFloorMs)"))
        val scan = library.substringAfter("private suspend fun scanAndPublish(").substringBefore("private fun scanTree(")
        assertTrue(scan, scan.indexOf("delay(musicRescanHoldMs(") in 0 until scan.indexOf("synchronized(lock)"))
    }

    // ── labels on disk are not trusted ───────────────────────────────────────────────────────────────

    private val read = MusicTrackMeta(
        stamp = MusicFileStamp(1_234L, 5_678L),
        state = MusicTagState.READ,
        tags = MusicTags(title = "One", artist = "U2", album = "Boy", albumArtist = "U2", trackNumber = 3, discNumber = 1, durationMs = 241_000L, compilation = false),
        hasEmbeddedArt = true,
    )

    /** The index an earlier build wrote for [root]: every one-word folder carries its lowercased name as a "platform", `snes` and `PS2` carry theirs, and every track has been read. */
    private fun oldPlainIndex(root: File): MusicIndex {
        fun track(label: String, path: String) = MusicTrack(label, path.substringAfterLast('/'), File(root, path).absolutePath)
        val tracks = listOf(
            track("", "loose.mp3"),
            track("queen", "Queen/A Night at the Opera/01 Death on Two Legs.mp3"),
            track("abba", "ABBA/Dancing Queen.mp3"),
            track("u2", "U2/One.mp3"),
            track("jayz", "Jay-Z/99 Problems.mp3"),
            track("snes", "snes/zelda.flac"),
            track("ps2", "PS2/Ico/ico.m4a"),
        )
        return MusicIndex("file://" + root.toURI().path.trimEnd('/'), 1_700_000_000_000L, tracks, tracks.associate { it.id to read })
    }

    @Test
    fun `an index written with the old labels loads with an artist's folder as general music and a platform's still the platform's`() {
        val root = Files.createTempDirectory("mimir-music-old-index").toFile()
        try {
            val old = oldPlainIndex(root)
            // Through the file, as a restart would meet it: the labels are whatever the file says.
            val loaded = MusicIndexCodec.decode(MusicIndexCodec.encode(old))!!.withPlatformLabels(platforms)

            val folders = loaded.tracks.associate { it.name to it.folder }
            assertEquals("", folders["01 Death on Two Legs.mp3"])
            assertEquals("", folders["Dancing Queen.mp3"])
            assertEquals("", folders["One.mp3"])
            assertEquals("", folders["99 Problems.mp3"])
            assertEquals("", folders["loose.mp3"])
            assertEquals("snes", folders["zelda.flac"])
            assertEquals("a platform's folder is spelled any way and carries the registry's id", "ps2", folders["ico.m4a"])
            // The tag cache, the ids, the names and the order are what the file held: nothing is read again.
            assertEquals(old.tracks.map { it.id to it.name }, loaded.tracks.map { it.id to it.name })
            assertEquals(old.meta, loaded.meta)
            assertTrue("every track keeps a read tag cache entry", loaded.meta.values.all { it == read })
            assertEquals(old.tree, loaded.tree)
            assertEquals(old.scannedAtMillis, loaded.scannedAtMillis)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a label is recomputed from the platform ids of the day, so a platform the registry has gained is found and one it dropped is not`() {
        val root = Files.createTempDirectory("mimir-music-new-ids").toFile()
        try {
            val old = oldPlainIndex(root)
            val noPsp = MusicPlatformFolders { name -> if (name.equals("snes", ignoreCase = true)) "snes" else null }
            val loaded = old.withPlatformLabels(noPsp)
            assertEquals("snes", loaded.tracks.first { it.name == "zelda.flac" }.folder)
            assertEquals("a platform this registry does not know is plain music", "", loaded.tracks.first { it.name == "ico.m4a" }.folder)
            // With no registry at all nothing is reserved.
            assertTrue(old.withPlatformLabels(MusicPlatformFolders.None).tracks.all { it.folder.isEmpty() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a picker tree's old labels are recomputed from the document ids, a volume root included`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val old = MusicIndex(
            tree, 1L,
            listOf(
                MusicTrack("", "loose.ogg", "primary:Music/loose.ogg"),
                MusicTrack("queen", "Death.mp3", "primary:Music/Queen/Opera/Death.mp3"),
                MusicTrack("u2", "One.mp3", "primary:Music/U2/One.mp3"),
                MusicTrack("snes", "zelda.flac", "primary:Music/snes/zelda.flac"),
                MusicTrack("pcgames", "doom.ogg", "primary:Music/PCGames/doom.ogg"),
            ),
            mapOf("primary:Music/Queen/Opera/Death.mp3" to read),
        )
        val loaded = old.withPlatformLabels(platforms)
        assertEquals(listOf("", "", "", "snes", "pc_games"), loaded.tracks.map { it.folder })
        assertEquals(old.meta, loaded.meta)

        val volume = MusicIndex(
            "content://com.android.externalstorage.documents/tree/primary%3A", 1L,
            listOf(MusicTrack("queen", "Death.mp3", "primary:Queen/Death.mp3"), MusicTrack("psp", "gta.ogg", "primary:PSP/gta.ogg"), MusicTrack("", "loose.mp3", "primary:loose.mp3")),
        )
        assertEquals(listOf("", "psp", ""), volume.withPlatformLabels(platforms).tracks.map { it.folder })
    }

    @Test
    fun `a track whose place cannot be told keeps its label, and an index that needs no change is returned as it is`() {
        val opaque = MusicIndex(
            "content://com.android.providers.downloads.documents/tree/downloads", 1L,
            listOf(MusicTrack("queen", "Death.mp3", "msf:1234"), MusicTrack("snes", "zelda.flac", "msf:5678")),
        )
        assertEquals("nothing to compute them from: the labels stay until a rescan", listOf("queen", "snes"), opaque.withPlatformLabels(platforms).tracks.map { it.folder })

        val fine = MusicIndex("content://tree/primary%3Amusic", 1L, listOf(MusicTrack("", "a.mp3", "primary:music/a.mp3"), MusicTrack("snes", "z.flac", "primary:music/snes/z.flac")))
        assertTrue(fine.withPlatformLabels(platforms) === fine)
        assertEquals("an id from another folder is not placed", listOf("x"), MusicIndex("content://tree/primary%3Amusic", 1L, listOf(MusicTrack("x", "a.mp3", "secondary:other/a.mp3"))).withPlatformLabels(platforms).tracks.map { it.folder })
    }

    @Test
    fun `the old labels are recomputed in the one place an index is loaded`() {
        val library = File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/MimirMusicLibrary.kt").readText()
        val load = library.substringAfter("fun load(context: Context").substringBefore("fun setTree(")
        assertTrue(load, load.indexOf("MusicIndexCodec::decode") in 0 until load.indexOf("withPlatformLabels(platformFolders)"))
        assertTrue(load, load.indexOf("withPlatformLabels(platformFolders)") in 0 until load.indexOf("startTagPass(appContext, index)"))
    }
}
