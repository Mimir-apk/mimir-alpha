package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [secondaryHomeResumeShouldReclaim]: `CompanionSecondaryHomeActivity.onResume()` may turn into
 * an auto-reclaim only while the runtime is stranded in `RecoveryNeeded`, or yielded to a host-covered app: the recents-pick lease
 * (D41, no return host beneath it) and, since the earlier update (Thor 2026-10-08), a Quick Launched app too. A HOME press on the Bottom starts the floor over
 * the app AND its return host, so the host never resumes and the floor resuming is the only way Mimir learns the display is its own again.
 * A DS/3DS lower screen (`existing_main_game_return`) is the emulator's own window above a floor that stays resumed, and is left alone.
 * The sequences themselves are in [BottomFloorNeverRestsBareTest].
 *
 * Device evidence, 2026-09-11: after `am force-stop` + restart with an app previously yielded on the
 * Bottom, the runtime logged `ProcessRecovery NoDisplay->RecoveryNeeded`, then
 * `RendererBound`/`DisplayAvailableObserved` landed while still `RecoveryNeeded` — display 4 showed
 * the secondary-home floor as a bare black window until the user did the right-edge swipe
 * (`explicitReclaim` -> `PresentationShown`). Every other disposition already has a settled or
 * in-flight owner for the display and must be left alone (see
 * [CompanionReturnCoordinator.noteSecondaryHomeResumed]'s KDoc for the full rationale).
 */
class SecondaryHomeResumeReclaimTest {

    @Test
    fun `recovery needed reclaims`() {
        val recovery = SecondaryDisplayDisposition.RecoveryNeeded(
            operationId = OperationId.create(),
            displayId = 4,
            fallback = PrimaryReturnOrigin("LIBRARY"),
            failure = FailureReason(FailureCode.PROCESS_RECOVERED_NON_MIRROR, "saved host"),
        )

        assertTrue(secondaryHomeResumeShouldReclaim(recovery))
        assertNull("recovery asserts nothing about an app leaving", secondaryHomeResumeReturningPackage(recovery))
    }

    @Test
    fun `a recents-pick lease reclaims when the floor resumes, naming the package that left`() {
        val lease = HostLease(
            operationId = OperationId.create(),
            packageName = "com.android.chrome",
            displayId = 4,
            launchMode = LaunchModeRecentsPick,
            primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
        )
        val yielded = SecondaryDisplayDisposition.Yielded(lease = lease, evidence = YieldEvidence.HOST_COVERED)

        assertTrue(secondaryHomeResumeShouldReclaim(yielded))
        assertEquals("com.android.chrome", secondaryHomeResumeReturningPackage(yielded))
    }

    @Test
    fun `a Quick Launched app reclaims when the floor resumes over it, naming the package that left`() {
        val lease = HostLease(
            operationId = OperationId.create(),
            packageName = "org.example.game",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
        )
        // its return host is beneath the floor after a HOME press on the Bottom, so the host never resumes to say it left.
        val quickLaunch = SecondaryDisplayDisposition.Yielded(lease = lease, evidence = YieldEvidence.HOST_COVERED)

        assertTrue(secondaryHomeResumeShouldReclaim(quickLaunch))
        assertEquals("org.example.game", secondaryHomeResumeReturningPackage(quickLaunch))
    }

    @Test
    fun `mirrored, empty and lower-screen dispositions are left alone`() {
        val owner = RendererOwnerId.create()
        val render = RenderAttempt(OperationId.create(), OperationId.create(), owner, displayId = 4)
        val lowerScreen = HostLease(
            operationId = OperationId.create(),
            packageName = "me.magnum.melonds",
            displayId = 4,
            launchMode = "existing_main_game_return",
            primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
        )

        assertFalse(secondaryHomeResumeShouldReclaim(SecondaryDisplayDisposition.NoDisplay))
        assertFalse(secondaryHomeResumeShouldReclaim(SecondaryDisplayDisposition.MirrorPresented(render)))
        // A DS/3DS lower screen is the emulator's own window above a floor that stays resumed: the floor resuming proves nothing.
        val handoff = SecondaryDisplayDisposition.Yielded(lease = lowerScreen, evidence = YieldEvidence.USER_REQUESTED_GAME_RETURN)
        assertFalse(secondaryHomeResumeShouldReclaim(handoff))
        assertNull(secondaryHomeResumeReturningPackage(handoff))
        assertNull(secondaryHomeResumeReturningPackage(SecondaryDisplayDisposition.Yielded(lease = null, evidence = YieldEvidence.HOST_COVERED)))
    }
}
