package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MediaOutput
import com.gruning.mimir.rewrite.core.audio.MediaOutputKind
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.OutputFormat
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.ThemeBedState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor header's information block as pure strings: where media sound goes, what is playing from any app (and the one honest line
 * without notification access), which Equalizer is active and whether the global engine has the mix, and the standing footnote about the
 * device's own equalizer. None of it names an emulator or a vendor app, and none shows a format for another app: Mimir's own track's format
 * appears directly under Mimir's own "Playing/Paused" entry and only while that entry is shown.
 */
class EqualizerEditorInfoTest {
    private val rock = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK)
    private val off = EqualizerSettings(enabled = false)

    private fun working() = SystemEqualizerState(wanted = true, status = SystemEqStatus.ON)
    private fun taken() = SystemEqualizerState(wanted = true, status = SystemEqStatus.NO_CONTROL)
    private fun refused() = SystemEqualizerState(wanted = true, status = SystemEqStatus.UNAVAILABLE)

    private fun other(title: String = "Hotel California", app: String? = "Spotify", playing: Boolean = true, authored: Boolean = true) = OtherPlayerSnapshot(
        title = title, artist = null, appLabel = app, artwork = null, isPlaying = playing, titleIsAuthored = authored,
    )

    private fun yours(title: String? = "Tiny 079", playing: Boolean = true, format: TrackFormat? = null) =
        YourMusicState(title = title, isPlaying = playing, format = format)

    private val mp3 = TrackFormat(
        codec = "MP3", lossless = false, bitrateKbps = 320, sampleRateHz = 44_100,
        output = OutputFormat(bitDepth = 16, sampleRateHz = 48_000),
    )

    /** What the format lines of [mp3] say; a header line like these under another app's name would read as that app's. */
    private val mp3Lines = listOf("MP3 · 320 kbps · 44.1 kHz", "Output: 16-bit · 48 kHz")

    private fun texts(lines: List<EqualizerInfoLine>) = lines.map { it.text }

    // ---- where the sound goes ----

    @Test
    fun `the output device line names the place, and the Bluetooth device by its own name`() {
        assertEquals("Plays on: Speaker", equalizerOutputDeviceLine(MediaOutput(MediaOutputKind.SPEAKER)))
        assertEquals("Plays on: Wired headphones", equalizerOutputDeviceLine(MediaOutput(MediaOutputKind.WIRED_HEADPHONES)))
        assertEquals("Plays on: USB audio", equalizerOutputDeviceLine(MediaOutput(MediaOutputKind.USB_AUDIO)))
        assertEquals("Plays on: Bluetooth · WH-1000XM5", equalizerOutputDeviceLine(MediaOutput(MediaOutputKind.BLUETOOTH, "WH-1000XM5")))
        assertEquals("nothing is guessed when the platform has not said", null, equalizerOutputDeviceLine(null))
    }

    // ---- what is playing ----

    @Test
    fun `Mimir's own music playing is named with its title and the word Playing`() {
        val lines = equalizerNowPlayingLines(MusicControlState(yours = yours(), otherAccess = true))
        assertEquals(listOf("Playing: Mimir · Tiny 079"), texts(lines))
    }

    @Test
    fun `Mimir's own format lines sit directly under its Playing entry, then the next entry`() {
        val own = MusicControlState(yours = yours(format = mp3), otherAccess = true)
        assertEquals(listOf("Playing: Mimir · Tiny 079") + mp3Lines, texts(equalizerNowPlayingLines(own)))
        assertEquals("the format lines are the ordinary tone, like the entry's facts", EqualizerInfoTone.NORMAL, equalizerNowPlayingLines(own).last().tone)
        // Both playing: Mimir's entry, its format, then the other app's entry with none.
        val both = own.copy(other = other(playing = true))
        assertEquals(listOf("Playing: Mimir · Tiny 079") + mp3Lines + "Playing: Spotify · Hotel California", texts(equalizerNowPlayingLines(both)))
        // A track whose format the player has not reported shows none, rather than a guess.
        assertEquals(listOf("Playing: Mimir · Tiny 079"), texts(equalizerNowPlayingLines(MusicControlState(yours = yours(), otherAccess = true))))
    }

    // ---- the theme bed (the default source) is Mimir's music too ----

    private fun bed(title: String = "Overworld Theme", playing: Boolean = true) = ThemeBedState(title = title, isPlaying = playing)

    @Test
    fun `the theme bed sounding is named Mimir's music with its title, and the header never says nothing is playing over it`() {
        val lines = texts(equalizerNowPlayingLines(MusicControlState(bed = bed(), otherAccess = true)))
        assertEquals(listOf("Playing: Mimir’s music · Overworld Theme"), lines)
        assertFalse(lines.contains(EqualizerNothingPlayingLine))
        assertEquals("Mimir’s music", EqualizerThemeAppLabel)
        // The bed has no track format to show: nothing here is a format.
        lines.forEach { line -> assertFalse("\"$line\" states a format", listOf("kbps", "kHz", "-bit", "Output:").any { line.contains(it) }) }
        // And the block as the editor draws it carries the line between where the sound goes and which Equalizer is active.
        assertEquals(
            listOf("Plays on: Speaker", "Playing: Mimir’s music · Overworld Theme"),
            texts(equalizerInfoLines(MediaOutput(MediaOutputKind.SPEAKER), MusicControlState(bed = bed(), otherAccess = true), rock, working())).take(2),
        )
    }

    @Test
    fun `a theme bed that is held silent is not named, and the honest line about what remains stands`() {
        // Held (another app, a call, the music hours): the deck is gone, so nothing of Mimir's sounds, and nothing else does either.
        assertEquals(listOf(EqualizerNothingPlayingLine), texts(equalizerNowPlayingLines(MusicControlState(bed = bed(playing = false), otherAccess = true))))
        assertEquals(listOf(EqualizerNoAccessLine), texts(equalizerNowPlayingLines(MusicControlState(bed = bed(playing = false), otherAccess = false))))
        // Held for Spotify, which plays: only Spotify is named.
        assertEquals(
            listOf("Playing: Spotify · Hotel California"),
            texts(equalizerNowPlayingLines(MusicControlState(bed = bed(playing = false), other = other(), otherAccess = true))),
        )
    }

    @Test
    fun `the theme bed and another app both sounding are both named, and the bed with no access is followed by the one honest line`() {
        assertEquals(
            listOf("Playing: Mimir’s music · Overworld Theme", "Playing: Spotify · Hotel California"),
            texts(equalizerNowPlayingLines(MusicControlState(bed = bed(), other = other(), otherAccess = true))),
        )
        assertEquals(
            listOf("Playing: Mimir’s music · Overworld Theme", EqualizerNoAccessLine),
            texts(equalizerNowPlayingLines(MusicControlState(bed = bed(), otherAccess = false))),
        )
        // A title the bed does not carry is left out, never replaced with a guess.
        assertEquals(listOf("Playing: Mimir’s music"), texts(equalizerNowPlayingLines(MusicControlState(bed = bed(title = " "), otherAccess = true))))
    }

    @Test
    fun `Your music paused for another app keeps its format held, and none of it is shown under that app`() {
        // Spotify took focus: Your music stays present (paused for another app) and its MP3's format stays on the state until the playlist clears.
        val spotifyTookFocus = MusicControlState(
            yours = yours(playing = false, format = mp3).copy(pausedReason = MusicPauseReason.OTHER_APP),
            other = other(playing = true),
            otherAccess = true,
            lastPlayed = MusicSource.OTHER_PLAYER,
        )
        assertEquals(listOf("Playing: Spotify · Hotel California"), texts(equalizerNowPlayingLines(spotifyTookFocus)))
        // The whole header block, as the editor draws it: no format line anywhere near the other app's name.
        val block = texts(equalizerInfoLines(MediaOutput(MediaOutputKind.SPEAKER), spotifyTookFocus, rock, working()))
        mp3Lines.forEach { assertFalse("\"$it\" is Mimir's track's, not Spotify's", block.contains(it)) }
        block.forEach { line ->
            assertFalse("\"$line\" states a format", listOf("kbps", "kHz", "-bit", "float", "Output:").any { line.contains(it) })
        }
    }

    @Test
    fun `with none playing the format follows the last-played Mimir entry only, and a paused Mimir entry keeps it`() {
        val none = MusicControlState(
            yours = yours(playing = false, format = mp3).copy(pausedReason = MusicPauseReason.USER),
            other = other(playing = false),
            otherAccess = true,
            lastPlayed = MusicSource.YOUR_MUSIC,
        )
        assertEquals(listOf("Paused: Mimir · Tiny 079") + mp3Lines, texts(equalizerNowPlayingLines(none)))
        // The other app sounded last: its Paused entry is shown alone, and Mimir's format with it is not.
        assertEquals(listOf("Paused: Spotify · Hotel California"), texts(equalizerNowPlayingLines(none.copy(lastPlayed = MusicSource.OTHER_PLAYER))))
        // Without notification access the one honest line follows Mimir's entry and its format, not the other way round.
        assertEquals(
            listOf("Playing: Mimir · Tiny 079") + mp3Lines + EqualizerNoAccessLine,
            texts(equalizerNowPlayingLines(MusicControlState(yours = yours(format = mp3), otherAccess = false))),
        )
    }

    @Test
    fun `another app's player is named by app and title, playing or paused, from the media-session reader`() {
        assertEquals(
            listOf("Playing: Spotify · Hotel California"),
            texts(equalizerNowPlayingLines(MusicControlState(other = other(), otherAccess = true))),
        )
        assertEquals(
            listOf("Paused: Spotify · Hotel California"),
            texts(equalizerNowPlayingLines(MusicControlState(other = other(playing = false), otherAccess = true))),
        )
    }

    @Test
    fun `a session that names no app is Another app, and one that names no title leaves the title out rather than guess`() {
        assertEquals(listOf("Playing: Another app · Song"), texts(equalizerNowPlayingLines(MusicControlState(other = other(title = "Song", app = null), otherAccess = true))))
        assertEquals(listOf("Playing: Spotify"), texts(equalizerNowPlayingLines(MusicControlState(other = other(title = "Media unavailable", authored = false), otherAccess = true))))
        assertEquals(listOf("Playing: Mimir"), texts(equalizerNowPlayingLines(MusicControlState(yours = yours(title = null), otherAccess = true))))
    }

    @Test
    fun `the player that sounds is the one named, both when both sound, and the one that sounded last when none does`() {
        val both = MusicControlState(yours = yours(playing = true), other = other(playing = true), otherAccess = true)
        assertEquals(listOf("Playing: Mimir · Tiny 079", "Playing: Spotify · Hotel California"), texts(equalizerNowPlayingLines(both)))

        val onlyOther = both.copy(yours = yours(playing = false))
        assertEquals(listOf("Playing: Spotify · Hotel California"), texts(equalizerNowPlayingLines(onlyOther)))

        val noneYoursLast = both.copy(yours = yours(playing = false), other = other(playing = false), lastPlayed = MusicSource.YOUR_MUSIC)
        assertEquals(listOf("Paused: Mimir · Tiny 079"), texts(equalizerNowPlayingLines(noneYoursLast)))
        val noneOtherLast = noneYoursLast.copy(lastPlayed = MusicSource.OTHER_PLAYER)
        assertEquals(listOf("Paused: Spotify · Hotel California"), texts(equalizerNowPlayingLines(noneOtherLast)))
    }

    @Test
    fun `with access and nothing playing it says so`() {
        assertEquals(listOf(EqualizerNothingPlayingLine), texts(equalizerNowPlayingLines(MusicControlState(otherAccess = true))))
        assertEquals("Nothing is playing.", EqualizerNothingPlayingLine)
    }

    @Test
    fun `without notification access one honest line says other apps cannot be seen, after Mimir's own music when it is there`() {
        assertEquals(listOf(EqualizerNoAccessLine), texts(equalizerNowPlayingLines(MusicControlState(otherAccess = false))))
        assertEquals(
            listOf("Playing: Mimir · Tiny 079", EqualizerNoAccessLine),
            texts(equalizerNowPlayingLines(MusicControlState(yours = yours(), otherAccess = false))),
        )
        assertEquals(
            "Other apps’ playback isn’t shown: allow notification access under Settings › Music › Behaviour › Show other players.",
            EqualizerNoAccessLine,
        )
    }

    // ---- which Equalizer is active ----

    @Test
    fun `off says off, and nothing about reach`() {
        assertEquals(listOf("Equalizer: Off"), texts(equalizerStatusLines(off, SystemEqualizerState())))
        // A stale engine state under an off switch changes nothing.
        assertEquals(listOf("Equalizer: Off"), texts(equalizerStatusLines(off, taken())))
    }

    @Test
    fun `on with the global engine holding the mix says it shapes all sound, with the preset`() {
        assertEquals(listOf("Equalizer: On · Rock · all sound on this device"), texts(equalizerStatusLines(rock, working())))
        assertEquals(EqualizerInfoTone.NORMAL, equalizerStatusLines(rock, working()).single().tone)
    }

    @Test
    fun `on before the engine has answered says it is starting and claims no reach`() {
        assertEquals(listOf("Equalizer: On · Rock · starting"), texts(equalizerStatusLines(rock, SystemEqualizerState())))
    }

    @Test
    fun `on while another app controls the engine says only music on this device is shaped, and why it is not active`() {
        val lines = equalizerStatusLines(rock, taken())
        assertEquals(
            listOf("Equalizer: On · Rock · music on this device only right now", "Not active: another app controls the system equalizer."),
            texts(lines),
        )
        assertEquals("the reason is brighter than the facts", EqualizerInfoTone.ATTENTION, lines.last().tone)
    }

    @Test
    fun `on while Android refused the engine says not active, why, and the same fallback`() {
        assertEquals(
            listOf("Equalizer: On · Rock · music on this device only right now", "Not active: Android refused the system equalizer."),
            texts(equalizerStatusLines(rock, refused())),
        )
    }

    @Test
    fun `the preset is whatever the player chose, Custom included`() {
        val custom = rock.withBand(3, 4f)
        assertEquals("Equalizer: On · Custom · all sound on this device", equalizerStatusLines(custom, working()).single().text)
    }

    // ---- the block as the header reads it ----

    @Test
    fun `the block reads device, playing, equalizer, and the footnote last`() {
        val lines = equalizerInfoLines(
            output = MediaOutput(MediaOutputKind.BLUETOOTH, "Buds"),
            music = MusicControlState(other = other(), otherAccess = true),
            settings = rock,
            systemEq = working(),
        )
        assertEquals(
            listOf(
                "Plays on: Bluetooth · Buds",
                "Playing: Spotify · Hotel California",
                "Equalizer: On · Rock · all sound on this device",
                "The device’s own equalizer, if it has one, applies as well.",
            ),
            texts(lines),
        )
        assertEquals(EqualizerInfoTone.QUIET, lines.last().tone)
    }

    @Test
    fun `no format is invented for another app, and the footnote is the shared plain line`() {
        val lines = texts(
            equalizerInfoLines(MediaOutput(MediaOutputKind.SPEAKER), MusicControlState(other = other(), otherAccess = true), rock, working()),
        )
        lines.forEach { line ->
            assertFalse("\"$line\" states a format", listOf("kbps", "kHz", "-bit", "float", "Output:").any { line.contains(it) })
        }
        assertEquals("The device’s own equalizer, if it has one, applies as well.", EqualizerVendorLine)
    }

    @Test
    fun `nothing the header says names an emulator or a vendor app`() {
        val banned = Regex("\\b(azahar|melonds|retroarch|odin|thor|system eq)\\b", RegexOption.IGNORE_CASE)
        val everything = buildList {
            for (status in SystemEqStatus.entries) for (settings in listOf(rock, off)) for (access in listOf(true, false)) {
                addAll(texts(equalizerInfoLines(MediaOutput(MediaOutputKind.SPEAKER), MusicControlState(yours = yours(), otherAccess = access), settings, SystemEqualizerState(wanted = true, status = status))))
            }
            add(EqualizerNoAccessLine)
            add(EqualizerVendorLine)
            add(EqualizerNothingPlayingLine)
        }
        everything.forEach { line -> assertFalse("\"$line\" names an emulator, a vendor app or the old switch", banned.containsMatchIn(line)) }
        assertTrue(everything.isNotEmpty())
    }
}
