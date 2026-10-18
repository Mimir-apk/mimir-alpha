package com.gruning.mimir.rewrite.core.storage.dao

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.LaunchProofEntity
import com.gruning.mimir.rewrite.core.storage.scan.ExternalStorageDocumentPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A game's launch history: first played, last played and the count are read from `launch_proofs` under the game's id, so
 * they follow the id. Two things used to cut them loose, both exercised here through a real in-memory Room database and the DAO
 * code Room generates (the shape of [RekeyRoomStateHarnessTest]):
 *
 * - **A rescan names the same file differently.** The scan upserts by `entryKey`, so the row takes the new id and the old one
 *   stops existing; a game alone under its id has no logical lineage to follow it, so its launches stayed behind. Driven here the
 *   way the scan drives it: [GameIdentityDao.entryGameIdentitiesForRoot] before the write, the upsert, then
 *   [GameIdentityDao.carryLaunchHistoryAcrossReidentification].
 * - **A merge then a split.** The merge rekeys the loser's launches onto the winner, and the split
 *   ([GameIdentityDao.quarantineAndClearA1CContainerIdentity]) used to leave all of them there. A launch records the locator it
 *   dispatched, so the ones that named a part's own file go back to it; the rest cannot be told apart and stay with the id that
 *   kept the original.
 *
 * A file the SAF walker indexed has no managed path, so a file-path emulator's launch records the `/storage/...` path its
 * document uri names (and a CONTENT_URI emulator's the uri): the SAF cases record launches that way, and a split or a departure
 * must still match them. The generated multi-disc `.m3u` is deliberately not matched (see `launchLocatorsOf`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class LaunchHistoryIdentityChangeRoomTest {

    private lateinit var db: MimirDatabase
    private lateinit var identity: GameIdentityDao

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MimirDatabase::class.java).build()
        identity = db.gameIdentityDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // ── a rescan names the same file differently ──────────────────────────────────────────────────

    @Test
    fun `an id change keeps first played, last played and the count`() = runBlocking {
        db.gameDao().upsertAll(listOf(file(MANA_FILE, OLD_MANA)))
        launches(OLD_MANA, MANA_PATH, 900L, 100L, 300L)
        db.launchProofDao().record(proof(OLD_MANA, MANA_PATH, 50L, outcome = "BRIDGE_ERROR")) // an attempt, not a play

        val before = identity.entryGameIdentitiesForRoot(ROOT)
        rescanWritesIds(MANA_FILE to NEW_MANA)
        identity.carryLaunchHistoryAcrossReidentification(ROOT, before)

        assertEquals(Played(first = 100L, last = 900L, count = 3), played(NEW_MANA))
        assertNull("the old id no longer reads as a game", db.gameDao().observeGameRow(OLD_MANA).first())
        assertEquals("nothing is left behind at the id nothing reads", 0, proofCount(OLD_MANA))
        assertEquals("the failed attempt travelled too", 4, proofCount(NEW_MANA))
    }

    @Test
    fun `a second rescan changes nothing`() = runBlocking {
        db.gameDao().upsertAll(listOf(file(MANA_FILE, OLD_MANA)))
        launches(OLD_MANA, MANA_PATH, 100L, 300L, 900L)
        val before = identity.entryGameIdentitiesForRoot(ROOT)
        rescanWritesIds(MANA_FILE to NEW_MANA)
        assertEquals(3, identity.carryLaunchHistoryAcrossReidentification(ROOT, before))
        val afterFirst = played(NEW_MANA)

        // The next rescan reads the ids the first one left, writes the same ids, and finds nothing changed.
        val beforeSecond = identity.entryGameIdentitiesForRoot(ROOT)
        rescanWritesIds(MANA_FILE to NEW_MANA)
        assertEquals(0, identity.carryLaunchHistoryAcrossReidentification(ROOT, beforeSecond))
        assertEquals(afterFirst, played(NEW_MANA))
        // Even the first scan's snapshot, replayed, moves nothing: the old id holds no launches any more.
        assertEquals(0, identity.carryLaunchHistoryAcrossReidentification(ROOT, before))
        assertEquals(Played(first = 100L, last = 900L, count = 3), played(NEW_MANA))
    }

    @Test
    fun `a pruned file, a new file and a file whose id did not change move nothing`() = runBlocking {
        db.gameDao().upsertAll(listOf(file(MANA_FILE, OLD_MANA), file(EARTH_FILE, EARTH, path = EARTH_PATH)))
        launches(OLD_MANA, MANA_PATH, 100L)
        launches(EARTH, EARTH_PATH, 200L)
        val before = identity.entryGameIdentitiesForRoot(ROOT)

        // The Mana file is gone from the root; the Earthbound file is untouched; a different file appears under a new id.
        db.gameDao().deleteByEntryKey(MANA_FILE)
        db.gameDao().upsertAll(listOf(file("root::snes/Chrono.sfc", "title:snes:chrono-trigger", path = "/roms/snes/Chrono.sfc")))

        assertEquals(0, identity.carryLaunchHistoryAcrossReidentification(ROOT, before))
        assertEquals("a pruned file's launches stay where they were", 1, proofCount(OLD_MANA))
        assertEquals(Played(first = 200L, last = 200L, count = 1), played(EARTH))
        assertEquals(0, identity.carryLaunchHistoryAcrossReidentification(ROOT, emptyList()))
    }

    @Test
    fun `when a group's winner is renamed and another member is live, that member keeps the history and the launches that named the moved file follow it`() = runBlocking {
        // OLD won a group with NEW; the scan renames OLD's file to OLD2. The group machinery hands OLD's state to the one
        // live member first (as the scan runs it), then the carry judges by where the history now is. Launches are recorded
        // at the winner's id, as production writes them for a folded game.
        db.gameDao().upsertAll(listOf(file(OLD_FILE, "OLD", path = "/roms/old.sfc"), file(NEW_FILE, "NEW", path = "/roms/new.sfc")))
        identity.reconcileScannedGroup("OLD", listOf("OLD", "NEW"), "EXACT_HASH", nowMillis = 1L)
        launches("OLD", "/roms/old.sfc", 100L, 200L)
        launches("OLD", "/roms/new.sfc", 300L)
        val logical = identity.logicalGameIdsForPhysicalIds(listOf("OLD")).single()

        val before = identity.entryGameIdentitiesForRoot(ROOT)
        rescanWritesIds(OLD_FILE to "OLD2")
        identity.reconcileCurrentWinners(listOf(logical), nowMillis = 2L)
        identity.carryLaunchHistoryAcrossReidentification(ROOT, before)

        // NEW is the group's one live member and keeps what it holds; the launches that named OLD's file follow that file.
        assertEquals(Played(first = 300L, last = 300L, count = 1), played("NEW"))
        assertEquals(Played(first = 100L, last = 200L, count = 2), played("OLD2"))
    }

    @Test
    fun `a group every member of which was renamed carries its history to the shelf tile, and each file's own launches to its file`() =
        runBlocking {
            db.gameDao().upsertAll(listOf(file(OLD_FILE, "OLD", path = "/roms/old.sfc"), file(NEW_FILE, "NEW", path = "/roms/new.sfc")))
            identity.reconcileScannedGroup("OLD", listOf("OLD", "NEW"), "EXACT_HASH", nowMillis = 1L)
            launches("OLD", "/roms/old.sfc", 100L, 200L)
            launches("OLD", "/roms/new.sfc", 300L)
            launches("OLD", "somewhere/else.sfc", 50L) // a launch that names no file the group still has
            val logical = identity.logicalGameIdsForPhysicalIds(listOf("OLD")).single()

            val before = identity.entryGameIdentitiesForRoot(ROOT)
            rescanWritesIds(OLD_FILE to "OLD2", NEW_FILE to "NEW2", primary = true)
            identity.reconcileCurrentWinners(listOf(logical), nowMillis = 2L)
            identity.carryLaunchHistoryAcrossReidentification(ROOT, before)

            // The shelf tile (OLD's file) carries on the original, so the launch nothing can place stays with it.
            assertEquals(Played(first = 50L, last = 200L, count = 3), played("OLD2"))
            assertEquals(Played(first = 300L, last = 300L, count = 1), played("NEW2"))
        }

    // ── one id becomes several ────────────────────────────────────────────────────────────────────

    @Test
    fun `when part of an id moves away the id keeps its history, and only the launches that named the moved file go with it`() =
        runBlocking {
            db.gameDao().upsertAll(
                listOf(
                    file(OLD_FILE, SHARED, path = "/roms/one.sfc"),
                    file(NEW_FILE, SHARED, path = "/roms/two.sfc", primary = false, groupId = SHARED),
                ),
            )
            launches(SHARED, "/roms/one.sfc", 100L)
            launches(SHARED, "/roms/two.sfc", 200L)
            launches(SHARED, "nowhere/known.sfc", 300L)

            val before = identity.entryGameIdentitiesForRoot(ROOT)
            rescanWritesIds(NEW_FILE to SPLIT_OFF, primary = true)
            identity.carryLaunchHistoryAcrossReidentification(ROOT, before)

            assertEquals(Played(first = 100L, last = 300L, count = 2), played(SHARED))
            assertEquals(Played(first = 200L, last = 200L, count = 1), played(SPLIT_OFF))
        }

    @Test
    fun `when every file of an id moves to its own id, the tile carries on the original and each file takes its own launches`() =
        runBlocking {
            db.gameDao().upsertAll(
                listOf(
                    file(OLD_FILE, SHARED, path = "/roms/one.sfc"),
                    file(NEW_FILE, SHARED, path = "/roms/two.sfc", primary = false, groupId = SHARED),
                ),
            )
            launches(SHARED, "/roms/one.sfc", 100L)
            launches(SHARED, "/roms/two.sfc", 200L)
            launches(SHARED, "nowhere/known.sfc", 50L)

            val before = identity.entryGameIdentitiesForRoot(ROOT)
            rescanWritesIds(OLD_FILE to "part-a", NEW_FILE to "part-b", primary = true)
            identity.carryLaunchHistoryAcrossReidentification(ROOT, before)

            assertEquals("the first play on record stays with the part that carries on the original", Played(50L, 100L, 2), played("part-a"))
            assertEquals(Played(200L, 200L, 1), played("part-b"))
            assertEquals(0, proofCount(SHARED))
        }

    // ── a merge, then a split ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a merge then a split gives each part its own launches back, and keeps first played with the id that kept the original`() =
        runBlocking {
            db.gameDao().upsertAll(
                listOf(
                    file(OLD_FILE, "winner", path = "/roms/win.zip", platform = "arcade"),
                    file(NEW_FILE, "loser", path = "/roms/lose.zip", platform = "arcade"),
                ),
            )
            launches("winner", "/roms/win.zip", 500L, 900L)
            launches("loser", "/roms/lose.zip", 100L, 300L)

            // The merge: the loser's launches become the winner's, so the winner reads the first play of both.
            identity.foldTerminalGroup("winner", listOf("loser"), "CATALOG_TITLE_ID", nowMillis = 1L)
            assertEquals(Played(first = 100L, last = 900L, count = 4), played("winner"))
            // A launch whose file no part can be matched to, and the earliest of all.
            launches("winner", "gone/before-the-merge.zip", 50L)

            // The split.
            identity.quarantineAndClearA1CContainerIdentity(nowMillis = 2L)

            assertEquals(
                "the id that kept the original keeps its own launches and every one nothing can place, first played included",
                Played(first = 50L, last = 900L, count = 3),
                played("winner"),
            )
            assertEquals("the loser reads the launches that named its own file", Played(first = 100L, last = 300L, count = 2), played("loser"))
        }

    @Test
    fun `splitting the same merge twice changes nothing the second time`() = runBlocking {
        db.gameDao().upsertAll(
            listOf(
                file(OLD_FILE, "winner", path = "/roms/win.zip", platform = "arcade"),
                file(NEW_FILE, "loser", path = "/roms/lose.zip", platform = "arcade"),
            ),
        )
        launches("winner", "/roms/win.zip", 500L)
        launches("loser", "/roms/lose.zip", 100L)
        identity.foldTerminalGroup("winner", listOf("loser"), "CATALOG_TITLE_ID", nowMillis = 1L)
        identity.quarantineAndClearA1CContainerIdentity(nowMillis = 2L)
        val winner = played("winner")
        val loser = played("loser")

        identity.quarantineAndClearA1CContainerIdentity(nowMillis = 3L)

        assertEquals(winner, played("winner"))
        assertEquals(loser, played("loser"))
        assertEquals(Played(500L, 500L, 1), winner)
        assertEquals(Played(100L, 100L, 1), loser)
    }

    // ── SAF-indexed files: the launch is recorded under a path the row has no column for ───────────

    @Test
    fun `a SAF-indexed pair split after a merge gives each file back the launches recorded under its derived path or its document uri`() =
        runBlocking {
            // The walker leaves managedPath null for a tree-grant file; a file-path emulator is handed (and the launch records)
            // the /storage path the document uri names, a CONTENT_URI emulator the uri itself.
            db.gameDao().upsertAll(
                listOf(
                    safFile(OLD_FILE, "winner", "arcade/win.zip", platform = "arcade"),
                    safFile(NEW_FILE, "loser", "arcade/lose.zip", platform = "arcade"),
                ),
            )
            launches("winner", derivedPath("arcade/win.zip"), 500L, 900L)
            launches("loser", derivedPath("arcade/lose.zip"), 100L, 300L)
            launches("loser", documentUri("arcade/lose.zip"), 200L)

            identity.foldTerminalGroup("winner", listOf("loser"), "CATALOG_TITLE_ID", nowMillis = 1L)
            assertEquals(Played(first = 100L, last = 900L, count = 5), played("winner"))
            launches("winner", "gone/before-the-merge.zip", 50L)

            identity.quarantineAndClearA1CContainerIdentity(nowMillis = 2L)

            assertEquals(
                "the winner keeps its own launches and every one nothing can place",
                Played(first = 50L, last = 900L, count = 3),
                played("winner"),
            )
            assertEquals(
                "the loser's file-path and content-uri launches both come back",
                Played(first = 100L, last = 300L, count = 3),
                played("loser"),
            )
        }

    @Test
    fun `a SAF-indexed file that leaves a live id takes the launches recorded under its derived path and its document uri`() =
        runBlocking {
            db.gameDao().upsertAll(
                listOf(
                    safFile(OLD_FILE, SHARED, "snes/one.sfc"),
                    safFile(NEW_FILE, SHARED, "snes/two.sfc", primary = false, groupId = SHARED),
                ),
            )
            launches(SHARED, derivedPath("snes/one.sfc"), 100L)
            launches(SHARED, derivedPath("snes/two.sfc"), 200L)
            launches(SHARED, documentUri("snes/two.sfc"), 250L)
            launches(SHARED, "nowhere/known.sfc", 300L)

            val before = identity.entryGameIdentitiesForRoot(ROOT)
            rescanWritesIds(NEW_FILE to SPLIT_OFF, primary = true)
            assertEquals(2, identity.carryLaunchHistoryAcrossReidentification(ROOT, before))

            assertEquals("the id that stays keeps its own file's launch and the one nothing can place", Played(100L, 300L, 2), played(SHARED))
            assertEquals("the file that left takes both spellings of its own", Played(200L, 250L, 2), played(SPLIT_OFF))
        }

    @Test
    fun `repeating a SAF-indexed carry or split moves nothing the second time`() = runBlocking {
        db.gameDao().upsertAll(
            listOf(
                safFile(OLD_FILE, SHARED, "snes/one.sfc"),
                safFile(NEW_FILE, SHARED, "snes/two.sfc", primary = false, groupId = SHARED),
            ),
        )
        launches(SHARED, derivedPath("snes/one.sfc"), 100L)
        launches(SHARED, derivedPath("snes/two.sfc"), 200L)
        val before = identity.entryGameIdentitiesForRoot(ROOT)
        rescanWritesIds(NEW_FILE to SPLIT_OFF, primary = true)
        assertEquals(1, identity.carryLaunchHistoryAcrossReidentification(ROOT, before))
        val kept = played(SHARED)
        val left = played(SPLIT_OFF)

        // The next rescan's snapshot, and the first scan's snapshot replayed: the launches already moved, so nothing does.
        assertEquals(0, identity.carryLaunchHistoryAcrossReidentification(ROOT, identity.entryGameIdentitiesForRoot(ROOT)))
        assertEquals(0, identity.carryLaunchHistoryAcrossReidentification(ROOT, before))

        assertEquals(kept, played(SHARED))
        assertEquals(left, played(SPLIT_OFF))
        assertEquals(Played(100L, 100L, 1), kept)
        assertEquals(Played(200L, 200L, 1), left)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private data class Played(val first: Long?, val last: Long?, val count: Int)

    private suspend fun played(gameId: String): Played? =
        db.gameDao().observeGameRow(gameId).first()?.let { Played(it.firstPlayedMillis, it.lastPlayedMillis, it.playCount) }

    private fun proofCount(gameId: String): Int = db.openHelper.readableDatabase
        .query(SimpleSQLiteQuery("SELECT COUNT(*) FROM launch_proofs WHERE gameId = ?", arrayOf(gameId)))
        .use { c -> c.moveToFirst(); c.getInt(0) }

    /** The scan's write: the same entries, upserted by `entryKey`, now carrying [newIds]. */
    private suspend fun rescanWritesIds(vararg newIds: Pair<String, String>, primary: Boolean? = null) {
        newIds.forEach { (entryKey, gameId) ->
            val existing = requireNotNull(db.gameDao().byEntryKey(entryKey)) { "no row for $entryKey" }
            db.gameDao().upsertAll(
                listOf(
                    existing.copy(
                        gameId = gameId,
                        isPrimaryMember = primary ?: existing.isPrimaryMember,
                        duplicateGroupId = if (primary == true) null else existing.duplicateGroupId,
                    ),
                ),
            )
        }
    }

    private suspend fun launches(gameId: String, locator: String, vararg at: Long) {
        at.forEach { db.launchProofDao().record(proof(gameId, locator, it)) }
    }

    private fun proof(gameId: String, locator: String, at: Long, outcome: String = "intent_sent") = LaunchProofEntity(
        gameId = gameId,
        androidPackageName = null,
        platformId = "snes",
        emulatorId = "retroarch",
        locator = locator,
        outcome = outcome,
        timestampMillis = at,
    )

    /** A file the SAF walker indexed: no managed path, the document uri under a tree grant on the primary volume. */
    private fun safFile(
        entryKey: String,
        gameId: String,
        relative: String,
        platform: String = "snes",
        primary: Boolean = true,
        groupId: String? = null,
    ) = file(entryKey, gameId, path = null, platform = platform, primary = primary, groupId = groupId, documentUri = documentUri(relative))

    private fun documentUri(relative: String) =
        "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2F" + relative.replace("/", "%2F")

    /** The path a file-path emulator is handed for that document, which is what its launch records. */
    private fun derivedPath(relative: String) = ExternalStorageDocumentPath.pathOf(documentUri(relative))!!

    private fun file(
        entryKey: String,
        gameId: String,
        path: String? = MANA_PATH,
        platform: String = "snes",
        primary: Boolean = true,
        groupId: String? = null,
        documentUri: String? = null,
    ) = GameEntity(
        entryKey = entryKey,
        gameId = gameId,
        platformId = platform,
        contentKind = "ROM",
        displayName = entryKey,
        title = gameId,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = if (documentUri != null) "SAF_TREE_GRANT" else "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 0L,
        contentHash = null,
        sourceRootUri = ROOT,
        sourceDocumentUri = documentUri,
        managedPath = path,
        relativePath = entryKey,
        discNumber = 0,
        duplicateGroupId = groupId,
        isPrimaryMember = primary,
        duplicateGroupKind = null,
    )

    private companion object {
        const val ROOT = "root"
        const val MANA_FILE = "root::snes/Secret of Mana.sfc"
        const val MANA_PATH = "/roms/snes/Secret of Mana.sfc"
        const val OLD_MANA = "title:snes:secret of mana"
        const val NEW_MANA = "catalog:secret_of_mana"
        const val EARTH_FILE = "root::snes/Earthbound.sfc"
        const val EARTH_PATH = "/roms/snes/Earthbound.sfc"
        const val EARTH = "title:snes:earthbound"
        const val OLD_FILE = "root::first"
        const val NEW_FILE = "root::second"
        const val SHARED = "shared"
        const val SPLIT_OFF = "split-off"
    }
}
