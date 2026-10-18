package com.gruning.mimir.rewrite.core.ui

import coil.size.Precision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * One cover request at INEXACT precision. The warm passes resolved
 * the fixed cap exactly, so small covers were upscaled to about 1024 px in the cache under the painters' key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirCoverRequestTest {

    @Test
    fun `the cover request is inexact, at the shared cap and key, with no crossfade`() {
        val request = mimirCoverRequest(RuntimeEnvironment.getApplication(), "content://art/1")
        assertEquals(Precision.INEXACT, request.precision)
        assertEquals(mimirCoverCacheKey("content://art/1"), request.memoryCacheKey?.key)
        assertEquals(mimirCoverCacheKey("content://art/1"), request.placeholderMemoryCacheKey?.key)
    }

    @Test
    fun `an icon file keeps its path as the identity`() {
        val request = mimirCoverRequest(RuntimeEnvironment.getApplication(), File("/x/icon.png"), "/x/icon.png")
        assertEquals(File("/x/icon.png"), request.data)
        assertEquals(mimirCoverCacheKey("/x/icon.png"), request.memoryCacheKey?.key)
    }

    @Test
    fun `no request outside the shared builder sizes itself to the cover cap`() {
        val root = listOf(File("."), File("..")).first { File(it, "settings.gradle.kts").isFile }
        val offenders = root.listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name != "MimirCoverDecode.kt" && it.readText().contains(".size(MimirCoverDecodeCapPx)") }
            .map { it.name }
        assertTrue("build these with mimirCoverRequest: $offenders", offenders.isEmpty())
    }
}
