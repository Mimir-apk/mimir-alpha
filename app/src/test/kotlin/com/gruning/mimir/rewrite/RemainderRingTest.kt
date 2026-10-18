package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remainder of the ring migration : the quick-launch tiles, the tome library's Create cell,
 * onboarding's two fields, the recovery gate's and the import page's buttons -- one
 * selection ring each, before their clip, no grow; the radial glow gone from the product. Source pins.
 */
class RemainderRingTest {

    @Test
    fun `no app surface wears the old ring, the glass row ring, or the radial glow`() {
        listOf(
            "HomeQuickLaunchPickerOverlay.kt", "HomeTomeLibraryOverlay.kt", "OnboardingFlow.kt",
            "HomeTileMenuOverlay.kt", "DatabaseRecoveryGate.kt", "MigrationImport.kt", "HomeCustomEnrichPicker.kt",
            "BrightnessOverlay.kt",
        ).forEach { name ->
            val code = codeOf(name)
            assertFalse("$name still calls mimirFocusRing", code.contains(".mimirFocusRing("))
            assertFalse("$name still carries the radial glow", code.contains("plainMenuFocus"))
        }
        assertFalse(codeOf("HomeTomeLibraryOverlay.kt").contains(".mimirGlassRow(focused, shape)"))
    }

    @Test
    fun `the quick-launch tile rings as a card on its named corner, with no grow`() {
        val code = codeOf("HomeQuickLaunchPickerOverlay.kt")
        assertTrue(code.contains("identity = item.stableId, shape = MimirRingShape.Card(HomeQuickLaunchTileCorner)"))
        assertTrue(code.contains("val shape = RoundedCornerShape(HomeQuickLaunchTileCorner)"))
        assertFalse(code.contains("focusedScale"))
    }

    @Test
    fun `onboarding's fields ring before their clip and paint their own glass`() {
        val code = codeOf("OnboardingFlow.kt")
        val name = code.substringAfter("private fun OnboardingNameField(").substringBefore("contentAlignment = Alignment.CenterStart")
        assertTrue(name.indexOf(".mimirSelectionRing(") < name.indexOf(".clip(shape)"))
        assertTrue(name.contains("restingAlpha = MimirFocusRestingAlpha"))
        // w_rev_ring_2: the resting hairline the name field's KDoc says the ring provides.
        assertTrue(name.contains("restingOutline = true"))
        assertTrue(name.contains("fill = SolidColor(palette.surfaceRaised.copy("))
        val search = code.substringAfter("key = OnboardingFavoritesSearchKey, scope = OnboardingScope)").substringBefore("verticalAlignment")
        assertTrue(search.contains("shape = MimirRingShape.Row(MimirSpacing.radiusCard)"))
        assertTrue(search.indexOf(".mimirSelectionRing(") < search.indexOf(".clip(shape)"))
        // the platform filter and its options keep the metallic ellipse and lift, ring-free, until design
        // review settles that against the ring; only their second focus node went.
        assertTrue(code.contains(".mimirActivatable(label = stringResource(R.string.app_onboarding_favorites_filter_label))"))
        assertTrue(code.contains(".mimirActivatable(label = stringResource(R.string.app_onboarding_favorites_filter_choose))"))
        assertTrue(code.contains("authority.requestFocus(OnboardingFavoritesPlatformFilterKey)"))
    }

    @Test
    fun `the radial glow is gone from the product`() {
        assertFalse(codeOf("HomeTileMenuOverlay.kt").contains("fun Modifier.plainMenuFocus("))
    }

    @Test
    fun `the recovery gate and the import page ring in the palette, where a static border was`() {
        // Update: both buttons are etched chips now, ringed on their plate as a Pill (they were filled rounded rectangles with a Row ring).
        val gate = codeOf("DatabaseRecoveryGate.kt")
        assertTrue(gate.contains("MimirEtchedChipPlate("))
        assertTrue(gate.contains("shape = MimirRingShape.Pill"))
        assertFalse("the close button is a chip, never a fill", gate.contains("MimirRingShape.Row(") || gate.contains("fill = SolidColor"))
        assertFalse(gate.contains("palette.focusRing"))
        val page = codeOf("MigrationImport.kt")
        val button = page.substringAfter("private fun ActionButton(")
        assertTrue(button.contains("MimirEtchedChipPlate("))
        assertTrue(button.contains("shape = MimirRingShape.Pill"))
        assertFalse("the import buttons are chips, never a fill", button.contains("fill = SolidColor") || button.contains(".clip("))
        assertTrue(page.contains("enabled = live, authority = authority, key = key, onActivate = onClick"))
        assertFalse("the static gold ring retired with the border", page.contains("MimirColors.Gold,") || page.contains("MimirColors.Gold\n"))
    }

    @Test
    fun `the surfaces one press from a migrated ring wear it too, in the one colour`() {
        // The review of 88ad8d5d8: the old ring's literal was not the only old look. palette.focusRing (accent, not
        // tileFocusRing), the 2 dp in-bounds focus border and a private mimirFocusTileRing were the others.
        listOf(
            "HomeQuickLaunchOverlay.kt", "CompanionOverlayPermissionPrompt.kt",
            "GameTitleEditOverlay.kt",
        ).forEach { name ->
            val code = codeOf(name)
            assertFalse("$name still calls mimirFocusRing", code.contains(".mimirFocusRing("))
            assertFalse("$name rings in palette.focusRing", code.contains("palette.focusRing"))
            assertFalse("$name draws the old in-bounds focus border", code.contains("MimirSpacing.focusRingWidth"))
            assertFalse("$name draws a private tile ring", code.contains(".mimirFocusTileRing("))
        }

        val dockFile = codeOf("HomeQuickLaunchOverlay.kt")
        assertTrue(dockFile.contains(".padding(horizontal = rowRingInset, vertical = MimirFocusTileRingReach)"))
        val dock = dockFile.substringAfter("private fun HomeQuickLaunchSlotRow(")
        assertTrue(dock.contains("identity = homeQuickLaunchSlotKey(slot.index),"))
        assertTrue(dock.contains("shape = MimirRingShape.Row(MimirSpacing.radiusCard),"))
        assertTrue(dock.indexOf(".mimirSelectionRing(") in 0 until dock.indexOf(".clip(shape)"))
        assertTrue("the reveal covers the ring", dock.contains("Rect(-ringReachPx, -ringReachPx,"))

        // Update: the permission prompt's two answers are etched chips ringed on their plate as a Pill (they were filled rounded rectangles).
        val prompt = codeOf("CompanionOverlayPermissionPrompt.kt").substringAfter("private fun PromptButton(")
        assertTrue(prompt.contains("MimirEtchedChipPlate("))
        assertTrue(prompt.contains("shape = MimirRingShape.Pill"))
        assertFalse("the prompt buttons are chips, never a fill", prompt.contains("filled = primary") || prompt.contains("SolidColor(palette.accent)") || prompt.contains(".clip("))
        assertFalse("the recoloured border retired", prompt.contains(".border("))

        val title = codeOf("GameTitleEditOverlay.kt")
        assertFalse("the 0.14 ink tint retired", title.contains("resetInk"))
        val reset = title.substringAfter("Modifier.mimirFocusable(authority, GameTitleEditResetKey, MimirKeyboardFocusScope)")
        assertTrue(reset.contains("identity = GameTitleEditResetKey,"))
        assertTrue(reset.indexOf(".mimirSelectionRing(") in 0 until reset.indexOf(".clip(RoundedCornerShape(MimirSpacing.radiusCard))"))
    }

    private fun codeOf(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
}
