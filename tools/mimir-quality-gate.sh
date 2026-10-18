#!/usr/bin/env sh
# Mimir Alpha offline quality gate ladder.
# Default: cached Gradle runs. --forced: --no-build-cache --rerun-tasks. --full: extra module tests.
# Emits exactly one terminal verdict; summaries precede PASS.

set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
GRADLE_SH="$ROOT/tools/mimir-gradle.sh"
POSTURE_GATE="$ROOT/tools/mimir_connected_posture_gate.py"
UI_GATE="$ROOT/tools/mimir_ui_invariants_gate.py"
TEXT_GATE="$ROOT/tools/mimir_hardcoded_text_gate.py"
TEST_GRADLE_SH="$ROOT/tools/test_mimir_gradle.sh"
TEST_QUALITY_GATE_SH="$ROOT/tools/test_mimir_quality_gate.sh"
TEST_SPECIAL_ACCESS_SH="$ROOT/tools/test_cert_special_access.sh"
TEST_UI_GATE_PY="$ROOT/tools/test_mimir_ui_invariants_gate.py"
TEST_TEXT_GATE_PY="$ROOT/tools/test_mimir_hardcoded_text_gate.py"
TEST_POSTURE_GATE_PY="$ROOT/tools/test_mimir_connected_posture_gate.py"
TEST_RELEASE_KIT_SH="$ROOT/tools/test_build_mimir_release_kit.sh"
TEST_THERMAL_CAPTURE_SH="$ROOT/tools/test_mimir_thermal_capture.sh"

FORCED=0
FULL=0
STEP=0
GATE_FAILED=0
FAILED_STEP=""
JVM_MODULES=""
LINT_RAN=0
TWEAKER_TESTS_RAN=0
VERDICT_EMITTED=0

usage() {
  cat <<'EOF'
Usage: tools/mimir-quality-gate.sh [--forced] [--full]

  (default)   Cached offline ladder
  --forced    Add --no-build-cache --rerun-tasks to every Gradle step
  --full      Also run core-metadata, core-storage, core-ui, core-platform tests
EOF
}

die_unknown() {
  printf 'mimir-quality-gate: unknown argument: %s\n' "$1" >&2
  usage >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --forced) FORCED=1 ;;
    --full) FULL=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die_unknown "$1" ;;
  esac
  shift
done

GRADLE_EXTRA=""
if [ "$FORCED" -eq 1 ]; then
  GRADLE_EXTRA="--no-build-cache --rerun-tasks"
fi

# shellcheck disable=SC2086
run_gradle() {
  # shellcheck disable=SC2086
  "$GRADLE_SH" "$@" --offline --console=plain $GRADLE_EXTRA
}

# Tweaker is an intentionally isolated nested Android build. Release truth must include both the
# mutable LAB posture and the signed-manifest Production posture; root Gradle cannot see these tasks.
run_tweaker_gradle() {
  # shellcheck disable=SC2086
  "$GRADLE_SH" -p "$ROOT/mimir-tweaker" "$@" --offline --console=plain $GRADLE_EXTRA
}

summarize_junit_result() {
  result_root="$1"
  task="$2"
  label="$3"
  python3 - "$result_root" "$task" "$label" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

mod_root = Path(sys.argv[1])
task = sys.argv[2]
label = sys.argv[3]
base = mod_root / f"build/test-results/{task}"
if not base.exists():
    print(f"  {label}: MISSING {task} results", file=sys.stderr)
    raise SystemExit(1)
tests = failures = errors = skipped = files = 0
for fpath in sorted(base.glob("TEST-*.xml")):
    if not fpath.stat().st_size:
        print(f"  {label}: EMPTY {fpath.name}", file=sys.stderr)
        raise SystemExit(1)
    try:
        root = ET.parse(fpath).getroot()
    except ET.ParseError:
        print(f"  {label}: MALFORMED {fpath.name}", file=sys.stderr)
        raise SystemExit(1)
    tests += int(root.attrib.get("tests", 0))
    failures += int(root.attrib.get("failures", 0))
    errors += int(root.attrib.get("errors", 0))
    skipped += int(root.attrib.get("skipped", 0))
    files += 1
if files == 0:
    print(f"  {label}: MISSING {task} results", file=sys.stderr)
    raise SystemExit(1)
print(f"  {label}: {tests} tests ({failures}F/{errors}E/{skipped}S) in {files} XML ({task})")
PY
}

summarize_junit_module() {
  mod="$1"
  task="testDebugUnitTest"
  case "$mod" in
    core-model|core-platform) task="test" ;;
  esac
  summarize_junit_result "$ROOT/$mod" "$task" "$mod"
}

summarize_lint() {
  python3 - "$ROOT/app/build/reports/lint-results-release.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

path = Path(sys.argv[1])
if not path.is_file():
    print("  app lintRelease: MISSING lint-results-release.xml", file=sys.stderr)
    raise SystemExit(1)
if path.stat().st_size == 0:
    print("  app lintRelease: EMPTY lint-results-release.xml", file=sys.stderr)
    raise SystemExit(1)
try:
    root = ET.parse(path).getroot()
except ET.ParseError:
    print("  app lintRelease: MALFORMED lint-results-release.xml", file=sys.stderr)
    raise SystemExit(1)
counts = {"Fatal": 0, "Error": 0, "Warning": 0, "Information": 0}
for issue in root.findall("issue"):
    sev = issue.attrib.get("severity", "")
    if sev in counts:
        counts[sev] += 1
print(
    "  app lintRelease: "
    f"fatal={counts['Fatal']} error={counts['Error']} "
    f"warning={counts['Warning']} information={counts['Information']}"
)
PY
}

# Build provenance — INFORMATIONAL ONLY, never a verdict input.
#
# The device-certified kit was built from a checkout with no local.properties, so BuildConfig
# SCREENSCRAPER_DEV_ID/SCREENSCRAPER_DEV_PASSWORD baked to empty strings; that made
# ScreenScraperDevRegistration.isConfigured false, which permanently closed MimirConnectedArtGate, which
# made Library Enrich tell the player to connect an account Settings simultaneously showed as connected.
# Nothing in the gate could see that, because the empty state is legal.
#
# It stays legal here. core-metadata/build.gradle.kts documents empty creds as a SUPPORTED state — a
# fresh clone has the fetch feature unconfigured and stays fully offline — so this ladder must not fail
# on it, and this function must never touch GATE_FAILED. It only makes the state legible in every run.
# tools/build-mimir-release-kit.sh is the one path that refuses absence, because only that path
# produces a certification artifact.
#
# POSTURE: presence/absence only. Credential VALUES are never printed, logged, or written.
report_build_provenance() {
  provenance_state="$(
    python3 - "$ROOT/local.properties" <<'PY'
import sys
from pathlib import Path

# Emits exactly one word — "configured" or "unconfigured". Never a credential value.
wanted = {"screenscraper.devid": "", "screenscraper.devpassword": ""}
path = Path(sys.argv[1])
if path.is_file():
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line[0] in "#!":
            continue
        for separator in ("=", ":"):
            key, found, value = line.partition(separator)
            if found:
                if key.strip() in wanted:
                    wanted[key.strip()] = value.strip()
                break
print("configured" if all(wanted.values()) else "unconfigured")
PY
  )" || provenance_state=error
  printf '\n== Build provenance ==\n'
  case "$provenance_state" in
    configured)
      printf '  ScreenScraper registration: CONFIGURED (informational; never changes the verdict)\n'
      printf '    local.properties supplies screenscraper.devid / screenscraper.devpassword (values never printed)\n'
      printf '    APKs built here bake ScreenScraperDevRegistration.isConfigured=true\n'
      ;;
    unconfigured)
      printf '  ScreenScraper registration: UNCONFIGURED (informational; never changes the verdict)\n'
      printf '    local.properties keys screenscraper.devid / screenscraper.devpassword are absent or empty\n'
      printf '    APKs built here bake ScreenScraperDevRegistration.isConfigured=false; the connected-art gate stays closed\n'
      ;;
    *)
      printf '  ScreenScraper registration: UNKNOWN (informational; never changes the verdict)\n'
      printf '    provenance could not be read; the state is not asserted in either direction\n'
      ;;
  esac
  return 0
}

print_summaries() {
  report_build_provenance
  if [ -n "$JVM_MODULES" ]; then
    printf '\n== JUnit summary ==\n'
    summary_failed=0
    for mod in $JVM_MODULES; do
      if ! summarize_junit_module "$mod"; then
        summary_failed=1
      fi
    done
    if [ "$summary_failed" -ne 0 ]; then
      GATE_FAILED=1
    fi
  fi
  if [ "$LINT_RAN" -eq 1 ]; then
    printf '\n== Lint summary ==\n'
    if ! summarize_lint; then
      GATE_FAILED=1
    fi
  fi
  if [ "$TWEAKER_TESTS_RAN" -eq 1 ]; then
    printf '\n== Tweaker JUnit summary ==\n'
    tweaker_summary_failed=0
    summarize_junit_result \
      "$ROOT/mimir-tweaker/app" \
      "testLabDebugUnitTest" \
      "mimir-tweaker LAB" || tweaker_summary_failed=1
    summarize_junit_result \
      "$ROOT/mimir-tweaker/app" \
      "testProductionDebugUnitTest" \
      "mimir-tweaker Production debug" || tweaker_summary_failed=1
    summarize_junit_result \
      "$ROOT/mimir-tweaker/app" \
      "testProductionReleaseUnitTest" \
      "mimir-tweaker Production release" || tweaker_summary_failed=1
    if [ "$tweaker_summary_failed" -ne 0 ]; then
      GATE_FAILED=1
    fi
  fi
}

emit_verdict() {
  if [ "$VERDICT_EMITTED" -eq 1 ]; then
    return 0
  fi
  VERDICT_EMITTED=1
  print_summaries
  if [ "$GATE_FAILED" -ne 0 ]; then
    if [ -n "$FAILED_STEP" ]; then
      printf '\nQUALITY GATE: FAIL at step: %s\n' "$FAILED_STEP" >&2
    else
      printf '\nQUALITY GATE: FAIL (summary)\n' >&2
    fi
    return 1
  fi
  printf '\nQUALITY GATE: PASS (%s mode%s)\n' \
    "$([ "$FORCED" -eq 1 ] && printf forced || printf cached)" \
    "$([ "$FULL" -eq 1 ] && printf ', full' || printf '')"
  return 0
}

on_exit() {
  if [ "$VERDICT_EMITTED" -eq 1 ]; then
    return 0
  fi
  emit_verdict
}
trap on_exit EXIT

fail() {
  GATE_FAILED=1
  FAILED_STEP="$1"
  exit 1
}

run_step() {
  STEP=$((STEP + 1))
  name="$1"
  shift
  printf '\n==[%s] %s==\n' "$STEP" "$name"
  if "$@"; then
    printf 'PASS: %s\n' "$name"
  else
    fail "$name"
  fi
}

run_tool_self_tests() {
  sh "$TEST_GRADLE_SH" || return 1
  sh "$TEST_QUALITY_GATE_SH" || return 1
  bash "$TEST_SPECIAL_ACCESS_SH" || return 1
  python3 "$TEST_UI_GATE_PY" || return 1
  python3 "$TEST_TEXT_GATE_PY" || return 1
  python3 "$TEST_POSTURE_GATE_PY" || return 1
  sh "$TEST_RELEASE_KIT_SH" || return 1
  bash "$TEST_THERMAL_CAPTURE_SH" || return 1
}

cd "$ROOT"

run_step "tool self-tests" run_tool_self_tests

run_step "app assembleDebug" run_gradle :app:assembleDebug
run_step "app assembleRelease (unsigned artifact)" run_gradle :app:assembleRelease

# Coverage correction 2026-07-24: six modules shipped real, passing suites that NEITHER the default nor
# --full ladder ran — feature-settings 41, feature-companion 42, feature-browse 1, core-launch 102,
# core-audio 19, core-companion-native 60 = 265 tests outside the gate. All six were measured green before
# being added, so this closes a coverage hole without changing any verdict. Each is pinned individually in
# test_mimir_quality_gate.sh; the gap was invisible because nothing asserted the module set.
JVM_MODULES="core-resolver core-input feature-library feature-settings feature-companion feature-browse app core-model core-launch core-audio core-companion-native"
# 2026-08-23: core-tweaker-client, core-files and feature-files were absent from this list, so
# the ladder printed PASS while core-tweaker-client sat red on the branch -- 1 failing test the
# gate structurally could not see. Added only once that module was green, because turning them
# on over a known failure would have reddened the gate for every other lane instead of fixing
# it. feature-files (805) and core-files (178) were clean already; they are here so the same
# blind spot cannot reopen.
JVM_TASKS=":core-resolver:testDebugUnitTest :core-input:testDebugUnitTest :feature-library:testDebugUnitTest :feature-settings:testDebugUnitTest :feature-companion:testDebugUnitTest :feature-browse:testDebugUnitTest :app:testDebugUnitTest :core-model:test :core-launch:testDebugUnitTest :core-audio:testDebugUnitTest :core-companion-native:testDebugUnitTest :core-tweaker-client:testDebugUnitTest :core-files:testDebugUnitTest :feature-files:testDebugUnitTest"
if [ "$FULL" -eq 1 ]; then
  JVM_MODULES="$JVM_MODULES core-metadata core-storage core-ui core-platform"
  JVM_TASKS="$JVM_TASKS :core-metadata:testDebugUnitTest :core-storage:testDebugUnitTest :core-ui:testDebugUnitTest :core-platform:test"
fi

# shellcheck disable=SC2086
run_step "JVM unit tests" run_gradle $JVM_TASKS

TWEAKER_TESTS_RAN=1
run_step "Tweaker LAB + Production variant gate" run_tweaker_gradle \
  :app:testLabDebugUnitTest \
  :app:testProductionDebugUnitTest \
  :app:testProductionReleaseUnitTest \
  :app:verifyQualificationGeneratorNotPackaged

LINT_RAN=1
run_step "app lintRelease" run_gradle :app:lintRelease

run_step "connected posture gate" python3 "$POSTURE_GATE"

if [ ! -f "$UI_GATE" ]; then
  fail "UI invariants gate missing (C5 required)"
fi
run_step "UI invariants gate" python3 "$UI_GATE"

# Localization step 0: hardcoded Text("...") may only fall, per module (tools/mimir-hardcoded-text-baseline.tsv).
run_step "hardcoded UI text ratchet" python3 "$TEXT_GATE"

emit_verdict
exit 0
