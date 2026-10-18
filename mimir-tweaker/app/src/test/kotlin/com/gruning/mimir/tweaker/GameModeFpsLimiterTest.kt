package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModeFpsLimiterTest {
    private val packageName = "dev.eden.eden_emulator"

    @Test
    fun `Odin standard and empty intervention output admits only exact repeated baseline`() {
        val modes = parseGameModeModesReadback(
            "$packageName current mode: standard, available game modes: [standard,performance,battery,custom]",
            packageName,
        )
        val configs = parseGameModeConfigReadback(
            "$packageName interventions: \n[Name:$packageName Modes: {}]",
            packageName,
        )
        val baseline = GameModeFpsReadback(checkNotNull(modes), checkNotNull(configs))

        assertEquals(baseline, exactRepeatedFpsBaseline(baseline, baseline.copy()))
        assertNull(exactRepeatedFpsBaseline(baseline, null))
    }

    @Test
    fun `Odin no-intervention package baseline is exact and package bound`() {
        assertEquals(
            GameModeConfigReadback.Baseline("org.ppsspp.ppsspp"),
            parseGameModeConfigReadback(
                "org.ppsspp.ppsspp interventions:\r\n No intervention found for package org.ppsspp.ppsspp ",
                "org.ppsspp.ppsspp",
            ),
        )
        assertNull(
            parseGameModeConfigReadback(
                "org.ppsspp.ppsspp interventions:\nNo intervention found for package org.ppsspp.ppsspp.gold",
                "org.ppsspp.ppsspp",
            ),
        )
        assertNull(
            parseGameModeConfigReadback(
                "org.ppsspp.ppsspp interventions:\n" +
                    "No intervention found for package org.ppsspp.ppsspp\nUNEXPECTED",
                "org.ppsspp.ppsspp",
            ),
        )
    }

    @Test
    fun `applied custom output accepts exact fps-only intervention`() {
        val modes = parseGameModeModesReadback(
            "$packageName current mode: custom, available game modes: [standard,performance,battery,custom]",
            packageName,
        )
        val config = parseGameModeConfigReadback(
            "$packageName interventions: \n" +
                "[Name:$packageName Modes: {4=[Game Mode:4,Scaling:-1.0,Use Angle:false," +
                "Fps:60,Loading Boost Duration:-1]}]",
            packageName,
        )
        val readback = GameModeFpsReadback(checkNotNull(modes), checkNotNull(config))

        assertTrue(readback.isExactTarget(60))
        assertFalse(readback.isExactTarget(40))
    }

    @Test
    fun `corrected FPS_MODES_OUTPUT and FPS_CONFIG_OUTPUT still parse a representative real cmd game line and extract the same groups`() {
        // Host-only proof: this runs on java.util.regex.Pattern (OpenJDK), which already accepted the
        // pre-fix bare-']'/'}' pattern permissively -- a passing host test can never demonstrate that
        // Android's ICU-backed engine now *accepts* the escaped pattern (only that OpenJDK still extracts
        // the same groups it always did). ICU acceptance itself remains device-unverified -- this worktree
        // does not touch adb/the device.
        val modesLine =
            "$packageName current mode: custom, available game modes: [standard,performance,battery,custom]"
        val modes = checkNotNull(parseGameModeModesReadback(modesLine, packageName))
        assertEquals("custom", modes.currentMode)
        assertEquals(setOf("standard", "performance", "battery", "custom"), modes.availableModes)

        val configLine =
            "$packageName interventions: \n" +
                "[Name:$packageName Modes: {4=[Game Mode:4,Scaling:-1.0,Use Angle:false," +
                "Fps:90,Loading Boost Duration:-1]}]"
        val config = checkNotNull(parseGameModeConfigReadback(configLine, packageName))
        check(config is GameModeConfigReadback.FpsOverride)
        assertEquals(packageName, config.packageName)
        assertEquals(90, config.fps)
    }

    @Test
    fun `preexisting or mixed interventions fail closed`() {
        val wrong = listOf(
            "[Name:$packageName Modes: {2=[Game Mode:2,Scaling:-1.0,Use Angle:false,Fps:60,Loading Boost Duration:-1]}]",
            "[Name:$packageName Modes: {4=[Game Mode:4,Scaling:0.8,Use Angle:false,Fps:60,Loading Boost Duration:-1]}]",
            "[Name:$packageName Modes: {4=[Game Mode:4,Scaling:-1.0,Use Angle:true,Fps:60,Loading Boost Duration:-1]}]",
            "[Name:$packageName Modes: {4=[Game Mode:4,Scaling:-1.0,Use Angle:false,Fps:60,Loading Boost Duration:5000]}]",
        )
        wrong.forEach { body ->
            assertNull(parseGameModeConfigReadback("$packageName interventions: \n$body", packageName))
        }
        assertNull(
            parseGameModeConfigReadback(
                "$packageName interventions: \n[Name:other.game Modes: {}]",
                packageName,
            ),
        )
    }

    @Test
    fun `frame options are only exact divisors of active refresh`() {
        assertEquals(listOf(30, 40, 60, 120), supportedGameModeFrameLimits(120))
        assertEquals(listOf(30, 60), supportedGameModeFrameLimits(60))
        assertEquals(listOf(40), supportedGameModeFrameLimits(80))
        assertEquals(emptyList<Int>(), supportedGameModeFrameLimits(0))
    }

    @Test
    fun `commands reject injection wrong user and oversize package without sanitizing`() {
        assertEquals(
            "cmd game set --user 0 --mode 4 --fps 60 $packageName",
            GameModeFpsCommands.apply(packageName, 0, 60),
        )
        assertEquals("cmd game reset --user 0 $packageName", GameModeFpsCommands.reset(packageName, 0))
        assertNull(GameModeFpsCommands.apply("$packageName;id", 0, 60))
        assertNull(GameModeFpsCommands.apply(packageName, 10, 60))
        assertNull(GameModeFpsCommands.apply(packageName, 0, 55))
        assertNull(GameModeFpsCommands.listModes("a.${"x".repeat(180)}"))
    }

    @Test
    fun `wrong echoed package malformed modes and duplicate modes reject`() {
        assertNull(
            parseGameModeModesReadback(
                "other.game current mode: standard, available game modes: [standard,custom]",
                packageName,
            ),
        )
        assertNull(
            parseGameModeModesReadback(
                "$packageName current mode: standard, available game modes: [standard,standard,custom]",
                packageName,
            ),
        )
        assertNull(
            parseGameModeModesReadback(
                "$packageName current mode: unknown, available game modes: [standard,custom]",
                packageName,
            ),
        )
    }
}
