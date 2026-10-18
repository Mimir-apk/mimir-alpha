package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch cover's table (covering emulator loading between dispatch and the game screen). A
 * profile names its GAME activity or it gets no cover, and exactly two profiles name one. The table is deliberately
 * tiny: an entry is a claim about another app's class names, and an unobserved emulator must not be guessed at.
 */
class LaunchCoverRulesTest {

    private val dolphinPackage = "org.dolphinemu.dolphinemu"
    private val armsx2Package = "com.armsx2"

    @Test
    fun `Dolphin covers its startup handler until its emulation activity, on GameCube and on Wii`() {
        for (platform in listOf(PlatformId.GAMECUBE, PlatformId.WII)) {
            val rule = requireNotNull(rule(platform, dolphinPackage)) { "no rule for $platform" }
            assertEquals(dolphinPackage, rule.packageName)
            assertEquals("org.dolphinemu.dolphinemu.ui.main.MainActivity", rule.dispatchActivity)
            assertEquals("org.dolphinemu.dolphinemu.activities.EmulationActivity", rule.gameActivity)
        }
    }

    @Test
    fun `ARMSX2 covers its boot splash until its Main activity`() {
        val rule = requireNotNull(rule(PlatformId.PS2, armsx2Package))
        assertEquals(armsx2Package, rule.packageName)
        assertEquals("com.armsx2.BootSplashActivity", rule.dispatchActivity)
        assertEquals("com.armsx2.Main", rule.gameActivity)
    }

    @Test
    fun `the legacy ARMSX2 package and ARMSX3 are unobserved, so they get no cover`() {
        assertNull(rule(PlatformId.PS2, "come.nanodata.armsx2"))
        assertNull(rule(PlatformId("ps3"), "com.armsx3"))
    }

    @Test
    fun `a profile that dispatches straight into its game gets no cover`() {
        assertNotNull("the profile exists", ShippingLaunchProfiles.profileFor(target(PlatformId.PSP, "org.ppsspp.ppsspp")))
        assertNull(rule(PlatformId.PSP, "org.ppsspp.ppsspp"))
        assertNull(rule(PlatformId.PS1, "com.github.stenzek.duckstation"))
        assertNull(rule(PlatformId.N64, "org.mupen64plusae.v3.fzurita"))
    }

    @Test
    fun `no dispatch profile at all means no cover`() {
        // Dolphin does not serve SNES, so it has no profile there, and a cover cannot outlive its profile.
        assertNull(ShippingLaunchProfiles.profileFor(target(PlatformId.SNES, dolphinPackage)))
        assertNull(rule(PlatformId.SNES, dolphinPackage))
    }

    @Test
    fun `exactly two emulators name a game activity across every seeded package and platform`() {
        val covered = mutableSetOf<String>()
        for (platform in EmulatorSeedRegistry.allSeedPlatforms()) {
            for (pkg in EmulatorSeedRegistry.allSeedPackages()) {
                val rule = rule(platform, pkg) ?: continue
                covered += "${platform.value}|${rule.packageName}|${rule.dispatchActivity}|${rule.gameActivity}"
            }
        }
        assertEquals(
            setOf(
                "gamecube|$dolphinPackage|org.dolphinemu.dolphinemu.ui.main.MainActivity|" +
                    "org.dolphinemu.dolphinemu.activities.EmulationActivity",
                "wii|$dolphinPackage|org.dolphinemu.dolphinemu.ui.main.MainActivity|" +
                    "org.dolphinemu.dolphinemu.activities.EmulationActivity",
                "ps2|$armsx2Package|com.armsx2.BootSplashActivity|com.armsx2.Main",
            ),
            covered,
        )
    }

    @Test
    fun `a game activity is never the dispatch activity, so a cover has something to wait for`() {
        for (platform in EmulatorSeedRegistry.allSeedPlatforms()) {
            for (pkg in EmulatorSeedRegistry.allSeedPackages()) {
                val rule = rule(platform, pkg) ?: continue
                assertTrue("$platform $pkg", rule.dispatchActivity != rule.gameActivity)
            }
        }
    }

    @Test
    fun `the ARMSX2 entry says in its own KDoc that it comes from one build on one device`() {
        val source = profilesSource()
        val kdocStart = source.indexOf("The activity ARMSX2 shows the running game in.")
        assertTrue("the ARMSX2 game-activity KDoc is missing", kdocStart >= 0)
        val kdoc = source.substring(kdocStart, source.indexOf("ACTIVITY_ARMSX2_CURRENT_GAME", kdocStart))
        assertTrue(kdoc, kdoc.contains("Inferred from one build on one device"))
        assertTrue(kdoc, kdoc.contains("not a documented contract"))
    }

    @Test
    fun `only the Dolphin and current ARMSX2 profiles carry a game activity in source`() {
        val source = profilesSource()
        val uses = Regex("""gameActivityName = ACTIVITY_[A-Z0-9_]+""").findAll(source).map { it.value }.toList()
        assertEquals(
            listOf(
                "gameActivityName = ACTIVITY_DOLPHIN_GAME",
                "gameActivityName = ACTIVITY_ARMSX2_CURRENT_GAME",
            ),
            uses,
        )
    }

    private fun profilesSource(): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "core-launch/src/main/kotlin/com/gruning/mimir/rewrite/core/launch/ShippingLaunchProfiles.kt",
            )
            if (candidate.isFile) return candidate.readText()
            val local = File(dir, "src/main/kotlin/com/gruning/mimir/rewrite/core/launch/ShippingLaunchProfiles.kt")
            if (local.isFile) return local.readText()
            dir = dir.parentFile
        }
        error("ShippingLaunchProfiles.kt not found from ${File(".").absolutePath}")
    }

    private fun rule(platformId: PlatformId, packageName: String): LaunchCoverRule? =
        launchCoverRuleFor(target(platformId, packageName))

    private fun target(platformId: PlatformId, packageName: String): LaunchTarget = LaunchTarget(
        gameId = GameId("game:${platformId.value}:sample"),
        platformId = platformId,
        emulator = EmulatorProfile(
            id = EmulatorId.fromNativeLauncherKey(packageName),
            packageName = packageName,
            displayName = packageName,
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.FILE_PATH,
            supportedPlatforms = setOf(platformId),
        ),
        locator = "/storage/sample/game.rom",
        locatorForm = LaunchLocatorForm.FILE_PATH,
        title = "Sample",
    )
}
