package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameGroup
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.model.ResidentLocationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the dedup/grouping stage . Files are pushed
 * through the same classify → identity → group pipeline the repository scan uses, then the resulting
 * [GameGroup]s are asserted for count, primary selection, and member folding.
 *
 * Grouping rules exercised:
 *  - Multi-disc (Disc 1/2/3 + m3u) → ONE group, m3u is primary, discs fold in.
 *  - Region variants (USA + Europe) with NO catalog key → do NOT auto-collapse (filename alone never merges).
 *  - Byte-identical dumps (same hash) → one group regardless of storage.
 *  - Cross-storage same-hash → one group, SD copy wins as primary.
 *  - Two genuinely different games → two groups (no false collapse).
 */
class GroupingDedupTest {

    private val classifier = ExtensionPlatformClassifier()
    private val identityBuilder = GameIdentityBuilder()
    private val grouper = KeyingDedupGrouper()

    /** Mimic a scan: a filename (+ optional folder/hash/location) → a resolved [ResidentFile]. */
    private fun scanFile(
        name: String,
        folder: String? = null,
        hash: String? = null,
        location: ResidentLocationKind = ResidentLocationKind.SAF_TREE_GRANT,
    ): ResidentFile {
        val ext = name.substringAfterLast('.', "").lowercase()
        val hint = ExtensionPlatformClassifier.platformHintFromFolder(folder ?: name)
        val input = ResolutionInput(fileName = name, extension = ext, contentHash = hash)
        val platform = classifier.classifyWithContext(input, hint) ?: PlatformId("unknown")
        val identity = identityBuilder.build(platform, input)
        val contentKind = when {
            ext == "m3u" -> ContentKind.MULTI_DISC_PLAYLIST
            ext in setOf("cue", "ccd", "gdi") -> ContentKind.TRACK_MANIFEST
            DISC.containsMatchIn(name) -> ContentKind.DISC_MEMBER
            else -> ContentKind.GAME
        }
        val disc = DISC.find(name)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        return ResidentFile(
            entryKey = EntryKey("${folder ?: ""}/$name#${location.name}"),
            platformId = platform,
            contentKind = contentKind,
            displayName = name,
            location = location,
            contentHash = hash,
            sourceRootUri = folder,
            // Folder-qualified so the folder-scoped track-set fold (and folderOf) sees the real parent.
            relativePath = if (folder != null) "$folder/$name" else name,
            discNumber = disc,
            identity = identity,
        )
    }

    private fun groupsOf(vararg files: ResidentFile): List<GameGroup> = grouper.group(files.toList())

    // ── Multi-disc ───────────────────────────────────────────────────────────

    @Test
    fun `multi-disc PS1 with m3u collapses to one group with m3u primary`() {
        val groups = groupsOf(
            scanFile("Final Fantasy VII (Disc 1).chd", folder = "Roms/PSX"),
            scanFile("Final Fantasy VII (Disc 2).chd", folder = "Roms/PSX"),
            scanFile("Final Fantasy VII (Disc 3).chd", folder = "Roms/PSX"),
            scanFile("Final Fantasy VII.m3u", folder = "Roms/PSX"),
        )
        assertEquals("one collapsed game", 1, groups.size)
        val g = groups.single()
        assertEquals(4, g.memberCount)
        assertEquals(DuplicateGroupKind.MULTI_DISC, g.groupKind)
        assertEquals(ContentKind.MULTI_DISC_PLAYLIST, g.primary.contentKind)
        assertEquals("3 discs", g.collapseBadge)
    }

    @Test
    fun `multi-disc without m3u collapses to one group with lowest disc primary`() {
        val groups = groupsOf(
            scanFile("Metal Gear Solid (Disc 2).chd", folder = "Roms/PSX"),
            scanFile("Metal Gear Solid (Disc 1).chd", folder = "Roms/PSX"),
        )
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals(2, g.memberCount)
        assertEquals(1, g.primary.discNumber)
        assertEquals(DuplicateGroupKind.MULTI_DISC, g.groupKind)
    }

    @Test
    fun `roman numeral disc siblings collapse to one group`() {
        val groups = groupsOf(
            scanFile("Panzer Dragoon Saga (Disc I).chd", folder = "Roms/Saturn"),
            scanFile("Panzer Dragoon Saga (Disc II).chd", folder = "Roms/Saturn"),
        )

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().memberCount)
        assertEquals(DuplicateGroupKind.MULTI_DISC, groups.single().groupKind)
    }

    @Test
    fun `Dreamcast cdi extension does not create a multi-disc key`() {
        val key = DefaultGroupingKeyer().keyFor(
            scanFile("Sonic Adventure.cdi", folder = "Roms/Dreamcast"),
        )

        assertTrue(key is GroupingKey.Singleton)
    }

    @Test
    fun `lone-disc game stays a single launchable group`() {
        // RE Code Veronica X ships only Disc 2 in some sets — it must remain one visible, launchable tile.
        val groups = groupsOf(
            scanFile("Resident Evil - Code Veronica X (Disc 2).chd", folder = "Roms/PSX"),
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups.single().memberCount)
    }

    // ── Track sets (one disc split into a .cue manifest + .bin tracks) ───────

    @Test
    fun `multi-track cue plus bin set folds to one tile with the cue as launch primary`() {
        val folder = "Roms/Dreamcast/Sonic Adventure 2 (USA)"
        val groups = groupsOf(
            scanFile("Sonic Adventure 2 (USA).cue", folder = folder),
            scanFile("Sonic Adventure 2 (USA) (Track 1).bin", folder = folder),
            scanFile("Sonic Adventure 2 (USA) (Track 2).bin", folder = folder),
            scanFile("Sonic Adventure 2 (USA) (Track 3).bin", folder = folder),
        )
        assertEquals("one tile, not four", 1, groups.size)
        val g = groups.single()
        assertEquals(4, g.memberCount)
        assertEquals(ContentKind.TRACK_MANIFEST, g.primary.contentKind)
        assertTrue("the .cue is the launch member", g.launchMember.displayName.endsWith(".cue"))
        assertEquals("a single disc split into tracks shows no 'N discs' badge", null, g.collapseBadge)
    }

    @Test
    fun `single-track cue plus bin folds to one tile`() {
        val folder = "Roms/PSX/Some Game"
        val groups = groupsOf(
            scanFile("Some Game (USA).cue", folder = folder),
            scanFile("Some Game (USA).bin", folder = folder),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().memberCount)
        assertEquals(ContentKind.TRACK_MANIFEST, groups.single().primary.contentKind)
    }

    @Test
    fun `two track sets in one folder stay separate by base title`() {
        val folder = "Roms/Saturn"
        val groups = groupsOf(
            scanFile("Game A (USA).cue", folder = folder),
            scanFile("Game A (USA) (Track 1).bin", folder = folder),
            scanFile("Game B (USA).cue", folder = folder),
            scanFile("Game B (USA) (Track 1).bin", folder = folder),
        )
        assertEquals("A and B fold independently", 2, groups.size)
    }

    @Test
    fun `cue and bin in different folders do NOT fold together`() {
        val groups = groupsOf(
            scanFile("Some Game (USA).cue", folder = "Roms/PSX/A"),
            scanFile("Some Game (USA).bin", folder = "Roms/PSX/B"),
        )
        assertEquals("track sets fold ONLY within one folder", 2, groups.size)
    }

    @Test
    fun `standalone bin with no cue sibling stays its own launchable tile`() {
        val groups = groupsOf(
            scanFile("Some Homebrew (USA).bin", folder = "Roms/PSX"),
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups.single().memberCount)
    }

    @Test
    fun `track manifest matching an existing multi-disc base joins that group`() {
        val folder = "Roms/Dreamcast/Sonic Adventure 2"
        val groups = groupsOf(
            scanFile("Sonic Adventure 2 (USA) (Disc 1).chd", folder = folder),
            scanFile("Sonic Adventure 2 (USA) (Disc 2).chd", folder = folder),
            scanFile("Sonic Adventure 2 (USA).cue", folder = folder),
        )

        assertEquals("the manifest must not mint a phantom second tile", 1, groups.size)
        assertEquals(3, groups.single().memberCount)
        assertEquals(ContentKind.TRACK_MANIFEST, groups.single().primary.contentKind)
    }

    @Test
    fun `track manifest bridge requires the same platform and normalized base`() {
        val groups = groupsOf(
            scanFile("Sonic Adventure 2 (USA) (Disc 1).chd", folder = "Roms/Dreamcast/Sonic Adventure 2"),
            scanFile("Sonic Adventure 2 (USA).cue", folder = "Roms/PSX/Sonic Adventure 2"),
            scanFile("Sonic Adventure (USA).cue", folder = "Roms/Dreamcast/Sonic Adventure"),
        )

        assertEquals("different platform/title inputs stay separate", 3, groups.size)
    }

    // ── Region variants (no catalog key) ─────────────────────────────────────

    @Test
    fun `region variants without catalog key do NOT auto-collapse on filename alone`() {
        // A game NOT in the title catalog must NOT merge USA + Europe on filename alone (needs a
        // serial/catalog key). Uses a made-up title so it is guaranteed absent from the catalog.
        val groups = groupsOf(
            scanFile("Obscure Homebrew RPG (USA).nds"),
            scanFile("Obscure Homebrew RPG (Europe).nds"),
        )
        assertEquals("uncatalogued region siblings stay separate", 2, groups.size)
    }

    // ── Title catalog: curated region/version/language collapse ──────────────

    @Test
    fun `catalogued region and version variants collapse to one canonical tile`() {
        val groups = groupsOf(
            scanFile("Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb"),
            scanFile("Pokemon Red (USA).gb"),
            scanFile("pokemon_red.gb"),
        )
        assertEquals("three variants → one tile", 1, groups.size)
        val g = groups.single()
        assertEquals(3, g.memberCount)
        assertEquals(DuplicateGroupKind.CATALOG_TITLE_ID, g.groupKind)
        assertEquals("shown under the region-neutral canonical title", "Pokémon Red", g.identity.title)
        assertEquals("3 versions", g.collapseBadge)
    }

    @Test
    fun `catalog collapses region + other-language variants but never merges different games`() {
        val groups = groupsOf(
            scanFile("1015 - Pokemon - Diamond Version (USA) (Rev 5).nds"),
            scanFile("1284 - Pokemon - Diamond Version (Europe) (Rev 13).nds"),
            scanFile("Pokemon - Diamant-Edition (Germany) (Rev 5).nds"), // German title of the SAME game
            scanFile("1286 - Pokemon - Pearl Version (Europe) (Rev 13).nds"), // a DIFFERENT game
        )
        assertEquals("Diamond (3 variants incl. German) fold; Pearl stays apart", 2, groups.size)
        assertTrue(groups.any { it.identity.title == "Pokémon Diamond" && it.memberCount == 3 })
        assertTrue(groups.any { it.identity.title.contains("Pearl") && it.memberCount == 1 })
    }

    @Test
    fun `region variants sharing a serial DO collapse to one group`() {
        // When a stage reads the same serial for both, they collapse (CATALOG_TITLE_ID).
        val usa = ResolutionInput("Pokemon Diamond (USA).nds", "nds", serial = "ADAE")
        val eur = ResolutionInput("Pokemon Diamond (Europe).nds", "nds", serial = "ADAE")
        val fUsa = residentFor(usa, "usa")
        val fEur = residentFor(eur, "eur")
        val groups = grouper.group(listOf(fUsa, fEur))
        assertEquals(1, groups.size)
        assertEquals(DuplicateGroupKind.CATALOG_TITLE_ID, groups.single().groupKind)
    }

    // ── Exact-hash / cross-storage ───────────────────────────────────────────

    @Test
    fun `byte-identical dumps merge regardless of storage and SD copy wins`() {
        val sd = scanFile(
            "Sonic.md", folder = "SDcard/Roms/MegaDrive", hash = "abc123",
            location = ResidentLocationKind.SAF_TREE_GRANT,
        )
        val internal = scanFile(
            "Sonic.md", folder = "Internal/Roms/MegaDrive", hash = "abc123",
            location = ResidentLocationKind.MANAGED_PATH,
        )
        val groups = grouper.group(listOf(internal, sd))
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals(DuplicateGroupKind.EXACT_HASH, g.groupKind)
        // SD (SAF_TREE_GRANT) wins as the surviving primary over the internal/managed copy.
        assertEquals(ResidentLocationKind.SAF_TREE_GRANT, g.primary.location)
        assertEquals(1, g.hiddenMembers.size)
    }

    // ── No false collapse ────────────────────────────────────────────────────

    @Test
    fun `two genuinely different games stay two groups`() {
        val groups = groupsOf(
            scanFile("Super Mario World.sfc"),
            scanFile("Super Metroid.sfc"),
        )
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.memberCount == 1 })
    }

    @Test
    fun `a realistic mixed folder folds to the expected group count`() {
        val groups = groupsOf(
            // FF7: 3 discs + m3u → 1
            scanFile("Final Fantasy VII (Disc 1).chd", folder = "Roms/PSX"),
            scanFile("Final Fantasy VII (Disc 2).chd", folder = "Roms/PSX"),
            scanFile("Final Fantasy VII (Disc 3).chd", folder = "Roms/PSX"),
            scanFile("Final Fantasy VII.m3u", folder = "Roms/PSX"),
            // MGS: 2 discs, no m3u → 1
            scanFile("Metal Gear Solid (Disc 1).chd", folder = "Roms/PSX"),
            scanFile("Metal Gear Solid (Disc 2).chd", folder = "Roms/PSX"),
            // Two standalone GBA games → 2
            scanFile("Metroid Fusion (USA).gba"),
            scanFile("Golden Sun (USA).gba"),
            // Region pair without catalog key → 2 (do not collapse)
            scanFile("Pokemon Diamond (USA).nds"),
            scanFile("Pokemon Diamond (Europe).nds"),
        )
        assertEquals(6, groups.size)
        // The FF7 group is the multi-disc one with an m3u primary.
        val ff7 = groups.first { it.identity.title.contains("Final Fantasy VII") }
        assertNotNull(ff7)
        assertEquals(ContentKind.MULTI_DISC_PLAYLIST, ff7.primary.contentKind)
        assertEquals(4, ff7.memberCount)
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun residentFor(input: ResolutionInput, tag: String): ResidentFile {
        val platform = classifier.classify(input) ?: PlatformId("unknown")
        val identity = identityBuilder.build(platform, input)
        return ResidentFile(
            entryKey = EntryKey("${input.fileName}#$tag"),
            platformId = platform,
            contentKind = ContentKind.GAME,
            displayName = input.fileName,
            location = ResidentLocationKind.SAF_TREE_GRANT,
            contentHash = input.contentHash,
            relativePath = input.fileName,
            identity = identity,
        )
    }

    private companion object {
        val DISC = Regex("""\(?\s*(disc|disk|cd)\s*([0-9]+)""", RegexOption.IGNORE_CASE)
    }
}
