package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the Bottom's hosts fade, in source (audit RC-35, RC-59): the pixel tests prove the fades; these pin that the animated value is read in the draw phase and never in
 * composition, and that nothing has gone back to unmounting on the commit frame. The Robolectric canvas cannot draw `GraphicsLayer` or blur, so these are the cheap guard on
 * the shape.
 */
class BottomHostFadeSourcePinTest {

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `the sort menu reads its fade in the layer, not in composition`() {
        val bento = source("CompanionActivityBento.kt")
        assertFalse("no animated alpha in a composition guard", bento.contains("menuAlpha > 0.001f"))
        assertTrue(bento.contains("if (menuOpen || menuFade.mounted)"))
        assertTrue(bento.contains(".graphicsLayer { alpha = menuFade.alpha }"))
    }

    @Test
    fun `the toast keeps its last plate for the fade out and reads the fade in the layer`() {
        val toast = source("RetroAchievementsToastPlacement.kt")
        assertTrue(toast.contains("val shown = toast ?: last[0]"))
        assertTrue(toast.contains("if (shown != null && (toast != null || fade.mounted)) AchievementToastPlate(shown, badgeSource, fade)"))
        assertTrue(toast.contains(".graphicsLayer { alpha = fade.alpha }"))
        assertFalse("the plate is no longer composed only while a toast is held", toast.contains("content = { if (toast != null) AchievementToastPlate("))
    }

    @Test
    fun `the recent-app tile is not unmounted by a cover, it fades and goes inert`() {
        val surface = source("CompanionWorkspaceSurface.kt")
        assertFalse("not mounted by the overlay flag", surface.contains("if (showRecentAppOverlay && page == CompanionWorkspacePage.ACTIVITY)"))
        assertTrue(surface.contains("if (page == CompanionWorkspacePage.ACTIVITY && recentAppShownOnce[0])"))
        assertTrue(surface.contains(".graphicsLayer { alpha = recentAlpha }"))
        assertTrue("faded out it takes no tap", surface.contains("enabled = navigationEnabled && showRecentAppOverlay"))
        assertTrue("and its icon comes from the shared warm path, which fades a cold arrival (Q13)", surface.contains("CompanionAppIconArt(entry.iconPath, Modifier.size(iconSize))"))
        assertFalse("no per-composition Coil load for it", surface.contains("model = entry.iconPath"))
    }

    @Test
    fun `the hub's pickers and keyboard leave on a fade and the hub holds one scrim on its glass`() {
        val hub = source("CompanionActivityAchievementHub.kt")
        assertFalse("no picker is dropped on the commit frame", hub.contains("    if (!open) return\n    ActivityConsolePicker("))
        assertFalse("nor the Show picker", hub.contains("    if (!open) return\n    ActivityShowPicker("))
        assertFalse("nor the keyboard", hub.contains("    if (!open) return\n    val palette = LocalMimirPalette.current\n    Box(\n        modifier\n            .fillMaxWidth()"))
        assertTrue("the keyboard is a sheet", hub.contains("MimirSheetHost(visible = open, reducedMotion = reducedMotion, modifier = modifier)"))
        assertTrue("one scrim, on the page's glass", hub.contains("if (showHeader) MimirGlassScrim(progress = glass)"))
        assertTrue("the pickers do not draw their own", Regex("drawScrim = false").findAll(hub).count() == 2)
        assertTrue("the glass holds the dissolve lead, on the animation clock", hub.contains("mimirHold(MimirMotion.OverlayDissolveCoverMs)"))
        assertFalse("not on a coroutine delay, which ignores the animator scale", hub.contains("delay(MimirMotion.OverlayDissolveCoverMs"))
        // The picker host is the shared fade-mount (art-fade pass: ActivityPickerFadeHost folded into MimirFadeMount), resting whole: no second fade on the way in under the panel's own entry.
        assertTrue("the picker host is the shared fade-mount, resting whole", hub.contains("rememberMimirFadeMount(visible = open, reducedMotion = reducedMotion, restsWhole = true)"))
        assertFalse("with no private fade of its own", hub.contains("val alpha = remember { Animatable(1f) }"))
        val mount = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirFadeMount.kt"),
            File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirFadeMount.kt"),
        ).first(File::isFile).readText()
        assertTrue("a closed picker's host is made whole again once it is unmounted", Regex("""fade\.mounted = false\s+(//[^\n]*\s+)?if \(restsWhole\) fade\.progress\.snapTo\(1f\)""").containsMatchIn(mount))
        assertTrue(hub.contains("rememberMimirGlassProgress(visible = pickerOpen || pickerGlassHeld.value, identity = pickerEpoch)"))
        val header = source("CompanionActivityAchievementFilterHeader.kt")
        assertTrue("the shared picker still draws its scrim by default (the Customize media page)", header.contains("drawScrim: Boolean = true,"))
    }
}
