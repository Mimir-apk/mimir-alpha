#!/usr/bin/env bash
# Reproducibly (re)provision the bundled identity assets. Hash DAT XML is build-time input only: every
# source becomes one immutable `*.midx` shard; compact keyed catalogues remain `*.idcatgz`.
#
# The DATs are checksums + canonical names: metadata, NOT game content, BIOS, or firmware. Their terms are
# recorded in the repo-root NOTICE (section 2), including the ones that are NOT ESTABLISHED. Sources (all
# logiqx XML, read by core-metadata/LogiqxDatParser):
#   - Cartridge (No-Intro parent-clone): hugo19941994/auto-datfile-generator release `no-intro_parent-clone.zip`.
#   - Disc/.chd (Redump data): MetalSlug/MAMERedump (MIT), <disk sha1> for CD/GD-ROM, <rom crc> for GC/Wii;
#     and, for dreamcast_redump, ps3 and xbox360, the generator's `redump.zip`.
#   - Wii/GameCube, Wii U, PS3, 3DS and DS identifiers: GameTDB, used under its public developer invitation
#     with a visible in-app link to https://www.gametdb.com/ (and a courtesy project notification still outstanding).
# The full MAME `-listxml` is build-time-only; compact exact-set/disk and loose-member indexes are bundled.
# Switch has no No-Intro hash DAT and uses a payload-bound ticket ID against the compact title catalog.
#
# Usage:  tools/fetch-dats.sh          (atomically replaces app/src/main/assets/dat/ after all gates pass)
# Deps:   curl, unzip, gzip, python3. Network required (metadata only — never content).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO_ROOT/app/src/main/assets/dat"

# ── Provenance gate: every source this script provisions must appear in the repo-root NOTICE ──────────
# Runs BEFORE any network work so a missing notice fails in a second, not after a 45-asset download.
#
# This is the enforcement point that matters: a new bundled source enters the app HERE, and for a while
# four sources shipped while only one was credited. NOTICE cannot live in "$OUT" — this script activates
# that directory by atomic rename, which would destroy it every run — so the linkage is checked instead.
NOTICE_FILE="$REPO_ROOT/NOTICE"
[ -f "$NOTICE_FILE" ] || { echo "!! missing $NOTICE_FILE — bundled data requires a provenance notice"; exit 1; }
for src in "MAMERedump" "auto-datfile-generator" "No-Intro" "MAME" "GameTDB" "titledb"; do
  grep -qi -- "$src" "$NOTICE_FILE" || {
    echo "!! NOTICE does not mention '$src'. Add its provenance and terms before provisioning assets."
    exit 1
  }
done
echo "→ provenance gate: NOTICE covers all provisioned sources"
STAGE="$(mktemp -d)"
DAT_STAGE="$STAGE/datgz"
# Keep the completed asset set on the same filesystem as OUT so the final directory activation is rename-only.
BUILD_OUT="$(mktemp -d "$REPO_ROOT/app/src/main/assets/.dat-next.XXXXXX")"
trap 'rm -rf "$STAGE" "${BUILD_OUT:-}"' EXIT
mkdir -p "$DAT_STAGE"

echo "→ staging source XML in $STAGE ; completed assets → $BUILD_OUT"

# ── Cartridge: No-Intro parent-clone XML (extract canonical per-system sets) ────────────────────
NI_ZIP="$STAGE/no-intro_pc.zip"
curl -fsSL "https://github.com/hugo19941994/auto-datfile-generator/releases/latest/download/no-intro_parent-clone.zip" -o "$NI_ZIP"

# Map: asset-slug ⇐ the canonical No-Intro set name (glob — release timestamps drift, so match by prefix).
#
# TRAP (do not "fix" this back): NES ships HEADERLESS — RomFingerprinter.kt strips the 16-byte iNES header
# before hashing, which only lands on the Headerless sha1 space. The set name below MUST say "(Headerless)".
# No-Intro also publishes a "(Headered)" NES set (raw iNES header kept, different sha1 per game) — it
# parses fine, gzips fine, and ships fine, but matches ZERO real files, silently, with no build error. This
# exact swap happened once already; verify by reading the shipped nes.datgz's own <header><name> field
# (gunzip | head), never by trusting this string or the filename.
# PORTABILITY (do not "tidy" these back into `declare -A`): associative arrays need bash 4+, and macOS
# ships bash 3.2.57 as /bin/bash, so `#!/usr/bin/env bash` resolves to an interpreter that dies on the
# FIRST such array with "declare: -A: invalid option" — before a single DAT is fetched. This script
# carried that defect from the start and had never actually run on the development machine. Plain functions
# work everywhere and keep each trap comment attached to the pattern it guards.
zip_set() { # zip_set <zipfile> <slug> <member-glob>
  local member
  member="$(unzip -Z1 "$1" "$3" | head -1)"
  [ -n "$member" ] || { echo "!! no match for $2 ($3)"; exit 1; }
  unzip -o -j "$1" "$member" -d "$STAGE" >/dev/null
  # -n strips source filename + mtime so identical XML provisions byte-identically across runs.
  gzip -n -9 -c "$STAGE/$(basename "$member")" > "$DAT_STAGE/$2.datgz"
  echo "  ✓ $2  ⇐ $(basename "$member")"
}

zip_set "$NI_ZIP" nes "Nintendo - Nintendo Entertainment System (Headerless) (Parent-Clone)*"
# SNES: unlike NES, No-Intro publishes exactly ONE cartridge set here — no "(Headered)"/"(Headerless)"
# qualifier at all, so do not go looking for one / do not append either word to this pattern. Confirmed
# by inspecting the set directly: every <rom> entry uses ".sfc" (0 use ".smc", the header-carrying
# extension) and sizes are all the clean header-free convention (1048576, 2097152, ... never base+512) —
# i.e. this set already IS the headerless convention. RomFingerprinter has no SNES branch (no header
# strip, unlike NES/N64/Genesis): it hashes the file exactly as given, so headerless is the variant that
# actually matches. Bundling a future "(Headered)" SNES set instead would reproduce the NES trap above.
zip_set "$NI_ZIP" snes "Nintendo - Super Nintendo Entertainment System (Parent-Clone)*"
zip_set "$NI_ZIP" gb "Nintendo - Game Boy (Parent-Clone)*"
zip_set "$NI_ZIP" gbc "Nintendo - Game Boy Color (Parent-Clone)*"
zip_set "$NI_ZIP" gba "Nintendo - Game Boy Advance (Parent-Clone)*"
zip_set "$NI_ZIP" nds "Nintendo - Nintendo DS (Encrypted) (Parent-Clone)*"
zip_set "$NI_ZIP" nds_dec "Nintendo - Nintendo DS (Decrypted) (Parent-Clone)*"
zip_set "$NI_ZIP" 3ds "Nintendo - Nintendo 3DS (Encrypted) (Parent-Clone)*"
zip_set "$NI_ZIP" 3ds_dec "Nintendo - Nintendo 3DS (Decrypted) (Parent-Clone)*"
zip_set "$NI_ZIP" n64 "Nintendo - Nintendo 64 (BigEndian) (Parent-Clone)*"
zip_set "$NI_ZIP" genesis "Sega - Mega Drive - Genesis (Parent-Clone)*"
zip_set "$NI_ZIP" mastersystem "Sega - Master System - Mark III (Parent-Clone)*"
zip_set "$NI_ZIP" gamegear "Sega - Game Gear (Parent-Clone)*"
zip_set "$NI_ZIP" sg1000 "Sega - SG-1000 - SC-3000 (Parent-Clone)*"
zip_set "$NI_ZIP" atari2600 "Atari - Atari 2600 (Parent-Clone)*"
# A78/LNX are intentionally the header-carrying variants: RomFingerprinter hashes `.a78`/`.lnx` whole-file.
zip_set "$NI_ZIP" atari7800 "Atari - Atari 7800 (A78) (Parent-Clone)*"
zip_set "$NI_ZIP" atarilynx "Atari - Atari Lynx (LNX) (Parent-Clone)*"
zip_set "$NI_ZIP" pcengine "NEC - PC Engine - TurboGrafx-16 (Parent-Clone)*"
# No-Intro spells these set names `NeoGeo` (no space); keep the verified prefixes exact.
zip_set "$NI_ZIP" ngp "SNK - NeoGeo Pocket (Parent-Clone)*"
zip_set "$NI_ZIP" ngpc "SNK - NeoGeo Pocket Color (Parent-Clone)*"
zip_set "$NI_ZIP" virtualboy "Nintendo - Virtual Boy (Parent-Clone)*"
zip_set "$NI_ZIP" wonderswan "Bandai - WonderSwan (Parent-Clone)*"
zip_set "$NI_ZIP" wonderswancolor "Bandai - WonderSwan Color (Parent-Clone)*"
zip_set "$NI_ZIP" pokemini "Nintendo - Pokemon Mini (Parent-Clone)*"

# ── Disc/.chd: MAMERedump (raw GitHub; the (NNNN) count in each name drifts, so resolve via the API) ──
MR_API="https://api.github.com/repos/MetalSlug/MAMERedump/contents/MAME%20Redump"
LISTING="$STAGE/mamedump.json"
curl -fsSL "$MR_API" -o "$LISTING"
mamedump_set() { # mamedump_set <slug> <name-prefix>
  local url
  url="$(grep -o '"download_url": "[^"]*"' "$LISTING" | cut -d'"' -f4 \
    | grep -F "$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1], safe='()'))" "$2")" | head -1)"
  [ -n "$url" ] || { echo "!! no MAMERedump match for $1 ($2)"; exit 1; }
  curl -fsSL "$url" -o "$STAGE/$1.dat"
  gzip -n -9 -c "$STAGE/$1.dat" > "$DAT_STAGE/$1.datgz"
  echo "  ✓ $1  ⇐ $(basename "$url")"
}

mamedump_set ps1 "Sony - PlayStation ("
mamedump_set psp "Sony - PlayStation Portable ("
mamedump_set dreamcast "Sega - Dreamcast ("
mamedump_set gamecube "Nintendo - GameCube ("
mamedump_set wii "Nintendo - Wii ("
mamedump_set ps2 "Sony - PlayStation 2 ("
mamedump_set saturn "Sega - Saturn ("
mamedump_set segacd "Sega - Mega CD & Sega CD ("
mamedump_set pcenginecd "NEC - PC Engine CD & TurboGrafx CD ("
mamedump_set snk_neo_geo_cd "SNK - Neo Geo CD ("

# Deliberate omissions: MAMERedump publishes no original-Xbox CHD set. Its Redump whole-image set cannot
# match local CHDs. 3DO remains unprovisioned until its GAP registry profile gains a content contract;
# the live MAMERedump prefix is `Panasonic - 3DO Interactive Multiplayer (` once that blocker is closed.

# ── Redump <rom> sets — whole-file sha1 where MAMERedump's <disk> (.chd) set can't serve ──────────
#
# NOTE the failure mode changed here: the old Dreamcast block was `if [ -n "$dc_member" ]`, i.e. a
# SILENT SKIP when the pattern stopped matching — provisioning would report success having quietly not
# written the asset. zip_set hard-exits instead, which is the same posture every other set already had.
RD_ZIP="$STAGE/redump.zip"
curl -fsSL "https://github.com/hugo19941994/auto-datfile-generator/releases/latest/download/redump.zip" -o "$RD_ZIP"

# Per-track (.gdi/.cue) Dreamcast — complements the MAMERedump <disk> (.chd) set above.
zip_set "$RD_ZIP" dreamcast_redump "Sega - Dreamcast - Datfile*"
# PS3 / Xbox 360: single-ISO generations, so 1.00 roms/game and SMALL despite being the newest consoles
# here (0.39 / 0.29 MiB gzipped) — the multi-track inflation that makes PS1 and Saturn expensive is a CD
# phenomenon that does not apply. Same header trap as NES: verify what actually shipped by reading the
# blob's own <header><name>, never the filename. Confirmed at bundling time:
#   ps3.datgz     -> <name>Sony - PlayStation 3</name>   (4503 games, 4515 <rom>, 0 <disk>)
#   xbox360.datgz -> <name>Microsoft - Xbox 360</name>   (3691 games, 3700 <rom>, 0 <disk>)
# Both are <rom> (not <disk>), so a .chd can never match them by construction — only a whole .iso can.
zip_set "$RD_ZIP" ps3 "Sony - PlayStation 3 - Datfile*"
zip_set "$RD_ZIP" xbox360 "Microsoft - Xbox 360 - Datfile*"

# ── Keyed identity catalogs (non-hash) → *.idcatgz : keyType<TAB>key<TAB>title ───────────────────
# For platforms the DAT hash path can't reach: arcade (MAME shortname), switch (Title ID), gc/wii (Game ID).
echo "→ building keyed id-catalogs (arcade / switch / gamecube+wii)"

# arcade: MAME -listfull (shortname -> full title). Freely-redistributable machine descriptions (MAME).
curl -fsSL "https://gist.githubusercontent.com/a8bit/265ec54b7ea4001e5fc01b6ebf96e18d/raw" -o "$STAGE/mame-listfull.txt"
python3 - "$STAGE/mame-listfull.txt" "$BUILD_OUT/arcade.idcatgz" <<'PY'
import re, gzip, sys
src, out = sys.argv[1], sys.argv[2]
rows=[]
for line in open(src, encoding='utf-8', errors='replace'):
    m=re.match(r'^(\S+)\s+"(.*)"\s*$', line.rstrip('\n'))
    if not m: continue
    s,t=m.group(1).lower(), m.group(2).strip()
    if s=='name' or not t: continue
    rows.append(f'NAME\t{s}\t{t}')
with open(out, 'wb') as raw:
    with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz:
        gz.write('\n'.join(rows).encode('utf-8'))
print(f'  arcade.idcatgz: {len(rows)} rows')
PY

# MAME: official release-pinned full driver XML -> compact exact complete-set/disk index plus a binary
# loose-member classifier. The generator verifies the source archive SHA-256 and writes deterministic gzip.
echo "→ building release-pinned MAME payload indexes"
"$REPO_ROOT/tools/regen-mame-payload-catalog.py" \
  --output "$BUILD_OUT/mame0287.mpaygz" \
  --member-output "$BUILD_OUT/mame0287.mmemgz"

# switch: blawar/titledb (MIT) US.en.json -> base-app (id ends 000) Title ID -> name.
curl -fsSL "https://raw.githubusercontent.com/blawar/titledb/master/US.en.json" -o "$STAGE/titledb-us.json"
python3 - "$STAGE/titledb-us.json" "$BUILD_OUT/switch.idcatgz" <<'PY'
import json, gzip, re, sys
d=json.load(open(sys.argv[1], encoding='utf-8')); rows=[]; seen=set()
for v in d.values():
    if not isinstance(v, dict): continue
    tid=str(v.get('id') or '').upper(); name=(v.get('name') or '').strip()
    if not re.fullmatch(r'[0-9A-F]{16}', tid) or not name or not tid.endswith('000') or tid in seen: continue
    seen.add(tid); name=name.replace('\t',' ').replace('™','').replace('®','').strip()
    rows.append(f'TITLE_ID\t{tid}\t{name}')
with open(sys.argv[2], 'wb') as raw:
    with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz:
        gz.write('\n'.join(rows).encode('utf-8'))
print(f'  switch.idcatgz: {len(rows)} base-app rows')
PY

# gamecube+wii: GameTDB (community) wiitdb.txt -> 6-char Game ID -> title (one shared catalog).
curl -fsSL "https://www.gametdb.com/wiitdb.txt?LANG=EN&GAMECUBE=1" -o "$STAGE/wiitdb.txt"
python3 - "$STAGE/wiitdb.txt" "$BUILD_OUT/gametdb.idcatgz" <<'PY'
import gzip, re, sys
rows=[]; seen=set()
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    line=line.rstrip('\n')
    if '=' not in line or line.startswith('TITLES'): continue
    gid,title=line.split('=',1); gid=gid.strip().upper(); title=title.strip()
    if not re.fullmatch(r'[A-Z0-9]{4,6}', gid) or not title or gid in seen: continue
    rows.append(f'GAME_ID\t{code}\t{title}')
with open(sys.argv[2], 'wb') as raw:
    with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz:
        gz.write('\n'.join(rows).encode('utf-8'))
print(f'  gametdb.idcatgz: {len(rows)} rows')
PY

# Wii U: GameTDB is the only newly additive hash catalogue in its family. Convert the hash-bearing WUD
# rows into the same small logiqx subset the runtime already consumes. The source's many descriptive fields
# stay build-time-only; no artwork or content is bundled. LANG=ORIG avoids the EN-truncated catalogue.
curl -fsSL "https://www.gametdb.com/wiiutdb.zip?LANG=ORIG" -o "$STAGE/wiiutdb.zip"
unzip -p "$STAGE/wiiutdb.zip" '*.xml' > "$STAGE/wiiutdb.xml"
python3 - "$STAGE/wiiutdb.xml" "$DAT_STAGE/wiiu.datgz" <<'PY'
import gzip, sys, xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

src, out = sys.argv[1], sys.argv[2]
rows = []
for event, elem in ET.iterparse(src, events=('end',)):
    if elem.tag != 'game':
        continue
    game_name = (elem.get('name') or '').strip()
    if game_name:
        for rom in elem.findall('rom'):
            crc = (rom.get('crc') or '').strip().lower()
            sha1 = (rom.get('sha1') or '').strip().lower()
            if not crc and not sha1:
                continue
            attrs = [('name', (rom.get('name') or game_name).strip())]
            for key in ('size', 'crc', 'md5', 'sha1'):
                value = (rom.get(key) or '').strip().lower() if key != 'size' else (rom.get(key) or '').strip()
                if value:
                    attrs.append((key, value))
            rows.append((game_name, attrs))
    elem.clear()

parts = ['<?xml version="1.0" encoding="UTF-8"?>', '<datafile>',
         '<header><name>GameTDB - Nintendo Wii U</name></header>']
for game_name, attrs in rows:
    rendered = ' '.join(f'{key}={quoteattr(value)}' for key, value in attrs)
    parts.append(f'<game name={quoteattr(game_name)}><rom {rendered}/></game>')
parts.append('</datafile>')
payload = ('\n'.join(parts) + '\n').encode('utf-8')
with open(out, 'wb') as raw:
    with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz:
        gz.write(payload)
print(f'  wiiu.datgz: {len(rows)} hash-bearing WUD rows')
PY

# PS3: PARAM.SFO carries TITLE_ID (for example BLUS30443). The runtime extracts that SERIAL locally and
# this compact catalogue maps it to GameTDB's English title, avoiding an ~8 GB whole-image read.
curl -fsSL "https://www.gametdb.com/ps3tdb.zip?LANG=ORIG" -o "$STAGE/ps3tdb.zip"
unzip -p "$STAGE/ps3tdb.zip" '*.xml' > "$STAGE/ps3tdb.xml"
python3 - "$STAGE/ps3tdb.xml" "$BUILD_OUT/ps3tdb.idcatgz" <<'PY'
import gzip, re, sys, xml.etree.ElementTree as ET

src, out = sys.argv[1], sys.argv[2]
rows, seen = [], set()
for event, elem in ET.iterparse(src, events=('end',)):
    if elem.tag != 'game':
        continue
    serial = (elem.findtext('id') or '').strip().upper()
    if not re.fullmatch(r'[A-Z0-9]{9}', serial) or serial in seen:
        elem.clear(); continue
    title = ''
    for locale in elem.findall('locale'):
        if (locale.get('lang') or '').upper() == 'EN':
            title = (locale.findtext('title') or '').strip()
            if title:
                break
    if not title:
        title = (elem.get('name') or '').strip()
    title = title.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip()
    if title:
        seen.add(serial)
        rows.append(f'SERIAL\t{serial}\t{title}')
    elem.clear()
with open(out, 'wb') as raw:
    with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz:
        gz.write(('\n'.join(rows) + '\n').encode('utf-8'))
print(f'  ps3tdb.idcatgz: {len(rows)} title-id rows')
PY

# 3ds: the four-character product code (the AQEE in CTR-P-AQEE) keyed to GameTDB's English title, so a
# 3DS card image is identified from partition 0's NCCH header instead of a whole-file SHA-1.
#
# QA-17  measured why this is worth a catalogue: on the Thor, 3DS is
# 16.14 GB across 26 files -- 43% of every byte the identity pass reads -- purely because "3ds" had no
# keyed route and fell through to RomFingerprinter. IdExtractor.threeDsProductCode reads the key from
# ~16 KB, the same trade ps3tdb below already makes against an ~8 GB image.
#
# GameTDB rather than No-Intro, and that was checked rather than assumed: the No-Intro 3DS (Encrypted)
# set this repo already ships as 3ds.midx carries NO serials at all -- 2,161 <game> elements with only
# `name` and `cloneof` attributes, verified 2026-09-16. GameTDB's 3dstdb keys every one of its 4,785
# games on the 4-char code, which is precisely what the extractor returns.
curl -fsSL "https://www.gametdb.com/3dstdb.zip?LANG=ORIG" -o "$STAGE/3dstdb.zip"
unzip -p "$STAGE/3dstdb.zip" '*.xml' > "$STAGE/3dstdb.xml"
python3 - "$STAGE/3dstdb.xml" "$BUILD_OUT/3ds.idcatgz" <<'PY'
import gzip, re, sys, xml.etree.ElementTree as ET

src, out = sys.argv[1], sys.argv[2]
CODE = re.compile(r'[A-Z0-9]{4}')
rows, seen = [], set()
for event, elem in ET.iterparse(src, events=('end',)):
    if elem.tag != 'game':
        continue
    code = (elem.findtext('id') or '').strip().upper()
    if not CODE.fullmatch(code) or code in seen:
        elem.clear(); continue
    title = ''
    for locale in elem.findall('locale'):
        if (locale.get('lang') or '').upper() == 'EN':
            title = (locale.findtext('title') or '').strip()
            if title:
                break
    if not title:
        title = (elem.get('name') or '').strip()
    for bad in ('\t', '\r', '\n'):
        title = title.replace(bad, ' ')
    title = title.strip()
    if title:
        seen.add(code)
        rows.append('GAME_ID' + '\t' + code + '\t' + title)
    elem.clear()
# An empty catalogue would register cleanly, resolve nothing, and be indistinguishable from a working
# one that simply has no matches. Fail instead of shipping that.
if not rows:
    sys.exit('!! 3ds.idcatgz: no 4-char product codes parsed -- refusing to ship an empty catalogue')
payload = ('\n'.join(rows) + '\n').encode('utf-8')
with open(out, 'wb') as raw:
    # mtime=0 and no stored filename so identical input provisions byte-identically across runs.
    with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz:
        gz.write(payload)
print(f'  3ds.idcatgz: {len(rows)} product-code rows')
PY

# ds: the four-character game code at offset 0x0C of the cart header, keyed to GameTDB's English title.
#
# QA-17 follow-up: DS is 2.53 GB across 88 files on the measured device -- the largest FILE count still on
# the whole-file hash path, and file count is what a player watches the identification counter move
# through. The code needs no extra read at all: 0x10 is well inside the existing head budget.
#
# Same source family and same 4-char key scheme as 3dstdb above; GameTDB's dstdb carries 8,460 games.
curl -fsSL "https://www.gametdb.com/dstdb.zip?LANG=ORIG" -o "$STAGE/dstdb.zip"
unzip -p "$STAGE/dstdb.zip" '*.xml' > "$STAGE/dstdb.xml"
python3 - "$STAGE/dstdb.xml" "$BUILD_OUT/ds.idcatgz" <<'PY'
import gzip, re, sys, xml.etree.ElementTree as ET

src, out = sys.argv[1], sys.argv[2]
CODE = re.compile(r'[A-Z0-9]{4}')
rows, seen = [], set()
for event, elem in ET.iterparse(src, events=('end',)):
    if elem.tag != 'game':
        continue
    code = (elem.findtext('id') or '').strip().upper()
    if not CODE.fullmatch(code) or code in seen:
        elem.clear(); continue
    title = ''
    for locale in elem.findall('locale'):
        if (locale.get('lang') or '').upper() == 'EN':
            title = (locale.findtext('title') or '').strip()
            if title:
                break
    if not title:
        title = (elem.get('name') or '').strip()
    for bad in ('\t', '\r', '\n'):
        title = title.replace(bad, ' ')
    title = title.strip()
    if title:
        seen.add(code)
        rows.append('GAME_ID' + '\t' + code + '\t' + title)
    elem.clear()
if not rows:
    sys.exit('!! ds.idcatgz: no 4-char game codes parsed -- refusing to ship an empty catalogue')
payload = ('\n'.join(rows) + '\n').encode('utf-8')
with open(out, 'wb') as raw:
    with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as gz:
        gz.write(payload)
print(f'  ds.idcatgz: {len(rows)} game-code rows')
PY

# ── Immutable packed SHA-1 indexes ──────────────────────────────────────────────────────────────
# Every source remains a separate ambiguity domain. In particular, DS/3DS encrypted/decrypted and the
# two Dreamcast sets MUST NOT be merged: source order and fail-closed collisions are part of lookup truth.
echo "→ packing immutable per-source SHA-1 indexes"
PACK_STATS="$STAGE/packed-stats"
mkdir -p "$PACK_STATS"
pack_set() { # pack_set <slug> <platform-id> <origin> <exact source header>
  local slug="$1" platform="$2" origin="$3" header="$4" stats="$PACK_STATS/$1.json"
  "$REPO_ROOT/tools/pack-dats.py" pack "$DAT_STAGE/$slug.datgz" "$BUILD_OUT/$slug.midx" \
    --source-id "$slug.datgz" \
    --platform-id "$platform" \
    --origin "$origin" \
    --expected-header "$header" > "$stats"
  python3 - "$stats" <<'PY'
import json, sys
s = json.load(open(sys.argv[1], encoding='utf-8'))
print(f"  ✓ {s['source_id']} -> {s['file_bytes']} B; {s['parsed_entry_count']} rows / "
      f"{s['unique_sha1_count']} SHA-1 / {s['ambiguous_sha1_count']} ambiguous / "
      f"{s['distinct_collapse_key_count']} collapse keys")
PY
}

pack_set nes nes NO_INTRO "Nintendo - Nintendo Entertainment System (Headerless) (Parent-Clone)"
pack_set snes snes NO_INTRO "Nintendo - Super Nintendo Entertainment System (Parent-Clone)"
pack_set gb gb NO_INTRO "Nintendo - Game Boy (Parent-Clone)"
pack_set gbc gbc NO_INTRO "Nintendo - Game Boy Color (Parent-Clone)"
pack_set gba gba NO_INTRO "Nintendo - Game Boy Advance (Parent-Clone)"
pack_set nds ds NO_INTRO "Nintendo - Nintendo DS (Encrypted) (Parent-Clone)"
pack_set nds_dec ds NO_INTRO "Nintendo - Nintendo DS (Decrypted) (Parent-Clone)"
pack_set 3ds 3ds NO_INTRO "Nintendo - Nintendo 3DS (Encrypted) (Parent-Clone)"
pack_set 3ds_dec 3ds NO_INTRO "Nintendo - Nintendo 3DS (Decrypted) (Parent-Clone)"
pack_set n64 n64 NO_INTRO "Nintendo - Nintendo 64 (BigEndian) (Parent-Clone)"
pack_set genesis megadrive NO_INTRO "Sega - Mega Drive - Genesis (Parent-Clone)"
pack_set mastersystem mastersystem NO_INTRO "Sega - Master System - Mark III (Parent-Clone)"
pack_set gamegear gamegear NO_INTRO "Sega - Game Gear (Parent-Clone)"
pack_set sg1000 sg1000 NO_INTRO "Sega - SG-1000 - SC-3000 (Parent-Clone)"
pack_set atari2600 atari2600 NO_INTRO "Atari - Atari 2600 (Parent-Clone)"
pack_set atari7800 atari7800 NO_INTRO "Atari - Atari 7800 (A78) (Parent-Clone)"
pack_set atarilynx atarilynx NO_INTRO "Atari - Atari Lynx (LNX) (Parent-Clone)"
pack_set pcengine pcengine NO_INTRO "NEC - PC Engine - TurboGrafx-16 (Parent-Clone)"
pack_set ngp ngp NO_INTRO "SNK - NeoGeo Pocket (Parent-Clone)"
pack_set ngpc ngpc NO_INTRO "SNK - NeoGeo Pocket Color (Parent-Clone)"
pack_set virtualboy virtualboy NO_INTRO "Nintendo - Virtual Boy (Parent-Clone)"
pack_set wonderswan wonderswan NO_INTRO "Bandai - WonderSwan (Parent-Clone)"
pack_set wonderswancolor wonderswancolor NO_INTRO "Bandai - WonderSwan Color (Parent-Clone)"
pack_set pokemini pokemini NO_INTRO "Nintendo - Pokemon Mini (Parent-Clone)"

pack_set ps1 ps1 REDUMP "Sony - PlayStation"
pack_set psp psp REDUMP "Sony - PlayStation Portable"
pack_set dreamcast dreamcast REDUMP "Sega - Dreamcast"
pack_set gamecube gamecube REDUMP "Nintendo - GameCube"
pack_set wii wii REDUMP "Nintendo - Wii"
pack_set ps2 ps2 REDUMP "Sony - PlayStation 2"
pack_set saturn saturn REDUMP "Sega - Saturn"
pack_set segacd segacd REDUMP "Sega - Mega CD & Sega CD"
pack_set pcenginecd pcenginecd REDUMP "NEC - PC Engine CD & TurboGrafx CD"
pack_set snk_neo_geo_cd snk_neo_geo_cd REDUMP "SNK - Neo Geo CD"
pack_set dreamcast_redump dreamcast REDUMP "Sega - Dreamcast"
pack_set ps3 ps3 REDUMP "Sony - PlayStation 3"
pack_set xbox360 xbox360 REDUMP "Microsoft - Xbox 360"
pack_set wiiu nintendo_wii_u TITLE_CATALOG "GameTDB - Nintendo Wii U"

midx_count="$(find "$BUILD_OUT" -type f -name '*.midx' | wc -l | tr -d ' ')"
id_count="$(find "$BUILD_OUT" -type f -name '*.idcatgz' | wc -l | tr -d ' ')"
# The escapes on the next line were doubled, which bash parses as a literal backslash followed by an
# unquoted `(` -- a syntax error that fails the WHOLE script at parse time, before a single line runs.
# Found by `bash -n` while adding the 3DS stanza above; pre-existing and unrelated to it, fixed here
# because a catalogue tool that cannot be parsed cannot build a catalogue either.
mame_payload_count="$(find "$BUILD_OUT" -type f \( -name 'mame0287.mpaygz' -o -name 'mame0287.mmemgz' \) | wc -l | tr -d ' ')"
[ "$midx_count" = 38 ] || { echo "!! expected 38 packed DATs, built $midx_count"; exit 1; }
[ "$id_count" = 4 ] || { echo "!! expected 4 id-catalogs, built $id_count"; exit 1; }
[ "$mame_payload_count" = 2 ] || { echo "!! expected 2 MAME payload indexes, built $mame_payload_count"; exit 1; }
if find "$BUILD_OUT" -type f -name '*.datgz' | grep -q .; then
  echo "!! build output contains legacy XML DAT blobs"
  exit 1
fi

# Activate only after every source fetched, packed and self-verified. The old complete directory remains
# recoverable until the second same-filesystem rename succeeds; a failed activation restores it.
BACKUP_OUT="$OUT.previous.$$"
if [ -d "$OUT" ]; then
  mv "$OUT" "$BACKUP_OUT"
fi
if mv "$BUILD_OUT" "$OUT"; then
  BUILD_OUT=""
  rm -rf "$BACKUP_OUT"
else
  [ ! -d "$BACKUP_OUT" ] || mv "$BACKUP_OUT" "$OUT"
  exit 1
fi

echo "→ done. Bundled $midx_count packed DATs + $id_count id-catalogs + $mame_payload_count MAME payload indexes ($(du -sh "$OUT" | cut -f1))."
echo "  Verify: ./gradlew :core-metadata:testDebugUnitTest --tests '*BundledDatAssetsTest' --tests '*PackedDatIndexTest' --tests '*IdMatchTest'"
