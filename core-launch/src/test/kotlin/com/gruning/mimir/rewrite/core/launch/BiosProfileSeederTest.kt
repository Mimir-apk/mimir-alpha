package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosCandidateRegistry
import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiosProfileSeederTest {
    @Test
    fun lazyReadIsMemoizedAndRealRevisionsReseed() = runBlocking {
        val persistence = FakePersistence()
        val duckId = EmulatorId.fromNativeLauncherKey("com.github.stenzek.duckstation")
        val duckPkg = duckId.value
        val seeder = BiosProfileSeeder(
            // No Android Context: the SAF branch lives behind BiosLibraryRootLister, so the seeder is
            // pure orchestration and stays a plain JVM unit test. This fake stands in for tier 2.
            rootLister = { BiosFolderListing(enumerable = false) },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { packageName ->
                    packageName == duckPkg
                }
            },
            platformDefault = { duckId },
        )

        seeder.ensureSeeded(PlatformId.PS1, duckId, duckPkg)
        seeder.ensureSeeded(PlatformId.PS1, duckId, duckPkg)
        assertEquals("same lazy key writes once", 1, persistence.writes.size)

        seeder.libraryRevisionChanged()
        assertEquals("library revision reseeds", 2, persistence.writes.size)

        seeder.emulatorAssignmentChanged(PlatformId.PS1, duckId)
        assertEquals("assignment revision reseeds", 3, persistence.writes.size)
        assertTrue(persistence.writes.last().profiles.isNotEmpty())
        // One persisted row per ALTERNATIVE GROUP, not per filename: every PS1 BIOS image is
        // interchangeable, so the six candidates collapse to a single required row.
        assertEquals(
            BiosCandidateRegistry.forPlatform(PlatformId.PS1)!!.candidates.map { it.groupKey }.distinct().size,
            persistence.writes.last().profiles.size,
        )
    }

    /**
     * REGRESSION (device-found 2026-07-18): scph5500/5501/5502 are the JP/US/EU region variants of ONE
     * PS1 BIOS. Before grouping, each was its own required row, so a user holding exactly one of them got
     * two MISSING rows — and LaunchReadinessEngine blocks when ANY required row is MISSING. That reported
     * "needs BIOS" for a PS1 setup that runs fine. The group must roll up to a single satisfied row.
     */
    @Test
    fun oneRegionVariantSatisfiesPs1AndNeverReportsMissing() = runBlocking {
        val persistence = FakePersistence(listOf(BiosLibraryRoot("/user/library", "local")))
        val duckId = EmulatorId.fromNativeLauncherKey("com.github.stenzek.duckstation")
        val duckPkg = duckId.value
        val seeder = BiosProfileSeeder(
            // A readable library folder holding ONLY the US variant — the other two are absent by
            // definition, which is exactly what used to manufacture the false "missing".
            rootLister = {
                BiosFolderListing(
                    enumerable = true,
                    files = listOf(BiosProbeFile("scph5501.bin", 524_288L, "/user/library")),
                )
            },
            persistence = persistence,
            emulators = InstalledEmulatorProvider {
                EmulatorSeedRegistry.candidatesFor(it) { pkg -> pkg == duckPkg }
            },
            platformDefault = { duckId },
        )

        val snapshot = seeder.ensureSeeded(PlatformId.PS1, duckId, duckPkg)

        val ps1 = snapshot.profiles
        assertEquals("PS1 alternatives collapse to one row", 1, ps1.size)
        assertEquals(BiosPresence.PRESENT, ps1.single().presence)
        assertTrue("no required row may be blocking-missing", ps1.none { it.isBlockingMissing })
    }

    private class FakePersistence(
        private val roots: List<BiosLibraryRoot> = emptyList(),
    ) : BiosSeedPersistence {
        data class Write(
            val platformId: PlatformId,
            val emulatorId: EmulatorId,
            val packageName: String,
            val profiles: List<BiosProfile>,
        )

        val writes = mutableListOf<Write>()

        override suspend fun platformsWithGames(): List<PlatformId> = listOf(PlatformId.PS1)

        override suspend fun platformForGame(gameId: GameId): PlatformId? = PlatformId.PS1

        override suspend fun libraryRoots(): List<BiosLibraryRoot> = roots

        override suspend fun persist(
            platformId: PlatformId,
            emulatorId: EmulatorId,
            packageName: String,
            profiles: List<BiosProfile>,
        ) {
            writes += Write(platformId, emulatorId, packageName, profiles)
        }
    }
}
