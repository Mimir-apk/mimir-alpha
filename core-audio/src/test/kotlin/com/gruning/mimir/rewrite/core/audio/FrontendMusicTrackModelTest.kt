package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * When the song-title chip learns a track started, and what skipping does on a single bed.
 * The engine needs a MediaPlayer; these rules do not, and source pins keep the engine to them.
 */
class FrontendMusicTrackModelTest {
    private val floor = 0.01f

    /** Drives the announcer the way the ramp does: one level per tick, and says which ticks announced. */
    private fun ticks(announcer: FrontendMusicTrackAnnouncer, vararg levels: Float): List<Boolean> =
        levels.map { announcer.levelApplied(it, floor) }

    @Test
    fun `an entry is announced once, when its fade first becomes audible, and not before`() {
        val announcer = FrontendMusicTrackAnnouncer()
        announcer.entryBegan(announce = true)

        assertEquals(
            listOf(false, false, true, false, false, false),
            ticks(announcer, 0f, 0.006f, 0.0133f, 0.2f, 0.5f, 0.5f),
        )
    }

    @Test
    fun `a loop seam never announces, however many passes there are`() {
        val announcer = FrontendMusicTrackAnnouncer()
        announcer.entryBegan(announce = true)
        assertTrue(announcer.levelApplied(0.5f, floor))

        // Every pass of the loop hands over to the prepared deck and the level carries on at the same value: the ramp keeps
        // asking, and nothing ever answers yes, because a seam begins no entry.
        repeat(50) { assertFalse("pass $it", announcer.levelApplied(0.5f, floor)) }
    }

    @Test
    fun `the seam's recovery re-entry stays silent, because the same track restarting is not a new track`() {
        val announcer = FrontendMusicTrackAnnouncer()
        announcer.entryBegan(announce = true)
        announcer.levelApplied(0.5f, floor)

        announcer.entryBegan(announce = false)
        assertEquals(listOf(false, false, false), ticks(announcer, 0f, 0.3f, 0.5f))
    }

    @Test
    fun `every new entry is announced once more, a start from silence, a bed change and a deliberate skip alike`() {
        val announcer = FrontendMusicTrackAnnouncer()
        repeat(3) { entry ->
            announcer.entryBegan(announce = true)
            assertEquals("entry $entry", listOf(false, true, false), ticks(announcer, 0f, 0.4f, 0.4f))
        }
    }

    @Test
    fun `an entry that never becomes audible announces nothing, and the next entry still does`() {
        val announcer = FrontendMusicTrackAnnouncer()
        announcer.entryBegan(announce = true)
        // The policy silenced it before it crossed the floor (a call came in during the entrance).
        assertEquals(listOf(false, false), ticks(announcer, 0.004f, 0f))

        announcer.entryBegan(announce = true)
        assertEquals(listOf(true), ticks(announcer, 0.3f))
    }

    @Test
    fun `a volume target below the audible floor is never announced`() {
        val announcer = FrontendMusicTrackAnnouncer()
        announcer.entryBegan(announce = true)
        assertEquals(listOf(false, false, false), ticks(announcer, 0.002f, 0.005f, 0.0099f))
    }

    @Test
    fun `nothing is announced before any entry began`() {
        assertEquals(listOf(false, false), ticks(FrontendMusicTrackAnnouncer(), 0.5f, 0.5f))
    }

    // ── skip ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a skip restarts only what is audible`() {
        assertTrue(FrontendMusicSkip.canSkip(playing = true, targetVolume = 0.5f))
        assertTrue(FrontendMusicSkip.canSkip(playing = true, targetVolume = 0.0001f))
        assertFalse("music off, in the background or yielded: the target is zero", FrontendMusicSkip.canSkip(playing = true, targetVolume = 0f))
        assertFalse("nothing is sounding yet", FrontendMusicSkip.canSkip(playing = false, targetVolume = 0.5f))
        assertFalse(FrontendMusicSkip.canSkip(playing = false, targetVolume = 0f))
    }

    // ── the engine keeps to the rules ────────────────────────────────────────────────────────────────

    private fun engineSource(): String = File(
        listOf(
            "core-audio/src/main/kotlin/com/gruning/mimir/rewrite/core/audio/MimirFrontendMusic.kt",
            "src/main/kotlin/com/gruning/mimir/rewrite/core/audio/MimirFrontendMusic.kt",
        ).first { File(it).isFile },
    ).readText()

    private fun body(source: String, from: String, to: String): String = source.substringAfter(from).substringBefore(to)

    @Test
    fun `no deck, seam or now-playing assignment announces a track`() {
        val source = engineSource()
        val sound = body(source, "private fun soundDeck(", "private fun discardDeck(")
        val seam = body(source, "private fun onDeckCompleted(", "private fun chainNextDeck(")
        val assignment = body(source, "// The Now playing row:", "Next track. See")
        listOf("soundDeck" to sound, "the seam" to seam, "the now-playing assignment" to assignment).forEach { (what, text) ->
            assertFalse("$what must not emit trackStarted", text.contains("trackStarted"))
            assertFalse("$what must not begin an entry", text.contains("entryBegan"))
            assertFalse("$what must not ask the announcer", text.contains("announcer"))
        }
        // The bed's chip is told from the fade ramp only. A folder's tracks are announced by the Media3 player, whose event the engine
        // relays from one handler: two emission sites in all, and neither is a deck, a seam or the now-playing assignment.
        assertEquals("the ramp and the folder player's relay", 2, Regex("""trackStarted\.tryEmit\(""").findAll(source).count())
        val relay = body(source, "override fun onTrack(", "override fun onFailed(")
        assertTrue(relay.contains("if (announce) trackStarted.tryEmit(track)"))
        val ramp = body(source, "private fun startFade(", "private companion object")
        assertTrue(ramp.contains("announcer.levelApplied(appliedVolume, AudibleFloor)"))
    }

    @Test
    fun `only a start from silence begins an entry, and the seam's failure arm does it unannounced`() {
        val source = engineSource()
        assertEquals("one site begins an entry", 1, Regex("""announcer\.entryBegan\(""").findAll(source).count())
        val start = body(source, "private fun applyTargetVolume(", "private fun createDeck(")
        assertTrue(start.contains("announcer.entryBegan(announce)"))
        assertTrue("and it sits in the start-from-silence branch", start.indexOf("if (target > 0f && !playing)") in 0 until start.indexOf("announcer.entryBegan(announce)"))
        val seam = body(source, "private fun onDeckCompleted(", "private fun chainNextDeck(")
        assertTrue(seam.contains("applyTargetVolume(currentTarget, announce = false)"))
    }

    @Test
    fun `a skip fades out over the existing fade, then restarts the same bed, and never goes through the bed flow`() {
        val source = engineSource()
        val skip = body(source, "private fun skip(", "/**\n     * Swap which piece plays")
        assertTrue(skip.contains("FrontendMusicSkip.canSkip(playing = playing, targetVolume = currentTarget)"))
        assertTrue("the current ramp is superseded", skip.contains("fadeGeneration += 1L"))
        assertTrue(skip.contains("to = 0f") && skip.contains("durationMs = FadeOutMs") && skip.contains("restartAfter = true"))
        assertFalse("setBed with the same value is a no-op, so a skip must not use it", skip.contains("setBed") || skip.contains("bed.value"))

        val ramp = body(source, "private fun startFade(", "private companion object")
        val release = body(ramp, "Log.d(Tag, \"release decks", "} else {")
        assertTrue(
            "the restart comes after both decks are released",
            ramp.indexOf("discardDeck(current)") in 0 until ramp.indexOf("applyTargetVolume(currentTarget)"),
        )
        assertTrue(
            "through the ordinary start, so the same three-second entrance",
            release.contains("if (restartAfter) {") && release.contains("applyTargetVolume(currentTarget)"),
        )
        // A skip restarts at once, as a bed swap does, so it does not also schedule the two re-samples that exist for a track
        // that is GONE (they would land inside the new entrance). A skip that started nothing still posts them.
        val settle = body(ramp, "val resampleSoon = {", "} else {")
        assertTrue(settle.contains("if (!restartAfter) resampleSoon()"))
        assertTrue(settle.contains("if (!playing) resampleSoon()"))
        assertEquals("the re-samples are posted from one place", 1, Regex("""MimirMediaBed\.refresh\(\)\s*\}""").findAll(ramp).count())
        assertTrue(source.contains("const val FadeInMs = 3_000L") && source.contains("const val FadeOutMs = 260L"))
    }

    @Test
    fun `a folder is handed to the Media3 player and the engine builds no MediaPlayer for it`() {
        val source = engineSource()
        assertTrue(source.contains("YourMusicPlayback.setPlaylist(next.tree, next.tracks, MimirMusicLibrary::labelOf, next.start?.trackId)"))
        assertTrue(source.contains("YourMusicPlayback.setTarget(target)"))
        assertTrue(source.contains("YourMusicPlayback.skip(forward)"))
        assertFalse("no folder file is opened by the platform player any more", source.contains("setDataSource"))
        val create = body(source, "private fun createDeck(", "private fun failFolder(")
        assertTrue("only the theme bed has a deck", create.contains("plan as? FrontendMusicPlan.Bed"))
    }

    @Test
    fun `the music switch is in the policy flow the engine combines`() {
        val source = engineSource()
        assertTrue(source.contains("AudioPreferences.musicEnabled,"))
        assertTrue(source.contains("musicEnabled = musicEnabled"))
    }
}
