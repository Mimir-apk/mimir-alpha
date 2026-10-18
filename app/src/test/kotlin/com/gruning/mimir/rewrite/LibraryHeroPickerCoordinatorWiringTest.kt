package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-2  pins `openLibraryHeroPicker`'s call site
 * itself, not only the pure functions it calls. `MimirAppCoordinator.kt`'s closures are captured inside
 * one large `@Composable` with no Robolectric harness in this module (the same gap
 * `MediaMenuHonestyCoordinatorWiringTest`, `AppBrightnessCoordinatorDeclineFeedbackTest`, and
 * `GameTitleEditOverlayResetWiringTest` document), so this reads the raw source text — labelled as a
 * source characterization, per that same house convention.
 *
 * The behavioural half — does `libraryHeroPickerMenuState` actually land on the top-level menu, and does
 * choosing "Customize media" from it still reach the media picker — is pinned in [LibraryHeroPickerTest]
 * and [MimirAppHomeArtHelpersTest] by driving [reduceHomeTileMenu] directly. This class pins the one thing
 * those tests cannot: that `openLibraryHeroPicker` never hands the real coordinator a synthesized CONFIRM
 * that could advance past the phase `libraryHeroPickerMenuState` returns before the player sees it.
 */
class LibraryHeroPickerCoordinatorWiringTest {

    private fun coordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppCoordinator.kt not found from ${File(".").absolutePath}")
    }

    private fun openLibraryHeroPickerBlock(): String = coordinatorSource()
        .substringAfter("val openLibraryHeroPicker: (GameRow) -> Unit = { game ->")
        .substringBefore("\n    val onHomeTomeLibraryAction")

    /**
     * Differential: restore the removed auto-confirm (`if (homeTileCustomEnrichActions(menu).size <= 1) {
     * onHomeTileMenuAction(ControllerAction.CONFIRM) }`) and this test fails — that call fires
     * synchronously on open, before the player ever sees the top-level menu `libraryHeroPickerMenuState`
     * now returns (S-2), for exactly the branch
     * [LibraryHeroPickerTest.openLibraryHeroPickerCustomEnrichActionsAreAlwaysTwoWide] proves is reachable
     * from every call this coordinator makes today. See that test for why deleting the guard rather than
     * re-arming it was correct: the branch it guarded is dead from this call site, and even if it were
     * not, firing at open time would itself violate S-2 by skipping the menu the player must choose from.
     */
    @Test
    fun openLibraryHeroPickerNeverSynthesizesAConfirm() {
        val block = openLibraryHeroPickerBlock()

        assertFalse(
            "opening the gamelist's Settings menu must show it, never auto-confirm past it -- " +
                "onHomeTileMenuAction must not appear anywhere in openLibraryHeroPicker's body",
            block.contains("onHomeTileMenuAction"),
        )
        assertTrue(
            "must still open on libraryHeroPickerMenuState's own (top-level) phase, not a phase " +
                "reconstructed at the call site",
            block.contains("navigation.openHomeTileMenu(libraryHeroPickerMenuState(game))"),
        )
    }
}
