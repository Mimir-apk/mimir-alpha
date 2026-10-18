package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * There is one live blur under pickers, while the card behind is frozen into a snapshot. The launch card behind OPEN WITH is held as a
 * SNAPSHOT while its picker is up. The Library no longer draws the glass itself (W2A #9): the card's host hands core's one `mimirGlassBackdropProgress` overload its
 * `snapshot` intent, and core freezes at the glass's peak and thaws on the first clearing frame (`mimirGlassSurfaceFrozen`, pinned by core-ui's MimirGlassSurfaceFreezeTest). Home calls
 * the same overload with `snapshot = { true }`, and the draw fix for the sharp layer (the OPEN WITH frost hard-cut) reaches this card through it.
 *
 * What is Library's to prove is its intent and its wiring, and that is what this does: the predicate (picker up, card arrived, nothing leaving) as a truth table, the host's
 * call into the core overload, that no copy of the glass draw is left in the module, and that the call site names the pickers' scopes. How many times the card is RECORDED across a
 * ramp is core's rule and is not countable here (a JVM software canvas refuses hand-recorded layers); it is owed to the device, with the Thor A/B.
 */
class LibraryCardGlassFreezeTest {

    private fun code(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `the card is a snapshot only with its picker up, fully arrived and nothing leaving`() {
        assertTrue(libraryCardSnapshotWanted(pickerOwnsInput = true, arrived = true, leaving = false))
        assertFalse("no picker: the card is live", libraryCardSnapshotWanted(pickerOwnsInput = false, arrived = true, leaving = false))
        assertFalse("a card still fading in is not a still image", libraryCardSnapshotWanted(pickerOwnsInput = true, arrived = false, leaving = false))
        assertFalse("a card with a page leaving it is changing", libraryCardSnapshotWanted(pickerOwnsInput = true, arrived = true, leaving = true))
        assertFalse(libraryCardSnapshotWanted(pickerOwnsInput = false, arrived = false, leaving = true))
    }

    @Test
    fun `the module keeps no copy of the glass draw, so a fix in core reaches the card`() {
        val holder = code("LibraryCardGlass.kt")
        assertFalse(holder.contains("obtainGraphicsLayer") || holder.contains("drawWithCache") || holder.contains("BlurEffect") || holder.contains(".record {"))
        for (name in listOf("LibraryCardGlass.kt", "LibraryScreen.kt")) {
            val text = code(name)
            assertFalse("$name still names the forked modifier", text.contains("libraryCardGlass(") || text.contains("LibraryCardGlassPlan") || text.contains("libraryCardGlassRecords"))
        }
    }

    @Test
    fun `the card's host calls core's freeze overload, built once, with the pickers' intent`() {
        val screen = code("LibraryScreen.kt")
        val host = screen.substringAfter("internal fun <T> LibraryOverlayPixelFadeTransition(").substringBefore("\n@Composable")
        assertTrue("built once, keyed on what its lambdas close over", host.contains("val cardGlassModifier = remember(glass, incomingAlpha) {"))
        assertTrue(host.contains("Modifier.mimirGlassBackdropProgress("))
        assertTrue("the same radius the scene's glass peaks at", host.contains("maxRadius = { MimirGlass.backdropBlurRadius },"))
        assertTrue(host.contains("progress = glass.progress,"))
        assertTrue(
            "the intent: picker up, card arrived, nothing leaving; core adds the peak",
            host.contains("snapshot = { libraryCardSnapshotWanted(glass.snapshot(), incomingAlpha.value >= 1f, outgoingVisibleNow.value) },"),
        )
        assertTrue("on the card's own box", host.contains("Box(modifier.then(cardGlassModifier).fillMaxSize()) {"))
    }

    @Test
    fun `the picker host names the pickers' scopes as the snapshot request, and the shared per-draw glass is gone from the card`() {
        val screen = code("LibraryScreen.kt")
        assertTrue(screen.contains("glass = pickerCardGlass,"))
        assertTrue(
            "the snapshot is requested exactly while a picker scope owns input",
            screen.contains("snapshot = { pickerFocusState.value.scope.let { it == OpenWithScope || it == LaunchChoiceScope } },"),
        )
        assertFalse("the card no longer takes the shared re-recording modifier", screen.contains("modifier = Modifier.mimirGlassBackdropProgress(selectOverlayGlass)"))
    }
}
