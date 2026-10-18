package com.gruning.mimir.rewrite.core.resolver.library

import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtSourceTest {
    @Test
    fun `physical support art resolves independently from cover and logo`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/media_art/Metroid Fusion-support.png"] =
                "file:///games/media_art/Metroid%20Fusion-support.png"
        }
        val source = LocalArtSource(access)

        assertEquals(
            "file:///games/media_art/Metroid%20Fusion-support.png",
            source.physicalMediaUriFor(null, "/games/Metroid Fusion.gba"),
        )
        assertNull(source.artUriFor(null, "/games/Metroid Fusion.gba"))
        assertNull(source.logoUriFor(null, "/games/Metroid Fusion.gba"))
    }

    @Test
    fun `hero art resolves from its own title screen sibling`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/media_art/Metroid Fusion-hero.webp"] =
                "file:///games/media_art/Metroid%20Fusion-hero.webp"
        }
        val source = LocalArtSource(access)

        assertEquals(
            "file:///games/media_art/Metroid%20Fusion-hero.webp",
            source.heroUriFor(null, "/games/Metroid Fusion.gba"),
        )
        assertNull(source.artUriFor(null, "/games/Metroid Fusion.gba"))
        assertNull(source.logoUriFor(null, "/games/Metroid Fusion.gba"))
    }

    /**
     * The probe ORDER changed on 2026-09-17 and this test carried the old one -- `media_art` is now tried
     * before the sibling. See [LocalArtSource.artBases] for the policy and the measurement behind
     * it. The cover here still resolves to the SIBLING, which is the point worth keeping: the ordering is a
     * PREFERENCE, not an exclusion, and a game whose only art is beside the ROM must still find it.
     */
    @Test
    fun `file cover and clear logo use distinct candidates and memoization`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/Chrono Cross.jpg"] = "file:///games/Chrono%20Cross.jpg"
            fileUris["/games/media_art/Chrono Cross-logo.webp"] =
                "file:///games/media_art/Chrono%20Cross-logo.webp"
        }
        val source = LocalArtSource(access)
        val romPath = "/games/Chrono Cross.chd"

        assertEquals(
            "no managed cover exists, so the sibling is still the answer",
            "file:///games/Chrono%20Cross.jpg",
            source.artUriFor(null, romPath),
        )
        assertEquals(
            "file:///games/media_art/Chrono%20Cross-logo.webp",
            source.logoUriFor(null, romPath),
        )
        val probesAfterFirstResolution = access.fileProbes.toList()

        assertEquals("file:///games/Chrono%20Cross.jpg", source.artUriFor(null, romPath))
        assertEquals(
            "file:///games/media_art/Chrono%20Cross-logo.webp",
            source.logoUriFor(null, romPath),
        )
        assertEquals(probesAfterFirstResolution, access.fileProbes)
        assertEquals(
            listOf(
                // Managed directory first, all four extensions, before the ROM's own folder is touched.
                "/games/media_art/Chrono Cross.png",
                "/games/media_art/Chrono Cross.jpg",
                "/games/media_art/Chrono Cross.jpeg",
                "/games/media_art/Chrono Cross.webp",
                "/games/Chrono Cross.png",
                "/games/Chrono Cross.jpg",
                // The logo IS managed, so the sibling candidates are never probed at all.
                "/games/media_art/Chrono Cross-logo.png",
                "/games/media_art/Chrono Cross-logo.jpg",
                "/games/media_art/Chrono Cross-logo.jpeg",
                "/games/media_art/Chrono Cross-logo.webp",
            ),
            access.fileProbes,
        )
    }

    /**
     * **Managed-art preference with both sources present.**
     *
     * Both files exist. Until 2026-09-17 the sibling won, and on a Thor that meant every one of
     * the games showed a 400x352 image the emulator\'s art pack had left beside the ROM on 2026-07-08,
     * while the 554x705 box-3D Mimir had just scraped sat unread in `media_art/`. The managed 3D boxes were not shown. The boxes were there; nothing ever looked at them.
     *
     * `media_art` is the only directory Mimir writes art into, so a sibling is by construction foreign --
     * and between two AUTOMATIC sources the managed one wins. A cover the player chose by hand is not in
     * this contest at all: it rides `GameRow.wiredFace` and outranks both.
     */
    @Test
    fun `a managed cover outranks a foreign one left beside the ROM`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/Bravely Default.png"] = "file:///games/Bravely%20Default.png"
            fileUris["/games/media_art/Bravely Default.png"] =
                "file:///games/media_art/Bravely%20Default.png"
        }
        val source = LocalArtSource(access)

        assertEquals(
            "file:///games/media_art/Bravely%20Default.png",
            source.artUriFor(null, "/games/Bravely Default.3ds"),
        )
    }

    // ── clips: the one slot where a foreign file is not a fallback ──────────────────────────

    /**
     * **The regression pin for hero motion.** Manual QA exercised a build that had downloaded ZERO clips --
     * the bulk fetch is retired -- and still got a moving backdrop on the reference shelf. It was one of 86 `.mp4`
     * files dated 2026-07-09 that an emulator art pack had left beside the ROMs, adopted automatically
     * because `videoUriFor` probed siblings exactly like every other slot.
     *
     * Hero motion is an explicit Customize media choice. The automatic
     * tier is not demoted for video the way it was for covers -- it is dropped. A foreign cover is a
     * fallback worth having; a foreign clip is a backdrop that starts moving on a screen someone is
     * reading, which is not a fallback for anything.
     */
    @Test
    fun `a clip left beside the ROM by another frontend is never adopted`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/Bravely Default.mp4"] = "file:///games/Bravely%20Default.mp4"
        }
        val source = LocalArtSource(access)

        assertNull(
            "motion is opt-in; a clip nobody chose must not play",
            source.videoUriFor(null, "/games/Bravely Default.3ds"),
        )
    }

    /**
     * **The managed tier is dropped too**. A Nova carrying a previous install's files had `.mp4`s inside `media_art` and played
     * them, because being in that folder was treated as proof Mimir had put them there. It is not: any
     * other frontend, an older build or a restored card populates the same path. Nothing in this build
     * produces a managed clip either — the library-wide download was retired and the one function that
     * would have written one had no callers.
     *
     * A chosen clip is unaffected and never came through here; Customize Media reads `CustomHeroStore`.
     */
    @Test
    fun `a managed clip in media_art is no longer adopted either`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/media_art/Bravely Default.mp4"] =
                "file:///games/media_art/Bravely%20Default.mp4"
        }
        val source = LocalArtSource(access)

        assertNull(
            "the folder is not proof this install wrote the file",
            source.videoUriFor(null, "/games/Bravely Default.3ds"),
        )
    }

    /** No probe at all, for any input: the cheapest proof that the tier is gone rather than narrowed. */
    @Test
    fun `no clip lookup touches the filesystem any more`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/media_art/Bravely Default.mp4"] =
                "file:///games/media_art/Bravely%20Default.mp4"
            fileUris["/games/media_art/Bravely Default.webm"] =
                "file:///games/media_art/Bravely%20Default.webm"
        }
        val source = LocalArtSource(access)

        assertNull(source.videoUriFor(null, "/games/Bravely Default.3ds"))
        assertTrue(
            "a dropped tier must not enumerate anything",
            access.documentProbes.isEmpty(),
        )
    }

    /**
     * The asymmetry, in one fixture: identical foreign files one extension apart, opposite answers. This
     * is the shape most likely to be "tidied" into consistency by someone who has not read the media-source policy.
     */
    @Test
    fun `a foreign cover is a fallback and a foreign clip is not`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/Bravely Default.png"] = "file:///games/Bravely%20Default.png"
            fileUris["/games/Bravely Default.mp4"] = "file:///games/Bravely%20Default.mp4"
        }
        val source = LocalArtSource(access)
        val rom = "/games/Bravely Default.3ds"

        assertEquals(
            "a cover beside the ROM still beats no cover at all",
            "file:///games/Bravely%20Default.png",
            source.artUriFor(null, rom),
        )
        assertNull("a clip beside the ROM does not play", source.videoUriFor(null, rom))
    }

    /** The SAF branch spells the probe order out by hand, so it needs its own proof. */
    @Test
    fun `the SAF branch does not adopt a sibling clip either`() {
        val romUri = "content://provider/tree/primary/document/primary%3AGames%2FBravely.3ds"
        val parentUri = "content://provider/tree/primary/document/opaque-parent-77"
        val access = FakeLocalArtAccess().apply {
            displayNames[romUri] = "Bravely.3ds"
            parents[romUri] = parentUri
            documentUris[ChildProbe(parentUri, "Bravely.mp4", null)] =
                "content://provider/tree/primary/document/opaque-clip-01"
        }
        val source = LocalArtSource(access)

        assertNull(source.videoUriFor(romUri, null))
        assertTrue(
            "the sibling must not even be probed",
            access.documentProbes.none { it.displayName == "Bravely.mp4" },
        )
    }

    /** A bare name has no folder to hold a `media_art`, so a managed-only search has nowhere to look. */
    @Test
    fun `a ROM with no directory yields no clip rather than falling back to the bare name`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["Bravely Default.mp4"] = "file:///Bravely%20Default.mp4"
        }
        val source = LocalArtSource(access)

        assertNull(source.videoUriFor(null, "Bravely Default.3ds"))
    }

    // ── the launch sound: the one media_art slot trusted by name  ─────────

    /**
     * A wipe erases install-local provenance records, so the launch sound is trusted
     * the way the card's images are: a `-audio.m4a` in `media_art` is the game's sound. The clip tier stays closed, and
     * the pins above are untouched by this.
     */
    @Test
    fun `a managed audio file in media_art is found and never searched beside the ROM`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/media_art/Bravely Default-audio.m4a"] =
                "file:///games/media_art/Bravely%20Default-audio.m4a"
            fileUris["/games/Bravely Default-audio.m4a"] = "file:///games/Bravely%20Default-audio.m4a"
        }
        val source = LocalArtSource(access)

        assertEquals(
            "file:///games/media_art/Bravely%20Default-audio.m4a",
            source.audioUriFor(null, "/games/Bravely Default.3ds"),
        )
        assertEquals(
            "exactly one probe: the managed directory, one extension, nothing beside the ROM",
            listOf("/games/media_art/Bravely Default-audio.m4a"),
            access.fileProbes,
        )
    }

    @Test
    fun `an audio file beside the ROM or of another type is never adopted`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/Bravely Default-audio.m4a"] = "file:///games/Bravely%20Default-audio.m4a"
            fileUris["/games/media_art/Bravely Default-audio.mp3"] =
                "file:///games/media_art/Bravely%20Default-audio.mp3"
            fileUris["/games/media_art/Bravely Default-audio.mp4"] =
                "file:///games/media_art/Bravely%20Default-audio.mp4"
            fileUris["/games/media_art/Bravely Default.m4a"] = "file:///games/media_art/Bravely%20Default.m4a"
        }
        val source = LocalArtSource(access)

        assertNull(source.audioUriFor(null, "/games/Bravely Default.3ds"))
    }

    /**
     * **The backdrop cannot come back through the sound.** With a real `-audio.m4a` AND a real clip in `media_art`,
     * the video answer is still null for every input, and asking for it does not probe the filesystem at all.
     */
    @Test
    fun `a managed audio file does not reopen the video tier`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/media_art/Bravely Default-audio.m4a"] =
                "file:///games/media_art/Bravely%20Default-audio.m4a"
            fileUris["/games/media_art/Bravely Default.mp4"] = "file:///games/media_art/Bravely%20Default.mp4"
            fileUris["/games/media_art/Bravely Default-audio.mp4"] =
                "file:///games/media_art/Bravely%20Default-audio.mp4"
        }
        val source = LocalArtSource(access)
        val rom = "/games/Bravely Default.3ds"
        assertNotNull(source.audioUriFor(null, rom))
        access.fileProbes.clear()

        assertNull(source.videoUriFor(null, rom))
        assertNull(source.videoUriFor(null, "/games/media_art/Bravely Default-audio.m4a"))
        assertTrue("the video answer must not look at storage", access.fileProbes.isEmpty())
        assertTrue(access.documentProbes.isEmpty())
    }

    @Test
    fun `audio resolves independently of cover logo hero and support`() {
        val access = FakeLocalArtAccess().apply {
            fileUris["/games/media_art/Bravely Default-audio.m4a"] =
                "file:///games/media_art/Bravely%20Default-audio.m4a"
        }
        val source = LocalArtSource(access)
        val rom = "/games/Bravely Default.3ds"

        assertNotNull(source.audioUriFor(null, rom))
        assertNull(source.artUriFor(null, rom))
        assertNull(source.logoUriFor(null, rom))
        assertNull(source.heroUriFor(null, rom))
        assertNull(source.physicalMediaUriFor(null, rom))
    }

    @Test
    fun `the SAF branch finds audio in media_art and does not probe the ROM's own folder`() {
        val romUri = "content://provider/tree/primary/document/primary%3AGames%2FBravely.3ds"
        val parentUri = "content://provider/tree/primary/document/opaque-parent-77"
        val managedDirectoryUri = "content://provider/tree/primary/document/opaque-media-31"
        val soundUri = "content://provider/tree/primary/document/opaque-sound-05"
        val access = FakeLocalArtAccess().apply {
            displayNames[romUri] = "Bravely.3ds"
            parents[romUri] = parentUri
            documentUris[ChildProbe(parentUri, "media_art", DocumentsContract.Document.MIME_TYPE_DIR)] =
                managedDirectoryUri
            documentUris[ChildProbe(managedDirectoryUri, "Bravely-audio.m4a", null)] = soundUri
            documentUris[ChildProbe(parentUri, "Bravely-audio.m4a", null)] =
                "content://provider/tree/primary/document/opaque-stray-99"
        }
        val source = LocalArtSource(access)

        assertEquals(soundUri, source.audioUriFor(romUri, null))
        assertTrue(
            "a sibling in the ROM's folder must never even be probed for a sound",
            access.documentProbes.none { it.parentUri == parentUri && it.displayName == "Bravely-audio.m4a" },
        )
    }

    @Test
    fun `an audio miss is memoized and both invalidate paths make a new file discoverable`() {
        val access = FakeLocalArtAccess()
        val source = LocalArtSource(access)
        val rom = "/games/Bravely Default.3ds"
        val sound = "/games/media_art/Bravely Default-audio.m4a"

        assertNull(source.audioUriFor(null, rom))
        val probesAfterMiss = access.fileProbes.toList()
        access.fileUris[sound] = "file:///games/media_art/Bravely%20Default-audio.m4a"

        assertNull("the miss is memoized", source.audioUriFor(null, rom))
        assertEquals(probesAfterMiss, access.fileProbes)

        source.invalidate(null, rom)
        assertEquals("file:///games/media_art/Bravely%20Default-audio.m4a", source.audioUriFor(null, rom))

        // And the batch path: a hit is memoized too, so a removed file keeps answering until the whole memo is dropped.
        access.fileUris.remove(sound)
        assertNotNull(source.audioUriFor(null, rom))
        source.invalidateAll()
        assertNull(source.audioUriFor(null, rom))
    }

    /** Same contest, every other slot -- a cartridge, a logo and a Hero resolve by the same rule. */
    @Test
    fun `the managed directory wins for support, logo and hero too`() {
        val access = FakeLocalArtAccess().apply {
            listOf("-support", "-logo", "-hero").forEach { suffix ->
                fileUris["/games/Bravely Default$suffix.png"] =
                    "file:///games/Bravely%20Default$suffix.png"
                fileUris["/games/media_art/Bravely Default$suffix.png"] =
                    "file:///games/media_art/Bravely%20Default$suffix.png"
            }
        }
        val source = LocalArtSource(access)
        val rom = "/games/Bravely Default.3ds"

        assertEquals(
            "file:///games/media_art/Bravely%20Default-support.png",
            source.physicalMediaUriFor(null, rom),
        )
        assertEquals("file:///games/media_art/Bravely%20Default-logo.png", source.logoUriFor(null, rom))
        assertEquals("file:///games/media_art/Bravely%20Default-hero.png", source.heroUriFor(null, rom))
    }

    @Test
    fun `SAF clear logo caches misses until invalidated then resolves media art`() {
        val romUri = "content://provider/tree/primary/document/primary%3AGames%2FMetroid%20Fusion.gba"
        val parentUri = "content://provider/tree/primary/document/opaque-parent-42"
        val managedDirectoryUri = "content://provider/tree/primary/document/opaque-media-9f"
        val resolvedUri =
            "content://provider/tree/primary/document/opaque-logo-a7"
        val access = FakeLocalArtAccess().apply {
            displayNames[romUri] = "Metroid Fusion.gba"
            parents[romUri] = parentUri
        }
        val source = LocalArtSource(access)

        assertNull(source.logoUriFor(romUri, null))
        val probesAfterMiss = access.documentProbes.toList()
        access.documentUris[ChildProbe(parentUri, "media_art", DocumentsContract.Document.MIME_TYPE_DIR)] =
            managedDirectoryUri
        access.documentUris[ChildProbe(managedDirectoryUri, "Metroid Fusion-logo.jpeg", null)] = resolvedUri

        assertNull("the first miss is memoized", source.logoUriFor(romUri, null))
        assertEquals(probesAfterMiss, access.documentProbes)

        source.invalidate(romUri, null)

        assertEquals(resolvedUri, source.logoUriFor(romUri, null))
        assertEquals(listOf(romUri, romUri), access.displayNameProbes)
        assertEquals(listOf(romUri, romUri), access.parentProbes)
        assertEquals(
            listOf(
                ChildProbe(parentUri, "Metroid Fusion-logo.png", null),
                ChildProbe(parentUri, "Metroid Fusion-logo.jpg", null),
                ChildProbe(parentUri, "Metroid Fusion-logo.jpeg", null),
                ChildProbe(parentUri, "Metroid Fusion-logo.webp", null),
                ChildProbe(parentUri, "media_art", DocumentsContract.Document.MIME_TYPE_DIR),
                ChildProbe(managedDirectoryUri, "Metroid Fusion-logo.png", null),
                ChildProbe(managedDirectoryUri, "Metroid Fusion-logo.jpg", null),
                ChildProbe(managedDirectoryUri, "Metroid Fusion-logo.jpeg", null),
            ),
            access.documentProbes.takeLast(8),
        )
    }

    private data class ChildProbe(
        val parentUri: String,
        val displayName: String,
        val requiredMimeType: String?,
    )

    /**
     * the product may not claim an absence it has not established.
     *
     * A SAF directory that will not enumerate and a directory with no matching file both produce a null
     * URI. Only one of them is a FACT. Before this, both were memoized as "known-absent" for the life of
     * the process, so a single transient provider failure marked every game in that folder art-less until
     * the app was restarted — with the files sitting on disk the whole time, which is exactly the shape of
     * the observed Nova failure.
     */
    @Test
    fun anUnreadableFolderIsNotAnEstablishedAbsence() {
        val access = FakeLocalArtAccess().apply {
            displayNames["content://tree/rom"] = "Game.iso"
            parents["content://tree/rom"] = "content://tree/dir"
            unreadableParents += "content://tree/dir"
        }
        val source = LocalArtSource(access)

        assertNull(source.artUriFor("content://tree/rom", null))
        assertFalse(
            "a folder that would not enumerate must not report a settled answer",
            source.artResolutionSettled("content://tree/rom", null),
        )

        // AND the miss is not memoized: the folder becomes readable and the very next probe finds the art,
        // with no restart and no cache invalidation. That retry is the whole point.
        access.unreadableParents.clear()
        access.documentUris[ChildProbe("content://tree/dir", "Game.png", null)] = "content://tree/art"
        assertEquals("content://tree/art", source.artUriFor("content://tree/rom", null))
        assertTrue(source.artResolutionSettled("content://tree/rom", null))
    }

    /** A folder that WAS read and holds nothing is a real answer, and still memoized as one. */
    @Test
    fun areadableFolderWithNoMatchIsAnEstablishedAbsence() {
        val access = FakeLocalArtAccess().apply {
            displayNames["content://tree/rom"] = "Game.iso"
            parents["content://tree/rom"] = "content://tree/dir"
        }
        val source = LocalArtSource(access)

        assertNull(source.artUriFor("content://tree/rom", null))
        assertTrue(source.artResolutionSettled("content://tree/rom", null))
        val probesAfterFirst = access.documentProbes.size
        assertNull(source.artUriFor("content://tree/rom", null))
        assertEquals("a settled miss stays memoized", probesAfterFirst, access.documentProbes.size)
    }

    /** A plain-file source cannot be inconclusive — File.isFile has no third answer. */
    @Test
    fun aPlainFileSourceIsAlwaysSettled() {
        assertTrue(LocalArtSource(FakeLocalArtAccess()).artResolutionSettled(null, "/roms/Game.iso"))
    }

    private class FakeLocalArtAccess : LocalArtAccess {
        /** Parents whose listing could not be read at all — distinct from a parent with no matches. */
        val unreadableParents = mutableSetOf<String>()
        val fileUris = mutableMapOf<String, String>()
        val displayNames = mutableMapOf<String, String>()
        val parents = mutableMapOf<String, String>()
        val documentUris = mutableMapOf<ChildProbe, String>()
        val fileProbes = mutableListOf<String>()
        val displayNameProbes = mutableListOf<String>()
        val parentProbes = mutableListOf<String>()
        val documentProbes = mutableListOf<ChildProbe>()

        override fun enumerationSettled(parentDocumentUri: String): Boolean =
            parentDocumentUri !in unreadableParents

        override fun existingFileUri(path: String): String? {
            fileProbes += path
            return fileUris[path]
        }

        override fun documentDisplayName(documentUri: String): String? {
            displayNameProbes += documentUri
            return displayNames[documentUri]
        }

        override fun parentDocumentUri(documentUri: String): String? {
            parentProbes += documentUri
            return parents[documentUri]
        }

        override fun existingChildUri(
            parentDocumentUri: String,
            displayName: String,
            requiredMimeType: String?,
        ): String? {
            val key = ChildProbe(parentDocumentUri, displayName, requiredMimeType)
            documentProbes += key
            return documentUris[key]
        }
    }
}
