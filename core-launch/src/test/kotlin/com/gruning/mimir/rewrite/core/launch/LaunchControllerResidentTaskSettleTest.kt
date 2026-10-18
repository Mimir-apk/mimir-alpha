package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reported warm-relaunch failures in aPS3e and Xbox 360 shells exposed a
 * launchMode="singleTask" target with no `onNewIntent` override. Such a target
 * silently drops a warm relaunch's payload, because Android routes the second launch to that missing
 * callback instead of onCreate. Mimir cannot patch the target app, so [LaunchController.launch] closes
 * the race between its own (asynchronous) pre-dispatch reap and the immediate redispatch with a short,
 * bounded wait — but ONLY for a profile that opts in via [EmulatorProfile.residentTaskWithoutIntentRefresh],
 * and ONLY when the reap actually attempted something. Pinned here in isolation from the broader
 * pre-dispatch-hook ordering covered by [LaunchControllerPreDispatchTest].
 */
class LaunchControllerResidentTaskSettleTest {
    private val platform = PlatformId("ps3")
    private val game = GameId("game:ps3:reap-settle")

    private fun emulator(needsSettle: Boolean) = EmulatorProfile(
        id = EmulatorId("aenu.aps3e"),
        packageName = "aenu.aps3e",
        displayName = "aPS3e",
        installed = true,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = LaunchLocatorForm.EITHER,
        supportedPlatforms = setOf(platform),
        residentTaskWithoutIntentRefresh = needsSettle,
    )

    private fun dispatcherWithReap(result: LaunchTargetReapResult) = object : LaunchDispatcher {
        override fun dispatch(target: LaunchTarget): LaunchOutcome =
            error("prepared path must own dispatch")

        override fun prepare(target: LaunchTarget): PreparedLaunch {
            val preflight = LaunchTargetPreflightContext(target.emulator.packageName, result)
            return PreparedLaunch.dispatchable(preflight) { LaunchOutcome.IntentSent }
        }
    }

    @Test
    fun `flagged profile with an attempted reap waits before redispatch`() = runBlocking {
        val controller = controller(
            emulator(needsSettle = true),
            dispatcherWithReap(LaunchTargetReapResult.REQUEST_ACCEPTED),
        )
        val start = System.currentTimeMillis()
        val result = controller.launch(game, platform)
        val elapsedMs = System.currentTimeMillis() - start

        assertEquals(LaunchOutcome.IntentSent, result.outcome)
        // Must track LaunchController's own (private) RESIDENT_TASK_REAP_SETTLE_MILLIS = 250L; a small
        // safety margin below it so clock jitter can't flake this pin.
        assertTrue("expected a >=250ms settle wait before redispatch, took ${elapsedMs}ms", elapsedMs >= 240L)
    }

    @Test
    fun `flagged profile skips the wait when the reap authority refused`() = runBlocking {
        val controller = controller(
            emulator(needsSettle = true),
            dispatcherWithReap(LaunchTargetReapResult.NOT_ALLOWED),
        )
        val start = System.currentTimeMillis()
        controller.launch(game, platform)
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue(
            "NOT_ALLOWED means the reap authority killed nothing, so there is nothing to wait for; took ${elapsedMs}ms",
            elapsedMs < 200L,
        )
    }

    @Test
    fun `unflagged profile never waits even with an attempted reap`() = runBlocking {
        val controller = controller(
            emulator(needsSettle = false),
            dispatcherWithReap(LaunchTargetReapResult.REQUEST_ACCEPTED),
        )
        val start = System.currentTimeMillis()
        controller.launch(game, platform)
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue(
            "only a profile that opts into residentTaskWithoutIntentRefresh should ever wait; took ${elapsedMs}ms",
            elapsedMs < 200L,
        )
    }

    private fun controller(
        testEmulator: EmulatorProfile,
        dispatcher: LaunchDispatcher,
    ) = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { listOf(testEmulator) },
        locatorResolver = { _, _, _, preferredForm ->
            LaunchLocatorResult(locator = "content://tree/roms/document/sample.iso", form = preferredForm)
        },
        overrideResolver = { null },
        overrideSetter = { _, _ -> Unit },
        overrideClearer = { Unit },
        biosResolver = { _, _, _ -> emptyList() },
        proofRecorder = { Unit },
        dispatcher = dispatcher,
        ioDispatcher = Dispatchers.Unconfined,
        confirmLaunchCue = {},
        gameLaunchArmed = {},
    )
}
