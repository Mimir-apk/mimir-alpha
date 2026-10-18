package com.gruning.mimir.rewrite.core.storage.dao

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.entity.EmulatorOverrideEntity
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.LaunchProofEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleOverrideEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * QA harness : [GameIdentityDao.rekeyRoomState] is the transactional
 * consolidation that runs on EVERY identity remap (`reconcileCurrentWinners`, `reconcileGroup`'s two
 * public entry points `reconcileScannedGroup`/`foldTerminalGroup`) — favorites, hidden_items,
 * emulator_overrides, title_overrides, home_shelf_items, launch_proofs, and title_metadata_cache all
 * move through it. It had ZERO test coverage before this file (reverting the most recent production
 * change to it left `:core-storage:testDebugUnitTest` green).
 *
 * HARNESS SHAPE — Robolectric + a real in-memory Room database, driven ENTIRELY through
 * [GameIdentityDao]'s own public entry points ([GameIdentityDao.reconcileScannedGroup],
 * [GameIdentityDao.foldTerminalGroup], [GameIdentityDao.reconcileCurrentWinners]) plus each sibling
 * DAO's public read/write surface (`FavoriteDao`, `HiddenItemDao`, `EmulatorOverrideDao`, `HomeShelfDao`,
 * `LaunchProofDao`, `TitleMetadataCacheDao`, `GameDao`). `rekeyRoomState` itself is `private`; there is no
 * way to call it directly, so every test here exercises it exactly the way production does — through a
 * real identity remap. Raw SQL (via `db.openHelper`) is used ONLY as a read-only assertion oracle where a
 * DAO exposes no getter for the exact stored value (e.g. `FavoriteDao` only exposes a boolean
 * `isFavorite`, never the row's `favoritedAtMillis`), and in exactly one place as a deliberately
 * malformed fixture to force a real SQLite constraint failure (see the atomicity test) — never to
 * reproduce `rekeyRoomState`'s own consolidation/tie-break logic. That reproduction is precisely the
 * flaw this harness exists to avoid: every other "SqlTest" in this module runs SQL constants against a
 * bare JDBC connection and therefore never executes Room's generated `Dao_Impl` or the Kotlin transaction
 * body at all.
 *
 * TRADEOFF: this is slower than the JDBC-constant style (a real Robolectric + Room bring-up per test,
 * ~0.2–0.4s) and is new machinery for this module (no other host test here builds a real Room database).
 * It is the only shape that actually runs the target function without a device — the JDBC-constant style
 * cannot reach `private` Kotlin code, and `androidTest` needs hardware unavailable to a host test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class RekeyRoomStateHarnessTest {

    private lateinit var db: MimirDatabase
    private lateinit var dao: GameIdentityDao

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MimirDatabase::class.java).build()
        dao = db.gameIdentityDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private fun game(gameId: String, entryKey: String = gameId, titleCatalogKey: String? = null) = GameEntity(
        entryKey = entryKey,
        gameId = gameId,
        platformId = "ps1",
        contentKind = "ROM",
        displayName = gameId,
        title = gameId,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = titleCatalogKey,
        identitySource = "FILENAME",
        locationKind = "SAF_TREE_GRANT",
        availability = "AVAILABLE",
        sizeBytes = 0L,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = null,
        relativePath = entryKey,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    /** Establishes a logical group + `game_id_aliases` graph purely through the public grouping entry
     *  point — never `insertGroup`/`upsertAlias` directly (both `protected`). */
    private suspend fun seedGroup(winner: String, members: List<String>, now: Long = 0L): String {
        dao.reconcileScannedGroup(
            winnerGameId = winner,
            memberGameIds = members,
            groupKind = "EXACT_HASH",
            nowMillis = now,
        )
        return dao.logicalGameIdsForPhysicalIds(listOf(winner)).first()
    }

    /**
     * Simulates the real-world trigger for `rekeyRoomState`: a rescan re-derives the SAME physical file
     * under [newGameId] (a fresh hash proven, etc.) — the old physical `games` row is never written back,
     * so it silently stops existing while the alias graph still remembers it. Drives the real function
     * through `GameIdentityDao.reconcileCurrentWinners`, the runtime call site.
     */
    private suspend fun rescanOnto(logicalId: String, newGameId: String, now: Long = 1000L) {
        db.gameDao().upsertAll(listOf(game(newGameId)))
        dao.reconcileCurrentWinners(listOf(logicalId), nowMillis = now)
    }

    // ── raw-SQL read-only oracles (no DAO getter exposes these exact stored values) ─────────────────

    private fun favoritedAtMillis(gameId: String): Long? = db.openHelper.readableDatabase
        .query(SimpleSQLiteQuery("SELECT favoritedAtMillis FROM favorites WHERE gameId = ?", arrayOf(gameId)))
        .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun hiddenRow(gameId: String): HiddenItemEntity? = db.openHelper.readableDatabase
        .query(
            SimpleSQLiteQuery(
                "SELECT gameId, hidden, reason, duplicateGroupId, isPrimaryMember, hiddenAtMillis " +
                    "FROM hidden_items WHERE gameId = ?",
                arrayOf(gameId),
            ),
        )
        .use { c ->
            if (!c.moveToFirst()) return@use null
            HiddenItemEntity(
                gameId = c.getString(0),
                hidden = c.getInt(1) != 0,
                reason = if (c.isNull(2)) null else c.getString(2),
                duplicateGroupId = if (c.isNull(3)) null else c.getString(3),
                isPrimaryMember = c.getInt(4) != 0,
                hiddenAtMillis = c.getLong(5),
            )
        }

    private fun overrideEmulatorId(gameId: String): String? = db.openHelper.readableDatabase
        .query(SimpleSQLiteQuery("SELECT emulatorId FROM emulator_overrides WHERE gameId = ?", arrayOf(gameId)))
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun titleOverrideTitle(gameId: String): String? = db.openHelper.readableDatabase
        .query(SimpleSQLiteQuery("SELECT title FROM title_overrides WHERE gameId = ?", arrayOf(gameId)))
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun homeShelfRank(profileId: String, itemId: String): Long? = db.openHelper.readableDatabase
        .query(
            SimpleSQLiteQuery(
                "SELECT rank FROM home_shelf_items WHERE profileId = ? AND itemKind = 'EMULATED_GAME' " +
                    "AND itemId = ?",
                arrayOf(profileId, itemId),
            ),
        )
        .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun launchProofCount(gameId: String): Int = db.openHelper.readableDatabase
        .query(SimpleSQLiteQuery("SELECT COUNT(*) FROM launch_proofs WHERE gameId = ?", arrayOf(gameId)))
        .use { c -> c.moveToFirst(); c.getInt(0) }

    private fun metadataDescription(catalogKey: String): String? = db.openHelper.readableDatabase
        .query(SimpleSQLiteQuery("SELECT description FROM title_metadata_cache WHERE catalogKey = ?", arrayOf(catalogKey)))
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    // ── 1. favorites ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `favorites — winner lands at target, source row is deleted, and the max timestamp wins`() = runBlocking {
        val logicalId = seedGroup(winner = "OLD", members = listOf("OLD", "NEW"))
        db.favoriteDao().set(FavoriteEntity("OLD", favoritedAtMillis = 100L))
        db.favoriteDao().set(FavoriteEntity("NEW", favoritedAtMillis = 50L)) // pre-existing at target, LOWER ts

        rescanOnto(logicalId, "NEW")

        assertTrue(db.favoriteDao().isFavorite("NEW"))
        assertFalse(db.favoriteDao().isFavorite("OLD"))
        assertEquals("the max favoritedAtMillis across target+source wins, not the target's own value", 100L, favoritedAtMillis("NEW"))
    }

    // ── 2. hidden_items ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `hidden_items — a hidden=true row outranks a newer hidden=false row, and duplicateGroupId is restamped to target`() = runBlocking {
        val logicalId = seedGroup(winner = "OLD", members = listOf("OLD", "NEW"))
        // Target's OWN pre-existing row is hidden=false but NEWER; source's row is hidden=true but OLDER.
        // The hidden=true row must still win (hidden.filter{hidden}.ifEmpty{hidden} runs before the
        // timestamp tie-break), proving preference-for-hidden beats recency.
        db.hiddenItemDao().upsert(
            HiddenItemEntity("NEW", hidden = false, reason = null, duplicateGroupId = null, isPrimaryMember = true, hiddenAtMillis = 500L),
        )
        db.hiddenItemDao().upsert(
            HiddenItemEntity("OLD", hidden = true, reason = "USER_DISMISSED", duplicateGroupId = null, isPrimaryMember = true, hiddenAtMillis = 100L),
        )

        rescanOnto(logicalId, "NEW")

        val winner = hiddenRow("NEW")
        assertNotNull(winner)
        assertTrue("the hidden=true row must win even though it is older", winner!!.hidden)
        assertEquals(100L, winner.hiddenAtMillis)
        assertEquals("NEW", winner.duplicateGroupId)
        assertTrue(winner.isPrimaryMember)
        assertNull(hiddenRow("OLD"))
    }

    // ── 3. emulator_overrides ─────────────────────────────────────────────────────────────────────

    @Test
    fun `emulator_overrides — the preferred (previous winner) id outranks the target's own pre-existing value`() = runBlocking {
        // Route through the SECOND public call site (reconcileGroup, via reconcileScannedGroup) instead
        // of reconcileCurrentWinners, proving both entry points drive the real rekeyRoomState body.
        seedGroup(winner = "A", members = listOf("A", "B"))
        db.emulatorOverrideDao().set(EmulatorOverrideEntity("B", emulatorId = "target-own-choice", setAtMillis = 999L))
        db.emulatorOverrideDao().set(EmulatorOverrideEntity("A", emulatorId = "preferred-choice", setAtMillis = 1L))

        // Re-fold the SAME group onto a new winner "B": previousGameId becomes "A" (the outgoing
        // winner), so rekeyRoomState receives preferredGameId="A", targetGameId="B", sourceIds=["A"] —
        // preferred and source coincide, target already had its own value, and preferred must still win.
        dao.reconcileScannedGroup(winnerGameId = "B", memberGameIds = listOf("A", "B"), groupKind = "EXACT_HASH", nowMillis = 1L)

        assertEquals(
            "the previous winner's override outranks the target's own pre-existing override",
            "preferred-choice",
            overrideEmulatorId("B"),
        )
        assertNull(overrideEmulatorId("A"))
    }

    // ── 4. title_overrides ────────────────────────────────────────────────────────────────────────

    /**
     * `title_overrides` has no plain "write at an explicit gameId" DAO — the only public write path is
     * `GameIdentityDao.setTitleOverrideForCurrent`, which resolves through the alias chain by design. That
     * makes it impossible to seed a value literally AT a physical id once that id is already aliased into
     * a group (it would silently redirect onto the group's current winner instead) — the same alias
     * resolution the test further below (`titleOverrideForCurrent resolves a pre-remap rename...`) exists
     * to pin. So fixture seeding here goes directly at the row (mirroring how every other store's own
     * explicit-gameId DAO already seeds its fixtures) rather than through the alias-aware helper.
     */
    private fun seedTitleOverrideRow(gameId: String, title: String, setAtMillis: Long) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO title_overrides (gameId, title, setAtMillis) VALUES (?, ?, ?)",
            arrayOf(gameId, title, setAtMillis),
        )
    }

    @Test
    fun `title_overrides — the preferred (previous winner) id outranks the target's own pre-existing rename`() = runBlocking {
        // Same two-step shape as the emulator_overrides test: create the group with "A" as the initial
        // winner (a harmless empty rekey fires), seed both rows directly, THEN re-fold the same group onto
        // "B" so rekeyRoomState receives preferredGameId="A" (the outgoing winner), targetGameId="B", and
        // "B" already has its own pre-existing rename.
        seedGroup(winner = "A", members = listOf("A", "B"))
        seedTitleOverrideRow("B", "Target's Own Rename", 999L)
        seedTitleOverrideRow("A", "Preferred Rename", 1L)

        dao.reconcileScannedGroup(winnerGameId = "B", memberGameIds = listOf("A", "B"), groupKind = "EXACT_HASH", nowMillis = 1L)

        assertEquals(
            "the previous winner's rename outranks the target's own pre-existing rename",
            "Preferred Rename",
            titleOverrideTitle("B"),
        )
        assertNull(titleOverrideTitle("A"))
    }

    /**
     * Closes the coverage gap in title override storage and precedence:
     * the `...ForCurrent` DAO wrapper methods had SQL-level proof
     * (`TitleOverrideSqlTest`) but no Kotlin-level test. Proves `titleOverrideForCurrent` reads a rename
     * set BEFORE a remap through a STALE caller-held id (`"OLD"`), and that `clearTitleOverrideForCurrent`
     * issued at the CURRENT id can actually reach and delete the consolidated row post-remap — the exact
     * defect the earlier change fixed at the SQL layer, now proven at the Kotlin/Room layer for the first time.
     */
    @Test
    fun `titleOverrideForCurrent resolves a pre-remap rename through a stale id, and a current-id clear reaches it after consolidation`() = runBlocking {
        val logicalId = seedGroup(winner = "OLD", members = listOf("OLD", "NEW"))
        dao.setTitleOverrideForCurrent("OLD", "Renamed Before Remap", 100L)

        rescanOnto(logicalId, "NEW")

        assertEquals("Renamed Before Remap", dao.titleOverrideForCurrent("OLD")?.title)
        assertEquals(
            "a stale caller-held id must resolve through the alias chain to the SAME consolidated row",
            "Renamed Before Remap",
            dao.titleOverrideForCurrent("NEW")?.title,
        )

        val deleted = dao.clearTitleOverrideForCurrent("NEW")

        assertEquals("a current-id clear must reach the consolidated row", 1, deleted)
        assertNull(dao.titleOverrideForCurrent("NEW"))
    }

    // ── 5. home_shelf_items ───────────────────────────────────────────────────────────────────────

    @Test
    fun `home_shelf_items — the lowest-rank row wins per profile, re-keyed onto target, independently across profiles`() = runBlocking {
        val logicalId = seedGroup(winner = "OLD", members = listOf("OLD", "NEW"))
        // Profile "p1": target has the WORSE (higher) rank, source has the BETTER (lower) rank — the
        // lower rank must win even though it is the one being deleted.
        db.homeShelfDao().insertItem(homeItem(profileId = "p1", itemId = "NEW", rank = 500L))
        db.homeShelfDao().insertItem(homeItem(profileId = "p1", itemId = "OLD", rank = 10L))
        // Profile "p2": ONLY the source has a row — must still land on target, independently of p1.
        db.homeShelfDao().insertItem(homeItem(profileId = "p2", itemId = "OLD", rank = 20L))

        rescanOnto(logicalId, "NEW")

        assertEquals("lower rank wins even though it came from the source row", 10L, homeShelfRank("p1", "NEW"))
        assertNull(homeShelfRank("p1", "OLD"))
        assertEquals("a second profile is consolidated independently of the first", 20L, homeShelfRank("p2", "NEW"))
        assertNull(homeShelfRank("p2", "OLD"))
    }

    private fun homeItem(profileId: String, itemId: String, rank: Long) =
        com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity(
            profileId = profileId,
            itemKind = "EMULATED_GAME",
            itemId = itemId,
            rank = rank,
            addedAtMillis = 0L,
        )

    // ── 6. launch_proofs ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `launch_proofs — every source row is rekeyed onto target and target's own prior rows survive alongside them`() = runBlocking {
        val logicalId = seedGroup(winner = "OLD", members = listOf("OLD", "NEW"))
        db.launchProofDao().record(launchProof("OLD", timestamp = 1L))
        db.launchProofDao().record(launchProof("OLD", timestamp = 2L))
        db.launchProofDao().record(launchProof("NEW", timestamp = 3L)) // target's own pre-existing proof

        rescanOnto(logicalId, "NEW")

        assertEquals("both rekeyed source proofs plus the pre-existing target proof", 3, launchProofCount("NEW"))
        assertEquals(0, launchProofCount("OLD"))
    }

    private fun launchProof(gameId: String, timestamp: Long) = LaunchProofEntity(
        gameId = gameId,
        androidPackageName = null,
        platformId = "ps1",
        emulatorId = "core",
        locator = "root::$gameId",
        outcome = "intent_sent",
        timestampMillis = timestamp,
    )

    // ── 7. title_metadata_cache ───────────────────────────────────────────────────────────────────

    @Test
    fun `metadata — a game-keyed cache row is copied onto target's key, and the source row is left in place (never deleted)`() = runBlocking {
        val logicalId = seedGroup(winner = "OLD", members = listOf("OLD", "NEW"))
        db.titleMetadataCacheDao().upsertAll(
            listOf(
                TitleMetadataCacheEntity(
                    catalogKey = "game:OLD",
                    platformId = "ps1",
                    canonicalTitle = "Foo",
                    region = null,
                    version = null,
                    cachedAtMillis = 0L,
                    description = "The synopsis.",
                ),
            ),
        )

        rescanOnto(logicalId, "NEW")

        assertEquals(
            "the richer/preferred source row is copied onto the target's own game:<id> key",
            "The synopsis.",
            metadataDescription("game:NEW"),
        )
        assertEquals(
            "unlike every other store, title_metadata_cache rows are never deleted by rekeyRoomState — " +
                "the source row survives as inert historical debris since nothing ever re-queries the old key",
            "The synopsis.",
            metadataDescription("game:OLD"),
        )
    }

    // ── invariant 1: the delete sweep can never reach the row it just wrote (or any pre-existing target row) ──

    @Test
    fun `invariant — a favorite that already lives at target, untouched by any source, survives the remap unchanged`() = runBlocking {
        val logicalId = seedGroup(winner = "OLD", members = listOf("OLD", "NEW"))
        // No favorite at the source at all — only a pre-existing one at target. The delete sweep must
        // iterate `sources` (which excludes targetGameId by construction), never `allIds`; if it ever
        // iterated allIds, this row would be deleted immediately after rekeyRoomState re-upserts it.
        db.favoriteDao().set(FavoriteEntity("NEW", favoritedAtMillis = 42L))

        rescanOnto(logicalId, "NEW")

        assertTrue("an untouched target-only favorite must survive its own remap", db.favoriteDao().isFavorite("NEW"))
        assertEquals(42L, favoritedAtMillis("NEW"))
    }

    // ── invariant 2: the whole consolidation is transactional — a failure partway rolls everything back ──

    @Test
    fun `invariant — a failure partway through the remap transaction rolls back every store rekeyRoomState already wrote`() = runBlocking {
        val logicalId = seedGroup(winner = "OLD", members = listOf("OLD", "NEW"))
        db.favoriteDao().set(FavoriteEntity("OLD", favoritedAtMillis = 100L))

        // Deliberately malformed fixture, reachable through NO production call sequence (a physical id can
        // only ever be aliased to one logical group, and `currentGameId` is always set to a physical id
        // aliased to that same group — so two groups can never legitimately collide on `currentGameId`,
        // which is exactly why it carries a UNIQUE index). Planting this row directly is the only way to
        // force `reconcileCurrentWinners`'s tail `updateGroup(logicalGameId, currentGameId = "NEW", ...)`
        // call to hit a real SQLite constraint violation AFTER rekeyRoomState has already run — proving the
        // whole `@Transaction` rolls back, not just reproducing rekeyRoomState's own logic.
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO logical_game_groups " +
                "(logicalGameId, currentGameId, groupKind, memberless, createdAtMillis, updatedAtMillis) " +
                "VALUES ('lg:collider', 'NEW', 'EXACT_HASH', 0, 0, 0)",
        )

        var threw = false
        try {
            rescanOnto(logicalId, "NEW")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        } catch (e: RuntimeException) {
            // Room may wrap the SQLite exception; accept any RuntimeException whose cause chain contains it.
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is android.database.sqlite.SQLiteConstraintException) {
                    threw = true
                    break
                }
                cause = cause.cause
            }
            if (!threw) throw e
        }

        assertTrue("expected the colliding currentGameId UNIQUE index to abort the transaction", threw)
        assertTrue(
            "rekeyRoomState's favorite write must have been rolled back along with the failed tail",
            db.favoriteDao().isFavorite("OLD"),
        )
        assertFalse(db.favoriteDao().isFavorite("NEW"))
    }
}
