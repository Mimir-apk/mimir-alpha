package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * F-08 pins — search, and the three promises the design makes about it in player-facing copy:
 * *"Names only, on the volume you are in. Mimir does not read file contents to search, and nothing is
 * sent anywhere."*
 *
 * Each clause is pinned as a behaviour, not as a comment: names-only by a file whose CONTENTS match and
 * whose name does not, no-network by reading the real source of every file on the search path, and the
 * volume scope by [StorageVolumeListing.volumeRootFor]'s own test below.
 */
class FileSearchPolicyTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-search-test").also { roots.add(it) }.toFile()

    private fun item(name: String, isDirectory: Boolean = false, path: String = "/tmp/$name") =
        FileItem(name = name, path = path, isDirectory = isDirectory)

    // ---- Names only ----

    @Test
    fun `search matches names and never file contents`() = runBlocking {
        val root = tempRoot()
        File(root, "Zelda.gba").writeText("this text contains the word emerald inside it")
        File(root, "Pokemon Emerald.gba").writeText("nothing relevant")

        val hits = LocalFileRepository().search(root.path, "emerald").toList().map { it.name }

        assertEquals(
            "a file whose CONTENTS contain the query must NOT match -- the design promises Mimir does " +
                "not read file contents to search, and reading them would be both a broken promise and " +
                "a whole-volume read on a handheld",
            listOf("Pokemon Emerald.gba"),
            hits,
        )
    }

    @Test
    fun `the pure name predicate is case-insensitive, and a blank query matches everything`() {
        assertTrue(fileSearchNameMatches(item("Pokemon Emerald.gba"), "emerald"))
        assertTrue(fileSearchNameMatches(item("Pokemon Emerald.gba"), "EMERALD"))
        assertFalse(fileSearchNameMatches(item("Pokemon Emerald.gba"), "sapphire"))
        assertTrue(
            "a blank query with a filter chip selected browses that kind -- the design's own behaviour",
            fileSearchNameMatches(item("anything.gba"), ""),
        )
    }

    // ---- R6-P3-03: accented titles and multi-token queries ----

    @Test
    fun `an unaccented query matches an accented name`() {
        assertTrue(
            "\"pokemon\" must find \"Pokémon Red.gb\" -- the old plain .contains(query, " +
                "ignoreCase = true) folded case but not diacritics, so an unaccented query missed " +
                "every accented title. fileSearchNameMatches now folds both sides the way " +
                "core-resolver's TitleCatalog.foldDiacritics folds ROM titles.",
            fileSearchNameMatches(item("Pokémon Red.gb"), "pokemon"),
        )
    }

    @Test
    fun `a multi-token query matches a name containing every token`() {
        assertTrue(
            "\"zelda link\" must find \"The Legend of Zelda - A Link to the Past.sfc\" -- the old " +
                "plain .contains(query) treated the query as one literal phrase, and this name never " +
                "contains the substring \"zelda link\". Each whitespace-separated token must now be " +
                "found independently.",
            fileSearchNameMatches(item("The Legend of Zelda - A Link to the Past.sfc"), "zelda link"),
        )
    }

    // ---- Filter chips ----

    @Test
    fun `each filter chip selects exactly its own kind`() {
        val game = item("Pokemon Emerald.gba")
        val archive = item("Golden Sun.zip")
        val bios = item("gba_bios.bin")
        val save = item("Pokemon Emerald.srm")
        val art = item("Pokemon Emerald.png")
        val folder = item("GBA", isDirectory = true)
        val all = listOf(game, archive, bios, save, art, folder)

        fun select(filter: FileSearchFilter) = all.filter {
            fileSearchMatches(
                item = it,
                query = "",
                filter = filter,
                platformOf = { candidate -> if (candidate === game) "gba" else null },
                isBios = { candidate -> candidate === bios },
            )
        }.map { it.name }

        assertEquals("All must include the folder too", all.map { it.name }, select(FileSearchFilter.ALL))
        assertEquals(listOf("Pokemon Emerald.gba"), select(FileSearchFilter.GAMES))
        assertEquals(listOf("Golden Sun.zip"), select(FileSearchFilter.ARCHIVES))
        assertEquals(listOf("gba_bios.bin"), select(FileSearchFilter.BIOS))
        assertEquals(
            "Saves must not sweep up media art -- that is a separate kind as of F-07",
            listOf("Pokemon Emerald.srm"),
            select(FileSearchFilter.SAVES),
        )
        assertTrue(
            "no chip except All may return a directory",
            FileSearchFilter.entries
                .filterNot { it == FileSearchFilter.ALL }
                .none { "GBA" in select(it) },
        )
    }

    @Test
    fun `the chip labels are the design's own five words`() {
        assertEquals(
            listOf("All", "Games", "Archives", "BIOS", "Saves"),
            FileSearchFilter.entries.map { it.label },
        )
    }

    // ---- One unreadable directory must not kill the whole search ----

    /**
     * F-08's engine defect. Upstream threw out of the traversal loop, so the first permission-denied
     * subdirectory anywhere under the root aborted the entire search and the player lost every result
     * already found.
     *
     * VOID-guarded per this project's "verify the instrument can see the defect" discipline: a sandbox
     * that cannot revoke read permission (running as root, most commonly) proves nothing here, and is
     * reported as VOID rather than as a pass.
     */
    @Test
    fun `one unreadable directory does not fail the whole search`() = runBlocking {
        val root = tempRoot()
        val locked = File(root, "locked").apply { mkdirs() }
        File(locked, "Zelda hidden.gba").writeText("x")
        val open = File(root, "open").apply { mkdirs() }
        File(open, "Zelda visible.gba").writeText("x")

        val readableChanged = locked.setReadable(false, false)
        val executableChanged = locked.setExecutable(false, false)
        val isRoot = System.getProperty("user.name") == "root"
        if (!readableChanged || !executableChanged || isRoot || Files.isReadable(locked.toPath())) {
            return@runBlocking // VOID -- see this test's KDoc.
        }

        try {
            val hits = LocalFileRepository().search(root.path, "zelda").toList().map { it.name }

            assertEquals(
                "a permission-denied branch must be skipped, not fatal -- otherwise one unreadable " +
                    "folder anywhere on the card makes search useless",
                listOf("Zelda visible.gba"),
                hits,
            )
        } finally {
            locked.setReadable(true, false)
            locked.setExecutable(true, false)
        }
    }

    /**
     * A bounded collector returns. Smoke coverage only — read the next test for the property that
     * actually matters.
     *
     * **DISCLOSED HOLE, found by differential-testing this suite rather than by review.** This test was
     * originally written to pin the cancellation-rethrow ordering, and it does NOT: deleting the
     * `catch (e: CancellationException) { throw e }` from [LocalFileRepository.search] leaves it green.
     * The reason is that a swallowed cancellation makes the walk *continue*, not *hang* — the loop still
     * terminates when its stack empties, and a fixture tree small enough to build in a unit test empties
     * in milliseconds. Distinguishing the two behaviourally would need a tree large enough for the
     * difference to be a wall-clock measurement, which is exactly the kind of timing-dependent assertion
     * that goes flaky on someone else's machine. So the ordering is pinned structurally instead, below,
     * and this test is renamed to claim only what it proves.
     */
    @Test(timeout = 20_000)
    fun `a bounded collector gets its first result and returns`() = runBlocking {
        val root = tempRoot()
        var current = root
        repeat(40) { depth ->
            current = File(current, "level$depth").apply { mkdirs() }
            File(current, "match-$depth.gba").writeText("x")
        }

        val first = LocalFileRepository().search(root.path, "match").first()

        assertTrue("sanity: something must have been found", first.name.startsWith("match-"))
    }

    /**
     * **The cancellation-rethrow ordering, pinned structurally because it cannot be pinned behaviourally
     * here** — see the test above for why, and for how that was discovered.
     *
     * The hazard is real and specific: F-08 replaced `search`'s rethrowing catch with a broad
     * skip-and-continue one, and `emit()` inside that same `try` is where a `first()`/`take(n)` collector
     * aborts, throwing a `CancellationException` subtype. Without a rethrow ahead of the broad catch, the
     * walk treats "the player closed the search screen" as "this directory was unreadable" and keeps
     * walking the volume. Before F-08 the guard was implicit — `asRepositoryException` rethrows
     * cancellations itself (`RepositoryContract.kt:199`) — and removing that call is what made it need
     * restating.
     *
     * Asserts on the search body only, not the whole file, so [listFiles]' own identical guard cannot
     * make this pass vacuously.
     */
    @Test
    fun `search rethrows cancellation ahead of its broad catch`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/core/files/LocalFileRepository.kt")
        assertTrue("sanity: the source must be readable from the module dir", source.isFile)

        val searchBody = source.readText()
            .substringAfter("fun search(")
            .substringBefore(".flowOn(Dispatchers.IO)")
        assertTrue(
            "sanity: the parse must have found the real search body",
            searchBody.contains("Files.newDirectoryStream") && searchBody.contains("stack"),
        )

        val cancellationCatch = searchBody.indexOf("catch (e: CancellationException)")
        val broadCatch = searchBody.indexOf("catch (_: Exception)")

        assertTrue(
            "search() must rethrow CancellationException -- without it, a collector that stopped early " +
                "is mistaken for an unreadable directory and the walk continues over the whole volume",
            cancellationCatch >= 0,
        )
        assertTrue(
            "sanity: the broad skip-and-continue catch must exist too, or the pin above is meaningless",
            broadCatch >= 0,
        )
        assertTrue(
            "the CancellationException catch must come BEFORE the broad one -- Kotlin matches catches " +
                "in order, so behind it the guard is dead code",
            cancellationCatch < broadCatch,
        )
    }

    // ---- Volume scope ----

    @Test
    fun `volumeRootFor picks the drive a path is on, and is not fooled by a shared prefix`() {
        val volumes = listOf(
            StorageVolume(name = "Internal", path = "/storage/emulated/0", isPrimary = true),
            StorageVolume(name = "SD card", path = "/storage/1A2B-3C4D", isRemovable = true),
        )

        assertEquals(
            "/storage/1A2B-3C4D",
            StorageVolumeListing.volumeRootFor("/storage/1A2B-3C4D/Roms/GBA/Zelda.gba", volumes),
        )
        assertEquals(
            "/storage/emulated/0",
            StorageVolumeListing.volumeRootFor("/storage/emulated/0/Download", volumes),
        )
        assertEquals(
            "a path that IS the volume root belongs to it",
            "/storage/1A2B-3C4D",
            StorageVolumeListing.volumeRootFor("/storage/1A2B-3C4D", volumes),
        )
        assertEquals(
            "a sibling whose name merely STARTS WITH a volume path is not on that volume -- plain " +
                "startsWith gets this wrong, and a player can name a folder anything",
            null,
            StorageVolumeListing.volumeRootFor("/storage/1A2B-3C4D-backup/Roms", volumes),
        )
        assertEquals(
            "the drive chooser itself is not a volume",
            null,
            StorageVolumeListing.volumeRootFor(StorageVolumeListing.VOLUME_ROOT_PATH, volumes),
        )
    }

    // ---- No network, on any path ----

    /**
     * Reads the real sources rather than trusting a comment. Modelled on
     * `ArchiveHelperExtractionTest.noRarPathExistsAnywhereInTheVendoredSurface`, which pins its own
     * licence ruling the same way.
     *
     * The check is import-level: an HTTP call in Kotlin needs one of these types by name, and a
     * source file that imports none of them cannot reach the network no matter what it does at runtime.
     */
    @Test
    fun `no network surface exists anywhere on the search path`() {
        val searchPath = listOf(
            "FileSearchPolicy.kt",
            "LocalFileRepository.kt",
            "LocalListingPolicy.kt",
            "StorageVolumeListing.kt",
        ).map { File("src/main/kotlin/com/gruning/mimir/rewrite/core/files/$it") }

        val networkTokens = listOf(
            "java.net.", "okhttp", "retrofit", "HttpURLConnection", "URLConnection",
            "java.nio.channels.SocketChannel", "Socket(", "android.webkit.WebView", "ktor",
        )

        searchPath.forEach { file ->
            assertTrue(
                "sanity: ${file.path} must exist relative to the module dir, or this test proves nothing",
                file.isFile,
            )
            val source = file.readText()
            networkTokens.forEach { token ->
                assertFalse(
                    "F-08 is offline, always: ${file.name} must not reference $token. The design says so " +
                        "in player-facing copy (\"nothing is sent anywhere\") and this tree forbids " +
                        "network on scan/launch outright.",
                    source.contains(token),
                )
            }
        }
    }

    @Test
    fun `the scope copy is the design's own sentence, unaltered`() {
        assertEquals(
            "Names only, on the volume you are in. Mimir does not read file contents to search, and " +
                "nothing is sent anywhere.",
            FILE_SEARCH_SCOPE_COPY,
        )
    }
}
