package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy 2026-08-12: `MigrationImport.kt` used to
 * render inside a Compose `androidx.compose.ui.window.Dialog` — a SEPARATE Android window with its own
 * `Window.Callback`. Both of this tree's controller feeds (`MainActivity.dispatchKeyEvent` /
 * `dispatchGenericMotionEvent`) are Activity-window methods, so while that dialog was frontmost,
 * `dispatchMigration` was unreachable from hardware input — every existing test of `dispatchMigration`
 * (`MigrationImportRestoreStateTest`) only pins the REDUCER's pure logic and would pass identically whether
 * the modal was reachable or not. This is exactly the "helper pinned, wiring unpinned" hole the adversarial
 * review found in two other fixes this gate (§3): a JVM unit test cannot dispatch a real hardware `KeyEvent`
 * through a live Android window stack (no device, no Robolectric window-focus model in this module), so the
 * only test-shaped proof available for a WINDOW-BOUNDARY defect is a source characterization of the
 * production container — same pattern `GameTitleEditOverlayLayeringTest`/`GameTitleEditOverlayResetWiringTest`
 * already use in this module for Compose-level wiring a JVM test can't otherwise reach.
 *
 * Each assertion here is chosen to actually fail if the fix is reverted (a plain revert of `MigrationImport.kt`
 * restores the `Dialog` import and call; a plain revert of the `MimirAppHost.kt` hunk drops the `BackHandler`)
 * — not merely to prove `dispatchMigration` exists, which the pre-existing tests already did without catching
 * this defect.
 */
class MigrationImportContainerWiringTest {

    @Test
    fun migrationOverlayNoLongerImportsOrUsesAComposeDialog() {
        val source = migrationImportSource()

        assertFalse(
            "MigrationImport.kt must not import Dialog " +
                "a Dialog is a separate Android " +
                "window neither dispatchKeyEvent nor dispatchGenericMotionEvent (MainActivity.kt) ever reaches.",
            source.contains("import androidx.compose.ui.window.Dialog"),
        )
        assertFalse("no bare Dialog( call either, in case of a wildcard import", source.contains("Dialog("))
        assertFalse("DialogProperties has no purpose without a Dialog", source.contains("DialogProperties"))
    }

    @Test
    fun migrationOverlayRendersAsAnInlineScrimBoxMatchingItsSiblings() {
        val source = migrationImportSource()

        // Same shape as CompanionOverlayPermissionPrompt / ArtworkConnectOverlay / ProfileNameEditModal: a
        // full-screen Box painted with a real scrim colour in THIS composition (so the shared pixel-fade
        // host actually records visible pixels — see migrationOverlayIsRegisteredForTheSharedPixelFade
        // below), not an empty layer behind a dialog window's own platform dim.
        assertTrue(
            "expected the same scrim convention as ArtworkConnectOverlay/ProfileNameEditModal",
            source.contains(".background(MimirColors.SurfaceBase.copy(alpha = 0.95f))"),
        )
        assertTrue(source.contains("contentAlignment = Alignment.Center"))
    }

    @Test
    fun migrationInteractiveIsTheSingleSharedGuardAcrossDispatchAndTheOverlay() {
        val source = migrationImportSource()

        // dispatchMigration and MigrationImportOverlay must both defer to the same helper — not each keep
        // their own copy of the Reading/Applying-are-non-dismissable rule, which is exactly how two guards
        // drift (the same class of bug the adversarial review flagged for the two "clean" verdicts that
        // turned out to disagree with each other).
        assertTrue(source.contains("internal fun migrationInteractive(state: MigrationUiState): Boolean"))
        val dispatchBody = source.substringAfter("internal fun dispatchMigration(")
        assertTrue(dispatchBody.contains("val interactive = migrationInteractive(state)"))
        val overlayBody = source.substringAfter("internal fun MigrationImportOverlay(")
        assertTrue(overlayBody.contains("val interactive = migrationInteractive(state)"))
    }

    @Test
    fun appHostRegistersABackHandlerForMigrationBeforeComposingTheOverlay() {
        // Preserve the escape: source review corrects the original finding — B/BACK likely already dismissed the Dialog via its own dismissOnBackPress path, so
        // going inline must not silently drop that. A Dialog registers its own OnBackPressedCallback for
        // free; a plain inline Box does not, so the call site needs an explicit BackHandler, matching every
        // other inline blocking overlay in this same host (PROFILE_NAME, STEAMGRIDDB_KEY, GAME_TITLE_EDIT,
        // ARTWORK_CONNECT, RETROACHIEVEMENTS_KEY all do this immediately before their own composable call).
        val source = mimirAppHostSource()
        val migrationBlock = source
            .substringAfter("bindings.migration.state?.takeIf { active == MimirOverlayKind.MIGRATION }?.let { migrationState ->")
            .substringBefore("MigrationImportOverlay(")

        assertTrue(
            "expected a BackHandler registered before MigrationImportOverlay is composed, " +
                "so the platform Back gesture/button still dismisses the inline modal",
            migrationBlock.contains("BackHandler(enabled = true)"),
        )
        assertTrue(
            "the BackHandler must defer to the same interactive guard as the reducer, not always close " +
                "(Reading/Applying must still swallow the press exactly as dispatchMigration does)",
            migrationBlock.contains("migrationInteractive(migrationState)"),
        )
        assertTrue(migrationBlock.contains("bindings.migration.close()"))
    }

    @Test
    fun migrationOverlayIsRegisteredForTheSharedPixelFadeEntrance() {
        // Inline overlay contract: MIGRATION must get the same token-driven entrance/exit its
        // siblings have. It was already flagged `true` here before this fix
        // (mimirDirectOverlayUsesPixelFade returns true for MIGRATION) — the flag was always correct, it
        // was fading an EMPTY layer because the Dialog's pixels never passed through
        // MimirDirectOverlayPixelFadeHost's recorded graphicsLayer. Now that the overlay renders inline in the same composition, the existing
        // MimirMotion.OverlayMs / MimirMotion.Easing tween in MimirDirectOverlayPixelFadeHost becomes real
        // for free — no new motion code is needed, only this call-site regression pin that the flag itself
        // is not silently flipped back to `false` as part of some future refactor.
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.MIGRATION))
    }

    @Test
    fun noMainSourceSetAnywhereInTheTreeImportsAComposeDialog() {
        // Tree-wide invariant, cited at OnboardingFlow.kt:95 ("all modals render INLINE, never a Compose
        // Dialog, which would miss the app's routed input") and confirmed by the adversarial review's sweep
        // (MigrationImport.kt was found to be the tree's ONLY Dialog import). Pinning it repo-wide, not just
        // for this one file, so a future overlay can't reintroduce the same class of defect unnoticed.
        val root = repoRoot()
        val moduleNames = listOf(
            "app", "core-audio", "core-companion-native", "core-input", "core-launch", "core-metadata",
            "core-model", "core-platform", "core-resolver", "core-storage", "core-tweaker-client", "core-ui",
            "feature-browse", "feature-companion", "feature-library", "feature-settings",
        )
        val offenders = mutableListOf<String>()
        for (module in moduleNames) {
            val mainKotlin = File(root, "$module/src/main/kotlin")
            if (!mainKotlin.isDirectory) continue
            mainKotlin.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    if (file.readText().contains("import androidx.compose.ui.window.Dialog")) {
                        offenders += file.relativeTo(root).path
                    }
                }
        }
        assertEquals(
            "expected zero Compose Dialog imports across every main source set; found: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: error("settings.gradle.kts not found walking up from ${File(".").absolutePath}")
        }
    }

    private fun migrationImportSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MigrationImport.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MigrationImport.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MigrationImport.kt not found from ${File(".").absolutePath}")
    }

    private fun mimirAppHostSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppHost.kt not found from ${File(".").absolutePath}")
    }
}
