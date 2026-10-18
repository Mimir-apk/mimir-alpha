package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Which root an interaction came from is declared by its router, and a restart is not an interaction.**
 *
 * This began as the pin for the gate's published `lastInteractionRoot`, which the second-screen convoy fix read: a discrete
 * controller press lands in RECENT, the same phase as resting, so the phase alone could not say "the player is driving the
 * primary". Nothing reads that flow any more, so it is gone; what stays is the part that is still live: every router names
 * its own root (the gate's log line names it), and windows appearing at a restart only wake the gate.
 */
class InteractionRootPublishingTest {

    // The same shape `InteractionMotionGateTest` uses. Not `runTest`: the gate launches a 12 s RECENT
    // deadline job on this scope, and runTest waits for its children -- the harness would hang on a
    // coroutine the product is meant to leave running.
    private val scopes = mutableListOf<CoroutineScope>()

    private fun gate() = DefaultInteractionMotionGate(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add),
        clock = { 0L },
    )

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    /** The roots a process start registers, with no input: windows appearing are wakes, so the gate rests in RECENT. */
    @Test
    fun `a restart with no input only wakes the gate`() {
        val gate = gate()
        gate.registerRoot(InteractionRoot.NATIVE_WINDOW, initiallyVisible = true)
        val primary = gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = false)
        primary.setVisible(true)
        gate.registerRoot(InteractionRoot.COMPANION, initiallyVisible = true)

        assertEquals(AmbientMotionPhase.RECENT, gate.phase.value)
    }

    /**
     * `InputRouter.interactionRoot` is deliberately NOT defaulted: `PrimaryMButtonPanelHost` builds a
     * second router for a `NATIVE_WINDOW` panel, and a default of PRIMARY would name the main screen as
     * the source of a pulse the panel made -- silently, and only visible in the gate's log line.
     */
    @Test
    fun `every router declares its own root and none of them defaults`() {
        val router = File("src/main/kotlin/com/gruning/mimir/rewrite/InputRouter.kt").readText()
        assertTrue(
            "InputRouter.interactionRoot must stay undefaulted so a new router cannot silently claim " +
                "PRIMARY",
            Regex("""private val interactionRoot: InteractionRoot,""").containsMatchIn(router),
        )
        listOf(
            "MainActivity.kt" to "InteractionRoot.PRIMARY",
            "PrimaryMButtonPanelHost.kt" to "InteractionRoot.NATIVE_WINDOW",
        ).forEach { (file, expected) ->
            val text = File("src/main/kotlin/com/gruning/mimir/rewrite/$file").readText()
            assertTrue(
                "$file constructs an InputRouter and must declare interactionRoot = $expected",
                text.contains("interactionRoot = $expected"),
            )
        }
    }
}
