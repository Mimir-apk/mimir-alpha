package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-06 pins. The default must refuse an uncertain platform folder rather than offer a wrong one, so most of this suite pins the ways the default DECLINES, which is the
 * part that can actually hurt a player.
 */
class ExtractDestinationPolicyTest {

    private val volumes = listOf(
        StorageVolume(name = "Internal", path = "/storage/emulated/0", isPrimary = true),
        StorageVolume(name = "SD card", path = "/storage/1A2B-3C4D", isRemovable = true),
    )

    private val volumeRootOf: (String) -> String? = { path ->
        StorageVolumeListing.volumeRootFor(path, volumes)
    }

    @Test
    fun `extract here is a fresh sibling directory named after the archive`() {
        assertEquals(
            "/storage/1A2B-3C4D/Roms/GBA/Golden Sun (USA)",
            ExtractDestinationPolicy.extractHereDestination("/storage/1A2B-3C4D/Roms/GBA/Golden Sun (USA).zip"),
        )
        assertEquals(
            "an archive with no extension still gets its own folder, not its parent",
            "/storage/1A2B-3C4D/Roms/bundle",
            ExtractDestinationPolicy.extractHereDestination("/storage/1A2B-3C4D/Roms/bundle"),
        )
    }

    @Test
    fun `a recognised archive with exactly one library folder on this volume gets the default`() {
        val suggestion = ExtractDestinationPolicy.suggestedPlatformFolder(
            archivePath = "/storage/1A2B-3C4D/Downloads/Golden Sun.zip",
            platformId = "gba",
            platformFolders = listOf("/storage/1A2B-3C4D/Roms/GBA"),
            volumeRootOf = volumeRootOf,
        )

        assertEquals("/storage/1A2B-3C4D/Roms/GBA", suggestion)
    }

    @Test
    fun `an unrecognised archive gets no default`() {
        assertNull(
            "the classifier is fail-closed and this must not be braver than the classifier",
            ExtractDestinationPolicy.suggestedPlatformFolder(
                archivePath = "/storage/1A2B-3C4D/Downloads/mystery.zip",
                platformId = null,
                platformFolders = listOf("/storage/1A2B-3C4D/Roms/GBA"),
                volumeRootOf = volumeRootOf,
            ),
        )
    }

    @Test
    fun `a platform the library has never seen gets no default`() {
        assertNull(
            "a player with no GBA games has no \"the GBA folder\"; inventing <volume>/Roms/GBA would be " +
                "fabricating a location",
            ExtractDestinationPolicy.suggestedPlatformFolder(
                archivePath = "/storage/1A2B-3C4D/Downloads/Golden Sun.zip",
                platformId = "gba",
                platformFolders = emptyList(),
                volumeRootOf = volumeRootOf,
            ),
        )
    }

    @Test
    fun `two candidate folders on the same volume is ambiguous, so no default`() {
        assertNull(
            "two candidates make preselection ambiguous, so neither may be selected automatically",
            ExtractDestinationPolicy.suggestedPlatformFolder(
                archivePath = "/storage/1A2B-3C4D/Downloads/Golden Sun.zip",
                platformId = "gba",
                platformFolders = listOf("/storage/1A2B-3C4D/Roms/GBA", "/storage/1A2B-3C4D/Backup/GBA"),
                volumeRootOf = volumeRootOf,
            ),
        )
    }

    @Test
    fun `a candidate on another drive is refused, but one on THIS drive still answers`() {
        assertNull(
            "extracting across volumes is a copy, not a move, and defaulting to it would surprise the player",
            ExtractDestinationPolicy.suggestedPlatformFolder(
                archivePath = "/storage/1A2B-3C4D/Downloads/Golden Sun.zip",
                platformId = "gba",
                platformFolders = listOf("/storage/emulated/0/Roms/GBA"),
                volumeRootOf = volumeRootOf,
            ),
        )

        assertEquals(
            "three folders of which exactly one is on this volume is UNAMBIGUOUS, not ambiguous -- the " +
                "volume filter must run before the ambiguity check, or a multi-drive player never gets " +
                "a default at all",
            "/storage/1A2B-3C4D/Roms/GBA",
            ExtractDestinationPolicy.suggestedPlatformFolder(
                archivePath = "/storage/1A2B-3C4D/Downloads/Golden Sun.zip",
                platformId = "gba",
                platformFolders = listOf(
                    "/storage/emulated/0/Roms/GBA",
                    "/storage/1A2B-3C4D/Roms/GBA",
                    "/storage/emulated/0/Backup/GBA",
                ),
                volumeRootOf = volumeRootOf,
            ),
        )
    }

    @Test
    fun `the same folder listed twice is one candidate, not an ambiguity`() {
        assertEquals(
            "duplicate rows in the library (several games in one folder) must not read as two folders",
            "/storage/1A2B-3C4D/Roms/GBA",
            ExtractDestinationPolicy.suggestedPlatformFolder(
                archivePath = "/storage/1A2B-3C4D/Downloads/Golden Sun.zip",
                platformId = "gba",
                platformFolders = listOf("/storage/1A2B-3C4D/Roms/GBA", "/storage/1A2B-3C4D/Roms/GBA"),
                volumeRootOf = volumeRootOf,
            ),
        )
    }

    @Test
    fun `option one always exists, and losing the default never removes option two`() {
        val declined = ExtractDestinationPolicy.options(
            archivePath = "/storage/1A2B-3C4D/Downloads/mystery.zip",
            platformId = null,
            platformFolders = emptyList(),
            volumeRootOf = volumeRootOf,
        )

        assertEquals("/storage/1A2B-3C4D/Downloads/mystery", declined.here)
        assertFalse(declined.hasPlatformSuggestion)
        assertNull(declined.suggestedPlatformFolder)

        val offered = ExtractDestinationPolicy.options(
            archivePath = "/storage/1A2B-3C4D/Downloads/Golden Sun.zip",
            platformId = "gba",
            platformFolders = listOf("/storage/1A2B-3C4D/Roms/GBA"),
            volumeRootOf = volumeRootOf,
        )

        assertTrue(offered.hasPlatformSuggestion)
        assertEquals(
            "the platform is carried so a surface can NAME what it is offering rather than showing a " +
                "bare path",
            "gba",
            offered.platformId,
        )
    }
}
