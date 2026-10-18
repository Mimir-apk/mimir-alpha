package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MimirEqualizer
import com.gruning.mimir.rewrite.core.audio.MimirMusicHours
import com.gruning.mimir.rewrite.core.audio.MimirMusicLibrary
import com.gruning.mimir.rewrite.core.audio.MimirMusicOrder
import com.gruning.mimir.rewrite.core.audio.MimirMusicSource
import com.gruning.mimir.rewrite.core.audio.MusicLibraryError
import com.gruning.mimir.rewrite.core.audio.MusicLibraryState
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * What Settings > Music stores and what its folder choice does, with a real Context : the nine
 * preferences persist under their documented keys and come back at startup, and the Custom music folder takes both shapes
 * the pickers hand back (a `file://` folder from Mimir's own browser, a `content://` tree from the system picker).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class MusicSettingsPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("mimir_rewrite_audio", Context.MODE_PRIVATE)
    private val roots = mutableListOf<File>()

    @Before
    fun clean() = reset()

    @After
    fun tearDown() {
        reset()
        roots.forEach { it.deleteRecursively() }
    }

    /** The preferences and the library are process singletons; whatever a test changes is put back. */
    private fun reset() {
        MimirMusicLibrary.forget(context)
        prefs.edit().clear().commit()
        AudioPreferences.load(context)
    }

    // ── the preferences ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every setting is stored under its documented key and read back at the next start`() {
        AudioPreferences.setMusicEnabled(context, false)
        AudioPreferences.setMusicSource(context, MimirMusicSource.CUSTOM)
        AudioPreferences.setMusicOrder(context, MimirMusicOrder.IN_ORDER)
        AudioPreferences.setSongTitlesEnabled(context, false)
        AudioPreferences.setCustomMusicTreeUri(context, "file:///sdcard/ROMs/music")
        AudioPreferences.setLaunchSoundEnabled(context, false)
        AudioPreferences.setLaunchSoundVolume(context, 0.4f)
        AudioPreferences.setGameMusicOnHighlight(context, true)
        AudioPreferences.setKeepMusicInGames(context, false)

        assertEquals(false, prefs.getBoolean("musicEnabled", true))
        assertEquals("CUSTOM", prefs.getString("musicSource", null))
        assertEquals("IN_ORDER", prefs.getString("musicOrder", null))
        assertEquals(false, prefs.getBoolean("songTitlesEnabled", true))
        assertEquals("file:///sdcard/ROMs/music", prefs.getString("customMusicTreeUri", null))
        assertEquals(false, prefs.getBoolean("launchSoundEnabled", true))
        assertEquals(0.4f, prefs.getFloat("launchSoundVolume", 1f), 0f)
        assertEquals(true, prefs.getBoolean("gameMusicOnHighlight", false))
        assertEquals(false, prefs.getBoolean("keepMusicInGames", true))

        // A restart: the stored values are what the object holds after load, whatever it held before.
        AudioPreferences.setMusicEnabled(context, true)
        prefs.edit().putBoolean("musicEnabled", false).commit()
        AudioPreferences.load(context)
        assertFalse(AudioPreferences.musicEnabled.value)
        assertEquals(MimirMusicSource.CUSTOM, AudioPreferences.musicSource.value)
        assertEquals(MimirMusicOrder.IN_ORDER, AudioPreferences.musicOrder.value)
        assertFalse(AudioPreferences.songTitlesEnabled.value)
        assertEquals("file:///sdcard/ROMs/music", AudioPreferences.customMusicTreeUri.value)
        assertFalse(AudioPreferences.launchSoundEnabled.value)
        assertEquals(0.4f, AudioPreferences.launchSoundVolume.value, 0f)
        assertTrue(AudioPreferences.gameMusicOnHighlight.value)
        assertFalse(AudioPreferences.keepMusicInGames.value)
    }

    @Test
    fun `the music rows of 2026-10-07 are stored under their documented keys and read back at the next start`() {
        AudioPreferences.setMusicHours(context, MimirMusicHours.EVENING)
        AudioPreferences.setFadeBetweenTracks(context, true)
        AudioPreferences.setPlayOutsideMimir(context, false)
        AudioPreferences.setPauseOnScreenOff(context, false)
        AudioPreferences.setPauseOnUnplug(context, false)
        AudioPreferences.setMusicOrder(context, MimirMusicOrder.IN_ORDER)

        assertEquals("EVENING", prefs.getString("musicHours", null))
        assertEquals(true, prefs.getBoolean("fadeBetweenTracks", false))
        assertEquals(false, prefs.getBoolean("playOutsideMimir", true))
        assertEquals(false, prefs.getBoolean("pauseOnScreenOff", true))
        assertEquals(false, prefs.getBoolean("pauseOnUnplug", true))
        assertEquals("IN_ORDER", prefs.getString("musicOrder", null))

        // A restart: whatever the object held, load reads the stored values back.
        AudioPreferences.setMusicHours(context, MimirMusicHours.ALL_DAY)
        AudioPreferences.setFadeBetweenTracks(context, false)
        prefs.edit().putString("musicHours", "NOT_OVERNIGHT").putBoolean("fadeBetweenTracks", true).commit()
        AudioPreferences.load(context)
        assertEquals(MimirMusicHours.NOT_OVERNIGHT, AudioPreferences.musicHours.value)
        assertTrue(AudioPreferences.fadeBetweenTracks.value)
        assertFalse(AudioPreferences.playOutsideMimir.value)
        assertFalse(AudioPreferences.pauseOnScreenOff.value)
        assertFalse(AudioPreferences.pauseOnUnplug.value)
        assertEquals(MimirMusicOrder.IN_ORDER, AudioPreferences.musicOrder.value)
    }

    @Test
    fun `a first run holds the spec's behaviour for the new rows, and a damaged hours value falls back to all day`() {
        AudioPreferences.load(context)
        assertEquals(MimirMusicHours.ALL_DAY, AudioPreferences.musicHours.value)
        assertFalse("gapless by default", AudioPreferences.fadeBetweenTracks.value)
        assertTrue(AudioPreferences.playOutsideMimir.value)
        assertTrue(AudioPreferences.pauseOnScreenOff.value)
        assertTrue(AudioPreferences.pauseOnUnplug.value)

        prefs.edit().putString("musicHours", "from-the-future").commit()
        AudioPreferences.load(context)
        assertEquals(MimirMusicHours.ALL_DAY, AudioPreferences.musicHours.value)
    }

    @Test
    fun `a stored per-platform music switch is cleared at load, and nothing re-writes it`() {
        prefs.edit().putBoolean("platformMusicEnabled", false).commit()

        AudioPreferences.load(context)

        assertFalse("the removed key is gone", prefs.contains("platformMusicEnabled"))
        AudioPreferences.setMusicEnabled(context, false)
        AudioPreferences.setSongTitlesEnabled(context, false)
        assertFalse("no setter brings it back", prefs.contains("platformMusicEnabled"))
    }

    @Test
    fun `the equalizer choice is stored whole, comes back at the next start, and every facade call persists`() {
        AudioPreferences.load(context)
        assertEquals(EqualizerSettings(), MimirEqualizer.settings.value)

        MimirEqualizer.setEnabled(true)
        MimirEqualizer.applyPreset(EqualizerPresetId.ROCK)
        assertEquals(EqualizerPresetId.ROCK, MimirEqualizer.settings.value.presetId)
        MimirEqualizer.setBand(2, -4.5f)
        MimirEqualizer.setBass(3f)
        MimirEqualizer.setTreble(-2f)
        val chosen = MimirEqualizer.settings.value
        assertEquals(EqualizerPresetId.CUSTOM, chosen.presetId)
        assertTrue(chosen.enabled)
        assertEquals(-4.5f, chosen.bandsDb[2], 0f)
        assertEquals(chosen.effectiveHeadroomDb, MimirEqualizer.effectiveHeadroomDb, 0f)
        assertNotNull(prefs.getString("equalizer", null))

        // A restart: whatever the object holds, load reads the stored string back.
        val stored = prefs.getString("equalizer", null)
        MimirEqualizer.reset()
        assertEquals(EqualizerSettings(enabled = true, customBandsDb = chosen.customBandsDb), MimirEqualizer.settings.value)
        prefs.edit().putString("equalizer", stored).commit()
        AudioPreferences.load(context)
        assertEquals(chosen, MimirEqualizer.settings.value)

        // A damaged value is the default, not an error.
        prefs.edit().putString("equalizer", "from-the-future").commit()
        AudioPreferences.load(context)
        assertEquals(EqualizerSettings(), MimirEqualizer.settings.value)
    }

    @Test
    fun `a cold start through the player reads the stored equalizer without the activity's load, once`() {
        AudioPreferences.load(context)
        MimirEqualizer.setEnabled(true)
        MimirEqualizer.applyPreset(EqualizerPresetId.VOCAL)
        MimirEqualizer.setBass(2f)
        val chosen = MimirEqualizer.settings.value
        val stored = prefs.getString("equalizer", null)
        assertNotNull(stored)

        // The process starts for a media button: nothing has loaded, the object holds the default, the file holds the choice.
        MimirEqualizer.reset()
        MimirEqualizer.setEnabled(false)
        prefs.edit().putString("equalizer", stored).commit()
        AudioPreferences.forgetEqualizerLoadedForTesting()
        AudioPreferences.ensureEqualizerLoaded(context)
        assertEquals(chosen, MimirEqualizer.settings.value)

        // Idempotent: a later call does not overwrite a change made since.
        MimirEqualizer.setBass(-4f)
        AudioPreferences.ensureEqualizerLoaded(context)
        assertEquals(-4f, MimirEqualizer.settings.value.bassDb, 0f)
    }

    @Test
    fun `a first run after the wipe holds the defaults and a damaged value falls back instead of failing`() {
        AudioPreferences.load(context)
        assertTrue(AudioPreferences.musicEnabled.value)
        assertEquals(MimirMusicSource.MIMIR, AudioPreferences.musicSource.value)
        assertEquals(MimirMusicOrder.SHUFFLE, AudioPreferences.musicOrder.value)
        assertNull(AudioPreferences.customMusicTreeUri.value)
        assertEquals(1f, AudioPreferences.launchSoundVolume.value, 0f)
        assertFalse(AudioPreferences.gameMusicOnHighlight.value)

        prefs.edit()
            .putString("musicSource", "a-source-from-the-future")
            .putString("musicOrder", "")
            .putString("customMusicTreeUri", "   ")
            .putFloat("launchSoundVolume", 9f)
            .commit()
        AudioPreferences.load(context)
        assertEquals(MimirMusicSource.MIMIR, AudioPreferences.musicSource.value)
        assertEquals(MimirMusicOrder.SHUFFLE, AudioPreferences.musicOrder.value)
        assertNull("a blank folder is no folder", AudioPreferences.customMusicTreeUri.value)
        assertEquals("clamped", 1f, AudioPreferences.launchSoundVolume.value, 0f)
    }

    @Test
    fun `the launch sound volume clamps and clearing the folder removes its key`() {
        AudioPreferences.setLaunchSoundVolume(context, 7f)
        assertEquals(1f, AudioPreferences.launchSoundVolume.value, 0f)
        AudioPreferences.setLaunchSoundVolume(context, -1f)
        assertEquals(0f, AudioPreferences.launchSoundVolume.value, 0f)

        AudioPreferences.setCustomMusicTreeUri(context, "file:///x")
        assertTrue(prefs.contains("customMusicTreeUri"))
        AudioPreferences.setCustomMusicTreeUri(context, null)
        assertFalse(prefs.contains("customMusicTreeUri"))
        assertNull(AudioPreferences.customMusicTreeUri.value)
    }

    // ── Launch sound: one choice over the two stored preferences (update) ─────────────────────────

    /** What the coordinator's choose does with a step: the toggle, and the level when the step has one. */
    private fun chooseLaunchSound(step: MusicLaunchSoundValue) {
        val write = musicLaunchSoundWrite(step)
        AudioPreferences.setLaunchSoundEnabled(context, write.enabled)
        write.volume?.let { AudioPreferences.setLaunchSoundVolume(context, it) }
    }

    private fun readLaunchSound(): MusicLaunchSoundValue =
        musicLaunchSoundValue(AudioPreferences.launchSoundEnabled.value, AudioPreferences.launchSoundVolume.value)

    @Test
    fun `a Launch sound step is stored as the toggle and the level the two old rows stored, and comes back as the same step after a restart`() {
        MusicLaunchSoundValue.entries.forEach { step ->
            chooseLaunchSound(step)
            assertEquals("$step is read at once", step, readLaunchSound())
            assertEquals("the toggle key holds the step's toggle", step.enabled, prefs.getBoolean("launchSoundEnabled", !step.enabled))
            if (step.enabled) assertEquals("the level key holds the step's level", step.percent / 100f, prefs.getFloat("launchSoundVolume", -1f), 0f)

            AudioPreferences.load(context) // a restart reads only what was stored
            assertEquals("$step is read back after a restart", step, readLaunchSound())
        }
    }

    @Test
    fun `Off keeps the level, so the next step brings the sound back, and a fresh install is 100 percent`() {
        assertEquals("a fresh install: toggle on, level full", MusicLaunchSoundValue.FULL, readLaunchSound())
        chooseLaunchSound(MusicLaunchSoundValue.HALF)
        chooseLaunchSound(MusicLaunchSoundValue.OFF)
        assertFalse(AudioPreferences.launchSoundEnabled.value)
        assertEquals("Off leaves the stored level alone", 0.5f, AudioPreferences.launchSoundVolume.value, 0f)
        chooseLaunchSound(MusicLaunchSoundValue.THREE_QUARTERS)
        assertTrue(AudioPreferences.launchSoundEnabled.value)
        assertEquals(0.75f, AudioPreferences.launchSoundVolume.value, 0f)
    }

    @Test
    fun `values the two old rows stored read as the nearest step, with no change to what is stored`() {
        // A toggle on with a dial level between the steps, as the Launch sound volume row could leave it.
        prefs.edit().putBoolean("launchSoundEnabled", true).putFloat("launchSoundVolume", 0.6f).commit()
        AudioPreferences.load(context)
        assertEquals(MusicLaunchSoundValue.HALF, readLaunchSound())
        assertEquals("the stored level is untouched by reading it", 0.6f, prefs.getFloat("launchSoundVolume", -1f), 0f)

        prefs.edit().putBoolean("launchSoundEnabled", true).putFloat("launchSoundVolume", 0.9f).commit()
        AudioPreferences.load(context)
        assertEquals(MusicLaunchSoundValue.FULL, readLaunchSound())

        // The toggle off is Off, whatever the level was; the toggle on is never Off, so a level of zero reads as the quietest step.
        prefs.edit().putBoolean("launchSoundEnabled", false).putFloat("launchSoundVolume", 0.9f).commit()
        AudioPreferences.load(context)
        assertEquals(MusicLaunchSoundValue.OFF, readLaunchSound())
        prefs.edit().putBoolean("launchSoundEnabled", false).putFloat("launchSoundVolume", 0f).commit()
        AudioPreferences.load(context)
        assertEquals(MusicLaunchSoundValue.OFF, readLaunchSound())
        prefs.edit().putBoolean("launchSoundEnabled", true).putFloat("launchSoundVolume", 0f).commit()
        AudioPreferences.load(context)
        assertEquals(MusicLaunchSoundValue.QUARTER, readLaunchSound())
        prefs.edit().putBoolean("launchSoundEnabled", true).putFloat("launchSoundVolume", 0.1f).commit()
        AudioPreferences.load(context)
        assertEquals(MusicLaunchSoundValue.QUARTER, readLaunchSound())
        assertEquals("the stored level is untouched by reading it", 0.1f, prefs.getFloat("launchSoundVolume", -1f), 0f)
    }

    @Test
    fun `settling at startup writes the step the row shows, so the level shown is the level that plays`() {
        fun stored(enabled: Boolean, level: Float) {
            prefs.edit().putBoolean("launchSoundEnabled", enabled).putFloat("launchSoundVolume", level).commit()
            AudioPreferences.load(context)
        }
        // On at zero showed 25% and played nothing: the step is written back, and the intro now plays it.
        stored(enabled = true, level = 0f)
        assertEquals(MusicLaunchSoundValue.QUARTER, readLaunchSound())
        assertNull("before the settle the intro plays nothing", gameOpeningIntroAudioTarget(true, 0f))
        settleLaunchSoundStep(context)
        assertEquals(0.25f, prefs.getFloat("launchSoundVolume", -1f), 0f)
        assertEquals(0.25f, AudioPreferences.launchSoundVolume.value, 0f)
        assertEquals(0.25f, gameOpeningIntroAudioTarget(AudioPreferences.launchSoundEnabled.value, AudioPreferences.launchSoundVolume.value))
        assertTrue("and the toggle is still on", prefs.getBoolean("launchSoundEnabled", false))
        // A level between steps is rounded to the step the row shows.
        stored(enabled = true, level = 0.6f)
        settleLaunchSoundStep(context)
        assertEquals(0.5f, prefs.getFloat("launchSoundVolume", -1f), 0f)
        // A level already on a step stays as it is, and a second settle changes nothing.
        stored(enabled = true, level = 0.75f)
        settleLaunchSoundStep(context)
        settleLaunchSoundStep(context)
        assertEquals(0.75f, prefs.getFloat("launchSoundVolume", -1f), 0f)
        // Off is only the toggle off: its level, zero included, is left alone.
        stored(enabled = false, level = 0f)
        settleLaunchSoundStep(context)
        assertEquals(MusicLaunchSoundValue.OFF, readLaunchSound())
        assertEquals(0f, prefs.getFloat("launchSoundVolume", -1f), 0f)
        assertFalse(prefs.getBoolean("launchSoundEnabled", true))
    }

    @Test
    fun `Play on highlight is still stored and read though its row is hidden`() {
        AudioPreferences.setGameMusicOnHighlight(context, true)
        assertEquals(true, prefs.getBoolean("gameMusicOnHighlight", false))
        AudioPreferences.load(context)
        assertTrue(AudioPreferences.gameMusicOnHighlight.value)
        // The read model still carries it, so the day the row returns it has its value.
        val model = musicReadModel(
            musicEnabled = true, nowPlaying = null, source = MimirMusicSource.MIMIR, order = MimirMusicOrder.IN_ORDER, ambientVolume = 0.5f,
            songTitlesEnabled = true, library = MusicLibraryState(), launchSoundEnabled = true, launchSoundVolume = 1f,
            gameMusic = GameMusicDownloadState.Idle(have = 0, total = 0), requestsToday = null,
            playOnHighlight = AudioPreferences.gameMusicOnHighlight.value, nowMillis = 0L,
        )
        assertTrue(model.playOnHighlight)
    }

    // ── the Custom music folder ──────────────────────────────────────────────────────────────────────

    private fun musicFolder(): File = Files.createTempDirectory("mimir-music-settings").toFile().also {
        roots += it
        File(it, "overworld.mp3").writeText("x")
        File(it, "notes.txt").writeText("x")
        File(it, "snes").mkdirs()
        File(it, "snes/zelda.flac").writeText("x")
        File(it, "ps2").mkdirs()
        File(it, "ps2/ico.m4a").writeText("x")
        File(it, "ps2/ff.wav").writeText("x")
    }

    private fun awaitScan(): MusicLibraryState = runBlocking {
        withTimeout(15_000) { MimirMusicLibrary.state.first { !it.scanning && (it.lastScanMillis != null || it.error != null) } }
    }

    @Test
    fun `a folder from Mimir's own browser is stored as it is, indexed, and remembered`() {
        val folder = musicFolder()
        val uri = "file://" + folder.toURI().path.trimEnd('/')

        MimirMusicLibrary.setTree(context, uri)
        val scanned = awaitScan()

        assertEquals(uri, scanned.treeUri)
        assertEquals(4, scanned.trackCount)
        assertNotNull(scanned.lastScanMillis)
        assertNull(scanned.error)
        assertEquals("the choice is stored as the browser gave it", uri, AudioPreferences.customMusicTreeUri.value)
        assertEquals(uri, prefs.getString("customMusicTreeUri", null))
        assertTrue("an app-private index was written", File(context.filesDir, "music_library_index.txt").isFile)
    }

    @Test
    fun `the next start restores the choice and the last counts without touching the folder`() {
        val folder = musicFolder()
        val uri = "file://" + folder.toURI().path.trimEnd('/')
        MimirMusicLibrary.setTree(context, uri)
        awaitScan()

        // The folder is gone, as if the card were out: a start must still show what was last indexed.
        folder.deleteRecursively()
        MimirMusicLibrary.load(context)
        assertEquals(uri, MimirMusicLibrary.state.value.treeUri)
        val restored = runBlocking { withTimeout(15_000) { MimirMusicLibrary.state.first { it.lastScanMillis != null } } }

        assertEquals(4, restored.trackCount)
        assertFalse(restored.scanning)
    }

    @Test
    fun `a rescan sees what changed, and forgetting clears the choice, the index and the counts but not the files`() {
        val folder = musicFolder()
        val uri = "file://" + folder.toURI().path.trimEnd('/')
        MimirMusicLibrary.setTree(context, uri)
        awaitScan()

        File(folder, "extra.ogg").writeText("x")
        val before = MimirMusicLibrary.state.value.lastScanMillis
        Thread.sleep(5)
        MimirMusicLibrary.rescan(context)
        val rescanned = runBlocking {
            withTimeout(15_000) { MimirMusicLibrary.state.first { !it.scanning && it.trackCount == 5 } }
        }
        assertEquals(5, rescanned.trackCount)
        assertTrue(rescanned.lastScanMillis!! >= before!!)

        MimirMusicLibrary.forget(context)
        assertEquals(MusicLibraryState(), MimirMusicLibrary.state.value)
        assertNull(AudioPreferences.customMusicTreeUri.value)
        assertFalse(prefs.contains("customMusicTreeUri"))
        assertFalse(File(context.filesDir, "music_library_index.txt").exists())
        assertTrue("the player's files are untouched", File(folder, "overworld.mp3").isFile)
    }

    @Test
    fun `a rescan with no folder does nothing, and a missing folder reports why and keeps the choice`() {
        MimirMusicLibrary.rescan(context)
        assertEquals(MusicLibraryState(), MimirMusicLibrary.state.value)

        val gone = File(Files.createTempDirectory("mimir-music-gone").toFile().also { roots += it }, "not-there")
        MimirMusicLibrary.setTree(context, "file://" + gone.toURI().path.trimEnd('/'))
        val failed = awaitScan()
        assertEquals(MusicLibraryError.UNREADABLE, failed.error)
        assertEquals("the choice stays so Rescan can try again", "file://" + gone.toURI().path.trimEnd('/'), failed.treeUri)
        assertEquals(0, failed.trackCount)
    }

    @Test
    fun `a system-picker tree is stored as it is and a provider that cannot list it reports unreadable`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fmusic"
        MimirMusicLibrary.setTree(context, tree)
        val state = awaitScan()

        assertEquals(tree, state.treeUri)
        assertEquals(tree, AudioPreferences.customMusicTreeUri.value)
        assertEquals("no provider answers on the JVM, which is a folder that cannot be listed", MusicLibraryError.UNREADABLE, state.error)
    }

    private fun awaitCatalog(count: Int) = runBlocking {
        withTimeout(15_000) { MimirMusicLibrary.catalog.first { it.tracks.size == count && !it.reading } }
    }

    @Test
    fun `an artist's folder is plain music through the real scan, and a platform's folder is still the platform's`() {
        // The review of #250 (P1): every one-word folder read as a platform's, so Queen, ABBA and U2 were left out of general playback and Shuffle never played them.
        MimirMusicLibrary.load(context, AppMusicPlatformFolders)
        val folder = Files.createTempDirectory("mimir-music-artists").toFile().also { roots += it }
        File(folder, "loose.mp3").writeText("x")
        File(folder, "Queen/A Night at the Opera").mkdirs()
        File(folder, "Queen/A Night at the Opera/01 Death on Two Legs.mp3").writeText("x")
        File(folder, "ABBA").mkdirs()
        File(folder, "ABBA/Dancing Queen.mp3").writeText("x")
        File(folder, "U2").mkdirs()
        File(folder, "U2/One.mp3").writeText("x")
        File(folder, "Jay-Z").mkdirs()
        File(folder, "Jay-Z/99 Problems.mp3").writeText("x")
        File(folder, "snes").mkdirs()
        File(folder, "snes/zelda.flac").writeText("x")
        File(folder, "PS2").mkdirs()
        File(folder, "PS2/ico.m4a").writeText("x")

        MimirMusicLibrary.setTree(context, "file://" + folder.toURI().path.trimEnd('/'))
        awaitScan()
        val folders = awaitCatalog(7).tracks.associate { it.fileName to it.folder }

        assertEquals("", folders["01 Death on Two Legs.mp3"])
        assertEquals("", folders["Dancing Queen.mp3"])
        assertEquals("", folders["One.mp3"])
        assertEquals("", folders["99 Problems.mp3"])
        assertEquals("", folders["loose.mp3"])
        assertEquals("snes", folders["zelda.flac"])
        assertEquals("ps2", folders["ico.m4a"])
    }

    @Test
    fun `an index an earlier build wrote loads with an artist's folder as plain music and the tags it held, without a rescan`() {
        // The Thor test device already has an index written when every one-word folder was a platform's: after the update nothing rescans, and the labels in that file must not be believed.
        val folder = Files.createTempDirectory("mimir-music-old-labels").toFile().also { roots += it }
        val tracks = listOf(
            Triple("queen", "Queen/Death on Two Legs.mp3", "Death on Two Legs"),
            Triple("abba", "ABBA/Dancing Queen.mp3", "Dancing Queen"),
            Triple("snes", "snes/zelda.flac", "Overworld"),
            Triple("", "loose.mp3", "Loose"),
        )
        tracks.forEach { (_, path, _) -> File(folder, path).also { it.parentFile.mkdirs() }.writeText("x") }
        val tree = "file://" + folder.toURI().path.trimEnd('/')
        fun escaped(value: String) = value.replace("\\", "\\\\")
        val lines = tracks.joinToString("") { (label, path, title) ->
            val file = File(folder, path)
            listOf(label, file.name, escaped(file.absolutePath), "1", "1000", "r", title, "Artist", "Album", "", "1", "1", "1000", "0", "0").joinToString("\t") + "\n"
        }
        File(context.filesDir, "music_library_index.txt").writeText("MIMIRMUSIC3\n1700000000000\n${escaped(tree)}\n$lines")
        AudioPreferences.setCustomMusicTreeUri(context, tree)

        MimirMusicLibrary.load(context, AppMusicPlatformFolders)
        val loaded = awaitCatalog(4).tracks.associateBy { it.fileName }

        assertEquals("Queen is plain music, not a platform", "", loaded.getValue("Death on Two Legs.mp3").folder)
        assertEquals("", loaded.getValue("Dancing Queen.mp3").folder)
        assertEquals("", loaded.getValue("loose.mp3").folder)
        assertEquals("a platform's folder is still the platform's", "snes", loaded.getValue("zelda.flac").folder)
        assertEquals("the tags the file held are kept, none read again", "Death on Two Legs", loaded.getValue("Death on Two Legs.mp3").title)
        assertEquals("Artist", loaded.getValue("Dancing Queen.mp3").artist)
    }

    @Test
    fun `a pick shows in the view, All music clears it, and a rescan that loses its track drops it`() {
        val folder = Files.createTempDirectory("mimir-music-pick").toFile().also { roots += it }
        File(folder, "a.mp3").writeText("x")
        File(folder, "b.mp3").writeText("x")
        MimirMusicLibrary.setTree(context, "file://" + folder.toURI().path.trimEnd('/'))
        awaitScan()
        val tracks = awaitCatalog(2).tracks
        val picked = tracks.first { it.fileName == "a.mp3" }.id

        assertNull("no pick at the start: All music is the selected row", MimirMusicLibrary.view.value.pickedTrackId)
        MimirMusicLibrary.playFromLibrary(picked)
        assertEquals(picked, MimirMusicLibrary.view.value.pickedTrackId)

        MimirMusicLibrary.clearSelection()
        assertNull("All music clears the pick", MimirMusicLibrary.view.value.pickedTrackId)
        MimirMusicLibrary.clearSelection()
        assertNull("and with none to clear it changes nothing", MimirMusicLibrary.view.value.pickedTrackId)

        MimirMusicLibrary.playFromLibrary(picked)
        assertEquals(picked, MimirMusicLibrary.view.value.pickedTrackId)
        // The picked track is gone after a rescan: the pick goes with it, so the browser does not keep marking an album that is not there.
        File(picked).delete()
        Thread.sleep(5)
        MimirMusicLibrary.rescan(context)
        val after = runBlocking { withTimeout(15_000) { MimirMusicLibrary.view.first { it.trackCount == 1 } } }
        assertNull(after.pickedTrackId)
    }

    @Test
    fun `the index is written whole and no staging file is left beside it`() {
        val folder = musicFolder()
        MimirMusicLibrary.setTree(context, "file://" + folder.toURI().path.trimEnd('/'))
        awaitScan()
        awaitCatalog(4)
        // The tag pass writes at its end too; give it the moment it needs.
        val deadline = System.currentTimeMillis() + 5_000
        fun leftovers() = context.filesDir.list().orEmpty().filter { it.startsWith("music_library_index") && it.endsWith(".tmp") }
        // Quiet for a few polls in a row: a staging file seen once is a write in flight, and one seen between two looks is the tag pass's own final write starting.
        var quietPolls = 0
        while (quietPolls < 6 && System.currentTimeMillis() < deadline) {
            quietPolls = if (leftovers().isEmpty()) quietPolls + 1 else 0
            Thread.sleep(25)
        }
        val left = leftovers()
        assertTrue("staging files left behind: $left", left.isEmpty())
        val text = File(context.filesDir, "music_library_index.txt").readText()
        assertTrue("the third format: $text", text.startsWith("MIMIRMUSIC3\n"))
        assertEquals("one line per track", 4, text.trimEnd('\n').split('\n').drop(3).size)
    }

    @Test
    fun `picking a new folder replaces the old one and its counts`() {
        val first = musicFolder()
        MimirMusicLibrary.setTree(context, "file://" + first.toURI().path.trimEnd('/'))
        awaitScan()

        val second = Files.createTempDirectory("mimir-music-second").toFile().also { roots += it }
        File(second, "only.mp3").writeText("x")
        val uri = "file://" + second.toURI().path.trimEnd('/')
        MimirMusicLibrary.setTree(context, uri)
        val replaced = awaitScan()

        assertEquals(uri, replaced.treeUri)
        assertEquals(1, replaced.trackCount)
    }
}
