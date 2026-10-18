package com.gruning.mimir.rewrite.core.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * AP01 + CF01 — the appearance-override transform + guardrail, pinned:
 *  - authored palettes pass through UNTOUCHED when no override is active (parity guarantee),
 *  - the accent derivation retargets ONLY the accent system, and only on Default,
 *  - the CF01 temperature is a white-balance blend that preserves authored brightness (the joycon LED
 *    channel itself was DISCARDED in CF01),
 *  - the ring override drives focusRing + tileFocusRing with the theme-appropriate tone,
 *  - the ≥3:1 guardrail admits every stop on the dark themes and does real work on the light ones.
 */
class MimirAppearanceOverridesTest {

    @After fun reset() {
        MimirAppearanceOverrides.accentStop = MimirAccentStop.CLASSIC_GOLD
        MimirAppearanceOverrides.accentPreview = null
        MimirAppearanceOverrides.accentTemps = emptyMap()
        MimirAppearanceOverrides.accentTempPreview = null
        MimirAppearanceOverrides.ringStops = emptyMap()
        MimirAppearanceOverrides.ringPreview = null
        MimirAppearanceOverrides.grounds = emptyMap()
        MimirAppearanceOverrides.buttonMark = null
    }

    @Test fun no_override_is_a_pure_passthrough_for_every_theme() {
        MimirThemePalette.all.forEach { authored ->
            assertEquals(authored, authored.withAppearanceOverrides())
        }
    }

    @Test fun accent_stop_retints_defaults_accent_system_only() {
        MimirAppearanceOverrides.accentStop = MimirAccentStop.ROSE_GOLD
        val p = MimirThemePalette.forId(MimirThemeId.DEFAULT).withAppearanceOverrides()
        assertEquals(MimirAccentStop.ROSE_GOLD.accent, p.accent)
        assertEquals(MimirAccentStop.ROSE_GOLD.accentBright, p.accentBright)
        assertEquals(MimirAccentStop.ROSE_GOLD.accentBright, p.focusRing)
        assertEquals(MimirAccentStop.ROSE_GOLD.accent, p.materials.hero.hardware)
        // Surfaces + text stay authored — the tint rides only the accent system.
        val authored = MimirThemePalette.forId(MimirThemeId.DEFAULT)
        assertEquals(authored.surfaceBase, p.surfaceBase)
        assertEquals(authored.textPrimary, p.textPrimary)
    }

    @Test fun accent_stop_never_touches_named_identities() {
        MimirAppearanceOverrides.accentStop = MimirAccentStop.ROSE_GOLD
        val authored = MimirThemePalette.forId(MimirThemeId.SURTR)
        assertEquals(authored, authored.withAppearanceOverrides())
    }

    @Test fun accent_preview_wins_over_committed_while_open() {
        MimirAppearanceOverrides.accentStop = MimirAccentStop.CLASSIC_GOLD
        MimirAppearanceOverrides.accentPreview = MimirAccentStop.AMBER_EMBER
        val p = MimirThemePalette.forId(MimirThemeId.DEFAULT).withAppearanceOverrides()
        assertEquals(MimirAccentStop.AMBER_EMBER.accent, p.accent)
    }

    @Test fun ring_stop_drives_both_ring_channels_dark_theme() {
        MimirAppearanceOverrides.ringStops = mapOf(MimirThemeId.SURTR to MimirRingStop.AZURE)
        val p = MimirThemePalette.forId(MimirThemeId.SURTR).withAppearanceOverrides()
        assertEquals(MimirRingStop.AZURE.bright, p.focusRing) // dark theme → bright tone on screen
        assertEquals(MimirRingStop.AZURE.bright, p.tileFocusRing)
    }

    @Test fun ring_stop_uses_the_highest_contrast_tone_on_metallic_themes() {
        MimirAppearanceOverrides.ringStops = mapOf(MimirThemeId.FOLKVANGR to MimirRingStop.VIOLET)
        val p = MimirThemePalette.forId(MimirThemeId.FOLKVANGR).withAppearanceOverrides()
        assertEquals(MimirThemeFinish.METALLIC, p.presentation.finish)
        assertEquals(MimirRingStop.VIOLET.bright, p.focusRing)
        assertEquals(MimirRingStop.VIOLET.bright, p.tileFocusRing)
    }

    // ── CF01: named-theme accent temperature ──

    @Test fun accent_temp_shifts_the_named_themes_accent_system_only() {
        MimirAppearanceOverrides.accentTemps = mapOf(MimirThemeId.SURTR to MimirAccentTemp.COOLER)
        val authored = MimirThemePalette.forId(MimirThemeId.SURTR)
        val p = authored.withAppearanceOverrides()
        assertEquals(MimirAccentTemp.COOLER.shift(authored.accent), p.accent)
        assertEquals(MimirAccentTemp.COOLER.shift(authored.accentBright), p.accentBright)
        assertEquals(MimirAccentTemp.COOLER.shift(authored.focusRing), p.focusRing)
        assertEquals(MimirAccentTemp.COOLER.shift(authored.tileFocusRing), p.tileFocusRing)
        // Surfaces + text stay authored — temperature rides only the accent system.
        assertEquals(authored.surfaceBase, p.surfaceBase)
        assertEquals(authored.textPrimary, p.textPrimary)
    }

    @Test fun accent_temp_original_is_a_pure_passthrough() {
        MimirAppearanceOverrides.accentTemps = mapOf(MimirThemeId.HEL to MimirAccentTemp.ORIGINAL)
        val authored = MimirThemePalette.forId(MimirThemeId.HEL)
        assertEquals(authored, authored.withAppearanceOverrides())
    }

    @Test fun accent_temp_applies_per_theme_only_and_never_to_default() {
        MimirAppearanceOverrides.accentTemps = mapOf(MimirThemeId.SURTR to MimirAccentTemp.WARMER)
        val hel = MimirThemePalette.forId(MimirThemeId.HEL)
        assertEquals(hel, hel.withAppearanceOverrides())
        val default = MimirThemePalette.forId(MimirThemeId.DEFAULT)
        assertEquals(default, default.withAppearanceOverrides())
    }

    @Test fun warm_raises_yellowness_and_cool_lowers_it_on_every_named_accent() {
        // The perceptual temperature axis is blue↔yellow opponency, not hue arc — pin it directly.
        fun yellowness(c: androidx.compose.ui.graphics.Color): Float = (c.red + c.green) / 2f - c.blue
        MimirThemePalette.all.filter { it.id != MimirThemeId.DEFAULT }.forEach { palette ->
            val authored = yellowness(palette.accentBright)
            val warm = yellowness(MimirAccentTemp.WARMER.shift(palette.accentBright))
            val cool = yellowness(MimirAccentTemp.COOLER.shift(palette.accentBright))
            assertTrue("${palette.id}: warming must not read cooler", warm >= authored - 1e-4f)
            assertTrue("${palette.id}: cooling must read cooler", cool < authored)
        }
    }

    @Test fun cooling_gold_never_drifts_pink() {
        // Regression pin for the rejected hue-rotation model: gold's shortest hue arc to azure runs
        // through red, which rendered "Cooler" PINK on device. The blend must shrink the red-over-blue
        // dominance instead of re-ranking red upward.
        val gold = MimirAccentStop.CLASSIC_GOLD.accentBright
        val cooled = MimirAccentTemp.COOLER.shift(gold)
        assertTrue("cooling must close the red-blue gap", cooled.red - cooled.blue < gold.red - gold.blue)
        assertTrue("cooled gold keeps green ≥ blue-adjacent balance, never red-pink", cooled.green >= cooled.blue - 0.05f)
    }

    @Test fun every_stop_preserves_authored_brightness() {
        // The white-balance blend restores the authored max channel — no stop dims or blows out a token.
        MimirThemePalette.all.forEach { palette ->
            MimirAccentTemp.entries.forEach { temp ->
                val shifted = temp.shift(palette.accentBright)
                val authoredMax = maxOf(palette.accentBright.red, palette.accentBright.green, palette.accentBright.blue)
                val shiftedMax = maxOf(shifted.red, shifted.green, shifted.blue)
                assertEquals("${palette.id}/$temp keeps brightness", authoredMax, shiftedMax, 1e-3f)
            }
        }
    }

    @Test fun near_neutral_accents_take_a_true_temperature_cast() {
        // Photographic semantics: platinum-family accents cool toward slate and warm toward bronze —
        // a temperature control that no-ops on a silver theme would be a dead feature there.
        val grey = androidx.compose.ui.graphics.Color(0xFF808080)
        val warmed = MimirAccentTemp.WARMER.shift(grey)
        val cooled = MimirAccentTemp.COOLER.shift(grey)
        assertTrue("warmed grey reads bronze (r > b)", warmed.red > warmed.blue)
        assertTrue("cooled grey reads slate (b > r)", cooled.blue > cooled.red)
        assertEquals("brightness preserved", 0x80 / 255f, maxOf(warmed.red, warmed.green, warmed.blue), 1e-3f)
    }

    @Test fun ring_override_applies_per_theme_only() {
        MimirAppearanceOverrides.ringStops = mapOf(MimirThemeId.SURTR to MimirRingStop.AZURE)
        val other = MimirThemePalette.forId(MimirThemeId.HEL)
        assertEquals(other, other.withAppearanceOverrides())
    }

    @Test fun customizes_reports_honestly_for_the_chip() {
        assertFalse(MimirAppearanceOverrides.customizes(MimirThemeId.DEFAULT))
        MimirAppearanceOverrides.accentStop = MimirAccentStop.CHAMPAGNE
        assertTrue(MimirAppearanceOverrides.customizes(MimirThemeId.DEFAULT))
        assertFalse("Default's stop never marks a named theme custom", MimirAppearanceOverrides.customizes(MimirThemeId.HEL))
        MimirAppearanceOverrides.ringStops = mapOf(MimirThemeId.HEL to MimirRingStop.GOLD)
        assertTrue(MimirAppearanceOverrides.customizes(MimirThemeId.HEL))
        // CF01: a named theme's temperature also flips its chip — per theme only.
        MimirAppearanceOverrides.ringStops = emptyMap()
        MimirAppearanceOverrides.accentTemps = mapOf(MimirThemeId.SURTR to MimirAccentTemp.COOL)
        assertTrue(MimirAppearanceOverrides.customizes(MimirThemeId.SURTR))
        assertFalse(MimirAppearanceOverrides.customizes(MimirThemeId.HEL))
    }

    @Test fun guardrail_admits_every_stop_on_release_themes() {
        MimirThemePalette.all.forEach { palette ->
            MimirRingStop.entries.forEach { stop ->
                assertTrue(
                    "${stop.name} should clear 3:1 on ${palette.id}",
                    stop.enabledFor(palette),
                )
            }
        }
    }

    @Test fun noReleaseThemeRetainsTheBrightFieldPosture() {
        assertTrue(MimirThemePalette.all.none { it.usesBrightField })
    }

    @Test fun cta_material_follows_the_default_accent_override_only() {
        val authored = MimirThemeId.DEFAULT.ctaMaterial()
        MimirAppearanceOverrides.accentStop = MimirAccentStop.ANTIQUE_BRONZE
        assertEquals(MimirAccentStop.ANTIQUE_BRONZE.ctaMaterial(), MimirThemeId.DEFAULT.ctaMaterial())
        assertEquals(MimirThemeId.SURTR.ctaMaterial(), MimirThemeId.SURTR.ctaMaterial())
        MimirAppearanceOverrides.accentStop = MimirAccentStop.CLASSIC_GOLD
        assertEquals(authored, MimirThemeId.DEFAULT.ctaMaterial())
    }

    @Test fun contrast_ratio_matches_known_anchors() {
        assertEquals(21.0, contrastRatio(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black), 0.01)
        assertEquals(1.0, contrastRatio(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.White), 0.01)
    }
}
