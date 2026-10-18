package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.MimirFrontendMusic
import com.gruning.mimir.rewrite.core.ui.LocalMimirGlanceClearance
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import kotlinx.coroutines.delay

/**
 * The song-title chip (spec 2026-09-30): about three seconds, no focus, never takes input. It enters and
 * leaves on [MimirMotion.OverlayMs], seated beside `HomeStatusPillHost` at the top end. Driven by the
 * engine's `trackStarted` cue and gated by `songTitlesEnabled`; a bed's loop seam never fires it, so the
 * chip names a CHANGE, not a lap. The `HomeAlphabetJumpIndicator` precedent: a new epoch supersedes the
 * old dismissal timer.
 */
internal const val MusicTitleChipHoldMs = 3_000L

internal data class MusicTitleNotice(val title: String, val source: String, val epoch: Long)

/** The chip's whole state, pure, so its timing is testable on a paused clock. */
internal class MusicTitleChipState {
    /** The notice on screen, or null while the chip is away (or fading out). */
    var notice by mutableStateOf<MusicTitleNotice?>(null)
        private set

    /** The last notice shown: the text the exit fade keeps, so a chip never blanks before it is gone. */
    var lastNotice by mutableStateOf<MusicTitleNotice?>(null)
        private set

    private var epoch = 0L

    /** Show [title]; [source] is what the track came from, kept with it so a chip can be told from a repeat. Returns the epoch a later [expire] must quote. */
    fun show(title: String, source: String): Long {
        epoch += 1L
        val next = MusicTitleNotice(title = title, source = source, epoch = epoch)
        notice = next
        lastNotice = next
        return epoch
    }

    /** Hide the chip, unless a newer track has already replaced [epoch]'s notice. */
    fun expire(epoch: Long) {
        if (notice?.epoch == epoch) notice = null
    }
}

/**
 * "♪ Title". The note is the chip's only glyph. The chip does not name the source ("Music on this device") or the artist: the source says what every track says, and ate the
 * room a long title needs on the glance row (sweep 10-09); the artist's tag is usually not read yet when a track starts, so a chip that named it would name it on some tracks only.
 * The source still tells a new chip from a repeat ([MusicTitleKey]).
 */
internal fun musicTitleChipText(title: String): String = "♪ $title"

/** What the chip announces: the pair that must CHANGE for a new chip. */
internal data class MusicTitleKey(val title: String, val source: String)

/**
 * The engine's `trackStarted` fires on every entry from silence (app start, a return from a game or the
 * background, a yield, a Skip, a bed change) and leaves the chattiness to the UI. The announcement contract: announce only a CHANGE of track. The first emission seen shows; afterwards a chip shows only
 * when (title, source) differs from the last announced, so a resume of the same bed, or a Skip on the single
 * bed, is silent (the Now playing row says it restarted), and a theme-bed change is not.
 */
internal fun musicTitleChipShouldAnnounce(lastAnnounced: MusicTitleKey?, next: MusicTitleKey): Boolean =
    lastAnnounced == null || lastAnnounced != next

/** The last (title, source) announced, kept for the PROCESS: a Home remount must not re-announce the same bed. */
internal object MusicTitleAnnounced {
    @Volatile
    var last: MusicTitleKey? = null
}

/** The least room worth a chip: "♪" and a few letters. Under it the chip stays away rather than show a stub (the Now playing row still names the track). */
internal val MusicTitleChipMinRoom: Dp = 72.dp

/**
 * The room the chip may take on the glance row, in px, or null when nothing needs keeping clear of ([clearRightPx] 0). The chip stands [gapPx] left of the status pill
 * ([pillLeftPx]); it ends [gapPx] right of the furthest control under the row ([clearRightPx], window px, see `MimirGlanceClearance`). Never negative.
 */
internal fun musicTitleChipRoomPx(pillLeftPx: Float, clearRightPx: Float, gapPx: Float): Int? =
    if (clearRightPx <= 0f) null else (pillLeftPx - gapPx - (clearRightPx + gapPx)).toInt().coerceAtLeast(0)

/**
 * Holds the chip to [room] px of width (a long title then ends in its ellipsis) and takes it away when that is under [MusicTitleChipMinRoom]. Read in the measure pass, so a control
 * that moves under the row re-measures the chip and nothing recomposes.
 */
private fun Modifier.musicTitleChipRoom(room: () -> Int?): Modifier = layout { measurable, constraints ->
    val roomPx = room()
    when {
        roomPx == null -> {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
        roomPx < MusicTitleChipMinRoom.roundToPx() -> layout(0, 0) {}
        else -> {
            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = minOf(constraints.maxWidth, roomPx)))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }
}

@Composable
internal fun MusicTitleChip(state: MusicTitleChipState, modifier: Modifier = Modifier, room: () -> Int? = { null }) {
    val notice = state.notice
    LaunchedEffect(notice?.epoch) {
        val live = notice ?: return@LaunchedEffect
        delay(MusicTitleChipHoldMs)
        state.expire(live.epoch)
    }
    val palette = LocalMimirPalette.current
    val shown = state.lastNotice
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
        exit = fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
        modifier = modifier.musicTitleChipRoom(room),
    ) {
        // The status pill's own material, so the two read as one row of glance chrome. Palette only.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(palette.surfaceRaised.copy(alpha = 0.92f))
                .padding(horizontal = MimirSpacing.md, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = musicTitleChipText(shown?.title.orEmpty()),
                style = MimirTypeScale.Pill,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The live host: one chip fed by the engine. Collecting only while song titles are on means a track that
 * starts with the switch off is never shown late when the switch comes back on (the flow has no replay), and
 * only a CHANGE of track is announced ([musicTitleChipShouldAnnounce]).
 */
@Composable
internal fun MusicTitleChipHost(
    modifier: Modifier = Modifier,
    room: () -> Int? = { null },
    state: MusicTitleChipState = remember { MusicTitleChipState() },
) {
    val enabled by AudioPreferences.songTitlesEnabled.collectAsState()
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        MimirFrontendMusic.trackStarted.collect { started ->
            val key = MusicTitleKey(title = started.title, source = musicSourceLabel(started.source))
            if (musicTitleChipShouldAnnounce(MusicTitleAnnounced.last, key)) {
                MusicTitleAnnounced.last = key
                state.show(title = key.title, source = key.source)
            }
        }
    }
    MusicTitleChip(state = state, modifier = modifier, room = room)
}

/**
 * The glance row at the top end of Home and the Library: the song-title chip, then the status pill. The chip is drawn over the screen, so it keeps off any control registered with
 * `mimirGlanceKeepClear` that stands in the row's band (the gamelist's Search pill: its label was under the chip whenever a track began, sweep 10-09): it gets the room between
 * that control and the status pill, a long title ending in an ellipsis, and gives way entirely when there is hardly any ([MusicTitleChipMinRoom]).
 */
@Composable
internal fun MusicTitleGlanceRow(modifier: Modifier = Modifier, chipState: MusicTitleChipState = remember { MusicTitleChipState() }) {
    val clearance = LocalMimirGlanceClearance.current
    var pill by remember { mutableStateOf<Rect?>(null) }
    val gapPx = with(LocalDensity.current) { MimirSpacing.sm.toPx() }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicTitleChipHost(
            state = chipState,
            room = {
                val bounds = pill
                if (clearance == null || bounds == null) {
                    null
                } else {
                    musicTitleChipRoomPx(bounds.left, clearance.clearRightPx(bounds.top, bounds.bottom), gapPx)
                }
            },
        )
        // F10 does not move the status pill : the M badge veiled over it is fine.
        HomeStatusPillHost(
            Modifier.onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                pill = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
            },
        )
    }
}
