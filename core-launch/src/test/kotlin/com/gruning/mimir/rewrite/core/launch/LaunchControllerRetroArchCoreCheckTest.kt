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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Where the RetroArch core check sits in [LaunchController.launch]: after the target is resolved and before
 * `prepare`, because `prepare` is what stops a running RetroArch. A provably missing core therefore refuses with
 * nothing dispatched, nothing stopped, no Tweaker hook run and no proof written; present and unknown launch as before.
 */
class LaunchControllerRetroArchCoreCheckTest {
    private val aarch64 = "com.retroarch.aarch64"
    private val platform = PlatformId.GBA
    private val game = GameId("game:gba:core-check")
    private val retroArchMgba = EmulatorProfile(
        id = EmulatorId("gba.retroarch.aarch64.mgba"),
        packageName = aarch64,
        displayName = "RetroArch 64 / mGBA",
        retroArchCore = "mgba",
        installed = true,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = LaunchLocatorForm.FILE_PATH,
        supportedPlatforms = setOf(platform),
    )
    private var savedLister: ((Int, String) -> RetroArchCoreListing)? = null

    @Before
    fun installNothing() {
        savedLister = RetroArchCoreCheck.lister
        RetroArchCoreCheck.invalidate(aarch64)
    }

    @After
    fun restore() {
        RetroArchCoreCheck.lister = savedLister
        RetroArchCoreCheck.invalidate(aarch64)
    }

    @Test
    fun `a provably missing core refuses before anything is prepared, stopped or run`() = runBlocking {
        val calls = mutableListOf<String>()
        RetroArchCoreCheck.lister = { _, _ -> RetroArchCoreListing.Known(setOf("gambatte_libretro_android.so")) }

        val result = controller(calls).launch(
            game,
            platform,
            preDispatchHook = { _, _ -> calls += "tweaker" },
            postDispatchHook = { _, _ -> calls += "post" },
            onDispatchFailed = { _, _, _ -> calls += "cleanup" },
        )

        assertTrue("nothing was prepared (the reap stops RetroArch), run or recorded: $calls", calls.isEmpty())
        assertTrue(result.readiness is LaunchReadiness.Ready)
        val failure = result.outcome as LaunchOutcome.Failed
        assertEquals(LaunchDispatchFailure.RETROARCH_CORE_MISSING, failure.reason)
        assertEquals("mGBA", failure.nativeMetadata[RetroArchCoreCheck.CORE_LABEL_KEY])
        assertEquals(retroArchMgba.id, failure.targetEmulatorId)
        assertFalse(result.launched)
        assertTrue(result.dispatchFailed)
    }

    @Test
    fun `a core that is there launches exactly as before`() = runBlocking {
        val calls = mutableListOf<String>()
        RetroArchCoreCheck.lister = { _, _ ->
            RetroArchCoreListing.Known(setOf("gambatte_libretro_android.so", "mgba_libretro_android.so"))
        }

        val result = controller(calls).launch(game, platform, preDispatchHook = { _, _ -> calls += "tweaker" })

        assertEquals(listOf("prepare", "tweaker", "dispatch", "proof"), calls)
        assertEquals(LaunchOutcome.IntentSent, result.outcome)
    }

    @Test
    fun `an unknown listing launches exactly as before`() = runBlocking {
        val calls = mutableListOf<String>()
        RetroArchCoreCheck.lister = { _, _ -> RetroArchCoreListing.Unknown }

        val result = controller(calls).launch(game, platform, preDispatchHook = { _, _ -> calls += "tweaker" })

        assertEquals(listOf("prepare", "tweaker", "dispatch", "proof"), calls)
        assertEquals(LaunchOutcome.IntentSent, result.outcome)
    }

    @Test
    fun `with no route installed a missing core cannot be told and the game launches`() = runBlocking {
        val calls = mutableListOf<String>()
        RetroArchCoreCheck.lister = null

        val result = controller(calls).launch(game, platform)

        assertEquals(listOf("prepare", "dispatch", "proof"), calls)
        assertTrue(result.launched)
    }

    private fun controller(calls: MutableList<String>) = LaunchController(
        emulatorProvider = InstalledEmulatorProvider { listOf(retroArchMgba) },
        locatorResolver = { _, _, _, preferredForm ->
            LaunchLocatorResult("/storage/emulated/0/ROMs/GBA/sample.gba", preferredForm)
        },
        overrideResolver = { null },
        overrideSetter = { _, _ -> Unit },
        overrideClearer = { Unit },
        biosResolver = { _, _, _ -> emptyList() },
        proofRecorder = { calls += "proof" },
        dispatcher = object : LaunchDispatcher {
            override fun dispatch(target: com.gruning.mimir.rewrite.core.model.LaunchTarget): LaunchOutcome {
                calls += "dispatch"
                return LaunchOutcome.IntentSent
            }

            override fun prepare(target: com.gruning.mimir.rewrite.core.model.LaunchTarget): PreparedLaunch {
                calls += "prepare"
                return PreparedLaunch.dispatchable { dispatch(target) }
            }
        },
        ioDispatcher = Dispatchers.Unconfined,
        confirmLaunchCue = {},
        gameLaunchArmed = {},
    )
}
