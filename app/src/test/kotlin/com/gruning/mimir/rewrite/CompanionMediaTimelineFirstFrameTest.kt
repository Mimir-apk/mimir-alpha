package com.gruning.mimir.rewrite

import android.app.Application
import android.media.session.MediaSession
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression: swiping from Activity to the lattice and back showed the media tile's progress bar at its OLD position for a split second, then
 * jumping to the right place. The Activity scene is composed only while it is on screen or panning, so the tile is composed fresh each time, and its
 * timeline seeded its first frame from the STORED callback position (the last time the session reported one) and only an effect, a frame later, moved it to
 * the projected one.
 *
 * The timeline now seeds from [companionMediaTimelinePositionNow]: the stored position carried forward by the elapsed time at the session's speed while it
 * PLAYS, the stored position as it is while paused. These tests compose the real tile with the main clock paused (nothing advances past the first
 * composition, so no effect has run) and read the bar's range off its semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionMediaTimelineFirstFrameTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val durationMs = 200_000L
    private val storedMs = 10_000L
    private val agoMs = 60_000L

    private fun track(state: CompanionMediaPlaybackState, speed: Float = 1f, updatedAt: Long?): CompanionMediaTrack {
        val token = MediaSession(ApplicationProvider.getApplicationContext<Application>(), "first-frame").sessionToken
        return CompanionMediaTrack(
            title = "Dreams of the Hedgehog",
            artist = "Anonymous",
            appLabel = "Player",
            packageName = "example.player",
            canPrevious = true,
            canPlayPause = true,
            canNext = true,
            sessionIdentity = token,
            trackIdentity = "track-1",
            playbackState = state,
            positionMs = storedMs,
            durationMs = durationMs,
            canSeek = true,
            positionUpdateElapsedRealtimeMs = updatedAt,
            playbackSpeed = speed,
        )
    }

    /** The clock moved well past [agoMs] so "updated [agoMs] ago" is a real, positive elapsed-realtime stamp. */
    private fun nowMs(): Long {
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 3_600_000L)
        return SystemClock.elapsedRealtime()
    }

    /** Composes the Activity page's media tile with the clock paused after the FIRST composition, and returns the bar's fraction as drawn then. */
    private fun firstFrameFraction(track: CompanionMediaTrack): Float {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize((1240f / density369).dp, (1080f / density369).dp)) {
                        CompanionWorkspaceSurface(
                            page = CompanionWorkspacePage.ACTIVITY,
                            navigationEnabled = true,
                            recentApp = null,
                            game = null,
                            media = CompanionMediaUiState.Active(track),
                            onRecentApp = {},
                            onReturnToGame = null,
                            onMediaAccess = {},
                            onMediaAction = { _, _, _ -> },
                        )
                    }
                }
            }
        }
        val node = compose.onNodeWithContentDescription("Seek playback").fetchSemanticsNode()
        return node.config[SemanticsProperties.ProgressBarRangeInfo].current
    }

    @Test
    fun `a playing track whose stored position is old draws its CURRENT position on the first frame`() {
        val now = nowMs()
        val fraction = firstFrameFraction(track(CompanionMediaPlaybackState.PLAYING, updatedAt = now - agoMs))
        val stored = storedMs.toFloat() / durationMs
        val projected = (storedMs + agoMs).toFloat() / durationMs
        assertTrue("the first frame is not the stale stored fraction ($fraction vs $stored)", fraction > stored + 0.1f)
        assertEquals("the first frame is the extrapolated position", projected, fraction, 0.02f)
    }

    @Test
    fun `a paused track draws its stored position on the first frame`() {
        val now = nowMs()
        val fraction = firstFrameFraction(track(CompanionMediaPlaybackState.PAUSED, updatedAt = now - agoMs))
        assertEquals("paused does not advance", storedMs.toFloat() / durationMs, fraction, 0.0005f)
    }

    @Test
    fun `the projection is the stored position plus the elapsed time at the session speed, bounded by the duration`() {
        val now = 5_000_000L
        val playing = track(CompanionMediaPlaybackState.PLAYING, speed = 2f, updatedAt = now - 30_000L)
        assertEquals(storedMs + 60_000L, companionMediaTimelinePositionNow(playing, now))
        assertEquals("never past the end", durationMs, companionMediaTimelinePositionNow(playing.copy(positionMs = 190_000L), now))
        assertEquals("paused is as stored", storedMs, companionMediaTimelinePositionNow(playing.copy(playbackState = CompanionMediaPlaybackState.PAUSED), now))
        assertEquals("no update stamp is as stored", storedMs, companionMediaTimelinePositionNow(playing.copy(positionUpdateElapsedRealtimeMs = null), now))
        assertEquals("no timing at all is zero", 0L, companionMediaTimelinePositionNow(playing.copy(positionMs = null, durationMs = null), now))
    }
}
