#!/usr/bin/env python3
"""Mimir Alpha UI invariants gate (F1 focus + P1 feature hex + R1 regex brackets
+ D1/D2/D3 must-consume).

Kotlin-aware lexer strips comments and emits exact-length views:
  - code view (strings masked) for F1
  - P1 view (comments stripped, strings preserved) for feature-main hex scans

The same lexer pass also collects decoded string-literal tokens (Kotlin
escapes resolved) used by R1: a check for regex patterns passed to
Regex(...)/Pattern.compile(...)/.toRegex() that contain a bare, unescaped
']' or '}' outside a character class. OpenJDK's java.util.regex accepts
these permissively as literal characters; Android's ICU-backed engine
rejects them with PatternSyntaxException at static-initializer time (see
GameModeFpsLimiter.kt history). R1 runs across all Kotlin sources, same
scope as F1.

D1/D2/D3 ("must-consume"): a defect class where a function computes a result
that carries a refusal/failure case and a caller discards it, so a declined
operation and a successful one become indistinguishable to the player. The registry of
load-bearing producers lives in tools/mimir-must-consume-producers.tsv (4
columns); this file only knows how to *apply* the three sub-rules to the
code view, on the same lexer output F1 already uses:

  - D1: a registered producer called in **statement position** (its result
    is neither bound, returned, nor chained -- see `_is_statement_start` /
    `_following_class`).
  - D2: a registered sealed result type collapsed by `==`/`!=` to one of its
    arms *outside* a decision (`if`/`while`/`when` condition) -- see
    `_enclosing_control_keyword`.
  - D3: a registered producer whose result gates a `?: return...` / `if (!
    producer(...)) return...` early exit whose body contains no call to a
    registered reporting channel -- see `_check_d3_elvis` /
    `_check_d3_negated_if`.

All three are lexical, not type-aware: they see exactly what F1/P1/R1 see
(strings masked, comments stripped), so they can be fooled by sufficiently
unusual formatting, but not by a producer name inside a comment or a string
literal. Known-unreachable shapes (a partially-consumed result, two `when`
arms with an identical body, a result type with no field for the failure it
must carry, an `Unit`-returning mutation, a default value built inside a
`catch`) are out of scope for this file by design -- see the spec's own
coverage table -- and stay covered, if at all, by source-characterization
wiring tests, not by this gate.

Waivers: tools/mimir-ui-invariants-waivers.tsv (7 columns). A waiver names its exception by
CONTENT -- an `anchor` substring that must share a line with `exact_match` -- not by line number.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Iterable, Optional

ROOT = Path(__file__).resolve().parent.parent
WAIVER_PATH = Path(__file__).resolve().parent / "mimir-ui-invariants-waivers.tsv"

# Nested agent worktree/cache roots must not inflate Kotlin enumeration.
EXCLUDED_WORKTREE_PREFIXES: tuple[str, ...] = (
    ".worktrees/",
    ".cursor/worktrees/",
)

WAIVER_HEADER = ("rule", "path", "anchor", "exact_match", "reason", "approver", "date")

F1_RULES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("import androidx.compose.foundation.focusable", re.compile(r"import\s+androidx\.compose\.foundation\.focusable\b")),
    ("import androidx.compose.ui.focus.focusTarget", re.compile(r"import\s+androidx\.compose\.ui\.focus\.focusTarget\b")),
    (".focusable(", re.compile(r"\.focusable\s*\(")),
    ("focusTarget(", re.compile(r"\bfocusTarget\s*\(")),
)

P1_RULES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("Color(0x", re.compile(r"\bColor\s*\(\s*0[xX][0-9A-Fa-f_]+\s*\)")),
    ("Color.rgb/argb", re.compile(r"\bColor\s*\.\s*(?:rgb|argb)\s*\(")),
    ("parseColor", re.compile(r"\bparseColor\s*\(")),
    ("#hex-string", re.compile(r'"#[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{1,5})?"')),
    ("0x-literal", re.compile(r"\b0[xX][0-9A-Fa-f_]{6,}(?:[uU]|(?:[uU])?L)?\b")),
)

_HEX_CHARS = set("0123456789abcdefABCDEF")

PRODUCER_PATH = Path(__file__).resolve().parent / "mimir-must-consume-producers.tsv"
PRODUCER_HEADER = ("rule", "producer", "scope_path", "note")
_PRODUCER_RULES = ("D1", "D2", "D3")


def hex_digits_valid(token: str) -> bool:
    body = token[2:]
    if body.endswith(("u", "U")):
        body = body[:-1]
    elif len(body) > 1 and body[-1] in "lL" and body[-2] in "uU":
        body = body[:-2]
    elif body and body[-1] in "lL":
        body = body[:-1]
    digits = body.replace("_", "")
    return len(digits) >= 6 and all(ch in _HEX_CHARS for ch in digits)


@dataclass(frozen=True)
class Producer:
    """A must-consume registry row: a producer whose result is load-bearing.

    ``rule`` selects which of D1/D2/D3 applies:
      - D1/D3: ``producer`` is a call target, either bare ("persist") or
        qualified with a literal receiver ("CustomHeroStore.clearAsAutomatic").
      - D2: ``producer`` is a sealed/enum type name ("ScanOutcome") whose
        arms are matched as ``<producer>.<Arm>``.
    ``scope_path`` is an optional substring the file's repo-relative path
    must contain; empty means repo-wide. It exists because some producer
    names (e.g. a private ``persist`` method) collide textually with
    unrelated same-named functions elsewhere in the tree -- scoping is how
    the registry stays precise without inventing a fake qualifier.
    """

    rule: str
    producer: str
    scope_path: str
    note: str


@dataclass(frozen=True)
class Waiver:
    """
    A waiver identifies its exception by CONTENT, not by position.

    `anchor` is a substring that must appear on the same line as `exact_match` -- in practice the
    declaring symbol, e.g. `PREVIEW_BG` beside `0xFF080D12`. The pair must resolve to exactly one line
    in the file, and that line must carry exactly one live finding.

    It used to be a line number, and that broke three times in one evening (2026-08-30) while
    `CompanionSurface.kt` lost 400 lines to a retirement and gained a job line. The waived thing never
    changed once. A line number tracks where an exception sits; nothing anyone cares about is *where*.
    Every strictness property survives the swap: a renamed symbol, a changed literal, two matching
    lines, or a finding that has gone away all still fail.
    """

    rule: str
    rel_path: str
    anchor: str
    exact_match: str
    reason: str
    approver: str
    waiver_date: str


@dataclass(frozen=True)
class Finding:
    rule: str
    rel_path: str
    line_no: int
    column: int
    exact_match: str
    line_text: str
    detail: str

    @property
    def key(self) -> tuple[str, str, int, str]:
        return (self.rule, self.rel_path, self.line_no, self.exact_match)


@dataclass(frozen=True)
class LiteralToken:
    """A single top-level (or nested) Kotlin string literal, as parsed by KotlinLexer.

    ``decoded`` is the literal's *runtime* string value with Kotlin escapes
    resolved (``\\\\`` -> ``\\``, ``\\n`` -> newline, ``\\uXXXX`` -> the
    unicode char, ...); raw (triple-quoted) strings have no escapes so
    ``decoded`` is a verbatim copy of their source content. ``offsets[i]``
    is the source index that produced ``decoded[i]`` (the backslash index
    for an escape sequence), so a position found in ``decoded`` can be
    mapped back to an exact source line/column.

    ``fully_literal`` is False when the literal contains any
    ``${...}``/``$name`` interpolation (dynamic content we cannot resolve
    statically) or an escape KotlinLexer could not decode; callers must
    treat such tokens as "value unknown" rather than trust ``decoded``.
    """

    start: int
    end: int
    decoded: str
    offsets: tuple[int, ...]
    fully_literal: bool


_KOTLIN_SIMPLE_ESCAPES: dict[str, str] = {
    "t": "\t",
    "b": "\b",
    "n": "\n",
    "r": "\r",
    "'": "'",
    '"': '"',
    "\\": "\\",
    "$": "$",
}

_HEX_DIGITS = set("0123456789abcdefABCDEF")

# Simple (brace-less) Kotlin string-template interpolation: a bare '$' is
# only special when followed by a valid identifier-start character.
_SIMPLE_INTERP_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def _decode_kotlin_escape(source: str, backslash_index: int) -> tuple[Optional[str], int]:
    """Decode one Kotlin escape sequence starting at source[backslash_index] == '\\'.

    Returns (decoded_char_or_None, raw_source_chars_consumed_including_backslash).
    None means the escape could not be resolved (would not compile as valid
    Kotlin, or a malformed \\uXXXX); callers must treat the owning literal as
    not fully literal rather than guess.
    """
    n = len(source)
    if backslash_index + 1 >= n:
        return None, 1
    nxt = source[backslash_index + 1]
    if nxt in _KOTLIN_SIMPLE_ESCAPES:
        return _KOTLIN_SIMPLE_ESCAPES[nxt], 2
    if nxt == "u":
        digits = source[backslash_index + 2 : backslash_index + 6]
        if len(digits) == 4 and all(c in _HEX_DIGITS for c in digits):
            return chr(int(digits, 16)), 6
        return None, 2
    return None, 2


class KotlinLexer:
    """Kotlin lexer producing exact-length code and P1 views."""

    def __init__(self, source: str) -> None:
        self.source = source
        self.n = len(source)
        self.code_chars: list[str] = []
        self.p1_chars: list[str] = []
        self.literals: list[LiteralToken] = []

    def _emit(self, ch: str, *, code: str, p1: str) -> None:
        self.code_chars.append(code)
        self.p1_chars.append(p1)

    def _emit_range(self, start: int, end: int, *, code: str, p1: str) -> None:
        span = end - start
        self.code_chars.extend(code * span)
        self.p1_chars.extend(p1 * span)

    def _mask(self, ch: str) -> None:
        self._emit(ch, code=(" " if ch != "\n" else "\n"), p1=(" " if ch != "\n" else "\n"))

    def _keep(self, ch: str) -> None:
        self._emit(ch, code=ch, p1=ch)

    def build_views(self) -> tuple[str, str]:
        i = 0
        while i < self.n:
            ch = self.source[i]
            nxt = self.source[i + 1] if i + 1 < self.n else ""
            nxt2 = self.source[i + 2] if i + 2 < self.n else ""

            if ch == "/" and nxt == "/":
                self._mask(ch)
                self._mask(nxt)
                j = i + 2
                while j < self.n and self.source[j] != "\n":
                    self._mask(self.source[j])
                    j += 1
                i = j
                continue

            if ch == "/" and nxt == "*":
                i = self._block_comment(i)
                continue

            if ch == '"':
                if nxt == '"' and nxt2 == '"':
                    i = self._raw_string(i)
                else:
                    i = self._normal_string(i)
                continue

            if ch == "'":
                i = self._char_literal(i)
                continue

            if ch == "`":
                i = self._backtick_identifier(i)
                continue

            self._keep(ch)
            i += 1

        code_view = "".join(self.code_chars)
        p1_view = "".join(self.p1_chars)
        if len(code_view) != self.n or len(p1_view) != self.n:
            raise ValueError("lexer views diverged from source length")
        return code_view, p1_view

    def _block_comment(self, start: int) -> int:
        depth = 1
        self._mask(self.source[start])
        self._mask(self.source[start + 1])
        i = start + 2
        while i < self.n and depth > 0:
            ch = self.source[i]
            nxt = self.source[i + 1] if i + 1 < self.n else ""
            if ch == "/" and nxt == "*":
                self._mask(ch)
                self._mask(nxt)
                depth += 1
                i += 2
                continue
            if ch == "*" and nxt == "/":
                self._mask(ch)
                self._mask(nxt)
                depth -= 1
                i += 2
                continue
            self._mask(ch)
            i += 1
        return i

    def _normal_string(self, start: int) -> int:
        i = start
        self._emit(self.source[i], code=" ", p1=self.source[i])
        i += 1
        decoded: list[str] = []
        offsets: list[int] = []
        fully_literal = True
        while i < self.n:
            ch = self.source[i]
            if ch == "\\" and i + 1 < self.n:
                decoded_ch, consumed = _decode_kotlin_escape(self.source, i)
                for k in range(consumed):
                    raw = self.source[i + k]
                    self._emit(raw, code=" ", p1=raw)
                if decoded_ch is None:
                    fully_literal = False
                else:
                    decoded.append(decoded_ch)
                    offsets.append(i)
                i += consumed
                continue
            if ch == '"':
                self._emit(ch, code=" ", p1=ch)
                self.literals.append(
                    LiteralToken(start, i + 1, "".join(decoded), tuple(offsets), fully_literal)
                )
                return i + 1
            if ch == "$" and i + 1 < self.n and self.source[i + 1] == "{":
                self._emit("$", code="$", p1="$")
                self._emit("{", code="{", p1="{")
                i += 2
                fully_literal = False
                i = self._template_expression(i, depth=1)
                continue
            if ch == "$" and i + 1 < self.n and (self.source[i + 1].isalpha() or self.source[i + 1] == "_"):
                m = _SIMPLE_INTERP_RE.match(self.source, i + 1)
                end = m.end() if m else i + 1
                for k in range(i, end):
                    raw = self.source[k]
                    self._emit(raw, code=" ", p1=raw)
                fully_literal = False
                i = end
                continue
            decoded.append(ch)
            offsets.append(i)
            self._emit(ch, code=" ", p1=ch)
            i += 1
        self.literals.append(LiteralToken(start, i, "".join(decoded), tuple(offsets), False))
        return i

    def _raw_string(self, start: int) -> int:
        literal_start = start
        i = start
        for _ in range(3):
            self._emit(self.source[i], code=" ", p1=self.source[i])
            i += 1
        decoded: list[str] = []
        offsets: list[int] = []
        fully_literal = True
        while i < self.n:
            if self.source[i : i + 3] == '"""':
                for _ in range(3):
                    self._emit(self.source[i], code=" ", p1=self.source[i])
                    i += 1
                self.literals.append(
                    LiteralToken(literal_start, i, "".join(decoded), tuple(offsets), fully_literal)
                )
                return i
            if self.source[i] == "$" and i + 1 < self.n and self.source[i + 1] == "{":
                self._emit("$", code="$", p1="$")
                self._emit("{", code="{", p1="{")
                i += 2
                fully_literal = False
                i = self._template_expression(i, depth=1)
                continue
            if self.source[i] == "$" and i + 1 < self.n and (
                self.source[i + 1].isalpha() or self.source[i + 1] == "_"
            ):
                m = _SIMPLE_INTERP_RE.match(self.source, i + 1)
                end = m.end() if m else i + 1
                for k in range(i, end):
                    raw = self.source[k]
                    self._emit(raw, code=" ", p1=raw)
                fully_literal = False
                i = end
                continue
            ch = self.source[i]
            decoded.append(ch)
            offsets.append(i)
            self._emit(ch, code=" ", p1=ch)
            i += 1
        self.literals.append(LiteralToken(literal_start, i, "".join(decoded), tuple(offsets), False))
        return i

    def _char_literal(self, start: int) -> int:
        i = start
        self._emit(self.source[i], code=" ", p1=self.source[i])
        i += 1
        while i < self.n:
            ch = self.source[i]
            if ch == "\\" and i + 1 < self.n:
                nxt = self.source[i + 1]
                self._emit(ch, code=" ", p1=ch)
                self._emit(nxt, code=" ", p1=nxt)
                i += 2
                continue
            self._emit(ch, code=" ", p1=ch)
            i += 1
            if ch == "'":
                return i
        return i

    def _backtick_identifier(self, start: int) -> int:
        i = start
        self._keep(self.source[i])
        i += 1
        while i < self.n:
            ch = self.source[i]
            self._keep(ch)
            i += 1
            if ch == "`":
                return i
        return i

    def _template_expression(self, i: int, depth: int) -> int:
        while i < self.n and depth > 0:
            ch = self.source[i]
            nxt = self.source[i + 1] if i + 1 < self.n else ""
            nxt2 = self.source[i + 2] if i + 2 < self.n else ""

            if ch == "/" and nxt == "/":
                self._mask(ch)
                self._mask(nxt)
                j = i + 2
                while j < self.n and self.source[j] != "\n":
                    self._mask(self.source[j])
                    j += 1
                i = j
                continue

            if ch == "/" and nxt == "*":
                i = self._block_comment(i)
                continue

            if ch == '"':
                if nxt == '"' and nxt2 == '"':
                    i = self._raw_string(i)
                else:
                    i = self._normal_string(i)
                continue

            if ch == "'":
                i = self._char_literal(i)
                continue

            if ch == "{":
                depth += 1
                self._keep(ch)
                i += 1
                continue

            if ch == "}":
                depth -= 1
                self._keep(ch)
                i += 1
                continue

            self._keep(ch)
            i += 1
        return i


# --- R1: regex bracket-escape scan -----------------------------------------
#
# Target: string literals (single or '+'-concatenated) that are the pattern
# argument of Regex(...) / Pattern.compile(...), or the receiver of
# .toRegex(). Only calls whose *entire* first argument is a chain of string
# literals (no interpolation, no non-literal operand) are analyzed; anything
# else is left alone rather than guessed at, since the whole point is not to
# assert something we cannot statically prove. That is a deliberate
# precision-over-recall choice: a dynamically-built pattern could still hit
# the same ICU rejection, but we would rather miss it than risk a false
# positive on this project's regex-heavy hot paths (213 Regex(...) call
# sites at the time this rule was written).
_QUANTIFIER_RE = re.compile(r"\{\d+(?:,\d*)?\}")
_PROPERTY_ESCAPE_LETTERS = ("p", "P")

_CALL_PREFIX_RE = re.compile(r"(?:\bRegex|\bPattern\.compile)\s*\(\s*\Z")
_TOREGEX_SUFFIX_RE = re.compile(r"\A\s*\.toRegex\s*\(")
_CHAIN_GAP_RE = re.compile(r"\s*\+\s*")
_ARG_END_RE = re.compile(r"\A\s*[,)]")

_CALL_PREFIX_LOOKBACK = 200
_ARG_END_LOOKAHEAD = 40
_TOREGEX_LOOKAHEAD = 40


def find_unescaped_bracket_violations(pattern: str) -> list[tuple[int, str]]:
    """Scan a *decoded* (Kotlin-unescaped) regex pattern for a bare ']' or
    '}' outside a character class -- valid, silently-permitted syntax under
    OpenJDK's java.util.regex, rejected by Android's ICU-backed engine.

    Rules encoded here, each grounded either in the regex spec both engines
    share or in an OpenJDK probe (jshell) run against real patterns from
    this codebase before this function was written:
      - '[' opens a class (nesting allowed, so Java class
        intersection/union like '[a-z&&[^aeiou]]' degrades to simple
        balanced-bracket tracking rather than misparsing).
      - A ']' as the very first character of a class (immediately after
        '[' or '[^') is a literal member, not the close -- confirmed with
        OpenJDK: Pattern.compile("[^]]*").matches("x") is true,
        .matches("]") is false. This project's own
        core-resolver/GameNameResolver.kt and CustomHeroFetcher.kt rely on
        exactly this idiom.
      - Any backslash escapes exactly the next character, in or out of a
        class, and is never itself a violation.
      - '\\p{...}' / '\\P{...}' Unicode property escapes are consumed as a
        unit; their braces are neither quantifiers nor stray literals (used
        by GameNameResolver.kt/TitleCatalog.kt: \\p{M}+, \\p{Mn}+).
      - '{n}', '{n,}', '{n,m}' bounded quantifiers are consumed as a unit;
        the closing '}' is structural. Pervasive in this codebase
        ([0-9a-f]{64} and friends) and must never be flagged.
      - Everything else outside a class: a bare ']' is always a violation
        (nothing can be open at depth 0); a bare '}' is a violation unless
        it was just consumed as part of a matched quantifier/property
        escape above.
    """
    violations: list[tuple[int, str]] = []
    i = 0
    n = len(pattern)
    class_depth = 0
    expect_class_leader = False
    while i < n:
        ch = pattern[i]

        if ch == "\\" and i + 1 < n:
            nxt = pattern[i + 1]
            if nxt in _PROPERTY_ESCAPE_LETTERS and i + 2 < n and pattern[i + 2] == "{":
                close = pattern.find("}", i + 3)
                i = n if close == -1 else close + 1
            else:
                i += 2
            expect_class_leader = False
            continue

        if ch == "[":
            class_depth += 1
            if i + 1 < n and pattern[i + 1] == "^":
                i += 1
            expect_class_leader = True
            i += 1
            continue

        if ch == "]":
            if expect_class_leader:
                expect_class_leader = False
            elif class_depth > 0:
                class_depth -= 1
            else:
                violations.append((i, "]"))
            i += 1
            continue

        expect_class_leader = False

        if ch == "{" and class_depth == 0:
            m = _QUANTIFIER_RE.match(pattern, i)
            if m:
                i = m.end()
                continue
            i += 1
            continue

        if ch == "}" and class_depth == 0:
            violations.append((i, "}"))
            i += 1
            continue

        i += 1

    return violations


def _build_regex_argument_chains(
    code_view: str, literals: list[LiteralToken]
) -> list[list[LiteralToken]]:
    """Group literals into maximal '+'-concatenation chains and keep only
    the chains that are a complete, statically-known Regex(...)/
    Pattern.compile(...) first argument or a .toRegex() receiver.

    "Complete" means the chain runs from the call's opening paren (or
    the .toRegex() receiver position) all the way to the argument's end
    (a following ',' or ')' for calls; nothing further to check for a bare
    receiver) with nothing but literals and '+' in between -- a chain that
    is merely a *prefix* of a partly-dynamic argument (e.g.
    Regex("a" + x + "b")) is dropped rather than analyzed piecemeal.
    """
    ordered = sorted(literals, key=lambda lit: lit.start)
    chains: list[list[LiteralToken]] = []
    i = 0
    n = len(ordered)
    while i < n:
        chain = [ordered[i]]
        j = i + 1
        while j < n:
            gap = code_view[chain[-1].end : ordered[j].start]
            if _CHAIN_GAP_RE.fullmatch(gap):
                chain.append(ordered[j])
                j += 1
                continue
            break

        prefix_window = code_view[max(0, chain[0].start - _CALL_PREFIX_LOOKBACK) : chain[0].start]
        is_call_arg = bool(_CALL_PREFIX_RE.search(prefix_window))
        if is_call_arg:
            arg_end_window = code_view[chain[-1].end : chain[-1].end + _ARG_END_LOOKAHEAD]
            is_call_arg = bool(_ARG_END_RE.match(arg_end_window))

        is_toregex = False
        if len(chain) == 1:
            suffix_window = code_view[chain[-1].end : chain[-1].end + _TOREGEX_LOOKAHEAD]
            is_toregex = bool(_TOREGEX_SUFFIX_RE.match(suffix_window))

        if is_call_arg or is_toregex:
            chains.append(chain)
        i = j
    return chains


def scan_regex_literals(
    *,
    rel_path: str,
    source: str,
    code_view: str,
    literals: list[LiteralToken],
    starts: list[int],
) -> list[Finding]:
    findings: list[Finding] = []
    for chain in _build_regex_argument_chains(code_view, literals):
        if not all(lit.fully_literal for lit in chain):
            continue
        decoded_pattern = "".join(lit.decoded for lit in chain)
        source_offsets: list[int] = []
        for lit in chain:
            source_offsets.extend(lit.offsets)
        for idx, ch in find_unescaped_bracket_violations(decoded_pattern):
            offset = source_offsets[idx]
            line_no, column = offset_to_line_col(offset, starts)
            detail = (
                "unescaped ] outside character class"
                if ch == "]"
                else "unescaped } outside character class/quantifier"
            )
            findings.append(
                Finding(
                    rule="R1",
                    rel_path=rel_path,
                    line_no=line_no,
                    column=column,
                    exact_match=ch,
                    line_text=line_text_at(source, line_no),
                    detail=detail,
                )
            )
    return dedupe_overlaps(findings, starts)


# --- D1/D2/D3: must-consume producer registry -------------------------------
#
# See the module docstring for the shapes and limitations of these three sub-rules.

_REPORTING_CHANNEL_RES: tuple[re.Pattern[str], ...] = (
    re.compile(r"\bToast\s*\.\s*makeText\s*\("),
    re.compile(r"\bemit[A-Za-z0-9_]*Message\s*\("),
    re.compile(r"\b(?:showMimirMessage|showCompanionFeedbackToast)\s*\("),
)


def _has_reporting_call(text: str) -> bool:
    return any(pattern.search(text) for pattern in _REPORTING_CHANNEL_RES)


def _producer_call_pattern(name: str) -> re.Pattern[str]:
    """Build a call-site matcher for a (possibly dot-qualified) producer name.

    Matches only where the name is immediately followed by '(' -- a call,
    never a bare reference (KDoc `[name]` links are already masked to spaces
    by the lexer since they only ever appear inside comments).
    """
    parts = name.split(".")
    escaped = r"\s*\.\s*".join(re.escape(p) for p in parts)
    return re.compile(r"\b" + escaped + r"\s*\(")


_DECL_BEFORE_RE = re.compile(r"\bfun\s+(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)?\Z")


def _is_function_declaration(code_view: str, start: int) -> bool:
    """True if *start* is the name in a `fun name(` / `fun Receiver.name(`
    declaration rather than a call. Only meaningful for bare (unqualified)
    producer names -- a qualified match like "Q.M(" cannot textually equal a
    declaration line, which never carries the "Q." prefix.
    """
    window = code_view[max(0, start - 80) : start]
    return bool(_DECL_BEFORE_RE.search(window))


def _find_matching_close_paren(code_view: str, open_idx: int) -> Optional[int]:
    depth = 0
    n = len(code_view)
    i = open_idx
    while i < n:
        ch = code_view[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


_LEADING_CONTINUATION_TOKENS: tuple[str, ...] = (
    # Checked longest-first so a 2-char operator is identified before a
    # shorter one that happens to share its final character (e.g. "==" is
    # matched as itself, not mistaken for nothing -- both would classify as
    # "continuation" anyway, but only one lookup should fire per call).
    "?.", "?:", "==", "!=", "&&", "||", "<=", ">=", "..", "->",
    "=", "+", "-", "*", "/", "%", "<", ">", ",", ".", ":", "(", "[",
)


def _is_statement_start(code_view: str, start: int) -> bool:
    """True if *start* plausibly begins a fresh statement rather than
    continuing or being bound by an expression that started on an earlier
    line -- a `val cleared = a &&\\n    producer(...)` wraps the producer
    call onto its own line, but it is still bound and branched, not
    discarded; the newline is a formatting accident, not a statement
    boundary. Kotlin has no explicit line-continuation marker, so the
    signal is the trailing token before *start*, skipping ALL whitespace
    including newlines: `{`, `}`, `;`, `->` genuinely open a new statement;
    a binary/assignment/comma/open-bracket operator means the previous
    line's expression is not finished; anything else (an identifier, `)`,
    `]`, a string/number literal -- i.e. a value that completed a prior
    statement) also opens a new one.
    """
    j = start - 1
    while j >= 0 and code_view[j] in " \t\r\n":
        j -= 1
    if j < 0:
        return True
    if code_view[j] in ("{", "}", ";"):
        return True
    if j >= 1 and code_view[j - 1 : j + 1] == "->":
        return True
    for op in _LEADING_CONTINUATION_TOKENS:
        if code_view[max(0, j - len(op) + 1) : j + 1] == op:
            return False
    return True


_CONTINUATION_PREFIXES: tuple[str, ...] = (
    "?.", "?:", "==", "!=", "&&", "||", "<=", ">=", "..",
    ".", ",", ")", "]", "+", "-", "*", "/", "%", "<", ">", ":",
)
_CONTINUATION_WORD_RE = re.compile(r"\A(as\b|is\b|in\b|else\b)")


def _following_class(code_view: str, close_paren_idx: int) -> str:
    """Classify what happens to a call's result by inspecting the token right
    after its closing paren: 'tail' (the call is the last expression of its
    enclosing block -- consumed as that block's implicit value), 'continuation'
    (chained into / used by a larger expression), or 'new_statement' (the
    call's own statement ended and the next one starts -- the result, if any,
    is unreachable: a discard).
    """
    n = len(code_view)
    i = close_paren_idx + 1
    while i < n and code_view[i] in " \t\r\n":
        i += 1
    if i >= n:
        return "new_statement"
    if code_view[i] == "}":
        return "tail"
    window = code_view[i : i + 3]
    for op in _CONTINUATION_PREFIXES:
        if window.startswith(op):
            return "continuation"
    if _CONTINUATION_WORD_RE.match(code_view[i : i + 6]):
        return "continuation"
    return "new_statement"


def scan_must_consume_d1(
    *,
    rel_path: str,
    source: str,
    code_view: str,
    starts: list[int],
    producers: list[Producer],
) -> list[Finding]:
    findings: list[Finding] = []
    for prod in producers:
        if prod.rule != "D1" or (prod.scope_path and prod.scope_path not in rel_path):
            continue
        is_qualified = "." in prod.producer
        pattern = _producer_call_pattern(prod.producer)
        for m in pattern.finditer(code_view):
            call_start = m.start()
            open_paren = m.end() - 1
            if not is_qualified and _is_function_declaration(code_view, call_start):
                continue
            close_paren = _find_matching_close_paren(code_view, open_paren)
            if close_paren is None:
                continue
            if not _is_statement_start(code_view, call_start):
                continue
            if _following_class(code_view, close_paren) != "new_statement":
                continue
            line_no, column = offset_to_line_col(call_start, starts)
            findings.append(
                Finding(
                    rule="D1",
                    rel_path=rel_path,
                    line_no=line_no,
                    column=column,
                    exact_match=source[call_start : open_paren + 1],
                    line_text=line_text_at(source, line_no),
                    detail=f"result of {prod.producer}(...) discarded in statement position -- {prod.note}",
                )
            )
    return findings


_CONTROL_KEYWORDS = ("if", "while", "when")


def _enclosing_control_keyword(code_view: str, pos: int) -> Optional[str]:
    """Walk backward from *pos* tracking only parenthesis depth. If the
    nearest unmatched '(' is reached before crossing a statement boundary
    ({, }, ;), return the keyword immediately before that '(' (if it is one
    of if/while/when); otherwise None. Used to tell "compared as a genuine
    decision" (`if (x == Y.Z)`) apart from "compared, and the Boolean result
    is what escapes" (the collapse this rule targets).
    """
    depth = 0
    i = pos - 1
    while i >= 0:
        ch = code_view[i]
        if ch == ")":
            depth += 1
        elif ch == "(":
            if depth == 0:
                j = i - 1
                while j >= 0 and code_view[j] in " \t\r\n":
                    j -= 1
                end = j + 1
                start = end
                while start > 0 and (code_view[start - 1].isalnum() or code_view[start - 1] == "_"):
                    start -= 1
                word = code_view[start:end]
                return word if word in _CONTROL_KEYWORDS else None
            depth -= 1
        elif ch in "{};":
            return None
        i -= 1
    return None


def _sealed_compare_patterns(type_name: str) -> tuple[re.Pattern[str], re.Pattern[str]]:
    esc = re.escape(type_name)
    forward = re.compile(r"(==|!=)\s*\b" + esc + r"\s*\.\s*[A-Za-z_][A-Za-z0-9_]*\b")
    backward = re.compile(r"\b" + esc + r"\s*\.\s*[A-Za-z_][A-Za-z0-9_]*\b\s*(==|!=)")
    return forward, backward


_BARE_IDENT_ONLY_RE = re.compile(r"\A[A-Za-z_][A-Za-z0-9_]*\Z")


def _bare_identifier_before(code_view: str, pos: int) -> Optional[tuple[str, int, int]]:
    """If the token immediately before *pos* (skipping whitespace) is a bare
    local identifier -- not the tail of a `.`-qualified chain such as
    `repository.rescan(rootUri).outcome` -- return (name, start, end); else
    None. A chain can never be "carried forward" as itself (there is no name
    to look for elsewhere); only a name bound to a local can be. The span is
    returned, not just the name, so the caller can exclude this exact
    occurrence -- which is *not* inside the `==`/`Type.Arm` match span -- when
    searching for another reference to the same name.
    """
    j = pos - 1
    while j >= 0 and code_view[j] in " \t\r\n":
        j -= 1
    end = j + 1
    start = end
    while start > 0 and (code_view[start - 1].isalnum() or code_view[start - 1] == "_"):
        start -= 1
    if start == end:
        return None
    name = code_view[start:end]
    if not _BARE_IDENT_ONLY_RE.match(name):
        return None
    k = start - 1
    while k >= 0 and code_view[k] in " \t\r\n":
        k -= 1
    if k >= 0 and code_view[k] == ".":
        return None
    return name, start, end


def _bare_identifier_after(code_view: str, pos: int) -> Optional[tuple[str, int, int]]:
    """Symmetric to `_bare_identifier_before`, looking forward from *pos*
    (used for the reversed `Type.Arm == name` comparison order)."""
    n = len(code_view)
    i = pos
    while i < n and code_view[i] in " \t\r\n":
        i += 1
    start = i
    while i < n and (code_view[i].isalnum() or code_view[i] == "_"):
        i += 1
    if i == start:
        return None
    name = code_view[start:i]
    if not _BARE_IDENT_ONLY_RE.match(name):
        return None
    return name, start, i


def _enclosing_block_span(code_view: str, pos: int) -> tuple[int, int]:
    """The (start, end) offsets of the tightest enclosing `{ ... }` around
    *pos* (start is just after the opening brace, end is the matching
    closing brace); falls back to the whole file's bounds if *pos* is not
    inside any braces.
    """
    depth = 0
    i = pos - 1
    start = 0
    while i >= 0:
        ch = code_view[i]
        if ch == "}":
            depth += 1
        elif ch == "{":
            if depth == 0:
                start = i + 1
                break
            depth -= 1
        i -= 1
    n = len(code_view)
    depth = 0
    i = pos
    end = n
    while i < n:
        ch = code_view[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            if depth == 0:
                end = i
                break
            depth -= 1
        i += 1
    return start, end


_VAL_VAR_BEFORE_RE = re.compile(r"\b(val|var)\s*\Z")


def _identifier_carried_forward(code_view: str, name: str, match_start: int, match_end: int) -> bool:
    """True if the bare local *name* on one side of a sealed-comparison at
    [match_start, match_end) is referenced again anywhere else in its
    tightest enclosing `{ ... }` block -- outside its own `val`/`var`
    declaration and outside the comparison itself.

    If so, the sealed value is bound once and read at least twice: the
    comparison derives a Boolean *alongside* the value (e.g.
    `SourceOperationOutcome(libraryChanged = outcome == Healthy, scanOutcome
    = outcome)`), it does not replace it -- the collapse this rule targets
    never happens, because the original value is still reachable by
    whatever consumes this block's result. A bare `if (x.outcome ==
    Healthy) { ... }` with the outcome going nowhere else is unaffected:
    `_bare_identifier_before`/`_bare_identifier_after` only return a name
    for a genuinely bare local, never for a `.`-qualified chain, so this
    function is never even asked about those.
    """
    start, end = _enclosing_block_span(code_view, match_start)
    ident_re = re.compile(r"\b" + re.escape(name) + r"\b")
    for m in ident_re.finditer(code_view, start, end):
        pos = m.start()
        if match_start <= pos < match_end:
            continue
        k = pos - 1
        while k >= 0 and code_view[k] in " \t\r\n":
            k -= 1
        if k >= 0 and code_view[k] == ".":
            continue
        prefix_window = code_view[max(0, pos - 12) : pos]
        if _VAL_VAR_BEFORE_RE.search(prefix_window):
            continue
        return True
    return False


def scan_must_consume_d2(
    *,
    rel_path: str,
    source: str,
    code_view: str,
    starts: list[int],
    producers: list[Producer],
) -> list[Finding]:
    findings: list[Finding] = []
    for prod in producers:
        if prod.rule != "D2" or (prod.scope_path and prod.scope_path not in rel_path):
            continue
        forward, backward = _sealed_compare_patterns(prod.producer)
        # (op_pos, match_start, match_end, side) -- side names which operand
        # position the *other* value (not the registered Type.Arm) sits at,
        # needed to know which bare-identifier extractor applies.
        candidates: list[tuple[int, int, int, str]] = []
        for m in forward.finditer(code_view):
            candidates.append((m.start(), m.start(), m.end(), "before"))
        for m in backward.finditer(code_view):
            op = m.group(1)
            op_pos = m.end() - len(op)
            candidates.append((op_pos, m.start(), m.end(), "after"))
        seen: set[int] = set()
        for op_pos, match_start, match_end, side in candidates:
            if op_pos in seen:
                continue
            seen.add(op_pos)
            if _enclosing_control_keyword(code_view, op_pos) is not None:
                continue
            bare = (
                _bare_identifier_before(code_view, op_pos)
                if side == "before"
                else _bare_identifier_after(code_view, op_pos + 2)
            )
            if bare is not None:
                bare_name, ident_start, ident_end = bare
                # Exclude both the Type.Arm/operator match AND this bare
                # identifier's own occurrence (it sits outside
                # [match_start, match_end) on the "before" side) from the
                # carried-forward search, or its own LHS use would be
                # mistaken for a second, forwarding reference to itself.
                self_start = min(match_start, ident_start)
                self_end = max(match_end, ident_end)
                if _identifier_carried_forward(code_view, bare_name, self_start, self_end):
                    continue
            line_no, column = offset_to_line_col(match_start, starts)
            findings.append(
                Finding(
                    rule="D2",
                    rel_path=rel_path,
                    line_no=line_no,
                    column=column,
                    exact_match=source[match_start:match_end],
                    line_text=line_text_at(source, line_no),
                    detail=(
                        f"{prod.producer} collapsed by comparison outside a decision "
                        f"(if/while/when) -- {prod.note}"
                    ),
                )
            )
    return findings


def _capture_statement(code_view: str, start: int) -> int:
    """From *start* (the first token of a would-be discard's body), return
    the end offset of that one statement: a brace/paren/bracket-depth-aware
    scan that stops at the first ';' or newline seen at depth 0, or at an
    enclosing closer this body does not own. Handles both a single bare
    statement (`return@launch`) and a braced block (`{ ...; return@launch }`)
    with the same walk.
    """
    n = len(code_view)
    i = start
    depth = 0
    while i < n:
        ch = code_view[i]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            if depth == 0:
                break
            depth -= 1
        elif ch == ";" and depth == 0:
            return i
        elif ch == "\n" and depth == 0:
            return i
        i += 1
    return i


_ELVIS_RE = re.compile(r"\A\s*\?:\s*")
_IF_NOT_BEFORE_RE = re.compile(r"\bif\s*\(\s*!\s*\Z")
_CLOSE_IF_RE = re.compile(r"\A\s*\)")
_HAS_RETURN_RE = re.compile(r"\breturn\b")


def _skip_tail_closes(code_view: str, idx: int, max_hops: int = 4) -> int:
    """From *idx*, repeatedly skip whitespace and one '}' at a time. A
    producer call that is itself the tail expression of a one-line wrapper
    (`withContext(Dispatchers.IO) { producer(...) } ?: return...`) needs this
    to reach the Elvis operator that actually guards it -- the operator sits
    after the wrapper's closing brace, not after the producer's own paren.
    Bounded so an unrelated run of literal '}' cannot be walked forever.
    """
    n = len(code_view)
    i = idx
    hops = 0
    while hops < max_hops:
        j = i
        while j < n and code_view[j] in " \t\r\n":
            j += 1
        if j < n and code_view[j] == "}":
            i = j + 1
            hops += 1
            continue
        break
    return i


def _check_d3_elvis(
    *, code_view: str, source: str, rel_path: str, starts: list[int], prod: Producer, call_start: int, close_paren: int
) -> Optional[Finding]:
    guard_pos = _skip_tail_closes(code_view, close_paren + 1)
    tail = code_view[guard_pos : guard_pos + 200]
    m = _ELVIS_RE.match(tail)
    if not m:
        return None
    body_start = guard_pos + m.end()
    body_end = _capture_statement(code_view, body_start)
    body = code_view[body_start:body_end]
    if not _HAS_RETURN_RE.search(body) or _has_reporting_call(body):
        return None
    line_no, column = offset_to_line_col(call_start, starts)
    return Finding(
        rule="D3",
        rel_path=rel_path,
        line_no=line_no,
        column=column,
        exact_match=f"{prod.producer}(",
        line_text=line_text_at(source, line_no),
        detail=(
            f"{prod.producer}(...) ?: {body.strip()[:60]!r} exits with no reporting call -- {prod.note}"
        ),
    )


def _check_d3_negated_if(
    *, code_view: str, source: str, rel_path: str, starts: list[int], prod: Producer, call_start: int, close_paren: int
) -> Optional[Finding]:
    window = code_view[max(0, call_start - 60) : call_start]
    if not _IF_NOT_BEFORE_RE.search(window):
        return None
    tail = code_view[close_paren + 1 : close_paren + 1 + 10]
    m = _CLOSE_IF_RE.match(tail)
    if not m:
        return None
    body_start = close_paren + 1 + m.end()
    n = len(code_view)
    while body_start < n and code_view[body_start] in " \t\r\n":
        body_start += 1
    body_end = _capture_statement(code_view, body_start)
    body = code_view[body_start:body_end]
    if not _HAS_RETURN_RE.search(body) or _has_reporting_call(body):
        return None
    line_no, column = offset_to_line_col(call_start, starts)
    return Finding(
        rule="D3",
        rel_path=rel_path,
        line_no=line_no,
        column=column,
        exact_match=f"{prod.producer}(",
        line_text=line_text_at(source, line_no),
        detail=(
            f"if (!{prod.producer}(...)) {body.strip()[:60]!r} exits with no reporting call -- {prod.note}"
        ),
    )


def scan_must_consume_d3(
    *,
    rel_path: str,
    source: str,
    code_view: str,
    starts: list[int],
    producers: list[Producer],
) -> list[Finding]:
    findings: list[Finding] = []
    for prod in producers:
        if prod.rule != "D3" or (prod.scope_path and prod.scope_path not in rel_path):
            continue
        is_qualified = "." in prod.producer
        pattern = _producer_call_pattern(prod.producer)
        for m in pattern.finditer(code_view):
            call_start = m.start()
            open_paren = m.end() - 1
            if not is_qualified and _is_function_declaration(code_view, call_start):
                continue
            close_paren = _find_matching_close_paren(code_view, open_paren)
            if close_paren is None:
                continue
            kwargs = dict(
                code_view=code_view,
                source=source,
                rel_path=rel_path,
                starts=starts,
                prod=prod,
                call_start=call_start,
                close_paren=close_paren,
            )
            finding = _check_d3_elvis(**kwargs) or _check_d3_negated_if(**kwargs)
            if finding is not None:
                findings.append(finding)
    return findings


def is_excluded_worktree_path(rel_posix: str) -> bool:
    normalized = rel_posix.removeprefix("./")
    if any(part in {"worktrees", ".worktrees"} for part in normalized.split("/")):
        return True
    return any(
        normalized == prefix.rstrip("/") or normalized.startswith(prefix)
        for prefix in EXCLUDED_WORKTREE_PREFIXES
    )


def should_prune_repo_walk_dir(rel_posix: str) -> bool:
    """Skip nested agent worktree/cache roots during any repo filesystem walk."""
    normalized = rel_posix.removeprefix("./")
    return normalized in {".worktrees", ".cursor/worktrees"} or is_excluded_worktree_path(
        normalized
    )


def iter_repo_paths(root: Path) -> Iterable[Path]:
    """Walk *root* filesystem tree; skip nested agent worktree/cache roots.

    Directory symlinks are never descended (loop/alias/worktree escape). File
    symlinks are yielded at their repo-relative path so prohibited sources cannot
    hide behind a link. Broken symlinks are skipped without error.
    """
    stack = [root]
    while stack:
        current = stack.pop()
        try:
            children = sorted(current.iterdir(), key=lambda p: p.name)
        except OSError:
            continue
        for child in children:
            try:
                rel = child.relative_to(root).as_posix()
            except ValueError:
                continue
            try:
                if child.is_symlink():
                    # Never descend through directory symlinks; only surface file links.
                    try:
                        if child.is_file():
                            yield child
                    except OSError:
                        pass
                    continue
                if child.is_dir():
                    if should_prune_repo_walk_dir(rel):
                        continue
                    stack.append(child)
                    continue
                yield child
            except OSError:
                continue


def is_kotlin_source_rel(rel_posix: str) -> bool:
    if is_excluded_worktree_path(rel_posix):
        return False
    if "/build/" in f"/{rel_posix}/":
        return False
    if "/src/" not in rel_posix:
        return False
    return True


def iter_kotlin_source_paths(root: Path) -> Iterable[Path]:
    """Walk *root* filesystem tree; skip nested agent worktree/cache roots."""
    for child in iter_repo_paths(root):
        rel = child.relative_to(root).as_posix()
        if child.suffix == ".kt" and is_kotlin_source_rel(rel):
            yield child


def kotlin_source_files() -> list[Path]:
    return sorted(iter_kotlin_source_paths(ROOT))


def feature_main_files() -> list[Path]:
    out: list[Path] = []
    for path in kotlin_source_files():
        rel = path.relative_to(ROOT).as_posix()
        if rel.startswith("feature-") and "/src/main/" in rel:
            out.append(path)
    return out


def main_kotlin_source_files() -> list[Path]:
    """Production Kotlin sources only (`/src/main/`), excluding `/src/test/`
    and `/src/androidTest/`. D1/D2/D3 are scoped to this set: the must-consume
    defect class is about a player mistaking a declined operation for a
    successful one, which only exists on a path that ships. A test's own
    `finally { cleanup(...) }` discarding a Boolean is a different (and much
    lower-stakes) question than F1/R1, which stay repo-wide because a banned
    focus API or an ICU-hostile regex is just as real in test code.
    """
    out: list[Path] = []
    for path in kotlin_source_files():
        rel = path.relative_to(ROOT).as_posix()
        if "/src/main/" in rel:
            out.append(path)
    return out


def line_starts(text: str) -> list[int]:
    starts = [0]
    for idx, ch in enumerate(text):
        if ch == "\n":
            starts.append(idx + 1)
    return starts


def offset_to_line_col(offset: int, starts: list[int]) -> tuple[int, int]:
    line_no = len(starts)
    for idx, start in enumerate(starts):
        if idx + 1 < len(starts) and offset < starts[idx + 1]:
            line_no = idx + 1
            return line_no, offset - start + 1
    return line_no, offset - starts[-1] + 1


def line_text_at(source: str, line_no: int) -> str:
    lines = source.split("\n")
    if line_no < 1 or line_no > len(lines):
        return ""
    return lines[line_no - 1]


def absolute_span(finding: Finding, starts: list[int]) -> tuple[int, int]:
    start = starts[finding.line_no - 1] + finding.column - 1
    return start, start + len(finding.exact_match)


def dedupe_overlaps(findings: list[Finding], starts: list[int] | None = None) -> list[Finding]:
    if not findings:
        return findings

    def span(f: Finding) -> tuple[int, int]:
        if starts is None:
            col_start = f.column - 1
            return col_start, col_start + len(f.exact_match)
        return absolute_span(f, starts)

    ordered = sorted(findings, key=lambda f: (span(f)[0], -(span(f)[1] - span(f)[0])))
    kept: list[Finding] = []
    for candidate in ordered:
        c0, c1 = span(candidate)
        if any(not (c1 <= span(k)[0] or c0 >= span(k)[1]) for k in kept):
            continue
        kept.append(candidate)
    return sorted(kept, key=lambda f: (f.rel_path, f.line_no, f.column))


def scan_view(
    *,
    rule: str,
    rel_path: str,
    source: str,
    view: str,
    starts: list[int],
    patterns: Iterable[tuple[str, re.Pattern[str]]],
) -> list[Finding]:
    findings: list[Finding] = []
    for detail, pattern in patterns:
        for match in pattern.finditer(view):
            start, end = match.start(), match.end()
            exact = source[start:end]
            line_no, column = offset_to_line_col(start, starts)
            findings.append(
                Finding(
                    rule=rule,
                    rel_path=rel_path,
                    line_no=line_no,
                    column=column,
                    exact_match=exact,
                    line_text=line_text_at(source, line_no),
                    detail=detail,
                )
            )
    return dedupe_overlaps(findings, starts)


def scan_file(
    path: Path,
    rules: tuple[str, ...],
    *,
    root: Optional[Path] = None,
    producers: Optional[list[Producer]] = None,
) -> list[Finding]:
    base = root or ROOT
    rel = path.relative_to(base).as_posix()
    source = path.read_text(encoding="utf-8")
    lexer = KotlinLexer(source)
    code_view, p1_view = lexer.build_views()
    starts = line_starts(source)

    findings: list[Finding] = []
    if "F1" in rules:
        findings.extend(
            scan_view(
                rule="F1",
                rel_path=rel,
                source=source,
                view=code_view,
                starts=starts,
                patterns=F1_RULES,
            )
        )
    if "P1" in rules:
        raw_p1 = scan_view(
            rule="P1",
            rel_path=rel,
            source=source,
            view=p1_view,
            starts=starts,
            patterns=P1_RULES,
        )
        findings.extend(
            f for f in raw_p1 if f.detail != "0x-literal" or hex_digits_valid(f.exact_match)
        )
    if "R1" in rules:
        findings.extend(
            scan_regex_literals(
                rel_path=rel,
                source=source,
                code_view=code_view,
                literals=lexer.literals,
                starts=starts,
            )
        )
    if "D1" in rules or "D2" in rules or "D3" in rules:
        active_producers = load_producers() if producers is None else producers
        common = dict(rel_path=rel, source=source, code_view=code_view, starts=starts)
        if "D1" in rules:
            findings.extend(scan_must_consume_d1(producers=active_producers, **common))
        if "D2" in rules:
            findings.extend(scan_must_consume_d2(producers=active_producers, **common))
        if "D3" in rules:
            findings.extend(scan_must_consume_d3(producers=active_producers, **common))
    return findings


def load_waivers() -> list[Waiver]:
    if not WAIVER_PATH.is_file():
        return []
    lines = WAIVER_PATH.read_text(encoding="utf-8").splitlines()
    if not lines:
        return []
    header = lines[0].strip().split("\t")
    if header != list(WAIVER_HEADER):
        expected = "\t".join(WAIVER_HEADER)
        raise SystemExit(
            "UI INVARIANTS GATE: malformed waiver header; "
            f"expected {expected}"
        )
    waivers: list[Waiver] = []
    seen: set[tuple[str, str, str, str]] = set()
    for raw in lines[1:]:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) != 7:
            raise SystemExit(f"UI INVARIANTS GATE: malformed waiver row: {raw!r}")
        rule, rel_path, anchor, exact_match, reason, approver, waiver_date = parts
        if rule not in {"F1", "P1", "R1", "D1", "D2", "D3"}:
            raise SystemExit(f"UI INVARIANTS GATE: invalid waiver rule {rule!r}")
        if len(reason) < 12:
            raise SystemExit(f"UI INVARIANTS GATE: waiver reason too short ({len(reason)} < 12)")
        if not approver.strip():
            raise SystemExit("UI INVARIANTS GATE: waiver approver must be non-empty")
        try:
            date.fromisoformat(waiver_date)
        except ValueError as exc:
            raise SystemExit(f"UI INVARIANTS GATE: invalid waiver date {waiver_date!r}") from exc
        if not anchor.strip():
            raise SystemExit("UI INVARIANTS GATE: waiver anchor must be non-empty")
        key = (rule, rel_path, anchor, exact_match)
        if key in seen:
            raise SystemExit(f"UI INVARIANTS GATE: duplicate waiver for {key}")
        seen.add(key)
        waivers.append(
            Waiver(
                rule=rule,
                rel_path=rel_path,
                anchor=anchor,
                exact_match=exact_match,
                reason=reason,
                approver=approver,
                waiver_date=waiver_date,
            )
        )
    return waivers


def load_producers() -> list[Producer]:
    if not PRODUCER_PATH.is_file():
        return []
    lines = PRODUCER_PATH.read_text(encoding="utf-8").splitlines()
    if not lines:
        return []
    header = lines[0].strip().split("\t")
    if header != list(PRODUCER_HEADER):
        expected = "\t".join(PRODUCER_HEADER)
        raise SystemExit(
            "UI INVARIANTS GATE: malformed must-consume producer registry header; "
            f"expected {expected}"
        )
    producers: list[Producer] = []
    seen: set[tuple[str, str, str]] = set()
    for raw in lines[1:]:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) != 4:
            raise SystemExit(f"UI INVARIANTS GATE: malformed producer row: {raw!r}")
        rule, producer, scope_path, note = parts
        if rule not in _PRODUCER_RULES:
            raise SystemExit(f"UI INVARIANTS GATE: invalid producer rule {rule!r}")
        if not producer.strip():
            raise SystemExit("UI INVARIANTS GATE: producer name must be non-empty")
        if len(note) < 8:
            raise SystemExit(
                f"UI INVARIANTS GATE: producer note too short for {producer!r} ({len(note)} < 8)"
            )
        key = (rule, producer, scope_path)
        if key in seen:
            raise SystemExit(f"UI INVARIANTS GATE: duplicate producer row for {key}")
        seen.add(key)
        producers.append(Producer(rule=rule, producer=producer, scope_path=scope_path, note=note))
    return producers


def waiver_matches(waivers: list[Waiver], finding: Finding) -> bool:
    # Matched on CONTENT: same rule, same file, same literal, and the anchor present on the finding's
    # own line. No line number is compared, so a waiver survives the file moving under it -- and still
    # fails the moment the literal changes, the anchor is renamed, or the finding moves to a line the
    # anchor does not describe. `validate_waivers` separately rejects an anchor that matches twice.
    return any(
        waiver.rule == finding.rule
        and waiver.rel_path == finding.rel_path
        and waiver.anchor in finding.line_text
        and waiver.exact_match == finding.exact_match
        for waiver in waivers
    )


def validate_waivers(waivers: list[Waiver], findings: list[Finding]) -> list[str]:
    errors: list[str] = []
    finding_counts = Counter(f.key for f in findings)
    matched: set[tuple[str, str, int, str]] = set()
    for waiver in waivers:
        path = ROOT / waiver.rel_path
        if not path.is_file():
            errors.append(f"stale waiver path missing: {waiver.rel_path}")
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        # Resolve the anchor to a line at scan time. This is the whole point of the format: the file
        # may move underneath the waiver, but what it waives may not change.
        hits = [
            n
            for n, line in enumerate(lines, start=1)
            if waiver.anchor in line and waiver.exact_match in line
        ]
        if not hits:
            errors.append(
                f"stale waiver: {waiver.rel_path} has no line carrying both anchor "
                f"{waiver.anchor!r} and {waiver.exact_match!r} -- the exception it names is gone or "
                f"changed, which is exactly when a waiver should fail"
            )
            continue
        if len(hits) > 1:
            errors.append(
                f"ambiguous waiver: anchor {waiver.anchor!r} with {waiver.exact_match!r} matches "
                f"{len(hits)} lines in {waiver.rel_path} (lines {hits}); narrow the anchor"
            )
            continue
        line_no = hits[0]
        key = (waiver.rule, waiver.rel_path, line_no, waiver.exact_match)
        if key not in finding_counts:
            errors.append(f"zero-finding waiver (no live match): {waiver.rel_path}:{line_no}")
            continue
        if finding_counts[key] > 1:
            errors.append(
                f"ambiguous waiver (multiple live matches on line): {waiver.rel_path}:{line_no}"
            )
            continue
        if key in matched:
            errors.append(f"duplicate waiver targets live finding: {waiver.rel_path}:{line_no}")
            continue
        matched.add(key)
    return errors


def run_gate() -> int:
    waivers = load_waivers()
    producers = load_producers()
    findings: list[Finding] = []
    for path in kotlin_source_files():
        findings.extend(scan_file(path, ("F1", "R1")))
    for path in main_kotlin_source_files():
        findings.extend(scan_file(path, ("D1", "D2", "D3"), producers=producers))
    for path in feature_main_files():
        findings.extend(scan_file(path, ("P1",)))

    stale = validate_waivers(waivers, findings)
    if stale:
        print("UI INVARIANTS GATE: FAIL (waiver validation)")
        for item in stale:
            print(f"  {item}")
        return 1

    active = [f for f in findings if not waiver_matches(waivers, f)]
    file_count = len(kotlin_source_files())
    if not active:
        print(
            f"UI INVARIANTS GATE: PASS ({file_count} Kotlin files, "
            f"{len(waivers)} live waiver(s))"
        )
        return 0

    print(f"UI INVARIANTS GATE: FAIL ({len(active)} finding(s))")
    for finding in active:
        print(
            f"  [{finding.rule}] {finding.rel_path}:{finding.line_no}:{finding.column} "
            f"{finding.exact_match!r}"
        )
    return 1


def main() -> None:
    raise SystemExit(run_gate())


if __name__ == "__main__":
    main()
