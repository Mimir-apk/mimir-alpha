package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier
import com.gruning.mimir.rewrite.core.resolver.PlatformResolution
import com.gruning.mimir.rewrite.core.resolver.ResolutionInput
import com.gruning.mimir.rewrite.feature.files.FileBrowserRecognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [fileBrowserPlatformId] -- the adapter that finally threads the resolver's real classifier into
 * the file browser, replacing `FileBrowserController`'s `classifyPlatform = { null }` default.
 *
 * Both resolver entry points are injected as fakes, deliberately: what needs pinning is WHICH evidence
 * each row kind is classified from (its own name vs. its parent folder), not whether the registry knows
 * that `.gba` is a Game Boy Advance file -- `core-resolver`'s own `ExtensionPlatformClassifierTest`
 * already owns that, and duplicating it here would just make the registry's coverage look bigger than it
 * is. The one test at the bottom that DOES touch the real classifier is marked as such.
 */
class FileBrowserPlatformClassifierTest {

    private fun directory(path: String) =
        FileItem(name = path.substringAfterLast('/'), path = path, isDirectory = true)

    private fun file(path: String) = FileItem(name = path.substringAfterLast('/'), path = path)

    /** Records what the folder index was asked about, so a test can assert on the QUESTION. */
    private class RecordingFolderHint(private val answers: Map<String, String>) : (String) -> PlatformId? {
        val asked = mutableListOf<String>()
        override fun invoke(folder: String): PlatformId? {
            asked += folder
            // Mirrors the real platformHintFromFolder: walk segments in reverse, first hit wins.
            return folder.split('/').filter { it.isNotBlank() }.asReversed()
                .firstNotNullOfOrNull { answers[it] }
                ?.let(::PlatformId)
        }
    }

    private val neverClassifiesFiles: (ResolutionInput, PlatformId?) -> PlatformId? = { _, _ -> null }

    // ── directories ───────────────────────────────────────────────────────────

    @Test
    fun `a directory is classified by its own name`() {
        val hint = RecordingFolderHint(mapOf("gba" to "gba"))

        val id = fileBrowserPlatformId(directory("/Roms/gba"), hint, neverClassifiesFiles)

        assertEquals("gba", id)
        assertEquals(listOf("gba"), hint.asked)
    }

    /**
     * The bug this adapter is shaped to avoid. `platformHintFromFolder` walks segments in REVERSE and
     * returns the first that resolves, so handing it a full path would make `/Roms/gba/Europe` inherit
     * GBA from its parent and draw a platform glyph on a folder that is not a platform.
     */
    @Test
    fun `a subfolder of a platform folder does not inherit that platform`() {
        val hint = RecordingFolderHint(mapOf("gba" to "gba"))

        val id = fileBrowserPlatformId(directory("/Roms/gba/Europe"), hint, neverClassifiesFiles)

        assertNull("a tome wears a platform's mark only when its OWN name claims one", id)
        assertEquals(
            "the directory's own name is the only thing the folder index may be asked about",
            listOf("Europe"),
            hint.asked,
        )
    }

    @Test
    fun `an unrecognised directory name yields no platform`() {
        val hint = RecordingFolderHint(emptyMap())

        assertNull(fileBrowserPlatformId(directory("/Roms/Screenshots"), hint, neverClassifiesFiles))
    }

    // ── files ─────────────────────────────────────────────────────────────────

    @Test
    fun `a file is classified from its name and extension, with its PARENT folder as the hint`() {
        val hint = RecordingFolderHint(mapOf("psx" to "psx"))
        var seenInput: ResolutionInput? = null
        var seenHint: PlatformId? = null

        val id = fileBrowserPlatformId(
            item = file("/Roms/psx/Final Fantasy VII.bin"),
            folderHint = hint,
            classifyFile = { input, folder ->
                seenInput = input
                seenHint = folder
                PlatformId("psx")
            },
        )

        assertEquals("psx", id)
        assertEquals("Final Fantasy VII.bin", seenInput?.fileName)
        assertEquals("bin", seenInput?.extension)
        assertEquals(
            "the parent folder is the context that unlocks ambiguous containers like .bin/.iso/.cso",
            PlatformId("psx"),
            seenHint,
        )
        assertEquals(listOf("/Roms/psx"), hint.asked)
    }

    @Test
    fun `a file extension is lowercased before the resolver sees it`() {
        var seenExtension: String? = null

        fileBrowserPlatformId(
            item = file("/Roms/gba/ZELDA.GBA"),
            folderHint = RecordingFolderHint(emptyMap()),
            classifyFile = { input, _ -> seenExtension = input.extension; null },
        )

        assertEquals("gba", seenExtension)
    }

    @Test
    fun `a file at the filesystem root asks the folder index about nothing rather than crashing`() {
        val hint = RecordingFolderHint(emptyMap())

        val id = fileBrowserPlatformId(file("loose.gba"), hint, neverClassifiesFiles)

        assertNull(id)
        assertEquals(listOf(""), hint.asked)
    }

    @Test
    fun `an unresolvable file yields no platform rather than a guess`() {
        assertNull(
            fileBrowserPlatformId(
                item = file("/Roms/mixed/disc.iso"),
                folderHint = RecordingFolderHint(emptyMap()),
                classifyFile = neverClassifiesFiles,
            ),
        )
    }

    @Test
    fun `a supported archive wrapper exposes a unique inner ROM extension`() {
        val classifier = ExtensionPlatformClassifier()

        val id = fileBrowserPlatformId(
            item = file("/Roms/flat/Game.nds.zip"),
            folderHint = RecordingFolderHint(emptyMap()),
            classifyFile = { _, _ -> PlatformId("outer-fallback") },
            resolveFile = classifier::resolveWithContext,
        )

        // The registry's legacy shelfmark id for the unique `.nds` extension is `ds`.
        assertEquals("ds", id)
    }

    @Test
    fun `packaged detection is deterministic for uppercase non English title names`() {
        assertEquals(
            "ds",
            fileBrowserPlatformId(
                item = file("/Roms/flat/Želda.NDS.ZIP"),
                folderHint = RecordingFolderHint(emptyMap()),
                classifyFile = { _, _ -> null },
                resolveFile = ExtensionPlatformClassifier()::resolveWithContext,
            ),
        )
    }

    @Test
    fun `an ambiguous inner ISO remains unknown without a parent context`() {
        val classifier = ExtensionPlatformClassifier()

        val id = fileBrowserPlatformId(
            item = file("/Roms/flat/Game.iso.7z"),
            folderHint = RecordingFolderHint(emptyMap()),
            classifyFile = { _, _ -> PlatformId("outer-fallback") },
            resolveFile = classifier::resolveWithContext,
        )

        assertNull(id)
    }

    @Test
    fun `an inner ISO uses a compatible parent context`() {
        val classifier = ExtensionPlatformClassifier()

        val id = fileBrowserPlatformId(
            item = file("/Roms/ps2/Game.iso.7z"),
            folderHint = RecordingFolderHint(mapOf("ps2" to "ps2")),
            classifyFile = { _, _ -> PlatformId("outer-fallback") },
            resolveFile = classifier::resolveWithContext,
        )

        assertEquals("ps2", id)
    }

    @Test
    fun `a meaningful inner conflict never falls back to the outer archive`() {
        val id = fileBrowserPlatformId(
            item = file("/Roms/ps2/Game.iso.zip"),
            folderHint = RecordingFolderHint(mapOf("ps2" to "ps2")),
            classifyFile = { _, _ -> PlatformId("outer-fallback") },
            resolveFile = { input, _ ->
                if (input.extension == "iso") {
                    PlatformResolution.FolderExtensionConflict
                } else {
                    PlatformResolution.Resolved(PlatformId("outer-fallback"))
                }
            },
        )

        assertNull(id)
    }

    @Test
    fun `plain archives and unsupported outer suffixes retain direct file behavior`() {
        val classify: (ResolutionInput, PlatformId?) -> PlatformId? = { input, hint ->
            PlatformId("${input.extension}:${hint?.value ?: "none"}")
        }
        val hint = RecordingFolderHint(mapOf("ps2" to "ps2"))

        assertEquals(
            "zip:ps2",
            fileBrowserPlatformId(file("/Roms/ps2/Game.zip"), hint, classify),
        )
        assertEquals(
            "rar:ps2",
            fileBrowserPlatformId(file("/Roms/ps2/Game.nds.rar"), hint, classify),
        )
    }

    @Test
    fun `missing stems malformed names and excessive wrappers fail closed`() {
        val resolver: (ResolutionInput, PlatformId?) -> PlatformResolution = { _, _ ->
            PlatformResolution.Resolved(PlatformId("would-be-guess"))
        }

        listOf(
            ".nds.zip",
            "Game.nds..zip",
            "Game.nds.zip.7z.zip.7z",
        ).forEach { name ->
            assertNull(
                name,
                fileBrowserPlatformId(
                    item = file("/Roms/flat/$name"),
                    folderHint = RecordingFolderHint(emptyMap()),
                    classifyFile = { _, _ -> PlatformId("outer-fallback") },
                    resolveFile = resolver,
                ),
            )
        }
    }

    @Test
    fun `dotted and hidden title stems remain eligible for packaged detection`() {
        val classifier = ExtensionPlatformClassifier()

        listOf("Dr...Mario.nds.zip", ".Game.nds.zip").forEach { name ->
            assertEquals(
                "ds",
                fileBrowserPlatformId(
                    item = file("/Roms/flat/$name"),
                    folderHint = RecordingFolderHint(emptyMap()),
                    classifyFile = { _, _ -> null },
                    resolveFile = classifier::resolveWithContext,
                ),
            )
        }
    }

    @Test
    fun `parent hint cache reuses one negative lookup across ten thousand siblings`() {
        var uncachedLookups = 0
        repeat(10_000) { index ->
            assertNull(
                fileBrowserPlatformId(
                    item = file("/Roms/mixed/Uncached$index.txt"),
                    folderHint = { uncachedLookups++; null },
                    classifyFile = neverClassifiesFiles,
                ),
            )
        }
        assertEquals(10_000, uncachedLookups)

        var lookups = 0
        val cache = ParentFolderHintCache(
            lookup = { lookups++; null },
        )
        val hint = RecordingFolderHint(emptyMap())

        repeat(10_000) { index ->
            assertNull(
                fileBrowserPlatformId(
                    item = file("/Roms/mixed/Game$index.txt"),
                    folderHint = hint,
                    classifyFile = neverClassifiesFiles,
                    parentHintCache = cache,
                ),
            )
        }

        assertEquals(1, lookups)
        assertEquals(0, hint.asked.size)
        assertEquals(1, cache.size)
    }

    @Test
    fun `parent hint cache includes negatives and evicts the least recently used entry`() {
        val lookups = mutableListOf<String>()
        val cache = ParentFolderHintCache(
            lookup = { folder -> lookups += folder; null },
            maxEntries = 2,
        )

        assertNull(cache.get("/one"))
        assertNull(cache.get("/two"))
        assertNull(cache.get("/one")) // cached negative; refreshes its LRU position
        assertNull(cache.get("/three"))
        assertNull(cache.get("/two")) // evicted, so this is a fresh lookup

        assertEquals(listOf("/one", "/two", "/three", "/two"), lookups)
        assertEquals(2, cache.size)
    }

    // ── the real classifier ───────────────────────────────────────────────────

    /**
     * The ONE test here that exercises the live [FileBrowserPlatformClassifier], registry load included.
     * It proves the wiring is real -- that the browser's lambda reaches a classifier that actually
     * resolves something -- rather than re-testing the registry's contents.
     *
     * Asserted as "not null and in the shelfmark vocabulary" rather than against a specific id, so a
     * legitimate registry re-canonicalisation does not fail this file; `core-resolver` owns the exact
     * id assertions.
     */
    @Test
    fun `the production lambda resolves a real ROM through the real registry`() {
        val recognition = FileBrowserPlatformClassifier.forBrowser(file("/storage/emulated/0/Roms/gba/zelda.gba"))

        assertEquals(
            "a .gba file must resolve through the live registry, or classifyPlatform is still a no-op",
            "gba",
            recognition.platformIdValue,
        )
        assertFalse("a settled platform is not an unsettled game", recognition.gameLike)
    }

    /**
     * Through the live registry: an `.iso` in a folder that names no platform is
     * game content the registry cannot place, and the browser must say so rather than treat it as a stranger.
     */
    @Test
    fun `the production lambda calls an iso with no platform folder a game, not a stranger`() {
        val recognition = FileBrowserPlatformClassifier.forBrowser(file("/storage/emulated/0/Download/mystery.iso"))

        assertNull(recognition.platformIdValue)
        assertTrue("several platforms claim .iso, so it is an unsettled game", recognition.gameLike)
    }

    // ── recognition: the answers beyond the platform id ───────────────────────

    private fun resolverAnswering(resolution: PlatformResolution): (ResolutionInput, PlatformId?) -> PlatformResolution =
        { _, _ -> resolution }

    @Test
    fun `the three unsettled resolutions are a game with no platform, and nothing else is`() {
        mapOf(
            PlatformResolution.Ambiguous to true,
            PlatformResolution.FolderExtensionConflict to true,
            PlatformResolution.ContextRequired to true,
            PlatformResolution.NoCandidates to false,
        ).forEach { (resolution, gameLike) ->
            val recognition = fileBrowserRecognition(
                item = file("/Downloads/thing.bin"),
                folderHint = RecordingFolderHint(emptyMap()),
                classifyFile = neverClassifiesFiles,
                resolveFile = resolverAnswering(resolution),
            )
            assertNull(recognition.platformIdValue)
            assertEquals("$resolution", gameLike, recognition.gameLike)
        }
    }

    @Test
    fun `a settled platform is not also an unsettled game`() {
        val recognition = fileBrowserRecognition(
            item = file("/Roms/gba/zelda.gba"),
            folderHint = RecordingFolderHint(mapOf("gba" to "gba")),
            classifyFile = neverClassifiesFiles,
            resolveFile = resolverAnswering(PlatformResolution.Resolved(PlatformId("gba"))),
        )

        assertEquals("gba", recognition.platformIdValue)
        assertFalse(recognition.gameLike)
    }

    @Test
    fun `a directory carries a platform and nothing else -- never game-like, never BIOS`() {
        val recognition = fileBrowserRecognition(
            item = directory("/Roms/gba"),
            folderHint = RecordingFolderHint(mapOf("gba" to "gba")),
            classifyFile = neverClassifiesFiles,
            isBios = { true },
        )

        assertEquals(FileBrowserRecognition(platformIdValue = "gba"), recognition)
    }

    @Test
    fun `BIOS is the injected answer, reported whether or not a platform also resolved`() {
        val recognition = fileBrowserRecognition(
            item = file("/Roms/gba/gba_bios.bin"),
            folderHint = RecordingFolderHint(mapOf("gba" to "gba")),
            classifyFile = neverClassifiesFiles,
            resolveFile = resolverAnswering(PlatformResolution.Resolved(PlatformId("gba"))),
            isBios = { it.name == "gba_bios.bin" },
        )

        assertTrue(recognition.bios)
        assertEquals("gba", recognition.platformIdValue)
        assertFalse(
            "the default asks nothing of the BIOS rule",
            fileBrowserRecognition(
                item = file("/Roms/gba/gba_bios.bin"),
                folderHint = RecordingFolderHint(emptyMap()),
                classifyFile = neverClassifiesFiles,
            ).bios,
        )
    }

    /** The resolver's own rule, reached through the browser's adapter: name and folder, the real thing. */
    @Test
    fun `the real BIOS rule names a BIOS by its name or by its folder, and a game by neither`() {
        fun bios(path: String) = FileBrowserRomFolders.isBiosPayload(file(path))

        assertTrue(bios("/Roms/psx/scph1001.bin"))
        assertTrue(bios("/Roms/gba/gba_bios.bin"))
        assertTrue("a file in a bios folder is a BIOS payload", bios("/Roms/bios/anything.dat"))
        assertFalse(bios("/Roms/gba/Metroid Fusion.gba"))
        assertFalse(bios("/Roms/psx/Final Fantasy VII.bin"))
    }
}
