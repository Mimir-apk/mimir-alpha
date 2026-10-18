package com.gruning.mimir.rewrite

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.launch.IntentLaunchDispatcher
import com.gruning.mimir.rewrite.core.launch.LaunchTargetReapResult
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Intent construction and dispatch side effects.
 *
 * The real [IntentLaunchDispatcher] runs with its `startActivity`, reap and path-probe seams replaced, so
 * what is asserted is the exact Intent Android would be handed and the order of the side effects around it.
 * No device is touched.
 *
 * Deliberately NOT asserted here (written up as gaps instead): that a RetroArch core file exists, and the
 * user id inside the RetroArch `CONFIGFILE` / `LIBRETRO` extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrackerLaunchIntentTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val duck = "com.github.stenzek.duckstation"
    private val duckActivity = "com.github.stenzek.duckstation.EmulationActivity"
    private val ppsspp = "org.ppsspp.ppsspp"
    private val dolphin = "org.dolphinemu.dolphinemu"
    private val retroActivity = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    private val residentFlags =
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private inner class Harness(
        private val failWith: Throwable? = null,
        private val romMissing: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val started = mutableListOf<Intent>()
        val dispatcher = IntentLaunchDispatcher(
            context = context,
            preDispatchReap = { pkg ->
                events += "reap:$pkg"
                LaunchTargetReapResult.REQUEST_ACCEPTED
            },
            bestEffortReap = { pkg -> events += "cleanup:$pkg" },
            packageLaunchIntent = { null },
            plainPathDefinitelyMissing = { romMissing },
            startActivity = { intent ->
                events += "start:${intent.component?.packageName}"
                started += intent
                failWith?.let { throw it }
            },
        )
    }

    private fun target(
        platform: PlatformId,
        pkg: String,
        locator: String,
        form: LaunchLocatorForm = LaunchLocatorForm.FILE_PATH,
        retroArchCore: String? = null,
    ) = LaunchTarget(
        gameId = GameId("game:${platform.value}:$locator"),
        platformId = platform,
        emulator = EmulatorProfile(
            id = EmulatorId.fromNativeLauncherKey(pkg),
            packageName = pkg,
            displayName = pkg,
            retroArchCore = retroArchCore,
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = form,
            supportedPlatforms = setOf(platform),
        ),
        locator = locator,
        locatorForm = form,
        title = "Sample",
    )

    private val safPlaylist =
        "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs/" +
            "document/6A7F-FCFE%3AROMs%2FPSX%2FGame.m3u"

    // ---- rows 1-2: DuckStation ----------------------------------------------------------------------

    @Test
    fun duckStationGetsThePlaylistsAbsolutePathAsBootPathAndNeverAUri() {
        val playlist = temporaryFolder.newFile("Game.m3u")
        val harness = Harness()

        val outcome = harness.dispatcher.dispatch(target(PlatformId.PS1, duck, playlist.absolutePath))

        assertEquals(LaunchOutcome.IntentSent, outcome)
        val intent = harness.started.single()
        assertEquals(ComponentName(duck, duckActivity), intent.component)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(
            "the playlist's own path, so DuckStation resolves relative entries beside it",
            playlist.absolutePath,
            intent.getStringExtra("bootPath"),
        )
        assertFalse(intent.getBooleanExtra("resumeState", true))
        assertNull("no data URI: a content URI is what broke relative m3u entries elsewhere", intent.data)
        assertNull(intent.clipData)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun duckStationIsRefusedAContentUriBeforeAnythingIsStarted() {
        val harness = Harness()

        val outcome = harness.dispatcher.dispatch(target(PlatformId.PS1, duck, safPlaylist))

        assertTrue(outcome is LaunchOutcome.Failed)
        assertEquals(LaunchDispatchFailure.LOCATOR_FORM_REJECTED, (outcome as LaunchOutcome.Failed).reason)
        assertTrue("nothing was started", harness.started.isEmpty())
    }

    // ---- rows 5-6: PPSSPP -----------------------------------------------------------------------------

    /**
     * PPSSPP's VIEW intent is built from a Mimir FileProvider URI, which needs a real provider root. That
     * cannot be resolved under Robolectric on this Windows host ("Failed to find configured root"), so the
     * URI shape (content scheme, `.launchfiles` authority, file name with its extension as the last path
     * segment, read grant + clip) is a device check; the profile itself is pinned by
     * ShippingLaunchProfilesTest.ppssppGoldTargetsSharedPpssppActivityWithDataRoute and the grant + clip
     * behaviour shared by every URI profile is pinned by the Dolphin test below.
     *
     * What IS host-provable: PPSSPP is path-only, so a SAF content locator is refused before anything is
     * started and PPSSPP is never handed a raw tree/document URI it would call an unknown file type.
     */
    @Test
    fun ppssppIsPathOnlyAndRefusesASafContentLocatorBeforeAnythingIsStarted() {
        for (name in listOf("Game.iso", "Game.chd")) {
            val harness = Harness()
            val documentUri = safPlaylist.replace("Game.m3u", name)

            val outcome = harness.dispatcher.dispatch(target(PlatformId.PSP, ppsspp, documentUri))

            assertTrue(name, outcome is LaunchOutcome.Failed)
            assertEquals(LaunchDispatchFailure.LOCATOR_FORM_REJECTED, (outcome as LaunchOutcome.Failed).reason)
            assertEquals("file_path_required", outcome.nativeMetadata["reason"])
            assertTrue("nothing was started", harness.started.isEmpty())
        }
    }

    @Test
    fun aSafDocumentHandedToADataUriEmulatorCarriesTheGrantFlagAndClip() {
        val harness = Harness()

        val outcome = harness.dispatcher.dispatch(
            target(PlatformId.GAMECUBE, dolphin, safPlaylist, LaunchLocatorForm.CONTENT_URI),
        )

        assertEquals(LaunchOutcome.IntentSent, outcome)
        val intent = harness.started.single()
        assertEquals(safPlaylist, intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(safPlaylist, intent.clipData!!.getItemAt(0).uri.toString())
    }

    // ---- rows 15-17: relaunch ----------------------------------------------------------------------------

    @Test
    fun relaunchingTheSameGameReapsTheEmulatorThenStartsAFreshTaskEveryTime() {
        val first = temporaryFolder.newFile("First.m3u")
        val second = temporaryFolder.newFile("Second.m3u")
        val harness = Harness()

        harness.dispatcher.dispatch(target(PlatformId.PS1, duck, first.absolutePath))
        harness.dispatcher.dispatch(target(PlatformId.PS1, duck, first.absolutePath))
        harness.dispatcher.dispatch(target(PlatformId.PS1, duck, second.absolutePath))

        assertEquals(
            "each launch reaps the exact emulator first and then starts it; a same-emulator relaunch adds no cross-cleanup",
            listOf("reap:$duck", "start:$duck", "reap:$duck", "start:$duck", "reap:$duck", "start:$duck"),
            harness.events,
        )
        assertTrue("the same game builds the same intent", harness.started[0].filterEquals(harness.started[1]))
        assertEquals(first.absolutePath, harness.started[1].getStringExtra("bootPath"))
        assertEquals("a different game carries its own payload", second.absolutePath, harness.started[2].getStringExtra("bootPath"))
        for (intent in harness.started) {
            assertEquals(
                "always a fresh task: new, multiple, cleared, retained in recents",
                residentFlags,
                intent.flags and residentFlags,
            )
        }
    }

    @Test
    fun launchingADifferentEmulatorAlsoCleansUpTheLastOneBeforeStarting() {
        val playlist = temporaryFolder.newFile("Game.m3u")
        val harness = Harness()

        harness.dispatcher.dispatch(target(PlatformId.PS1, duck, playlist.absolutePath))
        harness.dispatcher.dispatch(
            target(PlatformId.GAMECUBE, dolphin, safPlaylist, LaunchLocatorForm.CONTENT_URI),
        )

        assertEquals(
            listOf("reap:$duck", "start:$duck", "reap:$dolphin", "cleanup:$duck", "start:$dolphin"),
            harness.events,
        )
    }

    // ---- rows 7 and 10: every failure is a typed, visible outcome -----------------------------------------

    @Test
    fun anActivityThatIsNotThereIsATypedFailureNotASilentNoOp() {
        val playlist = temporaryFolder.newFile("Game.m3u")
        val harness = Harness(failWith = ActivityNotFoundException("renamed"))

        val outcome = harness.dispatcher.dispatch(target(PlatformId.PS1, duck, playlist.absolutePath))

        assertTrue(outcome is LaunchOutcome.Failed)
        outcome as LaunchOutcome.Failed
        assertEquals(LaunchDispatchFailure.ACTIVITY_NOT_STARTED, outcome.reason)
        assertEquals("activity_not_started", outcome.nativeMetadata["reason"])
        assertEquals(duck, outcome.nativeMetadata["package"])
    }

    @Test
    fun aRevokedGrantOnAContentLaunchIsDistinguishedFromAnActivityFailure() {
        val content = Harness(failWith = SecurityException("grant revoked"))
        val contentOutcome = content.dispatcher.dispatch(
            target(PlatformId.GAMECUBE, dolphin, safPlaylist, LaunchLocatorForm.CONTENT_URI),
        )
        assertEquals(
            LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE,
            (contentOutcome as LaunchOutcome.Failed).reason,
        )

        val playlist = temporaryFolder.newFile("Game.m3u")
        val path = Harness(failWith = SecurityException("denied"))
        val pathOutcome = path.dispatcher.dispatch(target(PlatformId.PS1, duck, playlist.absolutePath))
        assertEquals(LaunchDispatchFailure.ACTIVITY_NOT_STARTED, (pathOutcome as LaunchOutcome.Failed).reason)
    }

    @Test
    fun aRomDeletedSinceTheScanIsAMissingFileFailureAndNothingIsStarted() {
        val harness = Harness(romMissing = true)

        val outcome = harness.dispatcher.dispatch(
            target(PlatformId.PS1, duck, "/storage/6A7F-FCFE/ROMs/PSX/Gone.m3u"),
        )

        assertEquals(LaunchDispatchFailure.ROM_PATH_MISSING, (outcome as LaunchOutcome.Failed).reason)
        assertTrue(harness.started.isEmpty())
    }

    // ---- row 13: the three RetroArch builds at the Intent level ------------------------------------------------

    @Test
    fun eachRetroArchBuildIsLaunchedByExplicitComponentInItsOwnPackage() {
        val rom = temporaryFolder.newFile("Sample.gba")
        for (pkg in listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")) {
            val harness = Harness()

            val outcome = harness.dispatcher.dispatch(
                target(PlatformId.GBA, pkg, rom.absolutePath, retroArchCore = "mgba"),
            )

            assertEquals(pkg, LaunchOutcome.IntentSent, outcome)
            val intent = harness.started.single()
            assertEquals(ComponentName(pkg, retroActivity), intent.component)
            assertEquals(rom.absolutePath, intent.getStringExtra("ROM"))
            assertTrue(
                "the core path names this build: ${intent.getStringExtra("LIBRETRO")}",
                "/$pkg/" in intent.getStringExtra("LIBRETRO").orEmpty(),
            )
            assertTrue("/$pkg/" in intent.getStringExtra("CONFIGFILE").orEmpty())
            assertEquals(residentFlags, intent.flags and residentFlags)
        }
    }
}
