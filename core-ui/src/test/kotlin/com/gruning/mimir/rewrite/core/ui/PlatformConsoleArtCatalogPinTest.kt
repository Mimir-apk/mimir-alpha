package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the first platform art on screen parsed the console-art manifest in composition, and every tile that
 * composed a platform it had not shown before paid a `getIdentifier` lookup again. The catalog is parsed once per process, each
 * platform's resolved drawable is remembered, and [primePlatformConsoleArt] does both off the main thread ahead of the first tile
 * (Home calls it; nothing in the app warmup is involved).
 *
 * Source characterization: the resolve needs a real resource table, which a plain JVM test in this module does not have, so the
 * parts that are laws rather than numbers are pinned against the file text.
 */
class PlatformConsoleArtCatalogPinTest {

    private val source: String by lazy {
        listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/PlatformConsoleArt.kt"),
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/PlatformConsoleArt.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("PlatformConsoleArt.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun `the manifest is parsed once, safely published, and the resolved drawable is remembered per platform`() {
        assertTrue("the parsed catalog is @Volatile for its double-checked read", source.contains("@Volatile private var cached: Catalog? = null"))
        assertTrue("one resolved id per normalized platform", source.contains("private val resolved = ConcurrentHashMap<String, Int>()"))
        val resolve = source.substringAfter("fun drawableRes(context: Context, rawPlatformId: String): Int? {").substringBefore("/** Parses the manifest")
        assertTrue("a remembered id returns before any lookup", resolve.indexOf("resolved[normalized]?.let { return it }") in 0 until resolve.indexOf("getIdentifier"))
        assertTrue("a found id is remembered", resolve.contains("if (id != 0) resolved[normalized] = id"))
        assertEquals("the lookup happens in exactly one place", 1, Regex("getIdentifier\\(").findAll(source).count())
    }

    @Test
    fun `priming is public, parses the catalog and resolves every platform`() {
        assertTrue(source.contains("fun primePlatformConsoleArt(context: Context) = PlatformConsoleArtCatalog.prime(context.applicationContext)"))
        val prime = source.substringAfter("fun prime(context: Context) {").substringBefore("private fun load(")
        assertTrue("every alias is resolved", prime.contains("catalog.aliases.keys.forEach { drawableRes(context, it) }"))
    }
}
