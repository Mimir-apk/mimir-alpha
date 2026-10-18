package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/** What stands between two segments of a controller hint line: the factory legend's own " · ". */
const val MimirHintLineSeparator = " · "

/**
 * One piece of a hint line: a segment's words, and whether a separator stands before it. The separator belongs to the piece after it, so the
 * words and their dot move and fade as one thing.
 */
internal data class MimirHintUnit(val words: String, val separated: Boolean)

/** A line's pieces: split on [MimirHintLineSeparator], blank segments dropped, every piece after the first carrying its separator. */
internal fun mimirHintUnits(line: String): List<MimirHintUnit> = line
    .split(MimirHintLineSeparator.trim())
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .mapIndexed { index, words -> MimirHintUnit(words, separated = index > 0) }

/** Where a piece stands (x, in px from the line's start) and how strongly it shows. */
internal data class MimirHintPose(val x: Float, val alpha: Float)

/** How far each of the swap's three steps has run, 0..1: the leaving pieces fade out, the staying pieces glide, the arriving pieces fade in. */
internal data class MimirHintPhases(val leave: Float, val glide: Float, val arrive: Float)

/** Which of the swap's three steps a change has anything to do in. A step with nothing to do is skipped, not waited out. */
internal data class MimirHintPlan(val leaves: Boolean, val glides: Boolean, val arrives: Boolean) {
    val any: Boolean get() = leaves || glides || arrives
}

internal fun mimirHintPlan(from: Map<MimirHintUnit, MimirHintPose>, to: List<MimirHintUnit>): MimirHintPlan {
    val leaves = from.any { (unit, pose) -> unit !in to && pose.alpha > 0f }
    val arrives = to.any { unit -> from[unit]?.let { it.alpha < 1f } ?: true }
    val stays = to.any { it in from }
    return MimirHintPlan(leaves = leaves, glides = stays && (leaves || arrives), arrives = arrives)
}

/**
 * One piece's pose at [phases]. A piece in both lines ([from] and [toX] both set) only glides from where it stood to where it stands, at the
 * alpha it had (1, unless a change caught it mid-fade, when it rises with the arrivals). A piece only in the old line holds its place and fades
 * out. A piece only in the new line stands where it will and fades in. Null: the piece is in neither.
 */
internal fun mimirHintPose(from: MimirHintPose?, toX: Float?, phases: MimirHintPhases): MimirHintPose? = when {
    from != null && toX != null -> MimirHintPose(lerp(from.x, toX, phases.glide), lerp(from.alpha, 1f, phases.arrive))
    from != null -> MimirHintPose(from.x, from.alpha * (1f - phases.leave))
    toX != null -> MimirHintPose(toX, phases.arrive)
    else -> null
}

/** The x of each piece of a line of [widths], the whole line centred in [space]; never left of the start. */
internal fun mimirHintCentredXs(widths: List<Int>, space: Int): List<Float> {
    var x = ((space - widths.sum()) / 2f).coerceAtLeast(0f)
    return widths.map { width -> x.also { x += width } }
}

/** The x of each piece of a line of [widths], laid end to end from the start of [space]: the start-aligned counterpart of [mimirHintCentredXs]. */
internal fun mimirHintStartXs(widths: List<Int>): List<Float> {
    var x = 0f
    return widths.map { width -> x.also { x += width } }
}

private class MimirHintFlight(
    /** Where each piece stood, and how strongly it showed, when the line last changed; empty for a line that is simply there. */
    val from: Map<MimirHintUnit, MimirHintPose>,
    val to: List<MimirHintUnit>,
) {
    val plan = mimirHintPlan(from, to)
}

/**
 * A controller hint line whose pieces change one at a time: the pieces both lines share never fade, dissolve or draw twice, and no two different
 * pieces are ever drawn over one another.
 *
 * On Thor, the Tome library's footer, moving focus between the Home Shelf tome ("A OPEN · B BACK") and a custom one
 * ("A OPEN · X UNPIN · Y EDIT · B BACK") cross-faded the WHOLE line ([MimirHintSwap]). The words both lines share stand at different x in the two,
 * so the video showed "A OPEN · A OPEN · X UNPI · B BACK", "A OPEN · B BACK · B BACK" and "X UNPIN" printed over "Y EDIT".
 *
 * [line] is the whole line; it is split on [MimirHintLineSeparator] into pieces, each drawn by [segment] (its words) and, after the first, led by
 * [separator]. The change is keyed on the line's content, as [MimirHintSwap]'s is: the same line again animates nothing. A change runs in order,
 * each step on [durationMs] (the hint clock, [MimirMotion.OverlayMs]) and [MimirMotion.Easing], and a step with nothing to do is skipped:
 * 1. the pieces that left fade out where they stood (alpha only);
 * 2. the pieces both lines share glide, at full alpha, to where the new line centres them;
 * 3. the pieces that arrived fade in where they now stand.
 * The order is what keeps pieces apart: a leaving piece is gone before anything moves into its place, and an arriving piece appears only once
 * the pieces beside it are where they will stay. A change mid-swap starts from the poses on screen; nothing queues. The first composition is
 * already settled, and under reduced motion a change is instant.
 *
 * The line is centred, or starts at its leading edge when [centred] is false, as in the file manager
 * footer. It fills the width either way, so changes never move its origin; pieces that cannot all fit share the
 * width in proportion and ellipsize as the segment does. Pieces that are leaving are cleared from the semantics. Never place this inside a clip
 * narrower than the line.
 */
@Composable
fun MimirSegmentedHintSwap(
    line: String,
    modifier: Modifier = Modifier,
    durationMs: Int = MimirMotion.OverlayMs,
    centred: Boolean = true,
    separator: @Composable () -> Unit,
    segment: @Composable (words: String) -> Unit,
) {
    val reducedMotion = mimirReducedMotion()
    // Draw-phase snapshot, not Compose state: the pose each piece was last painted at, so a change mid-swap leaves from where the piece is.
    val drawn = remember { HashMap<MimirHintUnit, MimirHintPose>() }
    val target = remember(line) { mimirHintUnits(line) }
    val flight = remember(line) {
        val from: Map<MimirHintUnit, MimirHintPose> = if (reducedMotion) emptyMap() else drawn.filterValues { it.alpha > 0f }
        MimirHintFlight(from, target)
    }
    var finished by remember { mutableStateOf<MimirHintFlight?>(null) }
    val leave = remember(flight) { Animatable(if (flight.plan.leaves) 0f else 1f) }
    val glide = remember(flight) { Animatable(if (flight.plan.glides) 0f else 1f) }
    val arrive = remember(flight) { Animatable(if (flight.plan.arrives) 0f else 1f) }
    LaunchedEffect(flight) {
        if (!flight.plan.any) return@LaunchedEffect
        if (flight.plan.leaves) leave.animateTo(1f, tween(durationMs, easing = MimirMotion.Easing))
        if (flight.plan.glides) glide.animateTo(1f, tween(durationMs, easing = MimirMotion.Easing))
        if (flight.plan.arrives) arrive.animateTo(1f, tween(durationMs, easing = MimirMotion.Easing))
        finished = flight
    }
    val leaving = if (finished === flight) emptyList() else flight.from.keys.filter { it !in target }

    Layout(
        content = {
            (target + leaving).forEach { unit ->
                key(unit) {
                    DisposableEffect(unit) { onDispose { drawn.remove(unit) } }
                    Row(
                        modifier = Modifier
                            .layoutId(unit)
                            .then(if (unit in target) Modifier else Modifier.clearAndSetSemantics {}),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (unit.separated) Box(Modifier.clearAndSetSemantics {}) { separator() }
                        segment(unit.words)
                    }
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val byUnit = measurables.associateBy { it.layoutId as MimirHintUnit }
        val natural = target.map { byUnit.getValue(it).maxIntrinsicWidth(Constraints.Infinity) }
        val space = if (constraints.hasBoundedWidth) constraints.maxWidth else natural.sum()
        val squeeze = if (natural.sum() > space) space.toFloat() / natural.sum() else 1f
        val placeables = HashMap<MimirHintUnit, Placeable>()
        target.forEachIndexed { index, unit ->
            val limit = (natural[index] * squeeze).toInt()
            placeables[unit] = byUnit.getValue(unit).measure(Constraints(maxWidth = limit))
        }
        leaving.forEach { unit -> placeables[unit] = byUnit.getValue(unit).measure(Constraints(maxWidth = space)) }
        val widths = target.map { placeables.getValue(it).width }
        val toXs = target.zip(if (centred) mimirHintCentredXs(widths, space) else mimirHintStartXs(widths)).toMap()
        val height = placeables.values.maxOfOrNull { it.height } ?: 0
        layout(space, height) {
            // The poses are read here, in placement: a frame of the swap re-places four small pieces and measures nothing. The offset is the piece's own
            // (not a layer translation), so a piece at a fraction of its alpha is composited over its own bounds and never cut.
            val phases = MimirHintPhases(leave.value, glide.value, arrive.value)
            placeables.forEach { (unit, placeable) ->
                val pose = mimirHintPose(flight.from[unit], toXs[unit], phases) ?: return@forEach
                drawn[unit] = pose
                placeable.placeWithLayer(pose.x.roundToInt(), (height - placeable.height) / 2) {
                    alpha = pose.alpha
                    // Alpha per drawing instruction, no offscreen buffer: the pieces do not overlap, so nothing shows through anything.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
            }
        }
    }
}
