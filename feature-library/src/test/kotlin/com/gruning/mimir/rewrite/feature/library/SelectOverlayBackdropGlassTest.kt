package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library's select pickers use backdrop glass to blur the layer behind the selection menu.
 *
 * **Why this is a source test and not a behavioural one.** The thing being asserted is Compose layering:
 * that a specific `Modifier.mimirGlassBackdropProgress` sits on the detail-card transition and is driven
 * by the picker scopes. There is no JVM-reachable observable for "this layer is blurred" — the same
 * reason `CollapseBadgeSurfaceLawTest` is written this way.
 *
 * **What made this invisible for so long.** `MimirLiquidGlassPanel` thins its body to 0.78 alpha *because
 * it assumes a blur behind it*, and `mimirGlassBackdrop` has existed in core-ui the whole time — but the
 * app-host allowlist (`mimirOverlayUsesGlass`) is keyed on `MimirOverlayKind`, and the Library's pickers
 * are NOT app-host overlays. They are composed inside `LibraryScreen`'s own modal transition, so no kind
 * is ever active for them and the host blur never armed. `MimirAppHost`'s own comment even asserts the
 * destination blur "already softens Library's card as a side effect" — true when an app-host glass
 * overlay is open, and false for exactly these two pickers.
 */
class SelectOverlayBackdropGlassTest {

    private val librarySource: String by lazy {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt")
        assertTrue("LibraryScreen.kt must be readable from the module dir", file.isFile)
        file.readText()
    }

    /** The detail-card layer — what actually sits behind these modals — carries the backdrop blur. */
    @Test
    fun theDetailCardLayerCarriesABackdropBlur() {
        val declaration = librarySource.substringAfter("val selectOverlayGlass = rememberMimirGlassProgress(", "")
        assertTrue("selectOverlayGlass must exist", declaration.isNotEmpty())

        val transition = librarySource.substringAfter("val selectOverlayGlass", "")
            .substringBefore("val activeDetailState", "")
        assertTrue(
            "the detail transition must wear the picker glass (selectOverlayGlass through core's freeze overload, Q5: the card is held as a snapshot)",
            "progress = selectOverlayGlass," in transition && "glass = pickerCardGlass," in transition,
        )
    }

    /** Driven by the two scopes that ARE this shell, so it arms exactly when a picker is up. */
    @Test
    fun theBlurIsDrivenByBothSelectPickerScopes() {
        // 2026-10-14: the scopes are the REQUEST of a hold ("dissolve, then clear"), and the glass follows the held request,
        // so it clears after the picker has dissolved and not on the frame the commit pops the scope.
        val request = librarySource
            .substringAfter("val selectGlassHeld = rememberMimirGlassHold(", "")
            .substringBefore("val selectOverlayGlass", "")
        assertTrue("OpenWithScope must arm the blur", "OpenWithScope" in request)
        assertTrue("LaunchChoiceScope must arm the blur", "LaunchChoiceScope" in request)
        assertTrue(
            "the blur follows the held request",
            "val selectOverlayGlass = rememberMimirGlassProgress(visible = selectGlassHeld)" in librarySource,
        )
    }

    /**
     * **The load-bearing negative.** The scene Animatable blurs the GAMELIST, its identity is deliberately
     * shared between detail and search, and it already reads 1 when a picker opens over the card. Reusing
     * it here would stack two blurs under nested clips — the exact failure its own B→B race guard exists
     * to prevent — so the picker blur must be its own progress, never a widened `sceneGlassProgress`.
     */
    @Test
    fun thePickerBlurIsSeparateFromTheSceneBlur() {
        val sceneVisible = librarySource
            .substringAfter("val sceneGlassProgress = rememberMimirGlassProgress(", "")
            .substringBefore(")", "")

        assertTrue("the scene blur must still be detail/search only", "presentedDetailChrome" in sceneVisible)
        assertTrue(
            "a picker scope must NOT be folded into the scene blur",
            "LaunchChoiceScope" !in sceneVisible && "OpenWithScope" !in sceneVisible,
        )
    }
}
