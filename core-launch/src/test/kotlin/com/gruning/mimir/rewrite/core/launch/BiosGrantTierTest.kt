package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosCandidate
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

/**
 * LG01 slice 2 — tier 3, the BIOS-specific SAF grant.
 *
 * These run without Android: the SAF branch lives behind [BiosLibraryRootLister], so the seeder stays pure
 * orchestration and a fake lister can stand in for a granted tree.
 *
 * The property that matters is directional. Tier 3 is evidence FOR presence only: it can turn UNKNOWN or
 * MISSING into PRESENT, and must never do the reverse — a granted folder that happens not to contain a BIOS
 * file says nothing about whether the emulator's own private folder has one.
 */
class BiosGrantTierTest {

    private val ps1 = PlatformId("ps1")

    /** A folder listing carrying exactly these filenames, enumerable. */
    private fun listingOf(vararg names: String) = BiosFolderListing(
        enumerable = true,
        files = names.map { BiosProbeFile(name = it, sizeBytes = 0L) },
    )

    @Test
    fun `an unreadable direct folder alone is UNKNOWN, never MISSING`() {
        val presence = BiosPresenceProbe.probe(
            platformId = ps1,
            candidate = ps1Candidate(),
            direct = BiosFolderListing(enumerable = false),
            userLibrary = emptyList(),
        )
        assertEquals(
            "an app-private folder Mimir cannot open must stay UNKNOWN — this is the gap tier 3 exists to close",
            BiosPresence.UNKNOWN,
            presence,
        )
    }

    @Test
    fun `a tier-3 grant containing the file turns UNKNOWN into PRESENT`() {
        val candidate = ps1Candidate()
        // Derived from the candidate, not hardcoded: ps1 has five region variants and which one sorts first
        // is not this test's business. Hardcoding "scph5501.bin" made it fail against candidates.first() =
        // "scph5500.bin" — a self-inconsistent fixture asserting nothing about the code.
        val presence = BiosPresenceProbe.probe(
            platformId = ps1,
            candidate = candidate,
            direct = BiosFolderListing(enumerable = false),
            userLibrary = listOf(listingOf(candidate.fileName)),
        )
        assertEquals(BiosPresence.PRESENT, presence)
    }

    @Test
    fun `a tier-3 grant WITHOUT the file cannot manufacture MISSING`() {
        val presence = BiosPresenceProbe.probe(
            platformId = ps1,
            candidate = ps1Candidate(),
            direct = BiosFolderListing(enumerable = false),
            userLibrary = listOf(listingOf("readme.txt", "unrelated.bin")),
        )
        assertEquals(
            "an unrelated granted folder is not evidence of absence in a folder we still cannot read",
            BiosPresence.UNKNOWN,
            presence,
        )
    }

    @Test
    fun `biosGrantRoots defaults to empty so existing persistence keeps working`() = runBlocking {
        val persistence = object : BiosSeedPersistence {
            override suspend fun platformsWithGames() = emptyList<PlatformId>()
            override suspend fun platformForGame(gameId: GameId): PlatformId? = null
            override suspend fun libraryRoots() = emptyList<BiosLibraryRoot>()
            override suspend fun persist(
                platformId: PlatformId,
                emulatorId: EmulatorId,
                packageName: String,
                profiles: List<BiosProfile>,
            ) = Unit
        }
        assertTrue(persistence.biosGrantRoots().isEmpty())
    }

    @Test
    fun `a grant root is tagged so it can never be mistaken for a scan source`() {
        val root = BiosLibraryRoot("content://tree/bios", BiosGrantLocationKind)
        assertEquals("bios-grant", root.locationKind)
    }

    private fun ps1Candidate(): BiosCandidate =
        BiosCandidateRegistry.forPlatform(ps1)?.candidates?.first()
            ?: error("ps1 must have BIOS candidates — slice 1 seeded them")
}
