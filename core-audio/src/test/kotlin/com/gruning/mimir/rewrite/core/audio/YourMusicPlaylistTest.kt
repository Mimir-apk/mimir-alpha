package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Your music's Media3 playlist and gain, off-device. The player itself (gapless hand-over, the notification, focus) is
 * device-only; what is provable here is which tracks are given to it in which order and by which address, and what level and
 * ramp the policy's target becomes.
 */
class YourMusicPlaylistTest {
    private class FakeUris : YourMusicUris {
        override fun forPath(path: String) = "file://$path"

        override fun forDocument(tree: String, documentId: String) = "$tree/document/$documentId"
    }

    private val uris = FakeUris()

    // --- Playlist mapping -------------------------------------------------------------------------------------------------

    @Test
    fun `a picked tree's tracks become document addresses under that tree, in index order`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val tracks = listOf(
            MusicTrack(folder = "", name = "Alpha.mp3", id = "primary:Music/Alpha.mp3"),
            MusicTrack(folder = "", name = "Beta.ogg", id = "primary:Music/Beta.ogg"),
        )

        val entries = yourMusicEntries(tree, tracks, uris)

        assertEquals(listOf("primary:Music/Alpha.mp3", "primary:Music/Beta.ogg"), entries.map { it.mediaId })
        assertEquals(
            listOf("$tree/document/primary:Music/Alpha.mp3", "$tree/document/primary:Music/Beta.ogg"),
            entries.map { it.uri },
        )
    }

    @Test
    fun `a plain folder's tracks are read by their file path`() {
        val entries = yourMusicEntries(
            tree = "file:///storage/emulated/0/Music",
            tracks = listOf(MusicTrack(folder = "", name = "Song.flac", id = "/storage/emulated/0/Music/Song.flac")),
            uris = uris,
        )

        assertEquals("file:///storage/emulated/0/Music/Song.flac", entries.single().uri)
        assertEquals("/storage/emulated/0/Music/Song.flac", entries.single().mediaId)
    }

    @Test
    fun `the title is the file name without its extension, as the chip and the notification show it`() {
        val entries = yourMusicEntries(
            tree = "content://x/tree/y",
            tracks = listOf(
                MusicTrack("", "The Well Remembers.ogg", "a"),
                MusicTrack("", "no extension", "b"),
                MusicTrack("", ".hidden", "c"),
            ),
            uris = uris,
        )

        assertEquals(listOf("The Well Remembers", "no extension", ".hidden"), entries.map { it.title })
    }

    @Test
    fun `an empty folder is an empty playlist`() {
        assertTrue(yourMusicEntries("content://x/tree/y", emptyList(), uris).isEmpty())
    }

    @Test
    fun `the same tracks map to equal entries so a rescan that found nothing new restarts nothing`() {
        val tracks = listOf(MusicTrack("", "A.mp3", "a"), MusicTrack("", "B.mp3", "b"))
        assertEquals(yourMusicEntries("content://x/tree/y", tracks, uris), yourMusicEntries("content://x/tree/y", tracks, uris))
    }

    @Test
    fun `stepping wraps both ways and an empty list stays at zero`() {
        assertEquals(1, yourMusicStep(0, 3, forward = true))
        assertEquals(0, yourMusicStep(2, 3, forward = true))
        assertEquals(2, yourMusicStep(0, 3, forward = false))
        assertEquals(0, yourMusicStep(0, 1, forward = true))
        assertEquals(0, yourMusicStep(0, 0, forward = false))
    }

    // --- Gain applied to the player ---------------------------------------------------------------------------------------

    @Test
    fun `the player volume is the policy's compensated gain, held inside zero to one`() {
        // M = 0.25 perceived under S = 0.5 stream: gain 0.5 reaches the player as its volume.
        val gain = FrontendMusicPolicy.compensatedGain(perceived = 0.25f, streamLevel = 0.5f)
        assertEquals(0.5f, YourMusicGain.playerVolume(gain), 0f)
        // Game & media below the music level: the gain is capped at full, never above.
        assertEquals(1f, YourMusicGain.playerVolume(FrontendMusicPolicy.compensatedGain(0.8f, 0.4f)), 0f)
        assertEquals(0f, YourMusicGain.playerVolume(-0.2f), 0f)
        assertEquals(1f, YourMusicGain.playerVolume(3f), 0f)
    }

    @Test
    fun `a target above zero starts a silent player, below zero sounding pauses it, and silence stays silent`() {
        assertEquals(YourMusicTransport.START, YourMusicGain.transport(target = 0.4f, sounding = false))
        assertEquals(YourMusicTransport.RETARGET, YourMusicGain.transport(target = 0.4f, sounding = true))
        assertEquals(YourMusicTransport.PAUSE, YourMusicGain.transport(target = 0f, sounding = true))
        assertEquals(YourMusicTransport.NONE, YourMusicGain.transport(target = 0f, sounding = false))
    }

    @Test
    fun `a ramp is exactly its endpoints, linear between, and never overshoots`() {
        val steps = YourMusicGain.steps(YourMusicGain.EntranceMs)
        assertEquals(75, steps)
        assertEquals(0.4f / 75f, YourMusicGain.rampValue(0f, 0.4f, 1, steps), 1e-6f)
        assertEquals(0.2f, YourMusicGain.rampValue(0f, 0.4f, steps / 2, steps), 0.01f)
        assertEquals(0.4f, YourMusicGain.rampValue(0f, 0.4f, steps, steps), 0f)
        assertEquals(0.4f, YourMusicGain.rampValue(0f, 0.4f, steps + 5, steps), 0f)
        // Falling to silence ends on exactly zero, which is what the pause waits for.
        assertEquals(0f, YourMusicGain.rampValue(0.6f, 0f, YourMusicGain.steps(YourMusicGain.ExitMs), YourMusicGain.steps(YourMusicGain.ExitMs)), 0f)
    }

    @Test
    fun `a fall is prompt, an arrival is three seconds, a resume is short, and a retarget is proportional`() {
        assertEquals(YourMusicGain.ExitMs, YourMusicGain.rampMs(0.5f, 0f, entering = false, resuming = false))
        assertEquals(YourMusicGain.ExitMs, YourMusicGain.rampMs(0.5f, 0.2f, entering = true, resuming = false))
        assertEquals(YourMusicGain.EntranceMs, YourMusicGain.rampMs(0f, 0.5f, entering = true, resuming = false))
        assertEquals(YourMusicGain.ResumeMs, YourMusicGain.rampMs(0f, 0.5f, entering = false, resuming = true))
        // A small hardware step is never shorter than the floor, a full climb never longer than the entrance.
        assertEquals(YourMusicGain.MinRetargetMs, YourMusicGain.rampMs(0.50f, 0.51f, entering = false, resuming = false))
        assertEquals(1_500L, YourMusicGain.rampMs(0.25f, 0.75f, entering = false, resuming = false))
        assertEquals(YourMusicGain.EntranceMs, YourMusicGain.rampMs(0f, 1f, entering = false, resuming = false))
    }

    // --- A track that will not play ---------------------------------------------------------------------------------------

    @Test
    fun `a failing track moves on until several in a row fail, then the folder is given up with its reason`() {
        assertEquals(YourMusicFailure.SkipToNext, yourMusicFailure(1, CustomMusicFallback.UNREADABLE, maxAttempts = 5))
        assertEquals(YourMusicFailure.SkipToNext, yourMusicFailure(4, CustomMusicFallback.GRANT_LOST, maxAttempts = 5))
        assertEquals(
            YourMusicFailure.GiveUp(CustomMusicFallback.GRANT_LOST),
            yourMusicFailure(5, CustomMusicFallback.GRANT_LOST, maxAttempts = 5),
        )
        assertEquals(
            YourMusicFailure.GiveUp(CustomMusicFallback.UNREADABLE),
            yourMusicFailure(9, CustomMusicFallback.UNREADABLE, maxAttempts = 5),
        )
    }
}
