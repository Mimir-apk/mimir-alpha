package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Piece 3: source characterization for the Compose-level wiring a JVM unit test cannot otherwise reach
 * (no Compose test rule available in this module — matches `GameTitleEditOverlayLayeringTest`'s pattern).
 * The FOCUS GRAPH itself is proven in [GameTitleEditResetFocusGraphTest] and the CONFIRM shortcut in
 * [GameTitleEditResetDispatchTest]; this pins that the wiring connecting them to the real Composable is
 * actually present and uses the required primitives — never Compose's own `focusable()`/`focusTarget()`.
 */
class GameTitleEditOverlayResetWiringTest {

    @Test
    fun resetButtonRegistersThroughMimirFocusableOnlyInTheKeyboardScope() {
        val source = gameTitleEditOverlaySource()

        assertTrue(source.contains("Modifier.mimirFocusable(authority, GameTitleEditResetKey, MimirKeyboardFocusScope)"))
        assertFalse(source.contains(".focusable()"))
        assertFalse(source.contains(".focusTarget()"))
    }

    @Test
    fun overlayAcceptsAnOptionalFocusAuthorityMatchingControllerAndKeyDecoratorsOwnOptionality() {
        val source = gameTitleEditOverlaySource()

        assertTrue(source.contains("authority: FocusAuthority? = null"))
    }

    private fun gameTitleEditOverlaySource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/GameTitleEditOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/GameTitleEditOverlay.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("GameTitleEditOverlay.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun appHostThreadsTheRealFocusAuthorityIntoTheOverlayAndSharesTheResetSideEffect() {
        val source = mimirAppHostSource()

        assertTrue(source.contains("authority = core.focusAuthority,"))
        assertTrue(source.contains("onReset = { gameId -> resetGameTitleOverride(bindings, gameId) },"))
    }

    @Test
    fun dispatchShortcutChecksTheResetKeyBeforeFallingIntoTheGenericKeyboardBinding() {
        val source = mimirAppInputDispatchSource()

        val gameTitleEditBranch = source.substringAfter("MimirOverlayKind.GAME_TITLE_EDIT -> {")
            .substringBefore("MimirOverlayKind.HOME_TOME_LIBRARY -> {")
        assertTrue(
            gameTitleEditBranch.contains(
                "if (action == ControllerAction.CONFIRM && authority.state.value.focused == GameTitleEditResetKey)",
            ),
        )
        val confirmCheckIndex = gameTitleEditBranch.indexOf("ControllerAction.CONFIRM &&")
        val dispatchCallIndex = gameTitleEditBranch.indexOf("dispatchMimirKeyboardInput(")
        assertTrue(
            "the reset shortcut must be checked BEFORE the generic dispatch, not after",
            confirmCheckIndex in 0 until dispatchCallIndex,
        )
    }

    /**
     * Piece 4: idempotent reopen (proven at the binding level in [GameTitleEditFocusIdempotencyTest]) only
     * holds if EVERY exit path actually tears the overlay down through the ONE mechanism that resets the
     * remembered `MimirKeyboardFocusBinding` — `closeGameTitleEdit()`, which nulls `gameTitleEdit` and
     * unmounts the `.let { request -> ... }` block `rememberMimirKeyboardHost` lives inside. Save, Reset,
     * and Cancel are three separate closures; nothing stops a future edit from giving one of them a
     * shortcut that skips it. Pins all three reach it, in the same overlay-open block.
     */
    @Test
    fun saveResetAndCancelAllTearDownThroughTheSameCloseCall() {
        val source = mimirAppHostSource()
        val overlayBlock = source
            .substringAfter("bindings.navigation.gameTitleEdit?.takeIf { active == MimirOverlayKind.GAME_TITLE_EDIT }")
            .substringBefore("if (active == MimirOverlayKind.STEAMGRIDDB_KEY)")

        val onSave = overlayBlock.substringAfter("onSave = { gameId, title ->").substringBefore("onReset =")
        assertTrue("onSave must close the overlay", onSave.contains("bindings.navigation.closeGameTitleEdit()"))

        val onReset = overlayBlock.substringAfter("onReset = { gameId ->").substringBefore("onCancel =")
        assertTrue(
            "onReset must route through the SAME shared side effect as the controller shortcut " +
                "(GameTitleEditResetDispatchTest proves that helper itself calls closeGameTitleEdit())",
            onReset.contains("resetGameTitleOverride(bindings, gameId)"),
        )

        val onCancel = overlayBlock.substringAfter("onCancel = {").substringBefore("controller =")
        assertTrue("onCancel must close the overlay", onCancel.contains("bindings.navigation.closeGameTitleEdit()"))
    }

    /** The shared helper itself, so the claim above about `resetGameTitleOverride` isn't taken on faith. */
    @Test
    fun theSharedResetHelperItselfClosesTheOverlay() {
        val source = mimirAppInputDispatchSource()
        val helper = source.substringAfter("internal fun resetGameTitleOverride(")
            .substringBefore("/** Routes controller input")

        assertTrue(helper.contains("bindings.navigation.closeGameTitleEdit()"))
    }

    private fun mimirAppHostSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppHost.kt not found from ${File(".").absolutePath}")
    }

    private fun mimirAppInputDispatchSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppInputDispatch.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppInputDispatch.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppInputDispatch.kt not found from ${File(".").absolutePath}")
    }
}
