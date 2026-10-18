package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F8: melonDS 1.10 and a game outside its own folder. F4: a playlist that names a file that is not there.
 * Both are decisions over plain values; no Android class is touched.
 */
class LaunchPreflightTest {
    @get:Rule
    val folder = TemporaryFolder()

    // ---- F8: the melonDS decision -------------------------------------------------------------------------

    private val safDocument =
        "content://com.android.externalstorage.documents/tree/691E-7BEA%3AROMs/document/691E-7BEA%3AROMs%2Fnds%2FGame.nds"
    private val rawPath = "/storage/emulated/0/Roms/nds/Game.nds"
    private val providerUri = "content://com.gruning.mimir.rewrite.launchfiles/ext_storage/Roms/nds/Game.nds"

    private fun melon(version: Long?, locator: String, pkg: String = MelonDsRomFolderRule.PACKAGE_NAME) =
        MelonDsRomFolderRule.refusal(pkg, version, locator)

    @Test
    fun `old melonDS is refused for a path or provider locator and named in plain words`() {
        // 33 is Beta 1.10.0, the newest build whose lookup is an exact match against its own scan.
        listOf(rawPath, providerUri).forEach { locator ->
            val refusal = melon(33L, locator)
            assertNotNull("old melonDS must refuse $locator", refusal)
            assertEquals(LaunchDispatchFailure.EMULATOR_NEEDS_OWN_ROM_FOLDER, refusal!!.failure)
            assertEquals(
                "This melonDS version can only open games from its own ROM folder. " +
                    "Update melonDS, or add this folder in melonDS.",
                refusal.detail,
            )
        }
    }

    @Test
    fun `old melonDS still gets a SAF tree document, the shape its own scan produces`() {
        assertNull(melon(33L, safDocument))
    }

    @Test
    fun `2 point 0 and newer get any locator`() {
        listOf(MelonDsRomFolderRule.VERSION_CODE_2_0_0, 40L).forEach { version ->
            listOf(safDocument, rawPath, providerUri).forEach { locator ->
                assertNull("versionCode $version, $locator", melon(version, locator))
            }
        }
    }

    @Test
    fun `builds between 1_10 and 2_0 carry the fallback source and are not refused`() {
        // 7ce5ce5ba (2025-10-11) is an ancestor of every build from versionCode 35; the tested install reads 37.
        // Refusing 34 to 38 would block builds that open any URI (see MelonDsRomFolderRule).
        listOf(34L, 35L, 37L, 38L).forEach { version ->
            assertNull("versionCode $version", melon(version, providerUri))
        }
    }

    @Test
    fun `an unreadable version, another package and a tree root are not refused`() {
        assertNull("no version is no evidence", melon(null, providerUri))
        assertNull(melon(33L, providerUri, pkg = "me.magnum.melonds.nightly"))
        assertNull(melon(33L, providerUri, pkg = "org.ppsspp.ppsspp"))
        // A tree ROOT is not a document; it is refused like any other non-document locator.
        assertNotNull(melon(33L, "content://com.android.externalstorage.documents/tree/691E-7BEA%3AROMs"))
        // Another provider's document is not the shape melonDS scanned either.
        assertNotNull(melon(33L, "content://com.android.providers.downloads.documents/document/42"))
    }

    // ---- F4: playlists --------------------------------------------------------------------------------------

    private fun fileOps() = PlaylistFileOps(
        readText = { location, max ->
            runCatching { File(location).inputStream().use { PlaylistPreflight.readBoundedText(it, max) } }.getOrNull()
        },
        definitelyMissing = { !File(it).exists() },
    )

    /** Android paths use a forward slash; a Windows host's `File.path` uses a backslash, which no real locator contains. */
    private val File.unix: String get() = path.replace('\\', '/')

    private fun write(relative: String, text: String = "x"): File =
        File(folder.root, relative).also { it.parentFile.mkdirs(); it.writeText(text) }

    @Test
    fun `an m3u with every disc present is not refused`() {
        write("Disc 1.chd"); write("Disc 2.chd")
        val playlist = write("Game.m3u", "#EXTM3U\nDisc 1.chd\nDisc 2.chd\n")
        assertNull(PlaylistPreflight.refusal(playlist.unix, fileOps()))
    }

    @Test
    fun `an m3u names the first missing disc by its place in the list`() {
        write("Disc 1.chd")
        val playlist = write("Game.m3u", "# a comment\n\nDisc 1.chd\nDisc 2.chd\nDisc 3.chd\n")
        val refusal = PlaylistPreflight.refusal(playlist.unix, fileOps())!!
        assertEquals(LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING, refusal.failure)
        assertEquals("Disc 2 is missing: Disc 2.chd", refusal.detail)
    }

    @Test
    fun `an m3u reads quotes, a byte order mark, backslashes and subfolders the way the scan does`() {
        write("Discs/Disc 1.chd")
        val playlist = write("Game.m3u", "﻿\"Discs\\Disc 1.chd\"\n'Discs/Disc 2.chd'\n")
        val refusal = PlaylistPreflight.refusal(playlist.unix, fileOps())!!
        assertEquals("Disc 2 is missing: Disc 2.chd", refusal.detail)
    }

    @Test
    fun `an absolute m3u entry is checked where it points, as Mimir's own generated playlists need`() {
        // Fake ops, not files: an absolute Android path is not a path on a Windows host.
        val present = "/storage/emulated/0/elsewhere/Disc 1.chd"
        val ops = PlaylistFileOps(
            readText = { _, _ -> "$present\n/storage/emulated/0/gone/Disc 2.chd\n" },
            definitelyMissing = { it != present },
        )
        val refusal = PlaylistPreflight.refusal("/storage/emulated/0/Documents/Mimir/Playlists/abc.m3u", ops)!!
        assertEquals("Disc 2 is missing: Disc 2.chd", refusal.detail)
    }

    @Test
    fun `a cue names its first missing track file`() {
        write("Game (Track 1).bin")
        val cue = write(
            "Game.cue",
            "FILE \"Game (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n" +
                "FILE \"Game (Track 2).bin\" BINARY\n  TRACK 02 AUDIO\n    INDEX 01 00:00:00\n",
        )
        val refusal = PlaylistPreflight.refusal(cue.unix, fileOps())!!
        assertEquals(LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING, refusal.failure)
        assertEquals("Track file is missing: Game (Track 2).bin", refusal.detail)
    }

    @Test
    fun `a cue with its bin in place, an unquoted name or a lowercase keyword is not refused`() {
        write("Game.bin")
        val cue = write("Game.cue", "file Game.bin binary\n  track 01 mode2/2352\n")
        assertNull(PlaylistPreflight.refusal(cue.unix, fileOps()))
    }

    // ---- F4, review round: cue sheets one level inside an m3u, and sheets written on a PC ---------------------

    @Test
    fun `an m3u of cue sheets checks each sheet's tracks one level deep`() {
        write("Disc 1.cue", "FILE \"Disc 1.bin\" BINARY\n")
        write("Disc 1.bin")
        write("Disc 2.cue", "FILE \"Disc 2 (Track 1).bin\" BINARY\nFILE \"Disc 2 (Track 2).bin\" BINARY\n")
        write("Disc 2 (Track 1).bin")
        val playlist = write("Game.m3u", "Disc 1.cue\nDisc 2.cue\n")

        val refusal = PlaylistPreflight.refusal(playlist.unix, fileOps())!!

        assertEquals(LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING, refusal.failure)
        assertEquals("Track file is missing: Disc 2 (Track 2).bin", refusal.detail)
    }

    @Test
    fun `an m3u of cue sheets with every track in place is not refused, and sheets resolve beside themselves`() {
        write("Discs/Disc 1.cue", "FILE \"Disc 1.bin\" BINARY\n")
        write("Discs/Disc 1.bin")
        val playlist = write("Game.m3u", "Discs/Disc 1.cue\n")
        assertNull(PlaylistPreflight.refusal(playlist.unix, fileOps()))

        // The track is beside the m3u, not beside the sheet in its subfolder: it is missing where the emulator looks.
        File(folder.root, "Discs/Disc 1.bin").delete()
        write("Disc 1.bin")
        assertEquals(
            "Track file is missing: Disc 1.bin",
            PlaylistPreflight.refusal(playlist.unix, fileOps())!!.detail,
        )
    }

    @Test
    fun `entries are checked in order - a missing disc and a missing track name whichever comes first`() {
        write("Disc 1.cue", "FILE \"Disc 1.bin\" BINARY\n")
        // Disc 2.cue is absent, and Disc 1's track is absent too: Disc 1 is first.
        val playlist = write("Game.m3u", "Disc 1.cue\nDisc 2.cue\n")
        assertEquals("Track file is missing: Disc 1.bin", PlaylistPreflight.refusal(playlist.unix, fileOps())!!.detail)

        write("Disc 1.bin")
        assertEquals("Disc 2 is missing: Disc 2.cue", PlaylistPreflight.refusal(playlist.unix, fileOps())!!.detail)
    }

    @Test
    fun `only one level is read - a playlist inside a playlist is not opened`() {
        write("Inner.m3u", "gone.chd\n")
        val playlist = write("Game.m3u", "Inner.m3u\n")
        assertNull(PlaylistPreflight.refusal(playlist.unix, fileOps()))
    }

    @Test
    fun `at most sixteen cue sheets are opened, so the check stays cheap`() {
        val reads = mutableListOf<String>()
        val ops = PlaylistFileOps(
            readText = { location, _ ->
                reads += location
                if (location.endsWith(".m3u")) {
                    (1..20).joinToString("\n") { "/d/Disc $it.cue" }
                } else if (location.endsWith("Disc 17.cue")) {
                    "FILE \"gone.bin\" BINARY\n"
                } else {
                    "FILE \"here.bin\" BINARY\n"
                }
            },
            definitelyMissing = { it.endsWith("gone.bin") },
        )

        assertNull(PlaylistPreflight.refusal("/d/Game.m3u", ops))
        assertEquals(1 + PlaylistPreflight.MAX_NESTED_CUES, reads.size)
    }

    @Test
    fun `a Windows drive path in a sheet is skipped, never reported missing`() {
        val ops = PlaylistFileOps(
            readText = { _, _ ->
                "FILE \"C:\\Games\\Game (Track 1).bin\" BINARY\nFILE \"d:/Games/Game (Track 2).bin\" BINARY\nFILE \"Game.bin\" BINARY\n"
            },
            definitelyMissing = { true },
        )
        // The two drive paths are skipped; the relative one is the first thing that can be checked.
        assertEquals("Track file is missing: Game.bin", PlaylistPreflight.refusal("/d/Game.cue", ops)!!.detail)

        val onlyDrive = PlaylistFileOps(
            readText = { _, _ -> "FILE \"C:\\Games\\Game.bin\" BINARY\n" },
            definitelyMissing = { true },
        )
        assertNull(PlaylistPreflight.refusal("/d/Game.cue", onlyDrive))
    }

    @Test
    fun `a Windows drive path in an m3u is skipped too, and the discs after it keep their numbers`() {
        val ops = PlaylistFileOps(
            readText = { _, _ -> "D:\\Discs\\Disc 1.chd\nDisc 2.chd\n" },
            definitelyMissing = { true },
        )
        assertEquals("Disc 2 is missing: Disc 2.chd", PlaylistPreflight.refusal("/d/Game.m3u", ops)!!.detail)
        assertNull(PlaylistPreflight.memberLocation("/d/Game.m3u", "C:\\Games\\a.bin"))
        assertNull(PlaylistPreflight.memberLocation("/d/Game.m3u", "D:/a.bin"))
        assertEquals("/d/c.bin", PlaylistPreflight.memberLocation("/d/Game.m3u", "c.bin"))
    }

    @Test
    fun `a playlist that cannot be read, or a file that is not a playlist, is let through`() {
        assertNull(PlaylistPreflight.refusal("${folder.root.unix}/Nope.m3u", fileOps()))
        assertNull(PlaylistPreflight.refusal("${folder.root.unix}/Game.chd", fileOps()))
        assertNull(
            "a member the platform will not confirm is gone is not a missing member",
            PlaylistPreflight.refusal(
                write("Locked.m3u", "Disc 1.chd\n").unix,
                PlaylistFileOps(readText = { l, m -> File(l).inputStream().use { PlaylistPreflight.readBoundedText(it, m) } },
                    definitelyMissing = { false }),
            ),
        )
    }

    @Test
    fun `the check honours the scan's 128 entry cap and 64 KiB read`() {
        assertEquals(M3uPlaylistResolver.MAX_PLAYLIST_BYTES, PlaylistPreflight.MAX_PLAYLIST_BYTES)
        val body = (1..300).joinToString("\n") { "d$it.chd" }
        assertEquals(
            "PlaylistPreflight.MAX_ENTRIES must equal the scan's cap",
            PlaylistPreflight.MAX_ENTRIES,
            M3uPlaylistResolver.parseEntries(body).size,
        )
        val checked = mutableListOf<String>()
        val playlist = write("Many.m3u", body)
        PlaylistPreflight.refusal(
            playlist.unix,
            PlaylistFileOps(
                readText = { l, m -> File(l).inputStream().use { PlaylistPreflight.readBoundedText(it, m) } },
                definitelyMissing = { checked += it; false },
            ),
        )
        assertEquals(PlaylistPreflight.MAX_ENTRIES, checked.size)
    }

    @Test
    fun `a playlist over the byte cap is cut on a whole line so a half name is never a missing file`() {
        val line = "Disc.chd\n"
        val text = line.repeat(10)
        val cut = PlaylistPreflight.readBoundedText(ByteArrayInputStream(text.toByteArray()), 25L)
        // 25 bytes holds two whole lines and a third cut at "Disc.ch"; only whole lines survive.
        assertEquals("Disc.chd\nDisc.chd", cut)
        assertEquals(text, PlaylistPreflight.readBoundedText(ByteArrayInputStream(text.toByteArray()), 1000L))
    }

    @Test
    fun `a long file name is shortened for the card`() {
        val name = "A".repeat(300) + ".chd"
        val playlist = write("Long.m3u", "$name\n")
        val shown = PlaylistPreflight.refusal(playlist.unix, fileOps())!!.detail.removePrefix("Disc 1 is missing: ")
        assertEquals(96, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    // ---- F4 over SAF documents ----------------------------------------------------------------------------------

    private val safPlaylist =
        "content://com.android.externalstorage.documents/tree/691E-7BEA%3AROMs/document/691E-7BEA%3AROMs%2Fps1%2FXenogears.m3u"

    @Test
    fun `a SAF playlist's members are the sibling documents in the same tree`() {
        assertEquals(
            "content://com.android.externalstorage.documents/tree/691E-7BEA%3AROMs/document/" +
                "691E-7BEA%3AROMs%2Fps1%2FDisc%202.chd",
            PlaylistPreflight.siblingLocation(safPlaylist, "Disc 2.chd"),
        )
        assertEquals(
            "a playlist in the volume root has its siblings in the root",
            "content://com.android.externalstorage.documents/tree/691E-7BEA%3A/document/691E-7BEA%3ADisc%202.chd",
            PlaylistPreflight.siblingLocation(
                "content://com.android.externalstorage.documents/tree/691E-7BEA%3A/document/691E-7BEA%3AGame.m3u",
                "Disc 2.chd",
            ),
        )
        assertNull(
            "another provider's document cannot be placed",
            PlaylistPreflight.siblingLocation("content://com.android.providers.downloads.documents/document/9", "x"),
        )
    }

    @Test
    fun `a SAF playlist reads through the document ops and names a missing sibling`() {
        val present = PlaylistPreflight.siblingLocation(safPlaylist, "Disc 1.chd")
        val ops = PlaylistFileOps(
            readText = { location, _ -> "Disc 1.chd\nDisc 2.chd\n".takeIf { location == safPlaylist } },
            definitelyMissing = { it != present },
        )
        assertEquals("Disc 2 is missing: Disc 2.chd", PlaylistPreflight.refusal(safPlaylist, ops)!!.detail)
    }

    @Test
    fun `a content member is asked about as it is, and an unplaceable one is skipped`() {
        val asked = mutableListOf<String>()
        val ops = PlaylistFileOps(
            readText = { _, _ -> "content://other.provider/doc/1\nfile:///storage/emulated/0/x/Disc 2.chd\n" },
            definitelyMissing = { asked += it; false },
        )
        PlaylistPreflight.refusal("/storage/emulated/0/Games/Game.m3u", ops)
        assertEquals(listOf("content://other.provider/doc/1", "/storage/emulated/0/x/Disc 2.chd"), asked)

        // A relative member beside a playlist under another provider has no place to be looked for.
        assertNull(PlaylistPreflight.memberLocation("content://com.android.providers.downloads.documents/document/9", "Disc 2.chd"))
    }

    // ---- the entry point ----------------------------------------------------------------------------------------------

    private fun target(pkg: String, locator: String, form: LaunchLocatorForm = LaunchLocatorForm.FILE_PATH) = LaunchTarget(
        gameId = GameId("game:test"),
        platformId = PlatformId.PS1,
        emulator = EmulatorProfile(
            id = EmulatorId(pkg),
            packageName = pkg,
            displayName = "Emulator",
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = form,
        ),
        locator = locator,
        locatorForm = form,
        title = "Game",
    )

    @Test
    fun `the entry point asks the version only for melonDS and runs the playlist rule for everyone`() {
        val asked = mutableListOf<String>()
        val version: (String) -> Long? = { asked += it; 33L }
        val none = PlaylistFileOps(readText = { _, _ -> null }, definitelyMissing = { false })

        assertNotNull(LaunchPreflight.refusalFor(target(MelonDsRomFolderRule.PACKAGE_NAME, rawPath, LaunchLocatorForm.CONTENT_URI), version, none))
        assertNull(LaunchPreflight.refusalFor(target("com.github.stenzek.duckstation", rawPath), version, none))
        assertEquals(listOf(MelonDsRomFolderRule.PACKAGE_NAME), asked)

        write("Disc 1.chd")
        val playlist = write("Game.m3u", "Disc 1.chd\nDisc 2.chd\n")
        val refusal = LaunchPreflight.refusalFor(target("com.github.stenzek.duckstation", playlist.unix), version, fileOps())
        assertEquals("Disc 2 is missing: Disc 2.chd", refusal!!.detail)
    }

    @Test
    fun `an open-only launch has no locator to check`() {
        val none = PlaylistFileOps(readText = { _, _ -> error("must not read") }, definitelyMissing = { error("must not stat") })
        assertNull(LaunchPreflight.refusalFor(target("x.y", "", LaunchLocatorForm.NONE), { null }, none))
        assertFalse(PlaylistPreflight.MAX_ENTRIES <= 0)
    }
}
