package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiosCandidateRegistryTest {
    @Test
    fun candidateTableMatchesLg01RequiredAndOptionalRows() {
        val expected = mapOf(
            PlatformId.PS1 to listOf(
                "scph5500.bin" to true,
                "scph5501.bin" to true,
                "scph5502.bin" to true,
                "scph1000.bin" to false,
                "scph1001.bin" to false,
                "psxonpsp660.bin" to false,
            ),
            PlatformId.PS2 to listOf(
                "scph*.bin" to true,
                "*.bin" to true,
                "rom1.bin" to false,
                "rom2.bin" to false,
                "erom.bin" to false,
                "*.rom" to false,
            ),
            PlatformId.DS to listOf("bios7.bin" to true, "bios9.bin" to true, "firmware.bin" to true),
            PlatformId.SATURN to listOf(
                "saturn_bios.bin" to true,
                "sega_101.bin" to false,
                "mpr-17933.bin" to false,
                "mpr-18811-mx.ic1" to false,
            ),
            PlatformId.DREAMCAST to listOf(
                "dc_boot.bin" to true,
                "dc_flash.bin" to false,
                "naomi.zip" to false,
                "awbios.zip" to false,
                "hod2bios.zip" to false,
            ),
            PlatformId.ARCADE to listOf(
                "neogeo.zip" to true,
                "neocdz.zip" to false,
                "qsound.zip" to false,
                "pgm.zip" to false,
                "awbios.zip" to false,
                "naomi.zip" to false,
                "hod2bios.zip" to false,
                "konamigx.zip" to false,
                "cvs.zip" to false,
                "stvbios.zip" to false,
                "taitogn.zip" to false,
            ),
            PlatformId("segacd") to listOf(
                "bios_CD_U.bin" to true,
                "bios_CD_E.bin" to false,
                "bios_CD_J.bin" to false,
            ),
            PlatformId("pcenginecd") to listOf(
                "syscard3.pce" to true,
                "syscard2.pce" to false,
                "syscard1.pce" to false,
                "gexpress.pce" to false,
            ),
            PlatformId.GBA to listOf("gba_bios.bin" to false),
            PlatformId("atarilynx") to listOf("lynxboot.img" to false),
        )

        assertEquals(expected.keys, BiosCandidateRegistry.platforms.map { it.platformId }.toSet())
        expected.forEach { (platformId, rows) ->
            val actual = BiosCandidateRegistry.forPlatform(platformId)!!.candidates.map { it.fileName to it.required }
            assertEquals(platformId.value, rows, actual)
        }
    }

    /**
     * COVERAGE LOCK (LG01 2026-07-18): the Settings requirement list is PROJECTED from this registry, so
     * dropping a platform here silently removes a row users used to see. That regressed once already —
     * porting the legacy DETECTION table (6 platforms) over the legacy SETTINGS table (9) quietly lost
     * Sega CD / PC Engine CD / GBA / Lynx. Pin every platform that must keep a requirement row.
     */
    @Test
    fun everyPlatformWithAKnownBiosRequirementKeepsItsRow() {
        val covered = BiosCandidateRegistry.platforms.map { it.platformId.value }.toSet()
        listOf(
            "ps1", "ps2", "ds", "saturn", "dreamcast", "arcade",
            "segacd", "pcenginecd", "gba", "atarilynx",
        ).forEach { assertTrue("lost the BIOS row for $it", it in covered) }
    }

    /** Region variants must share a group, or a one-variant user is falsely told the BIOS is missing. */
    @Test
    fun regionVariantsShareOneAlternativeGroup() {
        listOf("ps1", "segacd", "pcenginecd").forEach { platform ->
            val required = BiosCandidateRegistry.forPlatform(PlatformId(platform))!!
                .candidates.filter { it.required }
            assertEquals(
                "$platform region variants must collapse to one group",
                1,
                required.map { it.groupKey }.distinct().size,
            )
        }
    }
}
