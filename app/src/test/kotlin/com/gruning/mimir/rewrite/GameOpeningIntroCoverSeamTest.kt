package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.asImageBitmap
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

/**
 * The seam between the in-activity intro and the launch cover: what the cover may take from the intro
 * before the intent fires, the frame it must draw, and what [finishGameOpeningIntro] does differently when a cover is up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class GameOpeningIntroCoverSeamTest {
    private val gameId = "cover-game"

    @Before
    fun setUp() = GameOpeningIntroState.hide()

    @After
    fun tearDown() = GameOpeningIntroState.hide()

    private fun plan(id: String = gameId, logo: String? = "file:///logo.png") = GameOpeningIntroPlan(
        gameIdValue = id,
        title = "Cover Game",
        heroUri = "file:///hero.png",
        logoUri = logo,
        audioUri = null,
    )

    private fun hero() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()

    // ---- the frame

    @Test
    fun `the intro's frame at time zero is its starting frame`() {
        val frame = gameOpeningIntroFrameAt(0L)

        assertEquals(0f, frame.heroAlpha, 0f)
        assertEquals(0f, frame.logoAlpha, 0f)
        assertEquals(GameOpeningIntroLogoScaleFrom, frame.logoScale, 0f)
        assertEquals(1f, frame.zoom, 0f)
    }

    @Test
    fun `by a dispatch, three seconds in, the entrance is over and only the zoom is still moving`() {
        val frame = gameOpeningIntroFrameAt(3_000L)

        assertEquals(1f, frame.heroAlpha, 0f)
        assertEquals(1f, frame.logoAlpha, 0f)
        assertEquals(1f, frame.logoScale, 0f)
        assertTrue("mid-zoom: ${frame.zoom}", frame.zoom > 1f && frame.zoom < GameOpeningIntroZoomTo)
    }

    @Test
    fun `the frame rests at the zoom's end however long the wait runs, and a clock that ran backwards is the start`() {
        assertEquals(GameOpeningIntroZoomTo, gameOpeningIntroFrameAt(GameOpeningIntroZoomMs.toLong()).zoom, 0f)
        assertEquals(GameOpeningIntroZoomTo, gameOpeningIntroFrameAt(60_000L).zoom, 0f)
        assertEquals(gameOpeningIntroFrameAt(0L), gameOpeningIntroFrameAt(-500L))
    }

    @Test
    fun `the frame only ever moves forward`() {
        var previous = gameOpeningIntroFrameAt(0L)
        for (ms in 25L..7_000L step 25L) {
            val frame = gameOpeningIntroFrameAt(ms)
            assertTrue("hero alpha at $ms", frame.heroAlpha >= previous.heroAlpha)
            assertTrue("logo scale at $ms", frame.logoScale >= previous.logoScale)
            assertTrue("zoom at $ms", frame.zoom >= previous.zoom)
            previous = frame
        }
    }

    @Test
    fun `the frame is what the layer's own tweens compute, at every point that matters`() {
        // The layer animates Animatables with tween(GameOpeningIntroEnterMs / ZoomMs, MimirMotion.Easing). This asks
        // Compose's own vectorized spec for the same number, so a change to either side fails here.
        fun compose(durationMs: Int, from: Float, to: Float, atMs: Long): Float =
            tween<Float>(durationMs, easing = MimirMotion.Easing)
                .vectorize(Float.VectorConverter)
                .getValueFromNanos(
                    atMs * 1_000_000L,
                    AnimationVector1D(from),
                    AnimationVector1D(to),
                    AnimationVector1D(0f),
                )
                .value

        for (ms in listOf(0L, 100L, 300L, 600L, 1_500L, 3_000L, 4_400L, 5_900L, 9_000L)) {
            val frame = gameOpeningIntroFrameAt(ms)
            assertEquals("hero alpha at $ms", compose(GameOpeningIntroEnterMs, 0f, 1f, ms), frame.heroAlpha, 1e-5f)
            assertEquals("logo alpha at $ms", compose(GameOpeningIntroEnterMs, 0f, 1f, ms), frame.logoAlpha, 1e-5f)
            assertEquals(
                "logo scale at $ms",
                compose(GameOpeningIntroEnterMs, GameOpeningIntroLogoScaleFrom, 1f, ms),
                frame.logoScale,
                1e-5f,
            )
            assertEquals("zoom at $ms", compose(GameOpeningIntroZoomMs, 1f, GameOpeningIntroZoomTo, ms), frame.zoom, 1e-5f)
        }
    }

    // ---- the snapshot

    @Test
    fun `there is no snapshot until the intro's hero has loaded`() {
        GameOpeningIntroState.show(plan())
        assertNull("the hero has not loaded", GameOpeningIntroState.coverSnapshotFor(gameId))

        val bitmap = hero()
        GameOpeningIntroState.reportHero(gameId, bitmap)
        val snapshot = GameOpeningIntroState.coverSnapshotFor(gameId)

        assertNotNull(snapshot)
        assertEquals(gameId, snapshot!!.gameIdValue)
        assertEquals("Cover Game", snapshot.title)
        assertEquals("file:///hero.png", snapshot.heroUri)
        assertEquals("file:///logo.png", snapshot.logoUri)
        assertSame("the very bitmap the intro loaded, not a decode of its own", bitmap, snapshot.hero)
    }

    @Test
    fun `the snapshot carries the frame the intro is on when it is taken`() {
        GameOpeningIntroState.show(plan())
        GameOpeningIntroState.reportHero(gameId, hero())

        val now = System.nanoTime()
        val early = GameOpeningIntroState.coverSnapshotFor(gameId, nowNanos = now)!!
        val later = GameOpeningIntroState.coverSnapshotFor(gameId, nowNanos = now + 3_000_000_000L)!!

        assertTrue("the zoom advanced between the two", later.frame.zoom > early.frame.zoom)
        assertEquals("three seconds later the entrance is over", 1f, later.frame.heroAlpha, 0f)
    }

    @Test
    fun `a snapshot is a copy, so clearing the intro leaves it whole`() {
        GameOpeningIntroState.show(plan())
        val bitmap = hero()
        GameOpeningIntroState.reportHero(gameId, bitmap)
        val snapshot = GameOpeningIntroState.coverSnapshotFor(gameId)!!

        // The yield path: hideIfCurrentWithoutExit clears the state the moment the cover takes over.
        GameOpeningIntroState.hideIfCurrentWithoutExit(gameId)

        assertNull(GameOpeningIntroState.current.value)
        assertNull(GameOpeningIntroState.coverSnapshotFor(gameId))
        assertEquals("Cover Game", snapshot.title)
        assertSame(bitmap, snapshot.hero)
        assertEquals("file:///logo.png", snapshot.logoUri)
    }

    @Test
    fun `a hero reported for another game, or after the intro went, is not kept`() {
        GameOpeningIntroState.show(plan())
        GameOpeningIntroState.reportHero("another-game", hero())
        assertNull(GameOpeningIntroState.coverSnapshotFor(gameId))

        GameOpeningIntroState.hide()
        GameOpeningIntroState.reportHero(gameId, hero())
        GameOpeningIntroState.show(plan())
        assertNull("a fresh intro starts with no hero", GameOpeningIntroState.coverSnapshotFor(gameId))
    }

    @Test
    fun `there is no snapshot for an intro that is not the one showing`() {
        GameOpeningIntroState.show(plan())
        GameOpeningIntroState.reportHero(gameId, hero())

        assertNull(GameOpeningIntroState.coverSnapshotFor("another-game"))
    }

    // ---- the audio

    @Test
    fun `a detached player survives the intro's removal, and an attached one does not`() {
        GameOpeningIntroState.show(plan())
        val kept = MediaPlayer()
        assertTrue(GameOpeningIntroState.attachPlayer(gameId, kept))

        assertSame(kept, GameOpeningIntroState.detachPlayer(gameId))
        GameOpeningIntroState.hideIfCurrentWithoutExit(gameId)
        assertNotEquals("the cover owns it now", ShadowMediaPlayer.State.END, shadowOf(kept).state)
        assertNull("and the state no longer holds it", GameOpeningIntroState.detachPlayer(gameId))

        GameOpeningIntroState.show(plan())
        val released = MediaPlayer()
        assertTrue(GameOpeningIntroState.attachPlayer(gameId, released))
        GameOpeningIntroState.hideIfCurrentWithoutExit(gameId)
        assertEquals("today's removal releases what it holds", ShadowMediaPlayer.State.END, shadowOf(released).state)
    }

    @Test
    fun `no player is detached for an intro that is not the current one`() {
        GameOpeningIntroState.show(plan())
        assertTrue(GameOpeningIntroState.attachPlayer(gameId, MediaPlayer()))

        assertNull(GameOpeningIntroState.detachPlayer("another-game"))
    }

    // ---- finishGameOpeningIntro with a cover up

    @Test
    fun `under a live cover the intro leaves at the yield with no audio hold, and the cover takes the player`() = runTest {
        GameOpeningIntroState.show(plan())
        val player = MediaPlayer()
        // A launch sound dialled to 40%: the cover is handed that level, read before the player is taken, so it fades from it.
        assertTrue(GameOpeningIntroState.attachPlayer(gameId, player, targetVolume = 0.4f))
        val cover = FakeCover(live = true)
        val handoff = CompletableDeferred<GameOpeningIntroHandoff>()
        finishGameOpeningIntro(
            this,
            launched = true,
            awaitHandoff = { handoff.await() },
            hostStopped = { true },
            restoreMenuMusic = {},
            cover = cover,
        )
        advanceTimeBy(1_000L)
        runCurrent()

        handoff.complete(GameOpeningIntroHandoff.GAME_HAS_SCREEN)
        runCurrent()

        assertNull("gone at the yield, not 0.2 s after it", GameOpeningIntroState.current.value)
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(listOf(gameId), cover.yields)
        assertSame(player, cover.taken)
        assertEquals("the cover is handed the level the intro was playing at", listOf(0.4f), cover.startVolumes)
        assertNotEquals("the intro's removal did not release it", ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertTrue("a yield is not a settle", cover.settles.isEmpty())
    }

    @Test
    fun `with no cover the same yield is held 0_2 s as it always was, and the player is never taken`() = runTest {
        GameOpeningIntroState.show(plan())
        val player = MediaPlayer()
        assertTrue(GameOpeningIntroState.attachPlayer(gameId, player))
        val cover = FakeCover(live = false)
        val handoff = CompletableDeferred<GameOpeningIntroHandoff>()
        finishGameOpeningIntro(
            this,
            launched = true,
            awaitHandoff = { handoff.await() },
            hostStopped = { true },
            restoreMenuMusic = {},
            cover = cover,
        )
        runCurrent()

        handoff.complete(GameOpeningIntroHandoff.GAME_HAS_SCREEN)
        runCurrent()
        advanceTimeBy(GameOpeningIntroYieldHoldMs - 1L)
        runCurrent()
        assertNotNull("still holding just short of 0.2 s", GameOpeningIntroState.current.value)

        advanceTimeBy(1L)
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertEquals(listOf(gameId), cover.yields)
        assertNull("no cover, so nothing was taken", cover.taken)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
    }

    @Test
    fun `the verdict, the cap and a launch that never dispatched each end the cover before the intro fades`() = runTest {
        for ((name, handoffOrNull) in listOf(
            "verdict" to GameOpeningIntroHandoff.SESSION_SETTLED,
            "cap" to GameOpeningIntroHandoff.CAP,
            "never dispatched" to null,
        )) {
            GameOpeningIntroState.show(plan())
            val cover = FakeCover(live = true)
            finishGameOpeningIntro(
                this,
                launched = handoffOrNull != null,
                awaitHandoff = { requireNotNull(handoffOrNull) },
                hostStopped = { false },
                restoreMenuMusic = {},
                cover = cover,
            )
            runCurrent()

            assertEquals(name, listOf(gameId), cover.settles)
            assertTrue("$name: no yield", cover.yields.isEmpty())
            advanceTimeBy(GameOpeningIntroExitMs.toLong())
            runCurrent()
            assertNull("$name: the intro then fades as it always has", GameOpeningIntroState.current.value)
        }
    }

    @Test
    fun `a wait that fails ends the cover if Mimir is showing, and leaves it if the game has the screen`() = runTest {
        for ((stopped, expectSettled) in listOf(false to true, true to false)) {
            GameOpeningIntroState.show(plan())
            val cover = FakeCover(live = true)
            finishGameOpeningIntro(
                this,
                launched = true,
                awaitHandoff = { throw IllegalStateException("the wait failed") },
                hostStopped = { stopped },
                restoreMenuMusic = {},
                cover = cover,
            )
            runCurrent()

            assertEquals("stopped=$stopped", if (expectSettled) listOf(gameId) else emptyList<String>(), cover.settles)
            assertNull(GameOpeningIntroState.current.value)
        }
    }

    // ---- fixtures

    private class FakeCover(private val live: Boolean) : GameLaunchCoverHandoff {
        val yields = mutableListOf<String>()
        val settles = mutableListOf<String>()
        var taken: MediaPlayer? = null
        val startVolumes = mutableListOf<Float>()

        override fun yielded(gameIdValue: String, startVolume: Float, takePlayer: () -> MediaPlayer?): Boolean {
            yields += gameIdValue
            startVolumes += startVolume
            if (live) taken = takePlayer()
            return live
        }

        override fun settled(gameIdValue: String) {
            settles += gameIdValue
        }
    }
}
