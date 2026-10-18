package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Destination selection includes platform folders across drives, whether empty or populated.
 *
 * The three cases that killed the single-guess version are the first three tests here, written as the
 * scenarios rather than as unit trivia, because each represents a reported failure. The PS2 case is a zipped PS2 game
 * with no offer to put it in the existing PS2 folder.
 */
class PlatformDestinationPolicyTest {

    private val library = mapOf(
        "gba" to listOf("/storage/9C33-6BBD/Roms/gba"),
        "ps1" to listOf("/storage/9C33-6BBD/Roms/psx"),
    )

    /** ps2 exists on the card and holds nothing yet — the empty-folder setup case. */
    private val onDisk = listOf(
        PlatformFolderCandidate("gba", "/storage/9C33-6BBD/Roms/gba"),
        PlatformFolderCandidate("ps1", "/storage/9C33-6BBD/Roms/psx"),
        PlatformFolderCandidate("ps2", "/storage/9C33-6BBD/Roms/ps2"),
        PlatformFolderCandidate("psp", "/storage/9C33-6BBD/Roms/psp"),
    )

    private fun volume(path: String): String? = when {
        path.startsWith("/storage/9C33-6BBD") -> "/storage/9C33-6BBD"
        path.startsWith("/storage/emulated/0") -> "/storage/emulated/0"
        else -> null
    }

    @Test
    fun `a zipped PS2 game is offered the PS2 folder, which the single-guess version could not do`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/Final Fantasy X.zip",
            // Null, and correctly so: PS2's content contract does not list `zip`, so the classifier
            // recognises nothing. That is the fail-closed ruling of 2026-08-16 and it is NOT relaxed
            // here -- the list simply does not need it.
            recognisedPlatformId = null,
            libraryFolders = library,
            discovered = onDisk,
            volumeRootOf = ::volume,
        )

        assertTrue(
            "the whole point: an unrecognised archive still gets every real destination offered",
            rows.any { it.path == "/storage/9C33-6BBD/Roms/ps2" },
        )
        assertTrue("...and nothing is preselected, because nothing was recognised", rows.none { it.recognised })
    }

    @Test
    fun `an existing platform folder is offered even when empty`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/God of War.7z",
            recognisedPlatformId = null,
            // The library has never sourced anything from ps2 or psp; both folders exist on the card.
            libraryFolders = library,
            discovered = onDisk,
            volumeRootOf = ::volume,
        )

        assertEquals(
            "\"any platform folder on your drives, empty or not\" -- a folder made this morning is " +
                "exactly when this feature is needed, and a library-only list cannot see it",
            listOf("gba", "psx", "ps2", "psp").sorted(),
            rows.map { PlatformDestinationPolicy.folderNameOf(it.path) }.sorted(),
        )
    }

    @Test
    fun `two source folders for one platform are BOTH offered, instead of cancelling each other out`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/Zelda.zip",
            recognisedPlatformId = "gba",
            libraryFolders = mapOf(
                "gba" to listOf("/storage/9C33-6BBD/Roms/gba", "/storage/emulated/0/Roms/gba"),
            ),
            volumeRootOf = ::volume,
        )

        assertEquals(
            "the old policy answered `candidates.singleOrNull()` -- correct while guessing, absurd in a " +
                "list, where the player can simply be shown both",
            2,
            rows.size,
        )
        assertTrue(rows.all { it.recognised })
    }

    @Test
    fun `the recognised platform sorts first, then folders the library actually uses`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/Zelda.zip",
            recognisedPlatformId = "gba",
            libraryFolders = library,
            discovered = onDisk,
            volumeRootOf = ::volume,
        )

        assertEquals("gba", rows.first().platformId)
        assertTrue(rows.first().recognised)
        assertEquals(
            "every tier is a fact, not a taste: what was recognised, then what the library provably " +
                "uses, then what merely exists",
            listOf(true, true, false, false),
            rows.map { it.fromLibrary },
        )
    }

    @Test
    fun `a folder reached from both the library and the disk is ONE row, and it counts as the library's`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/Zelda.zip",
            recognisedPlatformId = null,
            libraryFolders = mapOf("gba" to listOf("/storage/9C33-6BBD/Roms/gba")),
            discovered = listOf(PlatformFolderCandidate("gba", "/storage/9C33-6BBD/Roms/gba/")),
            volumeRootOf = ::volume,
        )

        assertEquals("a trailing slash is the same folder, not a second one", 1, rows.size)
        assertTrue(
            "a duplicate row would also hand the focus ring two identities for one place",
            rows.single().fromLibrary,
        )
    }

    @Test
    fun `the file's own folder is not offered as somewhere to put it`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/9C33-6BBD/Roms/gba/Zelda.zip",
            recognisedPlatformId = "gba",
            libraryFolders = library,
            discovered = onDisk,
            volumeRootOf = ::volume,
        )

        assertTrue(
            "Move into the folder it is already in reports success having moved nothing -- worse than " +
                "an absent row, because the player believes something happened",
            rows.none { it.path == "/storage/9C33-6BBD/Roms/gba" },
        )
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun `a destination on another drive is KEPT and marked, not dropped`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/God of War.7z",
            recognisedPlatformId = null,
            libraryFolders = library,
            discovered = onDisk,
            volumeRootOf = ::volume,
        )

        assertTrue("downloading to internal and extracting to the card IS the task", rows.isNotEmpty())
        assertTrue(
            "ExtractDestinationPolicy refused cross-volume to stop a GUESS crossing drives, never to " +
                "stop the player choosing to",
            rows.all { !it.onSubjectVolume },
        )
    }

    @Test
    fun `nothing at all is a real answer - no library, no folders, no rows`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/mystery.zip",
            recognisedPlatformId = "ps2",
            libraryFolders = emptyMap(),
            discovered = emptyList(),
        )

        assertTrue("a recognised platform is not a destination by itself", rows.isEmpty())
    }

    @Test
    fun `blank and whitespace paths are not destinations`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/x.zip",
            recognisedPlatformId = null,
            libraryFolders = mapOf("gba" to listOf("", "   ", "/storage/emulated/0/Roms/gba")),
            discovered = listOf(PlatformFolderCandidate("", "/storage/emulated/0/Roms/ps2")),
        )

        assertEquals(1, rows.size)
        assertEquals("/storage/emulated/0/Roms/gba", rows.single().path)
        assertFalse(rows.single().recognised)
    }

    // ──────────────────── R-22: a recognised game always has somewhere to go ──

    /**
     * **Observed recognition/destination mismatch.** A `.steam` shortcut Mimir had already badged `PG`, called
     * *"Recognised"*, and annotated *"Runs on PG"* — with no `pcgames` folder anywhere, so the one
     * platform it had just identified was the one platform not offered.
     */
    @Test
    fun `the recognised platform is offered even with no folder, and leads`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/Blasphemous 2.steam",
            recognisedPlatformId = "pc_games",
            libraryFolders = library,
            discovered = onDisk,
            proposedForRecognised = PlatformFolderCandidate("pc_games", "/storage/9C33-6BBD/Roms/pcgames"),
        )

        val first = rows.first()
        assertEquals("/storage/9C33-6BBD/Roms/pcgames", first.path)
        assertTrue("it is the recognised platform", first.recognised)
        assertFalse("and the caller must know to create it", first.exists)
        assertTrue(
            "every other row is a folder that really is there",
            rows.drop(1).all { it.exists },
        )
    }

    /** A gap-filler only: with a real folder present there is nothing to propose. */
    @Test
    fun `no proposal when the recognised platform already has a folder`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/God of War.iso",
            recognisedPlatformId = "ps2",
            libraryFolders = library,
            discovered = onDisk,
            proposedForRecognised = PlatformFolderCandidate("ps2", "/storage/9C33-6BBD/Roms/ps2-2"),
        )

        assertTrue("no invented row beside the real one", rows.all { it.exists })
        assertEquals(
            "and the real ps2 folder still leads, because it is the recognised platform",
            "/storage/9C33-6BBD/Roms/ps2",
            rows.first().path,
        )
    }

    /** Nothing recognised, nothing to propose — a proposal for a platform we did not identify is a guess. */
    @Test
    fun `a proposal is ignored when it does not match what was recognised`() {
        val rows = PlatformDestinationPolicy.destinations(
            subjectPath = "/storage/emulated/0/Download/mystery.bin",
            recognisedPlatformId = null,
            libraryFolders = library,
            discovered = onDisk,
            proposedForRecognised = PlatformFolderCandidate("pc_games", "/storage/9C33-6BBD/Roms/pcgames"),
        )

        assertTrue("nothing was identified, so nothing is proposed", rows.all { it.exists })
    }

}
