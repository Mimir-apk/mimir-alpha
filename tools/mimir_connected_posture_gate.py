#!/usr/bin/env python3
"""Mimir Alpha connected-art + SteamGridDB POSTURE GATE (R2).

Behavioral enforcement for audited egress chokepoints, dual-gate ownership, disjoint host suffix
authority, evidence binding, vendor containment, and cleartext bans. Kotlin-aware via the shared
length-preserving code view from tools/mimir_ui_invariants_gate.py (hard-fail import).
"""

from __future__ import annotations

import importlib.util
import io
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UI_GATE_PATH = ROOT / "tools" / "mimir_ui_invariants_gate.py"
EVIDENCE_DIR = ROOT / "tools" / "connected-posture"
EVIDENCE_BINDING = EVIDENCE_DIR / "BINDING.md"

spec = importlib.util.spec_from_file_location("mimir_ui_invariants_gate", UI_GATE_PATH)
if spec is None or spec.loader is None:
    print("POSTURE GATE: FAIL — cannot load mimir_ui_invariants_gate.py", file=sys.stderr)
    sys.exit(1)
ui_gate = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = ui_gate
spec.loader.exec_module(ui_gate)
KotlinLexer = ui_gate.KotlinLexer

NETWORK_PRIMITIVE_FAMILIES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("openConnection", re.compile(r"\.openConnection\s*\(")),
    ("openStream", re.compile(r"\.openStream\s*\(")),
    ("HttpURLConnection", re.compile(r"\bHttpURLConnection\b")),
    ("HttpsURLConnection", re.compile(r"\bHttpsURLConnection\b")),
    ("URLConnection", re.compile(r"\bURLConnection\b")),
    ("OkHttpClient", re.compile(r"\bOkHttpClient\b")),
    ("HttpClient", re.compile(r"\bHttpClient\s*\(")),
    ("java.net.Socket", re.compile(r"\bjava\.net\.Socket\b")),
    ("SSLSocket", re.compile(r"\bSSLSocket\b")),
)

GATE_SETTER_OWNERS: dict[str, set[str]] = {
    "MimirConnectedArtGate": {
        "app/src/main/kotlin/com/gruning/mimir/rewrite/ConnectedArtStore.kt",
        "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture/MimirConnectedArtGate.kt",
    },
    "MimirSteamGridDbGate": {
        "app/src/main/kotlin/com/gruning/mimir/rewrite/SteamGridDbKeyStore.kt",
        "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture/MimirSteamGridDbGate.kt",
    },
}

GATE_HOST_SUFFIXES: dict[str, tuple[str, ...]] = {
    "MimirConnectedArtGate": ("screenscraper.fr",),
    # Keyless Steam storefront description lookup uses the SteamGridDB opt-in gate.
    # Restrict the suffix to "store.steampowered.com", matching the Kotlin ALLOWED_HOSTS
    # literal and MimirProviderEgress.kt's exact storefront host check. A broader
    # "steampowered.com" suffix would admit unintended subdomains.
    "MimirSteamGridDbGate": ("steamgriddb.com", "store.steampowered.com"),
    # The opt-in emulator definitions fetch (Settings > Emulators > Emulator updates). One exact
    # host, no subdomains, armed only for the duration of the player's own press (never persisted open).
    "MimirEmulatorDefinitionsGate": ("raw.githubusercontent.com",),
}

EGRESS_PATH = "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt"

ROUTE_GATE_HOST_CHECKS: tuple[tuple[str, str], ...] = (
    ("MimirProviderEgressRoute.STEAMGRIDDB_API", "MimirSteamGridDbGate.allowsRequestTo"),
    ("MimirProviderEgressRoute.STEAMGRIDDB_MEDIA", "MimirSteamGridDbGate.allowsRequestTo"),
    ("MimirProviderEgressRoute.SCREENSCRAPER_MEDIA", "MimirConnectedArtGate.allowsRequestTo"),
    ("MimirProviderEgressRoute.STEAM_STORE_API", "MimirSteamGridDbGate.allowsRequestTo"),
    ("MimirProviderEgressRoute.EMULATOR_DEFINITIONS", "MimirEmulatorDefinitionsGate.allowsRequestTo"),
)

IS_OPEN_CROSS_GATE_OR = re.compile(
    r"Mimir(?:SteamGridDb|ConnectedArt)Gate\.isOpen\s*\|\|\s*Mimir(?:SteamGridDb|ConnectedArt)Gate\.isOpen",
)

IS_OPEN_ONLY_ROUTE_AUTH = re.compile(
    r"->\s*Mimir(?:SteamGridDb|ConnectedArt)Gate\.isOpen\b",
)

# ── R2-route-auth: per-arm scoping for gateAllowsRequestTo ─────────────────────
# ROUTE_GATE_HOST_CHECKS used to be enforced as two whole-file substring tests, which cannot
# tell "this route's arm calls gate_check" from "gate_check appears anywhere in the file for
# some other route". The functions below parse gateAllowsRequestTo's own `when (route) { ... }`
# block into (labels, body) arms and scope the gate_check assertion to the body of the arm that
# actually handles each route.
GATE_ALLOWS_MARKER = "fun gateAllowsRequestTo"
ROUTE_LABEL_TOKEN = r"(?:MimirProviderEgressRoute\.\w+|else)"
ROUTE_LABEL_LIST = re.compile(rf"{ROUTE_LABEL_TOKEN}(?:\s*,\s*{ROUTE_LABEL_TOKEN})*\s*,?")
TRAILING_ROUTE_LABEL_LIST = re.compile(rf"{ROUTE_LABEL_LIST.pattern}\s*$")
ROUTE_LABEL_FINDALL = re.compile(ROUTE_LABEL_TOKEN)


def split_at_depth0(text: str, token: str) -> list[str]:
    """Split text on occurrences of token that sit outside any (), {}, [] nesting."""
    parts: list[str] = []
    depth = 0
    start = 0
    i = 0
    n = len(text)
    tlen = len(token)
    while i < n:
        ch = text[i]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth = max(0, depth - 1)
        if depth == 0 and text.startswith(token, i):
            parts.append(text[start:i])
            i += tlen
            start = i
            continue
        i += 1
    parts.append(text[start:])
    return parts


def gate_allows_when_body(egress_view: str) -> str | None:
    """Extract the arms body of gateAllowsRequestTo's `when (route) { ... }` expression."""
    if egress_view.count(GATE_ALLOWS_MARKER) != 1:
        return None
    fn_start = egress_view.find(GATE_ALLOWS_MARKER)
    search_start = fn_start + len(GATE_ALLOWS_MARKER)
    # Bound the search to this function only: if another `fun ` shows up before a `when`
    # keyword does, gateAllowsRequestTo has no when-expression body of its own.
    next_fun = egress_view.find("fun ", search_start)
    when_match = re.search(r"\bwhen\b", egress_view[search_start:])
    if not when_match:
        return None
    when_pos = search_start + when_match.start()
    if next_fun != -1 and next_fun < when_pos:
        return None
    brace_pos = egress_view.find("{", when_pos)
    if brace_pos < 0:
        return None
    if next_fun != -1 and brace_pos > next_fun:
        return None
    return extract_braced_body(egress_view, brace_pos)


def parse_when_arms(arms_body: str) -> list[tuple[list[str], str]] | None:
    """Parse a `when` block's arms into (labels, body) pairs. None means unparseable."""
    parts = split_at_depth0(arms_body, "->")
    if len(parts) < 2:
        return None
    first_labels_text = parts[0].strip()
    if not ROUTE_LABEL_LIST.fullmatch(first_labels_text):
        return None
    current_labels = ROUTE_LABEL_FINDALL.findall(first_labels_text)
    arms: list[tuple[list[str], str]] = []
    last_index = len(parts) - 1
    for i in range(1, len(parts)):
        part = parts[i]
        if i == last_index:
            arms.append((current_labels, part))
            break
        match = TRAILING_ROUTE_LABEL_LIST.search(part)
        if not match:
            return None
        body = part[: match.start()]
        arms.append((current_labels, body))
        current_labels = ROUTE_LABEL_FINDALL.findall(match.group(0))
    return arms


def check_route_gate_scoping(egress_view: str) -> None:
    when_body = gate_allows_when_body(egress_view)
    if when_body is None:
        fail(
            "R2-route-auth",
            f"{EGRESS_PATH} could not locate a parseable gateAllowsRequestTo `when (route) {{ }}` "
            "block — route-to-gate scoping cannot be verified",
        )
        return
    arms = parse_when_arms(when_body)
    if arms is None:
        fail(
            "R2-route-auth",
            f"{EGRESS_PATH} gateAllowsRequestTo when-block has an arm this gate cannot parse — "
            "route-to-gate scoping cannot be verified",
        )
        return
    route_bodies: dict[str, str] = {}
    else_body: str | None = None
    for labels, body in arms:
        for label in labels:
            if label == "else":
                else_body = body
            else:
                route_bodies[label] = body
    for route, gate_check in ROUTE_GATE_HOST_CHECKS:
        body = route_bodies.get(route, else_body)
        if body is None:
            fail("R2-route-auth", f"{EGRESS_PATH} missing route branch for {route}")
            continue
        if gate_check not in body:
            fail(
                "R2-route-auth",
                f"{EGRESS_PATH} route {route} does not call {gate_check} in its gateAllowsRequestTo arm",
            )

RA_HASH_CALLABLE = "defaultRetroAchievementsHashLibraryGet"
RA_HASH_LIBRARY_SYMBOL = "RetroAchievementsHashLibrary"

CONNECTION_LIFECYCLE_PATH = (
    "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/"
    "CancellableBlockingCall.kt"
)
FAMILY_CATALOG_PATH = "mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/FamilyCatalogFetcher.kt"
MUSIC_STATE_PATH = "app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionMusicPlayerState.kt"
MUSIC_CALLER_PATH = "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt"

@dataclass(frozen=True)
class ChokepointSpec:
    rel_path: str
    required_gates: frozenset[str]
    require_primitives: bool = True
    extra_bindings: tuple[str, ...] = ()


REGISTERED_CHOKEPOINTS: tuple[ChokepointSpec, ...] = (
    # A lifecycle seam, not an opener: R4 still checks its type-only family set.
    ChokepointSpec(
        CONNECTION_LIFECYCLE_PATH,
        frozenset(),
        require_primitives=False,
        extra_bindings=("class ConnectionAbort", "HttpURLConnection", "fun attach(", "fun abort(",
                        "connection.disconnect()", "current?.disconnect()", "runAbortingOnCancel",
                        "awaitCancellation()", "ensureActive()"),
    ),
    ChokepointSpec(
        FAMILY_CATALOG_PATH,
        frozenset(),
        extra_bindings=("FamilyCatalogSignature.decodePublicKey(publicKeyBase64)",
                        "FamilyCatalogSignature.decodeSignatureFile(signatureFile.bytes)",
                        "FamilyCatalogSignature.MAX_SIGNATURE_FILE_BYTES", "Origin.parse(catalogUrl)"),
    ),
    ChokepointSpec(
        EGRESS_PATH,
        frozenset({"MimirSteamGridDbGate", "MimirConnectedArtGate", "MimirEmulatorDefinitionsGate"}),
        extra_bindings=("allowsRequestTo", "MimirProviderEgressRoute"),
    ),
    ChokepointSpec(
        "app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt",
        frozenset({"MimirConnectedArtGate"}),
    ),
    ChokepointSpec(
        "app/src/main/kotlin/com/gruning/mimir/rewrite/DatRefreshWorker.kt",
        frozenset(),
        extra_bindings=("catalogRefreshAuthorized", "BuildConfig.DEBUG"),
    ),
    ChokepointSpec(
        "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/ScreenScraperMetadataSource.kt",
        frozenset(),
        extra_bindings=("gateOpen", "ScreenScraperMetadataSource"),
    ),
    ChokepointSpec(
        "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/RetroAchievementsSource.kt",
        frozenset(),
        extra_bindings=("gateOpen", RA_HASH_CALLABLE),
    ),
    ChokepointSpec(
        "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/IgdbMetadataSource.kt",
        frozenset(),
        extra_bindings=("gateOpen", "IgdbMetadataSource"),
    ),
)

CHOKEPOINT_ALLOWED_FAMILIES: dict[str, frozenset[str]] = {
    CONNECTION_LIFECYCLE_PATH: frozenset({"HttpURLConnection"}),
    FAMILY_CATALOG_PATH: frozenset({"openConnection", "HttpURLConnection"}),
    EGRESS_PATH: frozenset({"openConnection", "HttpURLConnection", "URL"}),
    "app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt": frozenset(
        {"openConnection", "HttpURLConnection"},
    ),
    "app/src/main/kotlin/com/gruning/mimir/rewrite/DatRefreshWorker.kt": frozenset(
        {"openConnection", "HttpURLConnection"},
    ),
    "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/ScreenScraperMetadataSource.kt": frozenset(
        {"openConnection", "HttpURLConnection"},
    ),
    "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/RetroAchievementsSource.kt": frozenset(
        {"openConnection", "HttpURLConnection"},
    ),
    "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/IgdbMetadataSource.kt": frozenset(
        {"openConnection", "HttpURLConnection"},
    ),
}

HASH_LIBRARY_PATH = (
    "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/"
    "RetroAchievementsHashLibrary.kt"
)
RA_SOURCE_PATH = (
    "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/"
    "RetroAchievementsSource.kt"
)

VENDOR = re.compile(r"screenscraper|steamgrid|igdb|twitch", re.IGNORECASE)
# Explicit existing onboarding copy only; no prefix/wildcard exception for future keys.
ONBOARDING_VENDOR_KEYS = frozenset({
    "app_onboarding_metadata_screenscraper_title",
    "app_onboarding_metadata_screenscraper_detail",
    "app_onboarding_metadata_screenscraper_connected",
    "app_onboarding_metadata_steamgriddb_title",
    "app_onboarding_metadata_steamgriddb_with_key",
    "app_onboarding_metadata_steamgriddb_without_key",
    "app_onboarding_metadata_steamgriddb_added",
    "app_onboarding_metadata_steamgriddb_update_key",
    "app_onboarding_metadata_steamgriddb_add_key",
    "app_onboarding_reason_details_art_key",
    "app_onboarding_reason_art_key",
})
APP_STRINGS_PATH = re.compile(r"app/src/main/res/values(?:-[A-Za-z0-9+_-]+)?/strings\.xml\Z")
HTTP_LITERAL = re.compile(r"\"http://")
LOG_CALL_START = re.compile(r"\b(?:Log\.[ewidv]|println|print)\s*\(")
LOG_ANDROID_CALL = re.compile(r"\bLog\.[ewidv]\s*\(")
CREDENTIAL_IN_LOG = re.compile(
    r"(api[_-]?key|password|credential|Bearer\s)",
    re.IGNORECASE,
)
URL_IN_LOG = re.compile(r"https?://", re.IGNORECASE)
# Identifiers that denote a REMOTE endpoint or a secret.
#
# Two corrections, 2026-09-01, and they only make sense together — see `log_identifier_view`.
#
# `uri` is gone as a bare alias for `url`, and `token` is narrowed to its auth-bearing forms.
# On Android they are not synonyms for anything remote: `Uri` is the platform's LOCAL content
# locator (`content://`, `file://`) and is what every SAF grant in this tree is held in, while
# `token` here is the frontend bed's monotonic playback-generation counter. Folding those into a
# CONNECTED-posture rule made it fire on local storage locators and audio deck counters — findings
# that no amount of correct code can clear, because the code was never in R10's scope. Remote
# endpoints in this tree are named `url` / `remoteUrl` / `*Api` / `*_URL` (ScreenScraperApi,
# RetroAchievementsApi, MimirProviderEgress, CustomHeroFetcher), which this still matches.
#
# Residual, accepted: a remote URL parked in a variable literally named `uri`, or a secret in a
# non-auth-named `token`, escapes THIS arm. Both remain covered by `URL_IN_LOG` and
# `CREDENTIAL_IN_LOG` on the literal text, and by the R2/R6 egress-structure rules that decide
# where a URL may be built at all.
SENSITIVE_LOG_IDENTIFIER = re.compile(
    r"\b(?:remote[_A-Za-z]*)?url\b"
    r"|\bremote[_A-Za-z]*uri\b"
    r"|\bendpoint\b"
    r"|\bapi[_-]?keys?\b"
    r"|\bcredentials?\b"
    r"|\bpasswords?\b"
    r"|\bbearers?\b"
    r"|\b(?:access|auth|authorization|refresh|session|bearer|api)[_-]?tokens?\b",
    re.IGNORECASE,
)
SIMPLE_TEMPLATE_REF = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*")
ALLOWED_HOSTS_INITIALIZER = re.compile(
    r"ALLOWED_HOSTS\s*(?::\s*Set<String>\s*)?=\s*setOf\(([^)]*)\)",
    re.DOTALL,
)
EGRESS_OPEN_MARKER = "fun open("

failures: list[str] = []
checks = 0


def fail(rule: str, message: str) -> None:
    failures.append(f"[{rule}] {message}")


def rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def code_view(path: Path) -> str:
    return KotlinLexer(path.read_text(encoding="utf-8")).build_views()[0]


def p1_view(path: Path) -> str:
    return KotlinLexer(path.read_text(encoding="utf-8")).build_views()[1]


def main_source_files(suffix: str):
    for path in ui_gate.iter_repo_paths(ROOT):
        rel = path.relative_to(ROOT).as_posix()
        if ui_gate.is_excluded_worktree_path(rel):
            continue
        if "/build/" in f"/{rel}/" or "/.git/" in f"/{rel}/":
            continue
        if not path.name.endswith(suffix):
            continue
        if "/src/main/" not in rel:
            continue
        yield path


def primitive_hits(text: str) -> list[tuple[str, int]]:
    hits: list[tuple[str, int]] = []
    for family, pattern in NETWORK_PRIMITIVE_FAMILIES:
        for match in pattern.finditer(text):
            hits.append((family, match.start()))
    return hits


def line_number(source: str, index: int) -> int:
    return source.count("\n", 0, index) + 1


def strip_kotlin_comments(text: str) -> str:
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            i = text.find("\n", i)
            if i < 0:
                break
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                break
            i = end + 2
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def live_open_connection_calls(text: str) -> bool:
    return ".openConnection(" in text


def count_callable_refs(text: str, symbol: str) -> int:
  # References/bindings/calls: symbol followed by ( or ::symbol
    patterns = (
        re.compile(rf"\b{re.escape(symbol)}\s*\("),
        re.compile(rf"::{re.escape(symbol)}\b"),
        re.compile(rf"=\s*{re.escape(symbol)}\b"),
    )
    count = 0
    for pattern in patterns:
        count += len(pattern.findall(text))
    return count


def strip_string_literals(text: str) -> str:
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', text)


def interpolated_expressions(text: str) -> list[str]:
    """
    The Kotlin string-template expressions inside `text`, both `${...}` and bare `$ref`.

    Brace-matched rather than regex-scanned, because the corpus nests quotes and braces inside
    interpolations — `"volume=${"%.3f".format(to)}"` truncates at the first `}` under a naive
    `\\$\\{[^}]*\\}` and would smuggle the rest of the payload past the scan.
    """
    found: list[str] = []
    index = 0
    while index < len(text):
        char = text[index]
        if char == "\\":
            index += 2
            continue
        if char != "$" or index + 1 >= len(text):
            index += 1
            continue
        if text[index + 1] == "{":
            depth = 0
            cursor = index + 1
            while cursor < len(text):
                if text[cursor] == "{":
                    depth += 1
                elif text[cursor] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                cursor += 1
            body = text[index + 2 : cursor]
            found.append(body)
            found.extend(interpolated_expressions(body))
            index = cursor + 1
            continue
        match = SIMPLE_TEMPLATE_REF.match(text, index + 1)
        if not match:
            index += 1
            continue
        found.append(match.group(0))
        index = match.end()
    return found


def log_identifier_view(payload: str) -> str:
    """
    What the identifier heuristic is allowed to read: code outside string literals, PLUS the
    expressions interpolated into them.

    Without the second half the rule graded SYNTAX, not sensitivity. `strip_string_literals` blanks
    a whole literal including its `${...}` bodies, so the identical value was judged twice over and
    got opposite verdicts depending only on how it was spliced: MainActivity's SAF probe FAILED at
    `"candidates=[" + grants.joinToString { it.uri.toString() } + "]"` and PASSED six lines later at
    `"try ${grant.uri} -> $result"`. Worse in the direction that matters, `Log.d(TAG, "fetching
    $url")` and `Log.d(TAG, "hitting ${route.endpoint}")` — a real remote endpoint reaching logcat,
    exactly what R10 exists to stop — were invisible, while `+ url +` was caught. Interpolation is
    the ordinary way to log in Kotlin, so the rule was blind on its own main case.
    """
    parts = [strip_string_literals(payload)]
    parts.extend(strip_string_literals(expression) for expression in interpolated_expressions(payload))
    return " ".join(parts)


def extract_braced_body(source: str, open_brace_index: int) -> str | None:
    depth = 0
    for index in range(open_brace_index, len(source)):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[open_brace_index + 1:index]
    return None


def egress_public_open_body(egress_view: str) -> str | None:
    object_marker = "object MimirProviderEgress"
    object_start = egress_view.find(object_marker)
    if object_start < 0:
        return None
    open_start = egress_view.find(EGRESS_OPEN_MARKER, object_start)
    if open_start < 0:
        return None
    brace_index = egress_view.find("{", open_start)
    if brace_index < 0:
        return None
    return extract_braced_body(egress_view, brace_index)


def drop_first_log_argument(inner: str) -> str:
    index = 0
    depth = 0
    while index < len(inner):
        char = inner[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "," and depth == 0:
            return inner[index + 1 :]
        index += 1
    return ""


def log_payload_text(call_text: str) -> str:
    inner = call_text[call_text.find("(") + 1 : call_text.rfind(")")]
    if LOG_ANDROID_CALL.search(call_text):
        return drop_first_log_argument(inner)
    return inner


def multiline_log_violations(source: str, rel_path: str) -> list[str]:
    lines = source.splitlines()
    findings: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        stripped = line.strip()
        if stripped.startswith(("*", "//", "/*")):
            index += 1
            continue
        if not LOG_CALL_START.search(line):
            index += 1
            continue
        call_text = line
        depth = line.count("(") - line.count(")")
        next_index = index + 1
        while depth > 0 and next_index < len(lines):
            call_text += "\n" + lines[next_index]
            depth += lines[next_index].count("(") - lines[next_index].count(")")
            next_index += 1
        payload = log_payload_text(call_text)
        if CREDENTIAL_IN_LOG.search(payload) or URL_IN_LOG.search(payload):
            findings.append(f"{rel_path}:{index + 1} logs credentials or remote URLs")
        elif SENSITIVE_LOG_IDENTIFIER.search(log_identifier_view(payload)):
            findings.append(f"{rel_path}:{index + 1} logs credential/url-sensitive identifiers")
        index = next_index if depth <= 0 else index + 1
    return findings


def gate_host_literals(gate_file: Path) -> list[str]:
    raw = gate_file.read_text(encoding="utf-8")
    cleaned = strip_kotlin_comments(raw)
    match = ALLOWED_HOSTS_INITIALIZER.search(cleaned)
    if not match:
        fail("R6-hosts", f"{rel(gate_file)} ALLOWED_HOSTS must be a literal setOf(...) initializer")
        return []
    body = match.group(1)
    if re.search(r"\$|\+|buildString|\"\s*\+|\.plus\(", body):
        fail("R6-hosts", f"{rel(gate_file)} assembles host allowlist via dynamic string assembly")
    if re.search(r"\b[a-zA-Z_][a-zA-Z0-9_]*\b", strip_string_literals(body)):
        fail("R6-hosts", f"{rel(gate_file)} ALLOWED_HOSTS must contain only string literals")
    if not re.fullmatch(r'(\s*"[^"]+"\s*,?\s*)+', body, flags=re.DOTALL):
        fail("R6-hosts", f"{rel(gate_file)} ALLOWED_HOSTS must be a literal-only setOf expression")
    return re.findall(r"\"([a-z0-9.\-]+\.[a-z]{2,})\"", body)


def check_egress_open_structure(egress_view: str) -> None:
    body = egress_public_open_body(egress_view)
    if body is None:
        fail("R2-route-auth", f"{EGRESS_PATH} missing public open() function body")
        return
    opener_calls = list(re.finditer(r"\bopener\.open\s*\(", body))
    if len(opener_calls) != 1:
        fail(
            "R2-route-auth",
            f"{EGRESS_PATH} must call opener.open exactly once (found {len(opener_calls)})",
        )
        return
    opener_pos = opener_calls[0].start()
    for token in ("urlAllowed", "parseUrlHost", "gateAllowsRequestTo"):
        token_pos = body.find(token)
        if token_pos < 0 or token_pos > opener_pos:
            fail(
                "R2-route-auth",
                f"{EGRESS_PATH} must evaluate {token} before opener.open",
            )


def check_egress_authority(egress_view: str) -> None:
    if IS_OPEN_CROSS_GATE_OR.search(egress_view):
        fail("R2-route-auth", f"{EGRESS_PATH} authorizes routes via cross-gate isOpen OR")
    if IS_OPEN_ONLY_ROUTE_AUTH.search(egress_view):
        fail("R2-route-auth", f"{EGRESS_PATH} authorizes routes with isOpen instead of allowsRequestTo(host)")
    if "gateAllows(" in egress_view and "allowsRequestTo" not in egress_view:
        fail("R2-route-auth", f"{EGRESS_PATH} missing host-qualified allowsRequestTo checks")
    if "parseUrlHost" not in egress_view and "URI(url).host" not in egress_view:
        fail("R2-route-auth", f"{EGRESS_PATH} must parse URL host before opener calls")
    check_route_gate_scoping(egress_view)
    if "var opener" in egress_view or "MimirProviderEgress.opener" in egress_view:
        fail("R2-egress-opener", f"{EGRESS_PATH} exposes a mutable global opener")
    if not live_open_connection_calls(egress_view):
        fail("R2-stale-chokepoint", f"{EGRESS_PATH} no longer contains live openConnection calls")
    check_egress_open_structure(egress_view)


def non_gate_setter_view(r: str, view: str) -> str:
    """Mask only the known UI receiver and its own declaration, never an entire file."""
    spans: list[tuple[int, int]] = []
    if r == MUSIC_CALLER_PATH:
        for match in re.finditer(r"(?<![\w.])CompanionMusicPlayerState\s*\.\s*(setOpen)\s*\(", view):
            spans.append(match.span(1))
    if r == MUSIC_STATE_PATH:
        owner = re.search(r"\bobject\s+CompanionMusicPlayerState\s*\{", view)
        if owner:
            body_start = owner.end()
            body = extract_braced_body(view, owner.end() - 1)
            if body is not None:
                for match in re.finditer(r"\bfun\s+(setOpen)\s*\(\s*value\s*:\s*Boolean\s*\)", body):
                    prefix = body[:match.start()]
                    if prefix.count("{") == prefix.count("}"):
                        start, end = match.span(1)
                        spans.append((body_start + start, body_start + end))
    for start, end in reversed(sorted(spans)):
        view = view[:start] + " " * (end - start) + view[end:]
    return view


def check_family_catalog_bindings(path: Path, view: str) -> None:
    # Preserve relationships, not just identifier presence. Comments/strings cannot stand
    # in for executable checks. Whitespace normalization allows ordinary formatting.
    required = (
        "val key = publicKey ?: return FamilyCatalogFetchOutcome.DISABLED",
        "if (origin == null) return FamilyCatalogFetchOutcome.REFUSED_HOST",
        "if (!FamilyCatalogSignature.verify(key, catalog.bytes, signature)) { return FamilyCatalogFetchOutcome.BAD_SIGNATURE }",
        "if (!sink.promoteVerified(catalog.bytes, signature)) return FamilyCatalogFetchOutcome.DECLINED_BY_SINK",
        "if (origin?.owns(response.finalUrl) != true) return Step.Stop(FamilyCatalogFetchOutcome.REFUSED_HOST)",
        "if (response.body.isEmpty() || response.body.size > capBytes) { return Step.Stop(FamilyCatalogFetchOutcome.BAD_SIZE) }",
        "MAX_CATALOG_BYTES = 64 * 1024",
        "DEADLINE_MILLIS = 10_000L",
        "connection.instanceFollowRedirects = false",
        "connection.useCaches = false",
        "connection.connectTimeout = timeoutMillis",
        "connection.readTimeout = minOf(timeoutMillis, READ_STALL_LIMIT_MILLIS)",
        "readCapped(it, maxBodyBytes, deadlineNanos)",
        "val limit = maxBodyBytes + 1",
        "while (out.size() < limit)",
        "if (System.nanoTime() - deadlineNanos > 0L) throw SocketTimeoutException(",
        "connection.disconnect()",
        "if (uri.rawUserInfo != null) return null",
        "if (uri.port != -1 && uri.port != 443) return null",
        "if (uri.rawQuery != null || uri.rawFragment != null) return null",
    )
    compact = re.sub(r"\s+", "", view)
    for marker in required:
        if re.sub(r"\s+", "", marker) not in compact:
            fail("R2-family-catalog-binding", f"{FAMILY_CATALOG_PATH} missing executable check: {marker}")
    # The scheme check includes a literal, so use the comment-free literal-preserving view.
    scheme = 'if (!"https".equals(uri.scheme, ignoreCase = true)) return null'
    if re.sub(r"\s+", "", scheme) not in re.sub(r"\s+", "", p1_view(path)):
        fail("R2-family-catalog-binding", f"{FAMILY_CATALOG_PATH} missing HTTPS origin check")
    key_guard = compact.find("valkey=publicKey?:returnFamilyCatalogFetchOutcome.DISABLED")
    fetch_body = re.search(r"\bfun\s+fetchLocked\s*\([^)]*\)[^{]*\{", view)
    if fetch_body:
        body = extract_braced_body(view, fetch_body.end() - 1)
        normalized = re.sub(r"\s+", "", body or "")
        guard = normalized.find("valkey=publicKey?:returnFamilyCatalogFetchOutcome.DISABLED")
        origin_guard = normalized.find("if(origin==null)returnFamilyCatalogFetchOutcome.REFUSED_HOST")
        first_get = re.search(r"(?<![\w])get\(", normalized)
        if key_guard < 0 or guard < 0 or first_get is None or guard > first_get.start():
            fail("R2-family-catalog-binding", f"{FAMILY_CATALOG_PATH} public-key guard must precede fetch")
        if origin_guard < 0 or first_get is None or origin_guard > first_get.start():
            fail("R2-family-catalog-binding", f"{FAMILY_CATALOG_PATH} origin guard must precede fetch")
        verify_pos = normalized.find("if(!FamilyCatalogSignature.verify(key,catalog.bytes,signature))")
        promote_pos = normalized.find("sink.promoteVerified(")
        if verify_pos < 0 or promote_pos < 0 or verify_pos > promote_pos:
            fail("R2-family-catalog-binding", f"{FAMILY_CATALOG_PATH} signature check must precede promotion")
    else:
        fail("R2-family-catalog-binding", f"{FAMILY_CATALOG_PATH} missing fetchLocked body")


def vendor_resource_view(text: str, r: str) -> str:
    """Exclude named onboarding entries, keeping all other XML content under R8."""
    if not APP_STRINGS_PATH.fullmatch(r):
        return text
    try:
        # Serialization drops unused xmlns declarations and DTDs. Neither may
        # become an accidental place to hide vendor content outside an entry.
        if re.search(r"<!DOCTYPE\b", text, re.IGNORECASE):
            raise ValueError("DOCTYPE is not an onboarding string entry")
        for _, (prefix, uri) in ET.iterparse(io.StringIO(text), events=("start-ns",)):
            if VENDOR.search(prefix + " " + uri):
                raise ValueError("vendor namespace is not an onboarding string entry")
        parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True, insert_pis=True))
        root = ET.fromstring(text, parser=parser)
        if root.tag != "resources":
            raise ValueError("expected resources root")
        seen: set[str] = set()
        for child in list(root):
            name = child.get("name")
            if child.tag == "string" and name in ONBOARDING_VENDOR_KEYS:
                if name in seen:
                    raise ValueError(f"duplicate exempt string {name}")
                seen.add(name)
                # A tail belongs to the surrounding document, not the exempt entry.
                position = list(root).index(child)
                if position:
                    previous = root[position - 1]
                    previous.tail = (previous.tail or "") + (child.tail or "")
                else:
                    root.text = (root.text or "") + (child.tail or "")
                root.remove(child)
        return ET.tostring(root, encoding="unicode")
    except (ET.ParseError, ValueError) as exc:
        fail("R8-vendor-leak", f"{r} invalid resource XML: {exc}")
        return text


def check_gate_setter_ownership() -> None:
    all_owners = {owner for owners in GATE_SETTER_OWNERS.values() for owner in owners}
    for path in main_source_files(".kt"):
        r = rel(path)
        if "/src/test/" in r or "/src/androidTest/" in r:
            continue
        view = code_view(path)
        for gate, owners in GATE_SETTER_OWNERS.items():
            if not re.search(rf"\b{re.escape(gate)}\s*\.\s*setOpen\s*\(", view):
                continue
            if r not in owners:
                fail("R1-setter-owner", f"{r} calls {gate}.setOpen() outside gate owner files")
        if re.search(r"\bsetOpen\s*\(", non_gate_setter_view(r, view)) and r not in all_owners:
            fail("R1-setter-owner", f"{r} calls setOpen() outside gate owner files")


def main() -> int:
    global checks
    checks = 0
    failures.clear()

    # ── R1 gate-qualified setter ownership ─────────────────────────────────────
    checks += 1
    check_gate_setter_ownership()

    # ── R2 registered chokepoint liveness + behavioral bindings ────────────────
    checks += 1
    registered_paths = {spec.rel_path for spec in REGISTERED_CHOKEPOINTS}
    registered_paths.add(HASH_LIBRARY_PATH)
    for spec in REGISTERED_CHOKEPOINTS:
        path = ROOT / spec.rel_path
        if not path.is_file():
            fail("R2-missing-chokepoint", f"{spec.rel_path} missing — stale registration")
            continue
        view = code_view(path)
        if spec.require_primitives:
            if not live_open_connection_calls(view):
                fail("R2-stale-chokepoint", f"{spec.rel_path} has no live openConnection calls")
            elif not primitive_hits(view):
                fail("R2-stale-chokepoint", f"{spec.rel_path} no longer contains audited network primitives")
        for gate in spec.required_gates:
            if gate not in view:
                fail("R2-gate-binding", f"{spec.rel_path} missing direct code-view reference to {gate}")
        for binding in spec.extra_bindings:
            if binding not in view:
                fail("R2-binding", f"{spec.rel_path} missing required binding marker {binding}")
        if spec.rel_path == EGRESS_PATH:
            check_egress_authority(view)
        if spec.rel_path == FAMILY_CATALOG_PATH:
            check_family_catalog_bindings(path, view)

    # ── R3 unknown primitives outside registered chokepoints ───────────────────
    checks += 1
    for path in main_source_files(".kt"):
        r = rel(path)
        view = code_view(path)
        hits = primitive_hits(view)
        if not hits:
            continue
        if r in registered_paths:
            continue
        if r == RA_SOURCE_PATH:
            continue
        if r == HASH_LIBRARY_PATH:
            fail("R3-unregistered-primitive", f"{r} uses network primitives but is not the sole audited caller seam")
            continue
        families = sorted({family for family, _ in hits})
        fail(
            "R3-unregistered-primitive",
            f"{r} uses network primitive family {families} outside audited chokepoints",
        )

    # ── R4 unapproved primitive families inside registered chokepoints ───────────
    checks += 1
    for spec in REGISTERED_CHOKEPOINTS:
        path = ROOT / spec.rel_path
        if not path.is_file():
            continue
        view = code_view(path)
        allowed = CHOKEPOINT_ALLOWED_FAMILIES.get(spec.rel_path)
        if allowed is None:
            fail("R4-unknown-family", f"{spec.rel_path} missing per-chokepoint allowed primitive-family set")
            continue
        for family, index in primitive_hits(view):
            if family not in allowed:
                fail(
                    "R4-unknown-family",
                    f"{spec.rel_path}:{line_number(view, index)} uses unapproved primitive family {family}",
                )

    # ── R5 RA hash library single external caller + caller gate ────────────────
    checks += 1
    hash_path = ROOT / HASH_LIBRARY_PATH
    ra_path = ROOT / RA_SOURCE_PATH
    if not hash_path.is_file() or not ra_path.is_file():
        fail("R5-ra-hash-library", "RetroAchievements hash-library transport or source missing")
    else:
        ra_view = code_view(ra_path)
        if RA_HASH_CALLABLE not in ra_view:
            fail("R5-ra-hash-library", f"{RA_SOURCE_PATH} missing {RA_HASH_CALLABLE} binding")
        if "gateOpen" not in ra_view:
            fail("R5-ra-hash-library", f"{RA_SOURCE_PATH} missing gateOpen binding")
        ra_binding_count = count_callable_refs(ra_view, RA_HASH_CALLABLE)
        if ra_binding_count != 1:
            fail(
                "R5-ra-hash-library",
                f"{RA_SOURCE_PATH} must bind {RA_HASH_CALLABLE} exactly once (found {ra_binding_count})",
            )
        if "hashLibraryGet()" not in ra_view:
            fail(
                "R5-ra-hash-library",
                f"{RA_SOURCE_PATH} must invoke the bound hash-library callable in live code",
            )
        for path in main_source_files(".kt"):
            r = rel(path)
            if r in {HASH_LIBRARY_PATH, RA_SOURCE_PATH}:
                continue
            view = code_view(path)
            refs = count_callable_refs(view, RA_HASH_CALLABLE)
            if refs:
                fail("R5-ra-hash-library", f"{r} references {RA_HASH_CALLABLE} outside audited caller seam")
            if RA_HASH_LIBRARY_SYMBOL in view:
                fail("R5-ra-hash-library", f"{r} references {RA_HASH_LIBRARY_SYMBOL} outside audited transport")

    # ── R6 gate host suffix authority (disjoint, comment-free literals) ──────────
    checks += 1
    other_suffixes = [s for g, ss in GATE_HOST_SUFFIXES.items() for s in ss]
    for gate, suffixes in GATE_HOST_SUFFIXES.items():
        gate_file = ROOT / "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture" / f"{gate}.kt"
        if not gate_file.is_file():
            fail("R6-hosts", f"{gate} definition missing")
            continue
        raw = gate_file.read_text(encoding="utf-8")
        if re.search(r"//.*\"[a-z0-9.\-]+\.[a-z]{2,}\"", raw):
            fail("R6-hosts", f"{gate} host literals must not live in comments")
        hosts = gate_host_literals(gate_file)
        if not hosts:
            fail("R6-hosts", f"{gate} missing exact host literal set")
        for host in hosts:
            if not any(host == suffix or host.endswith("." + suffix) for suffix in suffixes):
                fail("R6-hosts", f"{gate} allowlists '{host}' outside allowed suffixes {suffixes}")
        for host in hosts:
            for other in other_suffixes:
                if other not in suffixes and (host == other or host.endswith("." + other)):
                    fail("R6-hosts-disjoint", f"{gate} host '{host}' overlaps foreign suffix {other}")

    # ── R7 evidence directory binding ────────────────────────────────────────────
    checks += 1
    if not EVIDENCE_DIR.is_dir():
        fail("R7-evidence", f"missing evidence directory {rel(EVIDENCE_DIR)}")
    if not EVIDENCE_BINDING.is_file():
        fail("R7-evidence", f"missing evidence binding file {rel(EVIDENCE_BINDING)}")
    else:
        binding = EVIDENCE_BINDING.read_text(encoding="utf-8")
        if "mimir_connected_posture_gate.py" not in binding:
            fail("R7-evidence", "BINDING.md does not reference mimir_connected_posture_gate.py")

    # ── R8 vendor leak ───────────────────────────────────────────────────────────
    checks += 1
    for path in ui_gate.iter_repo_paths(ROOT):
        r = rel(path)
        if ui_gate.is_excluded_worktree_path(r):
            continue
        if "/build/" in f"/{r}/" or "/.git/" in f"/{r}/":
            continue
        in_assets = "/src/main/assets/" in r
        in_res = "/src/main/res/" in r and path.suffix == ".xml"
        is_java = path.suffix == ".java" and "/src/main/" in r
        if not (in_assets or in_res or is_java):
            continue
        if r == "app/src/main/assets/legal/NOTICE":
            continue
        # Compressed/binary DAT payloads (e.g. MAME's .mdepgz) decode to noise under a permissive
        # utf-8 read, and a short vendor token can appear in that noise by pure chance -- confirmed:
        # mame0287.mdepgz's compressed bytes happen to contain "IgDb" once VENDOR grew a 4-letter
        # term. Skip anything named like a compressed blob, and skip anything binary by content too
        # (a NUL in the first 512 bytes is not valid UTF-8/XML/Java source text) as a second net for
        # any other compressed/binary asset extension this rule does not yet name.
        name = path.name.lower()
        if name.endswith(".gz") or name.endswith(".mdepgz"):
            continue
        try:
            head = path.read_bytes()[:512]
        except OSError:
            head = b""
        if b"\x00" in head:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        if VENDOR.search(vendor_resource_view(text, r)):
            fail("R8-vendor-leak", f"{r} carries a vendor identifier outside gated Kotlin")

    # ── R9 cleartext ─────────────────────────────────────────────────────────────
    checks += 1
    for path in main_source_files(".kt"):
        view = p1_view(path)
        for lineno, line in enumerate(view.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith(("*", "//", "/*")):
                continue
            if "startsWith(" in line or "setFeature(" in line:
                continue
            if HTTP_LITERAL.search(line) and "localhost" not in line and "127.0.0.1" not in line:
                fail("R9-cleartext", f"{rel(path)}:{lineno} http:// literal in main source")
    for path in ui_gate.iter_repo_paths(ROOT):
        if path.name != "AndroidManifest.xml":
            continue
        r = rel(path)
        if ui_gate.is_excluded_worktree_path(r):
            continue
        if "/build/" in f"/{r}/":
            continue
        if 'usesCleartextTraffic="true"' in path.read_text(encoding="utf-8", errors="ignore"):
            fail("R9-cleartext", f"{r} enables cleartext traffic")

    # ── R10 credential/url logging (multiline-aware) ─────────────────────────────
    checks += 1
    for path in main_source_files(".kt"):
        for finding in multiline_log_violations(p1_view(path), rel(path)):
            fail("R10-logging", finding)

    # ── verdict ──────────────────────────────────────────────────────────────────
    if failures:
        print(f"POSTURE GATE: FAIL ({len(failures)} finding(s) across {checks} rules)")
        for finding in failures:
            print(f"  {finding}")
        return 1
    print(
        "POSTURE GATE: PASS "
        f"({checks} rules — dual gate owners, audited chokepoints, disjoint hosts, evidence binding, "
        "no vendor leak, no cleartext, no credential/url logging)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
