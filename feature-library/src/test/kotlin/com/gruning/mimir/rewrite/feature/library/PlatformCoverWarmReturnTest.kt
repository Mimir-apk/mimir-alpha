package com.gruning.mimir.rewrite.feature.library

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R8a on the platform hero's WARM return: a cover that decoded once is painted from
 * memory on frame 0 with no stat, so deleting the file left stale art for as long as the cache held it. The warm branch now verifies
 * the file off the main thread, as the Bottom does for a warm icon: a file that is gone is evicted and the tile takes the normal
 * no-art default; a file that is there stands untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlatformCoverWarmReturnTest {

    private val cover = "file:///storage/emulated/0/media_art/snes/cover.png"

    @Test
    fun `a warm cover whose file is gone is evicted and reported gone`() = runBlocking {
        val evicted = mutableListOf<String>()
        val gone = platformCoverWarmGone(cover, wired = { false }, evict = { evicted += it })
        assertTrue("the file is gone, so the tile must fall back", gone)
        assertEquals("and the stale bitmap leaves the cache so no later mount paints it", listOf(cover), evicted)
    }

    @Test
    fun `a warm cover whose file is there stands and nothing is evicted`() = runBlocking {
        val evicted = mutableListOf<String>()
        val gone = platformCoverWarmGone(cover, wired = { true }, evict = { evicted += it })
        assertFalse(gone)
        assertEquals(emptyList<String>(), evicted)
    }

    @Test
    fun `the check names the file path of the uri and runs off the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        var asked: String? = null
        var statThread: Thread? = null
        platformCoverWarmGone(
            cover,
            wired = { path -> asked = path; statThread = Thread.currentThread(); true },
            evict = {},
        )
        assertEquals("/storage/emulated/0/media_art/snes/cover.png", asked)
        assertNotEquals("a disk stat never runs on the composing thread", caller, statThread)
    }

    @Test
    fun `the tile's warm branch runs the check and lands on the missing state, pinned in source`() {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !(File(dir, "settings.gradle.kts").isFile && File(dir, "core-ui").isDirectory)) dir = dir.parentFile
        val text = File(dir ?: error("repository root not found"), "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/PlatformHeroTile.kt")
            .readText()
        val warm = text.substringAfter("cachedCover?.let {", missingDelimiterValue = "").substringBefore("return@produceState")
        assertTrue("the warm branch anchor is present", warm.isNotBlank())
        assertTrue(
            "a warm return verifies its file and shows the default when it is gone: $warm",
            warm.contains("if (platformCoverWarmGone(override)) value = PlatformCoverDecodeState.Missing(override)"),
        )
    }
}
