package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 *
 *
 * `HomeLaunchCardOverlayHost` (MimirAppHost.kt, private to that file, so it cannot be hosted directly
 * from a separate test file) used to run its `titleEditBlurRadius` as a bare, un-keyed
 * `animateDpAsState` — unlike the surrounding per-session dissolve state
 * (`incomingAlpha`/`outgoingAlpha`/`frame`, all `remember(target, frame.epoch)`-scoped). A bare/unscoped
 * `remember` (which is what `animateDpAsState` uses internally to hold its `Animatable`) survives an
 * UNRELATED recomposition and keeps whatever it was mid-animating — so a blur transition left in flight
 * by a PRIOR session (Edit -> Cancel, card closed before the un-blur settled) kept draining on its own
 * clock while a NEW session's content dissolve played over it: two motions, decoupled, on the same
 * visual, which reads exactly as blur changing while the underlying content sharpens
 *
 * This test does not render the private `HomeLaunchCardOverlayHost` composable, and it does not attempt
 * to sample an animation mid-flight (Robolectric's `ComposeTestRule` here did not reliably drive recomposition
 * under a manually-paused animation clock — a harness limitation, not a claim about production behaviour, so
 * this test deliberately avoids depending on `mainClock` stepping). Instead it proves, by actually running
 * Compose (Robolectric, no device), the STRUCTURAL guarantee the fix relies on: `key(scope) { remember { ... } }`
 * hands out a FRESH remembered instance the moment `scope` changes, while a bare `remember { ... }` — exactly
 * what a bare `animateDpAsState` holds inside — keeps the SAME instance (and therefore the SAME in-flight
 * animation state) across an unrelated recomposition. `MimirAppHostMotionTest.
 * titleEditBlurRadiusIsScopedToTheSameSessionTupleAsTheRestOfTheDissolveState` separately pins that the
 * PRODUCTION code actually wraps `titleEditBlurRadius`'s `animateDpAsState` in `key(target, frame.epoch)`.
 *
 * Both tests here are reasoned/executed proofs of the STATE-IDENTITY mechanism, not a claim that the
 * transition now *looks* smooth — perceptual smoothness is a device visual-review question, not something a
 * JVM test can certify.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeLaunchCardBlurScopeMechanismTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a bare remember keeps the same instance across an unrelated recomposition`() {
        var editing by mutableStateOf(false)
        var lastInstanceId = -1
        var instancesCreated = 0

        compose.setContent {
            editing // read so this composable actually recomposes when `editing` changes
            val instanceId = remember { ++instancesCreated }
            lastInstanceId = instanceId
        }
        compose.waitForIdle()
        assertEquals(1, lastInstanceId)

        // Mirrors a NEW launch-card session mounting while the OLD titleEditBlurRadius Animatable (a
        // bare `remember` under the hood) is still mid-drain from a prior session's Edit -> Cancel: the
        // unrelated recomposition (session/target changed) does not give the animation a fresh instance.
        editing = true
        compose.waitForIdle()
        assertEquals(
            "a bare remember must NOT hand out a new instance on an unrelated recomposition -- this is " +
                "exactly why the un-keyed titleEditBlurRadius could carry a stale in-flight blur value " +
                "into a brand new launch-card session",
            1,
            lastInstanceId,
        )
    }

    @Test
    fun `keying by session scope hands out a fresh remembered instance per scope`() {
        var sessionScope by mutableStateOf(0)
        var lastInstanceId = -1
        var instancesCreated = 0

        compose.setContent {
            val instanceId = key(sessionScope) {
                remember { ++instancesCreated }
            }
            lastInstanceId = instanceId
        }
        compose.waitForIdle()
        assertEquals(1, lastInstanceId)

        // Mirrors HomeLaunchCardOverlayHost's fix: `key(target, frame.epoch) { animateDpAsState(...) }`.
        // A session-scope change must hand the blur radius a FRESH Animatable, initialised at the coarse
        // boolean's CURRENT value, instead of inheriting whatever the old session's Animatable was
        // mid-animating.
        sessionScope = 1
        compose.waitForIdle()
        assertEquals(
            "key(sessionScope) { remember {} } must hand out a NEW instance when sessionScope changes " +
                "-- this is the guarantee titleEditBlurRadius's key(target, frame.epoch) wrapping relies on",
            2,
            lastInstanceId,
        )
    }
}
