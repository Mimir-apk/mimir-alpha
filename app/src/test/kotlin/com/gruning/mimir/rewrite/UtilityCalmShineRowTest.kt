package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Utility's Calm shine row tones down gleam for sensitive players. It is one two-value
 * Utility row, offered right after Navigation hints, reachable by the pad through FocusAuthority, and a press flips it
 * both ways. The default (off) leaves today's shine untouched; what the switch does to the light is core-ui's test.
 */
class UtilityCalmShineRowTest {

    private val state = calmShineTestBrightnessState(media = true)

    private val calm = BrightnessControlKey.CALM_SHINE

    private class Pressed(
        val consumed: Boolean,
        val sets: List<Boolean>,
        val steps: Int,
        val focused: BrightnessControlKey?,
    )

    private fun press(focusOn: BrightnessControlKey, action: ControllerAction, calmShine: Boolean): Pressed {
        val authority = DefaultFocusAuthority()
        authority.pushScope(BrightnessScope)
        // Registration is what makes requestFocus take; without it every press reads a null focus and proves nothing.
        brightnessControls(state).forEach { key ->
            authority.register(brightnessFocusKey(key), BrightnessScope, FocusBounds(0f, 0f, 100f, 40f))
        }
        authority.requestFocus(brightnessFocusKey(focusOn))
        assertEquals(brightnessFocusKey(focusOn), authority.state.value.focused)
        val sets = mutableListOf<Boolean>()
        var steps = 0
        val consumed = dispatchBrightnessOverlay(
            authority = authority,
            state = state,
            action = action,
            onStep = { _, _ -> steps++ },
            onSetThemeAwareJoystickLight = {},
            onRequestWriteAccess = {},
            onRequestPrivilegedAccess = {},
            calmShine = calmShine,
            onSetCalmShine = sets::add,
            onDismiss = {},
        )
        val focused = BrightnessControlKey.entries.firstOrNull {
            brightnessFocusKey(it) == authority.state.value.focused
        }
        return Pressed(consumed, sets, steps, focused)
    }

    @Test
    fun `Utility offers the row right after Navigation hints, in the rail and not the footer`() {
        val controls = brightnessControls(state)
        assertEquals(
            BrightnessControlKey.CALM_SHINE,
            controls[controls.indexOf(BrightnessControlKey.NAVIGATION_HINTS) + 1],
        )
        assertTrue(BrightnessControlKey.CALM_SHINE in brightnessRailControls(state))
    }

    @Test
    fun `the Tweaker panel does not carry the row`() {
        assertFalse(BrightnessControlKey.CALM_SHINE in brightnessControls(state, surface = UtilitySurface.TWEAKER))
        assertFalse(BrightnessControlKey.CALM_SHINE in brightnessRailControls(state, surface = UtilitySurface.TWEAKER))
    }

    @Test
    fun `the pad reaches the row from Navigation hints and leaves it up and down`() {
        val rail = brightnessRailControls(state)
        val at = rail.indexOf(BrightnessControlKey.CALM_SHINE)
        assertTrue("fixture: a rail row follows the switch", at in 1 until rail.lastIndex)

        val down = press(BrightnessControlKey.NAVIGATION_HINTS, ControllerAction.NAV_DOWN, calmShine = false)
        assertEquals(BrightnessControlKey.CALM_SHINE, down.focused)
        val next = press(calm, ControllerAction.NAV_DOWN, calmShine = false)
        assertEquals(rail[at + 1], next.focused)
        val up = press(calm, ControllerAction.NAV_UP, calmShine = false)
        assertEquals(BrightnessControlKey.NAVIGATION_HINTS, up.focused)
        assertTrue(down.consumed && next.consumed && up.consumed)
    }

    @Test
    fun `Left and Right set the switch and stay silent at either end`() {
        // Off: Right turns it on, Left has nothing to do.
        assertEquals(listOf(true), press(calm, ControllerAction.NAV_RIGHT, false).sets)
        assertEquals(emptyList<Boolean>(), press(calm, ControllerAction.NAV_LEFT, false).sets)
        // On: Left turns it off, Right has nothing to do.
        assertEquals(listOf(false), press(calm, ControllerAction.NAV_LEFT, true).sets)
        assertEquals(emptyList<Boolean>(), press(calm, ControllerAction.NAV_RIGHT, true).sets)
        // It is a switch and never a slider: no brightness target steps.
        assertEquals(0, press(calm, ControllerAction.NAV_RIGHT, false).steps)
    }

    @Test
    fun `a press flips it both ways, so it never goes inert once it is on`() {
        assertEquals(listOf(true), press(calm, ControllerAction.CONFIRM, calmShine = false).sets)
        assertEquals(listOf(false), press(calm, ControllerAction.CONFIRM, calmShine = true).sets)
    }

    @Test
    fun `the row says On or Off and names its state for a screen reader`() {
        assertEquals("Off", calmShineValueLabel(false))
        assertEquals("On", calmShineValueLabel(true))
        assertEquals("Reduce shimmer effects off", calmShineStateDescription(false))
        assertEquals("Reduce shimmer effects on", calmShineStateDescription(true))
    }

    @Test
    fun `the row is a compact Utility row in plain words, drawn after Navigation hints and before Lights`() {
        val overlay = source("BrightnessOverlay.kt")
        val hints = overlay.indexOf("label = \"Navigation hints\",")
        val calmAt = overlay.indexOf("label = \"Reduce shimmer effects\",")
        val lights = overlay.indexOf("UtilitySectionLabel(\"Lights\", palette)")
        assertTrue("the rail reveals by index: render follows focus order", hints in 0 until calmAt && calmAt < lights)
        assertTrue(
            "one shared row: its ring, its FocusAuthority modifier and mimirActivatable come with it",
            overlay.substring(0, calmAt).trimEnd().endsWith("UtilityChoiceRow("),
        )
        val row = overlay.substring(calmAt, overlay.indexOf("compactSupporting = true,", calmAt))
        assertTrue(row.contains("supportingText = \"Games sparkle once; icons and menus don’t shimmer.\","))
        assertTrue(row.contains("focused = layerFocusedControl == BrightnessControlKey.CALM_SHINE,"))
        assertTrue(row.contains("modifier = railControlModifier(BrightnessControlKey.CALM_SHINE),"))
        assertTrue(row.contains("onActivate = { onSetCalmShine(!calmShine) },"))
        assertTrue(overlay.contains("UtilitySectionLabel(\"Motion\", palette)"))
        assertTrue("off unless a caller says otherwise", overlay.contains("    calmShine: Boolean = false,"))
        assertFalse("focus is the FocusAuthority's alone", overlay.contains(".focusable("))
        assertFalse(overlay.contains("focusTarget("))
    }

    @Test
    fun `the coordinator and its host hand the row the live switch and one setter`() {
        val coordinator = source("AppBrightnessCoordinator.kt")
        assertEquals(
            "the controller dispatch and the composed host both read the switch",
            2,
            coordinator.split("calmShine = MimirCalmShineSettings.enabled,").size - 1,
        )
        assertTrue(coordinator.contains("onSetCalmShine = ::setCalmShine,"))
        assertTrue(coordinator.contains("onSetCalmShine = coordinator::setCalmShine,"))
        assertTrue(coordinator.contains("AppearanceOverrideStore.setCalmShine(context, enabled)"))
        val host = source("AppBrightnessHost.kt")
        assertTrue(host.contains("BrightnessControlKey.CALM_SHINE -> onSetCalmShine(!calmShine)"))
        assertTrue(host.contains("        calmShine = calmShine,"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

/** A Utility panel state with nothing vendor-specific: the portable rows and, when asked, device media volume. */
internal fun calmShineTestBrightnessState(media: Boolean = false): BrightnessState = BrightnessState(
    capabilities = BrightnessCapabilities(
        canWriteSystemSettings = true,
        mainSystemBrightnessActive = true,
        secondaryWindowAvailable = false,
        secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
        joystickLightSupported = false,
        joystickLightColorSupported = false,
        mimirHapticsSupported = false,
        mimirHapticAmplitudeControl = false,
        mediaVolumeSupported = media,
        mediaVolumeFixed = false,
        mainWriteAccessUseful = true,
        privilegedUtilityPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.READY),
        navSoundSupported = false,
        ambientSoundSupported = false,
    ),
    primaryFraction = 0.5f,
    primaryWindowFallback = null,
    secondaryFraction = null,
    secondaryWindowFallback = null,
    joystickLightFraction = null,
    joystickColorFraction = null,
    joystickColorArgb = null,
    themeAwareJoystickLight = false,
    mimirHapticFraction = null,
    mediaVolumeFraction = if (media) 0.4f else null,
    themeAwareJoystickLightRequested = false,
    themeAwareJoystickLightRestorePending = false,
    ambientSoundFraction = null,
)
