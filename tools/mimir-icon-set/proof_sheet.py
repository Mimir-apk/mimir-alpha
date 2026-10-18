#!/usr/bin/env python3
"""Round-trip proof: read the GENERATED VectorDrawables back and draw them as SVG.

This verifies the shipped XML, not the design source -- if a path was mangled on
the way out (baked dashes, a dropped cell, a bad attribute), it shows up here.
Writes tools/mimir-icon-set/_proof.html
"""
import glob, os, re, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(__file__))
DRAW = os.path.abspath(os.path.join(ROOT, '..', '..', 'core-ui', 'src', 'main', 'res', 'drawable'))
A = '{http://schemas.android.com/apk/res/android}'

def svg_of(path):
    root = ET.parse(path).getroot()
    out = ['<svg width="46" height="46" viewBox="0 0 24 24" fill="none" '
           'stroke-linecap="butt" stroke-linejoin="miter">']
    for p in root.findall('path'):
        d = p.get(A + 'pathData')
        if p.get(A + 'fillColor'):
            out.append('<path d="%s" fill="#E8C57E"/>' % d)
        else:
            out.append('<path d="%s" stroke="#D4AF69" stroke-width="%s" stroke-opacity="%s"/>'
                       % (d, p.get(A + 'strokeWidth', '1.5'), p.get(A + 'strokeAlpha', '1')))
    out.append('</svg>')
    return ''.join(out)

files = sorted(glob.glob(os.path.join(DRAW, 'mimir_icon_*.xml'))) + \
        sorted(glob.glob(os.path.join(DRAW, 'mimir_dock_*.xml')))
cells = []
for f in files:
    name = os.path.basename(f)[:-4].replace('mimir_icon_', '').replace('mimir_dock_', 'dock/')
    cells.append(
        '<div style="display:flex;flex-direction:column;align-items:center;gap:7px;">'
        '<div style="width:70px;height:70px;border:1px solid rgba(212,175,105,.15);border-radius:14px;'
        'background:linear-gradient(180deg,rgba(27,28,43,.62),rgba(5,6,12,.62));'
        'display:flex;align-items:center;justify-content:center;">%s</div>'
        '<div style="font:9.5px ui-monospace,monospace;color:#8FA0C4;text-align:center;">%s</div></div>'
        % (svg_of(f), name))

open(os.path.join(ROOT, '_proof.html'), 'w').write(
    '<!doctype html><meta charset="utf-8"><body style="margin:0;background:#05060C;padding:36px;'
    'font-family:system-ui;color:#EEF2FF">'
    '<div style="font:11px ui-monospace,monospace;letter-spacing:2px;color:#D4AF69;margin-bottom:22px">'
    'ROUND-TRIP PROOF &#183; %d GENERATED VECTORDRAWABLES READ BACK</div>'
    '<div style="display:grid;grid-template-columns:repeat(10,1fr);gap:16px 12px;max-width:1180px">%s</div>'
    '</body>' % (len(files), ''.join(cells)))
print('wrote _proof.html from', len(files), 'drawables')
