package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ContentOpener
import com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroAchievementsM3uChdTargetResolverTest {

    @Test
    fun `fresh playlist edit supersedes stale persisted disc order`() = runBlocking {
        var body = "disc-1.chd\n"
        val resolver = resolver { tracked(body) }
        val disc1 = resident("disc-1", "sets/disc-1.chd")
        val disc2 = resident("disc-2", "sets/disc-2.chd")

        assertEquals(
            RetroAchievementsM3uChdResolution.Selected(disc1),
            resolver.resolve(playlist(), listOf(disc1, disc2)),
        )
        body = "disc-2.chd\n"
        assertEquals(
            RetroAchievementsM3uChdResolution.Selected(disc2),
            resolver.resolve(playlist(), listOf(disc1, disc2)),
        )
    }

    @Test
    fun `missing first target never falls through to the second entry`() = runBlocking {
        val resolver = resolver { tracked("missing.chd\ndisc-2.chd\n") }
        val disc2 = resident("disc-2", "sets/disc-2.chd")

        assertEquals(
            RetroAchievementsM3uChdResolution.Unavailable,
            resolver.resolve(playlist(), listOf(disc2)),
        )
    }

    @Test
    fun `non CHD first target preserves the existing native playlist route`() = runBlocking {
        val resolver = resolver { tracked("disc-1.cue\ndisc-2.chd\n") }

        assertEquals(
            RetroAchievementsM3uChdResolution.NotChdTarget,
            resolver.resolve(playlist(), listOf(resident("disc-2", "sets/disc-2.chd"))),
        )
    }

    @Test
    fun `relative content file and absolute entries map only exact current residents`() = runBlocking {
        val relative = resident("relative", "sets/disc one.chd")
        val content = resident(
            name = "content",
            relativePath = "opaque/content.chd",
            documentUri = "content://roms/tree/disc.chd",
        )
        val file = resident(
            name = "file",
            relativePath = "opaque/file.chd",
            managedPath = "/storage/roms/file.chd",
        )
        val absolute = resident(
            name = "absolute",
            relativePath = "opaque/absolute.chd",
            managedPath = "/storage/roms/absolute.chd",
        )
        val candidates = listOf(relative, content, file, absolute)
        val cases = listOf(
            "\"disc one.chd\"\n" to relative,
            "content://roms/tree/disc.chd\n" to content,
            "file:///storage/roms/file.chd\n" to file,
            "/storage/roms/absolute.chd\n" to absolute,
        )

        cases.forEach { (body, expected) ->
            assertEquals(
                RetroAchievementsM3uChdResolution.Selected(expected),
                resolver { tracked(body) }.resolve(playlist(), candidates),
            )
        }
    }

    @Test
    fun `relative target cannot escape its source root or choose an ambiguous resident`() = runBlocking {
        val samePathA = resident("a", "sets/disc.chd")
        val samePathB = resident("b", "sets/disc.chd")
        val otherRoot = resident("other", "sets/disc.chd", sourceRoot = "content://other")

        assertEquals(
            RetroAchievementsM3uChdResolution.Unavailable,
            resolver { tracked("disc.chd\n") }
                .resolve(playlist(), listOf(samePathA, samePathB)),
        )
        assertEquals(
            RetroAchievementsM3uChdResolution.Unavailable,
            resolver { tracked("disc.chd\n") }
                .resolve(playlist(), listOf(otherRoot)),
        )
        assertEquals(
            RetroAchievementsM3uChdResolution.Unavailable,
            resolver { tracked("../../outside.chd\n") }
                .resolve(playlist(), listOf(resident("outside", "outside.chd"))),
        )
    }

    @Test
    fun `successful target selection closes the playlist before returning`() = runBlocking {
        var closed = 0
        val disc = resident("disc", "sets/disc.chd")
        val resolver = resolver {
            object : ByteArrayInputStream("disc.chd\n".toByteArray()) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        }

        assertEquals(
            RetroAchievementsM3uChdResolution.Selected(disc),
            resolver.resolve(playlist(), listOf(disc)),
        )
        assertEquals(1, closed)
    }

    @Test
    fun `nested playlist is refused without considering resident candidates`() = runBlocking {
        val resolver = resolver { tracked("nested.m3u\ndisc.chd\n") }

        assertEquals(
            RetroAchievementsM3uChdResolution.Unavailable,
            resolver.resolve(playlist(), listOf(resident("disc", "sets/disc.chd"))),
        )
    }

    @Test
    fun `ordinary close failure becomes unavailable`() = runBlocking {
        val resolver = resolver {
            object : ByteArrayInputStream("disc.chd\n".toByteArray()) {
                override fun close() {
                    throw IllegalStateException("provider close failed")
                }
            }
        }

        assertEquals(
            RetroAchievementsM3uChdResolution.Unavailable,
            resolver.resolve(playlist(), listOf(resident("disc", "sets/disc.chd"))),
        )
    }

    @Test
    fun `oversized playlist closes and refuses before target selection`() = runBlocking {
        var closed = 0
        val bytes = ByteArray(M3uPlaylistResolver.MAX_PLAYLIST_BYTES.toInt() + 1) { 'a'.code.toByte() }
        ".chd".toByteArray().copyInto(bytes, bytes.lastIndex - 3)
        val resolver = resolver {
            object : ByteArrayInputStream(bytes) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        }

        assertEquals(
            RetroAchievementsM3uChdResolution.Unavailable,
            resolver.resolve(playlist(), listOf(resident("a", "sets/a.chd"))),
        )
        assertEquals(1, closed)
    }

    @Test
    fun `playlist read cancellation propagates and closes once`() {
        var closed = 0
        val resolver = resolver {
            object : InputStream() {
                override fun read(): Int = throw CancellationException("focus moved")

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    throw CancellationException("focus moved")

                override fun close() {
                    closed++
                }
            }
        }

        val failure = runCatching {
            runBlocking { resolver.resolve(playlist(), emptyList()) }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, closed)
    }

    private fun resolver(stream: () -> InputStream) =
        RetroAchievementsM3uChdTargetResolver(
            ContentOpener { _, _ -> stream() },
        )

    private fun tracked(body: String) = ByteArrayInputStream(body.toByteArray())

    private fun playlist() = RetroAchievementsM3uResident(
        entryKey = "playlist",
        sourceRootUri = "content://root",
        relativePath = "sets/game.m3u",
        documentUri = "content://root/sets/game.m3u",
        managedPath = null,
        sizeBytes = 64L,
    )

    private fun resident(
        name: String,
        relativePath: String,
        sourceRoot: String = "content://root",
        documentUri: String? = "content://root/$relativePath",
        managedPath: String? = null,
    ) = RetroAchievementsChdResident(
        entryKey = name,
        sourceRootUri = sourceRoot,
        relativePath = relativePath,
        documentUri = documentUri,
        managedPath = managedPath,
        sizeBytes = 4096L,
    )
}
