package com.gruning.mimir.rewrite.core.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentLaunchDispatcherAndroidTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mupenAlphaLeavesDataAbsentSoSplashCannotStripTheUri() {
        val intent = mupenUriIntent("org.mupen64plusae.v3.alpha")
        assertEquals("paulscode.android.mupen64plusae.SplashActivity", intent.component?.className)
        assertNull(intent.data)
    }

    @Test
    fun mupenAlphaCarriesCompleteUriExtraAndRealReadGrantEnvelope() {
        val intent = mupenUriIntent("org.mupen64plusae.v3.alpha")
        val uri = "content://mimir.intent.contract/document/Conker%27s%20Bad%20Fur%20Day.z64"
        assertEquals(uri, intent.getStringExtra("paulscode.android.mupen64plusae.ActivityHelper.Keys.ROM_PATH"))
        assertEquals(1, intent.clipData?.itemCount)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri?.toString())
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun otherMupenVariantsRetainTheirExistingDataContract() {
        for (pkg in listOf("org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro")) {
            val intent = mupenUriIntent(pkg)
            assertEquals("content://mimir.intent.contract/document/Conker%27s%20Bad%20Fur%20Day.z64", intent.dataString)
            assertEquals("application/octet-stream", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(intent.data, intent.clipData?.getItemAt(0)?.uri)
        }
    }

    private fun mupenUriIntent(packageName: String): Intent {
        var launched: Intent? = null
        val dispatcher = IntentLaunchDispatcher(context) { launched = it }
        assertEquals(LaunchOutcome.IntentSent, dispatcher.dispatch(target(
            platformId = PlatformId.N64,
            packageName = packageName,
            locator = "content://mimir.intent.contract/document/Conker%27s%20Bad%20Fur%20Day.z64",
            locatorForm = LaunchLocatorForm.CONTENT_URI,
        )))
        return requireNotNull(launched)
    }

    @Test
    fun armsX2ReceivesExplicitComponentAndRawPathExtraWithoutPrivateProviderUri() {
        var launched: Intent? = null
        val dispatcher = IntentLaunchDispatcher(
            context = context,
            plainPathDefinitelyMissing = { false },
            startActivity = { launched = it },
        )
        val path = "/storage/6E0E-FBB0/ROMS/ps2/Resident Evil 2.chd"

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId.PS2,
                packageName = "come.nanodata.armsx2",
                locator = path,
                locatorForm = LaunchLocatorForm.FILE_PATH,
            ),
        )

        assertEquals(LaunchOutcome.IntentSent, outcome)
        val intent = requireNotNull(launched)
        assertEquals("come.nanodata.armsx2", intent.component?.packageName)
        assertEquals("com.armsx2.MainActivity", intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_DEFAULT))
        assertEquals(path, intent.getStringExtra("path"))
        assertNull(intent.data)
        assertNull(intent.clipData)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun edenReceivesGrantableSafDataClipAndExactNightlyComponent() {
        var launched: Intent? = null
        val dispatcher = IntentLaunchDispatcher(context) { launched = it }
        // An intentionally unclaimed authority keeps the test deterministic: this verifies Android's
        // concrete Intent/ClipData envelope without depending on a device-specific persisted SAF grant.
        val uri = "content://mimir.intent.contract/document/switch-test.nsp"

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId.SWITCH,
                packageName = "dev.legacy.eden_emulator.nightly",
                locator = uri,
                locatorForm = LaunchLocatorForm.CONTENT_URI,
            ),
        )

        assertEquals(LaunchOutcome.IntentSent, outcome)
        val intent = requireNotNull(launched)
        assertEquals("dev.legacy.eden_emulator.nightly", intent.component?.packageName)
        assertEquals("org.yuzu.yuzu_emu.activities.EmulationActivity", intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.dataString)
        assertEquals("application/octet-stream", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, intent.clipData?.itemCount)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri?.toString())
        assertFalse(intent.hasExtra("path"))
    }

    @Test
    fun aPs3eShareableRouteCarriesDataClipGrantAndIsoUriExtra() {
        var launched: Intent? = null
        val dispatcher = IntentLaunchDispatcher(context) { launched = it }
        val uri = "content://mimir.intent.contract/document/ps3-test.iso"

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId("ps3"),
                packageName = "aenu.aps3e",
                locator = uri,
                locatorForm = LaunchLocatorForm.EITHER,
            ),
        )

        assertEquals(LaunchOutcome.IntentSent, outcome)
        val intent = requireNotNull(launched)
        assertEquals("aenu.aps3e", intent.component?.packageName)
        assertEquals("aenu.aps3e.EmulatorActivity", intent.component?.className)
        assertEquals("aenu.intent.action.APS3E", intent.action)
        assertEquals(uri, intent.dataString)
        assertEquals(uri, intent.getStringExtra("iso_uri"))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri?.toString())
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_DEFAULT))
    }

    @Test
    fun officialDolphinReceivesDirectGrantableRomUriThroughMainActivity() {
        var launched: Intent? = null
        val packageName = "org.dolphinemu.dolphinemu"
        val dispatcher = IntentLaunchDispatcher(context) { launched = it }
        val uri = "content://mimir.intent.contract/document/gamecube-test.rvz"

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId.GAMECUBE,
                packageName = packageName,
                locator = uri,
                locatorForm = LaunchLocatorForm.CONTENT_URI,
            ),
        )

        assertEquals(LaunchOutcome.IntentSent, outcome)
        val intent = requireNotNull(launched)
        assertEquals(packageName, intent.component?.packageName)
        assertEquals("org.dolphinemu.dolphinemu.ui.main.MainActivity", intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(uri, intent.dataString)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri?.toString())
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun preparationReapsBeforeOneShotStartAndCarriesExactPreflight() {
        val events = mutableListOf<String>()
        val dispatcher = IntentLaunchDispatcher(
            context = context,
            preDispatchReap = {
                events += "reap:$it"
                LaunchTargetReapResult.REQUEST_ACCEPTED
            },
            plainPathDefinitelyMissing = { false },
            startActivity = {
                events += "start:${it.component?.packageName}"
            },
        )
        val launch = dispatcher.prepare(
            target(
                platformId = PlatformId.PS2,
                packageName = "come.nanodata.armsx2",
                locator = "/storage/emulated/0/ROMS/PS2/test.iso",
                locatorForm = LaunchLocatorForm.FILE_PATH,
            ),
        )

        assertEquals(listOf("reap:come.nanodata.armsx2"), events)
        assertEquals(
            LaunchTargetPreflightContext(
                packageName = "come.nanodata.armsx2",
                result = LaunchTargetReapResult.REQUEST_ACCEPTED,
            ),
            launch.preflight,
        )
        assertEquals(LaunchOutcome.IntentSent, launch.dispatch())
        assertEquals(LaunchOutcome.IntentSent, launch.dispatch())
        assertEquals(
            listOf("reap:come.nanodata.armsx2", "start:come.nanodata.armsx2"),
            events,
        )
    }

    @Test
    fun synchronousActivityFailureRemainsTypedAfterPreparation() {
        val dispatcher = IntentLaunchDispatcher(
            context = context,
            plainPathDefinitelyMissing = { false },
            startActivity = { throw ActivityNotFoundException("missing") },
        )
        val outcome = dispatcher.prepare(
            target(
                platformId = PlatformId.PS2,
                packageName = "come.nanodata.armsx2",
                locator = "/storage/emulated/0/ROMS/PS2/test.iso",
                locatorForm = LaunchLocatorForm.FILE_PATH,
            ),
        ).dispatch()

        assertTrue(outcome is LaunchOutcome.Failed)
        assertEquals(
            LaunchDispatchFailure.ACTIVITY_NOT_STARTED,
            (outcome as LaunchOutcome.Failed).reason,
        )
    }

    @Test
    fun definitelyMissingPlainPathFailsBeforeReapOrActivityStart() {
        val events = mutableListOf<String>()
        val missingPath = "/storage/emulated/0/MimirQA-fixtures/LF01/ROMs/gb/Tetris.gb"
        val dispatcher = IntentLaunchDispatcher(
            context = context,
            preDispatchReap = {
                events += "reap:$it"
                LaunchTargetReapResult.REQUEST_ACCEPTED
            },
            bestEffortReap = { events += "best-effort:$it" },
            plainPathDefinitelyMissing = { path ->
                assertEquals(missingPath, path)
                true
            },
            startActivity = { events += "start:${it.component?.packageName}" },
        )

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId.GB,
                packageName = "com.retroarch.aarch64",
                locator = missingPath,
                locatorForm = LaunchLocatorForm.FILE_PATH,
                retroArchCore = "gambatte",
            ),
        )

        assertTrue(outcome is LaunchOutcome.Failed)
        assertEquals(LaunchDispatchFailure.ROM_PATH_MISSING, (outcome as LaunchOutcome.Failed).reason)
        assertTrue("missing ROM must not reap or start any target", events.isEmpty())
    }

    @Test
    fun uncertainPlainPathStillFollowsNormalReapAndStartDispatch() {
        val events = mutableListOf<String>()
        val dispatcher = IntentLaunchDispatcher(
            context = context,
            preDispatchReap = {
                events += "reap:$it"
                LaunchTargetReapResult.REQUEST_ACCEPTED
            },
            plainPathDefinitelyMissing = { false },
            startActivity = { events += "start:${it.component?.packageName}" },
        )

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId.PS2,
                packageName = "come.nanodata.armsx2",
                locator = "/unknown-or-denied/rom.iso",
                locatorForm = LaunchLocatorForm.FILE_PATH,
            ),
        )

        assertEquals(LaunchOutcome.IntentSent, outcome)
        assertEquals(
            listOf("reap:come.nanodata.armsx2", "start:come.nanodata.armsx2"),
            events,
        )
    }

    @Test
    fun noContentProfileDoesNotStatOrRejectItsUnusedLocator() {
        var probed = false
        var launched: Intent? = null
        val dispatcher = IntentLaunchDispatcher(
            context = context,
            preDispatchReap = { LaunchTargetReapResult.REQUEST_ACCEPTED },
            bestEffortReap = {},
            plainPathDefinitelyMissing = { probed = true; true },
            startActivity = { launched = it },
        )

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId("ps3"),
                packageName = "net.rpcsx",
                locator = "/definitely/missing/unused.iso",
                locatorForm = LaunchLocatorForm.FILE_PATH,
            ),
        )

        assertEquals(LaunchOutcome.IntentSent, outcome)
        assertFalse("NO_CONTENT profiles ignore locator paths", probed)
        assertNull(requireNotNull(launched).data)
    }

    /**
     * R-2b, BUILT / DEVICE-UNCERTIFIED: proves the exact intent shape against the two-sided proven
     * contract  -- typed extras read from a REAL
     * shortcut file's content (not just its path, unlike every other case in this file), and EXACTLY
     * NEW_TASK|CLEAR_TOP, never the shared ResidentLaunchFlags every other profile gets. No .steam file
     * was available on the test device, so this has not run on Thor -- it exists so that when one does,
     * the assertions are already written.
     */
    @Test
    fun gameNativeReceivesTypedAppIdAndGameSourceExtrasWithProvenFlagsOnly() {
        var launched: Intent? = null
        val dispatcher = IntentLaunchDispatcher(context) { launched = it }
        val shortcut = java.io.File(context.cacheDir, "Half-Life 2.steam").apply { writeText("440") }

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId("pc_games"),
                packageName = "app.gamenative",
                locator = shortcut.absolutePath,
                locatorForm = LaunchLocatorForm.FILE_PATH,
            ),
        )

        assertEquals(LaunchOutcome.IntentSent, outcome)
        val intent = requireNotNull(launched)
        assertEquals("app.gamenative", intent.component?.packageName)
        assertEquals("app.gamenative.MainActivity", intent.component?.className)
        assertEquals("app.gamenative.LAUNCH_GAME", intent.action)
        assertEquals(440, intent.getIntExtra("app_id", -1))
        assertEquals("STEAM", intent.getStringExtra("game_source"))
        assertNull("GameNative's contract carries no data URI", intent.data)
        assertNull("GameNative's contract carries no ClipData", intent.clipData)
        assertEquals(
            "must be EXACTLY NEW_TASK|CLEAR_TOP, not the shared ResidentLaunchFlags",
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            intent.flags,
        )
        shortcut.delete()
    }

    /** R-2b: a malformed shortcut body must be a STATED failure, never a dropped/silent intent. */
    @Test
    fun gameNativeMalformedShortcutSurfacesATypedFailureNotASilentDrop() {
        val dispatcher = IntentLaunchDispatcher(context) { launched -> Unit.also { launched } }
        val shortcut = java.io.File(context.cacheDir, "Damaged.steam").apply { writeText("-1") }

        val outcome = dispatcher.dispatch(
            target(
                platformId = PlatformId("pc_games"),
                packageName = "app.gamenative",
                locator = shortcut.absolutePath,
                locatorForm = LaunchLocatorForm.FILE_PATH,
            ),
        )

        assertTrue(outcome is LaunchOutcome.Failed)
        assertEquals(
            LaunchDispatchFailure.LOCATOR_CONTENT_MALFORMED,
            (outcome as LaunchOutcome.Failed).reason,
        )
        shortcut.delete()
    }

    private fun target(
        platformId: PlatformId,
        packageName: String,
        locator: String,
        locatorForm: LaunchLocatorForm,
        capability: EmulatorCapability = EmulatorCapability.DIRECT_PAYLOAD,
        retroArchCore: String? = null,
    ) = LaunchTarget(
        gameId = GameId("game:${platformId.value}:android-intent-contract"),
        platformId = platformId,
        emulator = EmulatorProfile(
            id = EmulatorId.fromNativeLauncherKey(packageName),
            packageName = packageName,
            displayName = packageName,
            retroArchCore = retroArchCore,
            installed = true,
            capability = capability,
            componentAvailable = true,
            locatorForm = locatorForm,
            supportedPlatforms = setOf(platformId),
        ),
        locator = locator,
        locatorForm = locatorForm,
        title = "Intent contract",
    )
}
