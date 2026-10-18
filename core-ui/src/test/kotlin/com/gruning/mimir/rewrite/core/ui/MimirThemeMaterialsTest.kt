package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirThemeMaterialsTest {
    @Test
    fun everyThemeOwnsDistinctSceneTextureAndCounterHue() {
        val palettes = MimirThemePalette.all

        assertEquals(9, palettes.map { it.materials.scene.atmosphere }.toSet().size)
        assertEquals(9, palettes.map { it.materials.scene.texture }.toSet().size)
        palettes.forEach { palette ->
            assertFalse("${palette.id} is monochrome", palette.accent == palette.materials.counterAccent)
            assertTrue(
                "${palette.id} accent and counter hue are too similar",
                rgbDistance(palette.accent, palette.materials.counterAccent) >= 0.25f,
            )
            assertTrue(
                "${palette.id} stage washes collapsed into one hue",
                rgbDistance(
                    palette.materials.scene.stagePrimary,
                    palette.materials.scene.stageSecondary,
                ) >= 0.18f,
            )
            assertTrue("${palette.id}.glassSheen is missing", palette.materials.glassSheen.alpha > 0f)
            assertTrue("${palette.id}.railSheen is missing", palette.materials.railSheen.alpha > 0f)
            assertFalse("${palette.id}.control material collapsed", palette.materials.controlTop == palette.materials.controlBottom)
        }
    }

    @Test
    fun onlyShippingMoltenThemesAdvertiseMoltenMaterial() {
        // Fólkvangr joined on 2026-09-22. It advertises the material and takes the Quick
        // Rail's PLINTH; it does not take the animated header rule, which is a narrower contract that
        // `MimirHeaderRule.moltenRuleSpec` still ships for Surtr and Niflheim alone. The two are
        // deliberately separable, which is why this asserts the material and not the motion.
        val molten = MimirThemePalette.all
            .filter { it.materials.separator.supportsMolten }
            .associate { it.id to it.materials.separator.kind }

        assertEquals(
            mapOf(
                MimirThemeId.SURTR to MimirSeparatorKind.SURTR_MOLTEN,
                MimirThemeId.NIFLHEIM to MimirSeparatorKind.NIFLHEIM_MOLTEN,
                MimirThemeId.FOLKVANGR to MimirSeparatorKind.FOLKVANGR_MOLTEN,
            ),
            molten,
        )
    }

    @Test
    fun authoredRolesMeetContrastAcrossEveryTheme() {
        MimirThemePalette.all.forEach { palette ->
            val surfaces = listOf(
                "base" to palette.surfaceBase,
                "raised" to palette.surfaceRaised,
                "high" to palette.surfaceHigh,
            )
            surfaces.forEach { (surfaceName, surface) ->
                assertContrast("${palette.id}.textPrimary/$surfaceName", palette.textPrimary, surface, 4.5f)
                assertContrast("${palette.id}.textSecondary/$surfaceName", palette.textSecondary, surface, 4.5f)
                assertContrast("${palette.id}.readableAccent/$surfaceName", palette.materials.readableAccent, surface, 3f)
                assertContrast("${palette.id}.focusRing/$surfaceName", palette.focusRing, surface, 3f)
            }
            assertContrast("${palette.id}.textMuted", palette.textMuted, palette.surfaceBase, 4.5f)
            assertContrast("${palette.id}.primaryInk", palette.onAccent, palette.accent, 4.5f)
            assertContrast("${palette.id}.brightInk", palette.onAccentBright, palette.accentBright, 4.5f)
            assertContrast("${palette.id}.dangerInk", palette.onDanger, palette.danger, 4.5f)
            assertContrast(
                "${palette.id}.counterInk",
                palette.materials.onCounterAccent,
                palette.materials.counterAccent,
                4.5f,
            )
            val hero = palette.materials.hero
            listOf(hero.coverTop, hero.coverMiddle, hero.coverBottom).forEachIndexed { index, cover ->
                assertContrast("${palette.id}.heroInk/$index", hero.onCover, cover, 4.5f)
            }
            assertContrast("${palette.id}.heroRing", palette.tileRing, palette.materials.hero.coverTop, 3f)
            assertContrast("${palette.id}.runeInk", palette.runeMark, hero.coverMiddle, 3f)

            val modalField = palette.scrim.compositeOver(palette.surfaceBase)
            assertContrast("${palette.id}.modalPrimary", palette.materials.onScrimPrimary, modalField, 4.5f)
            assertContrast("${palette.id}.modalSecondary", palette.materials.onScrimSecondary, modalField, 4.5f)
            assertContrast("${palette.id}.modalMuted", palette.materials.onScrimMuted, modalField, 4.5f)
            assertTrue(
                "${palette.id}.accentBright must be brighter than accent",
                palette.accentBright.luminance() > palette.accent.luminance(),
            )
        }
    }

    @Test
    fun midnightAndMetallicFieldsKeepTheirAuthoredDepth() {
        val midnight = MimirThemePalette.Mani
        val moonlit = midnight.fieldTreatment()
        assertEquals(midnight.surfaceHigh, moonlit.top)
        assertEquals(midnight.surfaceRaised, moonlit.horizon)
        assertEquals(midnight.surfaceBase, moonlit.body)
        assertEquals(midnight.materials.scene.fieldFooter, moonlit.footer)
        assertEquals(0.58f, moonlit.railAlpha)
        assertTrue("midnight footer must remain deep", moonlit.footer.luminance() < 0.01f)

        listOf(MimirThemePalette.Folkvangr, MimirThemePalette.Baldr).forEach { palette ->
            val field = palette.fieldTreatment()
            assertEquals(palette.surfaceBase, field.top)
            assertEquals(palette.signatureTop, field.horizon)
            assertEquals(palette.surfaceBase, field.body)
            assertEquals(palette.materials.scene.fieldFooter, field.footer)
            assertEquals(0.72f, field.railAlpha)
            assertTrue("${palette.id} field is not dark metallic", field.footer.luminance() < 0.02f)
        }
    }

    @Test
    fun atmosphereBloomStaysSubtle() {
        MimirThemePalette.all.forEach { palette ->
            val scene = palette.materials.scene
            assertTrue("${palette.id}.ambient wash is too strong", palette.amb.alpha <= 0.15f)
            assertTrue("${palette.id}.primary stage wash is too strong", scene.stagePrimary.alpha <= 0.15f)
            assertTrue("${palette.id}.secondary stage wash is too strong", scene.stageSecondary.alpha <= 0.20f)
        }
    }

    @Test
    fun metallicThemesKeepDarkReadableHeroVolumes() {
        listOf(MimirThemePalette.Folkvangr, MimirThemePalette.Baldr).forEach { palette ->
            assertTrue("${palette.id}.heroTop", palette.materials.hero.coverTop.luminance() < 0.12f)
            assertTrue("${palette.id}.heroMiddle", palette.materials.hero.coverMiddle.luminance() < 0.08f)
            assertTrue("${palette.id}.heroBottom", palette.materials.hero.coverBottom.luminance() < 0.03f)
            assertTrue(
                "${palette.id}.raised field is indistinguishable",
                contrastRatio(palette.surfaceBase, palette.surfaceRaised) >= 1.10f,
            )
            assertTrue(
                "${palette.id}.high field is indistinguishable",
                contrastRatio(palette.surfaceBase, palette.surfaceHigh) >= 1.20f,
            )
            listOf(palette.surfaceBase, palette.surfaceRaised, palette.surfaceHigh).forEachIndexed { index, surface ->
                assertContrast("${palette.id}.tileFocusRing/$index", palette.tileFocusRing, surface, 3f)
            }

            val glassContent = palette.liquidGlassContentPalette()
            listOf(
                palette.materials.hero.coverTop,
                palette.materials.hero.coverMiddle,
                palette.materials.hero.coverBottom,
            ).forEachIndexed { index, cover ->
                assertContrast("${palette.id}.glassPrimary/$index", glassContent.textPrimary, cover, 4.5f)
                assertContrast("${palette.id}.glassSecondary/$index", glassContent.textSecondary, cover, 4.5f)
                assertContrast("${palette.id}.glassMuted/$index", glassContent.textMuted, cover, 4.5f)
            }
        }
        assertEquals(Color(0xFFC6CDD5), MimirThemePalette.Folkvangr.focusRing)
        assertEquals(Color(0xFF7CC4FF), MimirThemePalette.Baldr.focusRing)
        assertTrue(MimirThemePalette.all.none { it.usesBrightField })
        assertTrue(MimirThemePalette.all.none { it.needsLocalizedMediaContrast })
        assertEquals(
            setOf(MimirThemeId.FOLKVANGR, MimirThemeId.BALDR),
            MimirThemePalette.all
                .filter { it.presentation.finish == MimirThemeFinish.METALLIC }
                .map { it.id }
                .toSet(),
        )
    }

    @Test
    fun identityRoleSnapshotIsDeterministic() {
        val actual = MimirThemePalette.all.joinToString("\n") { palette ->
            val material = palette.materials
            listOf(
                palette.id.storageKey,
                material.scene.atmosphere.name,
                material.scene.texture.name,
                material.counterAccent.hex(),
                material.readableAccent.hex(),
                material.hero.coverTop.hex(),
                material.hero.coverBottom.hex(),
                material.separator.kind.name,
            ).joinToString("|")
        }

        assertEquals(EXPECTED_IDENTITY_ROLES, actual)
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label contrast was $ratio, expected >= $minimum", ratio >= minimum)
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun rgbDistance(a: Color, b: Color): Float {
        val red = a.red - b.red
        val green = a.green - b.green
        val blue = a.blue - b.blue
        return kotlin.math.sqrt(red * red + green * green + blue * blue)
    }

    private fun Color.hex(): String = toArgb().toUInt().toString(16).padStart(8, '0')

    private companion object {
        val EXPECTED_IDENTITY_ROLES = """
            default|WELL|ARCHITECTURAL|ff7a90c0|ffd4af69|ff1b1c2b|ff05060c|BEAM
            gold|GOLDEN_HALL|GOLDEN_WEAVE|ff7f95d6|ffe2bd70|ff3a2c12|ff0a0703|BEAM
            crimson|FORGE|FORGED_STEEL|ffb9c4e6|ffef5236|ff451a13|ff0d0503|SURTR_MOLTEN
            midnight|MOONLIT|STARLIGHT|ffe4c27d|ff9db7ff|ff24306a|ff05060c|BEAM
            nightmare|DREAD|SHADOW_VEIL|ffc4b6ba|ffe3384d|ff401018|ff0a0203|BEAM
            void|VOID|ABYSS|ff9e8cff|ffdff8ff|ff2c2560|ff05040e|NIFLHEIM_MOLTEN
            terra|ROOTED_WELL|ROOT_GRAIN|ff93c46e|ffc0873c|ff3a2f16|ff090602|BEAM
            love|FOLKVANGR|BONE_ASH|ffc7b183|ffc6ced7|ff3a4049|ff121418|FOLKVANGR_MOLTEN
            baldr|SUNLIT|SUMMER_SKY|ffffcf5c|ff8cc9f7|ff1e3358|ff0a1424|BEAM
        """.trimIndent()
    }

    /** pad14: the two-palette lists above are the metallic subset on purpose; this one holds every shipping palette to the pad's leather law. */
    @Test
    fun allNineShippingPalettesHaveDarkReadableHeroLeather() {
        assertEquals(
            listOf("default", "gold", "crimson", "midnight", "nightmare", "void", "terra", "love", "baldr"),
            MimirThemePalette.all.map { it.id.storageKey },
        )
        MimirThemePalette.all.forEach { palette ->
            val hero = palette.materials.hero
            assertTrue("${palette.id}.heroBottom is dark leather: ${hero.coverBottom.luminance()}", hero.coverBottom.luminance() < 0.12f)
            assertTrue("${palette.id}.heroTop is leather, not paper: ${hero.coverTop.luminance()}", hero.coverTop.luminance() < 0.45f)
        }
    }
}
