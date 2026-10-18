package com.gruning.mimir.rewrite.core.storage.dao

import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.storage.CREATE_TITLE_OVERRIDES_SQL
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameDaoReadModelSqlTest {
    private lateinit var connection: Connection

    @Before
    fun openDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE games (
                    entryKey TEXT NOT NULL,
                    gameId TEXT NOT NULL,
                    canonicalTitle TEXT,
                    titleCatalogKey TEXT,
                    title TEXT NOT NULL,
                    platformId TEXT NOT NULL,
                    duplicateGroupKind TEXT,
                    isPrimaryMember INTEGER NOT NULL,
                    availability TEXT NOT NULL,
                    region TEXT,
                    version TEXT,
                    sizeBytes INTEGER NOT NULL,
                    sourceRootUri TEXT NOT NULL,
                    relativePath TEXT NOT NULL,
                    sourceDocumentUri TEXT,
                    managedPath TEXT,
                    discNumber INTEGER NOT NULL,
                    displayName TEXT NOT NULL,
                    duplicateGroupId TEXT
                )
                """,
            )
            statement.executeUpdate("CREATE TABLE hidden_items (gameId TEXT, hidden INTEGER, hiddenAtMillis INTEGER)")
            statement.executeUpdate("CREATE TABLE favorites (gameId TEXT)")
            statement.executeUpdate("CREATE TABLE emulator_overrides (gameId TEXT)")
            statement.executeUpdate(
                "CREATE TABLE game_id_aliases (oldGameId TEXT PRIMARY KEY, logicalGameId TEXT NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE TABLE launch_proofs (gameId TEXT, outcome TEXT, timestampMillis INTEGER)",
            )
            statement.executeUpdate(
                "CREATE TABLE title_metadata_cache (catalogKey TEXT PRIMARY KEY, canonicalTitle TEXT, " +
                    "description TEXT, source TEXT, cacheExpiresAt INTEGER)",
            )
            statement.executeUpdate(CREATE_TITLE_OVERRIDES_SQL)
            statement.executeUpdate(
                """
                INSERT INTO games VALUES
                    ('disc-1', 'chrono-cross', 'Chrono Cross', 'catalog:chrono-cross', 'Chrono Cross raw', 'ps1', 'MULTI_DISC', 1, 'AVAILABLE',
                     'USA', 'Rev 1', 734003200, 'root', 'disc-1', 'content://roms/disc1', NULL, 1,
                     'Chrono Cross (Disc 1).chd', NULL),
                    ('disc-2', 'chrono-cross', 'Chrono Cross', 'catalog:chrono-cross', 'Chrono Cross raw', 'ps1', 'MULTI_DISC', 0, 'AVAILABLE',
                     'USA', 'Rev 1', 728760320, 'root', 'disc-2', 'content://roms/disc2', NULL, 2,
                     'Chrono Cross (Disc 2).chd', NULL),
                    ('pal-1', 'chrono-cross-pal', 'Chrono Cross', 'catalog:chrono-cross-pal', 'Chrono Cross PAL', 'ps1',
                     'CATALOG_TITLE_ID', 0, 'AVAILABLE', 'EUR', NULL, 730000000, 'root', 'pal-1',
                     'content://roms/pal', NULL, 1,
                     'Chrono Cross PAL.chd', 'chrono-cross')
                """,
            )
            statement.executeUpdate(
                "INSERT INTO title_metadata_cache VALUES " +
                    "('catalog:chrono-cross', 'Chrono Cross', 'A fate-crossing adventure.', 'screenscraper:en', NULL)",
            )
            statement.executeUpdate("INSERT INTO favorites VALUES ('chrono-cross-pal')")
            statement.executeUpdate("INSERT INTO emulator_overrides VALUES ('chrono-cross-pal')")
            statement.executeUpdate(
                "INSERT INTO game_id_aliases VALUES " +
                    "('chrono-cross', 'lg:chrono-cross'), ('chrono-cross-pal', 'lg:chrono-cross')",
            )
            statement.executeUpdate(
                """
                INSERT INTO launch_proofs VALUES
                    ('chrono-cross-pal', 'intent_sent', 1000),
                    ('chrono-cross-pal', 'activity_not_found', 2000),
                    ('chrono-cross', 'intent_sent', 3000),
                    ('other-game', 'intent_sent', 4000)
                """,
            )
        }
    }

    @After
    fun closeDatabase() {
        connection.close()
    }

    @Test
    fun `per-platform member counts use the games indexes instead of scanning every game per row`() {
        // RG Rotate measurement (2026-09-27, 5529 games): an OR across gameId, duplicateGroupId and the alias EXISTS
        // made each row's member count scan all of `games`; one SNES list took 30-60 s and held both Room
        // connections. The production indexes are recreated here so the planner sees what the device sees.
        connection.createStatement().use { statement ->
            statement.executeUpdate("CREATE INDEX index_games_gameId ON games (gameId)")
            statement.executeUpdate("CREATE INDEX index_games_duplicateGroupId ON games (duplicateGroupId)")
            statement.executeUpdate("CREATE INDEX index_games_platformId ON games (platformId)")
            statement.executeUpdate("CREATE INDEX index_game_id_aliases_logicalGameId ON game_id_aliases (logicalGameId)")
        }
        val plan = connection.prepareStatement("EXPLAIN QUERY PLAN " + VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL.replace(":platformId", "?"))
            .use { statement ->
                statement.setString(1, "ps1")
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getString("detail")) }
                }
            }
        assertFalse("full scan of games as a member candidate: $plan", plan.any { it.startsWith("SCAN m") })
    }

    @Test
    fun `favourite candidates list the same visible groups as the per-platform read`() {
        val candidate = readUnboundRow(FAVORITE_CANDIDATE_GAME_ROWS_SQL)
        val platformRow = readRow(VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL, ":platformId", "ps1")
        assertEquals(platformRow.gameId, candidate.gameId)
        assertEquals(platformRow.region, candidate.region)
        assertEquals(platformRow.sizeBytes, candidate.sizeBytes)
        // The favourite sits on a subordinate member. The big read carries no favourite state at all; the
        // small read marks the visible group, as the per-platform read does.
        assertEquals(0, candidate.isFavorite)
        assertEquals(1, platformRow.isFavorite)
        assertTrue(platformRow.gameId in readFavouriteGroupIds())

        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO hidden_items VALUES ('chrono-cross-pal', 1, 0)")
        }
        connection.createStatement().use { statement ->
            statement.executeQuery(FAVORITE_CANDIDATE_GAME_ROWS_SQL).use { result ->
                assertFalse("hiding a member hides its visible group, as the per-platform read does", result.next())
            }
        }
    }

    @Test
    fun `the big read never names favourites, so a star cannot re-run it`() {
        // Slow favorite feedback on RG Rotate (2026-09-28): Room
        // re-runs a query when any table it names changes; this one holds a transaction over 5,500 rows.
        assertFalse(Regex("""\bfavorites\b""").containsMatchIn(FAVORITE_CANDIDATE_GAME_ROWS_SQL))
    }

    @Test
    fun `the favourite group ids mark exactly the rows the per-platform read marks as favourite`() {
        // Both arms: a subordinate member favourited (chrono-cross-pal, from setUp), a visible row favourited
        // directly, and a visible row nobody favourited.
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO games VALUES
                    ('mana', 'secret-of-mana', 'Secret of Mana', NULL, 'Secret of Mana', 'snes', NULL, 1, 'AVAILABLE',
                     'USA', NULL, 2097152, 'root', 'mana', 'content://roms/mana', NULL, 0, 'Secret of Mana.sfc', NULL),
                    ('earth', 'earthbound', 'EarthBound', NULL, 'EarthBound', 'snes', NULL, 1, 'AVAILABLE',
                     'USA', NULL, 3145728, 'root', 'earth', 'content://roms/earth', NULL, 0, 'EarthBound.sfc', NULL)
                """,
            )
            statement.executeUpdate("INSERT INTO favorites VALUES ('secret-of-mana')")
        }
        val favouriteIds = readFavouriteGroupIds()
        val candidateIds = connection.createStatement().use { statement ->
            statement.executeQuery(FAVORITE_CANDIDATE_GAME_ROWS_SQL).use { result ->
                buildSet { while (result.next()) add(result.getString("gameId")) }
            }
        }
        assertEquals(setOf("chrono-cross", "secret-of-mana", "earthbound"), candidateIds)
        // The oracle is the per-platform read's own group-aware favourite column.
        val platformFavourite = listOf("ps1", "snes").flatMap { platform ->
            connection.prepareStatement(VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL.replace(":platformId", "?")).use { statement ->
                statement.setString(1, platform)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getString("gameId") to (result.getInt("isFavorite") == 1)) }
                }
            }
        }.toMap()
        assertEquals(candidateIds, platformFavourite.keys)
        platformFavourite.forEach { (gameId, isFavorite) ->
            assertEquals(gameId, isFavorite, gameId in favouriteIds)
        }
    }

    private fun readFavouriteGroupIds(): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery(FAVORITE_GROUP_IDS_SQL).use { result ->
            buildSet { while (result.next()) add(result.getString(1)) }
        }
    }

    @Test
    fun `platform and single-game SQL preserve detail fields and count only successful exact-game proofs`() {
        val platformRow = readRow(VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL, ":platformId", "ps1")
        val singleRow = readRow(GAME_ROW_BY_ID_SQL, ":gameId", "chrono-cross")

        listOf(platformRow, singleRow).forEach { row ->
            assertEquals("chrono-cross", row.gameId)
            assertEquals("USA", row.region)
            assertEquals("Rev 1", row.version)
            assertEquals(734_003_200L, row.sizeBytes)
            assertEquals(2, row.playCount)
            assertEquals(3_000L, row.lastPlayedMillis)
            assertEquals(3, row.memberCount)
            assertEquals(2, row.discCount)
            assertEquals("A fate-crossing adventure.", row.description)
            assertEquals("screenscraper:en", row.descriptionSource)
        }
    }

    @Test
    fun `regular gamelist orders by last played and never promotes favorites`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO games VALUES
                    ('recent-plain-entry', 'recent-plain', 'Recent Plain', NULL, 'Recent Plain', 'ps1', NULL, 1,
                     'AVAILABLE', NULL, NULL, 1, 'root', 'recent-plain-entry',
                     'content://roms/recent', NULL, 0, 'Recent Plain.chd', NULL),
                    ('favorite-never-entry', 'favorite-never', 'Favorite Never', NULL, 'Favorite Never', 'ps1', NULL, 1,
                     'AVAILABLE', NULL, NULL, 1, 'root', 'favorite-never-entry',
                     'content://roms/favorite', NULL, 0, 'Favorite Never.chd', NULL)
                """,
            )
            statement.executeUpdate("INSERT INTO favorites VALUES ('favorite-never')")
            statement.executeUpdate("INSERT INTO launch_proofs VALUES ('recent-plain', 'intent_sent', 9000)")
        }

        val ids = buildList {
            connection.prepareStatement(VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL.replace(":platformId", "?")).use { statement ->
                statement.setString(1, "ps1")
                statement.executeQuery().use { result ->
                    while (result.next()) add(result.getString("gameId"))
                }
            }
        }

        assertEquals(listOf("recent-plain", "chrono-cross", "favorite-never"), ids)
    }

    // ── R6-P2-03 lever C: Enrich-all's priority order ─────────────────────

    @Test
    fun `enrichment walks favourites, then recently played, then the rest by title`() {
        connection.createStatement().use { statement ->
            // rowid AND title order both put 'zz-plain' first; only the priority order puts it last.
            statement.executeUpdate(
                """
                INSERT INTO games VALUES
                    ('zz-plain-entry', 'zz-plain', 'AAA Plain', NULL, 'AAA Plain', 'ps1', NULL, 1, 'AVAILABLE',
                     NULL, NULL, 1, 'root', 'zz-plain-entry', 'content://roms/plain', NULL, 0, 'AAA Plain.chd', NULL),
                    ('mm-favorite-entry', 'mm-favorite', 'MMM Favorite', NULL, 'MMM Favorite', 'ps1', NULL, 1, 'AVAILABLE',
                     NULL, NULL, 1, 'root', 'mm-favorite-entry', 'content://roms/fav', NULL, 0, 'MMM Favorite.chd', NULL),
                    ('aa-recent-entry', 'aa-recent', 'ZZZ Recent', NULL, 'ZZZ Recent', 'ps1', NULL, 1, 'AVAILABLE',
                     NULL, NULL, 1, 'root', 'aa-recent-entry', 'content://roms/recent', NULL, 0, 'ZZZ Recent.chd', NULL)
                """,
            )
            statement.executeUpdate("INSERT INTO favorites VALUES ('mm-favorite')")
            statement.executeUpdate("INSERT INTO launch_proofs VALUES ('aa-recent', 'intent_sent', 9000)")
        }
        val ids = buildList {
            connection.prepareStatement(PRIMARY_GAMES_FOR_ENRICHMENT_SQL).use { statement ->
                statement.executeQuery().use { result -> while (result.next()) add(result.getString("gameId")) }
            }
        }
        // chrono-cross is a favourite through its group member chrono-cross-pal and was played at 3000
        // (base fixture, above).
        assertEquals(listOf("chrono-cross", "mm-favorite", "aa-recent", "zz-plain"), ids)
    }

    @Test
    fun `MAME inventory query is exact-root available arcade only and stably bounded`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO games VALUES
                    ('arcade-b', 'arcade-b', NULL, NULL, 'B', 'arcade', NULL, 0, 'AVAILABLE',
                     NULL, NULL, 2, 'root-a', 'z/b.zip', NULL, '/a/b.zip', 0, 'b.zip', NULL),
                    ('arcade-a', 'arcade-a', NULL, NULL, 'A', 'arcade', NULL, 1, 'AVAILABLE',
                     NULL, NULL, 1, 'root-a', 'a/a.zip', NULL, '/a/a.zip', 0, 'a.zip', NULL),
                    ('detached', 'detached', NULL, NULL, 'D', 'arcade', NULL, 1, 'STORAGE_UNAVAILABLE',
                     NULL, NULL, 1, 'root-a', 'd.zip', NULL, '/a/d.zip', 0, 'd.zip', NULL),
                    ('other-platform', 'other-platform', NULL, NULL, 'O', 'snes', NULL, 1, 'AVAILABLE',
                     NULL, NULL, 1, 'root-a', 'o.zip', NULL, '/a/o.zip', 0, 'o.zip', NULL),
                    ('foreign-root', 'foreign-root', NULL, NULL, 'F', 'arcade', NULL, 1, 'AVAILABLE',
                     NULL, NULL, 1, 'root-a-child', 'f.zip', NULL, '/b/f.zip', 0, 'f.zip', NULL)
                """,
            )
        }

        val keys = connection.prepareStatement(
            AVAILABLE_ARCADE_RESIDENTS_FOR_EXACT_SOURCE_SQL,
        ).use { statement ->
            statement.setString(1, "root-a")
            statement.setInt(2, 10)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString("entryKey"))
                }
            }
        }
        val bounded = connection.prepareStatement(
            AVAILABLE_ARCADE_RESIDENTS_FOR_EXACT_SOURCE_SQL,
        ).use { statement ->
            statement.setString(1, "root-a")
            statement.setInt(2, 1)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString("entryKey"))
                }
            }
        }

        assertEquals(listOf("arcade-a", "arcade-b"), keys)
        assertEquals(listOf("arcade-a"), bounded)
    }

    @Test
    fun `metadata projection falls back to stable game identity when no catalog key exists`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "UPDATE games SET titleCatalogKey = NULL WHERE gameId = 'chrono-cross'",
            )
            statement.executeUpdate("DELETE FROM title_metadata_cache")
            statement.executeUpdate(
                "INSERT INTO title_metadata_cache VALUES " +
                    "('game:chrono-cross', 'Chrono Cross', 'A locally keyed synopsis.', 'screenscraper:en', NULL)",
            )
        }

        val row = readRow(GAME_ROW_BY_ID_SQL, ":gameId", "chrono-cross")
        assertEquals("A locally keyed synopsis.", row.description)
        assertEquals("screenscraper:en", row.descriptionSource)
    }

    @Test
    fun `current game provider synopsis outranks a retired catalog-key row`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO title_metadata_cache VALUES " +
                    "('game:chrono-cross', 'Chrono Cross Current', " +
                    "'Current logical winner synopsis.', 'screenscraper:en', NULL)",
            )
        }

        val row = readRow(GAME_ROW_BY_ID_SQL, ":gameId", "chrono-cross")
        assertEquals("Current logical winner synopsis.", row.description)
        assertEquals("Chrono Cross Current", row.providerCanonicalTitle)
    }

    @Test
    fun `legacy locale-unknown provider copy is hidden until English provenance exists`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "UPDATE title_metadata_cache SET canonicalTitle = 'Título heredado', " +
                    "description = 'Descripción heredada', source = 'screenscraper' " +
                    "WHERE catalogKey = 'catalog:chrono-cross'",
            )
        }

        val row = readRow(GAME_ROW_BY_ID_SQL, ":gameId", "chrono-cross")
        assertNull(row.providerCanonicalTitle)
        assertNull(row.description)
        assertNull(row.descriptionSource)
    }

    @Test
    fun `offline identity-only cache row does not masquerade as a completed provider synopsis lookup`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "UPDATE title_metadata_cache SET description = NULL, source = 'title_catalog' " +
                    "WHERE catalogKey = 'catalog:chrono-cross'",
            )
        }

        val row = readRow(GAME_ROW_BY_ID_SQL, ":gameId", "chrono-cross")
        assertNull(row.description)
        assertNull(row.descriptionSource)
    }

    // ── Steam storefront description : pins that the 19-site widening to
    // ACCEPTED_VERIFIED_METADATA_SOURCES_SQL actually surfaces a `steam:store` row through the SAME
    // projection ScreenScraper already used, and that the hydration selector stops re-selecting a
    // pc_games row once it carries one. ──────────────────────────────────────────────────────────────

    @Test
    fun `a steam store verified row surfaces through the read model exactly like a ScreenScraper row`() {
        insertPcGame(entryKey = "pc-1", gameId = "pc-game-with-desc", displayName = "Blasphemous 2.steam")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO title_metadata_cache VALUES " +
                    "('game:pc-game-with-desc', 'Blasphemous 2', " +
                    "'The Penitent One awakens as Blasphemous 2 joins him once again.', 'steam:store', NULL)",
            )
        }

        val row = readRow(GAME_ROW_BY_ID_SQL, ":gameId", "pc-game-with-desc")
        assertEquals(
            "The Penitent One awakens as Blasphemous 2 joins him once again.",
            row.description,
        )
        assertEquals("steam:store", row.descriptionSource)
        assertEquals("Blasphemous 2", row.providerCanonicalTitle)
    }

    @Test
    fun `the hydration selector stops re-selecting a pc_games row once it carries a verified Steam description`() {
        insertPcGame(entryKey = "pc-1", gameId = "pc-game-with-desc", displayName = "Blasphemous 2.steam")
        insertPcGame(entryKey = "pc-2", gameId = "pc-game-without-desc", displayName = "Undescribed.steam")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO title_metadata_cache VALUES " +
                    "('game:pc-game-with-desc', 'Blasphemous 2', 'A synopsis.', 'steam:store', NULL)",
            )
        }

        val selected = connection.prepareStatement(
            PRIMARY_GAMES_WITHOUT_VERIFIED_METADATA_SQL.replace(":limit", "?"),
        ).use { statement ->
            statement.setInt(1, 100)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString("gameId")) }
            }
        }

        assertFalse(
            "a pc_games row with a verified steam:store description must never be re-selected -- " +
                "ScreenScraper structurally can never verify it, so an unwidened selector would retry it " +
                "on every hydration run forever",
            selected.contains("pc-game-with-desc"),
        )
        assertTrue(selected.contains("pc-game-without-desc"))
    }

    // ── R6-P3-01 T4: the read-side half of the capped-source 24h rule (FRESH_DESCRIPTION_SQL). ─────────

    @Test
    fun `a fresh igdb synopsis surfaces through the read model with its own descriptionSource`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM title_metadata_cache")
            statement.executeUpdate(
                "INSERT INTO title_metadata_cache VALUES " +
                    "('game:chrono-cross', 'Chrono Cross', 'An IGDB name-guess synopsis.', 'igdb', 4102444800000)",
            )
        }

        val row = readRow(GAME_ROW_BY_ID_SQL, ":gameId", "chrono-cross")
        assertEquals("An IGDB name-guess synopsis.", row.description)
        assertEquals("igdb", row.descriptionSource)
    }

    @Test
    fun `an expired igdb synopsis reads as no description while descriptionSource stays igdb`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM title_metadata_cache")
            statement.executeUpdate(
                "INSERT INTO title_metadata_cache VALUES " +
                    "('game:chrono-cross', 'Chrono Cross', 'An IGDB name-guess synopsis.', 'igdb', 1)",
            )
        }

        val row = readRow(GAME_ROW_BY_ID_SQL, ":gameId", "chrono-cross")
        assertNull(row.description)
        assertEquals("igdb", row.descriptionSource)
    }

    private fun insertPcGame(entryKey: String, gameId: String, displayName: String) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO games VALUES ('$entryKey', '$gameId', NULL, NULL, '$displayName', " +
                    "'pc_games', NULL, 1, 'AVAILABLE', NULL, NULL, 1, 'root', '$entryKey', NULL, " +
                    "'/roms/pc/$displayName', 0, '$displayName', NULL)",
            )
        }
    }

    @Test
    fun `member SQL emits ordered disc identity display name and size`() {
        val sql = GAME_MEMBERS_READ_SQL.replace(":gameId", "'chrono-cross'")
        val members = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            GameMemberProjection(
                                entryKey = result.getString("entryKey"),
                                discNumber = result.getInt("discNumber"),
                                displayName = result.getString("displayName"),
                                sizeBytes = result.getLong("sizeBytes"),
                            ),
                        )
                    }
                }
            }
        }

        assertEquals(
            listOf(
                GameMemberProjection("disc-1", 1, "Chrono Cross (Disc 1).chd", 734_003_200L),
                GameMemberProjection("pal-1", 1, "Chrono Cross PAL.chd", 730_000_000L),
                GameMemberProjection("disc-2", 2, "Chrono Cross (Disc 2).chd", 728_760_320L),
            ),
            members,
        )
    }

    @Test
    fun `hidden projection retains favorite override and successful launch truth after mutation`() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO hidden_items VALUES ('chrono-cross-pal', 1, 5000)")
        }
        connection.prepareStatement(VISIBLE_GAME_ROWS_FOR_PLATFORM_SQL.replace(":platformId", "?")).use { statement ->
            statement.setString(1, "ps1")
            statement.executeQuery().use { result -> assertFalse(result.next()) }
        }
        val before = readUnboundRow(HIDDEN_GAME_ROWS_SQL)
        assertEquals(1, before.isFavorite)
        assertEquals(1, before.hasOverride)
        assertEquals(2, before.playCount)
        assertEquals(3_000L, before.lastPlayedMillis)

        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO launch_proofs VALUES ('chrono-cross', 'intent_sent', 6000)")
        }
        val after = readUnboundRow(HIDDEN_GAME_ROWS_SQL)
        assertEquals(3, after.playCount)
        assertEquals(6_000L, after.lastPlayedMillis)
        assertEquals(3, after.memberCount)
    }

    @Test
    fun `Home Shelf batch projection omits unavailable hidden and missing games`() {
        assertTrue(hasVisibleAvailableHomeRow("chrono-cross"))
        assertFalse(hasVisibleAvailableHomeRow("missing-game"))

        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "UPDATE games SET availability = 'STORAGE_UNAVAILABLE' " +
                    "WHERE gameId = 'chrono-cross' AND isPrimaryMember = 1",
            )
        }
        assertFalse(hasVisibleAvailableHomeRow("chrono-cross"))

        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "UPDATE games SET availability = 'AVAILABLE' " +
                    "WHERE gameId = 'chrono-cross' AND isPrimaryMember = 1",
            )
            statement.executeUpdate("INSERT INTO hidden_items VALUES ('chrono-cross', 1, 5000)")
        }
        assertFalse(hasVisibleAvailableHomeRow("chrono-cross"))
    }

    @Test
    fun `hash-source SQL returns the primary member's own file, reached by group id too`() {
        // The group's PRIMARY member wins over its siblings, so the same game always hashes the same file —
        // a cached "no achievement set" keyed on that file therefore stays valid across focus visits.
        val primary = readHashSource("chrono-cross")
        assertEquals("disc-1", primary?.entryKey)
        assertEquals("root", primary?.sourceRootUri)
        assertEquals("disc-1", primary?.relativePath)
        assertEquals("ps1", primary?.platformId)
        assertEquals("content://roms/disc1", primary?.sourceDocumentUri)
        assertNull(primary?.managedPath)
        // The member's OWN size, not the collapsed group's — a size ceiling and a cache key are claims about
        // the bytes actually opened.
        assertEquals(734_003_200L, primary?.sizeBytes)

        // A retired member id resolves the logical group and hashes the same stable primary bytes.
        assertEquals("content://roms/disc1", readHashSource("chrono-cross-pal")?.sourceDocumentUri)

        // An unknown id is null, never an exception — an evicted card cannot break the focus path.
        assertNull(readHashSource("no-such-game"))
    }

    @Test
    fun `physical tuple not opaque resident key controls hash and member order`() {
        val physicalFirstKey = physicalResidentKey("aa", "same.gba")
        val physicalSecondKey = physicalResidentKey("z", "same.gba")
        assertTrue(physicalFirstKey > physicalSecondKey)
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO games VALUES
                    ('$physicalSecondKey', 'opaque-order', NULL, NULL, 'Opaque', 'gba', NULL, 0,
                     'AVAILABLE', NULL, NULL, 10, 'z', 'same.gba',
                     'content://roms/physical-second', NULL, 0, 'Same display', NULL),
                    ('$physicalFirstKey', 'opaque-order', NULL, NULL, 'Opaque', 'gba', NULL, 0,
                     'AVAILABLE', NULL, NULL, 10, 'aa', 'same.gba',
                     'content://roms/physical-first', NULL, 0, 'Same display', NULL)
                """,
            )
        }

        assertEquals("content://roms/physical-first", readHashSource("opaque-order")?.sourceDocumentUri)
        val orderedKeys = connection.createStatement().use { statement ->
            statement.executeQuery(
                GAME_MEMBERS_READ_SQL.replace(":gameId", "'opaque-order'"),
            ).use { result ->
                buildList {
                    while (result.next()) add(result.getString("entryKey"))
                }
            }
        }
        assertEquals(listOf(physicalFirstKey, physicalSecondKey), orderedKeys)
    }

    private fun readHashSource(gameId: String): GameHashSourceProjection? =
        connection.createStatement().use { statement ->
            val escapedGameId = gameId.replace("'", "''")
            statement.executeQuery(
                GAME_HASH_SOURCE_SQL.replace(":gameId", "'$escapedGameId'"),
            ).use { result ->
                if (!result.next()) {
                    null
                } else {
                    GameHashSourceProjection(
                        entryKey = result.getString("entryKey"),
                        sourceRootUri = result.getString("sourceRootUri"),
                        relativePath = result.getString("relativePath"),
                        platformId = result.getString("platformId"),
                        sourceDocumentUri = result.getString("sourceDocumentUri"),
                        managedPath = result.getString("managedPath"),
                        sizeBytes = result.getLong("sizeBytes"),
                    )
                }
            }
        }

    private fun readRow(sql: String, parameter: String, value: String): RowResult =
        connection.prepareStatement(sql.replace(parameter, "?")).use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { result ->
                check(result.next()) { "Expected one read-model row" }
                RowResult(
                    gameId = result.getString("gameId"),
                    region = result.getString("region"),
                    version = result.getString("version"),
                    sizeBytes = result.getLong("sizeBytes"),
                    playCount = result.getInt("playCount"),
                    lastPlayedMillis = result.getLong("lastPlayedMillis"),
                    memberCount = result.getInt("memberCount"),
                    discCount = result.getInt("discCount"),
                    isFavorite = result.getInt("isFavorite"),
                    hasOverride = result.getInt("hasOverride"),
                    description = result.getString("description"),
                    descriptionSource = result.getString("descriptionSource"),
                    providerCanonicalTitle = result.getString("providerCanonicalTitle"),
                )
            }
        }

    private fun readUnboundRow(sql: String): RowResult =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "Expected one read-model row" }
                RowResult(
                    gameId = result.getString("gameId"),
                    region = result.getString("region"),
                    version = result.getString("version"),
                    sizeBytes = result.getLong("sizeBytes"),
                    playCount = result.getInt("playCount"),
                    lastPlayedMillis = result.getLong("lastPlayedMillis"),
                    memberCount = result.getInt("memberCount"),
                    discCount = result.getInt("discCount"),
                    isFavorite = result.getInt("isFavorite"),
                    hasOverride = result.getInt("hasOverride"),
                    description = result.getString("description"),
                    descriptionSource = result.getString("descriptionSource"),
                    providerCanonicalTitle = result.getString("providerCanonicalTitle"),
                )
            }
        }

    private fun hasVisibleAvailableHomeRow(gameId: String): Boolean =
        connection.prepareStatement(VISIBLE_AVAILABLE_GAME_ROWS_BY_IDS_SQL.replace(":gameIds", "?")).use { statement ->
            statement.setString(1, gameId)
            statement.executeQuery().use { result -> result.next() }
        }

    @Test
    fun `anyGamesExist is not visibility filtered`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { isolated ->
            isolated.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE games (
                        entryKey TEXT NOT NULL,
                        gameId TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO games(entryKey, gameId) VALUES ('entry/hidden', 'game/hidden')",
                )
            }
            assertTrue(
                isolated.createStatement().use { statement ->
                    statement.executeQuery("SELECT EXISTS(SELECT 1 FROM games)").use { result ->
                        result.next() && result.getInt(1) == 1
                    }
                },
            )
        }
    }

    @Test
    fun `anyGamesExist is false on empty library`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { isolated ->
            isolated.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE games (entryKey TEXT NOT NULL, gameId TEXT NOT NULL)",
                )
            }
            assertFalse(
                isolated.createStatement().use { statement ->
                    statement.executeQuery("SELECT EXISTS(SELECT 1 FROM games)").use { result ->
                        result.next() && result.getInt(1) == 1
                    }
                },
            )
        }
    }

    private data class RowResult(
        val gameId: String,
        val region: String?,
        val version: String?,
        val sizeBytes: Long,
        val playCount: Int,
        val lastPlayedMillis: Long,
        val memberCount: Int,
        val discCount: Int,
        val isFavorite: Int,
        val hasOverride: Int,
        val description: String?,
        val descriptionSource: String?,
        val providerCanonicalTitle: String?,
    )
}
