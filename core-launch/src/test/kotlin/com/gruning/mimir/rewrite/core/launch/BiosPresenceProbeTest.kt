package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosCandidate
import com.gruning.mimir.rewrite.core.model.BiosCandidateKind
import com.gruning.mimir.rewrite.core.model.BiosCandidateRegistry
import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BiosPresenceProbeTest {
    private val ps1Candidate = requireNotNull(BiosCandidateRegistry.forPlatform(PlatformId.PS1))
        .candidates.first { it.value == "scph5501.bin" }

    @Test
    fun folderEnumerableRequiresSuccessfulListFilesNotOnlyExists() {
        val blocked = SyntheticFolder(files = null)
        val readable = SyntheticFolder(files = emptyArray())

        assertTrue(blocked.exists())
        assertTrue(blocked.isDirectory)
        assertFalse(folderIsEnumerable(blocked))
        assertTrue(folderIsEnumerable(readable))
    }

    @Test
    fun directFolderHitWinsPresent() {
        val direct = BiosFolderListing(
            enumerable = true,
            files = listOf(BiosProbeFile("scph5501.bin", 1L)),
        )

        assertEquals(
            BiosPresence.PRESENT,
            BiosPresenceProbe.probe(PlatformId.PS1, ps1Candidate, direct, emptyList()),
        )
    }

    @Test
    fun userLibraryHitWinsPresentAfterDirectMiss() {
        val direct = BiosFolderListing(enumerable = true)
        val library = BiosFolderListing(
            enumerable = true,
            files = listOf(BiosProbeFile("SCPH5501.BIN", 1L)),
        )

        assertEquals(
            BiosPresence.PRESENT,
            BiosPresenceProbe.probe(PlatformId.PS1, ps1Candidate, direct, listOf(library)),
        )
    }

    @Test
    fun enumerableDirectFolderWithoutMatchIsMissing() {
        assertEquals(
            BiosPresence.MISSING,
            BiosPresenceProbe.probe(
                PlatformId.PS1,
                ps1Candidate,
                BiosFolderListing(enumerable = true),
                listOf(BiosFolderListing(enumerable = true)),
            ),
        )
    }

    @Test
    fun unreadableDirectFolderWithoutUserHitIsUnknownNeverMissing() {
        assertEquals(
            BiosPresence.UNKNOWN,
            BiosPresenceProbe.probe(
                PlatformId.PS1,
                ps1Candidate,
                BiosFolderListing(enumerable = false),
                listOf(BiosFolderListing(enumerable = true)),
            ),
        )
    }

    @Test
    fun duckStationChecksumRenameRequiresExactSizeAndCanonicalParent() {
        val canonicalDuckFolder = "/canonical/duckstation/bios"
        val name = "0123456789abcdef0123456789abcdef.bin"

        assertTrue(
            BiosCandidateMatcher.matches(
                PlatformId.PS1,
                ps1Candidate,
                BiosProbeFile(name, 4_194_304L, canonicalDuckFolder),
                duckStationFolderCanonicalPath = canonicalDuckFolder,
            ),
        )
        assertFalse(
            BiosCandidateMatcher.matches(
                PlatformId.PS1,
                ps1Candidate,
                BiosProbeFile(name, 524_287L, canonicalDuckFolder),
                duckStationFolderCanonicalPath = canonicalDuckFolder,
            ),
        )
        assertFalse(
            BiosCandidateMatcher.matches(
                PlatformId.PS1,
                ps1Candidate,
                BiosProbeFile(name, 524_288L, "/user/library"),
                duckStationFolderCanonicalPath = canonicalDuckFolder,
            ),
        )
    }

    @Test
    fun duckStationFolderIsTheOwnersByDefaultAndTheRunningUsersOtherwise() {
        assertEquals(
            "/storage/emulated/0/Android/data/com.github.stenzek.duckstation/files/bios",
            BiosCandidateMatcher.duckStationBiosFolder(),
        )
        assertEquals(BiosCandidateMatcher.duckStationBiosFolder(), BiosCandidateMatcher.duckStationBiosFolder(0))
        assertEquals(
            "/storage/emulated/10/Android/data/com.github.stenzek.duckstation/files/bios",
            BiosCandidateMatcher.duckStationBiosFolder(10),
        )
        listOf(0, 10, 11).forEach { userId ->
            assertEquals(
                "the matcher and the registry name the same DuckStation folder for user $userId",
                EmulatorSeedRegistry.biosFolderPath("com.github.stenzek.duckstation", userId),
                BiosCandidateMatcher.duckStationBiosFolder(userId),
            )
        }
    }

    @Test
    fun aDuckStationImportIsRecognisedOnlyInTheRunningUsersFolder() {
        val importName = "0123456789abcdef0123456789abcdef.bin"
        val ownersFolder = File(BiosCandidateMatcher.duckStationBiosFolder(0)).canonicalPath
        val userTenFolder = File(BiosCandidateMatcher.duckStationBiosFolder(10)).canonicalPath
        fun listingIn(parent: String) = BiosFolderListing(
            enumerable = true,
            files = listOf(BiosProbeFile(importName, 4_194_304L, parent)),
        )

        assertEquals(
            "user 10's own DuckStation import is the BIOS",
            BiosPresence.PRESENT,
            BiosPresenceProbe.probe(PlatformId.PS1, ps1Candidate, listingIn(userTenFolder), emptyList(), userId = 10),
        )
        assertEquals(
            "and it is not recognised as the owner's, which would read a real BIOS as absent",
            BiosPresence.MISSING,
            BiosPresenceProbe.probe(PlatformId.PS1, ps1Candidate, listingIn(userTenFolder), emptyList(), userId = 0),
        )
        assertEquals(
            "a file in the owner's folder is no evidence for user 10",
            BiosPresence.MISSING,
            BiosPresenceProbe.probe(PlatformId.PS1, ps1Candidate, listingIn(ownersFolder), emptyList(), userId = 10),
        )
        assertEquals(
            "the owner keeps recognising its own import with no user given",
            BiosPresence.PRESENT,
            BiosPresenceProbe.probe(PlatformId.PS1, ps1Candidate, listingIn(ownersFolder), emptyList()),
        )
    }

    @Test
    fun ps2MainAndRomSizeBandsAreInclusiveAndRejectOutOfBandFiles() {
        val binExtension = BiosCandidate(BiosCandidateKind.EXTENSION, "bin", required = true)

        assertTrue(BiosCandidateMatcher.matches(PlatformId.PS2, binExtension, BiosProbeFile("scph39001.bin", 1_048_576L)))
        assertTrue(BiosCandidateMatcher.matches(PlatformId.PS2, binExtension, BiosProbeFile("my-ps2-bios.bin", 8_388_608L)))
        assertFalse(BiosCandidateMatcher.matches(PlatformId.PS2, binExtension, BiosProbeFile("scph39001.bin", 1_048_575L)))
        assertFalse(BiosCandidateMatcher.matches(PlatformId.PS2, binExtension, BiosProbeFile("bios.bin", 8_388_609L)))

        assertTrue(BiosCandidateMatcher.isPs2Candidate("rom1.bin", 4_096L))
        assertTrue(BiosCandidateMatcher.isPs2Candidate("EROM.BIN", 1_048_576L))
        assertFalse(BiosCandidateMatcher.isPs2Candidate("rom2.bin", 4_095L))
        assertFalse(BiosCandidateMatcher.isPs2Candidate("erom.bin", 1_048_577L))
    }

    private class SyntheticFolder(
        private val files: Array<File>?,
    ) : File("/synthetic/bios") {
        override fun exists(): Boolean = true
        override fun isDirectory(): Boolean = true
        override fun listFiles(): Array<File>? = files
    }
}
