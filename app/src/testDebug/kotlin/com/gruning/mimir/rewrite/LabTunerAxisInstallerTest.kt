package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.tweaker.TweakerLabTunerAxisContract
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Arming, installing and disarming the synthetic axis — the only code that writes [MimirTunerBackend].
 *
 * **Separate from the axis's own suite because this one mutates process-global state.** The three fields
 * it writes are the same fields `LabTunerAxisTest` pins as untouched defaults, so every test here
 * restores them, and the restoration is itself part of what is under test: a disarm that left the tuner
 * pointed at a deleted directory would be worse than never arming.
 *
 * The ordering property the proof depends on — that `installIfArmed` runs before the cold-start
 * reconcile — is a `MainActivity.onCreate` statement order and is not reachable from a unit test. It is
 * device-verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class LabTunerAxisInstallerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun axis(): File = LabTunerAxisInstaller.axisDirectory(context)

    @After
    fun disarm() {
        LabTunerAxisInstaller.disarm(context)
        MimirTunerWrite.forgetCapture()
    }

    @Test
    fun `an unarmed device installs nothing and keeps the real backend`() {
        axis().deleteRecursively()
        LabTunerAxisInstaller.installIfArmed(context)
        assertTrue(MimirTunerBackend.nodes() is RealMimirTunerNodes)
        assertFalse(MimirTunerBackend.hostAvailable())
    }

    @Test
    fun `arming seeds the axis and points the tuner at it`() {
        LabTunerAxisInstaller.arm(context)
        val directory = axis()
        assertTrue(directory.isDirectory)
        assertTrue(MimirTunerBackend.nodes() is LabTunerNodes)
        // A file-writing host running as this app's own uid is always present; routing this is what lets
        // the proof run on a handheld with no vendor performance server.
        assertTrue(MimirTunerBackend.hostAvailable())

        val policies = discoverTunerPolicies(MimirTunerBackend.nodes())
        assertEquals(1, policies.size)
        assertEquals(TweakerLabTunerAxisContract.LAB_POLICY_ID, policies.single().id)
        assertEquals(
            TweakerLabTunerAxisContract.LAB_STOCK_ROW,
            File(directory, TweakerLabTunerAxisContract.LAB_QOS_FILE_NAME).readText().trim(),
        )
    }

    @Test
    fun `re-arming an axis already in use does not reset the cap under measurement`() {
        LabTunerAxisInstaller.arm(context)
        val qos = File(axis(), TweakerLabTunerAxisContract.LAB_QOS_FILE_NAME)
        qos.writeText("240:1800000 241:1800000\n")

        // A second arm extra -- a mistyped script line, a re-run -- must not silently destroy the state
        // the proof is in the middle of measuring, which would turn a real adoption failure into a
        // "discarded" that reads exactly like the staleness guard working correctly.
        LabTunerAxisInstaller.arm(context)
        assertEquals("240:1800000 241:1800000", qos.readText().trim())
    }

    @Test
    fun `disarming removes the axis and puts the real device back`() {
        LabTunerAxisInstaller.arm(context)
        assertTrue(axis().isDirectory)

        LabTunerAxisInstaller.disarm(context)
        assertFalse(axis().exists())
        assertTrue(MimirTunerBackend.nodes() is RealMimirTunerNodes)
        assertFalse(MimirTunerBackend.hostAvailable())
    }
}
