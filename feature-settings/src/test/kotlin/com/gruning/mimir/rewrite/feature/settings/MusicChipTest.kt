package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audible playback status: the Music player card's chip is the engine's reading of whether Mimir's music SOUNDS, not the
 * switch. It read "Playing" for the whole time the theme bed waited behind another app's audio and while it was silent (observed on Thor).
 */
class MusicChipTest {
    private fun model(enabled: Boolean, playing: Boolean) = MusicReadModel(
        musicEnabled = enabled,
        playing = playing,
        nowPlaying = null,
        source = MusicSourceValue.MIMIR,
        customSourceAvailable = false,
        order = MusicOrderValue.IN_ORDER,
        volumePercent = 50,
        songTitlesEnabled = true,
        folder = null,
        launchSoundEnabled = true,
        launchSoundVolumePercent = 100,
        gameMusic = GameMusicDownloadReadModel.Unavailable,
        playOnHighlight = false,
    )

    @Test
    fun `the chip says Playing while the music sounds`() {
        assertEquals("Playing", musicPlayerChipText(model(enabled = true, playing = true)))
    }

    @Test
    fun `the chip says Paused when Music is on but held or silent`() {
        assertEquals("Paused", musicPlayerChipText(model(enabled = true, playing = false)))
    }

    @Test
    fun `the chip says Off when the switch is off, whatever the player was doing`() {
        assertEquals("Off", musicPlayerChipText(model(enabled = false, playing = false)))
        assertEquals("Off", musicPlayerChipText(model(enabled = false, playing = true)))
    }

    @Test
    fun `a model that does not know claims nothing`() {
        val bare = MusicReadModel(
            musicEnabled = true,
            nowPlaying = null,
            source = MusicSourceValue.MIMIR,
            customSourceAvailable = false,
            order = MusicOrderValue.IN_ORDER,
            volumePercent = 50,
            songTitlesEnabled = true,
            folder = null,
            launchSoundEnabled = true,
            launchSoundVolumePercent = 100,
            gameMusic = GameMusicDownloadReadModel.Unavailable,
            playOnHighlight = false,
        )
        assertFalse(bare.playing)
        assertEquals("Paused", musicPlayerChipText(bare))
    }

    @Test
    fun `only Playing is gold, and the panel draws the chip from this one function`() {
        val panel = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/MusicPanel.kt").readText()
        val chip = panel.substringAfter("val chip = musicPlayerChipText(music)").substringBefore("}\n        },")
        assertTrue(chip.contains("color = if (chip == MusicChipPlaying) MimirColors.Gold else MimirColors.TextMuted"))
        assertFalse("the switch alone no longer decides", panel.contains("text = if (music.musicEnabled) \"Playing\" else \"Off\""))
    }
}
