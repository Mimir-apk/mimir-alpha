#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
if [[ -z "${SERIAL}" ]]; then
  printf 'Specify a device target as the first argument or ANDROID_SERIAL.\n' >&2
  exit 1
fi
PKG="${MIMIR_PACKAGE:-com.gruning.mimir.rewrite}"
APK="${MIMIR_APK:-app/build/outputs/apk/debug/app-debug.apk}"
STAMP="$(date +%Y-%m-%d_SPECIAL-ACCESS-CERT)"
ROOT="${MIMIR_EVIDENCE_ROOT:-build/reports/special-access/${STAMP}}"
CERT_SLEEP_SECONDS="${MIMIR_CERT_SLEEP_SECONDS:-2}"

mkdir -p "${ROOT}"

capture_appop() {
  operation="$1"
  destination="$2"
  if ! "${ADB_BIN}" -s "${SERIAL}" shell appops get "${PKG}" "${operation}" \
    >"${destination}" 2>&1; then
    printf 'appops query failed for %s\n' "${operation}" >>"${destination}"
  fi
}

appop_is_allowed() {
  operation="$1"
  source="$2"
  grep -Eq "^${operation}:[[:space:]]+allow([;[:space:]]|$)" "${source}"
}

pause_for_surface() {
  if [[ "${CERT_SLEEP_SECONDS}" != "0" ]]; then
    sleep "${CERT_SLEEP_SECONDS}"
  fi
}

capture_appop SYSTEM_ALERT_WINDOW "${ROOT}/00_appops_overlay_preinstall.txt"
capture_appop GET_USAGE_STATS "${ROOT}/00_appops_usage_preinstall.txt"
overlay_was_allowed=0
if appop_is_allowed SYSTEM_ALERT_WINDOW "${ROOT}/00_appops_overlay_preinstall.txt"; then
  overlay_was_allowed=1
fi

"${ADB_BIN}" -s "${SERIAL}" install -r "${APK}"
capture_appop SYSTEM_ALERT_WINDOW "${ROOT}/02_appops_overlay_postinstall.txt"
capture_appop GET_USAGE_STATS "${ROOT}/03_appops_usage_postinstall.txt"

if [[ "${overlay_was_allowed}" -eq 1 ]] &&
  ! appop_is_allowed SYSTEM_ALERT_WINDOW "${ROOT}/02_appops_overlay_postinstall.txt"; then
  "${ADB_BIN}" -s "${SERIAL}" shell appops set \
    "${PKG}" SYSTEM_ALERT_WINDOW allow
  capture_appop SYSTEM_ALERT_WINDOW "${ROOT}/02a_appops_overlay_restored.txt"
  if ! appop_is_allowed SYSTEM_ALERT_WINDOW "${ROOT}/02a_appops_overlay_restored.txt"; then
    printf 'FAILED: install changed a previously allowed overlay app-op and restoration did not verify.\n' \
      >"${ROOT}/02b_overlay_reconciliation.txt"
    exit 1
  fi
  printf 'RESTORED: install changed a previously allowed overlay app-op; exact readback is allow.\n' \
    >"${ROOT}/02b_overlay_reconciliation.txt"
else
  printf 'PRESERVED: gate did not elevate overlay access; pre-install grant state was retained.\n' \
    >"${ROOT}/02b_overlay_reconciliation.txt"
fi

# PERF-A: debug installs get zero dexopt; verify restores release-representative cold starts.
"${ADB_BIN}" -s "${SERIAL}" shell cmd package compile -m verify -f "${PKG}"
"${ADB_BIN}" -s "${SERIAL}" shell am force-stop "${PKG}"
"${ADB_BIN}" -s "${SERIAL}" shell monkey -p "${PKG}" 1 >/dev/null
pause_for_surface
"${ADB_BIN}" -s "${SERIAL}" exec-out screencap -p > "${ROOT}/01_mimir_after_install.png"

"${ADB_BIN}" -s "${SERIAL}" shell am start \
  -a android.settings.action.MANAGE_OVERLAY_PERMISSION \
  -d "package:${PKG}" >/dev/null || true
pause_for_surface
"${ADB_BIN}" -s "${SERIAL}" exec-out screencap -p > "${ROOT}/04_android_overlay_access_screen.png"

"${ADB_BIN}" -s "${SERIAL}" shell am force-stop com.android.settings >/dev/null 2>&1 || true
"${ADB_BIN}" -s "${SERIAL}" shell am start \
  -a android.settings.USAGE_ACCESS_SETTINGS >/dev/null || true
pause_for_surface
"${ADB_BIN}" -s "${SERIAL}" exec-out screencap -p > "${ROOT}/05_android_usage_access_attempt.png"

cat <<EOF
Special-access cert evidence written to:
${ROOT}

Captured:
- 00_appops_overlay_preinstall.txt
- 00_appops_usage_preinstall.txt
- 01_mimir_after_install.png
- 02_appops_overlay_postinstall.txt
- 02a_appops_overlay_restored.txt (only when the install flipped a prior grant)
- 02b_overlay_reconciliation.txt
- 03_appops_usage_postinstall.txt
- 04_android_overlay_access_screen.png
- 05_android_usage_access_attempt.png

The gate restores overlay access only when its own install changed a previously allowed
state. It never elevates a state that was not allowed before the install.

Manual cert still required: check Draw over other apps and Usage access in Android
Settings, then relaunch Mimir and capture the user-facing native-access surface.
EOF
