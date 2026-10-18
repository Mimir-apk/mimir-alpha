#!/usr/bin/env python3
"""Generate MimirIconArt.kt -- the Compose face of the STAVE icon set, from icons.json.

The VectorDrawables (generate_drawables.py) are single-tint assets for XML call sites.
Compose gets this instead, because the mark has TWO inks: the outline takes the theme
accent and the CELL takes accentBright / danger / a dimmed bronze depending on state,
and one `android:tint` cannot express that.

Regenerate with:  python3 tools/mimir-icon-set/generate_compose.py
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.join(
    ROOT, '..', '..', 'core-ui', 'src', 'main', 'kotlin', 'com', 'gruning', 'mimir',
    'rewrite', 'core', 'ui', 'MimirIconArt.kt'))
SRC = json.load(open(os.path.join(ROOT, 'icons.json')))

DASH_ON, DASH_OFF = 3.0, 2.4

#: The only commands the STAVE set may use. Both generators parse exactly this alphabet.
STRAIGHT_COMMANDS = set('MLHVZmlhvz')


def assert_straight(d, where):
    """Reject any curve/arc command, loudly, before it can do damage.

    This is not a style preference, it is the parsers' actual limit. `_subpaths` matches commands
    with `[MLHVZmlhvz]`, so an `A` (or `C`/`Q`/`S`/`T`) letter is NOT recognised as a command and is
    silently dropped -- while its numeric parameters are still consumed as coordinates of whichever
    command preceded it. Nothing raises. The consequences measured on this set:

      * `M9 11 V8 A3 3 0 0 1 15 8 V11` (LOCKED's old shackle) parsed as a degenerate zig-zag along
        x=9, the arc gone.
      * THERMAL's old two-arc bulb parsed as EIGHT subpaths, most of them single points.
      * `dash()` then failed to terminate on that geometry: the stale `carry` left by a zero-length
        segment drifts 1 ULP above DASH_OFF, `seg` goes tiny-negative, and `t` walks backwards
        forever.

    So a curve in icons.json is a silent corruption at best and a hung build at worst. Fail here.
    """
    bad = sorted({c for c in d if c.isalpha() and c not in STRAIGHT_COMMANDS})
    if bad:
        raise SystemExit(
            'icons.json: %s uses path command(s) %s.\n'
            'The STAVE set is straight-edged by design and both generators parse only M/L/H/V/Z.\n'
            'Express the form with straight segments (the set already uses diamonds and facets).\n'
            '  %s' % (where, '/'.join(bad), d))


def validate(src):
    """Check every field of every mark before a single line is generated."""
    for grp in ('icons', 'dock'):
        for m in src[grp]:
            for f in ('stroke', 'cell', 'dashed'):
                assert_straight(m[f], '%s/%s.%s' % (grp, m['id'], f))


def _subpaths(d):
    seq = []
    for c, n in re.findall(r'([MLHVZmlhvz])|(-?\d*\.?\d+)', d):
        seq.append(('c', c) if c else ('n', float(n)))
    subs, cur, x, y, start, cmd, i = [], [], 0.0, 0.0, None, None, 0
    while i < len(seq):
        kind, val = seq[i]
        if kind == 'c':
            cmd = val
            i += 1
            if cmd in 'Zz':
                if cur and start:
                    cur.append(start)
                if cur:
                    subs.append(cur)
                cur, start = [], None
            continue
        need = {'M': 2, 'L': 2, 'H': 1, 'V': 1}[cmd.upper()]
        args = [seq[i + k][1] for k in range(need)]
        i += need
        u, rel = cmd.upper(), cmd.islower()
        if u == 'M':
            x, y = (x + args[0], y + args[1]) if rel else (args[0], args[1])
            if cur:
                subs.append(cur)
            cur, start = [(x, y)], (x, y)
        else:
            if u == 'L':
                x, y = (x + args[0], y + args[1]) if rel else (args[0], args[1])
            elif u == 'H':
                x = x + args[0] if rel else args[0]
            else:
                y = y + args[0] if rel else args[0]
            cur.append((x, y))
    if cur:
        subs.append(cur)
    return subs


def dash(d):
    out = []
    for sub in _subpaths(d):
        carry, on = 0.0, True
        for (x0, y0), (x1, y1) in zip(sub, sub[1:]):
            dx, dy = x1 - x0, y1 - y0
            ln = (dx * dx + dy * dy) ** 0.5
            if ln == 0:
                continue
            ux, uy, t = dx / ln, dy / ln, 0.0
            while t < ln:
                seg = (DASH_ON if on else DASH_OFF) - carry
                end = min(t + seg, ln)
                if seg != 0.0 and end <= t:
                    # `t` cannot advance this pass, so without this the loop never terminates:
                    #   seg < 0  -- carry drifted 1 ULP past the dash length, so `end` lands BELOW
                    #              `t` and the walk goes backwards;
                    #   seg > 0  -- but so small that `t + seg == t`, so the step is absorbed.
                    # Either way, close the dash out and flip. `seg == 0.0` is deliberately NOT
                    # caught: that case already flips on its own, and catching it would drop the
                    # zero-length (and therefore inkless, caps are butt) dash the current output
                    # carries at MISSING's bottom-right corner -- a needless output change.
                    on, carry = not on, 0.0
                    continue
                if on:
                    out.append('M%s %s L%s %s' % (round(x0 + ux * t, 3), round(y0 + uy * t, 3),
                                                 round(x0 + ux * end, 3), round(y0 + uy * end, 3)))
                if end - t >= seg:
                    on, carry = not on, 0.0
                else:
                    carry += end - t
                t = end
    return ' '.join(out)


def name(prefix, i):
    return (prefix + re.sub(r'[^A-Za-z0-9]+', '_', i)).upper()


HEADER = '''package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * MIMIR-REWRITE :core-ui — the STAVE icon set, Compose face. GENERATED, do not hand-edit.
 *
 * Source of truth: `tools/mimir-icon-set/icons.json`. Regenerate both faces after any edit:
 *   python3 tools/mimir-icon-set/generate_compose.py      → this file
 *   python3 tools/mimir-icon-set/generate_drawables.py    → res/drawable/mimir_icon_*.xml
 *
 * WHY TWO FACES. The drawables are single-tint assets for XML call sites. A mark has TWO inks:
 * the OUTLINE takes the theme accent and the CELL — the one filled element per mark — takes
 * accentBright on focus, danger on failure, or a dim bronze at rest. One `android:tint` cannot
 * express that, so anything that changes state uses [MimirIconMark] and reads both colours from
 * [LocalMimirPalette]. Static XML chrome may keep the drawable.
 *
 * PALETTE. No hex in this file. Callers pass [ink] and [cellInk] from the palette; the defaults
 * are the palette's own accent and accentBright.
 *
 * SIZE. Marks are authored on a 24-unit grid with a 20-unit live area. Below 24.dp the geometry
 * is unchanged but the stroke is widened by [MimirIconArt.SmallStrokeBoost] so the silhouette
 * survives — the design law is that detail is DELETED, not scaled, and at this size the set has no
 * detail layer left to delete. The boost is decided from the edge the mark is ACTUALLY drawn at,
 * not from a parameter, so a caller that sizes the mark through [Modifier] still gets it.
 */
object MimirIconArt {
    /** Authoring grid. Every path below is expressed in this box. */
    const val ViewportUnits: Float = 24f

    /** Stroke of a mark's outline, in authoring units. */
    const val StrokeUnits: Float = 1.5f

    /** EMPTY's cell is an unfilled recess; its wall is thinner than the outline it sits inside. */
    const val HollowCellStrokeUnits: Float = 1.2f

    /** Below this the outline thickens so the mark holds together on a handheld at arm's length. */
    val SmallSizeThreshold: Dp = 24.dp
    const val SmallStrokeBoost: Float = 1.18f

    /** The outline never takes full ink; the cell is what the eye lands on. */
    const val OutlineAlpha: Float = 0.82f
}

/**
 * One mark of the STAVE set.
 *
 * [outline] and [dashed] are stroked; [cell] is the single filled element that carries state.
 * [hollowCell] marks the one state — EMPTY — whose cell is an unfilled recess.
 */
enum class MimirIcon(
    internal val outline: String,
    internal val cell: String,
    internal val dashed: String,
    internal val hollowCell: Boolean,
    /** The accessible name. Never rendered; always announced. */
    val label: String,
) {
'''

FOOTER = '''    ;

    /**
     * The parsed geometry, built ONCE per entry and shared by every draw.
     *
     * [PathParser] allocates three [Path]s per call; the dock rail draws six marks per frame, so
     * parsing at draw time put that on the hot path. The enum entry is the only object that lives
     * as long as the set does, so the cache belongs here. A [Path] is never mutated after parsing.
     */
    internal val paths: MimirIconPaths by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MimirIconPaths(parse(outline), parse(cell), parse(dashed))
    }
}

internal class MimirIconPaths(val outline: Path?, val cell: Path?, val dashed: Path?)

private fun parse(d: String): Path? =
    if (d.isEmpty()) null else PathParser().parsePathString(d).toPath()

/**
 * Draw one [MimirIcon] at [size], outline in [ink] and cell in [cellInk].
 *
 * Both colours default to the active palette, so a call site that wants the ordinary appearance
 * passes neither. A focused row passes `cellInk = palette.accentBright`; a failed one passes
 * `palette.danger`. The outline never changes hue — only the cell's temperature does.
 *
 * [size] is applied to the mark itself, so `MimirIconMark(MimirIcon.LIBRARY)` renders at 24.dp
 * inside any layout. A caller that puts its own size in [modifier] wins — that size is outermost —
 * and the stroke boost still tracks it, because the boost is read from the drawn edge.
 */
@Composable
fun MimirIconMark(
    icon: MimirIcon,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    ink: Color = LocalMimirPalette.current.accent,
    cellInk: Color = LocalMimirPalette.current.accentBright,
    contentDescription: String? = icon.label,
) {
    Canvas(
        modifier = modifier
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription }
            )
            .size(size)
    ) {
        drawMimirIconPaths(icon.paths, ink, cellInk, icon.hollowCell)
    }
}

/** The draw-only half, for call sites that already own a [DrawScope] (rows, coins, chips). */
internal fun DrawScope.drawMimirIcon(
    icon: MimirIcon,
    ink: Color,
    cellInk: Color,
    strokeBoost: Float = 1f,
) = drawMimirIconPaths(icon.paths, ink, cellInk, icon.hollowCell, strokeBoost)

private fun DrawScope.drawMimirIconPaths(
    paths: MimirIconPaths,
    ink: Color,
    cellInk: Color,
    hollowCell: Boolean,
    strokeBoost: Float = 1f,
) {
    val edge = min(size.width, size.height)
    if (edge <= 0f) return
    val k = edge / MimirIconArt.ViewportUnits
    // The boost is a property of how small the mark ACTUALLY landed, not of what the caller asked
    // for, so it survives a size that arrived through the modifier or through parent constraints.
    // Compared in PIXELS with a half-pixel slack: layout rounds a dp size to a whole pixel, so at
    // some densities a mark asked for at exactly 24.dp lands a hair under it and would otherwise
    // pick up the boost it is not supposed to have.
    val boost = strokeBoost *
        if (edge < MimirIconArt.SmallSizeThreshold.toPx() - 0.5f) MimirIconArt.SmallStrokeBoost else 1f
    val outlineInk = ink.copy(alpha = MimirIconArt.OutlineAlpha)
    val stroke = Stroke(
        width = MimirIconArt.StrokeUnits * boost,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
    // Centre the 24-unit box in whatever box the caller gave us, then work in authoring units.
    translate(left = (size.width - edge) / 2f, top = (size.height - edge) / 2f) {
        scale(scaleX = k, scaleY = k, pivot = Offset.Zero) {
            paths.dashed?.let { drawPath(it, color = outlineInk, style = stroke) }
            paths.outline?.let { drawPath(it, color = outlineInk, style = stroke) }
            paths.cell?.let {
                if (hollowCell) {
                    drawPath(
                        it,
                        color = outlineInk,
                        style = Stroke(
                            width = MimirIconArt.HollowCellStrokeUnits * boost,
                            cap = StrokeCap.Butt,
                            join = StrokeJoin.Miter,
                        ),
                    )
                } else {
                    drawPath(it, color = cellInk, style = Fill)
                }
            }
        }
    }
}
'''


def esc(s):
    return s.replace('\\', '\\\\').replace('"', '\\"')


def main():
    validate(SRC)
    entries = []
    for m in SRC['icons']:
        entries.append((name('', m['id']), m))
    for m in SRC['dock']:
        entries.append((name('DOCK_', m['id']), m))

    body = []
    for i, (n, m) in enumerate(entries):
        # Always ',' -- FOOTER closes the entry list with ';' before the enum's own members.
        tail = ','
        body.append(
            '    /** %s */\n'
            '    %s(\n'
            '        outline = "%s",\n'
            '        cell = "%s",\n'
            '        dashed = "%s",\n'
            '        hollowCell = %s,\n'
            '        label = "%s",\n'
            '    )%s\n' % (
                m['label'], n, esc(m['stroke']), esc(m['cell']),
                esc(dash(m['dashed']) if m['dashed'] else ''),
                'true' if m['hollowCell'] else 'false', esc(m['label']), tail))

    open(OUT, 'w').write(HEADER + ''.join(body) + FOOTER)
    print('wrote %s (%d marks)' % (OUT, len(entries)))


if __name__ == '__main__':
    sys.exit(main())
