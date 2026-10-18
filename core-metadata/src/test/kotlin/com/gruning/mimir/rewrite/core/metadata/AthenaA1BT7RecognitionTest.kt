package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, privacy-preserving real-corpus exercise.
 *
 * The private TSV and corpus root are external inputs. Committed output contains only caller-assigned opaque
 * ids, platform/container classes, read cost and typed dispositions — never paths, names or content hashes.
 */
class AthenaA1BT7RecognitionTest {

    @Test
    fun `production readers exercise the external T7 recognition matrix without mutation`() = runBlocking {
        val rootRaw = System.getenv(ROOT_ENV).orEmpty()
        val manifestRaw = System.getenv(MANIFEST_ENV).orEmpty()
        val assetRootRaw = System.getenv(ASSET_ROOT_ENV).orEmpty()
        assumeTrue(
            "Set $ROOT_ENV, $MANIFEST_ENV and $ASSET_ROOT_ENV for the private T7 exercise",
            rootRaw.isNotBlank() && manifestRaw.isNotBlank() && assetRootRaw.isNotBlank(),
        )

        val root = Path.of(rootRaw).toRealPath()
        val manifest = Path.of(manifestRaw).toRealPath()
        val assetRoot = Path.of(assetRootRaw).toRealPath()
        val fixtures = parseManifest(manifest)
        val datSource = BundledDatSource(filePackedProvider(assetRoot))
        val idSource = BundledIdSource(fileIdProvider(assetRoot))

        val results = fixtures.map { fixture ->
            val path = root.resolve(fixture.relativePath).normalize()
            require(path.startsWith(root)) { "${fixture.id}: path escapes corpus root" }
            require(path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "${fixture.id}: not a regular file" }
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val result = exercise(fixture, path, datSource, idSource)
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            assertEquals("${fixture.id}: size changed", before.size(), after.size())
            assertEquals("${fixture.id}: mtime changed", before.lastModifiedTime(), after.lastModifiedTime())
            assertEquals("${fixture.id}: file identity changed", before.fileKey(), after.fileKey())
            result
        }

        assertEquals(fixtures.size, results.size)
        assertTrue(results.any { it.disposition == "IDENTIFIED" })
        assertTrue(results.any { it.evidence == "FILENAME_CATALOG_KEY_CANDIDATE" })
        assertTrue(results.any { it.readerOutcome == "FINGERPRINTED_NO_CATALOG_HIT" })
        println("ATHENA_A1B_T7_SCORECARD_JSON=${canonicalJson(results)}")
    }

    private suspend fun exercise(
        fixture: Fixture,
        path: Path,
        datSource: BundledDatSource,
        idSource: BundledIdSource,
    ): Result {
        val started = System.nanoTime()
        var bytesRead = 0L
        val candidates: List<AthenaIdentityCandidate>
        val readerOutcome: String
        when (fixture.mode) {
            Mode.KEYED_NAME -> {
                val resolution = idSource.resolve(
                    fixture.platform,
                    path.fileName.toString(),
                    null,
                    0,
                )
                candidates = resolution.toAthenaCandidates()
                readerOutcome = resolution.outcomeName()
            }

            Mode.KEYED_HEAD -> {
                val counter = CountingInputStream(Files.newInputStream(path, StandardOpenOption.READ))
                val (head, length) = counter.use { readHead(it, keyHeadBytes(fixture.platform)) }
                bytesRead = counter.bytesRead
                val resolution = idSource.resolve(
                    fixture.platform,
                    path.fileName.toString(),
                    head,
                    length,
                )
                candidates = resolution.toAthenaCandidates()
                readerOutcome = resolution.outcomeName()
            }

            Mode.FINGERPRINT -> {
                val counter = CountingInputStream(Files.newInputStream(path, StandardOpenOption.READ))
                val fingerprint = RomFingerprinter.fingerprintWithProvenance(
                    fixture.platform,
                    path.fileName.toString(),
                    counter,
                )
                bytesRead = counter.bytesRead
                val record = fingerprint?.let {
                    datSource.lookup(MetadataQuery(PlatformId(fixture.platform), contentHash = it.datHash))
                }
                candidates = if (fingerprint != null && record != null) {
                    listOf(
                        AthenaIdentityCandidate(
                            canonicalTitle = record.canonicalTitle,
                            collapseKey = datSource.collapseKeyFor(PlatformId(fixture.platform), fingerprint.datHash),
                            origin = record.origin,
                            evidence = if (fingerprint.proof == RomFingerprintProof.CHD_HEADER_CLAIM) {
                                AthenaIdentityEvidence.CHD_HEADER_SHA1_CANDIDATE
                            } else {
                                AthenaIdentityEvidence.CONTENT_SHA1
                            },
                        ),
                    )
                } else {
                    emptyList()
                }
                readerOutcome = when {
                    fingerprint == null -> "UNREADABLE_OR_AMBIGUOUS_CONTAINER"
                    record == null -> "FINGERPRINTED_NO_CATALOG_HIT"
                    else -> "FINGERPRINTED_CATALOG_HIT"
                }
            }

            Mode.POLICY_SKIP -> {
                candidates = emptyList()
                readerOutcome = "POLICY_SKIPPED"
            }
        }

        val decision = AthenaIdentityEngine().decide(candidates)
        return Result(
            id = fixture.id,
            platform = fixture.platform,
            container = fixture.container,
            mode = fixture.mode.name,
            readerOutcome = readerOutcome,
            disposition = when (decision) {
                is AthenaIdentityDecision.Identified -> "IDENTIFIED"
                is AthenaIdentityDecision.Abstained -> "ABSTAINED"
                is AthenaIdentityDecision.NeedsReview -> "NEEDS_REVIEW"
            },
            reason = when (decision) {
                is AthenaIdentityDecision.Identified -> "NONE"
                is AthenaIdentityDecision.Abstained -> decision.reason.name
                is AthenaIdentityDecision.NeedsReview -> decision.reason.name
            },
            evidence = candidates.firstOrNull()?.evidence?.name ?: "NONE",
            bytesRead = bytesRead,
            elapsedNanos = System.nanoTime() - started,
        )
    }

    private fun BundledIdResolution.toAthenaCandidates(): List<AthenaIdentityCandidate> = when (this) {
        is BundledIdResolution.Identified -> listOf(match.toAthenaCandidate())
        is BundledIdResolution.Conflict -> listOf(
            embedded.toAthenaCandidate(),
            filename.toAthenaCandidate(),
        )
        BundledIdResolution.NoMatch -> emptyList()
    }

    private fun BundledIdMatch.toAthenaCandidate() = AthenaIdentityCandidate(
        canonicalTitle = record.canonicalTitle,
        collapseKey = null,
        origin = record.origin,
        evidence = when (binding) {
            BundledIdBinding.EMBEDDED_HEADER -> AthenaIdentityEvidence.EMBEDDED_CATALOG_KEY
            BundledIdBinding.FILENAME -> AthenaIdentityEvidence.FILENAME_CATALOG_KEY_CANDIDATE
        },
    )

    private fun BundledIdResolution.outcomeName(): String = when (this) {
        is BundledIdResolution.Identified -> "KEYED_${match.binding.name}"
        is BundledIdResolution.Conflict -> "KEYED_CONFLICT"
        BundledIdResolution.NoMatch -> "KEYED_NO_MATCH"
    }

    private fun parseManifest(path: Path): List<Fixture> {
        require(Files.size(path) <= MAX_MANIFEST_BYTES) { "private manifest exceeds byte limit" }
        val fixtures = Files.readAllLines(path).mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val fields = line.split('\t', limit = 5)
            require(fields.size == 5) { "manifest line ${index + 1}: expected five TSV fields" }
            val (id, platform, mode, container, relativePath) = fields
            require(ID.matches(id)) { "manifest line ${index + 1}: invalid opaque id" }
            require(PLATFORM.matches(platform)) { "$id: invalid platform" }
            require(CONTAINER.matches(container)) { "$id: invalid container class" }
            require(relativePath.isNotBlank()) { "$id: blank relative path" }
            Fixture(id, platform, Mode.valueOf(mode), container, relativePath)
        }
        require(fixtures.isNotEmpty()) { "private manifest is empty" }
        require(fixtures.size <= MAX_FIXTURES) { "private manifest exceeds fixture limit" }
        require(fixtures.map(Fixture::id).distinct().size == fixtures.size) { "duplicate opaque fixture id" }
        return fixtures
    }

    private fun filePackedProvider(assetRoot: Path) = PackedDatAssetProvider { assetName ->
        val path = safeAssetPath(assetRoot, assetName) ?: return@PackedDatAssetProvider null
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    private fun fileIdProvider(assetRoot: Path) = DatAssetProvider { assetName ->
        safeAssetPath(assetRoot, assetName)?.let { Files.newInputStream(it, StandardOpenOption.READ) }
    }

    private fun safeAssetPath(root: Path, assetName: String): Path? {
        if (!ASSET_NAME.matches(assetName)) return null
        val candidate = root.resolve(assetName).normalize()
        if (!candidate.startsWith(root) || !candidate.isRegularFile(LinkOption.NOFOLLOW_LINKS)) return null
        return candidate
    }

    private fun readHead(input: InputStream, bytes: Int): Pair<ByteArray, Int> {
        val head = ByteArray(bytes)
        var length = 0
        while (length < head.size) {
            val read = input.read(head, length, head.size - length)
            if (read < 0) break
            if (read == 0) continue
            length += read
        }
        return head to length
    }

    private fun keyHeadBytes(platform: String): Int =
        if (platform == "ps3") PS3_ISO_VOLUME_ID_END else KEY_HEAD_BYTES

    private fun canonicalJson(results: List<Result>): String = buildString {
        append("{\"schema\":\"mimir.athena.a1b.t7-scorecard.v1\",")
        append("\"resultDigest\":\"").append(resultDigest(results)).append("\",")
        append("\"fixtureCount\":").append(results.size).append(',')
        append("\"identified\":").append(results.count { it.disposition == "IDENTIFIED" }).append(',')
        append("\"abstained\":").append(results.count { it.disposition == "ABSTAINED" }).append(',')
        append("\"needsReview\":").append(results.count { it.disposition == "NEEDS_REVIEW" }).append(',')
        append("\"totalBytesRead\":").append(results.sumOf(Result::bytesRead)).append(',')
        append("\"results\":[")
        append(
            results.sortedBy(Result::id).joinToString(",") { result ->
                "{" +
                    "\"id\":\"${result.id}\"," +
                    "\"platform\":\"${result.platform}\"," +
                    "\"container\":\"${result.container}\"," +
                    "\"mode\":\"${result.mode}\"," +
                    "\"readerOutcome\":\"${result.readerOutcome}\"," +
                    "\"disposition\":\"${result.disposition}\"," +
                    "\"reason\":\"${result.reason}\"," +
                    "\"evidence\":\"${result.evidence}\"," +
                    "\"bytesRead\":${result.bytesRead}," +
                    "\"elapsedNanos\":${result.elapsedNanos}" +
                    "}"
            },
        )
        append("]}")
    }

    private fun resultDigest(results: List<Result>): String {
        val canonical = results.sortedBy(Result::id).joinToString("\n") { result ->
            listOf(
                result.id,
                result.platform,
                result.container,
                result.mode,
                result.readerOutcome,
                result.disposition,
                result.reason,
                result.evidence,
                result.bytesRead.toString(),
            ).joinToString("\t")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private enum class Mode {
        KEYED_NAME,
        KEYED_HEAD,
        FINGERPRINT,
        POLICY_SKIP,
    }

    private data class Fixture(
        val id: String,
        val platform: String,
        val mode: Mode,
        val container: String,
        val relativePath: String,
    )

    private data class Result(
        val id: String,
        val platform: String,
        val container: String,
        val mode: String,
        val readerOutcome: String,
        val disposition: String,
        val reason: String,
        val evidence: String,
        val bytesRead: Long,
        val elapsedNanos: Long,
    )

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, length).also { if (it > 0) bytesRead += it }
    }

    private companion object {
        const val ROOT_ENV = "MIMIR_ATHENA_T7_ROOT"
        const val MANIFEST_ENV = "MIMIR_ATHENA_T7_MANIFEST"
        const val ASSET_ROOT_ENV = "MIMIR_ATHENA_ASSET_ROOT"
        const val KEY_HEAD_BYTES = 512
        const val PS3_ISO_VOLUME_ID_END = 16 * 2_048 + 40 + 32
        const val MAX_FIXTURES = 64
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        val ID = Regex("[a-z0-9][a-z0-9-]{0,47}")
        val PLATFORM = Regex("[a-z0-9][a-z0-9_-]{0,47}")
        val CONTAINER = Regex("[A-Z0-9_]{1,32}")
        val ASSET_NAME = Regex("[a-z0-9_.-]{1,96}")
    }
}
