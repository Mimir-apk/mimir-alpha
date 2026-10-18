package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirFadeMount
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.rememberMimirFadeMount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Where in its band the toast stands: centred on the band's own height, or with its top on the band's top (a band of one text line, the toast reaching down over text). */
internal enum class AchievementToastVertical { Center, Top }

/** A band, in the coordinates of whoever asks: the control-free strip a page offers the toast. */
internal class AchievementToastBand(val rect: Rect, val vertical: AchievementToastVertical)

/**
 * The control-free band each Bottom page offers the toast (it must never cover a button): Now Playing's header between the Back chip and the clock, the
 * Activity page's header between its badge and the Trackpad plate, the achievements hub's count row (text only). A page reports its band while it is composed
 * ([achievementToastBand]) in window coordinates; the toast host reads the newest band whose centre is on the screen (a page parked off the canvas is not the page showing),
 * and with none falls back to the header's centre.
 */
internal object AchievementToastBands {
    private class Entry(val rect: Rect, val vertical: AchievementToastVertical, val serial: Long)

    private val entries = mutableStateMapOf<Any, Entry>()
    private var serial = 0L

    fun report(owner: Any, rect: Rect, vertical: AchievementToastVertical) {
        if (rect.width <= 0f || rect.height <= 0f) return
        val held = entries[owner]
        if (held != null && held.rect == rect && held.vertical == vertical) return
        serial += 1L
        entries[owner] = Entry(rect, vertical, serial)
    }

    fun release(owner: Any) {
        entries.remove(owner)
    }

    /** The newest band whose centre is inside [viewport] (window coordinates), or null. */
    fun current(viewport: Rect): AchievementToastBand? =
        entries.values
            .filter { viewport.contains(it.rect.center) }
            .maxByOrNull { it.serial }
            ?.let { AchievementToastBand(it.rect, it.vertical) }

    fun resetForTest() {
        entries.clear()
        serial = 0L
    }
}

/** This node's bounds are a band the toast may stand in while its page shows. Released when the node leaves composition. */
@Composable
internal fun Modifier.achievementToastBand(vertical: AchievementToastVertical = AchievementToastVertical.Center): Modifier {
    val owner = remember { Any() }
    DisposableEffect(owner) { onDispose { AchievementToastBands.release(owner) } }
    return onGloballyPositioned { AchievementToastBands.report(owner, it.boundsInWindow(), vertical) }
}

/**
 * A band that is the gap BETWEEN two nodes of one row (Now Playing's header: after the Back chip, before the clock), as tall as the row. [leading], [trailing] and [row]
 * go on the node before the gap, the node after it and the row; the band is reported once all three are placed and the gap has some width.
 */
internal class AchievementToastBandBetween(private val owner: Any, private val vertical: AchievementToastVertical) {
    private var leadingBox: Rect? = null
    private var trailingBox: Rect? = null
    private var rowBox: Rect? = null

    private fun update() {
        val before = leadingBox ?: return
        val after = trailingBox ?: return
        val whole = rowBox ?: return
        if (after.left > before.right) AchievementToastBands.report(owner, Rect(before.right, whole.top, after.left, whole.bottom), vertical)
    }

    val leading: Modifier = Modifier.onGloballyPositioned { leadingBox = it.boundsInWindow(); update() }
    val trailing: Modifier = Modifier.onGloballyPositioned { trailingBox = it.boundsInWindow(); update() }
    val row: Modifier = Modifier.onGloballyPositioned { rowBox = it.boundsInWindow(); update() }

    fun release() = AchievementToastBands.release(owner)
}

@Composable
internal fun rememberAchievementToastBandBetween(vertical: AchievementToastVertical = AchievementToastVertical.Center): AchievementToastBandBetween {
    val between = remember { AchievementToastBandBetween(Any(), vertical) }
    DisposableEffect(between) { onDispose { between.release() } }
    return between
}

/** The band with no page to offer one: the header's centre (the room between a header's Back chip and its trailing content), from the top inset down one control row. */
private fun fallbackToastBand(size: IntSize, density: Float): AchievementToastBand {
    val width = FallbackBandWidthDp * density
    val left = (size.width - width) / 2f
    return AchievementToastBand(
        rect = Rect(left, ActivityPageTopDp * density, left + width, (ActivityPageTopDp + FallbackBandHeightDp) * density),
        vertical = AchievementToastVertical.Center,
    )
}

private const val FallbackBandWidthDp = 260f
private const val FallbackBandHeightDp = 48f

/**
 * The Bottom's "Achievement earned" toast: the real host, wired to [AchievementToasts] and to the process's badge art. The Presentation draws it with this one call over the
 * whole window; it stands in the band the page showing offers ([AchievementToastBands]) and never over a control. [toasts], [onDone] and [badgeSource] are the seams a test
 * passes; the product passes none.
 */
@Composable
internal fun AchievementToastHost(
    modifier: Modifier = Modifier,
    toasts: StateFlow<AchievementToast?> = AchievementToasts.current,
    onDone: (key: Long) -> Unit = AchievementToasts::dismiss,
    badgeSource: ActivityBadgeSource? = null,
) {
    val toast by toasts.collectAsState()
    val context = LocalContext.current
    // The process's badge art, unless a test hands in its own (then the real store is never opened).
    val source = badgeSource ?: remember(context) { StoreActivityBadgeSource(CompanionActivityIcons.storeFor(context)) }
    val density = LocalDensity.current.density
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier.fillMaxSize().onGloballyPositioned {
            origin = it.positionInWindow()
            size = it.size
        },
    ) {
        val reported = AchievementToastBands.current(Rect(origin, Size(size.width.toFloat(), size.height.toFloat())))
        val band = reported?.let { AchievementToastBand(it.rect.translate(-origin), it.vertical) } ?: fallbackToastBand(size, density)
        AchievementToastLayer(toast = toast, badgeSource = source, onDone = onDone, band = band)
    }
}

/**
 * The toast itself: a plate with the badge on its left and two short lines (the eyebrow, then the unlock's title with its points), up for [ACHIEVEMENT_TOAST_MS] and then
 * handed to [onDone]. It stands in [band] (the host's coordinates): as wide as its words, to the band's width and [AchievementToastMaxWidth], centred on the band's middle,
 * and vertically as the band says. It is drawn and nothing else: no pointer input (a touch goes through it), no focus, one live-region phrase for a screen reader.
 * It arrives and leaves on the house overlay clock ([rememberMimirFadeMount]): the last toast is kept for the fade out, so the plate is never cut off the screen, and
 * nothing is composed once the fade has finished and there is no toast.
 */
@Composable
internal fun AchievementToastLayer(
    toast: AchievementToast?,
    badgeSource: ActivityBadgeSource,
    onDone: (key: Long) -> Unit,
    band: AchievementToastBand,
    modifier: Modifier = Modifier,
) {
    val latestDone by rememberUpdatedState(onDone)
    LaunchedEffect(toast?.key) {
        if (toast != null) {
            delay(ACHIEVEMENT_TOAST_MS)
            latestDone(toast.key)
        }
    }
    // The last toast outlives its showing for the length of the fade out (an idempotent holder, not state: it is read only in this composition).
    val last = remember { arrayOfNulls<AchievementToast>(1) }
    if (toast != null) last[0] = toast
    val shown = toast ?: last[0]
    val fade = rememberMimirFadeMount(visible = toast != null, reducedMotion = mimirReducedMotion())
    Layout(
        content = { if (shown != null && (toast != null || fade.mounted)) AchievementToastPlate(shown, badgeSource, fade) },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        // The band in whole pixels, rounded INWARD, so a plate that fits the band is never a pixel outside it.
        val bandLeft = kotlin.math.ceil(band.rect.left).toInt()
        val bandRight = kotlin.math.floor(band.rect.right).toInt()
        val plate = measurables.firstOrNull()?.measure(
            Constraints(maxWidth = minOf(AchievementToastMaxWidth.roundToPx(), bandRight - bandLeft).coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (plate != null) {
                val x = Math.round(band.rect.center.x - plate.width / 2f)
                    .coerceIn(bandLeft, maxOf(bandLeft, bandRight - plate.width))
                    .coerceIn(0, (constraints.maxWidth - plate.width).coerceAtLeast(0))
                val y = when (band.vertical) {
                    AchievementToastVertical.Center -> band.rect.center.y - plate.height / 2f
                    AchievementToastVertical.Top -> band.rect.top
                }.toInt().coerceIn(0, (constraints.maxHeight - plate.height).coerceAtLeast(0))
                plate.place(x, y)
            }
        }
    }
}

@Composable
private fun AchievementToastPlate(toast: AchievementToast, badgeSource: ActivityBadgeSource, fade: MimirFadeMount) {
    val palette = LocalMimirPalette.current
    val description = achievementToastDescription(toast)
    Row(
        Modifier
            // The fade is read in the layer, never in composition.
            .graphicsLayer { alpha = fade.alpha }
            // One phrase for a screen reader, in place of the lines read one by one. Before the plate's own drawing and padding, so its bounds are the whole plate.
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
                testTag = AchievementToastTag
            }
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(palette.surfaceBase.copy(alpha = 0.96f))
            .padding(horizontal = MimirSpacing.sm, vertical = MimirSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AchievementToastBadge(path = toast.badgePath, source = badgeSource)
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = achievementToastHeadline(toast),
                style = MimirTypeScale.MicroStatus,
                color = palette.accentBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    text = achievementToastTitle(toast),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MimirTypeScale.SupportCompact,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = " · " + achievementToastPoints(toast),
                    style = MimirTypeScale.SupportCompact,
                    color = palette.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** The badge's pixel art, held for as long as the toast is up (the lease is given back when it leaves), or the plain plate while it loads or when there is none. */
@Composable
private fun AchievementToastBadge(path: String?, source: ActivityBadgeSource) {
    val palette = LocalMimirPalette.current
    // The file when it is already staged (a stat, no fetch): the art starts painting on the first frame, while the lease below, which only keeps the file from being
    // evicted, is still on its way. The plate fades in as a whole, so the art is never a pop at full alpha beside it.
    val staged = remember(path, source) { path?.let(source::staged) }
    // Runtime 1.7.3 lint misses the inherited value setter assigned below.
    @SuppressLint("ProduceStateDoesNotAssignValue")
    val lease by produceState<ActivityIconLease?>(null, path, source) {
        var made: ActivityIconLease? = null
        try {
            if (path != null) {
                withContext(Dispatchers.IO) {
                    try {
                        made = source.acquire(path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Exception) {
                        made = null
                    }
                }
            }
            value = made
            awaitDispose { }
        } finally {
            made?.release()
        }
    }
    val held = lease
    Box(
        Modifier
            .size(AchievementToastBadgeSize)
            .clip(RoundedCornerShape(ActivityBadgeCorner))
            .background(palette.surfaceRaised.copy(alpha = 0.8f))
            .testTag(AchievementToastBadgeTag),
        contentAlignment = Alignment.Center,
    ) {
        (held?.file ?: staged)?.let { BadgeArt(it) }
    }
}
