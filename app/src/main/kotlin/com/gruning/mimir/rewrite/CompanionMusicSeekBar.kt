package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.companion.LaunchedEffectWhileLive
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import kotlinx.coroutines.delay

/*
 * W3e (Y1): the player's seek bar, written once for both kinds of player. A [MusicSeekSource] is what the bar reads: the track's length, whether it can be seeked, whether
 * it is advancing and where it is NOW (a function of the elapsed-realtime clock, so the bar never draws a stale position for a frame). Another app's session builds one
 * from the bridge track (companionMediaTimelinePositionNow); Your music from its YourMusicState. Seeking is one call on the release of a drag, through the same
 * CompanionSeekGestureGate the media tile's lane uses, so the workspace pan and a scrub never both take a finger.
 */

/** The bar's tag, for the render tests. */
internal const val MusicSeekBarTag = "bottom-music-player-seek"
internal const val MusicSeekElapsedTag = "bottom-music-player-seek-elapsed"
internal const val MusicSeekTotalTag = "bottom-music-player-seek-total"

/** The bar's touch zone and the track's thickness and the thumb's size; the times sit under the zone. */
internal const val MusicSeekZoneDp = 28
internal const val MusicSeekTrackDp = 4
internal const val MusicSeekThumbDp = 12

/**
 * The position under a finger at [x] pixels on a seek track [widthPx] wide, over a track of [durationMs]: the tapped or dragged-to fraction of the track's length, clamped
 * to the track. Shared by the media tile's lane and the player's bar, so a tap and a drag land on the same millisecond.
 */
internal fun seekTrackPositionAt(x: Float, widthPx: Int, durationMs: Long): Long =
    (x / widthPx.coerceAtLeast(1) * durationMs).toLong().coerceIn(0L, durationMs)

/** How often the playing bar redraws its position: the media tile's own 250 ms. */
private const val MusicSeekTickMs = 250L

/**
 * What the seek bar reads about one track. [revision] changes whenever the source's own data changed (a callback position, a state change), so the bar re-reads at once;
 * [key] is the track's identity, which resets any preview.
 */
internal class MusicSeekSource(
    val key: Any,
    val revision: Any,
    val durationMs: Long,
    val canSeek: Boolean,
    val advancing: Boolean,
    val positionNow: (nowElapsedRealtimeMs: Long) -> Long,
)

/** Your music's [YourMusicState] as a seek source, or null while the player has not read the track's length (no bar then). */
internal fun musicSeekSourceFor(yours: com.gruning.mimir.rewrite.core.audio.YourMusicState): MusicSeekSource? {
    val duration = yours.durationMs?.takeIf { it > 0L } ?: return null
    val position = yours.positionMs ?: return null
    return MusicSeekSource(
        // The player's own per-playing key (the track id and a count that moves at every change), not the title: two tracks with one title and one length are two keys.
        key = "your-music:${yours.trackKey ?: yours.title}",
        revision = Triple(position, yours.positionUpdatedAtElapsedRealtimeMs, yours.isPlaying),
        durationMs = duration,
        canSeek = yours.canSeek,
        advancing = yours.isPlaying && yours.positionAdvancing,
        positionNow = { now ->
            val moved = if (yours.isPlaying && yours.positionAdvancing) (now - yours.positionUpdatedAtElapsedRealtimeMs).coerceAtLeast(0L) else 0L
            (position + moved).coerceIn(0L, duration)
        },
    )
}

/** Another app's session as a seek source, or null when its timing is unusable (no bar then). */
internal fun musicSeekSourceFor(track: CompanionMediaTrack): MusicSeekSource? {
    val timing = companionMediaTimingSnapshot(track.positionMs, track.durationMs) ?: return null
    return MusicSeekSource(
        key = track.sessionIdentity to track.presentationTrackIdentity,
        revision = listOf(track.positionMs, track.positionUpdateElapsedRealtimeMs, track.playbackState, track.playbackSpeed, track.durationMs),
        durationMs = timing.durationMs,
        canSeek = track.canSeek,
        advancing = track.playbackState == CompanionMediaPlaybackState.PLAYING,
        positionNow = { now -> companionMediaTimelinePositionNow(track, now).coerceIn(0L, timing.durationMs) },
    )
}

/**
 * The seek bar with its times: a thin track, a small round thumb, the elapsed time under the left end and the total under the right (m:ss, h:mm:ss over an hour). With no
 * [source] the same height is kept and nothing is drawn, so a track that has no position never moves what is around it. A bar whose track cannot be seeked is read-only
 * progress: no thumb and no gesture. [onSeek] is called once, on the release of a drag that moved the thumb.
 */
@Composable
internal fun MusicSeekBar(
    source: MusicSeekSource?,
    enabled: Boolean,
    seekGate: CompanionSeekGestureGate,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val shown = source != null
    val duration = source?.durationMs ?: 1L
    val latestSource by rememberUpdatedState(source)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnSeek by rememberUpdatedState(onSeek)
    var live by remember(source?.key) { mutableStateOf(source?.positionNow?.invoke(SystemClock.elapsedRealtime()) ?: 0L) }
    var preview by remember(source?.key) { mutableStateOf(live) }
    val latestLive by rememberUpdatedState(live)
    LaunchedEffectWhileLive(source?.key, source?.revision, source?.advancing, enabled) {
        while (true) {
            val current = latestSource ?: break
            val now = current.positionNow(SystemClock.elapsedRealtime())
            live = now
            if (!seekGate.active) preview = now
            if (!current.advancing || !latestEnabled) break
            delay(MusicSeekTickMs)
        }
    }
    DisposableEffect(source?.key, source?.canSeek, enabled) { onDispose { seekGate.finish() } }
    val seekable = source != null && source.canSeek
    val gesture = if (seekable) {
        Modifier.pointerInput(source?.key, duration) {
            // F13: the one slider grammar, [detectSliderTouch]: a tap seeks to the tapped fraction, a drag along the track previews and seeks once on the release, a swipe
            // across it seeks nothing. The DOWN is claimed in Initial so the page's swipe detectors (the workspace pan, the player's drag down) cannot start on it.
            var engaged = false
            try {
                detectSliderTouch(
                    onEngaged = {
                        engaged = true
                        seekGate.begin()
                    },
                    emitAt = { x -> preview = seekTrackPositionAt(x, size.width, duration) },
                    onEnd = {
                        if (engaged && latestEnabled && seekGate.active) latestOnSeek(preview)
                        engaged = false
                        preview = latestLive
                        seekGate.finish()
                    },
                    pass = PointerEventPass.Initial,
                    claimDown = true,
                    enabled = { latestEnabled },
                )
            } finally {
                preview = latestLive
                if (seekGate.active) seekGate.finish()
            }
        }
    } else {
        Modifier
    }
    val fraction = (preview.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth().then(if (shown) Modifier else Modifier.alpha(0f).clearAndSetSemantics { })) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(MusicSeekZoneDp.dp)
                .testTag(MusicSeekBarTag)
                .then(gesture)
                // Its own node in the merged tree, so a pressable around it never folds the bar's progress into itself.
                .semantics(mergeDescendants = true) {
                    contentDescription = if (seekable) "Seek playback" else "Playback progress"
                    if (shown) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (seekable) {
                        setProgress { target ->
                            if (!latestEnabled) return@setProgress false
                            val bounded = companionMediaSeekPosition((target.coerceIn(0f, 1f) * duration.toFloat()).toLong(), duration, canSeek = true)
                                ?: return@setProgress false
                            latestOnSeek(bounded)
                            true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MusicSeekTrackDp.dp)
                    .clip(RoundedCornerShape(MusicSeekTrackDp.dp / 2))
                    .background(palette.textMuted.copy(alpha = 0.28f)),
            ) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxSize().background(palette.accent))
            }
            if (seekable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = ((maxWidth - MusicSeekThumbDp.dp).coerceAtLeast(0.dp) * fraction))
                        .size(MusicSeekThumbDp.dp)
                        .clip(CircleShape)
                        .background(palette.accentBright),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MimirChromeText(
                if (shown) companionMediaFormatTime(preview) else "0:00",
                style = MimirTypeScale.SupportCompact,
                color = palette.textSecondary,
                maxLines = 1,
                modifier = Modifier.testTag(MusicSeekElapsedTag),
            )
            MimirChromeText(
                if (shown) companionMediaFormatTime(duration) else "0:00",
                style = MimirTypeScale.SupportCompact,
                color = palette.textSecondary,
                maxLines = 1,
                modifier = Modifier.testTag(MusicSeekTotalTag),
            )
        }
    }
}
