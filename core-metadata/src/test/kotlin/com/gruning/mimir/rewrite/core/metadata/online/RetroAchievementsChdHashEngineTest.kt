package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ChdChainResident
import com.gruning.mimir.rewrite.core.metadata.ChdHeaderEvidence
import com.gruning.mimir.rewrite.core.metadata.ContainerIdentityResult
import com.gruning.mimir.rewrite.core.metadata.ContentOpener
import com.gruning.mimir.rewrite.core.metadata.NativeSeekablePath
import com.gruning.mimir.rewrite.core.metadata.RandomAccessContent
import com.gruning.mimir.rewrite.core.metadata.VerifiedChdChainNode
import com.gruning.mimir.rewrite.core.metadata.VerifiedChdChainSelection
import com.gruning.mimir.rewrite.core.metadata.VerifiedChdIdentity
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroAchievementsChdHashEngineTest {

    @Test
    fun `second pass closes holders once in reverse after native session returns`() = runBlocking {
        val events = mutableListOf<String>()
        val closes = linkedMapOf<String, Int>()
        val fixture = fixture(events, closes)
        fixture.engine.verifiedChainHasher = { consoleId, nodes, expectedIdentity, _ ->
            assertEquals(12, consoleId)
            assertEquals(listOf("native:/root.chd", "native:/child.chd"), nodes.map { it.path })
            assertEquals(fixture.selection.identity, expectedIdentity)
            events += "hash"
            events += "native-session-close"
            ContainerIdentityResult.Identified(
                VerifiedNativeChdHash(expectedIdentity, "canonical-md5"),
            )
        }

        val result = fixture.engine.hash(12, fixture.target)

        assertEquals(RetroAchievementsChdHashResult.Hashed("canonical-md5"), result)
        assertEquals(
            listOf(
                "open:/root.chd",
                "open:/child.chd",
                "hash",
                "native-session-close",
                "close:/child.chd",
                "close:/root.chd",
            ),
            events,
        )
        assertEquals(mapOf("/root.chd" to 1, "/child.chd" to 1), closes)
    }

    @Test
    fun `second pass closes holders once in reverse after exception and cancellation`() {
        listOf<Throwable>(
            IllegalStateException("native hash failed"),
            CancellationException("focus moved"),
        ).forEach { thrown ->
            val events = mutableListOf<String>()
            val closes = linkedMapOf<String, Int>()
            val fixture = fixture(events, closes)
            fixture.engine.verifiedChainHasher = { _, _, _, _ ->
                events += "hash"
                throw thrown
            }

            val failure = runCatching {
                runBlocking { fixture.engine.hash(12, fixture.target) }
            }.exceptionOrNull()

            assertTrue(failure === thrown)
            assertEquals(
                listOf(
                    "open:/root.chd",
                    "open:/child.chd",
                    "hash",
                    "close:/child.chd",
                    "close:/root.chd",
                ),
                events,
            )
            assertEquals(mapOf("/root.chd" to 1, "/child.chd" to 1), closes)
        }
    }

    @Test
    fun `second pass receives the settled identity and rejects changed verification`() = runBlocking {
        val events = mutableListOf<String>()
        val closes = linkedMapOf<String, Int>()
        val fixture = fixture(events, closes)
        var expectedSeen: VerifiedChdIdentity? = null
        fixture.engine.verifiedChainHasher = { _, _, expectedIdentity, _ ->
            expectedSeen = expectedIdentity
            ContainerIdentityResult.Identified(
                VerifiedNativeChdHash(
                    identity = expectedIdentity.copy(combinedSha1 = "f".repeat(40)),
                    md5 = "must-not-publish",
                ),
            )
        }

        val result = fixture.engine.hash(12, fixture.target)

        assertEquals(fixture.selection.identity, expectedSeen)
        assertEquals(RetroAchievementsChdHashResult.FileUnavailable, result)
        assertEquals(mapOf("/root.chd" to 1, "/child.chd" to 1), closes)
    }

    private fun fixture(
        events: MutableList<String>,
        closes: MutableMap<String, Int>,
    ): Fixture {
        val rootHeader = header(ROOT_DIGEST, null)
        val childHeader = header(CHILD_DIGEST, ROOT_DIGEST)
        val root = resident("root", "/root.chd")
        val child = resident("child", "/child.chd")
        val selection = VerifiedChdChainSelection(
            identity = VerifiedChdIdentity(
                version = childHeader.version,
                logicalBytes = childHeader.logicalBytes,
                rawSha1 = childHeader.rawSha1,
                combinedSha1 = childHeader.combinedSha1,
                chainDepth = 2,
                compressionTags = childHeader.compressionTags,
            ),
            nodesRootFirst = listOf(
                VerifiedChdChainNode(root, rootHeader),
                VerifiedChdChainNode(child, childHeader),
            ),
        )
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): InputStream? =
                error("CHD engine must use seekable seams")

            override fun openRandomAccess(
                documentUri: String?,
                managedPath: String?,
            ): RandomAccessContent {
                assertEquals("/child.chd", managedPath)
                return ByteArrayRandomAccess(headerBytes(childHeader))
            }

            override fun openNativeSeekablePath(
                documentUri: String?,
                managedPath: String?,
            ): NativeSeekablePath {
                val residentPath = requireNotNull(managedPath)
                events += "open:$residentPath"
                return object : NativeSeekablePath {
                    override val path: String = "native:$residentPath"

                    override fun close() {
                        closes[residentPath] = (closes[residentPath] ?: 0) + 1
                        events += "close:$residentPath"
                    }
                }
            }
        }
        val engine = RetroAchievementsChdHashEngine(
            residentInventory = { error("injected discovery must not enumerate again") },
            opener = opener,
        )
        engine.verifiedChainDiscovery = { _, target, parsedHeader, _ ->
            assertEquals(child, target)
            assertEquals(childHeader, parsedHeader)
            ContainerIdentityResult.Identified(selection)
        }
        return Fixture(
            engine = engine,
            target = RetroAchievementsChdResident(
                entryKey = child.entryKey,
                sourceRootUri = child.sourceRootUri,
                relativePath = child.relativePath,
                documentUri = child.documentUri,
                managedPath = child.managedPath,
            ),
            selection = selection,
        )
    }

    private fun resident(name: String, path: String) = ChdChainResident(
        entryKey = "v11:$name",
        sourceRootUri = "content://root",
        relativePath = "$name.chd",
        documentUri = null,
        managedPath = path,
    )

    private fun header(combined: String, parent: String?) = ChdHeaderEvidence(
        version = 5,
        logicalBytes = 4096L,
        hunkBytes = 4096,
        rawSha1 = combined,
        combinedSha1 = combined,
        parentSha1 = parent,
        compressionTags = listOf(0, 0, 0, 0),
        declaredHunkCount = 1L,
    )

    private fun headerBytes(header: ChdHeaderEvidence): ByteArray = ByteArray(132).also { bytes ->
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes.putBe32(8, 124)
        bytes.putBe32(12, header.version)
        bytes.putBe64(32, header.logicalBytes)
        bytes.putBe32(56, header.hunkBytes)
        bytes.putBe32(60, 512)
        hex(header.rawSha1).copyInto(bytes, 64)
        hex(header.combinedSha1).copyInto(bytes, 84)
        header.parentSha1?.let { hex(it).copyInto(bytes, 104) }
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.putBe32(at: Int, value: Int) {
        repeat(4) { index -> this[at + index] = (value ushr ((3 - index) * 8)).toByte() }
    }

    private fun ByteArray.putBe64(at: Int, value: Long) {
        repeat(8) { index -> this[at + index] = (value ushr ((7 - index) * 8)).toByte() }
    }

    private data class Fixture(
        val engine: RetroAchievementsChdHashEngine,
        val target: RetroAchievementsChdResident,
        val selection: VerifiedChdChainSelection,
    )

    private class ByteArrayRandomAccess(
        private val bytes: ByteArray,
    ) : RandomAccessContent {
        override val length: Long = bytes.size.toLong()

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset !in 0 until length) return 0
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            return count
        }

        override fun close() = Unit
    }

    private companion object {
        const val ROOT_DIGEST = "1111111111111111111111111111111111111111"
        const val CHILD_DIGEST = "2222222222222222222222222222222222222222"
    }
}
