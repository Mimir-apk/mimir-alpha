package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name-first identity path, measured against the REAL shipped shards and the sampled REAL library.
 *
 * Every file name below was read off the reference device on 2026-09-17 (`/storage/<volume-id>/ROMs`). The
 * sampled library contains three tiers of name per game on the small platforms — the canonical dump name, a
 * plausible-but-wrong one, and a garbage one (`zelda.nes`, `sonic.md`) — which makes this corpus a far
 * better oracle than anything synthetic: it contains exactly the mistakes a real library contains.
 *
 * What this pins is a TRADE, not just a mapping. [AthenaIdentityEvidence.DAT_NAME_UNIQUE] is the one
 * authoritative class that never reads the payload, so the cases that must stay unresolved matter as much
 * as the ones that resolve. If a future normalization rule makes `Metroid Prime (USA) (v1.02)` resolve, it
 * has started guessing between `(USA)` and `(USA) (Rev 2)`, and this fails.
 */
class DatNameResolutionCorpusTest {

    private fun assetsDir(): File = listOf(
        File("../app/src/main/assets/dat"),
        File("app/src/main/assets/dat"),
    ).firstOrNull { it.isDirectory } ?: error("bundled DAT assets not visible from ${File(".").absolutePath}")

    private fun source(): BundledDatSource {
        val dir = assetsDir()
        return BundledDatSource({ name ->
            File(dir, name).takeIf(File::isFile)?.let { ByteBuffer.wrap(it.readBytes()) }
        })
    }

    private fun resolve(platform: String, fileName: String): String? =
        source().resolveByName(PlatformId(platform), fileName.substringBeforeLast('.'))?.canonicalTitle

    // ── The expensive files: 19.5 GB of whole-file reads this path removes ───────────────────────────

    /**
     * The claim, stated as data. Each entry is a file that today is read end-to-end — and for `.chd`,
     * decompressed end-to-end by [ChdPayloadVerifier] — purely to learn a title the DAT already carries
     * under a slightly different parenthetical.
     */
    private val expensive = listOf(
        Triple("ps1", "Chrono Cross (USA) (Disc 1).chd", "Chrono Cross (USA, Canada) (Disc 1)"),
        Triple("ps1", "Chrono Cross (USA) (Disc 2).chd", "Chrono Cross (USA, Canada) (Disc 2)"),
        Triple(
            "ps1",
            "Resident Evil 2 - Dual Shock Ver. (USA) (Disc 1).chd",
            "Resident Evil 2 - Dual Shock Ver. (USA) (Disc 1)",
        ),
        Triple(
            "ps1",
            "Resident Evil 2 - Dual Shock Ver. (USA) (Disc 2).chd",
            "Resident Evil 2 - Dual Shock Ver. (USA) (Disc 2)",
        ),
        Triple("ps1", "Xenogears (USA) (Disc 1).chd", "Xenogears (USA) (Disc 1)"),
        Triple("ps1", "Xenogears (USA) (Disc 2).chd", "Xenogears (USA) (Disc 2)"),
        Triple(
            "ps2",
            "Castlevania - Curse of Darkness (Europe) (En,Fr,De,Es,It).iso",
            "Castlevania - Curse of Darkness (Europe) (En,Fr,De,Es,It)",
        ),
        Triple(
            "psp",
            "Castlevania - The Dracula X Chronicles (Europe) (En,Fr,De,Es,It).chd",
            "Castlevania - The Dracula X Chronicles (Europe) (En,Fr,De,Es,It)",
        ),
        Triple(
            "dreamcast",
            "Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es).cue",
            "Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es)",
        ),
        Triple(
            "xbox360",
            "Resident Evil 5 ~ Biohazard 5 (World) (En,Ja,Fr,De,Es,It).iso",
            "Resident Evil 5 (World) (En,Ja,Fr,De,Es,It)",
        ),
    )

    @Test
    fun `every expensive disc resolves from its name, to the exact shipped dump name`() {
        val failures = expensive.mapNotNull { (platform, file, expected) ->
            val got = resolve(platform, file)
            if (got == expected) null else "$platform/$file -> ${got ?: "UNRESOLVED"} (expected $expected)"
        }
        assertEquals(
            "the whole point of the path is these ten files; each one that regresses is a multi-GB read " +
                "returning to first run",
            emptyList<String>(),
            failures,
        )
    }

    @Test
    fun `the region list narrows the set, and is never required to match`() {
        // Some of the discs above only resolve because region NARROWS rather than gates: the file says
        // (USA), the shipped DAT says (USA, Canada) or (USA, Asia). Asserted separately from the table so
        // that a change turning region into an equality test names its own cause.
        assertEquals(
            "Chrono Cross (USA, Canada) (Disc 1)",
            resolve("ps1", "Chrono Cross (USA) (Disc 1).chd"),
        )
        assertEquals(
            "Shadows of the Damned (USA, Asia) (En,Fr,De,Es,It)",
            resolve("ps3", "Shadows of the Damned (USA) (En,Fr,De,Es,It).iso"),
        )
    }

    @Test
    fun `the retired dual heading is folded away`() {
        // The file carries "Resident Evil 5 ~ Biohazard 5"; the shipped DAT dropped the alternate title.
        assertEquals(
            "Resident Evil 5 (World) (En,Ja,Fr,De,Es,It)",
            resolve("xbox360", "Resident Evil 5 ~ Biohazard 5 (World) (En,Ja,Fr,De,Es,It).iso"),
        )
    }

    // ── The half that must NOT resolve ───────────────────────────────────────────────────────────────

    @Test
    fun `a genuinely ambiguous name is never guessed`() {
        // Metroid Prime ships as (USA) and (USA) (Rev 2); the file's own "(v1.02)" names neither. Two
        // equally good candidates is the exact case where only the bytes can decide.
        assertNull(resolve("gamecube", "Metroid Prime (USA) (v1.02).iso"))
        // Sonic ships as (USA, Europe) and several revisions that share that region; the file's "(USA)" is
        // none of their exact tag lists, so it stays on the hash.
        assertNull(resolve("genesis", "Sonic The Hedgehog (USA).md"))
    }

    // ── Version 2: canonical names (policy 2026-09-28, the measured safe core) ──────────────────

    @Test
    fun `a name whose tags are exactly one dump's resolves to that dump, not a revision beside it`() {
        //  The sampled near-miss pair used to stay unresolved; each is the exact name of one dump.
        assertEquals("Legend of Zelda, The (USA)", resolve("nes", "Legend of Zelda (USA).nes"))
        assertEquals("Legend of Zelda, The (USA) (Rev 1)", resolve("nes", "Legend of Zelda, The (USA) (Rev 1).nes"))
        assertEquals("Jurassic Park (USA)", resolve("snes", "Jurassic Park (USA).sfc"))
        assertEquals("Fatal Fury Special (Europe) (Beta)", resolve("snes", "Fatal Fury Special (Europe) (Beta).sfc"))
    }

    @Test
    fun `a trailing article without its comma and a smashed region list key like the dump name`() {
        assertEquals(
            "Addams Family, The (Europe) (En,Fr,De)",
            resolve("snes", "Addams Family The (Europe) (En Fr De).sfc"),
        )
        assertEquals("Incredibles, The (USA, Europe)", resolve("gba", "IncrediblesThe (USAEurope).zip"))
    }

    @Test
    fun `names the RG's bytes proved to be another dump stay on the hash`() {
        // Each of these resolved to a DIFFERENT dump by content on the reference RG Rotate (2026-09-28).
        assertNull(resolve("snes", "NBA Jam (USA) (Beta).sfc"))
        assertNull(resolve("snes", "Marvel Super Heroes - War of the Gems (USA) (Beta).sfc"))
        assertNull(resolve("snes", "Arkanoid - Doh It Again (Europe).sfc"))
        assertNull(resolve("snes", "Super Tetris 2 + Bombliss (Japan) (Genteiban).sfc"))
        assertNull(resolve("snes", "Great Battle IV The (Japan) (Sample).sfc"))
    }

    @Test
    fun `every shipped dump name resolves to itself or to nothing, never to another dump`() {
        val dir = assetsDir()
        for (platform in listOf("snes", "gba", "nes", "genesis")) {
            BundledDatManifest.assets.filter { it.platformId.value == platform }.forEach { asset ->
                val bytes = File(dir, asset.assetName).takeIf(File::isFile)?.readBytes() ?: return@forEach
                val index = requireNotNull(PackedDatIndex.open(ByteBuffer.wrap(bytes), asset.packedExpectation()))
                var itself = 0
                val crossed = mutableListOf<String>()
                index.dumpNames().forEach { name ->
                    val resolved = index.byName(name)?.canonicalTitle ?: return@forEach
                    if (resolved == name) itself++ else crossed += "$name -> $resolved"
                }
                assertTrue("$platform: ${crossed.take(5)}", crossed.isEmpty())
                assertTrue("$platform resolved only $itself names to themselves", itself > 0)
            }
        }
    }

    @Test
    fun `a name the catalogue does not carry resolves nothing rather than something close`() {
        // The shipped Dreamcast DATs carry "House of the Dead 2, The"; this file is named for a game that
        // is not in them. A stem-prefix or edit-distance rule would "helpfully" return the sequel.
        assertNull(resolve("dreamcast", "House of the Dead (USA).chd"))
        assertNull(resolve("gamecube", "Call of Duty MW Reflex (USA).rvz"))
        assertNull(resolve("gb", "Pokemon Red (USA).gb"))
        assertNull(resolve("gbc", "Zelda Oracle of Ages (USA).gbc"))
    }

    @Test
    fun `a garbage name resolves nothing`() {
        assertNull(resolve("nes", "zelda.nes"))
        assertNull(resolve("genesis", "sonic.md"))
        assertNull(resolve("gb", "pokemon_red.gb"))
        assertNull(resolve("gbc", "zelda_ages.gbc"))
    }

    @Test
    fun `a plainly named file never resolves to a beta, prototype or trial dump`() {
        // Sonic Adventure 2 is present in both Dreamcast shards as (USA), (Europe), (Japan), a (Beta) and
        // a (Taikenban). The qualified dumps must be invisible to an unqualified file even when they would
        // otherwise narrow the set.
        assertEquals(
            "Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es)",
            resolve("dreamcast", "Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es).cue"),
        )
        // Resident Evil 5 ships with three Beta dumps beside the release; without the qualification filter
        // the set would be four and nothing would resolve.
        assertEquals(
            "Resident Evil 5 (World) (En,Ja,Fr,De,Es,It)",
            resolve("xbox360", "Resident Evil 5 ~ Biohazard 5 (World) (En,Ja,Fr,De,Es,It).iso"),
        )
    }

    @Test
    fun `discs are distinct identities, never collapsed onto one another`() {
        val one = resolve("ps1", "Xenogears (USA) (Disc 1).chd")
        val two = resolve("ps1", "Xenogears (USA) (Disc 2).chd")
        assertEquals("Xenogears (USA) (Disc 1)", one)
        assertEquals("Xenogears (USA) (Disc 2)", two)
        assertTrue("a disc number that stops being part of identity is a swapped-disc bug", one != two)
    }

    /**
     * The inverse of the test above, in both directions, and the more dangerous one.
     *
     * A disc number is part of identity, so a file and a dump that disagree about whether there IS one must
     * not match. Both cases below fail closed today simply because the bucket key differs — which means the
     * protection is a side effect of the lookup rather than a decision, and a future change making an
     * untagged name match any disc would silently reintroduce swapped discs. Asserted so that change fails.
     */
    @Test
    fun `a disc number that only one side carries never matches`() {
        // The shipped ps1 DAT has Xenogears ONLY as (Disc 1) and (Disc 2). An untagged file naming it --
        // an .m3u playlist entry, or a merged dump -- must not silently become disc one.
        assertNull(resolve("ps1", "Xenogears (USA).chd"))

        // And the other way: Symphony of the Night ships untagged, so a file claiming a disc is either
        // mislabelled or a different release. Neither may resolve to the single-disc dump.
        assertNull(resolve("ps1", "Castlevania - Symphony of the Night (USA) (Disc 1).chd"))
        assertEquals(
            "the premise: the untagged form is what the catalogue actually carries",
            "Castlevania - Symphony of the Night (USA)",
            resolve("ps1", "Castlevania - Symphony of the Night (USA).chd"),
        )
    }

    /**
     * The device finding of 2026-09-17, pinned at its cause.
     *
     * The sampled PS1 discs sit behind `.m3u` playlists, and the PLAYLIST is the row the enricher resolves.
     * Its name is `Chrono Cross (USA)` -- the set -- while Redump carries only `(Disc 1)` and `(Disc 2)`.
     * The strict rule failed closed, the playlist fell through to the hash, and 2.2 GB of CHD was
     * decompressed to learn a title the catalogue already had. Worse, the enricher walks smallest-first, so
     * those tiny playlists went FIRST: 55 seconds before the progress counter moved at all.
     *
     * A multi-disc group's primary may therefore say which disc stands for the set. Nothing else may.
     */
    @Test
    fun `a multi-disc playlist resolves through disc one, while a lone file still may not`() {
        val playlist = "Chrono Cross (USA).m3u".substringBeforeLast('.')

        assertNull(
            "without the group signal this is still an untagged name against disc-tagged dumps",
            source().resolveByName(PlatformId("ps1"), playlist)?.canonicalTitle,
        )
        assertEquals(
            "with it, disc one stands for the set",
            "Chrono Cross (USA, Canada) (Disc 1)",
            source().resolveByName(PlatformId("ps1"), playlist, discFallback = 1)?.canonicalTitle,
        )

        // All three of the sampled PS1 sets, since all three paid the hash.
        listOf(
            "Xenogears (USA)" to "Xenogears (USA) (Disc 1)",
            "Resident Evil 2 - Dual Shock Ver. (USA)" to
                "Resident Evil 2 - Dual Shock Ver. (USA) (Disc 1)",
        ).forEach { (name, expected) ->
            assertEquals(expected, source().resolveByName(PlatformId("ps1"), name, discFallback = 1)?.canonicalTitle)
        }
    }

    @Test
    fun `the disc fallback narrows like any other lookup, and still fails closed`() {
        // It supplies a missing disc number; it does not switch off the rest of the rule. A stem the
        // catalogue does not carry stays unresolved no matter which disc is offered.
        assertNull(source().resolveByName(PlatformId("ps1"), "Not A Real Game (USA)", discFallback = 1))
        // And a name that DOES carry its own disc keeps it -- the fallback must never override a stated fact.
        assertEquals(
            "Xenogears (USA) (Disc 2)",
            source().resolveByName(PlatformId("ps1"), "Xenogears (USA) (Disc 2)", discFallback = 1)?.canonicalTitle,
        )
    }

    @Test
    fun `both Dreamcast shards are consulted and must agree`() {
        // Dreamcast is the only platform shipping two shards (MAMERedump .chd + Redump per-track). They
        // agree on this title and its collapse parent; if a repack ever made them disagree, the lookup
        // must fail closed rather than let manifest order pick a winner.
        val dir = assetsDir()
        val matches = listOf("dreamcast.midx", "dreamcast_redump.midx").map { shard ->
            val asset = BundledDatManifest.assets.single { it.assetName == shard }
            val index = PackedDatIndex.open(
                ByteBuffer.wrap(File(dir, shard).readBytes()),
                asset.packedExpectation(),
            )
            requireNotNull(index) { "$shard failed its packed invariants" }
                .byName("Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es)")
        }
        assertEquals("both shards must answer, or this test proves nothing", 2, matches.filterNotNull().size)
        assertEquals("the two shards must agree on identity AND collapse parent", matches[0], matches[1])
    }

}
