#!/usr/bin/env sh
# Hermetic self-tests for tools/mimir-quality-gate.sh (no production gate recursion).

set -eu

TOOLS="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SOURCE_GATE="$TOOLS/mimir-quality-gate.sh"
PASS=0
FAIL=0

assert_eq() {
  got="$1"
  want="$2"
  label="$3"
  if [ "$got" = "$want" ]; then
    PASS=$((PASS + 1))
  else
    printf 'FAIL %s: got [%s] want [%s]\n' "$label" "$got" "$want" >&2
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  haystack="$1"
  needle="$2"
  label="$3"
  case "$haystack" in
    *"$needle"*) PASS=$((PASS + 1)) ;;
    *)
      printf 'FAIL %s: missing [%s]\n' "$label" "$needle" >&2
      FAIL=$((FAIL + 1))
      ;;
  esac
}

assert_not_contains() {
  haystack="$1"
  needle="$2"
  label="$3"
  case "$haystack" in
    *"$needle"*)
      printf 'FAIL %s: unexpected [%s]\n' "$label" "$needle" >&2
      FAIL=$((FAIL + 1))
      ;;
    *) PASS=$((PASS + 1)) ;;
  esac
}

count_substring() {
  haystack="$1"
  needle="$2"
  count=0
  rest="$haystack"
  while case "$rest" in *"$needle"*) rest="${rest#*"$needle"}"; count=$((count + 1)) ;; *) break ;; esac; do :; done
  printf '%s' "$count"
}

setup_fixture() {
  FIXTURE="$1"
  mkdir -p "$FIXTURE/tools"
  GRADLE_LOG="$FIXTURE/gradle.log"
  : >"$GRADLE_LOG"

  cp "$SOURCE_GATE" "$FIXTURE/tools/mimir-quality-gate.sh"
  chmod +x "$FIXTURE/tools/mimir-quality-gate.sh"

  cat >"$FIXTURE/tools/mimir-gradle.sh" <<EOF
#!/usr/bin/env sh
printf '%s\n' "\$*" >>"$GRADLE_LOG"
case "\$*" in
  *mimir-tweaker*)
    if [ -f "$FIXTURE/.tweaker-fail" ]; then
      exit 1
    fi
    ;;
  *testDebugUnitTest*|*" :core-model:test"*|*" :core-platform:test"*|*:core-model:test*|*:core-platform:test*)
    if [ -f "$FIXTURE/.jvm-fail" ]; then
      exit 1
    fi
    ;;
  *lintRelease*)
    if [ -f "$FIXTURE/.lint-fail" ]; then
      exit 1
    fi
    ;;
  *assembleDebug*|*assembleRelease*)
    if [ -f "$FIXTURE/.assemble-fail" ]; then
      exit 1
    fi
    ;;
esac
exit 0
EOF
  chmod +x "$FIXTURE/tools/mimir-gradle.sh"

  cat >"$FIXTURE/tools/mimir_ui_invariants_gate.py" <<'EOF'
#!/usr/bin/env python3
raise SystemExit(0)
EOF
  chmod +x "$FIXTURE/tools/mimir_ui_invariants_gate.py"

  cat >"$FIXTURE/tools/mimir_hardcoded_text_gate.py" <<'EOF'
#!/usr/bin/env python3
raise SystemExit(0)
EOF
  chmod +x "$FIXTURE/tools/mimir_hardcoded_text_gate.py"

  cat >"$FIXTURE/tools/mimir_connected_posture_gate.py" <<'EOF'
#!/usr/bin/env python3
raise SystemExit(0)
EOF
  chmod +x "$FIXTURE/tools/mimir_connected_posture_gate.py"

  cat >"$FIXTURE/tools/test_mimir_gradle.sh" <<EOF
#!/usr/bin/env sh
if [ -f "$FIXTURE/.fail-gradle-selftest" ]; then
  exit 1
fi
exit 0
EOF
  chmod +x "$FIXTURE/tools/test_mimir_gradle.sh"

  cat >"$FIXTURE/tools/test_mimir_quality_gate.sh" <<EOF
#!/usr/bin/env sh
if [ -f "$FIXTURE/.fail-quality-selftest" ]; then
  exit 1
fi
exit 0
EOF
  chmod +x "$FIXTURE/tools/test_mimir_quality_gate.sh"

  cat >"$FIXTURE/tools/test_cert_special_access.sh" <<EOF
#!/usr/bin/env sh
if [ -f "$FIXTURE/.fail-special-access-selftest" ]; then
  exit 1
fi
exit 0
EOF
  chmod +x "$FIXTURE/tools/test_cert_special_access.sh"

  cat >"$FIXTURE/tools/test_mimir_ui_invariants_gate.py" <<'EOF'
#!/usr/bin/env python3
import os
import sys
fixture = os.environ.get("MIMIR_QG_FIXTURE", "")
if fixture and os.path.isfile(os.path.join(fixture, ".fail-ui-selftest")):
    raise SystemExit(1)
raise SystemExit(0)
EOF

  cat >"$FIXTURE/tools/test_mimir_hardcoded_text_gate.py" <<'EOF'
#!/usr/bin/env python3
import os
fixture = os.environ.get("MIMIR_QG_FIXTURE", "")
if fixture and os.path.isfile(os.path.join(fixture, ".fail-text-selftest")):
    raise SystemExit(1)
raise SystemExit(0)
EOF

  cat >"$FIXTURE/tools/test_build_mimir_release_kit.sh" <<EOF
#!/bin/sh
if [ -f "$FIXTURE/.fail-release-kit-selftest" ]; then
  exit 1
fi
exit 0
EOF
  chmod +x "$FIXTURE/tools/test_build_mimir_release_kit.sh"

  cat >"$FIXTURE/tools/test_mimir_thermal_capture.sh" <<EOF
#!/usr/bin/env bash
if [ -f "$FIXTURE/.fail-thermal-capture-selftest" ]; then
  exit 1
fi
exit 0
EOF
  chmod +x "$FIXTURE/tools/test_mimir_thermal_capture.sh"

  make_junit_xml() {
    mod="$1"
    task="$2"
    dir="$FIXTURE/$mod/build/test-results/$task"
    mkdir -p "$dir"
    cat >"$dir/TEST-demo.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="demo" tests="2" failures="0" errors="0" skipped="0"></testsuite>
EOF
  }

  make_junit_xml core-resolver testDebugUnitTest
  make_junit_xml core-input testDebugUnitTest
  make_junit_xml feature-library testDebugUnitTest
  make_junit_xml app testDebugUnitTest
  make_junit_xml core-model test
  make_junit_xml core-platform test
  # Added 2026-07-24 with the six modules whose suites the ladder never ran.
  make_junit_xml feature-settings testDebugUnitTest
  make_junit_xml feature-companion testDebugUnitTest
  make_junit_xml feature-browse testDebugUnitTest
  make_junit_xml core-launch testDebugUnitTest
  make_junit_xml core-audio testDebugUnitTest
  make_junit_xml core-companion-native testDebugUnitTest
  make_junit_xml core-metadata testDebugUnitTest
  make_junit_xml core-storage testDebugUnitTest
  make_junit_xml core-ui testDebugUnitTest
  make_junit_xml mimir-tweaker/app testLabDebugUnitTest
  make_junit_xml mimir-tweaker/app testProductionDebugUnitTest
  make_junit_xml mimir-tweaker/app testProductionReleaseUnitTest

  mkdir -p "$FIXTURE/app/build/reports"
  cat >"$FIXTURE/app/build/reports/lint-results-release.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<issues format="6">
  <issue id="a" severity="Fatal" message="fatal"/>
  <issue id="b" severity="Error" message="error"/>
  <issue id="c" severity="Warning" message="warning"/>
  <issue id="d" severity="Information" message="information"/>
</issues>
EOF
}

add_posture_self_test() {
  cat >"$FIXTURE/tools/test_mimir_connected_posture_gate.py" <<EOF
#!/usr/bin/env python3
import os
fixture = os.environ.get("MIMIR_QG_FIXTURE", "")
if fixture and os.path.isfile(os.path.join(fixture, ".fail-posture-selftest")):
    raise SystemExit(1)
raise SystemExit(0)
EOF
}

run_fixture_gate() {
  MIMIR_QG_FIXTURE="$FIXTURE" sh "$FIXTURE/tools/mimir-quality-gate.sh" "$@"
}

run_tests() {
  TMP_BASE="$(mktemp -d "${TMPDIR:-/tmp}/mimir-qg-test.XXXXXX")"
  trap 'rm -rf "$TMP_BASE"' EXIT

  FIXTURE="$TMP_BASE/fixture"
  setup_fixture "$FIXTURE"

  # Success path requires posture self-test present
  add_posture_self_test
  : >"$GRADLE_LOG"
  out="$(run_fixture_gate 2>&1)" || status=$?
  status="${status:-0}"
  assert_eq "$status" "0" "success exit"
  while read -r line; do
    assert_contains "$line" "--offline" "gradle log offline"
    assert_contains "$line" "--console=plain" "gradle log console"
  done <"$GRADLE_LOG"
  assert_eq "$(count_substring "$(tr '\n' ' ' <"$GRADLE_LOG")" ":core-model:test")" "1" "default gradle core-model exactly once"
  assert_contains "$(tr '\n' ' ' <"$GRADLE_LOG")" ":app:testLabDebugUnitTest" "Tweaker LAB suite in gate"
  assert_contains "$(tr '\n' ' ' <"$GRADLE_LOG")" ":app:testProductionDebugUnitTest" "Tweaker Production suite in gate"
  assert_contains "$(tr '\n' ' ' <"$GRADLE_LOG")" ":app:testProductionReleaseUnitTest" "Tweaker Production release suite in gate"
  assert_contains "$(tr '\n' ' ' <"$GRADLE_LOG")" ":app:verifyQualificationGeneratorNotPackaged" "Tweaker signer isolation in gate"
  assert_contains "$(tr '\n' ' ' <"$GRADLE_LOG")" ":app:assembleRelease" "Mimir release artifact in gate"
  assert_eq "$(count_substring "$out" "core-model: 2 tests")" "1" "default core-model summary exactly once"
  assert_contains "$out" "== JUnit summary ==" "success junit summary"
  assert_contains "$out" "core-resolver: 2 tests" "resolver junit line"
  assert_contains "$out" "core-model: 2 tests" "default core-model summary"
  # These six shipped real, passing suites that the default ladder never ran (265 tests, measured green
  # 2026-07-24). Pinned individually so a module cannot silently drop out of coverage again — the previous
  # gap was invisible precisely because nothing asserted the set.
  assert_contains "$out" "feature-settings: 2 tests" "default feature-settings summary"
  assert_contains "$out" "feature-companion: 2 tests" "default feature-companion summary"
  assert_contains "$out" "feature-browse: 2 tests" "default feature-browse summary"
  assert_contains "$out" "core-launch: 2 tests" "default core-launch summary"
  assert_contains "$out" "core-audio: 2 tests" "default core-audio summary"
  assert_contains "$out" "core-companion-native: 2 tests" "default core-companion-native summary"
  assert_contains "$out" "== Tweaker JUnit summary ==" "Tweaker junit summary"
  assert_contains "$out" "mimir-tweaker LAB: 2 tests" "Tweaker LAB junit line"
  assert_contains "$out" "mimir-tweaker Production debug: 2 tests" "Tweaker Production debug junit line"
  assert_contains "$out" "mimir-tweaker Production release: 2 tests" "Tweaker Production release junit line"
  assert_contains "$out" "(test)" "jvm test task label"
  assert_contains "$out" "== Lint summary ==" "success lint summary"
  assert_contains "$out" "fatal=1 error=1 warning=1 information=1" "lint severities"
  assert_contains "$out" "QUALITY GATE: PASS" "success pass banner"
  assert_eq "$(count_substring "$out" "QUALITY GATE: PASS")" "1" "single pass banner"
  assert_not_contains "$out" "--no-build-cache" "cached mode no forced flags"

  # Build provenance is INFORMATIONAL. This fixture has no local.properties — exactly the state that
  # produced a device-certified APK with an empty BuildConfig ScreenScraper registration — and the
  # ladder must still PASS. Empty creds are a supported build state; refusing them belongs to
  # tools/build-mimir-release-kit.sh, which is the only path that emits a certification artifact.
  assert_contains "$out" "== Build provenance ==" "provenance section present"
  assert_contains "$out" "ScreenScraper registration: UNCONFIGURED" "provenance unconfigured label"
  assert_eq "$(count_substring "$out" "== Build provenance ==")" "1" "provenance printed exactly once"

  # A present registration flips the label, not the verdict — and never prints the values themselves.
  qg_dev_id=qg-fixture-devid-not-a-real-registration
  qg_dev_password=qg-fixture-devpassword-not-a-real-registration
  cat >"$FIXTURE/local.properties" <<EOF
# fixture registration — fake values, never printed
screenscraper.devid=$qg_dev_id
screenscraper.devpassword=$qg_dev_password
EOF
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "0" "configured provenance still exits zero"
  assert_contains "$out" "ScreenScraper registration: CONFIGURED" "provenance configured label"
  assert_contains "$out" "QUALITY GATE: PASS" "configured provenance pass banner"
  assert_not_contains "$out" "$qg_dev_id" "provenance never prints devid"
  assert_not_contains "$out" "$qg_dev_password" "provenance never prints devpassword"

  # A blank value is unconfigured, matching ScreenScraperDevRegistration.isConfigured's isNullOrBlank.
  cat >"$FIXTURE/local.properties" <<EOF
screenscraper.devid=$qg_dev_id
screenscraper.devpassword=
EOF
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "0" "half-configured provenance still exits zero"
  assert_contains "$out" "ScreenScraper registration: UNCONFIGURED" "half-configured reads unconfigured"
  rm -f "$FIXTURE/local.properties"

  # Step failure must not print PASS
  : >"$GRADLE_LOG"
  : >"$FIXTURE/.assemble-fail"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "assemble failure exit"
  assert_not_contains "$out" "QUALITY GATE: PASS" "assemble failure no pass"
  assert_contains "$out" "QUALITY GATE: FAIL" "assemble failure banner"
  assert_contains "$out" "== Build provenance ==" "provenance prints on the failure path too"
  rm -f "$FIXTURE/.assemble-fail"

  # Fake-Gradle JVM failure
  : >"$FIXTURE/.jvm-fail"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "jvm failure exit"
  assert_not_contains "$out" "QUALITY GATE: PASS" "jvm failure no pass"
  assert_contains "$out" "JVM unit tests" "jvm failure step"
  rm -f "$FIXTURE/.jvm-fail"

  # Nested Tweaker verification is a variant gate, not an advisory suite.
  : >"$FIXTURE/.tweaker-fail"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "Tweaker gate failure exit"
  assert_contains "$out" "Tweaker LAB + Production variant gate" "Tweaker failure step"
  assert_not_contains "$out" "QUALITY GATE: PASS" "Tweaker failure no pass"
  rm -f "$FIXTURE/.tweaker-fail"

  # Nested Tweaker suites fail closed even when Gradle itself exits zero. This catches NO-SOURCE,
  # stale-deletion, and malformed-report regressions that task invocation alone cannot prove.
  rm -rf "$FIXTURE/mimir-tweaker/app/build/test-results/testLabDebugUnitTest"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "missing Tweaker LAB junit exit"
  assert_contains "$out" "mimir-tweaker LAB: MISSING testLabDebugUnitTest results" "missing Tweaker LAB detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "missing Tweaker LAB no pass"
  make_junit_xml mimir-tweaker/app testLabDebugUnitTest

  : >"$FIXTURE/mimir-tweaker/app/build/test-results/testProductionDebugUnitTest/TEST-demo.xml"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "empty Tweaker Production junit exit"
  assert_contains "$out" "mimir-tweaker Production debug: EMPTY TEST-demo.xml" "empty Tweaker Production detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "empty Tweaker Production no pass"
  make_junit_xml mimir-tweaker/app testProductionDebugUnitTest

  rm -rf "$FIXTURE/mimir-tweaker/app/build/test-results/testProductionReleaseUnitTest"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "missing Tweaker Production release junit exit"
  assert_contains "$out" "mimir-tweaker Production release: MISSING testProductionReleaseUnitTest results" "missing Tweaker Production release detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "missing Tweaker Production release no pass"
  make_junit_xml mimir-tweaker/app testProductionReleaseUnitTest

  printf 'not xml' >"$FIXTURE/mimir-tweaker/app/build/test-results/testLabDebugUnitTest/TEST-demo.xml"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "malformed Tweaker LAB junit exit"
  assert_contains "$out" "mimir-tweaker LAB: MALFORMED TEST-demo.xml" "malformed Tweaker LAB detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "malformed Tweaker LAB no pass"
  make_junit_xml mimir-tweaker/app testLabDebugUnitTest

  # Lint Gradle failure
  : >"$FIXTURE/.lint-fail"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "lint gradle failure exit"
  assert_not_contains "$out" "QUALITY GATE: PASS" "lint gradle failure no pass"
  rm -f "$FIXTURE/.lint-fail"

  # Missing JUnit results fails closed but still prints summary header
  rm -rf "$FIXTURE/app/build/test-results/testDebugUnitTest"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "missing junit exit"
  assert_contains "$out" "== JUnit summary ==" "failure junit summary header"
  assert_contains "$out" "MISSING testDebugUnitTest results" "missing junit detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "missing junit no pass"

  # Empty JUnit XML
  make_junit_xml app testDebugUnitTest
  : >"$FIXTURE/app/build/test-results/testDebugUnitTest/TEST-demo.xml"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "empty junit exit"
  assert_contains "$out" "EMPTY TEST-demo.xml" "empty junit detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "empty junit no pass"
  make_junit_xml app testDebugUnitTest

  # Malformed JUnit XML
  printf 'not xml' >"$FIXTURE/app/build/test-results/testDebugUnitTest/TEST-demo.xml"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "malformed junit exit"
  assert_contains "$out" "MALFORMED TEST-demo.xml" "malformed junit detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "malformed junit no pass"
  make_junit_xml app testDebugUnitTest

  # Missing lint report
  rm -f "$FIXTURE/app/build/reports/lint-results-release.xml"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "missing lint exit"
  assert_contains "$out" "== Lint summary ==" "failure lint summary header"
  assert_contains "$out" "MISSING lint-results-release.xml" "missing lint detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "missing lint no pass"

  # Empty lint report
  mkdir -p "$FIXTURE/app/build/reports"
  : >"$FIXTURE/app/build/reports/lint-results-release.xml"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "empty lint exit"
  assert_contains "$out" "EMPTY lint-results-release.xml" "empty lint detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "empty lint no pass"

  # Malformed lint report
  printf 'not xml' >"$FIXTURE/app/build/reports/lint-results-release.xml"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "malformed lint exit"
  assert_contains "$out" "MALFORMED lint-results-release.xml" "malformed lint detail"
  assert_not_contains "$out" "QUALITY GATE: PASS" "malformed lint no pass"
  cat >"$FIXTURE/app/build/reports/lint-results-release.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?><issues></issues>
EOF

  # --full includes core-model/core-platform test task labels
  : >"$GRADLE_LOG"
  out="$(run_fixture_gate --full 2>&1)" || status=$?
  status="${status:-0}"
  assert_contains "$out" "core-model: 2 tests" "full core-model summary"
  assert_contains "$out" "(test)" "jvm test task label"
  assert_contains "$(tr '\n' ' ' <"$GRADLE_LOG")" ":core-model:test" "full gradle core-model:test"
  assert_contains "$(tr '\n' ' ' <"$GRADLE_LOG")" ":core-platform:test" "full gradle core-platform:test"
  assert_eq "$(count_substring "$(tr '\n' ' ' <"$GRADLE_LOG")" ":core-model:test")" "1" "full gradle core-model exactly once"
  assert_eq "$(count_substring "$out" "core-model: 2 tests")" "1" "full core-model summary exactly once"

  # --forced adds cache-bust flags on every Gradle invocation
  : >"$GRADLE_LOG"
  out="$(run_fixture_gate --forced 2>&1)" || status=$?
  status="${status:-0}"
  while read -r line; do
    assert_contains "$line" "--no-build-cache" "forced no-build-cache"
    assert_contains "$line" "--rerun-tasks" "forced rerun-tasks"
  done <"$GRADLE_LOG"
  assert_contains "$out" "QUALITY GATE: PASS (forced mode)" "forced pass banner"

  # Each tool self-test slot must fail closed independently (no PASS banner).
  for slot in gradle-selftest quality-selftest special-access-selftest ui-selftest text-selftest posture-selftest release-kit-selftest thermal-capture-selftest; do
    rm -f "$FIXTURE"/.fail-*-selftest
    : >"$FIXTURE/.fail-$slot"
    out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
    assert_eq "$status" "1" "$slot failure exit"
    assert_not_contains "$out" "QUALITY GATE: PASS" "$slot failure no pass"
    assert_contains "$out" "QUALITY GATE: FAIL" "$slot failure banner"
    rm -f "$FIXTURE/.fail-$slot"
  done

  # UI gate missing is fail-closed once C5 exists in production tree
  make_junit_xml app testDebugUnitTest
  cat >"$FIXTURE/app/build/reports/lint-results-release.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?><issues></issues>
EOF
  rm -f "$FIXTURE/tools/mimir_ui_invariants_gate.py"
  out="$(run_fixture_gate 2>&1)" && status=0 || status=$?
  assert_eq "$status" "1" "missing ui gate exit"
  assert_contains "$out" "UI invariants gate missing" "missing ui gate message"
  assert_not_contains "$out" "QUALITY GATE: PASS" "missing ui gate no pass"

  # Hermetic fixtures cannot detect omitted files in the real exported tool set.
for input in mimir_hardcoded_text_gate.py test_mimir_hardcoded_text_gate.py mimir-hardcoded-text-baseline.tsv; do
  if [ -f "$TOOLS/$input" ]; then
    PASS=$((PASS + 1))
  else
    printf 'FAIL exported quality-gate input missing: %s\n' "$input" >&2
    FAIL=$((FAIL + 1))
  fi
done

printf 'test_mimir_quality_gate.sh: %s passed, %s failed\n' "$PASS" "$FAIL"
  if [ "$FAIL" -ne 0 ]; then
    exit 1
  fi
}

run_tests
