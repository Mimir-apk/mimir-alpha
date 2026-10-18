#!/usr/bin/env python3
"""Mimir-rewrite hardcoded UI text ratchet (localization step 0).

Flags new hardcoded `Text("...")` in feature modules. Nearly all on-screen text is still a Kotlin literal, so this gate cannot
ban them; it counts them and refuses to let the count rise. Steps 1-9 extract a screen at a time and lower the
baseline in the same PR.

What is counted: a string literal that is the first argument (positional, or `text =`) of one of the text
composables in TEXT_CALLEES, in a module's `src/main`, and that holds at least one letter. `Text("")`, a glyph
such as `Text("•")` and a pure template such as `Text("$count")` carry nothing to translate and are not counted;
`Text("$count items")` is. A literal that reaches a composable through a variable, a `when` or an `if` is not
seen: this is a ratchet on the common shape, not a proof. Comments and strings in comments are ignored (the
same Kotlin lexer the UI invariants gate uses).

Modules: `app`, `core-ui` and every `feature-*` (the modules that draw UI). The Tweaker is a separate Gradle
build with its own strings and is not scanned.

Baseline: tools/mimir-hardcoded-text-baseline.tsv, `module<TAB>count`. A module absent from the file has a
baseline of 0. The gate FAILS when a module's count is above its baseline (and lists that module's sites), and
PASSES with a note when a count is below it. After an extraction, lock the gain in with
`python3 tools/mimir_hardcoded_text_gate.py --update-baseline` and commit the result. Raising a baseline is a
decision that needs a reason in the commit message.
"""

from __future__ import annotations

import importlib.util
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

TOOLS_DIR = Path(__file__).resolve().parent
ROOT = TOOLS_DIR.parent
BASELINE_PATH = TOOLS_DIR / "mimir-hardcoded-text-baseline.tsv"
BASELINE_HEADER = ("module", "count")

# Composables whose first parameter is the displayed `text: String`. `Text` is the Compose one; the Mimir
# wrappers are what most feature code actually calls (core-ui MimirTextIntent / MimirControllerHints /
# MimirWordEllipsis). The intent-taking overloads (MimirIntentText, MimirAnimatedChromeText(intent = ...)) wrap a
# MimirTextIntent value and are out of reach of a lexical check.
TEXT_CALLEES: tuple[str, ...] = (
    "Text",
    "MimirChromeText",
    "MimirAnimatedChromeText",
    "MimirControlHintText",
    "MimirWordEllipsisText",
)
_CALLEE_ALTERNATION = "|".join(TEXT_CALLEES)
_CALL_PREFIX_RE = re.compile(rf"\b(?:{_CALLEE_ALTERNATION})\s*\(\s*(?:text\s*=\s*)?\Z")
_CALL_PREFIX_LOOKBACK = 160


def _load_ui_gate():
    """The UI invariants gate owns the Kotlin lexer and the worktree-aware repo walk; reuse both."""
    path = TOOLS_DIR / "mimir_ui_invariants_gate.py"
    spec = importlib.util.spec_from_file_location("_mimir_ui_invariants_gate_for_text_gate", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


_ui_gate = _load_ui_gate()


@dataclass(frozen=True)
class Site:
    module: str
    rel_path: str
    line_no: int
    text: str


def scanned_module(rel_posix: str) -> str | None:
    """The module a `src/main` Kotlin file belongs to when the gate scans it, else None."""
    parts = rel_posix.split("/")
    if len(parts) < 4 or parts[1] != "src" or parts[2] != "main":
        return None
    module = parts[0]
    if module == "app" or module == "core-ui" or module.startswith("feature-"):
        return module
    return None


def count_sites(source: str, module: str, rel_path: str) -> list[Site]:
    """Hardcoded text sites in one Kotlin source."""
    lexer = _ui_gate.KotlinLexer(source)
    code_view, _ = lexer.build_views()
    sites: list[Site] = []
    for token in lexer.literals:
        if not any(ch.isalpha() for ch in token.decoded):
            continue
        prefix = code_view[max(0, token.start - _CALL_PREFIX_LOOKBACK) : token.start]
        if not _CALL_PREFIX_RE.search(prefix):
            continue
        line_no = source.count("\n", 0, token.start) + 1
        sites.append(Site(module, rel_path, line_no, token.decoded))
    return sites


def scan_repo(root: Path) -> list[Site]:
    sites: list[Site] = []
    for path in _ui_gate.iter_kotlin_source_paths(root):
        rel = path.relative_to(root).as_posix()
        module = scanned_module(rel)
        if module is None:
            continue
        sites.extend(count_sites(path.read_text(encoding="utf-8"), module, rel))
    sites.sort(key=lambda s: (s.module, s.rel_path, s.line_no))
    return sites


def module_counts(sites: Iterable[Site]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for site in sites:
        counts[site.module] = counts.get(site.module, 0) + 1
    return counts


def scanned_modules(root: Path) -> list[str]:
    """Every module directory the gate covers, so a module at zero still has a baseline row."""
    found = []
    for child in sorted(root.iterdir()):
        if child.is_dir() and (child.name in ("app", "core-ui") or child.name.startswith("feature-")):
            if (child / "src" / "main").is_dir():
                found.append(child.name)
    return found


def load_baseline(path: Path) -> dict[str, int]:
    if not path.is_file():
        raise ValueError(f"baseline missing: {path}")
    baseline: dict[str, int] = {}
    saw_header = False
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw.strip() or raw.startswith("#"):
            continue
        cells = raw.split("\t")
        if not saw_header:
            if tuple(cells) != BASELINE_HEADER:
                raise ValueError(f"{path.name}:{number}: header must be {'<TAB>'.join(BASELINE_HEADER)}")
            saw_header = True
            continue
        if len(cells) != 2 or not cells[1].isdigit():
            raise ValueError(f"{path.name}:{number}: expected 'module<TAB>count'")
        if cells[0] in baseline:
            raise ValueError(f"{path.name}:{number}: duplicate module {cells[0]}")
        baseline[cells[0]] = int(cells[1])
    if not saw_header:
        raise ValueError(f"{path.name}: header row missing")
    return baseline


def write_baseline(path: Path, root: Path, counts: dict[str, int]) -> None:
    lines = [
        "# Hardcoded UI text ratchet. module<TAB>count of string-literal Text(...) calls in src/main.",
        "# A module's count may fall, never rise. Regenerate with tools/mimir_hardcoded_text_gate.py --update-baseline.",
        "\t".join(BASELINE_HEADER),
    ]
    for module in sorted(set(scanned_modules(root)) | set(counts)):
        lines.append(f"{module}\t{counts.get(module, 0)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def evaluate(counts: dict[str, int], baseline: dict[str, int]) -> tuple[list[str], list[str]]:
    """(rising modules, modules whose baseline can be lowered)."""
    rising = sorted(m for m, n in counts.items() if n > baseline.get(m, 0))
    lowerable = sorted(m for m, allowed in baseline.items() if counts.get(m, 0) < allowed)
    return rising, lowerable


def run_gate(root: Path = ROOT, baseline_path: Path = BASELINE_PATH, update: bool = False) -> int:
    sites = scan_repo(root)
    counts = module_counts(sites)
    if update:
        write_baseline(baseline_path, root, counts)
        total = sum(counts.values())
        print(f"HARDCODED TEXT GATE: baseline written ({total} site(s) in {len(counts)} module(s))")
        return 0
    try:
        baseline = load_baseline(baseline_path)
    except ValueError as exc:
        print(f"HARDCODED TEXT GATE: FAIL ({exc})")
        return 1
    rising, lowerable = evaluate(counts, baseline)
    if rising:
        print(f"HARDCODED TEXT GATE: FAIL ({len(rising)} module(s) added hardcoded UI text)")
        for module in rising:
            print(f"  {module}: {counts[module]} site(s), baseline {baseline.get(module, 0)}")
            for site in sites:
                if site.module == module:
                    print(f"    {site.rel_path}:{site.line_no} {site.text!r}")
        print("  Put the text in a string resource (stringResource) instead of a literal.")
        return 1
    total = sum(counts.values())
    print(f"HARDCODED TEXT GATE: PASS ({total} site(s) across {len(counts)} module(s), baseline respected)")
    for module in lowerable:
        print(
            f"  note: {module} is at {counts.get(module, 0)}, baseline {baseline[module]}; "
            "run tools/mimir_hardcoded_text_gate.py --update-baseline to lock the gain in"
        )
    return 0


def main(argv: list[str]) -> int:
    if argv == ["--update-baseline"]:
        return run_gate(update=True)
    if argv:
        print("usage: mimir_hardcoded_text_gate.py [--update-baseline]", file=sys.stderr)
        return 2
    return run_gate()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
