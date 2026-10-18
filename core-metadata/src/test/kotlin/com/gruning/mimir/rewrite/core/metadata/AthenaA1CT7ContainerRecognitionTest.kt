package com.gruning.mimir.rewrite.core.metadata

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in A1C replay over frozen private container shapes. Output is restricted to externally assigned opaque IDs,
 * container classes, typed outcomes and read counts; names, paths, hashes and titles never leave the test.
 */
class AthenaA1CT7ContainerRecognitionTest {

    @Test
    fun `A1C readers replay the frozen container matrix without mutating it`() = runBlocking {
        val rootRaw = System.getenv(ROOT_ENV).orEmpty()
        val manifestRaw = System.getenv(MANIFEST_ENV).orEmpty()
        val assetRootRaw = System.getenv(ASSET_ROOT_ENV).orEmpty()
        assumeTrue(
            "Set $ROOT_ENV, $MANIFEST_ENV and $ASSET_ROOT_ENV for the private T7 exercise",
            rootRaw.isNotBlank() && manifestRaw.isNotBlank() && assetRootRaw.isNotBlank(),
        )

        val root = Path.of(rootRaw).toRealPath()
        val assetRoot = Path.of(assetRootRaw).toRealPath()
        val fixtures = parseManifest(Path.of(manifestRaw).toRealPath())
            .filter { it.container in CONTAINER_CLASSES }
        assertEquals(8, fixtures.size)
        val provider = DatAssetProvider { name ->
            safeAssetPath(assetRoot, name)?.let { Files.newInputStream(it, StandardOpenOption.READ) }
        }
        val mame = MamePayloadCatalog(provider)
        val idSource = BundledIdSource(provider)

        val results = fixtures.map { fixture ->
            val path = root.resolve(fixture.relativePath).normalize()
            require(path.startsWith(root) && path.isRegularFile(LinkOption.NOFOLLOW_LINKS))
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val result = exercise(fixture, path, mame, idSource)
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            assertEquals("${fixture.id}: size changed", before.size(), after.size())
            assertEquals("${fixture.id}: mtime changed", before.lastModifiedTime(), after.lastModifiedTime())
            assertEquals("${fixture.id}: file identity changed", before.fileKey(), after.fileKey())
            result
        }.sortedBy(Result::id)

        println("ATHENA_A1C_T7_CONTAINER_SCORECARD=${canonicalJson(results)}")
        assertEquals(1, results.count { it.outcome == "MAME_MACHINE_PAYLOAD_IDENTIFIED" })
        assertEquals(1, results.count { it.outcome == "MAME_MEMBER_ONLY" })
        assertEquals(2, results.count { it.outcome == "SWITCH_TICKET_CATALOG_HINT" })
        assertEquals(2, results.count { it.outcome == "CHD_HEADER_PROVISIONAL_HOST" })
        assertEquals(2, results.count { it.outcome == "ZIPPED_OPTICAL_UNSUPPORTED" })
        assertTrue(results.filter { it.outcome == "ZIPPED_OPTICAL_UNSUPPORTED" }.all { it.bytesRead == 0L })
    }

    private suspend fun exercise(
        fixture: Fixture,
        path: Path,
        mame: MamePayloadCatalog,
        idSource: BundledIdSource,
    ): Result {
        var bytesRead = 0L
        val outcome = when (fixture.container) {
            "MAME_ZIP" -> {
                val counted = CountingInputStream(Files.newInputStream(path, StandardOpenOption.READ))
                val result = MameZipIdentityReader.identify(counted, mame)
                bytesRead = counted.bytesRead
                if (result is ContainerIdentityResult.Identified) {
                    "MAME_MACHINE_PAYLOAD_IDENTIFIED"
                } else {
                    "MAME_SET_${result.reasonName()}"
                }
            }

            "MAME_MEMBER" -> {
                val counted = CountingInputStream(Files.newInputStream(path, StandardOpenOption.READ))
                val result = MameLooseMemberReader.classify(counted, mame)
                bytesRead = counted.bytesRead
                if (
                    result == ContainerIdentityResult.PolicyAbstain(
                        ContainerIdentityReason.MEMBER_ONLY,
                    )
                ) {
                    "MAME_MEMBER_ONLY"
                } else {
                    "MAME_MEMBER_${result.reasonName()}"
                }
            }

            "CHD" -> {
                val counted = CountingInputStream(Files.newInputStream(path, StandardOpenOption.READ))
                var headLength = 0
                val head = counted.use { input ->
                    ByteArray(CHD_HEAD_BYTES).also { bytes ->
                        while (headLength < bytes.size) {
                            val read = input.read(bytes, headLength, bytes.size - headLength)
                            if (read < 0) break
                            if (read > 0) headLength += read
                        }
                    }
                }
                bytesRead = counted.bytesRead
                val header = RomFingerprinter.chdHeaderEvidence(head, headLength)
                    ?: return Result(fixture.id, fixture.container, "CHD_MALFORMED", bytesRead)
                // A host JVM deliberately has no Android libchdr bridge. Header/catalog data stays
                // provisional; exact raw-payload authority is earned only by connected-device replay.
                mame.classifyDisk(header.combinedSha1)
                "CHD_HEADER_PROVISIONAL_HOST"
            }

            "NSP_BASE", "NSP_UPDATE" -> {
                val resident = requireNotNull(
                    ContentOpener { _, _ -> null }.openRandomAccess(null, path.toString()),
                )
                val counted = CountingRandomAccessContent(resident)
                val parsed = counted.use { SwitchPfs0IdentityReader.identify(it) }
                bytesRead = counted.bytesRead
                val payload = (parsed as? ContainerIdentityResult.Identified)?.value
                    ?: return Result(
                        fixture.id,
                        fixture.container,
                        "SWITCH_${parsed.reasonName()}",
                        bytesRead,
                    )
                val catalog = idSource.resolveEmbedded(
                    "switch",
                    ExtractedId(IdKeyType.TITLE_ID, payload.baseTitleId),
                )
                val expectedRole = if (fixture.container == "NSP_BASE") {
                    SwitchPackageRole.BASE
                } else {
                    SwitchPackageRole.UPDATE
                }
                if (payload.role == expectedRole && catalog is BundledIdResolution.Identified) {
                    "SWITCH_TICKET_CATALOG_HINT"
                } else {
                    "SWITCH_ROLE_OR_CATALOG_MISMATCH"
                }
            }

            "DISC_ZIP" -> {
                require(
                    ContainerRoutingPolicy.route(fixture.platform, path.fileName.toString()) ==
                        ContainerRoutingPolicy.Route.OPTICAL_ZIP_UNSUPPORTED,
                )
                "ZIPPED_OPTICAL_UNSUPPORTED"
            }

            else -> error("unrouted container ${fixture.container}")
        }
        return Result(fixture.id, fixture.container, outcome, bytesRead)
    }

    private fun ContainerIdentityResult<*>.reasonName(): String = when (this) {
        is ContainerIdentityResult.Identified -> "IDENTIFIED"
        is ContainerIdentityResult.PolicyAbstain -> reason.name
        is ContainerIdentityResult.NeedsReview -> reason.name
        is ContainerIdentityResult.RetryableFailure -> reason.name
    }

    private fun parseManifest(path: Path): List<Fixture> {
        require(Files.size(path) <= MAX_MANIFEST_BYTES)
        return Files.readAllLines(path).mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val fields = line.split('\t', limit = 5)
            require(fields.size == 5)
            require(ID.matches(fields[0]))
            require(CONTAINER.matches(fields[3]))
            Fixture(fields[0], fields[1], fields[3], fields[4])
        }
    }

    private fun safeAssetPath(root: Path, assetName: String): Path? {
        if (!ASSET_NAME.matches(assetName)) return null
        val candidate = root.resolve(assetName).normalize()
        return candidate.takeIf {
            it.startsWith(root) && it.isRegularFile(LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun canonicalJson(results: List<Result>): String =
        "{\"schema\":\"mimir.athena.a1c.t7-container.v1\",\"fixtureCount\":${results.size}," +
            "\"results\":[" +
            results.joinToString(",") {
                "{\"id\":\"${it.id}\",\"container\":\"${it.container}\"," +
                    "\"outcome\":\"${it.outcome}\",\"bytesRead\":${it.bytesRead}}"
            } +
            "]}"

    private data class Fixture(
        val id: String,
        val platform: String,
        val container: String,
        val relativePath: String,
    )

    private data class Result(
        val id: String,
        val container: String,
        val outcome: String,
        val bytesRead: Long,
    )

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, length).also { if (it > 0) bytesRead += it }
    }

    private class CountingRandomAccessContent(
        private val delegate: RandomAccessContent,
    ) : RandomAccessContent by delegate {
        var bytesRead: Long = 0L
            private set

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int = delegate.readAt(offset, destination, destinationOffset, byteCount).also {
            if (it > 0) bytesRead += it
        }
    }

    private companion object {
        const val ROOT_ENV = "MIMIR_ATHENA_T7_ROOT"
        const val MANIFEST_ENV = "MIMIR_ATHENA_T7_MANIFEST"
        const val ASSET_ROOT_ENV = "MIMIR_ATHENA_ASSET_ROOT"
        const val CHD_HEAD_BYTES = 132
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        val CONTAINER_CLASSES = setOf(
            "MAME_ZIP",
            "MAME_MEMBER",
            "CHD",
            "NSP_BASE",
            "NSP_UPDATE",
            "DISC_ZIP",
        )
        val ID = Regex("[a-z0-9][a-z0-9-]{0,47}")
        val CONTAINER = Regex("[A-Z0-9_]{1,32}")
        val ASSET_NAME = Regex("[a-z0-9_.-]{1,96}")
    }
}
