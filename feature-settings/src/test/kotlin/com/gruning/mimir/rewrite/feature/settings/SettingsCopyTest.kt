package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.enabledFor
import com.gruning.mimir.rewrite.core.ui.resolve
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCopyTest {
    @Test
    fun `static settings labels use the capped runic lane`() {
        assertEquals("COLOR & FOCUS", settingsStaticHeader("Color & focus"))
        assertEquals("3DO", settingsStaticHeader("3DO"))
        assertEquals("15 seconds", settingsDynamicValue("15 seconds"))
    }

    @Test
    fun `RetroAchievements preserves the screen title`() {
        assertEquals("RetroAchievements", RetroAchievementsTitle)
        val resolved = RetroAchievementsTitleIntent.resolve()
        assertEquals("RetroAchievements", resolved.visualText)
        assertEquals("RetroAchievements", resolved.spokenText)
    }

    @Test
    fun `storage and ScreenScraper bridge copy explain ownership and actions`() {
        assertEquals("Managed by Mimir on this device", MimirManagedArtworkStorageCopy)
        assertEquals("Stay in your chosen folders · never sent online", MimirGameFilesStorageCopy)
        assertEquals(
            "Rebuild reachable folders. Unreachable folders stay registered; only folders proven missing leave Mimir’s index. Game files stay untouched.",
            MimirLibraryRescanSupportingCopy,
        )
        assertEquals(
            "Connect your ScreenScraper account to use enrichment.",
            screenScraperProfileSupportingCopy(null),
        )
        assertEquals(
            "Connected as Folkvangr. Downloads stay on this device after disconnecting.",
            screenScraperProfileSupportingCopy(" Folkvangr "),
        )
        assertEquals(
            "Download missing descriptions and artwork for games in your library.",
            ScreenScraperDownloadSupportingCopy,
        )
    }

    @Test
    fun `hidden-game platforms preserve registered display labels and never raw slugs`() {
        assertEquals(
            "Super Nintendo Entertainment System",
            settingsPlatformLabel("Super Nintendo Entertainment System"),
        )
        assertEquals("Game Boy Advance", settingsPlatformLabel("Game Boy Advance"))
        assertEquals("Unknown platform", settingsPlatformLabel(null))
    }

    @Test
    fun `receipt values stack before compact square text can collapse`() {
        assertEquals(true, settingsReceiptStacks(296f))
        assertEquals(false, settingsReceiptStacks(420f))
        assertEquals(false, settingsReceiptStacks(640f))
    }

    @Test
    fun `focus dial preserves all seven targets in a settled compact window`() {
        assertEquals(12, colorDialPanelPaddingDp(360f))
        assertEquals(24, colorDialPanelPaddingDp(538f))
        val compactContentWidth = 360 - colorDialPanelPaddingDp(360f) * 4
        assertEquals(312, compactContentWidth)
        assertEquals(7, com.gruning.mimir.rewrite.core.ui.MimirRingStop.entries.size)
        // The scroll content carries the ring's 14 dp reach on both sides: 7 x 52 + 28 = 392.
        assertEquals(0, colorDialScrollTargetPx(0, 7, 52f, compactContentWidth.toFloat(), 14f))
        assertEquals(40, colorDialScrollTargetPx(3, 7, 52f, compactContentWidth.toFloat(), 14f))
        assertEquals(80, colorDialScrollTargetPx(6, 7, 52f, compactContentWidth.toFloat(), 14f))
        assertEquals(false, colorDialRowCenters(7, 52f, compactContentWidth.toFloat(), 14f))
        assertEquals(true, colorDialRowCenters(7, 52f, 592f, 14f))
        assertEquals(true, colorDialRowCenters(7, 52f, 392f, 14f))
        assertEquals(false, colorDialRowCenters(7, 52f, 391f, 14f))
        // Every detent, at its own scroll target, keeps its whole ring (13.5 dp past the seat) in view.
        val total = 7 * 52f + 28f
        for (i in 0 until 7) {
            val scroll = colorDialScrollTargetPx(i, 7, 52f, compactContentWidth.toFloat(), 14f)
            val seatLeft = 14f + i * 52f - scroll
            org.junit.Assert.assertTrue("detent $i left ring", seatLeft - 13.5f >= 0f)
            org.junit.Assert.assertTrue("detent $i right ring", seatLeft + 52f + 13.5f <= compactContentWidth)
            org.junit.Assert.assertTrue("detent $i scroll in range", scroll in 0..(total - compactContentWidth).toInt())
        }
    }

    @Test
    fun `focus dial legend names the D-pad that steps it and no shoulder`() {
        assertEquals("D-pad Adjust · A Keep · B Back", ColorDialControlHint)
        org.junit.Assert.assertFalse("L/R is a shoulder token", ColorDialControlHint.contains("L/R"))
        // A pad whose shoulders are L1/R1 still reads D-pad: the legend has no token a map could rewrite.
        val shoulders = com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels(
            mapOf("L/R" to "L1/R1", "A" to "A", "B" to "B"),
        )
        assertEquals("D-pad Adjust · A Keep · B Back", shoulders.rewrite(ColorDialControlHint))
        // And the D-pad is what steps it.
        val palette = com.gruning.mimir.rewrite.core.ui.MimirThemePalette.forId(com.gruning.mimir.rewrite.core.ui.MimirThemeId.DEFAULT)
        val stops = com.gruning.mimir.rewrite.core.ui.MimirRingStop.entries.filter { it.enabledFor(palette) }
        var stepped: com.gruning.mimir.rewrite.core.ui.MimirRingStop? = null
        dispatchColorDial(
            focusedStop = stops.first(),
            authoredPalette = palette,
            action = com.gruning.mimir.rewrite.core.input.ControllerAction.NAV_RIGHT,
            onStepTo = { stepped = it },
            onCommit = {},
            onDismiss = {},
        )
        assertEquals(stops[1 % stops.size], stepped)
    }

    @Test
    fun `credential keyboard collapses duplicate preview on square`() {
        assertEquals(true, settingsKeyboardPreviewCompact(360f))
        assertEquals(true, settingsKeyboardPreviewCompact(420f))
        assertEquals(false, settingsKeyboardPreviewCompact(421f))
    }

    @Test
    fun `platform emulator title never falls back to a resolver slug`() {
        assertEquals("Super Nintendo Entertainment System emulator", platformEmulatorPickerTitle("Super Nintendo Entertainment System"))
        assertEquals("Platform emulator", platformEmulatorPickerTitle(null))
        assertEquals("Platform emulator", platformEmulatorPickerTitle("  "))
    }

    @Test
    fun `action row title matches Hero Motion on the capped Body step and nothing else moves`() {
        // supersedes the earlier "13sp action lane". That bespoke size existed
        // only to match HERO MOTION back when Row was 15sp and Support 13sp; the capped scale puts both
        // on one Body step, so matching HERO MOTION now means simply BEING RowTitle.
        assertEquals(MimirType.RowTitle.fontSize, MimirType.SettingsActionTitle.fontSize)
        assertEquals(MimirType.RowTitle.fontSize, MimirType.SettingsReceiptValue.fontSize)
        // The shared ramp is still the control — it just reads at the capped sizes now.
        assertEquals(14f, MimirTypeScale.Row.fontSize.value, 0f)
        assertEquals(14f, MimirType.RowTitle.fontSize.value, 0f)
        // Subtext rose 13 -> 14 to sit level with its title. This is a shared type-role change, not rounding.
        assertEquals(14f, MimirType.RowSupport.fontSize.value, 0f)
        assertEquals(MimirType.RowTitle.fontWeight, MimirType.RowSupport.fontWeight)
        assertEquals(11f, MimirType.RunicEyebrow.fontSize.value, 0f)
        assertEquals(14f, MimirType.TitleCaps.fontSize.value, 0f)
        // Weight/tracking are inherited from RowTitle — only the size lane changed.
        assertEquals(MimirType.RowTitle.fontWeight, MimirType.SettingsActionTitle.fontWeight)
        assertEquals(MimirType.RowTitle.letterSpacing, MimirType.SettingsActionTitle.letterSpacing)
        assertEquals(MimirType.RowTitle.fontWeight, MimirType.SettingsReceiptValue.fontWeight)
        assertEquals(MimirType.RowTitle.letterSpacing, MimirType.SettingsReceiptValue.letterSpacing)
    }

    @Test
    fun `SteamGridDB validation feedback never calls a saved key connected`() {
        assertEquals(null, steamGridDbKeyValidationCopy(SteamGridDbKeyValidationUiState.IDLE))
        assertEquals(
            "KEY NOT ACCEPTED · CHECK IT AND TRY AGAIN",
            steamGridDbKeyValidationCopy(SteamGridDbKeyValidationUiState.REJECTED),
        )
        assertEquals(
            "STEAMGRIDDB COULDN’T BE REACHED · TRY AGAIN",
            steamGridDbKeyValidationCopy(SteamGridDbKeyValidationUiState.UNREACHABLE),
        )
    }
}
