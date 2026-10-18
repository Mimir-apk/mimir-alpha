#!/usr/bin/env python3
"""Generate Mimir's STAVE icon set as Android VectorDrawables from icons.json.

The source of truth is icons.json (24x24 geometry).
Regenerate with:  python3 tools/mimir-icon-set/generate_drawables.py

Every drawable is a single-tint asset: the OUTLINE strokes at 0.82 alpha and the
CELL filled at 1.0, so the mark keeps its hierarchy under ONE tint applied by the
CALLER -- `Icon(painterResource(...), tint = ...)` from `LocalMimirPalette`.

The generated `<vector>` carries NO `android:tint`. It used to emit
`?attr/colorControlNormal`, which is an AppCompat/Material-VIEWS theme attribute:
`core-ui` is Compose-only and declares no such dependency, so nothing in this project's
resource namespace defines it. AAPT compiles every resource in a module whether or not
any code references it, so all 56 files failed `:app:processDebugResources` with
`resource attr/colorControlNormal not found` merely by existing . It also contradicted the per-file header two lines below it, which
already said the tint belongs at the call site.
Two-colour rendering (outline in the theme accent, cell in accentBright/danger)
is the Compose path -- see MimirIcons.kt.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.join(ROOT, '..', '..', 'core-ui', 'src', 'main', 'res', 'drawable'))
SRC = json.load(open(os.path.join(ROOT, 'icons.json')))

STROKE_W = 1.5
STROKE_ALPHA = 0.82
DASH_ON, DASH_OFF = 3.0, 2.4

#: The only commands the STAVE set may use. Kept identical to generate_compose.py.
STRAIGHT_COMMANDS = set('MLHVZmlhvz')


def assert_straight(d, where):
    """Reject any curve/arc command, loudly. See generate_compose.assert_straight for the detail.

    `_pts` below matches commands with `[MLHVZmlhvz]`, so an `A`/`C`/`Q`/`S`/`T` letter is dropped
    while its numeric parameters are still eaten as coordinates of the preceding command -- silent
    corruption, and `dash()` can then spin forever on the result.
    """
    bad = sorted({c for c in d if c.isalpha() and c not in STRAIGHT_COMMANDS})
    if bad:
        raise SystemExit(
            'icons.json: %s uses path command(s) %s.\n'
            'The STAVE set is straight-edged by design and both generators parse only M/L/H/V/Z.\n'
            '  %s' % (where, '/'.join(bad), d))


def validate(src):
    for grp in ('icons', 'dock'):
        for m in src[grp]:
            for f in ('stroke', 'cell', 'dashed'):
                assert_straight(m[f], '%s/%s.%s' % (grp, m['id'], f))


def _pts(d):
    """Parse an M/L/H/V/Z polyline path into a list of subpaths of points."""
    toks = re.findall(r'([MLHVZmlhvz])|(-?\d*\.?\d+)', d)
    subs, cur, x, y, start = [], [], 0.0, 0.0, None
    i, cmd = 0, None
    nums = []
    seq = []
    for c, n in toks:
        seq.append(('c', c) if c else ('n', float(n)))
    idx = 0
    while idx < len(seq):
        kind, val = seq[idx]
        if kind == 'c':
            cmd = val
            idx += 1
            if cmd in 'Zz':
                if cur and start:
                    cur.append(start)
                if cur:
                    subs.append(cur)
                cur, start = [], None
            continue
        need = {'M': 2, 'L': 2, 'H': 1, 'V': 1}[cmd.upper()]
        args = [seq[idx + k][1] for k in range(need)]
        idx += need
        u = cmd.upper()
        rel = cmd.islower()
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
    """VectorDrawable has no stroke-dasharray -- bake the dashes into geometry."""
    out = []
    for sub in _pts(d):
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
                    # `t` cannot advance (seg negative from carry drift, or too small to survive
                    # the float add), so the loop would never terminate. Close the dash out.
                    # `seg == 0.0` is left alone: it already flips, and catching it would change
                    # existing output. See generate_compose.py for the full account.
                    on, carry = not on, 0.0
                    continue
                if on:
                    out.append('M%s %s L%s %s' % (
                        round(x0 + ux * t, 3), round(y0 + uy * t, 3),
                        round(x0 + ux * end, 3), round(y0 + uy * end, 3)))
                if end - t >= seg:
                    on, carry = not on, 0.0
                else:
                    carry += end - t
                t = end
    return ' '.join(out)


TPL = '''<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by tools/mimir-icon-set/generate_drawables.py. Do not hand-edit.
     Mimir STAVE icon set. Single tint: outline at {alpha} alpha, cell filled solid.
     Tint at the call site from LocalMimirPalette; never hardcode a hex here.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
{body}</vector>
'''

STROKE_PATH = ('    <path\n'
               '        android:pathData="{d}"\n'
               '        android:strokeColor="#FFFFFFFF"\n'
               '        android:strokeAlpha="{alpha}"\n'
               '        android:strokeWidth="{w}"\n'
               '        android:strokeLineCap="butt"\n'
               '        android:strokeLineJoin="miter" />\n')

FILL_PATH = ('    <path\n'
             '        android:pathData="{d}"\n'
             '        android:fillColor="#FFFFFFFF" />\n')

HOLLOW_PATH = ('    <path\n'
               '        android:pathData="{d}"\n'
               '        android:strokeColor="#FFFFFFFF"\n'
               '        android:strokeAlpha="{alpha}"\n'
               '        android:strokeWidth="1.2"\n'
               '        android:strokeLineCap="butt"\n'
               '        android:strokeLineJoin="miter" />\n')


def build(mark):
    body = ''
    if mark['dashed']:
        body += STROKE_PATH.format(d=dash(mark['dashed']), alpha=STROKE_ALPHA, w=STROKE_W)
    if mark['stroke']:
        body += STROKE_PATH.format(d=mark['stroke'], alpha=STROKE_ALPHA, w=STROKE_W)
    if mark['cell']:
        body += (HOLLOW_PATH.format(d=mark['cell'], alpha=STROKE_ALPHA)
                 if mark['hollowCell'] else FILL_PATH.format(d=mark['cell']))
    return TPL.format(body=body, alpha=STROKE_ALPHA)


def main():
    validate(SRC)
    os.makedirs(OUT, exist_ok=True)
    written = []
    for m in SRC['icons']:
        p = os.path.join(OUT, 'mimir_icon_%s.xml' % m['id'])
        open(p, 'w').write(build(m))
        written.append(os.path.basename(p))
    for m in SRC['dock']:
        p = os.path.join(OUT, 'mimir_dock_%s.xml' % m['id'])
        open(p, 'w').write(build(m))
        written.append(os.path.basename(p))
    print('wrote %d drawables into %s' % (len(written), OUT))
    return 0


if __name__ == '__main__':
    sys.exit(main())
