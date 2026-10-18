package com.gruning.mimir.rewrite.core.storage

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MimirDatabaseMigration11To12SqlTest {
    private lateinit var connection: Connection

    @Before
    fun openV11Database() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE bios_profiles (" +
                    "platformId TEXT NOT NULL, fileName TEXT NOT NULL, required INTEGER NOT NULL, " +
                    "presence TEXT NOT NULL, expectedHash TEXT, expectedRelativePath TEXT, " +
                    "PRIMARY KEY(platformId, fileName))",
            )
            statement.executeUpdate(
                "CREATE TABLE platforms (" +
                    "platformId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, " +
                    "gamesCount INTEGER NOT NULL, requiresBios INTEGER NOT NULL, catalogLongId TEXT)",
            )
            statement.executeUpdate(
                "INSERT INTO bios_profiles VALUES " +
                    "('ps1', 'present.bin', 1, 'PRESENT', 'hash-present', 'bios/present.bin')," +
                    "('ps1', 'missing.bin', 1, 'MISSING', 'hash-missing', 'bios/missing.bin')," +
                    "('ps2', 'unknown.bin', 0, 'UNKNOWN', NULL, NULL)",
            )
            statement.executeUpdate(
                "INSERT INTO platforms VALUES ('ps1', 'PlayStation', 3, 1, NULL)," +
                    "('ps2', 'PlayStation 2', 0, 0, 'ps2-long')",
            )
        }
    }

    @After
    fun closeDatabase() = connection.close()

    @Test
    fun `v11 presence values migrate to legacy scope with UNKNOWN presence`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT platformId, emulatorScopeKey, packageName, fileName, required, presence, " +
                    "expectedHash, expectedRelativePath FROM bios_profiles ORDER BY fileName",
            ).use { result ->
                assertTrue(result.next())
                assertEquals("ps1", result.getString("platformId"))
                assertEquals("", result.getString("emulatorScopeKey"))
                assertNull(result.getString("packageName"))
                assertEquals("missing.bin", result.getString("fileName"))
                assertEquals(1, result.getInt("required"))
                assertEquals("UNKNOWN", result.getString("presence"))
                assertEquals("hash-missing", result.getString("expectedHash"))
                assertEquals("bios/missing.bin", result.getString("expectedRelativePath"))

                assertTrue(result.next())
                assertEquals("present.bin", result.getString("fileName"))
                assertEquals("UNKNOWN", result.getString("presence"))
                assertEquals("hash-present", result.getString("expectedHash"))

                assertTrue(result.next())
                assertEquals("ps2", result.getString("platformId"))
                assertEquals("unknown.bin", result.getString("fileName"))
                assertEquals(0, result.getInt("required"))
                assertEquals("UNKNOWN", result.getString("presence"))
                assertNull(result.getString("expectedHash"))
                assertNull(result.getString("expectedRelativePath"))
                assertFalse(result.next())
            }
        }
    }

    @Test
    fun `v11 platforms requiresBios values survive byte for byte`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT platformId, requiresBios, catalogLongId FROM platforms ORDER BY platformId",
            ).use { result ->
                assertTrue(result.next())
                assertEquals("ps1", result.getString("platformId"))
                assertEquals(1, result.getInt("requiresBios"))

                assertTrue(result.next())
                assertEquals("ps2", result.getString("platformId"))
                assertEquals(0, result.getInt("requiresBios"))
                assertEquals("ps2-long", result.getString("catalogLongId"))
                assertFalse(result.next())
            }
        }
    }

    @Test
    fun `v12 primary key includes emulator scope`() {
        migrate()

        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO bios_profiles VALUES " +
                    "('ps1', 'duckstation', 'com.duckstation', 'scph5501.bin', 1, 'PRESENT', NULL, NULL)," +
                    "('ps1', 'retroarch-ps1', 'com.retroarch', 'scph5501.bin', 1, 'MISSING', NULL, NULL)",
            )
            statement.executeQuery(
                "SELECT COUNT(*) FROM bios_profiles WHERE platformId = 'ps1' AND fileName = 'scph5501.bin'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals(2, result.getInt(1))
            }
        }
    }

    @Test
    fun `newerSchemaFailure only compares integer ordering`() {
        assertNotNull(MimirDatabaseFactory.newerSchemaFailure(12, 11))
        assertNull(MimirDatabaseFactory.newerSchemaFailure(12, 12))
        assertNull(MimirDatabaseFactory.newerSchemaFailure(11, 12))
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(CREATE_BIOS_PROFILES_V12_SQL)
            statement.executeUpdate(MIGRATE_BIOS_PROFILES_V11_TO_V12_INSERT_SQL)
            statement.executeUpdate("DROP TABLE bios_profiles")
            statement.executeUpdate("ALTER TABLE bios_profiles_v12 RENAME TO bios_profiles")
        }
    }
}
