package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home door's card is composed from a body that runs for every change of every store behind it (owperf, 2026-10-15). What the card is handed must therefore be
 * the same instance across a pass that changed nothing it draws, and its close lambda the same lambda across the flip of the back flag that OPEN WITH opening causes;
 * `HomeOpenWithRecompositionCountTest` (feature-library) counts what that saves, on the same wiring in miniature. This pins that the host still wires it that way.
 */
class HomeLaunchCardHostSkipTest {

    private val host: String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("MimirAppHost.kt not found from ${File(".").absolutePath}")

    private val liveContent: String = host.substringAfter("private fun HomeLaunchCardLiveContent(").substringBefore("private fun HomeLaunchChoiceOverlay(")

    @Test
    fun `the Home card is handed an instance the host keeps while the state it builds is equal`() {
        assertTrue(liveContent.contains("val cardState = rememberEqualInstance(\n        HomeLaunchCardUiState("))
        assertTrue(liveContent.contains("HomeLaunchCard(\n        state = cardState,"))
        assertFalse("the state is not built inline in the call again", liveContent.contains("state = HomeLaunchCardUiState("))
    }

    @Test
    fun `the Home card's close lambda reads the back flag through the updated state, so it is one lambda across the flip`() {
        assertTrue(liveContent.contains("val backEnabledNow by rememberUpdatedState(backEnabled)"))
        assertTrue(liveContent.contains("onClose = { if (backEnabledNow) bindings.overlays.dispatch(ControllerAction.CANCEL) },"))
        assertFalse(liveContent.contains("onClose = { if (backEnabled)"))
    }
}
