package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.graphics.Color
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformHeroIconStopsTest {
    @Test
    fun defaultThemePortsShippingIconGradientStops() {
        val stops = platformHeroIconStops(MimirThemePalette.Default)

        assertEquals(0.00f, stops[0].offset)
        assertEquals(Color(0xFFFFF3C8), stops[0].color)
        assertEquals(0.40f, stops[1].offset)
        assertEquals(Color(0xFFF7D07D), stops[1].color)
        assertEquals(0.62f, stops[2].offset)
        assertEquals(Color(0xFFE2B873), stops[2].color)
        assertEquals(1.00f, stops[3].offset)
        assertEquals(Color(0xFFBB8C4E), stops[3].color)
    }

    @Test
    fun iconGradientRetintsForMoodThemes() {
        assertEquals(
            listOf(
                Color(0xFFFFCDB8),
                Color(0xFFFF8A4D),
                Color(0xFFEF5236),
                Color(0xFF7A2412),
            ),
            platformHeroIconStops(MimirThemePalette.forId(MimirThemeId.SURTR)).map { it.color },
        )
        assertEquals(
            listOf(
                Color(0xFFEDF1F5),
                Color(0xFFCBD3DB),
                Color(0xFFB6BEC7),
                Color(0xFF6E7883),
            ),
            platformHeroIconStops(MimirThemePalette.forId(MimirThemeId.FOLKVANGR)).map { it.color },
        )
    }

    @Test
    fun everyThemeUsesTheShippingStopOffsets() {
        val expectedOffsets = listOf(0.00f, 0.40f, 0.62f, 1.00f)

        MimirThemePalette.all.forEach { palette ->
            assertEquals(palette.id.storageKey, expectedOffsets, platformHeroIconStops(palette).map { it.offset })
        }
    }

    @Test
    fun everyBookHeroConsumesItsTypedCoverInkAndHardwareRoles() {
        MimirThemePalette.all.forEach { palette ->
            val expected = palette.materials.hero
            val actual = platformHeroBookMaterial(palette)

            assertEquals("${palette.id}.hero", expected, actual)
            assertEquals(
                "${palette.id}.book icon",
                listOf(expected.onCover, expected.onCover),
                platformHeroBookIconStops(actual).map { it.color },
            )
            assertNotEquals("${palette.id}.hardware collapsed into cover", expected.coverMiddle, expected.hardware)
        }
    }

    @Test
    fun freyaAndBaldrBooksKeepReadableConsoleInkAndDistinctTooling() {
        listOf(MimirThemePalette.Folkvangr, MimirThemePalette.Baldr).forEach { palette ->
            val hero = platformHeroBookMaterial(palette)

            assertEquals(
                palette.id.storageKey,
                hero.onCover,
                platformHeroBookIconStops(hero).map { it.color }.distinct().single(),
            )
            assertNotEquals("${palette.id}.tooling must not use console ink", hero.onCover, hero.hardware)
            assertNotEquals("${palette.id}.cover needs authored depth", hero.coverTop, hero.coverMiddle)
            assertNotEquals("${palette.id}.cover needs authored depth", hero.coverMiddle, hero.coverBottom)
        }
    }

    @Test
    fun customPlatformArtOwnsTheWholeFaceWhilePendingTruthStaysQuiet() {
        assertEquals(
            PlatformHeroFaceMode.PENDING,
            platformHeroFaceMode(false, storeHydrated = false, overrideDecodeFailed = false, overrideDecoded = false),
        )
        assertEquals(
            PlatformHeroFaceMode.DEFAULT,
            platformHeroFaceMode(false, storeHydrated = true, overrideDecodeFailed = false, overrideDecoded = false),
        )
        assertEquals(
            PlatformHeroFaceMode.PENDING,
            platformHeroFaceMode(true, storeHydrated = true, overrideDecodeFailed = false, overrideDecoded = false),
        )
        assertEquals(
            PlatformHeroFaceMode.CUSTOM,
            platformHeroFaceMode(true, storeHydrated = true, overrideDecodeFailed = false, overrideDecoded = true),
        )
        // R8: a pick that exists but does not decode is wired art that FAILED, which draws nothing; it never selects the default.
        assertEquals(
            PlatformHeroFaceMode.FAILED,
            platformHeroFaceMode(true, storeHydrated = true, overrideDecodeFailed = true, overrideDecoded = false),
        )
    }

    @Test
    fun aCustomPickWhoseFileIsGoneIsNoArtButACorruptOneDrawsNothing() {
        // R8a: the file is gone, so the pick counts as no art and the face takes the normal default (the console glyph or the book).
        assertEquals(
            PlatformHeroFaceMode.DEFAULT,
            platformHeroFaceMode(true, storeHydrated = true, overrideDecodeFailed = false, overrideDecoded = false, overrideFileMissing = true),
        )
        // R8: the file is there but undecodable, so NOTHING draws: not the default's glyph, not its book or skin.
        val corrupt = platformHeroFaceMode(true, storeHydrated = true, overrideDecodeFailed = true, overrideDecoded = false, overrideFileMissing = false)
        assertEquals(PlatformHeroFaceMode.FAILED, corrupt)
        assertNotEquals(PlatformHeroFaceMode.DEFAULT, corrupt)
        // A decoded cover owns the face whatever else is said.
        assertEquals(
            PlatformHeroFaceMode.CUSTOM,
            platformHeroFaceMode(true, storeHydrated = true, overrideDecodeFailed = false, overrideDecoded = true, overrideFileMissing = false),
        )
    }

    @Test
    fun retainedDecodeCannotOwnANewerPlatformArtRevision() {
        val first = "file:/platform-snes.png?v=1200&mimirLive=7"
        val replacement = "file:/platform-snes.png?v=1200&mimirLive=8"

        assertTrue(platformCoverSourceMatches(first, first))
        assertFalse(platformCoverSourceMatches(first, replacement))
    }

    /** pad14: the two-palette list above is the metallic subset on purpose; every one of the nine shipping palettes has a leather cover with authored depth. */
    @Test
    fun allNineShippingPalettesKeepADepthedCoverAndTheShippingStops() {
        assertEquals(9, MimirThemePalette.all.size)
        MimirThemePalette.all.forEach { palette ->
            val hero = platformHeroBookMaterial(palette)
            assertNotEquals("${palette.id}.cover needs a lit top and a dark foot", hero.coverTop, hero.coverBottom)
            assertEquals("${palette.id}", 4, platformHeroIconStops(palette).size)
        }
    }
}
