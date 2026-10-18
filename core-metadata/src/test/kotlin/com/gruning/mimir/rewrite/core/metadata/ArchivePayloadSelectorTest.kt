package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePayloadSelectorTest {

    @Test
    fun `expansion ratio refuses the first byte above the two-hundred-times boundary`() {
        assertFalse(archiveExpansionRatioExceeded(expandedBytes = 200_000L, compressedBytes = 1_000L))
        assertTrue(archiveExpansionRatioExceeded(expandedBytes = 200_001L, compressedBytes = 1_000L))
    }

    @Test
    fun `off-allowlist payload identifies only through the hash oracle`() = runBlocking {
        val payload = payload(1)
        val archive = zipOf(Entry("readme.txt", ByteArray(25)), Entry("game.rom", payload))
        assertLegacyZipRetryable(archive)
        val result = selector(
            archive = archive,
            matches = mapOf(sha1(payload) to match("Oracle Game")),
        ).select(budget())

        val identified = result as ArchivePayloadSelectionResult.Identified
        assertEquals("Oracle Game", identified.identity.canonicalTitle)
        assertEquals(0, identified.identity.descentDepth)
        assertNull(identified.identity.fingerprint.exactRawSha1)
    }

    @Test
    fun `extensionless payload identifies only through the hash oracle`() = runBlocking {
        val game = payload(123)
        val archive = zipOf(
            Entry("readme.txt", ByteArray(25)),
            Entry("payload", game),
        )
        assertLegacyZipRetryable(archive)

        val result = selector(
            archive = archive,
            matches = mapOf(sha1(game) to match("Extensionless Game")),
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Extensionless Game", result.identity.canonicalTitle)
        assertEquals(0, result.identity.descentDepth)
        assertNull(result.identity.fingerprint.exactRawSha1)
    }

    @Test
    fun `junk-only archive terminalises with a typed reason`() = runBlocking {
        val archive = zipOf(
            Entry("readme.txt", ByteArray(25)),
            Entry("info.nfo", ByteArray(2048)),
            Entry("site.url", ByteArray(2048)),
        )
        assertLegacyZipRetryable(archive)

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.NO_ALLOWLISTED_PAYLOAD,
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `conflicting catalog hits never choose a winner`() = runBlocking {
        val first = payload(2)
        val second = payload(3)
        val archive = zipOf(Entry("first.z64", first), Entry("second.z64", second))
        assertLegacyZipRetryable(archive)

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.AMBIGUOUS_CATALOG_MATCH,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(
                archive,
                mapOf(
                    sha1(first) to match("First"),
                    sha1(second) to match("Second"),
                ),
            ).select(budget()),
        )
    }

    @Test
    fun `agreeing catalog hits identify one logical game`() = runBlocking {
        val first = payload(111)
        val second = payload(112)
        val archive = zipOf(Entry("first.z64", first), Entry("second.z64", second))

        val result = selector(
            archive,
            mapOf(
                sha1(first) to match("Agreed Game"),
                sha1(second) to match("Agreed Game"),
            ),
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Agreed Game", result.identity.canonicalTitle)
        assertEquals("collapse:Agreed Game", result.identity.collapseKey)
        assertEquals(0, result.identity.descentDepth)
    }

    @Test
    fun `AppleDouble resource fork cannot manufacture a second Tier A candidate`() = runBlocking {
        val resourceFork = payload(113)
        val game = payload(114)
        val archive = zipOf(
            Entry("__MACOSX/._game.z64", resourceFork),
            Entry("game.z64", game),
        )
        val oracleCalls = mutableListOf<String?>()
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, fingerprint ->
                oracleCalls += fingerprint.datHash.sha1
                when (fingerprint.datHash.sha1) {
                    sha1(resourceFork) -> match("Resource Fork Must Not Identify")
                    sha1(game) -> match("Real Game")
                    else -> null
                }
            },
            nanoTime = { 0L },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Real Game", result.identity.canonicalTitle)
        assertEquals(listOf(sha1(game)), oracleCalls)
    }

    @Test
    fun `platform allowlist wins when bin also resembles junk`() = runBlocking {
        val game = payload(115)
        val archive = zipOf(Entry("game.bin", game))

        val result = selector(
            archive = archive,
            matches = mapOf(sha1(game) to match("Mega Drive Game")),
            platformId = "megadrive",
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Mega Drive Game", result.identity.canonicalTitle)
    }

    @Test
    fun `tiny entry is excluded while a two-kibibyte Atari cart remains admissible`() = runBlocking {
        val tiny = payload(116, 512)
        val game = payload(117, 2 * 1024)
        val archive = zipOf(
            Entry("tiny.bin", tiny),
            Entry("game.bin", game),
        )
        val oracleCalls = mutableListOf<String?>()
        val result = ArchivePayloadSelector(
            platformId = "atari2600",
            residentSizeBytes = archive.size.toLong(),
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, fingerprint ->
                oracleCalls += fingerprint.datHash.sha1
                when (fingerprint.datHash.sha1) {
                    sha1(tiny) -> match("Tiny Must Not Identify")
                    sha1(game) -> match("Atari Game")
                    else -> null
                }
            },
            nanoTime = { 0L },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Atari Game", result.identity.canonicalTitle)
        assertEquals(listOf(sha1(game)), oracleCalls)
    }

    @Test
    fun `one Tier A payload ignores Tier B oracle work and never descends`() = runBlocking {
        val preferred = payload(4)
        val fallback = payload(5)
        val nestedPayload = payload(6)
        val nested = zipOf(Entry("nested.z64", nestedPayload))
        val archive = zipOf(
            Entry("fallback.rom", fallback),
            Entry("game.z64", preferred),
            Entry("inner.zip", nested, stored = true),
        )
        var oracleCalls = 0
        var opens = 0
        val selector = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = {
                opens++
                ByteArrayInputStream(archive)
            },
            oracle = { _, fingerprint ->
                oracleCalls++
                if (fingerprint.datHash.sha1 == sha1(preferred)) match("Preferred") else null
            },
            nanoTime = { 0L },
        )

        val result = selector.select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Preferred", result.identity.canonicalTitle)
        assertEquals(1, oracleCalls)
        assertEquals(1, opens) // one generation-stable walk; the nested archive was not opened
    }

    @Test
    fun `wrapper before a valid shallow Tier A is never descended`() = runBlocking {
        val selected = payload(60)
        val archive = zipOf(
            Entry("first-wrapper.zip", payload(61), stored = true),
            Entry("selected.z64", selected),
        )
        var depthOneReached = false
        var opens = 0

        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = {
                opens++
                ByteArrayInputStream(archive)
            },
            oracle = { _, fingerprint ->
                if (fingerprint.datHash.sha1 == sha1(selected)) match("Shallow A") else null
            },
            nanoTime = { 0L },
            onCandidateReached = { depth -> if (depth == 1) depthOneReached = true },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Shallow A", result.identity.canonicalTitle)
        assertFalse(depthOneReached)
        assertEquals(1, opens)
    }

    @Test
    fun `Tier B oracle runs after a lone Tier A candidate misses`() = runBlocking {
        val allowlistedMiss = payload(41)
        val fallbackHit = payload(42)
        val archive = zipOf(
            Entry("decoy.z64", allowlistedMiss),
            Entry("actual.rom", fallbackHit),
        )
        val calls = mutableListOf<String?>()
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, fingerprint ->
                calls += fingerprint.datHash.sha1
                if (fingerprint.datHash.sha1 == sha1(fallbackHit)) match("Fallback Hit") else null
            },
            nanoTime = { 0L },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Fallback Hit", result.identity.canonicalTitle)
        assertEquals(listOf(sha1(allowlistedMiss), sha1(fallbackHit)), calls)
    }

    @Test
    fun `wrapper before a valid shallow Tier B is never descended`() = runBlocking {
        val selected = payload(62)
        val archive = zipOf(
            Entry("first-wrapper.zip", payload(63), stored = true),
            Entry("selected.rom", selected),
        )
        var depthOneReached = false
        var opens = 0

        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = {
                opens++
                ByteArrayInputStream(archive)
            },
            oracle = { _, fingerprint ->
                if (fingerprint.datHash.sha1 == sha1(selected)) match("Shallow B") else null
            },
            nanoTime = { 0L },
            onCandidateReached = { depth -> if (depth == 1) depthOneReached = true },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Shallow B", result.identity.canonicalTitle)
        assertFalse(depthOneReached)
        assertEquals(2, opens)
    }

    @Test
    fun `one valid Tier A wins before eight Tier B candidates consume work`() = runBlocking {
        val selected = payload(64)
        val archive = zipOf(
            Entry("selected.z64", selected),
            *(0 until 8).map { index ->
                Entry("fallback-$index.rom", payload(65 + index))
            }.toTypedArray(),
        )
        var opens = 0

        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = {
                opens++
                ByteArrayInputStream(archive)
            },
            oracle = { _, fingerprint ->
                if (fingerprint.datHash.sha1 == sha1(selected)) match("Preferred A") else null
            },
            nanoTime = { 0L },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Preferred A", result.identity.canonicalTitle)
        assertEquals(1, opens)
    }

    @Test
    fun `changed resident generation on required Tier B pass is retryable`() = runBlocking {
        val selected = payload(73)
        val firstGeneration = zipWithComment("first!", Entry("selected.rom", selected))
        val secondGeneration = zipWithComment("second", Entry("selected.rom", selected))
        var opens = 0

        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = firstGeneration.size.toLong(),
            openResident = {
                opens++
                ByteArrayInputStream(if (opens == 1) firstGeneration else secondGeneration)
            },
            oracle = { _, fingerprint ->
                if (fingerprint.datHash.sha1 == sha1(selected)) match("Must Not Admit") else null
            },
            nanoTime = { 0L },
        ).select(budget())

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.ARCHIVE_CHANGED,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            result,
        )
        assertEquals(2, opens)
    }

    @Test
    fun `stored outer and deflated inner bundle identifies at one descent`() = runBlocking {
        val rom = payload(7, 8 * 1024 * 1024)
        val nested = zipOf(Entry("Super Mario 64 (ESP) [!].z64", rom))
        val archive = zipOf(
            Entry("readme.txt", ByteArray(25), stored = true),
            Entry("Super Mario 64 - 51230/", ByteArray(0), directory = true),
            Entry("Super Mario 64 - 51230/files/", ByteArray(0), directory = true),
            Entry("Super Mario 64 - 51230/files/33226/", ByteArray(0), directory = true),
            Entry("Super Mario 64 - 51230/files/33226/51230.zip", nested, stored = true),
            Entry("Super Mario 64 - 51230/images/", ByteArray(0), directory = true),
            Entry("Super Mario 64 - 51230/images/cover.png", payload(8, 84_760)),
        )
        assertLegacyZipRetryable(archive)

        val result = selector(
            archive = archive,
            matches = mapOf(sha1(rom) to match("Super Mario 64")),
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Super Mario 64", result.identity.canonicalTitle)
        assertEquals(1, result.identity.descentDepth)
    }

    @Test
    fun `nested off-allowlist payload identifies through the oracle`() = runBlocking {
        val rom = payload(118)
        val nested = zipOf(Entry("51230.rom", rom))
        val archive = zipOf(
            Entry("bundle/files/33226/51230.zip", nested, stored = true),
        )

        val result = selector(
            archive,
            mapOf(sha1(rom) to match("Nested Oracle Game")),
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Nested Oracle Game", result.identity.canonicalTitle)
        assertEquals(1, result.identity.descentDepth)
        assertNull(result.identity.fingerprint.exactRawSha1)
    }

    @Test
    fun `numeric bundle signature orders descent without becoming identity`() = runBlocking {
        val ordinaryPayload = payload(119)
        val numericPayload = payload(120)
        val ordinary = zipOf(Entry("ordinary.z64", ordinaryPayload))
        val numeric = zipOf(Entry("numeric.z64", numericPayload))
        val archive = zipOf(
            Entry("ordinary.zip", ordinary, stored = true),
            Entry("bundle/files/33226/51230.zip", numeric, stored = true),
        )
        val oracleCalls = mutableListOf<String?>()
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, fingerprint ->
                oracleCalls += fingerprint.datHash.sha1
                match("Agreed Nested Game")
            },
            nanoTime = { 0L },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Agreed Nested Game", result.identity.canonicalTitle)
        assertEquals(
            listOf(sha1(numericPayload), sha1(ordinaryPayload)),
            oracleCalls,
        )
    }

    @Test
    fun `inner cover art is never hashed or admitted as identity`() = runBlocking {
        val cover = payload(121)
        val game = payload(122)
        val nested = zipOf(
            Entry("images/cover.png", cover),
            Entry("payload.rom", game),
        )
        val archive = zipOf(Entry("inner.zip", nested, stored = true))
        val oracleCalls = mutableListOf<String?>()
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, fingerprint ->
                oracleCalls += fingerprint.datHash.sha1
                when (fingerprint.datHash.sha1) {
                    sha1(cover) -> match("Cover Must Not Identify")
                    sha1(game) -> match("Nested Game")
                    else -> null
                }
            },
            nanoTime = { 0L },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Nested Game", result.identity.canonicalTitle)
        assertEquals(listOf(sha1(game)), oracleCalls)
    }

    @Test
    fun `one resident generation is opened even when a later generation would be ambiguous`() = runBlocking {
        val selected = payload(44)
        val conflicting = payload(45)
        val firstGeneration = zipOf(Entry("selected.z64", selected))
        val secondGeneration = zipOf(
            Entry("selected.z64", selected),
            Entry("conflict.z64", conflicting),
        )
        var opens = 0
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = firstGeneration.size.toLong(),
            openResident = {
                opens++
                ByteArrayInputStream(if (opens == 1) firstGeneration else secondGeneration)
            },
            oracle = { _, fingerprint ->
                when (fingerprint.datHash.sha1) {
                    sha1(selected) -> match("Stable Generation")
                    sha1(conflicting) -> match("Must Not Mix")
                    else -> null
                }
            },
            nanoTime = { 0L },
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Stable Generation", result.identity.canonicalTitle)
        assertEquals(1, opens)
    }

    @Test
    fun `nested payload DAT miss remains terminal but records depth-one reach`() = runBlocking {
        val nested = zipOf(Entry("unknown.z64", payload(46)))
        val archive = zipOf(Entry("inner.zip", nested, stored = true))

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.NO_CATALOG_MATCH,
                ArchivePayloadReach(deepestCandidateDepthReached = 1),
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `shallow miss plus nested junk remains a catalog miss`() = runBlocking {
        val inner = zipOf(Entry("readme.txt", ByteArray(25)))
        val outer = zipOf(
            Entry("unknown.z64", payload(108)),
            Entry("inner.zip", inner, stored = true),
        )

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.NO_CATALOG_MATCH,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(outer).select(budget()),
        )
    }

    @Test
    fun `zip inside the permitted inner archive terminalises over-depth`() = runBlocking {
        val deepest = zipOf(Entry("game.z64", payload(9)))
        val nested = zipOf(Entry("deeper.zip", deepest, stored = true))
        val archive = zipOf(Entry("inner.zip", nested, stored = true))
        assertLegacyZipRetryable(archive)

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.NESTED_PAYLOAD_OVER_DEPTH,
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `a valid depth-one payload wins over an over-depth sibling`() = runBlocking {
        val rom = payload(47)
        val deeper = zipOf(Entry("too-deep.z64", payload(48)))
        val nested = zipOf(
            Entry("game.z64", rom),
            Entry("deeper.zip", deeper, stored = true),
        )
        val archive = zipOf(Entry("inner.zip", nested, stored = true))

        val result = selector(
            archive,
            mapOf(sha1(rom) to match("Depth One Wins")),
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Depth One Wins", result.identity.canonicalTitle)
        assertEquals(ArchivePayloadReach(1), result.reach)
    }

    @Test
    fun `one valid nested Tier A wins before eight nested Tier B candidates consume work`() = runBlocking {
        val selected = payload(76)
        val inner = zipOf(
            Entry("selected.z64", selected),
            *(0 until 8).map { index ->
                Entry("fallback-$index.rom", payload(77 + index))
            }.toTypedArray(),
        )
        val outer = zipOf(Entry("inner.zip", inner, stored = true))

        val result = selector(
            outer,
            mapOf(sha1(selected) to match("Nested Preferred A")),
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Nested Preferred A", result.identity.canonicalTitle)
        assertEquals(1, result.identity.descentDepth)
    }

    @Test
    fun `valid depth-one payload wins without spending work on over-depth siblings`() = runBlocking {
        val selected = payload(92)
        val inner = zipOf(
            Entry("selected.z64", selected),
            *(0 until 9).map { index ->
                Entry("forbidden-depth-$index.zip", payload(93 + index, 2048), stored = true)
            }.toTypedArray(),
        )
        val outer = zipOf(Entry("inner.zip", inner, stored = true))

        val result = selector(
            outer,
            mapOf(sha1(selected) to match("Depth-One Authority")),
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Depth-One Authority", result.identity.canonicalTitle)
        assertEquals(ArchivePayloadReach(1), result.reach)
    }

    @Test
    fun `Tier C descents share the eight-work cap`() = runBlocking {
        val nested = zipOf(Entry("readme.txt", payload(56, 2048)))
        val archive = zipOf(
            *(0 until 9).map { index ->
                Entry("bundle-$index.zip", nested, stored = true)
            }.toTypedArray(),
        )

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `more than eight admissible candidates terminalises without partial identity`() = runBlocking {
        val entries = (0 until 9).map { index -> Entry("candidate-$index.rom", payload(index + 10)) }
        val archive = zipOf(*entries.toTypedArray())

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `entry total is shared across outer and inner levels`() = runBlocking {
        val innerEntries = (0 until 20).map { index ->
            Entry("inner-$index.txt", ByteArray(1))
        }
        val nested = zipOf(*innerEntries.toTypedArray())
        val outerEntries = (0 until 500).map { index ->
            Entry("outer-$index.txt", ByteArray(1))
        } + Entry("inner.zip", nested, stored = true)
        val archive = zipOf(*outerEntries.toTypedArray())

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `directory entries count toward the root entry ceiling`() = runBlocking {
        val archive = zipOf(
            *(0 until 513).map { index ->
                Entry("directory-$index/", ByteArray(0), directory = true)
            }.toTypedArray(),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `directory entry ceiling is shared across root and depth one`() = runBlocking {
        val inner = zipOf(
            *(0 until 12).map { index ->
                Entry("inner-directory-$index/", ByteArray(0), directory = true)
            }.toTypedArray(),
        )
        val outer = zipOf(
            *(0 until 500).map { index ->
                Entry("outer-directory-$index/", ByteArray(0), directory = true)
            }.toTypedArray(),
            Entry("inner.zip", inner, stored = true),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            selector(outer).select(budget()),
        )
    }

    @Test
    fun `outer CRC corruption discards valid-looking inner findings as retryable`() = runBlocking {
        val rom = payload(40)
        val nested = zipOf(Entry("game.z64", rom))
        val validOuter = zipOf(Entry("inner.zip", nested, stored = true))
        val corruptedOuter = corruptFirstEntryByte(validOuter)

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.MALFORMED_ARCHIVE,
            ),
            selector(
                corruptedOuter,
                mapOf(sha1(rom) to match("Must Not Escape")),
            ).select(budget()),
        )
    }

    @Test
    fun `CRC-valid outer entry with malformed inner bytes terminalises`() = runBlocking {
        val malformedInner = ByteArray(2048).also {
            it[0] = 0x50
            it[1] = 0x4b
            it[2] = 0x03
            it[3] = 0x04
            // Local header with an impossible 32,767-byte name in a CRC-valid 2 KiB outer entry.
            it[26] = 0xff.toByte()
            it[27] = 0x7f
        }
        val archive = zipOf(Entry("inner.zip", malformedInner, stored = true))

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.INNER_MALFORMED,
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `CRC-valid inner archive with malformed UTF-8 name terminalises`() = runBlocking {
        val malformedInner = invalidateFirstLocalName(
            zipOf(Entry("game.z64", payload(49))),
        )
        val archive = zipOf(Entry("inner.zip", malformedInner, stored = true))

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.INNER_MALFORMED,
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `resident truncated after a complete local entry is retryable not identified`() = runBlocking {
        val rom = payload(50)
        val complete = zipOf(Entry("game.z64", rom))
        val truncated = complete.copyOf(complete.size - 22)

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.MALFORMED_ARCHIVE,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(
                truncated,
                mapOf(sha1(rom) to match("Must Not Admit")),
            ).select(budget()),
        )
    }

    @Test
    fun `forged EOCD without its claimed central directory is retryable`() = runBlocking {
        val rom = payload(75)
        val forged = removeCentralDirectoryAndForgeEocd(
            zipOf(Entry("game.z64", rom, stored = true)),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.MALFORMED_ARCHIVE,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(
                forged,
                mapOf(sha1(rom) to match("Must Not Admit")),
            ).select(budget()),
        )
    }

    @Test
    fun `root central-directory name mismatch is retryable`() = runBlocking {
        val rom = payload(89)
        val mismatched = mutateFirstCentralName(
            zipOf(Entry("game.z64", rom, stored = true)),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.MALFORMED_ARCHIVE,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(
                mismatched,
                mapOf(sha1(rom) to match("Must Not Admit")),
            ).select(budget()),
        )
    }

    @Test
    fun `root central-directory local-header offset mismatch is retryable`() = runBlocking {
        val rom = payload(103)
        val mismatched = mutateFirstCentralLocalOffset(
            zipOf(Entry("game.z64", rom, stored = true)),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.MALFORMED_ARCHIVE,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(
                mismatched,
                mapOf(sha1(rom) to match("Must Not Admit")),
            ).select(budget()),
        )
    }

    @Test
    fun `duplicate names cannot hide swapped central local-header offsets`() = runBlocking {
        val malformed = duplicateNamesAndSwapCentralOffsets(
            zipOf(
                Entry("one.z64", payload(106)),
                Entry("two.z64", payload(107)),
            ),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.MALFORMED_ARCHIVE,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(malformed).select(budget()),
        )
    }

    @Test
    fun `root central-directory flag mismatch is retryable`() = runBlocking {
        val rom = payload(104)
        val mismatched = mutateFirstCentralFlags(
            zipOf(Entry("game.z64", rom, stored = true)),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.MALFORMED_ARCHIVE,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(
                mismatched,
                mapOf(sha1(rom) to match("Must Not Admit")),
            ).select(budget()),
        )
    }

    @Test
    fun `per-entry ZIP64 sentinel is a typed resource refusal`() = runBlocking {
        val rom = payload(105)
        val unsupported = mutateFirstCentralSizeToZip64(
            zipOf(Entry("game.z64", rom, stored = true)),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(unsupported).select(budget()),
        )
    }

    @Test
    fun `per-entry ZIP64 disk sentinel is a typed resource refusal`() = runBlocking {
        val unsupported = mutateFirstCentralDiskToZip64(
            zipOf(Entry("game.z64", payload(110), stored = true)),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            selector(unsupported).select(budget()),
        )
    }

    @Test
    fun `inner central-directory CRC mismatch terminalises as inner malformed`() = runBlocking {
        val rom = payload(90)
        val inner = mutateFirstCentralCrc(
            zipOf(Entry("game.z64", rom, stored = true)),
        )
        val outer = zipOf(Entry("inner.zip", inner, stored = true))

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.INNER_MALFORMED,
                ArchivePayloadReach(deepestCandidateDepthReached = 1),
            ),
            selector(
                outer,
                mapOf(sha1(rom) to match("Must Not Admit")),
            ).select(budget()),
        )
    }

    @Test
    fun `CRC-valid outer entry containing truncated inner EOCD terminalises`() = runBlocking {
        val completeInner = zipOf(Entry("game.z64", payload(51)))
        val truncatedInner = completeInner.copyOf(completeInner.size - 22)
        val archive = zipOf(Entry("inner.zip", truncatedInner, stored = true))

        assertEquals(
            ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.INNER_MALFORMED,
                ArchivePayloadReach(deepestCandidateDepthReached = 1),
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `truncated outer EOCD overrides a malformed inner candidate`() = runBlocking {
        val completeOuter = zipOf(Entry("inner.zip", payload(74), stored = true))
        val truncatedOuter = completeOuter.copyOf(completeOuter.size - 22)

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.MALFORMED_ARCHIVE,
            ),
            selector(truncatedOuter).select(budget()),
        )
    }

    @Test
    fun `read-time storage failure remains IO unavailable`() = runBlocking {
        val archive = zipOf(Entry("game.z64", payload(52)))
        var closed = false
        val failing = object : InputStream() {
            private val delegate = ByteArrayInputStream(archive)
            private var remaining = 128

            override fun read(): Int {
                if (remaining == 0) throw IOException("volume detached")
                remaining--
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining == 0) throw IOException("volume detached")
                val read = delegate.read(b, off, minOf(len, remaining))
                if (read > 0) remaining -= read
                return read
            }

            override fun close() {
                closed = true
                delegate.close()
            }
        }
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { failing },
            oracle = { _, _ -> null },
            nanoTime = { 0L },
        ).select(budget())

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.IO_UNAVAILABLE,
            ),
            result,
        )
        assertTrue(closed)
    }

    @Test
    fun `mid-read revoked storage grant remains IO unavailable`() = runBlocking {
        val archive = zipOf(Entry("game.z64", payload(91)))
        var remaining = 128
        val revoked = object : InputStream() {
            private val delegate = ByteArrayInputStream(archive)

            override fun read(): Int {
                if (remaining == 0) throw SecurityException("grant revoked")
                remaining--
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining == 0) throw SecurityException("grant revoked")
                val read = delegate.read(b, off, minOf(len, remaining))
                if (read > 0) remaining -= read
                return read
            }
        }
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { revoked },
            oracle = { _, _ -> null },
            nanoTime = { 0L },
        ).select(budget())

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.IO_UNAVAILABLE,
            ),
            result,
        )
    }

    @Test
    fun `data-bearing directory entries at both depths stay non-candidates`() = runBlocking {
        val rom = payload(53)
        val nested = zipOf(
            Entry("hidden/", payload(54), stored = true, directory = true),
            Entry("game.z64", rom),
        )
        val archive = zipOf(
            Entry("outer-hidden/", payload(55), stored = true, directory = true),
            Entry("inner.zip", nested, stored = true),
        )

        val result = selector(
            archive,
            mapOf(sha1(rom) to match("Directory Safe")),
        ).select(budget()) as ArchivePayloadSelectionResult.Identified

        assertEquals("Directory Safe", result.identity.canonicalTitle)
        assertEquals(ArchivePayloadReach(1), result.reach)
    }

    @Test
    fun `aggregate expansion guard is enforced continuously`() = runBlocking {
        val archive = zipOf(Entry("game.z64", ByteArray(64 * 1024)))
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = 1L,
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, _ -> null },
            nanoTime = { 0L },
        ).select(budget())

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            result,
        )
    }

    @Test
    fun `nested composition trips aggregate guard while each entry ratio stays admissible`() = runBlocking {
        var state = 0x1234_5678
        val mostlyZero = ByteArray(2 * 1024 * 1024)
        for (index in mostlyZero.indices step 1024) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            repeat(4) { offset ->
                if (index + offset < mostlyZero.size) {
                    mostlyZero[index + offset] = (state ushr (offset * 8)).toByte()
                }
            }
        }
        val nested = zipOf(Entry("game.z64", mostlyZero, stored = true))
        val archive = zipOf(Entry("inner.zip", nested))
        val outerRatio = nested.size.toDouble() / archive.size.toDouble()
        assertTrue(
            "fixture must keep the outer entry below 200x but above 100x; was $outerRatio",
            outerRatio in 100.0..199.0,
        )
        assertLegacyZipRetryable(archive)

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
                ArchivePayloadReach(deepestCandidateDepthReached = 1),
            ),
            selector(archive).select(budget()),
        )
    }

    @Test
    fun `expanded-byte ceiling double-counts outer wrapper and inner payload work`() = runBlocking {
        val rom = payload(57, 16 * 1024)
        val nested = zipOf(Entry("game.z64", rom, stored = true))
        val archive = zipOf(Entry("inner.zip", nested, stored = true))
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, _ -> null },
            nanoTime = { 0L },
            limits = ArchivePayloadLimits(
                maxTotalExpandedBytes = nested.size.toLong() + rom.size * 3L / 4L,
            ),
        ).select(budget())

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            result,
        )
    }

    @Test
    fun `deadline elapsed after depth-one admission aborts the inner walk`() = runBlocking {
        val nested = zipOf(Entry("game.z64", payload(58, 64 * 1024), stored = true))
        val archive = zipOf(Entry("inner.zip", nested, stored = true))
        var depthOneReached = false
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, _ -> null },
            nanoTime = { if (depthOneReached) 46L * 1_000_000_000L else 0L },
            onCandidateReached = { depth ->
                if (depth == 1) depthOneReached = true
            },
        ).select(ChdVerificationBudget.forResolve(nanoTime = { 0L }))

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
                ArchivePayloadReach(deepestCandidateDepthReached = 1),
            ),
            result,
        )
    }

    @Test
    fun `deadline crossing during oracle lookup cannot admit identity`() = runBlocking {
        val rom = payload(109)
        val archive = zipOf(Entry("game.z64", rom))
        var oracleRan = false
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, _ ->
                oracleRan = true
                match("Too Late")
            },
            nanoTime = { if (oracleRan) 46L * 1_000_000_000L else 0L },
        ).select(ChdVerificationBudget.forResolve(nanoTime = { 0L }))

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
                ArchivePayloadReach(deepestCandidateDepthReached = 0),
            ),
            result,
        )
    }

    @Test
    fun `cancellation during depth-one payload closes the resident and escapes`() = runBlocking {
        val nested = zipOf(Entry("game.z64", payload(59, 64 * 1024), stored = true))
        val archive = zipOf(Entry("inner.zip", nested, stored = true))
        var closedCount = 0
        val failure = runCatching {
            ArchivePayloadSelector(
                platformId = "n64",
                residentSizeBytes = archive.size.toLong(),
                openResident = {
                    object : ByteArrayInputStream(archive) {
                        override fun close() {
                            closedCount++
                            super.close()
                        }
                    }
                },
                oracle = { _, _ -> null },
                nanoTime = { 0L },
                onCandidateReached = { depth ->
                    if (depth == 1) throw CancellationException("cancel nested payload")
                },
            ).select(budget())
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(closedCount >= 2)
    }

    @Test
    fun `expired shared deadline refuses before archive work begins`() = runBlocking {
        val archive = zipOf(Entry("game.z64", payload(43)))
        var opens = 0
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = {
                opens++
                ByteArrayInputStream(archive)
            },
            oracle = { _, _ -> match("Must Not Run") },
            nanoTime = { 0L },
        ).select(
            ChdVerificationBudget.forResolve(
                nanoTime = { 0L },
                absoluteDeadlineNanos = 0L,
            ),
        )

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            result,
        )
        assertEquals(0, opens)
    }

    @Test
    fun `root central-directory tail remains under the deadline`() = runBlocking {
        val archive = zipOf(
            *(0 until 300).map { index ->
                Entry("junk-$index.txt", ByteArray(0), stored = true)
            }.toTypedArray(),
        )
        var tailReached = false
        val input = object : ByteArrayInputStream(archive) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val read = super.read(b, off, len)
                if (pos >= archive.size - 256) tailReached = true
                return read
            }
        }
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = archive.size.toLong(),
            openResident = { input },
            oracle = { _, _ -> null },
            nanoTime = { if (tailReached) 46L * 1_000_000_000L else 0L },
        ).select(ChdVerificationBudget.forResolve(nanoTime = { 0L }))

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            result,
        )
    }

    @Test
    fun `root raw resident ceiling includes the EOCD comment tail`() = runBlocking {
        val archive = zipWithComment("x".repeat(4096))
        val result = ArchivePayloadSelector(
            platformId = "n64",
            residentSizeBytes = 128L,
            openResident = { ByteArrayInputStream(archive) },
            oracle = { _, _ -> null },
            nanoTime = { 0L },
        ).select(budget())

        assertEquals(
            ArchivePayloadSelectionResult.Retryable(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            ),
            result,
        )
    }

    @Test
    fun `cancellation always escapes and closes the resident`() = runBlocking {
        var closed = false
        val cancelling = object : InputStream() {
            override fun read(): Int = throw CancellationException("stop")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw CancellationException("stop")
            override fun close() {
                closed = true
            }
        }
        val result = runCatching {
            ArchivePayloadSelector(
                platformId = "n64",
                residentSizeBytes = 4096L,
                openResident = { cancelling },
                oracle = { _, _ -> null },
                nanoTime = { 0L },
            ).select(budget())
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(closed)
    }

    private fun selector(
        archive: ByteArray,
        matches: Map<String?, ArchivePayloadOracleMatch> = emptyMap(),
        platformId: String = "n64",
    ) = ArchivePayloadSelector(
        platformId = platformId,
        residentSizeBytes = archive.size.toLong(),
        openResident = { ByteArrayInputStream(archive) },
        oracle = { _: PlatformId, fingerprint: RomFingerprint ->
            matches[fingerprint.datHash.sha1]
        },
        nanoTime = { 0L },
    )

    private fun budget() = ChdVerificationBudget.forResolve(nanoTime = { 0L })

    private suspend fun assertLegacyZipRetryable(
        archive: ByteArray,
        platformId: String = "n64",
    ) {
        val probe = ResidentHashProbe(
            ContentOpener { _, _ -> ByteArrayInputStream(archive) },
        )
        assertEquals(
            ResidentFingerprintResult.Retryable,
            probe.fingerprintEvidenceResultFor(
                platformId = platformId,
                documentUri = null,
                managedPath = null,
                fileNameOverride = "fixture.zip",
            ),
        )
    }

    private fun match(title: String) = ArchivePayloadOracleMatch(
        canonicalTitle = title,
        collapseKey = "collapse:$title",
        origin = MetadataOrigin.NO_INTRO,
    )

    private fun payload(seed: Int, size: Int = 4096): ByteArray {
        var state = (seed.toLong() shl 32) xor 0x9e3779b97f4a7c15UL.toLong()
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            state.toByte()
        }
    }

    private fun sha1(bytes: ByteArray): String? =
        FileHasher.hash(ByteArrayInputStream(bytes)).sha1

    private data class Entry(
        val name: String,
        val bytes: ByteArray,
        val stored: Boolean = false,
        val directory: Boolean = false,
    )

    private fun zipOf(vararg entries: Entry): ByteArray {
        return zipWithComment(null, *entries)
    }

    private fun zipWithComment(
        comment: String?,
        vararg entries: Entry,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            if (comment != null) zip.setComment(comment)
            entries.forEach { item ->
                val name = if (item.directory && !item.name.endsWith('/')) "${item.name}/" else item.name
                val entry = ZipEntry(name)
                if (item.stored || item.directory) {
                    val crc = CRC32().apply { update(item.bytes) }
                    entry.method = ZipEntry.STORED
                    entry.size = item.bytes.size.toLong()
                    entry.compressedSize = item.bytes.size.toLong()
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(item.bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun corruptFirstEntryByte(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        require(copy.size > 30)
        val nameLength = (copy[26].toInt() and 0xff) or ((copy[27].toInt() and 0xff) shl 8)
        val extraLength = (copy[28].toInt() and 0xff) or ((copy[29].toInt() and 0xff) shl 8)
        val dataStart = 30 + nameLength + extraLength
        copy[dataStart] = (copy[dataStart].toInt() xor 0x01).toByte()
        return copy
    }

    private fun invalidateFirstLocalName(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        require(copy.size > 30)
        val nameLength = (copy[26].toInt() and 0xff) or ((copy[27].toInt() and 0xff) shl 8)
        require(nameLength > 0)
        copy[30] = 0xff.toByte()
        return copy
    }

    private fun removeCentralDirectoryAndForgeEocd(zip: ByteArray): ByteArray {
        val eocdAt = eocdOffset(zip)
        val centralOffset =
            (zip[eocdAt + 16].toInt() and 0xff) or
                ((zip[eocdAt + 17].toInt() and 0xff) shl 8) or
                ((zip[eocdAt + 18].toInt() and 0xff) shl 16) or
                ((zip[eocdAt + 19].toInt() and 0xff) shl 24)
        val eocd = zip.copyOfRange(eocdAt, zip.size)
        repeat(4) { offset -> eocd[12 + offset] = 0 }
        eocd[16] = (centralOffset and 0xff).toByte()
        eocd[17] = ((centralOffset ushr 8) and 0xff).toByte()
        eocd[18] = ((centralOffset ushr 16) and 0xff).toByte()
        eocd[19] = ((centralOffset ushr 24) and 0xff).toByte()
        return zip.copyOfRange(0, centralOffset) + eocd
    }

    private fun mutateFirstCentralName(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val centralAt = centralDirectoryOffset(copy)
        require(copy[centralAt] == 0x50.toByte() && copy[centralAt + 2] == 0x01.toByte())
        copy[centralAt + 46] = (copy[centralAt + 46].toInt() xor 0x01).toByte()
        return copy
    }

    private fun mutateFirstCentralCrc(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val centralAt = centralDirectoryOffset(copy)
        require(copy[centralAt] == 0x50.toByte() && copy[centralAt + 2] == 0x01.toByte())
        copy[centralAt + 16] = (copy[centralAt + 16].toInt() xor 0x01).toByte()
        return copy
    }

    private fun mutateFirstCentralLocalOffset(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val centralAt = centralDirectoryOffset(copy)
        copy[centralAt + 42] = (copy[centralAt + 42].toInt() xor 0x01).toByte()
        return copy
    }

    private fun mutateFirstCentralFlags(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val centralAt = centralDirectoryOffset(copy)
        copy[centralAt + 8] = (copy[centralAt + 8].toInt() xor 0x02).toByte()
        return copy
    }

    private fun mutateFirstCentralSizeToZip64(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val centralAt = centralDirectoryOffset(copy)
        repeat(4) { offset -> copy[centralAt + 20 + offset] = 0xff.toByte() }
        return copy
    }

    private fun mutateFirstCentralDiskToZip64(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val centralAt = centralDirectoryOffset(copy)
        copy[centralAt + 34] = 0xff.toByte()
        copy[centralAt + 35] = 0xff.toByte()
        return copy
    }

    private fun duplicateNamesAndSwapCentralOffsets(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val centralRecords = centralRecordOffsets(copy)
        require(centralRecords.size == 2)
        val localOffsets = centralRecords.map { centralAt ->
            (copy[centralAt + 42].toInt() and 0xff) or
                ((copy[centralAt + 43].toInt() and 0xff) shl 8) or
                ((copy[centralAt + 44].toInt() and 0xff) shl 16) or
                ((copy[centralAt + 45].toInt() and 0xff) shl 24)
        }
        val duplicateName = "dup.z64".toByteArray()
        centralRecords.forEachIndexed { index, centralAt ->
            duplicateName.copyInto(copy, destinationOffset = centralAt + 46)
            duplicateName.copyInto(copy, destinationOffset = localOffsets[index] + 30)
        }
        writeLeU32(copy, centralRecords[0] + 42, localOffsets[1])
        writeLeU32(copy, centralRecords[1] + 42, localOffsets[0])
        return copy
    }

    private fun centralRecordOffsets(zip: ByteArray): List<Int> {
        val eocdAt = eocdOffset(zip)
        val centralEnd = eocdAt
        val offsets = mutableListOf<Int>()
        var cursor = centralDirectoryOffset(zip)
        while (cursor < centralEnd) {
            require(
                zip[cursor] == 0x50.toByte() &&
                    zip[cursor + 1] == 0x4b.toByte() &&
                    zip[cursor + 2] == 0x01.toByte() &&
                    zip[cursor + 3] == 0x02.toByte(),
            )
            offsets += cursor
            val name = (zip[cursor + 28].toInt() and 0xff) or
                ((zip[cursor + 29].toInt() and 0xff) shl 8)
            val extra = (zip[cursor + 30].toInt() and 0xff) or
                ((zip[cursor + 31].toInt() and 0xff) shl 8)
            val comment = (zip[cursor + 32].toInt() and 0xff) or
                ((zip[cursor + 33].toInt() and 0xff) shl 8)
            cursor += 46 + name + extra + comment
        }
        return offsets
    }

    private fun writeLeU32(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun centralDirectoryOffset(zip: ByteArray): Int {
        val eocdAt = eocdOffset(zip)
        return (zip[eocdAt + 16].toInt() and 0xff) or
            ((zip[eocdAt + 17].toInt() and 0xff) shl 8) or
            ((zip[eocdAt + 18].toInt() and 0xff) shl 16) or
            ((zip[eocdAt + 19].toInt() and 0xff) shl 24)
    }

    private fun eocdOffset(zip: ByteArray): Int =
        zip.indices.reversed().first { index ->
            index + 4 <= zip.size &&
                zip[index] == 0x50.toByte() &&
                zip[index + 1] == 0x4b.toByte() &&
                zip[index + 2] == 0x05.toByte() &&
                zip[index + 3] == 0x06.toByte()
        }
}
