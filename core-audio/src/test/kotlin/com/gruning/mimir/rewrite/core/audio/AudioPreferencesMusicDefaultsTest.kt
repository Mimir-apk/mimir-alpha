package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings > Music contract : what a player who never opens the card gets.
 *
 * The preferences object holds its defaults until `load(context)` runs, and nothing in this module's JVM
 * tests loads it, so reading it unloaded IS reading the defaults. The persistence round trip needs a
 * Context and lives with the app's Robolectric tests.
 */
class AudioPreferencesMusicDefaultsTest {
    @Test
    fun `a player who never opens Music keeps the shipped bed and hears the launch sound as before`() {
        assertTrue(AudioPreferences.musicEnabled.value)
        assertEquals(MimirMusicSource.MIMIR, AudioPreferences.musicSource.value)
        assertEquals(MimirMusicOrder.SHUFFLE, AudioPreferences.musicOrder.value)
        assertTrue(AudioPreferences.songTitlesEnabled.value)
        assertNull(AudioPreferences.customMusicTreeUri.value)
        assertTrue(AudioPreferences.launchSoundEnabled.value)
        // 1.0 is the MediaPlayer level the intro played at before this was a setting.
        assertEquals(1f, AudioPreferences.launchSoundVolume.value, 0f)
    }

    @Test
    fun `the equalizer is off and flat until the player turns it on`() {
        assertEquals(EqualizerSettings(), AudioPreferences.equalizer.value)
        assertFalse(AudioPreferences.equalizer.value.enabled)
    }

    @Test
    fun `game sounds are launch-only by default`() {
        assertFalse(AudioPreferences.gameMusicOnHighlight.value)
    }

    @Test
    fun `other music keeps playing in games unless the player turns it off`() {
        assertTrue(AudioPreferences.keepMusicInGames.value)
    }

    @Test
    fun `the music volume is the existing ambient volume and no second one exists`() {
        assertEquals(0.5f, AudioPreferences.ambientVolume.value, 0f)
        val musicVolumeProperties = AudioPreferences::class.java.declaredFields
            .map { it.name }
            .filter { it.contains("musicVolume", ignoreCase = true) }
        assertEquals(emptyList<String>(), musicVolumeProperties)
    }

    @Test
    fun `an unknown stored source or order falls back to the default instead of failing`() {
        assertEquals(MimirMusicSource.CUSTOM, AudioPreferences.parseMusicSource("CUSTOM"))
        assertEquals(MimirMusicSource.MIMIR, AudioPreferences.parseMusicSource("MIMIR"))
        assertEquals(MimirMusicSource.MIMIR, AudioPreferences.parseMusicSource(null))
        assertEquals(MimirMusicSource.MIMIR, AudioPreferences.parseMusicSource("playlist"))
        assertEquals(MimirMusicOrder.IN_ORDER, AudioPreferences.parseMusicOrder("IN_ORDER"))
        assertEquals(MimirMusicOrder.SHUFFLE, AudioPreferences.parseMusicOrder(null))
        assertEquals(MimirMusicOrder.SHUFFLE, AudioPreferences.parseMusicOrder(""))
    }

    @Test
    fun `an empty library has no folder, no tracks, no scan and no error`() {
        val empty = MusicLibraryState()
        assertNull(empty.treeUri)
        assertEquals(0, empty.trackCount)
        assertNull(empty.lastScanMillis)
        assertFalse(empty.scanning)
        assertNull(empty.error)
    }

    @Test
    fun `each bed has a title the player can read`() {
        assertEquals("Mimir Ambient", MimirFrontendBed.Ambient.title)
        assertEquals("Fallen Hero", MimirFrontendBed.FallenHero.title)
    }
}
