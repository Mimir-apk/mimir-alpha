#!/usr/bin/env bash
set -euo pipefail

TOOLS="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SOURCE="${TOOLS}/cert-special-access.sh"
TMP_BASE="$(mktemp -d "${TMPDIR:-/tmp}/mimir-special-access-test.XXXXXX")"
trap 'rm -rf "${TMP_BASE}"' EXIT

PASS=0
FAIL=0

assert_contains() {
  haystack="$1"
  needle="$2"
  label="$3"
  if [[ "${haystack}" == *"${needle}"* ]]; then
    PASS=$((PASS + 1))
  else
    printf 'FAIL %s: missing [%s]\n' "${label}" "${needle}" >&2
    FAIL=$((FAIL + 1))
  fi
}

assert_not_contains() {
  haystack="$1"
  needle="$2"
  label="$3"
  if [[ "${haystack}" == *"${needle}"* ]]; then
    printf 'FAIL %s: unexpected [%s]\n' "${label}" "${needle}" >&2
    FAIL=$((FAIL + 1))
  else
    PASS=$((PASS + 1))
  fi
}

MOCK_ADB="${TMP_BASE}/adb"
cat >"${MOCK_ADB}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${MOCK_STATE}/commands.log"
if [[ "$*" == *" install -r "* ]]; then
  : >"${MOCK_STATE}/installed"
  exit 0
fi
if [[ "$*" == *" shell appops set "*" SYSTEM_ALERT_WINDOW allow" ]]; then
  : >"${MOCK_STATE}/restored"
  exit 0
fi
if [[ "$*" == *" shell appops get "*" SYSTEM_ALERT_WINDOW" ]]; then
  if [[ ! -f "${MOCK_STATE}/installed" ]]; then
    printf 'SYSTEM_ALERT_WINDOW: %s\n' "${MOCK_OVERLAY_BEFORE}"
  elif [[ -f "${MOCK_STATE}/restored" ]]; then
    printf 'SYSTEM_ALERT_WINDOW: allow\n'
  else
    printf 'SYSTEM_ALERT_WINDOW: %s\n' "${MOCK_OVERLAY_AFTER}"
  fi
  exit 0
fi
if [[ "$*" == *" shell appops get "*" GET_USAGE_STATS" ]]; then
  printf 'GET_USAGE_STATS: allow\n'
  exit 0
fi
if [[ "$*" == *" exec-out screencap -p" ]]; then
  printf 'PNG'
  exit 0
fi
exit 0
EOF
chmod +x "${MOCK_ADB}"

run_case() {
  name="$1"
  before="$2"
  after="$3"
  target_source="${4:-argument}"
  CASE_ROOT="${TMP_BASE}/${name}"
  EVIDENCE="${CASE_ROOT}/evidence"
  mkdir -p "${CASE_ROOT}"
  : >"${CASE_ROOT}/commands.log"
  if [[ "${target_source}" == env ]]; then
    set --
  else
    set -- test-serial
  fi
  MOCK_STATE="${CASE_ROOT}" \
    MOCK_OVERLAY_BEFORE="${before}" \
    MOCK_OVERLAY_AFTER="${after}" \
    ADB_BIN="${MOCK_ADB}" \
    MIMIR_EVIDENCE_ROOT="${EVIDENCE}" \
    MIMIR_CERT_SLEEP_SECONDS=0 \
    MIMIR_APK="${CASE_ROOT}/candidate.apk" \
    ANDROID_SERIAL=env-test-serial \
    bash "${SOURCE}" "$@" >/dev/null
}

missing_root="${TMP_BASE}/missing"
mkdir -p "${missing_root}"
if missing_output="$(ANDROID_SERIAL= ADB_BIN="${MOCK_ADB}" MOCK_STATE="${missing_root}" \
  MIMIR_EVIDENCE_ROOT="${missing_root}/evidence" bash "${SOURCE}" 2>&1)"; then
  printf 'FAIL missing target: command succeeded\n' >&2
  FAIL=$((FAIL + 1))
else
  PASS=$((PASS + 1))
fi
assert_contains "${missing_output}" "first argument or ANDROID_SERIAL" "missing target guidance"
for unexpected in commands.log evidence; do
  if [[ -e "${missing_root}/${unexpected}" ]]; then
    printf 'FAIL missing target: created %s\n' "${unexpected}" >&2
    FAIL=$((FAIL + 1))
  else
    PASS=$((PASS + 1))
  fi
done

run_case flipped allow ignore
flipped_log="$(<"${TMP_BASE}/flipped/commands.log")"
flipped_decision="$(<"${TMP_BASE}/flipped/evidence/02b_overlay_reconciliation.txt")"
flipped_final="$(<"${TMP_BASE}/flipped/evidence/02a_appops_overlay_restored.txt")"
assert_contains "${flipped_log}" \
  "shell appops get com.gruning.mimir.rewrite SYSTEM_ALERT_WINDOW" \
  "exact overlay query"
assert_contains "${flipped_log}" \
  "shell appops set com.gruning.mimir.rewrite SYSTEM_ALERT_WINDOW allow" \
  "restore flipped grant"
assert_contains "${flipped_decision}" "RESTORED" "restored decision"
assert_contains "${flipped_final}" "SYSTEM_ALERT_WINDOW: allow" "restored readback"
assert_contains "${flipped_log}" "-s test-serial install -r" "argument target install"
assert_contains "${flipped_log}" "-s test-serial shell cmd package compile -m verify -f" "verify compilation preserved"
assert_contains "${flipped_log}" "-s test-serial exec-out screencap -p" "screenshots preserved"

run_case not_granted ignore ignore
not_granted_log="$(<"${TMP_BASE}/not_granted/commands.log")"
not_granted_decision="$(<"${TMP_BASE}/not_granted/evidence/02b_overlay_reconciliation.txt")"
assert_not_contains "${not_granted_log}" \
  "shell appops set com.gruning.mimir.rewrite SYSTEM_ALERT_WINDOW allow" \
  "do not elevate absent grant"
assert_contains "${not_granted_decision}" "PRESERVED" "preserved decision"

run_case env_target ignore ignore env
env_log="$(<"${TMP_BASE}/env_target/commands.log")"
assert_contains "${env_log}" "-s env-test-serial install -r" "environment target install"
assert_not_contains "${env_log}" "-s test-serial " "environment target used for every call"

printf 'test_cert_special_access.sh: %s passed, %s failed\n' "${PASS}" "${FAIL}"
if [[ "${FAIL}" -ne 0 ]]; then
  exit 1
fi
