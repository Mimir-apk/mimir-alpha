#!/usr/bin/env python3
"""Draft a Tweaker family-catalog entry from a capture.sh capture.

    python tools/family-catalog/draft.py capture-thor.txt > entry.json

stdout is the draft entry (one JSON object, LF line endings). stderr reports:
the snapped kHz each profile would land on, a few observations, and an `owed` list of what
a read-only capture cannot see. Python 3 standard library only.

This only DRAFTS. The Kotlin validator (FamilyCatalog.kt) is the authority on whether an
entry is acceptable, and `corpus/*.json` is run through it by FamilyCatalogCorpusTest. Do
not add validation rules here; a second validator would drift from the first.

SHAPE: the entry follows the Kotlin model in FamilyCatalog.kt: `cpu.kind=anchor` with
integer-pair rows, `gpu.kind=transposed`, kebab-case transport names from the compiled enum.
Every shape decision lives in build_entry() and the constants just above it, so following a
change in the Kotlin model is a one-place edit.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys

PROFILES = ("quiet", "balanced", "performance")

# --- compiled anchor arithmetic, replicated from TuningPlanner.kt (read, not authoritative) --
# Sequence 10's signed Odin 3 vector as (target kHz, that policy's own live max kHz) integer
# pairs, little cluster then prime cluster. Integers on purpose: a truncated decimal flips
# roundToInt. TuningPlanner.kt MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ / ODIN_ANCHOR_*_MAX_KHZ.
ANCHOR_CPU = {
    "quiet": {"little": (1785600, 3532800), "prime": (2246400, 4320000)},
    "balanced": {"little": (1996800, 3532800), "prime": (2438400, 4320000)},
    "performance": {"little": (1996800, 3532800), "prime": (3072000, 4320000)},
}
# The GPU half, as FamilyCatalog's `gpu.kind=transposed` carries it: the Odin's 14-entry live
# table (13 steps) and its quiet max_pwrlevel 5 (TuningPlanner.kt ODIN_3_QUIET_GPU_MAX_POWER_LEVEL,
# ODIN_3_LIVE_GPU_LEVEL_COUNT). balanced and performance carry no GPU
# ceiling on the transposed path.
GPU_ANCHOR_LEVELS = 14
GPU_CAPPED = {"quiet": 5}
GPU_UNCAPPED_PROFILES = ("balanced", "performance")
# Vendor performance_mode mapping.
VENDOR_MODE = {"quiet": 0, "balanced": 1, "performance": 2}

# Transport names: FamilyTransport.wire in FamilyCatalog.kt. An unknown name rejects the entry.
T_CPU_SCALING_MAX = "cpu-scaling-max"
T_CPU_MSM_PERFORMANCE_QOS = "cpu-msm-performance-qos"
T_GPU_KGSL_MAX_PWRLEVEL = "gpu-kgsl-max-pwrlevel"
T_GPU_DEVFREQ_MAX = "gpu-devfreq-max"
T_VENDOR_PERFORMANCE_MODE = "vendor-performance-mode"
T_FAN_PWM = "fan-pwm"
# FamilyCatalogLimits.SOC_ALIAS_EXACT / PLATFORM_ALIAS_EXACT
SOC_ALIAS_RE = re.compile(r"^[A-Z0-9_-]{2,32}$")
PLATFORM_ALIAS_RE = re.compile(r"^[a-z0-9_-]{2,32}$")

# Aliases the bundled catalog reserves today (HardwareClassifier.kt resolve(), plus the
# platforms D1/D4 name). A draft that reuses one would make the whole catalog be rejected.
# Informational only; the real tweaker-families.json is consulted too when it exists.
RESERVED = {
    "soc": {
        "SM8250": "qualcomm-sm8250",
        "QCS8550": "qualcomm-8gen2",
        "SM8550": "qualcomm-8gen2",
        "CQ8725S": "qualcomm-8-elite",
        "SM8750": "qualcomm-8-elite",
    },
    "platform": {
        "kona": "qualcomm-sm8250",
        "kalama": "qualcomm-8gen2",
        "sun": "qualcomm-8-elite",
    },
}

SENTINELS = {
    "<absent>": "absent",
    "<denied>": "denied",
    "<unreadable>": "unreadable",
    "<empty>": "empty",
    # never written by capture.sh; marks a value a hand-assembled corpus fixture does not have
    "<not-captured>": "notcaptured",
}
QUALCOMM_SOC = re.compile(r"^(?:SM|QCS|CQ|MSM|SDM)[0-9][A-Z0-9-]*$")
ID_RE = re.compile(r"^[a-z0-9-]{3,40}$")


class CaptureError(Exception):
    """The capture is unusable (truncated, or it has no SoC)."""


# --------------------------------------------------------------------------- parsing
class Capture:
    def __init__(self, text: str):
        self.sections: dict[str, list[str]] = {}
        self.owed_markers: list[str] = []
        current = None
        for raw in text.splitlines():
            line = raw.rstrip("\r").rstrip()
            if line.startswith("### "):
                current = line[4:].strip()
                self.sections.setdefault(current, [])
                continue
            if current is None or not line:
                continue
            if line.startswith("# daemon read owed:"):
                self.owed_markers.append(line.split(":", 1)[1].strip())
                continue
            if line.startswith("#"):
                continue
            self.sections[current].append(line)
        if "end" not in self.sections:
            raise CaptureError("capture is incomplete: no '### end' line (re-run capture.sh)")
        self._kv = {name: self._parse_kv(lines) for name, lines in self.sections.items()}

    @staticmethod
    def _parse_kv(lines):
        out = {}
        for line in lines:
            if "=" in line:
                key, _, value = line.partition("=")
                out[key.strip()] = value.strip()
        return out

    def status(self, section: str, key: str) -> str:
        """'value', 'absent', 'denied', 'unreadable', 'empty', 'notcaptured' or 'missing' (no such line)."""
        raw = self._kv.get(section, {}).get(key)
        if raw is None:
            return "missing"
        return SENTINELS.get(raw, "value")

    def value(self, section: str, key: str):
        return self._kv[section][key] if self.status(section, key) == "value" else None

    def present(self, section: str, key: str) -> bool:
        """The node exists (a denied or unreadable node still exists)."""
        return self.status(section, key) in ("value", "denied", "unreadable", "empty")

    def prop(self, key: str):
        return self.value("getprop", key)


def ints(text) -> list[int]:
    if not text:
        return []
    return [int(t) for t in re.findall(r"\d+", text)]


class Policy:
    def __init__(self, cap: Capture, pid: int):
        key = f"policy{pid}"
        self.id = pid
        self.cpus = ints(cap.value("cpufreq", f"{key}.related_cpus"))
        self.avail = sorted(set(f for f in ints(cap.value("cpufreq", f"{key}.scaling_available_frequencies")) if f > 0))
        # Same fallbacks as AndroidHardware.detectCpuPolicies(): cpuinfo_* first, else the table ends.
        cmax = ints(cap.value("cpufreq", f"{key}.cpuinfo_max_freq"))
        cmin = ints(cap.value("cpufreq", f"{key}.cpuinfo_min_freq"))
        self.cpuinfo_max = cmax[0] if cmax and cmax[0] > 0 else None
        self.cpuinfo_min_read = bool(cmin and cmin[0] > 0)
        self.max = self.cpuinfo_max or (self.avail[-1] if self.avail else None)
        self.min = cmin[0] if self.cpuinfo_min_read else (self.avail[0] if self.avail else None)
        smax = ints(cap.value("cpufreq", f"{key}.scaling_max_freq"))
        self.scaling_max = smax[0] if smax else None

    def live(self) -> list[int]:
        if self.min is None or self.max is None:
            return []
        return [f for f in self.avail if self.min <= f <= self.max]

    def live_max(self):
        live = self.live()
        return live[-1] if live else self.max


def policies_of(cap: Capture) -> list[Policy]:
    ids = sorted({int(m.group(1)) for k in cap._kv.get("cpufreq", {}) if (m := re.match(r"policy(\d+)\.", k))})
    return [Policy(cap, i) for i in ids]


def round_half_up(x: float) -> int:
    """Kotlin Double.roundToInt(); Python's round() is banker's and would differ on .5."""
    return int(math.floor(x + 0.5))


def snap_at_or_below(available: list[int], target: int, fallback: int) -> int:
    s = sorted({f for f in available if f > 0})
    below = [f for f in s if f <= target]
    return below[-1] if below else (s[0] if s else fallback)


def anchored_cpu_targets(profile: str, policies: list[Policy]):
    """TuningPlanner.odinAnchoredTransposedCpuTargetsKhz. None when it cannot be computed."""
    usable = [p for p in policies if p.live()]
    if len(usable) < 2 or len(usable) != len(policies):
        return None
    (lt, lm), (pt, pm) = ANCHOR_CPU[profile]["little"], ANCHOR_CPU[profile]["prime"]
    little, prime = lt / lm, pt / pm
    ordered = sorted(usable, key=lambda p: p.live_max())
    last = len(ordered) - 1
    out = {}
    for ordinal, p in enumerate(ordered):
        fraction = little + (ordinal / last) * (prime - little)
        live_max = p.live_max()
        out[p.id] = snap_at_or_below(p.live(), round_half_up(fraction * live_max), live_max)
    return out


def anchored_gpu_level(profile: str, level_count: int):
    """TuningPlanner.odinAnchoredTransposedGpuMaxPowerLevel (KGSL level 0 is the fastest)."""
    if level_count < 2:
        return None
    if profile in GPU_UNCAPPED_PROFILES:
        return 0
    if profile not in GPU_CAPPED:
        return None
    fraction = GPU_CAPPED[profile] / (GPU_ANCHOR_LEVELS - 1)
    return min(max(round_half_up(fraction * (level_count - 1)), 0), level_count - 1)


# --------------------------------------------------------------------------- the entry
def make_id(soc: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", soc.lower()).strip("-")
    return ("qualcomm-" + slug)[:40].rstrip("-")


def make_label(socs: list[str]) -> str:
    text = re.sub(r"[^\x20-\x7e]", "", "Qualcomm " + " / ".join(socs) + " (draft)")
    return text[:64]


def aliases_of(cap: Capture):
    """(socs, platforms, dropped): the observed names that fit the catalog's alias grammar.

    Exact aliases only: a draft never guesses a trailing `*` prefix. A name that does not fit
    (spaces, too long) is dropped here and reported as owed instead of producing a rejected entry.
    """
    socs, platforms, dropped = [], [], []
    for candidate in (cap.prop("ro.soc.model"), cap.prop("ro.vendor.qti.soc_model")):
        if not candidate:
            continue
        alias = candidate.upper()
        if not SOC_ALIAS_RE.match(alias):
            dropped.append(("soc", candidate))
        elif alias not in socs:
            socs.append(alias)
    platform = cap.prop("ro.board.platform")
    if platform:
        alias = platform.lower()
        if PLATFORM_ALIAS_RE.match(alias):
            platforms.append(alias)
        else:
            dropped.append(("platform", platform))
    return socs, platforms, dropped


def build_entry(cap: Capture):
    """The draft entry dict. All entry-shape decisions are here."""
    socs, platforms, _dropped = aliases_of(cap)
    if not socs:
        raise CaptureError("no usable SoC name: ro.soc.model and ro.vendor.qti.soc_model are missing or not alias-shaped")

    match = {"soc": socs}
    if platforms:
        match["platform"] = platforms

    entry = {
        "id": make_id(socs[0]),
        "label": make_label(socs),
        "treatment": "general-live-opp",
        "match": match,
        "cpu": {
            "kind": "anchor",
            "profiles": {
                name: {side: list(pair) for side, pair in ANCHOR_CPU[name].items()} for name in PROFILES
            },
        },
        "gpu": {
            "kind": "transposed",
            "anchorLevels": GPU_ANCHOR_LEVELS,
            "capped": dict(GPU_CAPPED),
            "uncapped": list(GPU_UNCAPPED_PROFILES),
        },
    }
    if cap.present("settings", "performance_mode"):
        entry["vendorMode"] = dict(VENDOR_MODE)

    # Informational "may probe" names, in the compiled enum's order; the node stays the gate.
    transports = []
    if any(cap.present("cpufreq", f"policy{p.id}.scaling_max_freq") for p in policies_of(cap)):
        transports.append(T_CPU_SCALING_MAX)
    if cap.present("msm_performance", "cpu_max_freq"):
        transports.append(T_CPU_MSM_PERFORMANCE_QOS)
    if cap.present("kgsl", "max_pwrlevel"):
        transports.append(T_GPU_KGSL_MAX_PWRLEVEL)
    if cap.present("kgsl", "devfreq_available_frequencies"):
        transports.append(T_GPU_DEVFREQ_MAX)
    if "vendorMode" in entry:
        transports.append(T_VENDOR_PERFORMANCE_MODE)
    if cap.present("gpio5_pwm2", "ls") and cap.status("gpio5_pwm2", "ls") != "empty":
        transports.append(T_FAN_PWM)
    if transports:
        entry["transports"] = transports
    return entry


# --------------------------------------------------------------------------- stderr notes
def alias_matches(a: str, b: str) -> bool:
    a, b = a.lower(), b.lower()
    if a.endswith("*"):
        a, b = b, a
    if b.endswith("*"):
        return a.startswith(b[:-1])
    return a == b


def reserved_conflicts(entry, catalog_path):
    """Aliases the draft shares with an existing family: (kind, alias, owner id)."""
    known = []  # (kind, alias, owner)
    for kind, table in RESERVED.items():
        known += [(kind, alias, owner) for alias, owner in table.items()]
    catalog_note = None
    if catalog_path and os.path.isfile(catalog_path):
        try:
            with open(catalog_path, encoding="utf-8") as fh:
                catalog = json.load(fh)
            for fam in catalog.get("families", []):
                for kind in ("soc", "platform"):
                    for alias in (fam.get("match") or {}).get(kind, []):
                        known.append((kind, alias, fam.get("id", "?")))
            catalog_note = catalog_path
        except (OSError, ValueError) as exc:
            catalog_note = f"{catalog_path} unreadable ({exc})"
    hits = []
    for kind in ("soc", "platform"):
        for alias in entry["match"].get(kind, []):
            for k, other, owner in known:
                if k == kind and alias_matches(alias, other) and (kind, alias, owner) not in hits:
                    hits.append((kind, alias, owner))
    return hits, catalog_note


def describe(cap: Capture, entry, catalog_path=None):
    """Returns (notes, owed, snapped) as lists of strings for stderr."""
    notes, owed, snapped = [], [], []
    policies = policies_of(cap)

    for kind, name in aliases_of(cap)[2]:
        owed.append(f"{kind} name {name!r} does not fit the catalog's alias grammar and was left out of match.{kind}")

    # reserved aliases first: a doomed entry should be obvious
    hits, catalog_note = reserved_conflicts(entry, catalog_path)
    own = entry["id"]
    for kind, alias, owner in hits:
        if owner == own:
            continue
        notes.append(
            f"match.{kind} alias {alias} is already claimed by {owner}; this draft is a "
            f"re-derivation to compare against, not a new entry. Appending it would make the whole catalog be rejected."
        )
    if catalog_note is None:
        owed.append("alias overlap was only checked against the built-in reserved aliases (tweaker-families.json not found)")

    if not QUALCOMM_SOC.match(entry["match"]["soc"][0]):
        notes.append(
            f"{entry['match']['soc'][0]} does not look like a Qualcomm SoC name; the id prefix 'qualcomm-' was kept by convention. Review it."
        )

    # CPU
    if not policies:
        owed.append("no cpufreq policy was captured: the CPU topology is owed")
    else:
        tops = ", ".join(
            f"policy{p.id} (cpus {' '.join(map(str, p.cpus)) or '?'}) top {p.live_max() or '?'}"
            for p in sorted(policies, key=lambda p: p.live_max() or 0)
        )
        notes.append(f"CPU policies by live max: {tops}")
        if len(policies) < 2:
            owed.append("only one cpufreq policy: the anchor transposition needs a little and a prime cluster, so no CPU vector will be derived")
        for p in policies:
            if not p.avail:
                owed.append(f"policy{p.id}.scaling_available_frequencies is missing from the capture: its OPP table (and the snapped kHz) is owed")
            if p.cpuinfo_max and p.avail and p.cpuinfo_max != p.avail[-1]:
                notes.append(
                    f"policy{p.id}: cpuinfo_max_freq {p.cpuinfo_max} differs from the highest available OPP {p.avail[-1]}; "
                    f"the planner's live max is the available OPP {p.live_max()}"
                )
            if p.avail and not p.cpuinfo_min_read:
                owed.append(
                    f"policy{p.id}.cpuinfo_min_freq is missing from the capture (the shell was refused, or not captured): the OPP floor was taken as the lowest available OPP {p.avail[0]}"
                )
        clamped = [p for p in policies if p.scaling_max and p.avail and p.scaling_max < p.avail[-1]]
        if clamped:
            notes.append(
                "scaling_max_freq is below the table top on "
                + ", ".join(f"policy{p.id} ({p.scaling_max} < {p.avail[-1]})" for p in clamped)
                + ": a clamp (a Tweaker profile or the vendor) was standing at capture time. "
                "OPP maxima were taken from scaling_available_frequencies, never from the clamp."
            )

        for name in PROFILES:
            targets = anchored_cpu_targets(name, policies)
            if targets is None:
                snapped.append(f"{name:<12} (not derivable from this capture)")
            else:
                snapped.append(
                    f"{name:<12} " + "  ".join(f"policy{pid} {khz}" for pid, khz in sorted(targets.items()))
                )

    # GPU
    freqs_hz = ints(cap.value("kgsl", "gpu_available_frequencies"))
    levels = len(freqs_hz)
    if levels:
        gpu_khz = sorted({f // 1000 for f in freqs_hz})
        parts = []
        for name in PROFILES:
            level = anchored_gpu_level(name, levels)
            if level is None:
                parts.append(f"{name} n/a")
            else:
                label = "uncapped" if name in GPU_UNCAPPED_PROFILES else f"{sorted(gpu_khz, reverse=True)[level]} kHz"
                parts.append(f"{name} max_pwrlevel {level} ({label})")
        snapped.append(f"GPU ({levels} levels, {gpu_khz[0]}..{gpu_khz[-1]} kHz): " + "; ".join(parts))
        declared = ints(cap.value("kgsl", "num_pwrlevels"))
        if declared and declared[0] != levels:
            notes.append(f"kgsl num_pwrlevels {declared[0]} but gpu_available_frequencies lists {levels}: reconcile before trusting the GPU levels")
        if levels < 2:
            owed.append("the GPU table has fewer than two levels: no GPU ceiling can be derived")
    else:
        owed.append("kgsl gpu_available_frequencies was not readable: the GPU table is owed")

    # vendor mode
    for line in cap.sections.get("settings", []):
        if line.startswith("performance_mode="):
            notes.append(
                f"vendorMode drafted as quiet 0 / balanced 1 / performance 2 (configured mapping); performance_mode read {line.split('=', 1)[1]} at capture time"
            )
            owed.append("whether this device's vendor stack honours performance_mode 0/1/2 as mapped is untested (read-only capture)")
    if "vendorMode" not in entry:
        notes.append("no performance_mode setting found: vendorMode omitted")

    # transports and what the node cannot say
    if cap.present("msm_performance", "cpu_max_freq"):
        raw = cap.value("msm_performance", "cpu_max_freq")
        if raw is None:
            owed.append("msm_performance cpu_max_freq exists but the shell could not read it: SHARED_QOS coherence needs a daemon read")
        else:
            qos = {}
            ok = True
            for tok in raw.split():
                m = re.fullmatch(r"(\d{1,3}):(\d{1,10})", tok)
                if not m or int(m.group(1)) in qos:
                    ok = False
                    break
                qos[int(m.group(1))] = int(m.group(2))
            expected = {c for p in policies for c in p.cpus}
            coherent = ok and qos and set(qos) == expected and all(
                len({qos.get(c) for c in p.cpus}) == 1 for p in policies
            )
            notes.append(
                "msm_performance cpu_max_freq is "
                + ("a coherent per-policy map (the SHARED_QOS gate would pass)" if coherent else "present but NOT a coherent map over the captured policies (the SHARED_QOS gate would refuse)")
            )
    else:
        owed.append("msm_performance cpu_max_freq is absent or not captured: whether it exists, and whether it is shell-readable or daemon-only, is owed")

    if cap.present("kgsl", "max_pwrlevel"):
        owed.append(
            "whether a max_pwrlevel write holds, or the devfreq cap is needed instead, is untested (the Thor's did not hold)"
            if cap.present("kgsl", "devfreq_available_frequencies")
            else "whether a max_pwrlevel write holds on this device is untested"
        )
    if T_FAN_PWM in entry.get("transports", []):
        owed.append("the gpio5_pwm2 node being listed is not fan behaviour: duty/period/tachometer response is untested")

    pservice = cap.prop("init.svc.pservice")
    if pservice is None:
        owed.append("init.svc.pservice is missing from the capture: whether a PServer runs here is owed")
    elif pservice != "running":
        owed.append(f"init.svc.pservice is {pservice}, not running: the PServer-dependent transports are unproven here")

    cadence = [line for line in cap.sections.get("logcat_cpugpumode", []) if "cpugpumode" in line]
    if cadence:
        notes.append(f"logcat held {len(cadence)} cpugpumode line(s); read the timestamps in the capture for the cadence")
    else:
        owed.append("no cpugpumode lines were in the logcat buffer: the vendor cadence is owed (capture again right after changing performance mode)")

    denied = list(dict.fromkeys(cap.owed_markers))
    if denied:
        owed.append("daemon read owed (the shell was refused): " + ", ".join(denied))
    missing = [
        f"{section}.{key}" if section not in ("cpufreq", "msm_performance") else key
        for section, kv in cap._kv.items()
        for key, raw in kv.items()
        if SENTINELS.get(raw) == "notcaptured"
    ]
    if missing:
        owed.append("not captured (a hand-assembled fixture, not a device run): " + ", ".join(missing))

    owed.append("treatment stays general-live-opp: promoting to mimir-certified-reference requires review after measurement")
    owed.append("nothing here shows the anchor fractions suit this SoC thermally: a first on-device test of the entry is owed")
    return notes, owed, snapped


# --------------------------------------------------------------------------- output
def dumps(obj, level=0) -> str:
    """JSON, 2-space indent, lists of scalars on one line (pairs stay readable)."""
    pad = "  " * level
    if isinstance(obj, dict):
        if not obj:
            return "{}"
        inner = ",\n".join(f"{pad}  {json.dumps(k)}: {dumps(v, level + 1)}" for k, v in obj.items())
        return "{\n" + inner + "\n" + pad + "}"
    if isinstance(obj, list):
        if all(not isinstance(x, (dict, list)) for x in obj):
            return "[" + ", ".join(json.dumps(x) for x in obj) + "]"
        inner = ",\n".join(f"{pad}  {dumps(x, level + 1)}" for x in obj)
        return "[\n" + inner + "\n" + pad + "]"
    return json.dumps(obj)


def draft(text: str, catalog_path=None):
    """Returns (entry, notes, owed, snapped). Raises CaptureError on an unusable capture."""
    cap = Capture(text)
    entry = build_entry(cap)
    notes, owed, snapped = describe(cap, entry, catalog_path)
    return entry, notes, owed, snapped


def default_catalog_path():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))
    return os.path.join(root, "mimir-tweaker", "app", "src", "main", "assets", "tweaker-families.json")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Draft a family-catalog entry from a capture.sh capture.")
    ap.add_argument("capture", help="capture file written by capture.sh ('-' reads stdin)")
    ap.add_argument("--catalog", default=None, help="catalog to check alias overlap against (default: the bundled tweaker-families.json when present)")
    args = ap.parse_args(argv)

    data = sys.stdin.buffer.read() if args.capture == "-" else open(args.capture, "rb").read()
    text = data.decode("utf-8", errors="replace")
    try:
        entry, notes, owed, snapped = draft(text, args.catalog or default_catalog_path())
    except CaptureError as exc:
        sys.stderr.write(f"draft: {exc}\n")
        return 2

    err = []
    err.append(f"# draft entry {entry['id']}")
    if snapped:
        err.append("# snapped, eyeball these (kHz; the planner recomputes them from the live tables):")
        err += [f"#   {line}" for line in snapped]
    if notes:
        err.append("# notes:")
        err += [f"#   - {line}" for line in notes]
    err.append("# owed (what a read-only capture cannot see):")
    err += [f"#   - {line}" for line in owed]
    sys.stderr.write("\n".join(err) + "\n")

    # bytes, so a Windows console redirect cannot turn LF into CRLF
    sys.stdout.buffer.write((dumps(entry) + "\n").encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
