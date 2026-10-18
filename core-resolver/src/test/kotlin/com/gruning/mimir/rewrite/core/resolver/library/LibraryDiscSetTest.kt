package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 *
 *
 * A multi-disc game is several files plus an `.m3u` that names them, and the playlist resolves those
 * names against its own folder ([com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver]). Move one
 * disc out and the playlist points at nothing: the game stops working on disk, for Mimir and for every
 * emulator that reads the playlist, and no bookkeeping in Room repairs a text file.
 *
 * `LibraryRepository.discSetFor` is what lets the file manager say so **before** the move. Everything
 * here is about the two ways that warning could be worse than useless:
 *
 * 1. **Warning when nothing breaks** — about a duplicate pair, or a title-grouped set with no playlist —
 *    trains the player to dismiss it, and then the one that matters is dismissed too.
 * 2. **Offering a repair that does not repair** — "move them together" when the playlist names fixed
 *    paths — is worse than offering nothing, because the player takes the offer and stops looking.
 *
 * The playlists here are **real files on disk**, written per test, because the fact under test is a fact
 * about their contents. A fixture that returned "travels intact" without a file would prove the plumbing
 * and nothing about the rule.
 */
class LibraryDiscSetTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: String

    private class Recorded {
        var subject: GameEntity? = null
        var members: List<GameEntity> = emptyList()
    }

    private fun row(
        path: String,
        contentKind: ContentKind = ContentKind.DISC_MEMBER,
        discNumber: Int = 0,
        groupKind: DuplicateGroupKind? = DuplicateGroupKind.MULTI_DISC,
    ) = GameEntity(
        entryKey = physicalResidentKey(root, File(path).relativeTo(File(root)).invariantSeparatorsPath),
        gameId = "title:ps1:ff9",
        platformId = "ps1",
        contentKind = contentKind.name,
        displayName = File(path).name,
        title = "Final Fantasy IX",
        region = "EUR",
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 700_000_000L,
        contentHash = null,
        sourceRootUri = root,
        sourceDocumentUri = null,
        managedPath = path,
        relativePath = File(path).relativeTo(File(root)).invariantSeparatorsPath,
        discNumber = discNumber,
        duplicateGroupId = "grp",
        isPrimaryMember = discNumber <= 1,
        duplicateGroupKind = groupKind?.name,
    )

    private fun repository(recorded: Recorded): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { method, args ->
            when (method) {
                "gameByManagedPath" -> recorded.subject.takeIf { it?.managedPath == args[0] }
                "membersOfGame" -> recorded.members
                else -> error("Unexpected GameDao call: $method")
            }
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = unusedProxy(),
            hiddenItemDao = unusedProxy(),
            favoriteDao = unusedProxy(),
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult = SourceWalkResult.Complete(emptyList())
            },
            runScanTransaction = { block -> block() },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    /**
     * A real three-disc set on disk. Returns the disc paths; the playlist is written by the caller so
     * each test can decide what it says, which is the variable every test here is really about.
     */
    private fun threeDiscsOnDisk(): List<String> {
        root = temp.newFolder("Roms").path
        val folder = File(root, "ps1/Final Fantasy IX").apply { mkdirs() }
        return (1..3).map { n -> File(folder, "FF9 (Disc $n).chd").apply { writeText("x") }.path }
    }

    private fun playlist(body: String): String =
        File(File(root, "ps1/Final Fantasy IX"), "FF9.m3u").apply { writeText(body) }.path

    private fun recordedSet(discs: List<String>, playlistPath: String?, subject: String) = Recorded().apply {
        val discRows = discs.mapIndexed { index, path -> row(path, discNumber = index + 1) }
        val playlistRow = playlistPath?.let { row(it, contentKind = ContentKind.MULTI_DISC_PLAYLIST) }
        members = listOfNotNull(playlistRow) + discRows
        this.subject = members.first { it.managedPath == subject }
    }

    @Test
    fun `a disc of a real set, with a playlist of bare filenames, can travel as a set`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { File(it).name })

        val set = repository(recordedSet(discs, m3u, discs[1])).discSetFor(discs[1])!!

        assertEquals("Final Fantasy IX", set.title)
        assertEquals("the library's own disc order, not the filesystem's", 2, set.subjectDiscNumber)
        assertEquals(discs, set.discPaths)
        assertEquals(m3u, set.playlistPath)
        assertTrue(
            "every entry is a bare sibling filename, and M3uPlaylistResolver resolves entries against " +
                "the playlist's own parent -- so landing all four files in one folder keeps it resolving",
            set.wholeSetTravelsIntact,
        )
        assertTrue(set.carryable)
        assertEquals(
            "the playlist goes FIRST: it is the file that has to arrive with them, not one of the discs",
            listOf(m3u) + discs,
            set.allPaths,
        )
    }

    @Test
    fun `a playlist naming absolute paths cannot be repaired by moving the set`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n"))

        val set = repository(recordedSet(discs, m3u, discs[0])).discSetFor(discs[0])!!

        assertFalse(
            "an absolute entry names a place the move does not preserve, so 'move them together' would " +
                "advertise a repair that does not repair -- the player takes the offer and stops looking",
            set.wholeSetTravelsIntact,
        )
        assertFalse(set.carryable)
    }

    @Test
    fun `a playlist reaching into a subfolder cannot be repaired either`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { "discs/${File(it).name}" })

        val set = repository(recordedSet(discs, m3u, discs[0])).discSetFor(discs[0])!!

        assertFalse(
            "the discs land FLAT in the destination, so a relative entry with a directory in it resolves " +
                "to a folder that will not exist there",
            set.carryable,
        )
    }

    @Test
    fun `a playlist naming a file that is not in the set cannot be trusted`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist("FF9 (Disc 1).chd\nFF9 (Disc 4).chd")

        val set = repository(recordedSet(discs, m3u, discs[0])).discSetFor(discs[0])!!

        assertFalse(
            "moving the three the library knows about would leave the playlist naming a fourth file this " +
                "move never touched -- fail closed rather than move a set and hope",
            set.carryable,
        )
    }

    @Test
    fun `an unreadable playlist fails closed but still warns`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val ghost = File(root, "ps1/Final Fantasy IX/gone.m3u").path

        val set = repository(recordedSet(discs, ghost, discs[0])).discSetFor(discs[0])!!

        assertFalse("nothing was read, so nothing can be promised", set.wholeSetTravelsIntact)
        assertTrue(
            "**the warning still fires.** A playlist row the library holds is a playlist that will be " +
                "left behind whether or not this process could read it; going silent because the file " +
                "could not be opened would make the disc warning unreachable",
            set.discPaths.size >= 2,
        )
    }

    @Test
    fun `a DUPLICATE group is not a disc set, and saying it was would be a fabricated fact`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { File(it).name })
        val recorded = recordedSet(discs, m3u, discs[0]).apply {
            subject = subject!!.copy(duplicateGroupKind = DuplicateGroupKind.CROSS_STORAGE.name)
        }

        assertNull(
            "GameDao.memberCount > 1 is a disc set OR a duplicate group -- the distinction " +
                "LibraryRelocation.partOfSet deliberately conflates because after the fact it does not " +
                "matter. Before the fact it is the whole warning: \"this is disc 2 of 3\" said about two " +
                "copies of one ROM is a lie the player cannot check.",
            repository(recorded).discSetFor(discs[0]),
        )
    }

    /**
     * **This test used to assert the opposite, and it was the reason the defect looked deliberate.**
     *
     * It was called *"a multi-disc group with no playlist on disk breaks nothing, so it says nothing"*,
     * and its stated reason was: *"the group re-forms on title at the next scan."* That is true and it
     * is beside the point. **Mimir's grouping re-forming is not the game working.** The group re-forms in
     * Room while disc 2 sits in another folder, and Dolphin still asks for a disc that is not there. The
     * old reasoning measured the wrong thing — the library's bookkeeping instead of the player's game —
     * and it was written confidently enough that review passed over it twice.
     *
     * Moves require the relevant warning before dispatch.
     */
    @Test
    fun `a multi-disc group with no playlist still breaks, so it still warns`() = runBlocking {
        val discs = threeDiscsOnDisk()

        val set = repository(recordedSet(discs, playlistPath = null, subject = discs[0])).discSetFor(discs[0])

        assertEquals(
            "DedupGrouper forms MULTI_DISC groups on the disc token in the filename alone, with no .m3u " +
                "anywhere. Refusing those made the warning unreachable " +
                "for the shape he has more of.",
            3,
            set?.discPaths?.size,
        )
        assertTrue("and moving them together is a real repair, with no playlist to invalidate", set?.carryable == true)
    }

    @Test
    fun `the playlist itself is not one of its own discs`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { File(it).name })

        assertNull(
            "moving the .m3u alone is a different question with a different answer, and answering it " +
                "with 'this is disc 0 of 3' would be nonsense on the screen",
            repository(recordedSet(discs, m3u, m3u)).discSetFor(m3u),
        )
    }

    @Test
    fun `a file the library never indexed is not a disc set`() = runBlocking {
        threeDiscsOnDisk()
        assertNull(repository(Recorded()).discSetFor("$root/ps1/screenshot.png"))
    }

    @Test
    fun `a set of one is not a set`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist("FF9 (Disc 1).chd")
        val recorded = recordedSet(discs, m3u, discs[0]).apply {
            members = members.filter { it.contentKind == ContentKind.MULTI_DISC_PLAYLIST.name || it.managedPath == discs[0] }
        }

        assertNull(
            "one disc and a playlist naming it is a single-disc game filed oddly, not a set that can be " +
                "broken by moving its only member",
            repository(recorded).discSetFor(discs[0]),
        )
    }

    @Test
    fun `nothing here writes anything`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { File(it).name })
        val before = File(root).walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }

        repository(recordedSet(discs, m3u, discs[1])).discSetFor(discs[1])

        val after = File(root).walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
        assertEquals(
            "this runs BEFORE the player has agreed to anything -- a pre-flight that touched a file " +
                "would move a game on the strength of a question that has not been answered yet",
            before,
            after,
        )
        // And the DAO proxy above `error`s on every method other than the two reads, so a write through
        // Room would have failed the test before reaching here.
    }

    // ── shapes that are MULTI_DISC and are not multi-disc (gatekeeper head pass, item 1) ─────────────

    /**
     * **`duplicateGroupKind == MULTI_DISC` is necessary and nowhere near sufficient.**
     *
     * `DedupGrouper` stamps it on a TRACK SET too (`GroupingKey.TrackSetBase.kind`), so an ordinary
     * single-disc `.cue` + `.bin` pair reaches `discSetFor` as a two-member MULTI_DISC group. It warned
     * on every move of one. This is the exact failure the feature was built to avoid — a warning that
     * fires when nothing breaks teaches the player to dismiss it, and then the one that matters is
     * dismissed too — and it would have fired on far more of the example library than the real sets do.
     */
    @Test
    fun `a cue and its bin are one disc, and warn about nothing`() = runBlocking {
        root = temp.newFolder("Roms").path
        val dir = File(root, "ps1").apply { mkdirs() }
        val cue = File(dir, "Vagrant Story (USA).cue").apply { writeText("x") }.path
        val bin = File(dir, "Vagrant Story (USA).bin").apply { writeText("x") }.path
        val recorded = Recorded().apply {
            members = listOf(
                row(cue, contentKind = ContentKind.TRACK_MANIFEST, discNumber = 0),
                row(bin, discNumber = 0),
            )
            subject = members.first { it.managedPath == bin }
        }

        assertNull(
            "neither file carries a disc token, so ScanRecognizer.discNumberOf answers 0 for both -- " +
                "there is no evidence of a second disc anywhere, only of a second FILE",
            repository(recorded).discSetFor(bin),
        )
    }

    @Test
    fun `two revisions of one disc are one disc, and warn about nothing`() = runBlocking {
        root = temp.newFolder("Roms").path
        val dir = File(root, "ps1").apply { mkdirs() }
        val plain = File(dir, "FF9 (Disc 1).chd").apply { writeText("x") }.path
        val revised = File(dir, "FF9 (Disc 1) (Rev 1).chd").apply { writeText("x") }.path
        val recorded = Recorded().apply {
            members = listOf(row(plain, discNumber = 1), row(revised, discNumber = 1))
            subject = members.first { it.managedPath == revised }
        }

        assertNull(
            "the keyer buckets both to the same base title, so they arrive as one MULTI_DISC group -- " +
                "and \"Disc 1 of 2\" offering to co-move a game that has ONE disc is a sentence the " +
                "player can check against the folder and find false",
            repository(recorded).discSetFor(revised),
        )
    }

    @Test
    fun `basenames that differ only in case are still a collision`() = runBlocking {
        root = temp.newFolder("Roms").path
        val here = File(root, "ps1/a").apply { mkdirs() }
        val there = File(root, "ps1/b").apply { mkdirs() }
        val one = File(here, "FF9 (Disc 1).chd").apply { writeText("x") }.path
        val two = File(there, "ff9 (disc 1).chd").apply { writeText("x") }.path
        val recorded = Recorded().apply {
            members = listOf(row(one, discNumber = 1), row(two, discNumber = 2))
            subject = members.first { it.managedPath == two }
        }

        assertNull(
            "**the retraction that removed the folder guard claimed the basename check was already " +
                "doing this job, and that was a false universal.** `toSet()` on raw names is " +
                "case-SENSITIVE, so these two passed it -- and then a move-all self-collides mid-loop on " +
                "a case-insensitive destination, which his SD card, being exFAT, is.",
            repository(recorded).discSetFor(two),
        )
    }

    @Test
    fun `backslash is a native separator only on hosts that use it`() = runBlocking {
        root = temp.newFolder("native-names").path
        // On POSIX these are two distinct basenames containing literal backslashes. On Windows
        // they name the same basename in two subfolders, so the collision guard must refuse them.
        val one = File(root, "a\\disc.chd").path
        val two = File(root, "b\\disc.chd").path
        val recorded = recordedSet(listOf(one, two), playlistPath = null, subject = one)

        val set = repository(recorded).discSetFor(one)

        if (File.separatorChar == '\\') {
            assertNull(set)
        } else {
            assertEquals(listOf(one, two), set!!.discPaths)
            assertTrue(set.wholeSetTravelsIntact)
        }
    }

    @Test
    fun `a SAF-held playlist is reported as a playlist, not as none`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { File(it).name })
        val recorded = Recorded().apply {
            val playlistRow = row(m3u, contentKind = ContentKind.MULTI_DISC_PLAYLIST).copy(managedPath = null)
            members = listOf(playlistRow) + discs.mapIndexed { i, p -> row(p, discNumber = i + 1) }
            subject = members.first { it.managedPath == discs[1] }
        }

        val set = repository(recorded).discSetFor(discs[1])

        assertTrue(
            "the caller derived \"has a playlist\" from `playlistPath != null`, and that field is null " +
                "for TWO unrelated reasons -- no playlist at all, and a playlist we cannot move by path. " +
                "So a player with a SAF-held playlist was told their set has none, which is the wrong " +
                "failure described on the one screen whose whole job is to describe the failure.",
            set?.hasPlaylist == true,
        )
        assertNull("its path is still unavailable, and that is a different fact", set?.playlistPath)
        assertFalse("so the co-move is still not offered", set?.carryable == true)
    }

    // ── a set with no playlist at all ─────────────────────────────────

    @Test
    fun `his GameCube set has no playlist, and it warns anyway`() = runBlocking {
        // **Observed on Odin:**
        // /storage/<volume-id>/ROMs/gamecube/ holds `Resident Evil (USA) (Disc 1).iso` and `(Disc 2).iso`
        // with NO .m3u anywhere in the folder -- `ls | grep -i m3u` returns nothing. Before this,
        // discSetFor required a playlist row and returned null, so no warning was given and the file
        // moved. Reported behavior: Moves require the relevant warning before dispatch.
        root = temp.newFolder("Roms").path
        val dir = File(root, "gamecube").apply { mkdirs() }
        val discs = (1..2).map { n ->
            File(dir, "Resident Evil (USA) (Disc $n).iso").apply { writeText("x") }.path
        }
        val recorded = Recorded().apply {
            members = discs.mapIndexed { index, path -> row(path, discNumber = index + 1) }
            subject = members.first { it.managedPath == discs[1] }
        }

        val set = repository(recorded).discSetFor(discs[1])

        assertEquals("both discs", 2, set?.discPaths?.size)
        assertNull("and no playlist, because there isn't one -- not because we failed to read it", set?.playlistPath)
        assertTrue(
            "**the co-move is the EASY case here.** With no playlist there is no text file to " +
                "invalidate, so moving the discs together always works -- this set is more carryable " +
                "than the .m3u one, and it was the one being refused.",
            set?.wholeSetTravelsIntact == true,
        )
        assertTrue(set?.carryable == true)
    }

    @Test
    fun `a playlist the library can only reach through SAF warns, but cannot carry`() = runBlocking {
        // The distinction `carryable` used to lose. A playlist row EXISTS -- it will be left behind and
        // break the set -- but the library holds it only as a SAF document, so this process cannot move
        // it by path. Reading `playlistPath != null` answered the same for this as for "no playlist at
        // all", which is why the flag had to stop being about the path. The gatekeeper called the
        // model's null-playlistPath shape unconstructible dead vocabulary (finding 1); this constructs it.
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { File(it).name })
        val recorded = Recorded().apply {
            val playlistRow = row(m3u, contentKind = ContentKind.MULTI_DISC_PLAYLIST).copy(managedPath = null)
            members = listOf(playlistRow) + discs.mapIndexed { i, p -> row(p, discNumber = i + 1) }
            subject = members.first { it.managedPath == discs[1] }
        }

        val set = repository(recorded).discSetFor(discs[1])

        assertEquals("it is still a set, and he is still warned", 3, set?.discPaths?.size)
        assertFalse(
            "but offering to move them together would strand the playlist and break the game anyway -- " +
                "a repair that does not repair is worse than no repair",
            set?.wholeSetTravelsIntact == true,
        )
        assertFalse(set?.carryable == true)
    }

    // ── the duplicate-copy shape (gatekeeper finding 2) ──────────────────────────────────────────────

    /**
     * The same game in two folders. `gameId` is CONTENT-derived, so both copies carry the same id and
     * `membersOfGame` hands back **one** member list holding two playlists and two of every disc.
     */
    private fun twoCopiesOnDisk(): Pair<List<String>, List<String>> {
        root = temp.newFolder("Roms").path
        fun copy(folder: String): List<String> {
            val dir = File(root, "ps1/$folder").apply { mkdirs() }
            val discs = (1..3).map { n -> File(dir, "FF9 (Disc $n).chd").apply { writeText("x") }.path }
            File(dir, "FF9.m3u").writeText(discs.joinToString("\n") { File(it).name })
            return listOf(File(dir, "FF9.m3u").path) + discs
        }
        return copy("Final Fantasy IX") to copy("backup-ff9")
    }

    private fun recordedRows(paths: List<String>, subject: String) = Recorded().apply {
        members = paths.map { path ->
            if (path.endsWith(".m3u")) {
                row(path, contentKind = ContentKind.MULTI_DISC_PLAYLIST)
            } else {
                row(path, discNumber = path.substringAfter("(Disc ").substringBefore(')').toInt())
            }
        }
        this.subject = members.first { it.managedPath == subject }
    }

    @Test
    fun `two copies of one set in two folders warn about nothing`() = runBlocking {
        val (first, second) = twoCopiesOnDisk()
        val all = first + second
        val subject = first[1]

        assertNull(
            "**The shape that shipped a fabricated repair.** One gameId, two folders, so the member " +
                "list carries two playlists and six discs. The old code took the FIRST playlist, " +
                "excluded exactly one row by entryKey -- so the OTHER playlist counted as a disc -- and " +
                "collapsed the repeated basenames into a Set, which is where the duplication was lost. " +
                "The screen then said \"disc 2 of 7\" and offered to move all seven into one folder: " +
                "the first copy commits, the second copy's identical filename hits an ASK conflict " +
                "INSIDE the engine's own loop, and the card parks holding source paths that no longer " +
                "exist. No answer replays. The set ends up split across three places by the button that " +
                "offered to keep it together.",
            repository(recordedRows(all, subject)).discSetFor(subject),
        )
    }

    @Test
    fun `a second playlist in the member list is never counted as a disc`() = runBlocking {
        val (first, second) = twoCopiesOnDisk()
        val members = first + second.first()

        assertNull(
            "the member filter was `entryKey != playlist.entryKey`, which excludes ONE row -- so a " +
                "second `.m3u` was reported to the player as a disc. Filtering by contentKind is what " +
                "makes the count a fact rather than an arithmetic accident.",
            repository(recordedRows(members, first[1])).discSetFor(first[1]),
        )
    }

    @Test
    fun `a repeated basename is refused, and THAT is the duplicate-copy defence`() = runBlocking {
        val (first, second) = twoCopiesOnDisk()
        val members = first + second[1]

        assertNull(
            "copy A's three discs plus copy B's disc 2 -- two files called `FF9 (Disc 2).chd`. This is " +
                "the collision the co-move would hit mid-loop, and the basename check is what stops it. " +
                "The test that used to stand here claimed the reason was that members must share a " +
                "folder; that claim is retracted (see the next two tests) and it was never the reason " +
                "this input was refused.",
            repository(recordedRows(members, first[1])).discSetFor(first[1]),
        )
    }

    /**
     * A set really can span folders, and `ac1135e8` silenced every one of them.
     *
     * The guard's stated ground was that `M3uPlaylistResolver` resolves entries against the playlist's
     * own parent, so a nameable set is single-folder by construction. **That is false.**
     * `M3uPlaylistResolver.kt:64-70` deliberately resolves `content://`, `file://`, absolute and
     * subfolder entries too. So the guard traded the silent failure observed on device for a different
     * one, on the set most worth warning about. Gatekeeper re-review, finding 6.
     */
    private fun splitAcrossFolders(): Triple<String, String, String> {
        root = temp.newFolder("Roms").path
        val here = File(root, "ps1/Final Fantasy IX").apply { mkdirs() }
        val there = File(root, "ps1/Final Fantasy IX overflow").apply { mkdirs() }
        val one = File(here, "FF9 (Disc 1).chd").apply { writeText("x") }.path
        val two = File(there, "FF9 (Disc 2).chd").apply { writeText("x") }.path
        return Triple(one, two, here.path)
    }

    @Test
    fun `a set split across folders still warns, and bare names can still repair it`() = runBlocking {
        val (one, two, here) = splitAcrossFolders()
        val m3u = File(File(here), "FF9.m3u")
            .apply { writeText("FF9 (Disc 1).chd\nFF9 (Disc 2).chd\n") }.path

        val set = repository(recordedRows(listOf(m3u, one, two), two)).discSetFor(two)

        assertEquals("it is a set, and moving one disc out of it is still the thing being warned about", 2, set?.discPaths?.size)
        assertTrue(
            "and here the co-move is not merely honest, it is a REPAIR: the game is already broken by " +
                "the split, and landing both discs plus the playlist in one folder is exactly what makes " +
                "the bare names resolve",
            set?.carryable == true,
        )
    }

    @Test
    fun `a split set whose playlist names fixed locations warns, and offers no repair`() = runBlocking {
        val (one, two, here) = splitAcrossFolders()
        val m3u = File(File(here), "FF9.m3u").apply { writeText("$one\n$two\n") }.path

        val set = repository(recordedRows(listOf(m3u, one, two), two)).discSetFor(two)

        assertEquals("still warned -- silence here is the defect being fixed", 2, set?.discPaths?.size)
        assertFalse(
            "but absolute entries name places the move does not preserve, so 'move them together' would " +
                "advertise a repair that does not repair. Warn, offer nothing better than move-anyway.",
            set?.carryable == true,
        )
    }

    @Test
    fun `his own card still warns -- two discs beside their playlist, bare filenames, LF`() = runBlocking {
        // The exact shape measured on the Odin (reference device) 2026-08-18: /ROMs/ps1/ holds three 2-disc
        // sets, every playlist LF-terminated bare siblings, every disc in the playlist's own folder.
        // The guards above are only correct if they leave THIS alone.
        root = temp.newFolder("Roms").path
        val dir = File(root, "ps1").apply { mkdirs() }
        val discs = (1..2).map { n ->
            File(dir, "Chrono Cross (USA) (Disc $n).chd").apply { writeText("x") }.path
        }
        val m3u = File(dir, "Chrono Cross (USA).m3u")
            .apply { writeText(discs.joinToString("\n") { File(it).name } + "\n") }
            .path

        val set = repository(recordedSet(discs, m3u, discs[1])).discSetFor(discs[1])

        assertEquals("both discs, and neither is the playlist", 2, set?.discPaths?.size)
        assertTrue(
            "and the co-move is still offered -- the guards refuse duplicate copies, not real sets",
            set?.carryable == true,
        )
    }

    // ── PAL libcrypt companions: the scan skips .sbi/.sub, so no row holds them (Cocoon D#248) ──────────

    private fun beside(disc: String, name: String): String =
        File(File(disc).parentFile, name).apply { writeText("x") }.path

    @Test
    fun `an sbi or sub beside a disc travels with the set but is never counted as a disc`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { File(it).name })
        val sbiOne = beside(discs[0], "FF9 (Disc 1).sbi")
        val subTwo = beside(discs[1], "FF9 (Disc 2).sub")
        val sbiThree = beside(discs[2], "FF9 (Disc 3).sbi")

        val set = repository(recordedSet(discs, m3u, discs[1])).discSetFor(discs[1])!!

        assertEquals("the warning still counts three discs, not five files", discs, set.discPaths)
        assertEquals(listOf(sbiOne, subTwo, sbiThree), set.companionPaths)
        assertEquals(
            "move-all takes the playlist, then the discs, then each disc's subchannel files",
            listOf(m3u) + discs + listOf(sbiOne, subTwo, sbiThree),
            set.allPaths,
        )
    }

    @Test
    fun `only a same-name file in the disc's own folder is a companion, never an unrelated file`() = runBlocking {
        val discs = threeDiscsOnDisk()
        val m3u = playlist(discs.joinToString("\n") { File(it).name })
        // Another game's subchannel file, a different disc of the title that is NOT in the set, a same-name file of an
        // unrelated type, a title with no disc suffix, and a DIRECTORY wearing a companion's name.
        beside(discs[0], "FF9 (Disc 4).sbi")
        beside(discs[0], "FF9.sbi")
        beside(discs[0], "Other Game.sub")
        beside(discs[0], "FF9 (Disc 1).txt")
        beside(discs[0], "FF9 (Disc 1).sbi.bak")
        File(File(discs[1]).parentFile, "FF9 (Disc 2).sbi").mkdirs()
        // And a real companion in ANOTHER folder: same name, wrong place.
        File(root, "ps1/elsewhere").apply { mkdirs() }.let { File(it, "FF9 (Disc 3).sbi").writeText("x") }

        val set = repository(recordedSet(discs, m3u, discs[0])).discSetFor(discs[0])!!

        assertEquals("nothing unrelated is pulled in", emptyList<String>(), set.companionPaths)
        assertEquals(listOf(m3u) + discs, set.allPaths)
    }

    @Test
    fun `a cue and its bin share one companion and a disc with none adds nothing`() = runBlocking {
        root = temp.newFolder("Roms").path
        val folder = File(root, "ps1/Legend").apply { mkdirs() }
        val discs = listOf("Legend (Disc 1).cue", "Legend (Disc 1).bin", "Legend (Disc 2).cue").map {
            File(folder, it).apply { writeText("x") }.path
        }
        val sbiOne = beside(discs[0], "Legend (Disc 1).sbi")

        val set = repository(recordedSet(discs, playlistPath = null, subject = discs[0])).discSetFor(discs[0])!!

        assertEquals("the cue and the bin both name the same .sbi; it is listed once", listOf(sbiOne), set.companionPaths)
        assertEquals(discs + sbiOne, set.allPaths)
    }

    private inline fun <reified T> unusedProxy(): T = proxy { method, _ ->
        error("Unexpected ${T::class.java.simpleName} call: $method")
    }

    /** [LibraryRelocationTest]'s idiom. */
    private inline fun <reified T> proxy(crossinline answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name, args ?: emptyArray())
        } as T
}
