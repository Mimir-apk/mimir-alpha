"""Author the PC Games platform icon: a Windows 98-style waving four-pane flag with its trail of squares.

 No BAXY console fits PC games, which fell
back to the generic console drawing, so Mimir draws this one itself. A white silhouette on a 48x48 viewport, like
every mimir_platform_icon_* (the theme tints or gradient-masks it).

This script is the icon's source: generate-platform-console-art.mjs records its sha256 beside the drawable's, and
re-running it must reproduce the drawable byte for byte.

usage (from the repo root): python tools/platform-registry/author-windows-98-flag.py [--preview out.png]
"""
import math
import sys

NL = chr(10)

# Flag geometry in design units, before recentring.
W, H = 27.0, 26.0


def flag_point(u, v):
    """The waving flag: a gentle S across the width, a slight lean, the right side a touch higher."""
    x = W * u - 1.6 * v
    y = H * v + 2.3 * math.sin(2 * math.pi * (0.82 * u) + 0.35) - 1.4 * u
    return x, y


def pane(u0, u1, v0, v1, steps=10):
    pts = []
    for i in range(steps + 1):
        pts.append(flag_point(u0 + (u1 - u0) * i / steps, v0))
    for i in range(1, steps + 1):
        pts.append(flag_point(u1, v0 + (v1 - v0) * i / steps))
    for i in range(1, steps + 1):
        pts.append(flag_point(u1 - (u1 - u0) * i / steps, v1))
    for i in range(1, steps):
        pts.append(flag_point(u0, v1 - (v1 - v0) * i / steps))
    return pts


def square(cx, cy, size):
    h = size / 2
    return [(cx - h, cy - h), (cx + h, cy - h), (cx + h, cy + h), (cx - h, cy + h)]


GAP = 0.055
shapes = []
for (u0, u1) in ((0.0, 0.5 - GAP / 2), (0.5 + GAP / 2, 1.0)):
    for (v0, v1) in ((0.0, 0.5 - GAP / 2), (0.5 + GAP / 2, 1.0)):
        shapes.append(pane(u0, u1, v0, v1))

# The trail: squares breaking off the flag's left edge, smaller and sparser the further they fly.
TRAIL = [
    # (u, v offsets inside a row, size)
    (-0.095, (0.09, 0.30), 3.1),
    (-0.205, (0.16, 0.36), 2.5),
    (-0.305, (0.07,), 2.0),
    (-0.395, (0.26,), 1.6),
    (-0.47, (0.14,), 1.2),
]
for row_top in (0.0, 0.5 + GAP / 2):
    for u, offsets, size in TRAIL:
        for dv in offsets:
            cx, cy = flag_point(u, row_top + dv + 0.06)
            shapes.append(square(cx, cy, size))

# Recentre into the 48 box, the larger side filling 44 of it (the set's ~4-dp margin).
xs = [p[0] for s in shapes for p in s]
ys = [p[1] for s in shapes for p in s]
minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
scale = 44.0 / max(maxx - minx, maxy - miny)
ox = (48 - (maxx - minx) * scale) / 2 - minx * scale
oy = (48 - (maxy - miny) * scale) / 2 - miny * scale
shapes = [[(round(x * scale + ox, 2), round(y * scale + oy, 2)) for x, y in s] for s in shapes]


def path_data(s):
    head, *rest = s
    return "M" + f"{head[0]} {head[1]}" + "".join(f"L{x} {y}" for x, y in rest) + "Z"


paths = NL.join(
    f'    <path android:fillColor="#FFFFFFFF" android:pathData="{path_data(s)}" />' for s in shapes
)
xml = (
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="48dp" android:height="48dp" '
    'android:viewportWidth="48" android:viewportHeight="48">' + NL + paths + NL + "</vector>" + NL
)
OUT = "core-ui/src/main/res/drawable/mimir_platform_icon_windows_98.xml"
open(OUT, "w", encoding="utf-8", newline=NL).write(xml)
print("wrote", OUT)

if "--preview" in sys.argv:
    from PIL import Image, ImageDraw

    k = 8
    img = Image.new("RGB", (48 * k, 48 * k), (18, 20, 28))
    d = ImageDraw.Draw(img)
    for s in shapes:
        d.polygon([(x * k, y * k) for x, y in s], fill=(212, 175, 105))
    d.rectangle([0, 0, 48 * k - 1, 48 * k - 1], outline=(60, 60, 70))
    preview = sys.argv[sys.argv.index("--preview") + 1]
    img.save(preview)
    print("preview", preview)
print("shapes", len(shapes), "bounds", round(minx, 2), round(maxx, 2), round(miny, 2), round(maxy, 2))
