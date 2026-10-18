package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.dao.BiosProfileDao
import com.gruning.mimir.rewrite.core.storage.entity.BiosProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBiosGathererTest {
    private val ps2 = PlatformId.PS2
    private val armsx2Id = EmulatorId.fromNativeLauncherKey("come.nanodata.armsx2")
    private val armsx2Pkg = armsx2Id.value
    private val netherPkg = "xyz.aethersx2.android"
    private val sharedPs2Id = EmulatorId("shared-ps2")

    @Test
    fun resolveReturnsOnlyExactScopeAndPackageRows() = runBlocking {
        val gatherer = RoomBiosGatherer(
            FakeBiosDao(
                rows = listOf(
                    entity("gba", "", null, "legacy.bin", BiosPresence.UNKNOWN),
                    entity(ps2.value, armsx2Id.value, armsx2Pkg, "arms.bin", BiosPresence.PRESENT),
                    entity(ps2.value, sharedPs2Id.value, netherPkg, "nether.bin", BiosPresence.MISSING),
                ),
            ),
        )

        val rows = gatherer.resolve(ps2, armsx2Id, armsx2Pkg)

        assertEquals(1, rows.size)
        assertEquals("arms.bin", rows.single().fileName)
        assertEquals(BiosPresence.PRESENT, rows.single().presence)
        assertEquals(armsx2Id, rows.single().emulatorId)
        assertEquals(armsx2Pkg, rows.single().packageName)
    }

    @Test
    fun resolveDoesNotBorrowPackageAMissingRowWhenQueryingPackageBWithSharedId() = runBlocking {
        val gatherer = RoomBiosGatherer(
            FakeBiosDao(
                rows = listOf(
                    entity(ps2.value, "", null, "legacy-missing.bin", BiosPresence.MISSING),
                    entity(ps2.value, sharedPs2Id.value, armsx2Pkg, "package-a-missing.bin", BiosPresence.MISSING),
                ),
            ),
        )

        val packageBRows = gatherer.resolve(ps2, sharedPs2Id, netherPkg)
        assertTrue(packageBRows.isEmpty())

        val packageARows = gatherer.resolve(ps2, sharedPs2Id, armsx2Pkg)
        assertEquals(1, packageARows.size)
        assertEquals("package-a-missing.bin", packageARows.single().fileName)
        assertEquals(BiosPresence.MISSING, packageARows.single().presence)
        assertEquals(sharedPs2Id, packageARows.single().emulatorId)
        assertEquals(armsx2Pkg, packageARows.single().packageName)
    }

    private fun entity(
        platformId: String,
        emulatorScopeKey: String,
        packageName: String?,
        fileName: String,
        presence: BiosPresence,
    ): BiosProfileEntity = BiosProfileEntity(
        platformId = platformId,
        emulatorScopeKey = emulatorScopeKey,
        packageName = packageName,
        fileName = fileName,
        required = true,
        presence = presence.name,
        expectedHash = null,
        expectedRelativePath = null,
    )

    private class FakeBiosDao(
        private val rows: List<BiosProfileEntity>,
    ) : BiosProfileDao() {
        override suspend fun upsertAll(profiles: List<BiosProfileEntity>) = Unit

        override suspend fun forPlatform(platformId: String): List<BiosProfileEntity> = emptyList()

        override suspend fun blockingMissingForPlatform(platformId: String): List<BiosProfileEntity> =
            emptyList()

        override suspend fun forExactScope(
            platformId: String,
            emulatorScopeKey: String,
            packageName: String,
        ): List<BiosProfileEntity> =
            rows.filter {
                it.platformId == platformId &&
                    it.emulatorScopeKey == emulatorScopeKey &&
                    it.packageName == packageName
            }

        override suspend fun blockingMissingForExactScope(
            platformId: String,
            emulatorScopeKey: String,
            packageName: String,
        ): List<BiosProfileEntity> = emptyList()

        override suspend fun deleteForExactScope(platformId: String, emulatorScopeKey: String) = Unit
    }
}
