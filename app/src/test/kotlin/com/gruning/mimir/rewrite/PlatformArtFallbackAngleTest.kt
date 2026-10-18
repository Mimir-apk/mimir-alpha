package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.platform.IdentityNamespace
import com.gruning.mimir.rewrite.core.platform.PlatformRegistryLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-02b. The picker seeds from the primary angle only, while the auto fetch walks every angle — so a
 * platform with a weak primary showed an empty picker although a working spelling was already in the
 * table. These pin the fallback walk and, more importantly, the two cases where it must REFUSE to walk.
 */
class PlatformArtFallbackAngleTest {

    /**
     * Live production compatibility branches outside the registry. Keep covered until retired or migrated.
     */
    private val productionCompatibilityBranchIds = setOf(
        "psvita", "wiiu", "gmd", "sms", "gg", "mame", "3do", "panasonic3do",
    )

    private fun next(cacheKey: String, query: String) =
        SteamGridDbTileFetcher.nextPlatformArtSearchAngle(cacheKey, query)

    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
        "Missing test resource $name"
    }

    private fun registryDerivedSweepIds(): List<String> {
        val registry = resource("mimir-platform-registry.v2.json").use {
            PlatformRegistryLoader.load(it).requireRegistry()
        }
        val canonicalIds = registry.platforms().map { it.id.value }
        val rewriteLegacyAliasIds = registry.platforms().flatMap { platform ->
            platform.aliases
                .filter { it.namespace == IdentityNamespace.REWRITE_LEGACY }
                .map { it.normalizedValue }
        }
        return (canonicalIds + rewriteLegacyAliasIds + productionCompatibilityBranchIds).distinct().sorted()
    }

    @Test
    fun `the seeded chain advances one angle at a time and then stops`() {
        // psp: Sony PlayStation Portable -> Sony PSP -> PlayStation Portable -> PSP -> stop
        assertEquals("Sony PSP", next("platform/psp", "Sony PlayStation Portable"))
        assertEquals("PlayStation Portable", next("platform/psp", "Sony PSP"))
        assertEquals("PSP", next("platform/psp", "PlayStation Portable"))
        assertNull("the last angle must terminate the walk", next("platform/psp", "PSP"))
    }

    @Test
    fun `a hand-edited query never triggers a fallback`() {
        // The user typed this. Silently searching something else answers a question they did not ask.
        assertNull(next("platform/ps2", "Playstation Two"))
        assertNull(next("platform/ps2", "mario"))
        assertNull(next("platform/ps2", ""))
    }

    @Test
    fun `non-platform pickers never fall back`() {
        // Game art has no angle table; there is nothing to walk.
        assertNull(next("game/abc123", "Sony PlayStation 2"))
        assertNull(next("", "Sony PlayStation 2"))
        assertNull(next("platformish/ps2", "Sony PlayStation 2"))
    }

    @Test
    fun `an unmapped platform has no successor`() {
        // Unmapped ids fall back to a single-entry list, so there is never a second angle.
        assertNull(next("platform/notaconsole", "Whatever Label"))
    }

    @Test
    fun `matching is case-insensitive so a keyboard round-trip still advances`() {
        assertEquals("Sony PSP", next("platform/psp", "sony playstation portable"))
    }

    @Test
    fun `the walk is bounded by the table and cannot revisit`() {
        var query: String? = "Sony PlayStation 2"
        val seen = mutableListOf<String>()
        var guard = 0
        while (query != null && guard++ < 50) {
            seen += query
            query = next("platform/ps2", query)
        }
        assertEquals("walk must terminate within the table", seen, seen.distinct())
        assertEquals(3, seen.size)
    }

    @Test
    fun `no angle list contains a case-insensitive duplicate, on any shipping platform`() {
        // THE invariant the walk's termination used to depend on. nextPlatformArtSearchAngle re-derives
        // position with indexOfFirst + ignoreCase, which always returns the FIRST match — so a duplicate
        // anywhere in a list makes the walk cycle, issuing a live provider request per cycle. The table is
        // hand-maintained and its edit pattern is "add the newly-tested spelling, keep the old ones", which
        // is exactly the edit that introduces one. This fails at test time so it cannot reach a user.
        assertEquals(6, SteamGridDbTileFetcher.MaxPlatformArtSearchAngles)
        val ids = registryDerivedSweepIds()
        assertTrue(
            "registry sweep has ${ids.size} ids, expected strictly more than the retired 30-id hand list",
            ids.size > 30,
        )
        ids.forEach { id ->
            val angles = SteamGridDbTileFetcher.platformArtSearchTitles(id, "fallback")
            val folded = angles.map(String::lowercase)
            assertEquals(
                "$id has a case-insensitive duplicate angle, which would make the walk cycle: $angles",
                folded.size,
                folded.distinct().size,
            )
            assertTrue(
                "$id has ${angles.size} angles, past the MaxPlatformArtSearchAngles ceiling of 6",
                angles.size <= 6,
            )
        }
    }

    @Test
    fun `the ceiling bounds the walk even against a malformed table`() {
        // Termination must not depend on the table being well-formed. This pins the backstop's existence
        // and headroom; the production loop breaks on angleAttempts >= this value regardless of content.
        assertTrue(SteamGridDbTileFetcher.MaxPlatformArtSearchAngles > 0)
        val longest = listOf("ps1", "ps2", "psp", "arcade", "snes", "megadrive")
            .maxOf { SteamGridDbTileFetcher.platformArtSearchTitles(it, "fallback").size }
        assertTrue(
            "the ceiling must clear the longest shipping list ($longest)",
            SteamGridDbTileFetcher.MaxPlatformArtSearchAngles >= longest,
        )
    }
}
