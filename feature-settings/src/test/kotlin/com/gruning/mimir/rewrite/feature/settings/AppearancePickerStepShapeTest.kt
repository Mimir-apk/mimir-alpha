package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.FocusKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appearance pickers as reviewed on Nova: the Dim and Blur steps are rounded RECTANGLES with the
 * product ring hugging the visible rectangle, not capsules with a ring floating around their 48 dp target; the Upload tile
 * is a `+` over one word with no inner box to clip; the only circle left in the two pickers is the button's coin, which retains its circular geometry. Source pins, the module's idiom (no Compose harness here), plus the step geometry as numbers.
 */
class AppearancePickerStepShapeTest {

    @Test
    fun `a step is a rectangle centred in its 48 dp target, and the corner is a small card corner`() {
        val geometry = appearancePickerStepGeometry()
        assertEquals(48f, geometry.targetHeightDp, 0f)
        assertEquals(32f, geometry.stepHeightDp, 0f)
        assertEquals("the rectangle sits 8 dp inside the target top and bottom", 8f, geometry.verticalInsetDp, 0f)
        assertTrue("a rectangle, never a capsule: the corner is well under half the height", geometry.rectangular)
        assertEquals(6f, geometry.cornerDp, 0f)
    }

    @Test
    fun `the ring sits on the visible step, on its own corner, before its clip -- and the target only registers and taps`() {
        val picker = source("BackgroundPicker.kt")
        val step = picker.substringAfter("private fun StepBox(").substringBefore("fun dispatchBackgroundPicker(")
        // The outer box is the target: the focus key and the tap, no ring.
        val target = step.substringAfter("Box(").substringBefore("contentAlignment = Alignment.Center,")
        assertTrue(target.contains(".height(MimirSpacing.minTarget)"))
        assertTrue(target.contains(".then(focusable)"))
        assertTrue(target.contains(".mimirActivatable("))
        assertFalse("the target wears no ring", target.contains(".mimirSelectionRing("))
        // The inner box is the step: sized, ringed on its rectangle, then clipped on the same symbol.
        val ringAt = step.indexOf(".mimirSelectionRing(")
        assertTrue(ringAt > 0 && step.indexOf(".mimirSelectionRing(", ringAt + 1) < 0)
        assertTrue("the ring is sized by the visible step", step.indexOf(".height(AppearancePickerStepHeight)") in 1 until ringAt)
        assertTrue("ring before clip", ringAt < step.indexOf(".clip(shape)"))
        assertTrue(step.contains("shape = MimirRingShape.Card(AppearancePickerStepCorner),"))
        assertTrue("one symbol for the clip and the ring (R-1)", step.contains("val shape = RoundedCornerShape(AppearancePickerStepCorner)"))
        assertTrue("a greyed step dims through the ring's rest dim", step.contains("restingAlpha = if (enabled) 1f else AppearancePickerStepDisabledAlpha,"))
        assertFalse("no alpha layer over the ring (walk-20)", picker.contains(".alpha("))
        assertTrue("the selected step keeps its accent fill and border; focus is the ring", step.contains("if (selected) palette.accentBright else palette.outline"))
    }

    @Test
    fun `no capsule or circle is left in the two pickers but the coin`() {
        val background = source("BackgroundPicker.kt")
        val template = source("AppearanceImagePicker.kt")
        val button = source("MimirButtonPicker.kt")
        listOf(background, template).forEach { file ->
            assertFalse(file.contains("RoundedCornerShape(50)"))
            assertFalse(file.contains("MimirRingShape.Pill"))
            assertFalse(file.contains("CircleShape"))
        }
        assertFalse(button.contains("MimirRingShape.Pill"))
        val coin = button.substringAfter("fun MarkCoin(").substringBefore("private fun StruckMarkThumbnail(")
        assertTrue("the round coin stays: its geometry is preserved", coin.contains("CircleShape"))
        val outsideCoin = button.substringBefore("fun MarkCoin(").lineSequence().filterNot { it.startsWith("import ") }.joinToString("\n") +
            button.substringAfter("private fun StruckMarkThumbnail(")
        assertFalse("and it is the only circle in the button picker", outsideCoin.contains("CircleShape"))
        // The ✕ on a focused tile is a rounded square on the step corner.
        val remove = template.substringAfter("if (focused && tile is AppearancePickerTile.Saved) {").substringBefore("Spacer(Modifier.height(2.dp))")
        assertTrue(remove.contains("val removeShape = RoundedCornerShape(AppearancePickerStepCorner)"))
        assertTrue(remove.contains(".size(MimirSpacing.minTarget)"))
    }

    @Test
    fun `the Upload tile is a plus over one word, centred, with no inner box`() {
        val template = source("AppearanceImagePicker.kt")
        val add = template.substringAfter("is AppearancePickerTile.Add -> {").substringBefore("if (badge != null) {")
        assertFalse("no inner border to crowd the word", add.contains(".border("))
        assertTrue(add.contains("AppearanceUploadGlyph"))
        assertTrue(add.contains("AppearanceUploadLabel"))
        // The glyph is the largest step of the scale (Display), held at that size in dp whatever the font scale: a symbol, not reading text, so at 1.3 the `+` over
        // "Upload" still stands inside a 48 dp compact tile instead of being cut at its bottom edge.
        assertTrue("the glyph is the largest step of the scale", add.contains("MimirTypeStep.Display.value / LocalDensity.current.fontScale") && add.contains("fontSize = plusSize"))
        assertTrue("centred", add.contains("horizontalAlignment = Alignment.CenterHorizontally") && add.contains("verticalArrangement = Arrangement.Center"))
        assertEquals("one line each, so nothing wraps into a clip", 2, Regex("softWrap = false").findAll(add).count())
        assertEquals("+", AppearanceUploadGlyph)
        assertEquals("Upload", AppearanceUploadLabel)
        assertEquals("Upload an image", appearanceTileSpokenLabel(AppearancePickerTile.Add(FocusKey("test/add")), defaultIsCurrent = true))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
        File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
    ).first(File::isFile).readText()
}
