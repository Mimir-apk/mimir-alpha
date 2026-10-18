package com.gruning.mimir.rewrite

import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keyboard safety: a Compose keyboard whose COMPOSITION throws. `onCreateInputView`'s catch cannot see
 * that -- composition runs after the view is attached -- so what protects the player is the persistent armed
 * marker: it is written before the view is shown, never cleared because the keyboard never drew and stayed up,
 * and the next service start (a new process) finds it and uses the plain fallback keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirInputMethodServiceComposeCrashTest {

    @get:Rule
    val compose = createComposeRule()

    class CrashingService : MimirInputMethodService() {
        internal var trail: ImeSafetyStore = MemoryImeSafetyStore()
        internal var token = "process-1"
        internal var reverts = 0

        override val safetyStore: ImeSafetyStore get() = trail
        override val safetyProcessToken: String get() = token
        override fun safetyVersionStamp(): Long = 1L
        override fun revertDefaultForSafety() {
            reverts += 1
        }

        /** A real ComposeView whose content throws while it is being composed. */
        override fun buildComposeKeyboardView(): View = ComposeView(this).apply {
            setContent { throw IllegalStateException("composition blew up") }
        }
    }

    @Test
    fun aKeyboardWhoseCompositionThrowsLeavesTheMarkerAndTheNextStartUsesTheFallback() {
        val first = Robolectric.setupService(CrashingService::class.java)
        val view = first.onCreateInputView()
        // Construction did not throw, and the trail is armed before anything composes.
        val trail = first.trail as MemoryImeSafetyStore
        assertEquals(ImeMode.COMPOSE, trail.state.armedMode)

        // Attach it: composition starts here, outside onCreateInputView, and throws. Whether the exception
        // surfaces to this call or to the looper, the keyboard never drew, so nothing may clear the marker.
        val thrown = runCatching {
            compose.setContent { AndroidView(factory = { view }) }
            compose.waitForIdle()
        }.exceptionOrNull()
        println("composition outcome: ${thrown?.javaClass?.simpleName ?: "no exception surfaced to the caller"}")
        assertEquals("never cleared: it did not draw and stay up", ImeMode.COMPOSE, trail.state.armedMode)
        assertEquals("process-1", trail.state.armedProcess)

        // The process died; a new one starts the service.
        val second = Robolectric.setupService(CrashingService::class.java).apply {
            this.trail = first.trail
            this.token = "process-2"
        }
        assertTrue("the plain fallback, not Compose again", second.onCreateInputView() is MimirImeFallbackKeyboard)
        assertTrue(trail.state.fallbackForced)
        assertEquals(1, trail.state.composeCrashes)
        assertEquals(0, second.reverts)

        // A third start stays on the fallback.
        val third = Robolectric.setupService(CrashingService::class.java).apply {
            this.trail = first.trail
            this.token = "process-3"
        }
        assertTrue(third.onCreateInputView() is MimirImeFallbackKeyboard)
    }
}
