package com.gruning.mimir.rewrite.core.audio

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Background-game audio filtering, observed on Thor: a game Mimir keeps running in the background never counts as other music.
 *
 * `AudioPlaybackConfiguration` cannot be built in a host test, so the stream under test is a plain label: "game" is `USAGE_MEDIA` +
 * `CONTENT_TYPE_UNKNOWN` (the measured emulator stream), "youtube" is `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`, "podcast" is `USAGE_MEDIA` +
 * `CONTENT_TYPE_SPEECH`. Several streams of one signature are the same label with a number. Times are `uptimeMillis`-style readings the test owns.
 */
class KeptGameStreamsTest {
    private fun signature(stream: String): Any? = when {
        stream.startsWith("game") -> mimirKeptGameSignature(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN)
        stream.startsWith("arcade") -> mimirKeptGameSignature(AudioAttributes.USAGE_GAME, AudioAttributes.CONTENT_TYPE_UNKNOWN)
        stream.startsWith("youtube") -> mimirKeptGameSignature(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC)
        stream.startsWith("podcast") -> mimirKeptGameSignature(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)
        else -> null
    }

    private fun streams() = MimirKeptGameStreams<String>(
        gameLikeSignature = ::signature,
        isRealMedia = { it.startsWith("youtube") },
    )

    private val grace = MimirKeptGameStreams.ReopenGraceMs

    /** What the courtesy gate says for [present] streams of which [owned] are Mimir's own. */
    private fun blocked(kept: MimirKeptGameStreams<String>, present: List<String>, owned: Int = 0, music: Boolean = true, call: Boolean = false): Boolean =
        mimirMediaBedEnvironmentBlocked(
            mimirMediaBedEnvironmentSample(
                musicActive = music,
                observedPlaybackCount = present.size,
                relevantPlaybackCount = present.size,
                keptGameStreams = kept.discount(present),
                ownedPlaybackCount = owned,
                callInterrupted = call,
            ),
        )

    /** A game launched with [atLaunch] playing and left with [atReturn] playing: the kept game, as the media bed would hold it. */
    private fun keptAfterLaunch(atLaunch: List<String>, atReturn: List<String>, session: Boolean = false, launchAt: Long = 0L, returnAt: Long = 10_000L): MimirKeptGameStreams<String> {
        val kept = streams()
        kept.onLaunchArmed(atLaunch, launchAt)
        kept.onPlayback(atReturn, returnAt - 1L)
        kept.onReturned(atReturn, strangerSessionPlaying = session, nowMs = returnAt)
        return kept
    }

    // ── 1. A game Mimir keeps in the background never counts as other music ──────────────────────────────────────

    @Test
    fun `the signature is the emulator's stream and a game's declared usage, never music, movie or speech`() {
        assertEquals(AudioAttributes.USAGE_MEDIA to AudioAttributes.CONTENT_TYPE_UNKNOWN, mimirKeptGameSignature(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN))
        assertEquals(AudioAttributes.USAGE_GAME to AudioAttributes.CONTENT_TYPE_MUSIC, mimirKeptGameSignature(AudioAttributes.USAGE_GAME, AudioAttributes.CONTENT_TYPE_MUSIC))
        assertNull("a music app names its music", mimirKeptGameSignature(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC))
        assertNull(mimirKeptGameSignature(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MOVIE))
        assertNull("a podcast is not a game", mimirKeptGameSignature(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH))
        assertNull(mimirKeptGameSignature(AudioAttributes.USAGE_ALARM, AudioAttributes.CONTENT_TYPE_UNKNOWN))
    }

    @Test
    fun `the game left running is not other music once Mimir is back, and the gate opens`() {
        val kept = streams()
        kept.onLaunchArmed(emptyList(), 0L)
        kept.onPlayback(listOf("game-1"), 5_000L)
        assertEquals(1, kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = 10_000L))
        assertEquals(1, kept.discount(listOf("game-1")))
        assertFalse("the Thor case: observed=1 relevant=1 owned=0 no longer blocks", blocked(kept, listOf("game-1")))
    }

    @Test
    fun `without the discount the same stream blocks, which is the defect`() {
        assertTrue(blocked(streams(), listOf("game-1")))
    }

    @Test
    fun `nothing is discounted while the game is in front, only once it is not`() {
        val kept = streams()
        kept.onLaunchArmed(emptyList(), 0L)
        assertEquals("the game is in front", 0, kept.discount(listOf("game-1")))
        kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = 10_000L)
        assertEquals("Mimir is back; the game is behind it", 1, kept.discount(listOf("game-1")))
        kept.onLaunchArmed(listOf("game-1"), 20_000L)
        assertEquals("the game is brought to the front again", 0, kept.discount(listOf("game-1")))
        kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = 30_000L)
        assertEquals("and left again: the same game is still the one kept", 1, kept.discount(listOf("game-1")))
    }

    @Test
    fun `a stream that was already playing when the game launched is not the game's`() {
        val kept = streams()
        kept.onLaunchArmed(listOf("game-0"), 0L)
        assertEquals("one game-like stream before, one after: only the excess is the game's", 1, kept.onReturned(listOf("game-0", "game-1"), strangerSessionPlaying = false, nowMs = 10_000L))
        assertEquals(1, kept.discount(listOf("game-0", "game-1")))

        val quiet = streams()
        quiet.onLaunchArmed(listOf("game-0"), 0L)
        assertEquals("the game made no stream of its own", 0, quiet.onReturned(listOf("game-0"), strangerSessionPlaying = false, nowMs = 10_000L))
        assertTrue("so what was playing before still blocks", blocked(quiet, listOf("game-0")))
    }

    @Test
    fun `YouTube Music playing over the game and still playing at the return is not discounted`() {
        val present = listOf("game-1", "youtube-1")
        val kept = keptAfterLaunch(emptyList(), present, session = true)
        assertEquals("only the game-like stream is the game's", 1, kept.discount(present))
        assertTrue("the music app still counts, so the gate stays shut", blocked(kept, present))
        assertTrue("and it is shut even if the game stream goes", blocked(kept, listOf("youtube-1")))
    }

    @Test
    fun `YouTube Music alone is never the game's, with or without the media session`() {
        for (session in listOf(true, false)) {
            val kept = keptAfterLaunch(emptyList(), listOf("youtube-1"), session = session)
            assertEquals(0, kept.discount(listOf("youtube-1")))
            assertTrue(blocked(kept, listOf("youtube-1")))
        }
    }

    @Test
    fun `an app that says nothing about its music while its session plays is not told from the game, so the gate stays shut`() {
        // The media session proves some other player is playing, and nothing real-media tagged explains it: it must be the unknown-typed stream.
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"), session = true)
        assertEquals(0, kept.discount(listOf("game-1")))
        assertTrue(blocked(kept, listOf("game-1")))
    }

    @Test
    fun `a stream that starts after the return still blocks a passive start`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        assertFalse(blocked(kept, listOf("game-1")))
        assertTrue("a second game-like stream is beyond the budget", blocked(kept, listOf("game-1", "game-2")))
        assertTrue("a music app that starts later", blocked(kept, listOf("game-1", "youtube-1")))
        assertTrue("a podcast that starts later", blocked(kept, listOf("game-1", "podcast-1")))
        // And the passive start it blocks is silent: the gate's own verdict, with no take-over, holds the bed at zero.
        val later = listOf("game-1", "game-2")
        val passive = FrontendMusicPolicyInput(
            appForeground = true,
            musicVolume = 0.5f,
            focus = AudioFocusState.Foreground,
            environmentBlocked = blocked(kept, later),
            competingOwnedBed = false,
        )
        assertTrue("the verdict is the derived one", passive.environmentBlocked)
        assertEquals(0f, FrontendMusicPolicy.targetVolume(passive), 0f)
        val kept2 = keptAfterLaunch(emptyList(), listOf("game-1"))
        val onlyTheGame = passive.copy(environmentBlocked = blocked(kept2, listOf("game-1")))
        assertFalse("with only the game left, the same passive start plays", onlyTheGame.environmentBlocked)
        assertEquals(0.5f, FrontendMusicPolicy.targetVolume(onlyTheGame), 0f)
    }

    @Test
    fun `the discount goes when the game's stream does, and a newcomer of its signature is a stranger again`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onPlayback(emptyList(), 20_000L)
        kept.onPlayback(emptyList(), 20_000L + grace)
        kept.onPlayback(listOf("game-9"), 20_000L + grace + 100L)
        assertEquals(0, kept.discount(listOf("game-9")))
        assertTrue("the game closed; this is somebody else's", blocked(kept, listOf("game-9")))
        assertFalse(kept.holding())
    }

    @Test
    fun `a cold start with no launch armed snapshots nothing`() {
        val kept = streams()
        assertEquals(0, kept.onReturned(listOf("game-1"), strangerSessionPlaying = false, nowMs = 10_000L))
        assertEquals(0, kept.discount(listOf("game-1")))
        assertTrue(blocked(kept, listOf("game-1")))
    }

    @Test
    fun `Mimir's own bed alongside the kept game is not a stranger either`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        // The bed's own configuration is MUSIC (never game-like) and claimed: owned = 1 of 2 relevant, the game is the other.
        assertFalse(blocked(kept, listOf("game-1", "bed-1"), owned = 1))
    }

    @Test
    fun `a game declared with the game usage is the game's too`() {
        val kept = keptAfterLaunch(emptyList(), listOf("arcade-1"))
        assertFalse(blocked(kept, listOf("arcade-1")))
    }

    @Test
    fun `a call interrupts however many streams are discounted`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        assertTrue(blocked(kept, listOf("game-1"), call = true))
        assertTrue(blocked(kept, emptyList(), music = false, call = true))
    }

    @Test
    fun `music reported active with no stream to explain it still blocks, as before`() {
        assertTrue(blocked(streams(), emptyList(), music = true))
    }

    // ── The game's stream is recreated: a second launch in the same emulator, a route change ─────────────────────

    @Test
    fun `a second launch in the same emulator keeps the discount for its new stream when the first stream is released`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        assertEquals("game A is kept", 1, kept.discount(listOf("game-1")))
        // Launch game B: the launch counts A as the kept game, not as a stranger.
        kept.onLaunchArmed(listOf("game-1"), 20_000L)
        // The emulator releases A's stream while B is in front, and the release outlasts the grace.
        kept.onPlayback(emptyList(), 21_000L)
        kept.onPlayback(emptyList(), 21_000L + grace + 500L)
        // At the return B has its own stream.
        kept.onReturned(listOf("game-2"), strangerSessionPlaying = false, nowMs = 40_000L)
        assertEquals("B's stream is the game's, not the count the launch saw", 1, kept.discount(listOf("game-2")))
        assertFalse("the bed is not blocked again", blocked(kept, listOf("game-2")))
    }

    @Test
    fun `a second launch counts the game kept from before as the game's, not as a stranger, when its stream stays`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onLaunchArmed(listOf("game-1"), 20_000L)
        kept.onReturned(listOf("game-1", "game-2"), strangerSessionPlaying = false, nowMs = 40_000L)
        assertEquals("both streams are the games'", 2, kept.discount(listOf("game-1", "game-2")))
        assertFalse(blocked(kept, listOf("game-1", "game-2")))
    }

    @Test
    fun `a stranger that was already there at the second launch is still a stranger`() {
        // Kept game A (1) plus an unknown-typed app that started after the return: 2 streams, 1 discounted.
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onPlayback(listOf("game-1", "game-5"), 15_000L)
        assertTrue(blocked(kept, listOf("game-1", "game-5")))
        kept.onLaunchArmed(listOf("game-1", "game-5"), 20_000L)
        kept.onReturned(listOf("game-1", "game-5", "game-2"), strangerSessionPlaying = false, nowMs = 40_000L)
        assertEquals("the two games' streams, not the stranger's", 2, kept.discount(listOf("game-1", "game-5", "game-2")))
        assertTrue("one stranger remains", blocked(kept, listOf("game-1", "game-5", "game-2")))
    }

    @Test
    fun `a stream recreated on a route change inside the grace picks the discount back up`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onPlayback(emptyList(), 20_000L)
        assertEquals("while it is gone there is nothing to discount", 0, kept.discount(emptyList()))
        kept.onPlayback(listOf("game-1b"), 20_000L + 400L)
        assertEquals("the same game, reopened", 1, kept.discount(listOf("game-1b")))
        assertFalse(blocked(kept, listOf("game-1b")))
        // And it stays the game's: the reopening is not a fresh drop.
        kept.onPlayback(listOf("game-1b"), 30_000L)
        assertEquals(1, kept.discount(listOf("game-1b")))
    }

    @Test
    fun `a stream that comes back after the grace is a new stream, and a stranger`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onPlayback(emptyList(), 20_000L)
        kept.onPlayback(listOf("game-1b"), 20_000L + grace + 1L)
        assertEquals(0, kept.discount(listOf("game-1b")))
        assertTrue(blocked(kept, listOf("game-1b")))
    }

    // ── The discount follows the count ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stranger left behind when the game closes is a stranger again at once`() {
        // A kept game, and an unknown-typed app that started after the return (blocked: it is beyond the budget).
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onPlayback(listOf("game-1", "game-5"), 15_000L)
        assertTrue(blocked(kept, listOf("game-1", "game-5")))
        // The player closes the game: 2 streams become 1. The drop comes off the discount first.
        kept.onPlayback(listOf("game-5"), 20_000L)
        assertEquals("the count fell by one, so the discount did", 0, kept.discount(listOf("game-5")))
        assertTrue("the passive bed does not play over the app that is left", blocked(kept, listOf("game-5")))
        // And it stays so once the grace has passed.
        kept.onPlayback(listOf("game-5"), 20_000L + grace + 1L)
        assertEquals(0, kept.discount(listOf("game-5")))
        assertTrue(blocked(kept, listOf("game-5")))
        assertFalse(kept.holding())
    }

    @Test
    fun `a stranger that closes first is not mistaken for the game closing once it is back inside the grace`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onPlayback(listOf("game-1", "game-5"), 15_000L)
        kept.onPlayback(listOf("game-1"), 20_000L)
        assertEquals("conservative: the drop is taken off the discount first", 0, kept.discount(listOf("game-1")))
        kept.onPlayback(listOf("game-1", "game-5"), 20_000L + 300L)
        assertEquals("the count is back inside the grace, so the discount is too", 1, kept.discount(listOf("game-1", "game-5")))
    }

    // ── The count bounces: the new stream opens before the old one closes ────────────────────────────────────────

    @Test
    fun `a route change that opens the new stream before it closes the old one keeps the discount`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        // 1 -> 2 -> 1: the emulator opens its stream on the new route, then closes the old one.
        kept.onPlayback(listOf("game-1", "game-1b"), 20_000L)
        assertEquals("while both are open, the game still has its one", 1, kept.discount(listOf("game-1", "game-1b")))
        kept.onPlayback(listOf("game-1b"), 20_000L + 300L)
        assertEquals("the new stream is the game's: the fall back is not a drop", 1, kept.discount(listOf("game-1b")))
        assertFalse("the bed is not blocked again", blocked(kept, listOf("game-1b")))
        // And it is not a drop later either: the grace passing finds nothing to commit.
        kept.onPlayback(listOf("game-1b"), 20_000L + grace + 1_000L)
        kept.onPlayback(listOf("game-1b"), 40_000L)
        assertEquals(1, kept.discount(listOf("game-1b")))
        assertTrue(kept.holding())
    }

    @Test
    fun `another app's short game-like stream beside the kept game costs it nothing`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onPlayback(listOf("game-1", "game-7"), 20_000L)
        assertTrue("while it plays, it is beyond the budget", blocked(kept, listOf("game-1", "game-7")))
        kept.onPlayback(listOf("game-1"), 20_000L + grace - 1L)
        assertEquals("it left inside the grace: the game is still the game", 1, kept.discount(listOf("game-1")))
        assertFalse(blocked(kept, listOf("game-1")))
    }

    @Test
    fun `a bounce through a longer overlap and a gap both keep the discount, and each is measured on its own`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        // Two bounces in a row: the second does not inherit the first's clock.
        kept.onPlayback(listOf("game-1", "game-1b"), 20_000L)
        kept.onPlayback(listOf("game-1b"), 20_000L + grace - 1L)
        kept.onPlayback(listOf("game-1b", "game-1c"), 20_000L + grace + 5_000L)
        kept.onPlayback(listOf("game-1c"), 20_000L + grace + 5_000L + grace - 1L)
        assertEquals(1, kept.discount(listOf("game-1c")))
        // A gap in between (close, then open) is the reopen the grace always covered.
        kept.onPlayback(emptyList(), 60_000L)
        kept.onPlayback(listOf("game-1d"), 60_000L + 400L)
        assertEquals(1, kept.discount(listOf("game-1d")))
    }

    @Test
    fun `a second stream that has stood past the grace is a stranger, and when the count then falls the game is assumed gone (the stated limit)`() {
        // Mimir cannot tell which of two same-signature streams closed. A rise that stood is somebody else's, so a later fall from it comes off the
        // discount: the same rule as a stranger left behind when the game closes. The cost: a same-signature stranger that stood for the grace and
        // then left also takes the game's discount, and the bed waits for the next launch.
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        kept.onPlayback(listOf("game-1", "game-7"), 20_000L)
        kept.onPlayback(listOf("game-1"), 20_000L + grace)
        assertEquals(0, kept.discount(listOf("game-1")))
        assertTrue(blocked(kept, listOf("game-1")))
    }

    // ── A game resumed or fronted from its tab has no launch edge ───────────────────────────────────────────────────

    @Test
    fun `a game on screen with no launch edge is not discounted, and the discount is back unchanged when it is not`() {
        val kept = keptAfterLaunch(emptyList(), listOf("game-1"))
        assertFalse("kept behind Mimir", blocked(kept, listOf("game-1")))
        assertTrue("the control says a game is on screen (Resume, Recents, the Bottom game's tab)", kept.setFront(true))
        assertEquals(0, kept.discount(listOf("game-1")))
        assertTrue("its stream is the courtesy gate's again", blocked(kept, listOf("game-1")))
        assertFalse("saying it twice changes nothing", kept.setFront(true))
        assertTrue(kept.setFront(false))
        assertEquals("the budget was never touched", 1, kept.discount(listOf("game-1")))
        assertFalse(blocked(kept, listOf("game-1")))
        assertTrue(kept.holding())
    }

    // ── A restart: the app proves the game's task is still there ─────────────────────────────────────────────────

    @Test
    fun `a restart with the kept game's task proven discounts its stream, and what comes later still blocks`() {
        val kept = streams()
        assertEquals(1, kept.seed(listOf("game-1"), keptTasks = 1, strangerSessionPlaying = false, nowMs = 5_000L))
        assertEquals(1, kept.discount(listOf("game-1")))
        assertFalse("the Thor case after a restart: observed=1 relevant=1 owned=0 no longer blocks", blocked(kept, listOf("game-1")))
        assertTrue("a second game-like stream is beyond the one task", blocked(kept, listOf("game-1", "game-2")))
        assertTrue("a music app", blocked(kept, listOf("game-1", "youtube-1")))
        // And it follows the count from here on, as after a return.
        kept.onPlayback(emptyList(), 10_000L)
        kept.onPlayback(emptyList(), 10_000L + grace)
        kept.onPlayback(listOf("game-9"), 10_000L + grace + 100L)
        assertTrue("the game closed; this is somebody else's", blocked(kept, listOf("game-9")))
    }

    @Test
    fun `no task proven seeds nothing, and never more than the tasks proven`() {
        val none = streams()
        assertEquals(0, none.seed(listOf("game-1"), keptTasks = 0, strangerSessionPlaying = false, nowMs = 5_000L))
        assertTrue(blocked(none, listOf("game-1")))
        val one = streams()
        assertEquals("two game-like streams, one task", 1, one.seed(listOf("game-1", "game-2"), keptTasks = 1, strangerSessionPlaying = false, nowMs = 5_000L))
        assertTrue("the other one is a stranger", blocked(one, listOf("game-1", "game-2")))
        val many = streams()
        assertEquals("one stream, two tasks proven: only what is there", 1, many.seed(listOf("game-1"), keptTasks = 2, strangerSessionPlaying = false, nowMs = 5_000L))
    }

    @Test
    fun `a restart never discounts music, movie or speech`() {
        val kept = streams()
        assertEquals(0, kept.seed(listOf("youtube-1", "podcast-1"), keptTasks = 1, strangerSessionPlaying = false, nowMs = 5_000L))
        assertTrue(blocked(kept, listOf("youtube-1")))
        assertTrue(blocked(kept, listOf("podcast-1")))
    }

    @Test
    fun `a launch or a return seen in this process owns the budget, so a late seed changes nothing`() {
        val returned = keptAfterLaunch(emptyList(), listOf("game-1"))
        assertEquals(0, returned.seed(listOf("game-1", "game-2"), keptTasks = 2, strangerSessionPlaying = false, nowMs = 20_000L))
        assertEquals("the budget is the return's", 1, returned.discount(listOf("game-1", "game-2")))
        val inFront = streams()
        inFront.onLaunchArmed(emptyList(), 0L)
        assertEquals("a game is in front", 0, inFront.seed(listOf("game-1"), keptTasks = 1, strangerSessionPlaying = false, nowMs = 5_000L))
    }

    @Test
    fun `a playing media session with nothing real-media tagged is not told from the game after a restart either`() {
        val kept = streams()
        assertEquals(0, kept.seed(listOf("game-1"), keptTasks = 1, strangerSessionPlaying = true, nowMs = 5_000L))
        assertTrue(blocked(kept, listOf("game-1")))
        val explained = streams()
        assertEquals("a real media stream explains the session", 1, explained.seed(listOf("game-1", "youtube-1"), keptTasks = 1, strangerSessionPlaying = true, nowMs = 5_000L))
        assertTrue("and the music app still blocks", blocked(explained, listOf("game-1", "youtube-1")))
    }

    @Test
    fun `the media bed seeds from the live streams, under its lock, and says so`() {
        val bed = source("MimirMediaBed.kt")
        val seed = bed.substringAfter("fun seedKeptGame(keptTasks: Int, strangerSessionPlaying: Boolean): Int {").substringBefore("/** The relevant streams that are not claimed")
        assertTrue(seed.contains("synchronized(lock)"))
        assertTrue("fresh streams first", seed.indexOf("acceptPlaybackConfigurationsLocked") < seed.indexOf("keptGame.seed("))
        assertTrue(seed.contains("keptGame.seed(unownedPlaybackLocked(), keptTasks, strangerSessionPlaying, SystemClock.uptimeMillis())"))
        assertTrue(seed.contains("publishEnvironmentLocked()"))
        assertTrue("one line in the log", seed.contains("restart with a Mimir game still running"))
    }

    // ── The wiring ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the media bed takes the launch and the return from the audio focus edges and logs them`() {
        val control = source("MimirMusicControl.kt")
        val watch = control.substringAfter("MimirAudioFocus.state.collect { focus ->").substringBefore("gameInFrontBefore = gameInFrontNow")
        assertTrue(watch.contains("focus is AudioFocusState.Suspended"))
        assertTrue("the launch", watch.contains("if (gameInFrontNow && !gameInFrontBefore) MimirMediaBed.noteGameLaunchArmed()"))
        assertTrue("the return carries the media-session evidence", watch.contains("MimirMediaBed.noteReturnedFromGame(strangerSessionPlaying = _otherAccess.value && _otherPlayer.value?.isPlaying == true)"))
        val bed = source("MimirMediaBed.kt")
        assertTrue("the filter is not widened", bed.contains("contentType != AudioAttributes.CONTENT_TYPE_SONIFICATION"))
        assertFalse(bed.substringAfter("internal fun mimirMediaBedCourtesyRelevantContent").substringBefore("}").contains("UNKNOWN"))
        assertTrue("the diagnostic keeps its fields and gains kept=", bed.contains("pending=\${pendingPlaybackClaims.size} kept=\$keptDiscount"))
        assertTrue(bed.contains("returned from a Mimir launch"))
        // The budget follows the list on the ordinary path, not through the owned-start gap's early return, and with the clock.
        val accept = bed.substringAfter("private fun acceptPlaybackConfigurationsLocked(").substringBefore("private fun ownBedSignature")
        assertTrue(accept.indexOf("keptGame.onPlayback(relevant, SystemClock.uptimeMillis())") > accept.indexOf("mainHandler.postDelayed(refreshOwnedStartGap, OwnActiveClaimRefreshMs)"))
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/$name").readText()
}
