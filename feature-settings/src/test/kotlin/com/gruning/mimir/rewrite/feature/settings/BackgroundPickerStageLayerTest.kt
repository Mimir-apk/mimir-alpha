package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Background picker's stage: the ground is the real shelf bake at stage size
 * and the ONLY thing that cross-fades; the chrome -- the host's real shelf pieces, or the drawn fallback -- stands above
 * the cross-fade in the stage's own box, static, so it is drawn once and never sits in a partial-alpha layer that would
 * clip a tile ring's bloom.
 */
class BackgroundPickerStageLayerTest {

    private fun source(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name").readText()

    @Test
    fun `the ground cross-fades on its own and the chrome stands above it, from the host or the fallback`() {
        val picker = source("BackgroundPicker.kt")
        val stage = picker.substringAfter("stage = {").substringBefore("defaultTile = {")
        val fade = stage.substringAfter("Crossfade(").substringBefore("if (stageChrome != null)")
        assertTrue("each fading layer is the real shelf background at stage size", fade.contains("MimirShelfBackground(modifier = Modifier.fillMaxSize(), palette = palette, ground = ground) {}"))
        assertFalse("with nothing drawn inside the fade", fade.contains("BackgroundStageChrome(") || fade.contains("stageChrome("))
        assertTrue("the fade is the house scene beat", fade.contains("tween<Float>(MimirMotion.SceneMs, easing = MimirMotion.Easing)"))
        assertTrue("a cut under reduced motion", fade.contains("if (reduced) snap<Float>()"))
        assertTrue(
            "the chrome is the host's, else the fallback, after the fade",
            stage.contains("if (stageChrome != null) stageChrome() else BackgroundStageChrome(palette, state.themeId.label)"),
        )
        assertTrue("the slot is a stage-box lambda, null for the fallback", picker.contains("stageChrome: (@Composable BoxScope.() -> Unit)? = null,"))
    }
}
