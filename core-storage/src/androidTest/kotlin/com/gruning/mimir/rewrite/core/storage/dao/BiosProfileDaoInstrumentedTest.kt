package com.gruning.mimir.rewrite.core.storage.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import com.gruning.mimir.rewrite.core.storage.entity.BiosProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiosProfileDaoInstrumentedTest {
    private lateinit var database: MimirDatabase
    private lateinit var dao: BiosProfileDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MimirDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.biosProfileDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun twoExactScopesOnOnePlatformCoexistWithoutOverwrite() = runBlocking {
        dao.replaceExactScope(
            platformId = "ps1",
            emulatorScopeKey = "duckstation",
            profiles = listOf(
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "duckstation",
                    packageName = "com.duckstation",
                    fileName = "scph5501.bin",
                    presence = "PRESENT",
                ),
            ),
        )
        dao.replaceExactScope(
            platformId = "ps1",
            emulatorScopeKey = "retroarch-ps1",
            profiles = listOf(
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "retroarch-ps1",
                    packageName = "com.retroarch",
                    fileName = "scph5501.bin",
                    presence = "MISSING",
                ),
            ),
        )

        assertEquals(
            "PRESENT",
            dao.forExactScope("ps1", "duckstation", "com.duckstation").single().presence,
        )
        assertEquals(
            "MISSING",
            dao.forExactScope("ps1", "retroarch-ps1", "com.retroarch").single().presence,
        )
    }

    @Test
    fun replacingOneScopeDoesNotMutateAnotherScopeOrLegacyEmptyScope() = runBlocking {
        dao.upsertAll(
            listOf(
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "",
                    packageName = null,
                    fileName = "legacy.bin",
                    presence = "UNKNOWN",
                ),
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "duckstation",
                    packageName = "com.duckstation",
                    fileName = "duck.bin",
                    presence = "PRESENT",
                ),
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "retroarch-ps1",
                    packageName = "com.retroarch",
                    fileName = "ra.bin",
                    presence = "MISSING",
                ),
            ),
        )

        dao.replaceExactScope(
            platformId = "ps1",
            emulatorScopeKey = "duckstation",
            profiles = listOf(
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "duckstation",
                    packageName = "com.duckstation",
                    fileName = "duck.bin",
                    presence = "UNKNOWN",
                ),
            ),
        )

        assertEquals("UNKNOWN", dao.forPlatform("ps1").single { it.fileName == "legacy.bin" }.presence)
        assertEquals(
            "MISSING",
            dao.forExactScope("ps1", "retroarch-ps1", "com.retroarch").single().presence,
        )
        assertEquals(
            "UNKNOWN",
            dao.forExactScope("ps1", "duckstation", "com.duckstation").single().presence,
        )
    }

    @Test
    fun emptyReplacementPrunesOnlyTheExactScope() = runBlocking {
        dao.replaceExactScope(
            platformId = "ps2",
            emulatorScopeKey = "armsx2",
            profiles = listOf(
                entity(
                    platformId = "ps2",
                    emulatorScopeKey = "armsx2",
                    packageName = "com.armsx2",
                    fileName = "scph30000.bin",
                    presence = "PRESENT",
                ),
            ),
        )
        dao.replaceExactScope(
            platformId = "ps2",
            emulatorScopeKey = "nether",
            profiles = listOf(
                entity(
                    platformId = "ps2",
                    emulatorScopeKey = "nether",
                    packageName = "com.nether",
                    fileName = "scph30000.bin",
                    presence = "MISSING",
                ),
            ),
        )

        dao.replaceExactScope("ps2", "armsx2", emptyList())

        assertTrue(dao.forExactScope("ps2", "armsx2", "com.armsx2").isEmpty())
        assertEquals(1, dao.forExactScope("ps2", "nether", "com.nether").size)
    }

    @Test
    fun exactScopeReadsRequireMatchingPackageProvenance() = runBlocking {
        dao.replaceExactScope(
            platformId = "ps1",
            emulatorScopeKey = "duckstation",
            profiles = listOf(
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "duckstation",
                    packageName = "com.duckstation.a",
                    fileName = "scph5501.bin",
                    presence = "PRESENT",
                ),
            ),
        )

        assertTrue(dao.forExactScope("ps1", "duckstation", "com.duckstation.b").isEmpty())
        assertTrue(dao.blockingMissingForExactScope("ps1", "duckstation", "com.duckstation.b").isEmpty())
        assertEquals(
            "PRESENT",
            dao.forExactScope("ps1", "duckstation", "com.duckstation.a").single().presence,
        )
    }

    @Test
    fun replaceExactScopeRejectsEmptyScopeKey() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.replaceExactScope("ps1", "", emptyList())
            }
        }
    }

    @Test
    fun replaceExactScopeRejectsScopeMismatchBeforeMutatingStoredRows() = runBlocking {
        dao.replaceExactScope(
            platformId = "ps1",
            emulatorScopeKey = "duckstation",
            profiles = listOf(
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "duckstation",
                    packageName = "com.duckstation",
                    fileName = "scph5501.bin",
                    presence = "PRESENT",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.replaceExactScope(
                    platformId = "ps1",
                    emulatorScopeKey = "duckstation",
                    profiles = listOf(
                        entity(
                            platformId = "ps2",
                            emulatorScopeKey = "duckstation",
                            packageName = "com.duckstation",
                            fileName = "scph5501.bin",
                            presence = "MISSING",
                        ),
                    ),
                )
            }
        }

        assertEquals(
            "PRESENT",
            dao.forExactScope("ps1", "duckstation", "com.duckstation").single().presence,
        )
    }

    @Test
    fun replaceExactScopeRollsBackWhenInsertFailsAfterDeletion() = runBlocking {
        dao.replaceExactScope(
            platformId = "ps1",
            emulatorScopeKey = "duckstation",
            profiles = listOf(
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "duckstation",
                    packageName = "com.duckstation",
                    fileName = "scph5501.bin",
                    presence = "PRESENT",
                ),
                entity(
                    platformId = "ps1",
                    emulatorScopeKey = "duckstation",
                    packageName = "com.duckstation",
                    fileName = "scph5502.bin",
                    presence = "MISSING",
                ),
            ),
        )

        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DROP TRIGGER IF EXISTS mimir_bios_insert_abort")
        sqlite.execSQL(
            "CREATE TRIGGER mimir_bios_insert_abort AFTER INSERT ON bios_profiles " +
                "WHEN NEW.fileName = 'abort-me.bin' BEGIN " +
                "SELECT RAISE(ABORT, 'forced insert failure'); END",
        )
        try {
            assertThrows(Exception::class.java) {
                runBlocking {
                    dao.replaceExactScope(
                        platformId = "ps1",
                        emulatorScopeKey = "duckstation",
                        profiles = listOf(
                            entity(
                                platformId = "ps1",
                                emulatorScopeKey = "duckstation",
                                packageName = "com.duckstation",
                                fileName = "abort-me.bin",
                                presence = "MISSING",
                            ),
                        ),
                    )
                }
            }
            val surviving = dao.forExactScope("ps1", "duckstation", "com.duckstation")
            assertEquals(2, surviving.size)
            assertEquals(
                "PRESENT",
                surviving.single { it.fileName == "scph5501.bin" }.presence,
            )
            assertEquals(
                "MISSING",
                surviving.single { it.fileName == "scph5502.bin" }.presence,
            )
        } finally {
            sqlite.execSQL("DROP TRIGGER IF EXISTS mimir_bios_insert_abort")
        }
    }

    private fun entity(
        platformId: String,
        emulatorScopeKey: String,
        packageName: String?,
        fileName: String,
        presence: String,
        required: Boolean = true,
    ): BiosProfileEntity = BiosProfileEntity(
        platformId = platformId,
        emulatorScopeKey = emulatorScopeKey,
        packageName = packageName,
        fileName = fileName,
        required = required,
        presence = presence,
        expectedHash = null,
        expectedRelativePath = null,
    )
}
