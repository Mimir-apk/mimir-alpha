package com.gruning.mimir.rewrite

import android.app.Application
import android.media.MediaPlayer
import android.os.Looper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Settings > Music > Launch sound and Launch sound volume : the intro plays the game's sound only when the
 * switch is on, ramps to the dialled level instead of 1.0, and both exits (the intro's own fade and the launch cover's) fall
 * from that same level in the same shape. The visual intro is untouched either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class LaunchSoundSettingTest {
    private val gameId = "launch-sound-game"

    @Before
    fun setUp() = GameOpeningIntroState.hide()

    @After
    fun tearDown() = GameOpeningIntroState.hide()

    private fun plan() = GameOpeningIntroPlan(
        gameIdValue = gameId,
        title = "Launch Sound Game",
        heroUri = "file:///hero.png",
        logoUri = null,
        audioUri = "file:///media_art/Launch Sound Game-audio.m4a",
    )

    // ── the decision ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the game's sound is skipped when the switch is off or the volume is nothing, and otherwise plays at the dialled level`() {
        assertNull("off: no sound at all", gameOpeningIntroAudioTarget(launchSoundEnabled = false, launchSoundVolume = 1f))
        assertNull("off wins over any volume", gameOpeningIntroAudioTarget(launchSoundEnabled = false, launchSoundVolume = 0.4f))
        assertNull("dialled to zero: no player is built for a silent sound", gameOpeningIntroAudioTarget(true, 0f))
        assertEquals("the default is the level the intro always played at", 1f, gameOpeningIntroAudioTarget(true, 1f)!!, 0f)
        assertEquals(0.4f, gameOpeningIntroAudioTarget(true, 0.4f)!!, 0f)
        assertEquals("clamped", 1f, gameOpeningIntroAudioTarget(true, 7f)!!, 0f)
        assertNull("clamped", gameOpeningIntroAudioTarget(true, -3f))
    }

    @Test
    fun `a step of a ramp or a fade is the target times the fraction, so the shape is the same at any level`() {
        val fractions = (0..20).map { it / 20f }
        val full = fractions.map { gameOpeningIntroAudioVolume(1f, it) }
        val quiet = fractions.map { gameOpeningIntroAudioVolume(0.4f, it) }
        full.zip(quiet).forEach { (f, q) -> assertEquals(f * 0.4f, q, 1e-6f) }
        assertEquals(0.4f, quiet.last(), 1e-6f)
        assertEquals(0f, quiet.first(), 0f)
        assertEquals("never past the target even for a bad fraction", 0.4f, gameOpeningIntroAudioVolume(0.4f, 3f), 0f)
    }

    // ── the intro: ramp up to the target, fall from the target ───────────────────────────────────────

    @Test
    fun `the entrance ramps to the dialled level and not to full`() = runTest {
        GameOpeningIntroState.show(plan())
        val player = MediaPlayer()
        assertTrue(GameOpeningIntroState.attachPlayer(gameId, player, targetVolume = 0.4f))

        GameOpeningIntroState.startAudioRamp(this, gameId)
        advanceTimeBy(16)
        runCurrent()
        assertEquals("the first step of twenty, scaled", 0.4f / 20f, shadowOf(player).leftVolume, 1e-4f)

        advanceTimeBy(400)
        runCurrent()
        assertEquals("the ramp ends AT the target", 0.4f, shadowOf(player).leftVolume, 1e-4f)
        assertEquals(0.4f, shadowOf(player).rightVolume, 1e-4f)
    }

    @Test
    fun `the intro's own exit falls from the dialled level to silence`() = runTest {
        GameOpeningIntroState.show(plan())
        val player = MediaPlayer()
        GameOpeningIntroState.attachPlayer(gameId, player, targetVolume = 0.4f)
        player.setVolume(0.4f, 0.4f)

        val fade = launch { GameOpeningIntroState.fadeAudioOut(gameId, 400L) }
        runCurrent()
        assertEquals("the first step is below the target, not a jump to 0.95", 0.4f * 0.95f, shadowOf(player).leftVolume, 1e-4f)

        advanceUntilIdle()
        fade.join()
        assertEquals(0f, shadowOf(player).leftVolume, 1e-4f)
    }

    @Test
    fun `the cover is told the level the intro was playing at, and 1 when there was no sound`() {
        assertEquals("no intro, no player: full", 1f, GameOpeningIntroState.audioTargetFor(gameId), 0f)
        GameOpeningIntroState.show(plan())
        assertEquals("an intro with no player yet: full", 1f, GameOpeningIntroState.audioTargetFor(gameId), 0f)
        GameOpeningIntroState.attachPlayer(gameId, MediaPlayer(), targetVolume = 0.4f)
        assertEquals(0.4f, GameOpeningIntroState.audioTargetFor(gameId), 0f)
        assertEquals("another game's intro is not this one's level", 1f, GameOpeningIntroState.audioTargetFor("other-game"), 0f)
    }

    // ── the launch cover: the same fall, from the same level ─────────────────────────────────────────

    @Test
    fun `the cover's fade from a dialled level starts below it and ends in silence`() {
        assertEquals("unchanged at full: the old values", 0.95f, gameLaunchCoverAudioVolume(1, 20), 1e-6f)
        assertEquals(0.4f * 0.95f, gameLaunchCoverAudioVolume(1, 20, startVolume = 0.4f), 1e-6f)
        assertTrue("at 0.4 the first cover step is below 0.4", gameLaunchCoverAudioVolume(1, 20, startVolume = 0.4f) < 0.4f)
        assertEquals(0f, gameLaunchCoverAudioVolume(20, 20, startVolume = 0.4f), 0f)
        val steps = (1..20).map { gameLaunchCoverAudioVolume(it, 20, startVolume = 0.4f) }
        assertEquals("strictly falling", steps, steps.sortedDescending())
    }

    @Test
    fun `the cover's player really fades from the level it was handed`() {
        val player = MediaPlayer()
        val audio = MediaPlayerCoverAudio(player, startVolume = 0.4f)

        audio.fadeOut(400L)
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        assertEquals("the first step of the cover's fade", 0.4f * 0.95f, shadowOf(player).leftVolume, 1e-4f)

        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertEquals(0f, shadowOf(player).leftVolume, 1e-4f)
        audio.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ── where the decision is made ───────────────────────────────────────────────────────────────────

    private fun source(name: String): String = File(
        listOf(
            "app/src/main/kotlin/com/gruning/mimir/rewrite/$name",
            "src/main/kotlin/com/gruning/mimir/rewrite/$name",
        ).first { File(it).isFile },
    ).readText()

    @Test
    fun `both starts of the intro's sound pass the one gate, before any player is built`() {
        val intro = source("GameOpeningIntro.kt")
        val play = intro.substringAfter("suspend fun playGameOpeningIntro(").substringBefore("suspend fun presentGameOpening(")
        val presentMarker = "suspend fun presentGameOpening("
        val presentStart = intro.indexOf(presentMarker)
        assertTrue("present intro declaration must exist", presentStart >= 0)
        val preMeasureStart = intro.indexOf("private suspend fun gameOpeningIntroPreMeasureLogo(", startIndex = presentStart)
        assertTrue("logo pre-measure declaration must follow present intro", preMeasureStart > presentStart)
        val present = intro.substring(presentStart + presentMarker.length, preMeasureStart)
        assertTrue(play.contains("startGameOpeningIntroAudio(context, plan, looping = false)"))
        assertTrue(present.contains("startGameOpeningIntroAudio(context, plan, looping = true)"))

        assertEquals("one place builds the sound's player", 1, Regex("""MediaPlayer\(\)""").findAll(intro).count())
        val start = intro.substringAfter("private suspend fun startGameOpeningIntroAudio(")
        assertTrue(
            "the switch and the level are read before the player exists",
            start.indexOf("gameOpeningIntroAudioTarget(") in 0 until start.indexOf("prepareOpeningPlayer("),
        )
        assertTrue(start.contains("AudioPreferences.launchSoundEnabled.value") && start.contains("AudioPreferences.launchSoundVolume.value"))
        assertTrue("the level goes to the state with the player", start.contains("attachPlayer(plan.gameIdValue, mediaPlayer, target, presentation)"))
    }

    @Test
    fun `the intro reads the launch sound settings and nothing of the backdrop's preview audio switch`() {
        // Code only: the sound's KDoc is allowed to NAME the switch it deliberately does not read.
        val intro = source("GameOpeningIntro.kt")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }
        assertFalse(intro.contains("previewAudioEnabled"))
        assertFalse(intro.contains("previewAudioMix"))
        assertFalse("the music's own switch and volume are not the launch sound's", intro.contains("AudioPreferences.musicEnabled") || intro.contains("AudioPreferences.ambientVolume"))
        assertTrue(intro.contains("AudioPreferences.launchSoundEnabled") && intro.contains("AudioPreferences.launchSoundVolume"))
    }

    @Test
    fun `the ramp and both exits are scaled by the one captured level and the cover is handed it`() {
        val intro = source("GameOpeningIntro.kt")
        val state = intro.substringAfter("internal fun startAudioRamp(").substringBefore("internal fun hideIfCurrent(")
        assertEquals("the ramp and the intro's fade both scale", 2, Regex("""gameOpeningIntroAudioVolume\(target,""").findAll(state).count())
        assertFalse("no bare 1f ramp or fade is left", state.contains("val volume = (step + 1)") || state.contains("val volume = 1f -"))
        val finish = intro.substringAfter("val coverHasIt = ").substringBefore("if (coverHasIt)")
        assertTrue(finish.contains("GameOpeningIntroState.audioTargetFor(plan.gameIdValue)"))
        val cover = source("GameLaunchCover.kt")
        assertTrue(cover.contains("MediaPlayerCoverAudio(it, startVolume = startVolume)"))
        assertTrue(cover.contains("gameLaunchCoverAudioVolume(step, GameLaunchCoverAudioFadeSteps, startVolume)"))
    }

    @Test
    fun `a remote sound is still refused whatever the settings say`() {
        assertNull(gameOpeningIntroAudioUri(previewClipUri = null, launchAudioUri = "https://www.screenscraper.fr/clip.m4a"))
        assertEquals(
            "file:///media_art/a-audio.m4a",
            gameOpeningIntroAudioUri(previewClipUri = null, launchAudioUri = "file:///media_art/a-audio.m4a"),
        )
    }
}
