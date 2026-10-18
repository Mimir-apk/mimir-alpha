package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.feature.library.launchCardEmulatorLabel
import com.gruning.mimir.rewrite.feature.library.openWithOptionsAfterPick
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 5b: Main's Home launch card names the emulator the launch would start (`OpenWithCandidate.isLaunchPick`).
 * With nothing marked it keeps the text it showed before, the platform's resolved name passed as the fallback, so the
 * row never changes layout.
 */
class HomeLaunchCardEmulatorLabelTest {
    /** What `shelfEmulatorLabels` gives the card before options land: the platform default, else Auto. */
    private val platformName = "RetroArch 64 / Gambatte"
    /** Auto's first pick, which a launch with an unusable override actually starts. */
    private val autoPick = "RetroArch 64 / SameBoy"
    private val overrideName = "Pizza Boy GBC"

    private fun candidate(
        label: String,
        installed: Boolean = true,
        override: Boolean = false,
        auto: Boolean = false,
        launchPick: Boolean = false,
    ) = OpenWithCandidate(
        emulatorId = EmulatorId("emu.${label.filter(Char::isLetterOrDigit).lowercase()}"),
        label = label,
        packageName = "pkg.${label.filter(Char::isLetterOrDigit).lowercase()}",
        installed = installed,
        isCurrentOverride = override,
        isAutoPick = auto,
        isLaunchPick = launchPick,
    )

    @Test
    fun `options not loaded keep the text the card showed before`() {
        assertEquals(platformName, homeLaunchCardEmulatorLabel(null, fallback = platformName))
        assertNull(homeLaunchCardEmulatorLabel(null, fallback = null))
    }

    @Test
    fun `an unusable override names the emulator the launch falls to, not the stale override`() {
        val options = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(
                candidate(autoPick, launchPick = true),
                candidate(platformName, auto = true),
                candidate(overrideName, installed = false, override = true),
            ),
        )
        assertEquals(autoPick, homeLaunchCardEmulatorLabel(options, fallback = platformName))
    }

    @Test
    fun `an override that is no longer listed never names the platform default`() {
        val options = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(candidate(autoPick, launchPick = true), candidate(platformName, auto = true)),
        )
        assertEquals(autoPick, homeLaunchCardEmulatorLabel(options, fallback = platformName))
    }

    @Test
    fun `a usable override and a game with no override name the marked emulator`() {
        val usable = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(candidate(platformName, auto = true), candidate(overrideName, override = true, launchPick = true)),
        )
        assertEquals(overrideName, homeLaunchCardEmulatorLabel(usable, fallback = platformName))
        val noOverride = OpenWithOptions(
            hasOverride = false,
            candidates = listOf(candidate(autoPick), candidate(platformName, auto = true, launchPick = true)),
        )
        assertEquals(platformName, homeLaunchCardEmulatorLabel(noOverride, fallback = null))
    }

    @Test
    fun `nothing marked keeps the text the card showed before, even with a stale override`() {
        val options = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(candidate(overrideName, installed = false, override = true)),
        )
        assertEquals(platformName, homeLaunchCardEmulatorLabel(options, fallback = platformName))
    }

    @Test
    fun `the Home card reads its EMULATOR name through the shared rule`() {
        val lines = sourceFile("HomeLaunchCardCoordinator.kt").lines().map(String::trim)
        assertTrue(
            "fun currentOpenWithLabel(fallback: String? = null, overridePending: Boolean = false): String? =" in lines &&
                "homeLaunchCardEmulatorLabel(openWithOptions, fallback, overridePending)" in lines,
        )
    }

    @Test
    fun `a game with an override whose options have not loaded never reads Automatic or the platform default`() {
        // Review 2026-10-14 (finding 7): until the load lands the row fell to the platform's default (or "Automatic" when that map is still empty),
        // both untrue for a game with an override.
        assertEquals(HomeLaunchCardEmulatorLoadingLabel, homeLaunchCardEmulatorLabel(null, fallback = platformName, overridePending = true))
        assertEquals(HomeLaunchCardEmulatorLoadingLabel, homeLaunchCardEmulatorLabel(null, fallback = null, overridePending = true))
        // No override: the fallback (or the card's own Automatic) is true, and stays.
        assertEquals(platformName, homeLaunchCardEmulatorLabel(null, fallback = platformName, overridePending = false))
        // Loaded options always win over the placeholder.
        val usable = OpenWithOptions(true, listOf(candidate(overrideName, override = true, launchPick = true)))
        assertEquals(overrideName, homeLaunchCardEmulatorLabel(usable, fallback = platformName, overridePending = true))
    }

    @Test
    fun `a reload that fails keeps the options the card has, one that lands replaces them`() {
        val had = OpenWithOptions(true, listOf(candidate(overrideName, override = true, launchPick = true)))
        val landed = OpenWithOptions(false, listOf(candidate(autoPick, launchPick = true)))
        assertEquals(had, reloadedOpenWithOptions(had, null))
        assertEquals(landed, reloadedOpenWithOptions(had, landed))
        assertNull(reloadedOpenWithOptions(null, null))
    }

    @Test
    fun `a card seeds its options from the last load for that game, newest 32 kept`() {
        val cache = HomeLaunchCardOptionsCache(capacity = 3)
        val one = OpenWithOptions(true, listOf(candidate(overrideName, override = true, launchPick = true)))
        cache[GameId("a")] = one
        assertEquals(one, cache[GameId("a")])
        assertNull(cache[GameId("never-shown")])
        cache[GameId("b")] = one
        cache[GameId("c")] = one
        assertEquals(one, cache[GameId("a")]) // touched: now the newest
        cache[GameId("d")] = one
        assertNull("the least recently used goes", cache[GameId("b")])
        assertEquals(one, cache[GameId("a")])
    }

    @Test
    fun `the Home card seeds from the cache on open, names a pick at once and drops loads that began before it`() {
        val source = sourceFile("HomeLaunchCardCoordinator.kt")
        assertTrue("seeded on open, not nulled", source.contains("openWithOptions = game?.let { openWithCache[it.gameId] }"))
        assertTrue("a failed reload keeps the options", source.contains("openWithOptions = reloadedOpenWithOptions(openWithOptions, loaded)"))
        val choose = source.substringAfter("    fun choose(candidate: OpenWithCandidate) {").substringBefore("    fun chooseAutomatic()")
        assertTrue("the pick is named at once", choose.contains("openWithOptionsAfterPick(current, candidate)"))
        assertTrue("and a load that started before it is stale", choose.contains("openWithLoadSerial++"))
        assertTrue("a load drops itself when a newer one or a pick owns the options", source.contains("if (serial != openWithLoadSerial) return@launch"))
        assertTrue("the host passes whether the game has an override", sourceFile("MimirAppHost.kt").contains("overridePending = game?.hasEmulatorOverride == true"))
    }

    @Test
    fun `a pick reads as the override and the launch pick, and nothing else does`() {
        val options = OpenWithOptions(
            hasOverride = false,
            candidates = listOf(candidate(autoPick, auto = true, launchPick = true), candidate(overrideName)),
        )
        val after = openWithOptionsAfterPick(options, options.candidates[1])
        assertTrue(after.hasOverride)
        assertEquals(overrideName, launchCardEmulatorLabel(after))
        assertEquals(listOf(false, true), after.candidates.map { it.isCurrentOverride })
        assertEquals(listOf(false, true), after.candidates.map { it.isLaunchPick })
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
