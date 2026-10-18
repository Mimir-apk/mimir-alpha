package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The empty shelf's "Open library" action. After the ring migration  it wore one ring and had one tap path; after update (no filled blob, and the sweep
 * of 10-09: it turned solid gold when focused) it is the PRIMARY etched chip, its ring a pill on the plate itself.
 */
class HomeShelfActionRingTest {

    private val code = listOf(
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
    ).first(File::isFile).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    /** The composable's own body: the next declaration ends the window (the hint ring below it keeps its colour). */
    private val action = code.substringAfter("internal fun HomeShelfEmptyAction(").substringBefore("@Composable")

    @Test
    fun `the empty shelf action is the primary etched chip, ringed on its plate, and taps through the helper`() {
        assertTrue(action.contains(".mimirSelectionRing(focused = focused, identity = HomeShelfOpenLibraryKey, shape = MimirRingShape.Pill, edgeFlush = true)"))
        assertTrue(action.contains(".mimirEtchedChipSurface(palette, focused = focused, primary = true, edgeFlush = true)"))
        // The ring is on the plate and before the surface, so its edge is the plate's edge and its wash is under the opaque metal.
        assertTrue(action.indexOf(".mimirSelectionRing(") < action.indexOf(".mimirEtchedChipSurface("))
        assertTrue(action.contains("key = HomeShelfOpenLibraryKey, onActivate = onOpenLibrary"))
        assertFalse(action.contains("palette.focusRing"))
    }

    @Test
    fun `nothing about it is filled, tinted or animated to a fill, and its word is the primary ink`() {
        assertFalse("no solid accent, no raised ground", action.contains(".background(") || action.contains("animateColorAsState") || action.contains("palette.accent,"))
        assertFalse("no hairline border, no gap-ring for a filled pill", action.contains(".border(") || action.contains("filled = true"))
        assertTrue(action.contains("color = palette.etchedChipInk(primary = true)"))
        assertFalse("the label no longer flips to the on-accent ink", action.contains("onAccent"))
        assertTrue(action.contains("style = MimirEtchedChipLabelStyle"))
        assertFalse(code.contains("HomeShelfActionShape"))
    }
}
