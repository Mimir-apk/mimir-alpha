package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.MimirEqualizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the one Equalizer stores, how the two switches it used to have become one, and how Your music's own player stands down for the
 * global engine, with a real Context: the switch persists inside the Equalizer's own string, a stored System EQ switch from an older
 * build is merged into it once (either on is on; both off is off) and removed, and the player's view of the Equalizer is the choice
 * with its switch off for exactly as long as the global engine has the mix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class SystemEqualizerPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("mimir_rewrite_audio", Context.MODE_PRIVATE)

    @Before
    fun clean() = reset()

    @After
    fun tearDown() = reset()

    /** The preferences are a process singleton; whatever a test changes is put back. */
    private fun reset() {
        AudioPreferences.setSystemEqualizerActive(false)
        prefs.edit().clear().commit()
        AudioPreferences.load(context)
    }

    /** The string an older build stored for a music-only Equalizer that was [enabled], on the Vocal preset with 2 dB of bass. */
    private fun storedEqualizer(enabled: Boolean): String {
        MimirEqualizer.applyPreset(EqualizerPresetId.VOCAL)
        MimirEqualizer.setBass(2f)
        MimirEqualizer.setEnabled(enabled)
        return prefs.getString("equalizer", null)!!
    }

    /** A new process: the flows begin at their defaults and the file holds what an older build left. */
    private fun restart() {
        AudioPreferences.forgetEqualizerLoadedForTesting()
        AudioPreferences.setSystemEqualizerActive(false)
        AudioPreferences.load(context)
    }

    // ── the one switch persists ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the Equalizer is off for a player who never touched it, and no second switch is stored`() {
        assertFalse(AudioPreferences.equalizerChoice.value.enabled)
        assertFalse(AudioPreferences.systemEqualizerActive.value)
        assertFalse(prefs.contains("systemEqualizer"))
    }

    @Test
    fun `the one switch is part of the stored Equalizer, is read back at the next start, and never writes the old key`() {
        MimirEqualizer.setEnabled(true)
        assertTrue("stored whole, switch included", prefs.getString("equalizer", null)!!.startsWith("1;true;"))
        assertFalse("nothing writes the second switch any more", prefs.contains("systemEqualizer"))

        restart()
        assertTrue("restored by the activity's load", AudioPreferences.equalizerChoice.value.enabled)

        // A start that gets there first (the Application, before any activity) reads it on demand.
        AudioPreferences.setSystemEqualizerActive(false)
        AudioPreferences.forgetEqualizerLoadedForTesting()
        AudioPreferences.ensureEqualizerLoaded(context)
        assertTrue("restored by the on-demand load", AudioPreferences.equalizerChoice.value.enabled)

        MimirEqualizer.setEnabled(false)
        restart()
        assertFalse("an off stays off across a start", AudioPreferences.equalizerChoice.value.enabled)
    }

    // ── the migration of the two old switches ────────────────────────────────────────────────────────

    @Test
    fun `an older build's music-only switch on, with the System EQ never touched, is the Equalizer on with its curve`() {
        val stored = storedEqualizer(enabled = true)
        restart()
        assertTrue(AudioPreferences.equalizerChoice.value.enabled)
        assertEquals(EqualizerPresetId.VOCAL, AudioPreferences.equalizerChoice.value.presetId)
        assertEquals(stored, prefs.getString("equalizer", null))
    }

    @Test
    fun `an older build's System EQ switch on, with the music-only one off, is the Equalizer on with the stored curve and preset`() {
        storedEqualizer(enabled = false)
        prefs.edit().putBoolean("systemEqualizer", true).commit()
        restart()

        val choice = AudioPreferences.equalizerChoice.value
        assertTrue("either switch on is on", choice.enabled)
        assertEquals("the preset it had", EqualizerPresetId.VOCAL, choice.presetId)
        assertEquals("and its shelf", 2f, choice.bassDb, 0f)
        assertTrue("written back whole, switch on", prefs.getString("equalizer", null)!!.startsWith("1;true;VOCAL;2.0;"))
        assertFalse("the old key is gone", prefs.contains("systemEqualizer"))
    }

    @Test
    fun `both older switches on is on, and both off is off, and either way the old key is gone`() {
        storedEqualizer(enabled = true)
        prefs.edit().putBoolean("systemEqualizer", true).commit()
        restart()
        assertTrue(AudioPreferences.equalizerChoice.value.enabled)
        assertFalse(prefs.contains("systemEqualizer"))

        storedEqualizer(enabled = false)
        prefs.edit().putBoolean("systemEqualizer", false).commit()
        restart()
        assertFalse(AudioPreferences.equalizerChoice.value.enabled)
        assertFalse("a stored off is removed too, so nothing is left to read", prefs.contains("systemEqualizer"))
        assertEquals(EqualizerPresetId.VOCAL, AudioPreferences.equalizerChoice.value.presetId)
    }

    @Test
    fun `a System EQ switch on with no Equalizer ever stored is the Equalizer on, flat`() {
        prefs.edit().clear().putBoolean("systemEqualizer", true).commit()
        restart()
        assertTrue(AudioPreferences.equalizerChoice.value.enabled)
        assertTrue(AudioPreferences.equalizerChoice.value.isFlat)
        assertFalse(prefs.contains("systemEqualizer"))
    }

    @Test
    fun `the on-demand load merges too, and the merge is idempotent - the old key never turns a switched-off Equalizer back on`() {
        storedEqualizer(enabled = false)
        prefs.edit().putBoolean("systemEqualizer", true).commit()
        AudioPreferences.forgetEqualizerLoadedForTesting()
        AudioPreferences.setSystemEqualizerActive(false)
        AudioPreferences.ensureEqualizerLoaded(context)
        assertTrue("merged by the Application's early read", AudioPreferences.equalizerChoice.value.enabled)
        assertFalse(prefs.contains("systemEqualizer"))

        // The activity's load comes after: nothing left to merge, nothing changes.
        val afterFirst = prefs.getString("equalizer", null)
        AudioPreferences.load(context)
        assertTrue(AudioPreferences.equalizerChoice.value.enabled)
        assertEquals(afterFirst, prefs.getString("equalizer", null))

        // The player switches it off; the next start must not read the old switch back.
        MimirEqualizer.setEnabled(false)
        restart()
        assertFalse("a later start cannot resurrect an old switch", AudioPreferences.equalizerChoice.value.enabled)
        restart()
        assertFalse(AudioPreferences.equalizerChoice.value.enabled)
    }

    // ── the curve ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the global engine applies the same stored curve the editor edits, not a second one`() {
        MimirEqualizer.applyPreset(EqualizerPresetId.VOCAL)
        MimirEqualizer.setBass(2f)
        MimirEqualizer.setEnabled(true)
        assertEquals("one curve", MimirEqualizer.settings.value, AudioPreferences.equalizerChoice.value)
        assertTrue("one stored string for it", prefs.getString("equalizer", null)!!.contains("VOCAL"))
    }

    // ── the bypass ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Your music's own equalizer stands down exactly while the global engine has the mix, and the choice is untouched`() {
        MimirEqualizer.setEnabled(true)
        MimirEqualizer.applyPreset(EqualizerPresetId.ROCK)
        val choice = MimirEqualizer.settings.value
        assertTrue(choice.enabled)
        assertEquals("with the engine out of reach the player applies the choice as it is: the stated fallback", choice, AudioPreferences.equalizer.value)

        AudioPreferences.setSystemEqualizerActive(true)
        assertFalse("the player's equalizer is off: the engine shapes everything, this music included", AudioPreferences.equalizer.value.enabled)
        assertEquals("with its curve in hand", choice.bandsDb, AudioPreferences.equalizer.value.bandsDb)
        assertEquals("the editor still holds the choice, switch on", choice, MimirEqualizer.settings.value)
        assertTrue("and the stored string is the choice, not the bypass", prefs.getString("equalizer", null)!!.startsWith("1;true;"))

        AudioPreferences.setSystemEqualizerActive(false)
        assertEquals("the moment the engine is gone the player's own is back, as it was", choice, AudioPreferences.equalizer.value)
    }

    @Test
    fun `an edit while bypassed reaches the stored choice and the engine's curve, and the player stays bypassed`() {
        MimirEqualizer.setEnabled(true)
        AudioPreferences.setSystemEqualizerActive(true)
        MimirEqualizer.setBass(3f)
        MimirEqualizer.setBand(4, 5f)
        assertEquals(3f, MimirEqualizer.settings.value.bassDb, 0f)
        assertEquals(5f, AudioPreferences.equalizerChoice.value.bandsDb[4], 0f)
        assertFalse(AudioPreferences.equalizer.value.enabled)
        AudioPreferences.setSystemEqualizerActive(false)
        assertTrue(AudioPreferences.equalizer.value.enabled)
        assertEquals(5f, AudioPreferences.equalizer.value.bandsDb[4], 0f)
    }

    @Test
    fun `a switch that is off is off for the player whether or not the engine has the mix`() {
        assertFalse(AudioPreferences.equalizer.value.enabled)
        AudioPreferences.setSystemEqualizerActive(true)
        assertFalse(AudioPreferences.equalizer.value.enabled)
        MimirEqualizer.setEnabled(true)
        assertFalse("turned on while the engine has the mix: still bypassed, never twice", AudioPreferences.equalizer.value.enabled)
        AudioPreferences.setSystemEqualizerActive(false)
        assertTrue(AudioPreferences.equalizer.value.enabled)
    }

    @Test
    fun `the bypass is not stored, so a new process starts without it`() {
        AudioPreferences.setSystemEqualizerActive(true)
        assertFalse(prefs.all.keys.any { it.contains("active", ignoreCase = true) })
        assertFalse(prefs.contains("systemEqualizer"))
    }
}
