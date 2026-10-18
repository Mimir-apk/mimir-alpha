package com.gruning.mimir.rewrite.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailureReason
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyPhysicalIdentityPreflightDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun validPersistedAndLegacyManagedRowsPassWithoutTouchingOwnerBytes() {
        (1..10).forEach { schemaVersion ->
            withDatabase("valid-$schemaVersion", schemaVersion) { name, database ->
                insert(
                    database,
                    entryKey = "content://provider/tree/ROM%3A1::SNES/folder::game.sfc",
                    sourceRootUri = "content://provider/tree/ROM%3A1",
                    relativePath = "SNES/folder::game.sfc",
                    locationKind = "SAF_TREE_GRANT",
                    managedPath = null,
                )
                insert(
                    database,
                    entryKey = "/storage/card/ROMs:::game.rom",
                    sourceRootUri = null,
                    relativePath = ":game.rom",
                    locationKind = "MANAGED_PATH",
                    managedPath = "/storage/card/ROMs/:game.rom",
                )
                database.close()
                val before = ownerBytes(name)

                assertNull(MimirDatabaseFactory.inspectDatabaseCompatibility(context, name))
                assertEquals(before, ownerBytes(name))
            }
        }
    }

    @Test
    fun mismatchedLegacyIdentityIsTypedAndOwnerBytesRemainExact() {
        withDatabase("mismatch") { name, database ->
            insert(
                database,
                entryKey = "/other::game.rom",
                sourceRootUri = "/storage/card/ROMs",
                relativePath = "game.rom",
                locationKind = "MANAGED_PATH",
                managedPath = "/storage/card/ROMs/game.rom",
            )
            database.close()
            val before = ownerBytes(name)

            val failure = MimirDatabaseFactory.inspectDatabaseCompatibility(context, name)

            assertEquals(DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE, failure?.reason)
            assertEquals(10, failure?.foundSchemaVersion)
            assertEquals(before, ownerBytes(name))
        }
    }

    @Test
    fun nonTextIdentityStorageClassIsRefusedWithoutCoercion() {
        withDatabase("storage-class") { name, database ->
            database.execSQL(
                "INSERT INTO games(entryKey, sourceRootUri, relativePath, locationKind, managedPath) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf("/root::game.rom", "/root", byteArrayOf(1, 2, 3), "MANAGED_PATH", null),
            )
            database.close()
            val before = ownerBytes(name)

            val failure = MimirDatabaseFactory.inspectDatabaseCompatibility(context, name)

            assertEquals(DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE, failure?.reason)
            assertEquals(before, ownerBytes(name))
        }
    }

    @Test
    fun malformedUtf8TextIsRefusedWithoutReplacementDecoding() {
        withDatabase("malformed-text") { name, database ->
            database.execSQL(
                "INSERT INTO games(entryKey, sourceRootUri, relativePath, locationKind, managedPath) " +
                    "SELECT CAST(X'80' AS TEXT) || '::game.rom', CAST(X'80' AS TEXT), " +
                    "'game.rom', 'MANAGED_PATH', NULL",
            )
            database.close()
            val before = ownerBytes(name)

            val failure = MimirDatabaseFactory.inspectDatabaseCompatibility(context, name)

            assertEquals(DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE, failure?.reason)
            assertEquals(before, ownerBytes(name))
        }
    }

    @Test
    fun malformedLegacyIdentityVisibleOnlyInHotWalIsRefusedWithoutChangingAnyOwnerByte() {
        val name = "ep14-preflight-hot-wal-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        val writer = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            assertTrue(writer.enableWriteAheadLogging())
            writer.execSQL(
                "CREATE TABLE games(" +
                    "entryKey TEXT PRIMARY KEY NOT NULL, sourceRootUri TEXT, relativePath TEXT NOT NULL, " +
                    "locationKind TEXT NOT NULL, managedPath TEXT)",
            )
            writer.execSQL(
                "INSERT INTO games(entryKey, sourceRootUri, relativePath, locationKind, managedPath) " +
                    "SELECT '/root::game.rom', '/root', CAST(X'80' AS TEXT), " +
                    "'MANAGED_PATH', '/root/game.rom'",
            )
            writer.execSQL("PRAGMA user_version = 10")

            val wal = File(file.path + "-wal")
            assertTrue("Fixture did not retain a WAL", wal.isFile && wal.length() > 32L)
            assertNotEquals(
                "Fixture checkpointed the legacy schema into the main header",
                10,
                sqliteHeaderUserVersion(file),
            )
            val before = ownerBytes(name)

            val failure = MimirDatabaseFactory.inspectDatabaseCompatibility(context, name)

            assertEquals(DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE, failure?.reason)
            assertEquals(before, ownerBytes(name))
        } finally {
            writer.close()
            context.deleteDatabase(name)
        }
    }

    private fun withDatabase(
        label: String,
        schemaVersion: Int = 10,
        block: (name: String, database: SQLiteDatabase) -> Unit,
    ) {
        val name = "ep14-preflight-$label-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val database = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        try {
            database.execSQL(
                "CREATE TABLE games(" +
                    "entryKey TEXT PRIMARY KEY NOT NULL, sourceRootUri TEXT, relativePath TEXT NOT NULL, " +
                    "locationKind TEXT NOT NULL, managedPath TEXT)",
            )
            database.execSQL("PRAGMA user_version = $schemaVersion")
            block(name, database)
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(name)
        }
    }

    private fun insert(
        database: SQLiteDatabase,
        entryKey: String,
        sourceRootUri: String?,
        relativePath: String,
        locationKind: String,
        managedPath: String?,
    ) {
        database.execSQL(
            "INSERT INTO games(entryKey, sourceRootUri, relativePath, locationKind, managedPath) " +
                "VALUES (?, ?, ?, ?, ?)",
            arrayOf(entryKey, sourceRootUri, relativePath, locationKind, managedPath),
        )
    }

    private fun ownerBytes(name: String): Map<String, String> {
        val main = context.getDatabasePath(name)
        return listOf("", "-wal", "-journal", "-shm")
            .mapNotNull { suffix ->
                File(main.path + suffix).takeIf(File::isFile)?.let { file ->
                    suffix to MessageDigest.getInstance("SHA-256")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                }
            }
            .toMap()
    }

    private fun sqliteHeaderUserVersion(file: File): Int =
        RandomAccessFile(file, "r").use { input ->
            input.seek(60L)
            input.readInt()
        }
}
