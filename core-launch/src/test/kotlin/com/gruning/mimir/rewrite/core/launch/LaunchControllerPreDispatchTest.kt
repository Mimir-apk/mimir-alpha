package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-dispatch hook is a call into ANOTHER app (Mimir Tweaker) over Binder. These pin the three ways
 * that goes: it works, it throws, or it never comes back — and only the first may change launch ordering.
 */
class LaunchControllerPreDispatchTest {
    private val platform = PlatformId.PS1
    private val game = GameId("game:ps1:predispatch")
    private val emulator = EmulatorProfile(
        id = EmulatorId("com.github.stenzek.duckstation"),
        packageName = "com.github.stenzek.duckstation",
        displayName = "DuckStation",
        installed = true,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = LaunchLocatorForm.FILE_PATH,
        supportedPlatforms = setOf(platform),
    )

    @Test
    fun `pre-dispatch hook runs after readiness and before dispatch`() = runBlocking {
        val calls = mutableListOf<String>()
        val degraded = mutableListOf<PreDispatchOutcome>()

        val result = controller(calls).launch(
            game,
            platform,
            preDispatchHook = { ready, _ -> calls += "tweaker:${ready.target.emulator.packageName}" },
            onPreDispatchDegraded = { _, outcome -> degraded += outcome },
        )

        assertEquals(listOf("tweaker:com.github.stenzek.duckstation", "dispatch"), calls)
        assertEquals(LaunchOutcome.IntentSent, result.outcome)
        assertTrue(result.launched)
        assertTrue(degraded.isEmpty())
    }

    @Test
    fun `real preparation seat precedes tweaker and carries preflight across hook`() = runBlocking {
        val calls = mutableListOf<String>()
        val preflight = LaunchTargetPreflightContext(
            packageName = emulator.packageName,
            result = LaunchTargetReapResult.REQUEST_ACCEPTED,
        )
        val dispatcher = object : LaunchDispatcher {
            override fun dispatch(target: com.gruning.mimir.rewrite.core.model.LaunchTarget): LaunchOutcome =
                error("prepared path must own dispatch")

            override fun prepare(
                target: com.gruning.mimir.rewrite.core.model.LaunchTarget,
            ): PreparedLaunch {
                calls += "prepare"
                return PreparedLaunch.dispatchable(preflight) {
                    calls += "dispatch"
                    LaunchOutcome.IntentSent
                }
            }
        }

        val result = controller(calls, dispatcher = dispatcher).launch(
            game,
            platform,
            preDispatchHook = { _, _ -> calls += "tweaker" },
        )

        assertEquals(listOf("prepare", "tweaker", "dispatch"), calls)
        assertEquals(preflight, result.preflight)
    }

    @Test
    fun `post-dispatch handoff consumes tuning only after IntentSent`() = runBlocking {
        val calls = mutableListOf<String>()
        val attempts = mutableListOf<LaunchAttemptId>()

        val result = controller(calls).launch(
            game,
            platform,
            preDispatchHook = { _, attemptId ->
                calls += "admit"
                attempts += attemptId
            },
            postDispatchHook = { _, attemptId ->
                calls += "start-tweaker"
                attempts += attemptId
            },
        )

        assertEquals(listOf("admit", "dispatch", "start-tweaker"), calls)
        assertEquals(2, attempts.size)
        assertEquals(attempts.first(), attempts.last())
        assertTrue(attempts.first().value.isNotBlank())
        assertTrue(result.launched)
    }

    @Test
    fun `synchronous dispatch failure invokes contained cleanup exactly once`() = runBlocking {
        val calls = mutableListOf<String>()
        val attempts = mutableListOf<LaunchAttemptId>()
        val failure = LaunchOutcome.Failed(
            reason = LaunchDispatchFailure.ACTIVITY_NOT_STARTED,
            targetEmulatorId = emulator.id,
        )
        val result = controller(
            calls = calls,
            dispatcher = LaunchDispatcher {
                calls += "dispatch"
                failure
            },
        ).launch(
            game,
            platform,
            preDispatchHook = { _, attemptId ->
                calls += "tweaker"
                attempts += attemptId
            },
            postDispatchHook = { _, _ -> calls += "must-not-start" },
            onDispatchFailed = { _, outcome, attemptId ->
                calls += "cleanup:${outcome.reason.name}"
                attempts += attemptId
            },
        )

        assertEquals(
            listOf("tweaker", "dispatch", "cleanup:ACTIVITY_NOT_STARTED"),
            calls,
        )
        assertEquals(2, attempts.size)
        assertEquals(attempts.first(), attempts.last())
        assertSame(failure, result.outcome)
    }

    @Test
    fun `non-dispatchable preparation skips tweaker but still unwinds failure`() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = LaunchOutcome.Failed(
            reason = LaunchDispatchFailure.TARGET_MISSING,
            targetEmulatorId = emulator.id,
        )
        val dispatcher = object : LaunchDispatcher {
            override fun dispatch(target: com.gruning.mimir.rewrite.core.model.LaunchTarget): LaunchOutcome =
                error("non-dispatchable preparation must own failure")

            override fun prepare(
                target: com.gruning.mimir.rewrite.core.model.LaunchTarget,
            ): PreparedLaunch = PreparedLaunch.failed(failure)
        }

        val result = controller(calls, dispatcher = dispatcher).launch(
            game,
            platform,
            preDispatchHook = { _, _ -> calls += "tweaker" },
            onDispatchFailed = { _, _, _ -> calls += "cleanup" },
        )

        assertEquals(listOf("cleanup"), calls)
        assertSame(failure, result.outcome)
    }

    @Test
    fun `prepared launch is one shot even if dispatch is called twice`() {
        var starts = 0
        val prepared = PreparedLaunch.dispatchable {
            starts++
            LaunchOutcome.IntentSent
        }

        assertEquals(LaunchOutcome.IntentSent, prepared.dispatch())
        assertEquals(LaunchOutcome.IntentSent, prepared.dispatch())
        assertEquals(1, starts)
    }

    @Test
    fun `a throwing hook is contained and the game still launches`() = runBlocking {
        val calls = mutableListOf<String>()
        val degraded = mutableListOf<PreDispatchOutcome>()
        val boom = IllegalStateException("tweaker binder died")

        val result = controller(calls).launch(
            game,
            platform,
            preDispatchHook = { _, _ -> throw boom },
            onPreDispatchDegraded = { _, outcome -> degraded += outcome },
        )

        assertEquals(listOf("dispatch"), calls)
        assertEquals(LaunchOutcome.IntentSent, result.outcome)
        assertTrue(result.launched)
        assertEquals(1, degraded.size)
        assertSame(boom, (degraded.single() as PreDispatchOutcome.Failed).cause)
    }

    @Test
    fun `a hook that never returns is abandoned and the game still launches`() {
        val calls = mutableListOf<String>()
        val degraded = mutableListOf<PreDispatchOutcome>()
        var postDispatchCalls = 0
        val entered = CountDownLatch(1)
        // Stands in for a synchronous Binder transaction that never returns: it blocks its thread and
        // ignores cancellation, exactly like the real wedge this guard exists for.
        val release = CountDownLatch(1)

        val result = try {
            runBlocking {
                controller(
                    calls = calls,
                    ioDispatcher = Dispatchers.IO,
                    confirmLaunchCue = { calls += "cue" },
                    gameLaunchArmed = { calls += "armed" },
                ).launch(
                    game,
                    platform,
                    preDispatchHook = { _, _ ->
                        entered.countDown()
                        release.await()
                    },
                    preDispatchTimeoutMillis = 150L,
                    onPreDispatchDegraded = { _, outcome ->
                        calls += "degraded"
                        degraded += outcome
                    },
                    postDispatchHook = { _, _ -> postDispatchCalls += 1 },
                )
            }
        } finally {
            release.countDown()
        }

        assertTrue("the hook must actually have started", entered.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("dispatch", "cue", "armed", "degraded"), calls)
        assertEquals(LaunchOutcome.IntentSent, result.outcome)
        assertTrue(result.launched)
        assertEquals(PreDispatchOutcome.TimedOut(150L), degraded.single())
        assertEquals(0, postDispatchCalls)
    }

    @Test
    fun `degraded DS preparation yields companion before warning presentation`() {
        val dsPlatform = PlatformId.DS
        val dsEmulator = emulator.copy(
            id = EmulatorId("me.magnum.melonds"),
            packageName = "me.magnum.melonds",
            displayName = "melonDS",
            locatorForm = LaunchLocatorForm.CONTENT_URI,
            supportedPlatforms = setOf(dsPlatform),
        )
        val calls = mutableListOf<String>()
        var postDispatchCalls = 0
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val result = try {
            runBlocking {
                controller(
                    calls = calls,
                    ioDispatcher = Dispatchers.IO,
                    testEmulator = dsEmulator,
                    dualScreenLaunchNotifier = { calls += "dual-yield" },
                    confirmLaunchCue = { calls += "cue" },
                    gameLaunchArmed = { calls += "armed" },
                ).launch(
                    game,
                    dsPlatform,
                    preDispatchHook = { _, _ ->
                        entered.countDown()
                        release.await()
                    },
                    preDispatchTimeoutMillis = 150L,
                    onPreDispatchDegraded = { _, _ -> calls += "degraded" },
                    postDispatchHook = { _, _ -> postDispatchCalls += 1 },
                )
            }
        } finally {
            release.countDown()
        }

        assertTrue("the hook must actually have started", entered.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("dispatch", "cue", "armed", "dual-yield", "degraded"), calls)
        assertEquals(LaunchOutcome.IntentSent, result.outcome)
        assertEquals(0, postDispatchCalls)
    }

    @Test
    fun `a not-ready launch never runs the hook`() = runBlocking {
        val calls = mutableListOf<String>()
        val notInstalled = emulator.copy(installed = false)
        val controller = LaunchController(
            emulatorProvider = InstalledEmulatorProvider { listOf(notInstalled) },
            locatorResolver = { _, _, _, _ -> null },
            overrideResolver = { null },
            overrideSetter = { _, _ -> Unit },
            overrideClearer = { Unit },
            biosResolver = { _, _, _ -> emptyList() },
            proofRecorder = { Unit },
            dispatcher = LaunchDispatcher {
                calls += "dispatch"
                LaunchOutcome.IntentSent
            },
                ioDispatcher = Dispatchers.Unconfined,
            confirmLaunchCue = {},
            gameLaunchArmed = {},
        )

        val result = controller.launch(
            game,
            platform,
            preDispatchHook = { _, _ -> calls += "tweaker" },
        )

        assertTrue(calls.isEmpty())
        assertNull(result.outcome)
        assertTrue(result.readiness !is LaunchReadiness.Ready)
    }

    private fun controller(
        calls: MutableList<String>,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        dispatcher: LaunchDispatcher = LaunchDispatcher {
            calls += "dispatch"
            LaunchOutcome.IntentSent
        },
        testEmulator: EmulatorProfile = emulator,
        dualScreenLaunchNotifier: ((String) -> Unit)? = null,
        confirmLaunchCue: () -> Unit = {},
        gameLaunchArmed: () -> Unit = {},
    ) = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { listOf(testEmulator) },
        locatorResolver = { _, _, _, preferredForm ->
            LaunchLocatorResult(
                locator = when (preferredForm) {
                    LaunchLocatorForm.CONTENT_URI -> "content://tree/roms/document/sample.nds"
                    else -> "/storage/emulated/0/ROMs/PS1/sample.chd"
                },
                form = preferredForm,
            )
        },
        overrideResolver = { null },
        overrideSetter = { _, _ -> Unit },
        overrideClearer = { Unit },
        biosResolver = { _, _, _ -> emptyList() },
        proofRecorder = { Unit },
        dispatcher = dispatcher,
        dualScreenLaunchNotifier = dualScreenLaunchNotifier,
        ioDispatcher = ioDispatcher,
        confirmLaunchCue = confirmLaunchCue,
        gameLaunchArmed = gameLaunchArmed,
    )
}
