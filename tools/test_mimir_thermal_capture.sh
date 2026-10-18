#!/usr/bin/env bash
# Host self-tests for tools/mimir-thermal-capture.sh.
#
# Runs with NO device attached: a fake adb is placed first on PATH, replays a scripted sample
# sequence, and records every invocation so the read-only posture is asserted over what the tool
# actually ran rather than over what its comments claim. Asserted content is byte-deterministic —
# no timestamps, no wall-clock, no device identity beyond the fixed test serial.

set -euo pipefail

TOOLS="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SOURCE="${TOOLS}/mimir-thermal-capture.sh"
TMP_BASE="$(mktemp -d "${TMPDIR:-/tmp}/mimir-thermal-capture-test.XXXXXX")"
trap 'rm -rf "${TMP_BASE}"' EXIT

PASS=0
FAIL=0

assert_eq() {
  if [[ "$1" == "$2" ]]; then
    PASS=$((PASS + 1))
  else
    printf 'FAIL %s: got [%s] want [%s]\n' "$3" "$1" "$2" >&2
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  if [[ "$1" == *"$2"* ]]; then
    PASS=$((PASS + 1))
  else
    printf 'FAIL %s: missing [%s]\n' "$3" "$2" >&2
    FAIL=$((FAIL + 1))
  fi
}

assert_not_contains() {
  if [[ "$1" == *"$2"* ]]; then
    printf 'FAIL %s: unexpected [%s]\n' "$3" "$2" >&2
    FAIL=$((FAIL + 1))
  else
    PASS=$((PASS + 1))
  fi
}

# Replays one scripted line per invocation. "__NO_SAMPLE__" models a dropped adb round trip. Output
# carries CRLF exactly as adb shell does, so the tool's carriage-return stripping is exercised.
FAKE_ADB="${TMP_BASE}/fake-adb"
cat >"${FAKE_ADB}" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${FAKE_ADB_LOG}"
seen=$(cat "${FAKE_ADB_STATE}/counter" 2>/dev/null || printf 0)
printf '%s' "$((seen + 1))" >"${FAKE_ADB_STATE}/counter"
line=$(sed -n "$((seen + 1))p" "${FAKE_ADB_SAMPLES}")
if [[ -z "${line}" || "${line}" == "__NO_SAMPLE__" ]]; then
  printf 'error: device offline\n' >&2
  exit 1
fi
printf '%s\r\n' "${line}"
FAKE
chmod +x "${FAKE_ADB}"

CASE_DIR=
CASE_STDOUT=
CASE_TSV=
CASE_LOG=

# Writes the scenario's scripted samples from stdin, then runs one capture over them. sleep_s (default
# 0 = no wait) becomes MIMIR_THERMAL_SLEEP_SECONDS for this run; every existing call site omits it and
# keeps getting 0, unchanged. A non-zero sleep_s is used by the rate-reporting scenarios below, which
# need a real (short) interval to exercise the target-cadence/drift arithmetic end to end.
run_capture() {
  local scenario="$1" samples="$2" sleep_s="${3:-0}"
  CASE_DIR="${TMP_BASE}/${scenario}"
  CASE_STDOUT="${CASE_DIR}/stdout.txt"
  CASE_TSV="${CASE_DIR}/capture.tsv"
  CASE_LOG="${CASE_DIR}/adb.log"
  mkdir -p "${CASE_DIR}/bin" "${CASE_DIR}/state"
  cp "${FAKE_ADB}" "${CASE_DIR}/bin/adb"
  cat >"${CASE_DIR}/samples"
  : >"${CASE_LOG}"
  PATH="${CASE_DIR}/bin:${PATH}" \
    FAKE_ADB_LOG="${CASE_LOG}" \
    FAKE_ADB_STATE="${CASE_DIR}/state" \
    FAKE_ADB_SAMPLES="${CASE_DIR}/samples" \
    MIMIR_THERMAL_SLEEP_SECONDS="${sleep_s}" \
    bash "${SOURCE}" --serial test-serial --samples "${samples}" --out "${CASE_TSV}" \
    >"${CASE_STDOUT}" 2>&1
}

# The transition readout with runs of spaces squeezed, so content and ordering are asserted without
# pinning column widths.
transition_block() {
  sed -n '/^\*\*\* sample/,$p' "$1" \
    | grep -v '^mimir-thermal-capture: done' \
    | grep -v '^mimir-thermal-capture: achieved rate' \
    | tr -s ' '
}

# --- source audit ------------------------------------------------------------------------------
audit_out="$(bash "${SOURCE}" --self-test 2>&1)" && audit_status=0 || audit_status=$?
assert_eq "${audit_status}" "0" "self-test exit"
assert_contains "${audit_out}" "self-test PASS" "self-test banner"

# The audit must actually bite. A mutating verb in a live line is rejected even though the same words
# appear harmlessly in the header comments.
# Injected as an assignment so the mutated copy still runs: the audit is what must reject it, not a
# command-not-found further down.
mutated="${TMP_BASE}/mutated-capture.sh"
awk '{ print } /^ADB=/ { print "MUTATION_PROBE='\''settings put system fan_mode 2'\''" }' \
  "${SOURCE}" >"${mutated}"
mutated_out="$(bash "${mutated}" --self-test 2>&1)" && mutated_status=0 || mutated_status=$?
assert_eq "${mutated_status}" "2" "mutated self-test exit"
assert_contains "${mutated_out}" "SELF-TEST FAIL" "mutated self-test banner"

# --- scenario A: a CPU trip, with a dropped read before it ---------------------------------------
# Every real sample line now carries 4 trailing power fields (usb_uA|usb_uV|batt_uA|batt_uV) matching
# the tool's extended PROBE. __NO_SAMPLE__ is a sentinel checked before any pipe-splitting and needs no
# change. Power values are the same on every row here (10.000/-1.950/11.950 W) since scenario A's
# subject is the thermal trip precedence, not power — the dedicated "power" scenario below pins the
# charging/discharging/unreadable arithmetic.
run_capture cpu-trip 5 <<'SAMPLES'
3|305000000|1900800|1900800|72000|68000|380|1|128|1|2000000|5000000|-500000|3900000
__NO_SAMPLE__
3|305000000|1900800|1900800|80000|70000|400|2|128|1|2000000|5000000|-500000|3900000
0|842000000|1900800|2400000|86000|70000|430|2|128|1|2000000|5000000|-500000|3900000
0|842000000|1900800|2400000|84000|69000|425|2|128|1|2000000|5000000|-500000|3900000
SAMPLES

# Built with printf, not a tab-delimited heredoc: every field separator below is the unambiguous
# 2-character escape \t, never a literal tab keystroke that a diff could silently corrupt.
: >"${TMP_BASE}/expected-cpu-trip.tsv"
printf 'sample\tgpu_maxlvl\tgpu_hz\tpol0_khz\tpol6_khz\tcpu_hot_mC\tgpu_hot_mC\tbatt_dC\tthermal_status\tfan_duty\tfan_mode\tusb_uA\tusb_uV\tadapter_w\tbatt_uA\tbatt_uV\tbatt_w\ttrue_w\tevent\n' \
  >>"${TMP_BASE}/expected-cpu-trip.tsv"
printf '0\t3\t305000000\t1900800\t1900800\t72000\t68000\t380\t1\t128\t1\t2000000\t5000000\t10.000\t-500000\t3900000\t-1.950\t11.950\t-\n' \
  >>"${TMP_BASE}/expected-cpu-trip.tsv"
printf '1\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\tNO_SAMPLE\n' \
  >>"${TMP_BASE}/expected-cpu-trip.tsv"
printf '2\t3\t305000000\t1900800\t1900800\t80000\t70000\t400\t2\t128\t1\t2000000\t5000000\t10.000\t-500000\t3900000\t-1.950\t11.950\t-\n' \
  >>"${TMP_BASE}/expected-cpu-trip.tsv"
printf '3\t0\t842000000\t1900800\t2400000\t86000\t70000\t430\t2\t128\t1\t2000000\t5000000\t10.000\t-500000\t3900000\t-1.950\t11.950\tCEILING_CHANGE\n' \
  >>"${TMP_BASE}/expected-cpu-trip.tsv"
printf '4\t0\t842000000\t1900800\t2400000\t84000\t69000\t425\t2\t128\t1\t2000000\t5000000\t10.000\t-500000\t3900000\t-1.950\t11.950\t-\n' \
  >>"${TMP_BASE}/expected-cpu-trip.tsv"

# epoch_ms (D2) is real wall clock, so byte-exact comparison excludes it — cut it off column 1 before
# diffing. Its VALUE is never asserted, matching "no timestamps inside asserted content"; its presence,
# shape and ordering are asserted separately below instead.
cut -f2- "${CASE_TSV}" >"${TMP_BASE}/actual-cpu-trip-no-ts.tsv"
if diff -u "${TMP_BASE}/expected-cpu-trip.tsv" "${TMP_BASE}/actual-cpu-trip-no-ts.tsv" \
  >"${TMP_BASE}/tsv.diff" 2>&1; then
  PASS=$((PASS + 1))
else
  printf 'FAIL cpu-trip TSV mismatch:\n' >&2
  cat "${TMP_BASE}/tsv.diff" >&2
  FAIL=$((FAIL + 1))
fi

header_line="$(sed -n '1p' "${CASE_TSV}")"
assert_eq "$(printf '%s' "${header_line}" | awk -F'\t' '{print NF}')" "20" "cpu-trip header column count"
assert_eq "$(printf '%s' "${header_line}" | cut -f1)" "epoch_ms" "cpu-trip header starts with epoch_ms"

epoch_col="$(cut -f1 "${CASE_TSV}" | tail -n +2)"
epoch_all_13digit="$(printf '%s\n' "${epoch_col}" | \
  awk '{ if ($0 !~ /^[0-9]{13}$/) bad = 1 } END { print (bad == 1) ? "BAD" : "OK" }')"
assert_eq "${epoch_all_13digit}" "OK" "cpu-trip epoch_ms is 13-digit millisecond-scale on every row"
epoch_monotonic="$(printf '%s\n' "${epoch_col}" | \
  awk 'NR > 1 && $0 < prev { bad = 1 } { prev = $0 } END { print (bad == 1) ? "BAD" : "OK" }')"
assert_eq "${epoch_monotonic}" "OK" "cpu-trip epoch_ms is non-decreasing across rows (D2/D3 fix)"

# The readout the ad-hoc script could not produce: its awk program was mangled by nested quoting into
# an expression pattern with no BEGIN block, so it read stdin and printed nothing under a header that
# had already appeared. Every formatted value below is that regression's pin.
cpu_block="$(transition_block "${CASE_STDOUT}")"
read -r -d '' expected_cpu_block <<'BLOCK' || true
*** sample 3 CEILING CHANGE gpu_maxlvl 3->0 pol6 1900800->2400000
 trip inputs at this sample:
 android_thermal_status 2 trip >= 3 (SEVERE) below
 cpu_hottest_zone 86.0 C trip > 85 C (whole, trunc) OVER
 gpu_hottest_zone 70.0 C trip > 85 C (whole, trunc) below
 battery 43.0 C trip > 45.0 C below
 first matching trip in supervisor order: THERMAL_CPU
 (no debounce: the state at the sampled instant, not a confession)
BLOCK
assert_eq "${cpu_block}" "${expected_cpu_block}" "cpu-trip transition readout"

capture_summary="$(grep 'ceiling changes' "${CASE_STDOUT}")"
assert_contains "${capture_summary}" "5 samples, 1 ceiling changes, 1 dropped reads" "capture summary"

# --- D1/D3: cadence is reported, never just asserted ------------------------------------------------
# "supervisor tick is 1 Hz" is a true, grounded fact (HardwareSessionSupervisor.kt) and legitimately
# appears here; what D1 forbids is the tool asserting an unmeasured rate for its OWN capture, which is
# covered below by pinning the exact no-fixed-target wording and requiring a real achieved-rate report.
stdout_all="$(cat "${CASE_STDOUT}")"
assert_contains "${stdout_all}" "target cadence: no fixed wait (sleep=0s)" \
  "cpu-trip target cadence banner names sleep=0 rather than a fixed Hz"
assert_contains "${stdout_all}" "achieved rate" "cpu-trip achieved-rate line present (D1 fix)"
assert_contains "${stdout_all}" "no fixed target" \
  "cpu-trip achieved-rate line names sleep=0 rather than asserting a target (D1 fix)"

# --- read-only posture over the recorded invocations ---------------------------------------------
adb_log="$(cat "${CASE_LOG}")"
assert_contains "${adb_log}" "-s test-serial shell " "invocation shape"
assert_contains "${adb_log}" "settings get system fan_mode" "probe read reached the device"
assert_contains "${adb_log}" "dumpsys thermalservice" "thermal status read"
assert_contains "${adb_log}" "power_supply/usb/current_now" "usb current probe reached the device"
assert_contains "${adb_log}" "power_supply/usb/voltage_now" "usb voltage probe reached the device"
assert_contains "${adb_log}" "power_supply/battery/current_now" "battery current probe reached the device"
assert_contains "${adb_log}" "power_supply/battery/voltage_now" "battery voltage probe reached the device"
for verb in "settings put" "settings delete" "setprop" "appops set" " install " " push " \
  "force-stop" "am start" "am broadcast" "svc " "pm clear"; do
  assert_not_contains "${adb_log}" "${verb}" "read-only: no ${verb}"
done
assert_not_contains "${adb_log}" "> /sys/class/power_supply" "read-only: no write to power_supply"
assert_eq "$(grep -c '^-s test-serial shell ' "${CASE_LOG}")" "5" "exactly one round trip per sample"

# --- scenario B: supervisor precedence, not "whatever is hottest" ---------------------------------
run_capture precedence 2 <<'SAMPLES'
3|305000000|1900800|1900800|72000|68000|380|1|128|1
0|842000000|1900800|2400000|90000|91000|460|3|128|1
SAMPLES
precedence_block="$(transition_block "${CASE_STDOUT}")"
assert_contains "${precedence_block}" "first matching trip in supervisor order: THERMAL_ANDROID" \
  "android wins precedence"
assert_contains "${precedence_block}" "cpu_hottest_zone 90.0 C trip > 85 C (whole, trunc) OVER" \
  "cpu still reported over"
assert_contains "${precedence_block}" "gpu_hottest_zone 91.0 C trip > 85 C (whole, trunc) OVER" \
  "gpu still reported over"
assert_contains "${precedence_block}" "battery 46.0 C trip > 45.0 C OVER" "battery still reported over"

# --- scenario C: truncation and exclusivity decide the close calls --------------------------------
# 85.999 C truncates to 85 and 85 > 85 is false; 450 deci-C is not > 450. A rounding or inclusive
# implementation would claim a trip here that the supervisor would never have fired.
run_capture truncation 2 <<'SAMPLES'
3|305000000|1900800|1900800|72000|68000|380|2|128|1
0|842000000|1900800|2400000|85999|85000|450|2|128|1
SAMPLES
truncation_block="$(transition_block "${CASE_STDOUT}")"
assert_contains "${truncation_block}" "cpu_hottest_zone 86.0 C trip > 85 C (whole, trunc) below" \
  "85.999 C truncates below trip"
assert_contains "${truncation_block}" "battery 45.0 C trip > 45.0 C below" "450 deci-C is not over"
assert_contains "${truncation_block}" "no trip input was over threshold at this sample" \
  "close calls report no trip"
assert_not_contains "${truncation_block}" "first matching trip" "no trip named on a close call"

# --- scenario D: an out-of-range reading cannot trip ----------------------------------------------
run_capture invalid-range 2 <<'SAMPLES'
3|305000000|1900800|1900800|72000|68000|380|2|128|1
0|842000000|1900800|2400000|200000|68000|380|2|128|1
SAMPLES
invalid_block="$(transition_block "${CASE_STDOUT}")"
assert_contains "${invalid_block}" "cpu_hottest_zone 200.0 C trip > 85 C (whole, trunc) INVALID" \
  "out-of-range cpu is INVALID"
assert_contains "${invalid_block}" "no trip input was over threshold at this sample" \
  "INVALID never trips"

# --- scenario E: an unreadable field is reported, not guessed -------------------------------------
run_capture unreadable 2 <<'SAMPLES'
3|305000000|1900800|1900800|72000|68000|380|2|128|1
0|842000000|1900800|2400000|||380||128|1
SAMPLES
unreadable_block="$(transition_block "${CASE_STDOUT}")"
assert_contains "${unreadable_block}" "cpu_hottest_zone unreadable trip > 85 C (whole, trunc) UNREADABLE" \
  "missing cpu reads UNREADABLE"
assert_contains "${unreadable_block}" "android_thermal_status unreadable trip >= 3 (SEVERE) UNREADABLE" \
  "missing status reads UNREADABLE"
assert_contains "${unreadable_block}" "no trip input was over threshold at this sample" \
  "UNREADABLE never trips"

# --- scenario F: power delivery — charging vs. discharging, and true_w vs. adapter_w --------------
# true_w = adapter_w - batt_w, not adapter_w alone: row 1 is the adapter comfortably covering load
# plus topping up the battery (batt_w positive/charging, true_w < adapter_w); row 2 is the adapter
# saturated under load with the battery supplementing it (batt_w negative/discharging, true_w >
# adapter_w — the exact effect that made adapter-only logging understate real consumption on
# hardware). Row 3 has the power nodes unreadable while thermal stays normal, so every power column
# must render "-", never a silent 0.000 that would look like a real zero-power reading.
run_capture power 3 <<'SAMPLES'
3|305000000|1900800|1900800|72000|68000|380|1|128|1|1800000|5000000|300000|4000000
3|305000000|1900800|1900800|72000|68000|380|1|128|1|2500000|5000000|-1200000|3900000
3|305000000|1900800|1900800|72000|68000|380|1|128|1||||
SAMPLES

power_row1="$(sed -n '2p' "${CASE_TSV}")"
assert_eq "$(printf '%s' "${power_row1}" | cut -f13)" "1800000" "power row1 usb_uA passthrough"
assert_eq "$(printf '%s' "${power_row1}" | cut -f14)" "5000000" "power row1 usb_uV passthrough"
assert_eq "$(printf '%s' "${power_row1}" | cut -f15)" "9.000" "power row1 adapter_w = 1.8A * 5.0V"
assert_eq "$(printf '%s' "${power_row1}" | cut -f16)" "300000" "power row1 batt_uA passthrough"
assert_eq "$(printf '%s' "${power_row1}" | cut -f17)" "4000000" "power row1 batt_uV passthrough"
assert_eq "$(printf '%s' "${power_row1}" | cut -f18)" "1.200" "power row1 batt_w charging (positive)"
assert_eq "$(printf '%s' "${power_row1}" | cut -f19)" "7.800" "power row1 true_w = adapter_w - batt_w"

power_row2="$(sed -n '3p' "${CASE_TSV}")"
assert_eq "$(printf '%s' "${power_row2}" | cut -f15)" "12.500" "power row2 adapter_w saturating at the wall"
assert_eq "$(printf '%s' "${power_row2}" | cut -f18)" "-4.680" "power row2 batt_w discharging (negative)"
assert_eq "$(printf '%s' "${power_row2}" | cut -f19)" "17.180" \
  "power row2 true_w exceeds adapter_w while the battery supplements it"

power_row3="$(sed -n '4p' "${CASE_TSV}")"
for col in 13 14 15 16 17 18 19; do
  assert_eq "$(printf '%s' "${power_row3}" | cut -f"${col}")" "-" \
    "power row3 col ${col} unreadable renders dash, not 0.000"
done

# --- scenario G: rate self-reporting with a real (short) sleep so the achieved-rate line's full
# structure is exercised end to end. The TARGET is deterministic (derived from the sleep argument, not
# from real elapsed time) and is what gets pinned; the ACHIEVED number is real wall-clock and is
# deliberately left unpinned, exactly like epoch_ms — 0.125s is an exact power-of-two fraction so
# 1/0.125 is exactly representable and "8.000"/"8.00" never depend on floating-point rounding.
run_capture rate-report 4 0.125 <<'SAMPLES'
3|305000000|1900800|1900800|72000|68000|380|1|128|1
3|305000000|1900800|1900800|72000|68000|380|1|128|1
3|305000000|1900800|1900800|72000|68000|380|1|128|1
3|305000000|1900800|1900800|72000|68000|380|1|128|1
SAMPLES
rate_stdout="$(cat "${CASE_STDOUT}")"
assert_contains "${rate_stdout}" \
  "target cadence: 8.000 Hz (sleep=0.125s, 8.00x the 1 Hz supervisor tick)" \
  "rate-report target cadence banner names an explicit multiple (D3 fix)"
assert_contains "${rate_stdout}" "achieved rate" "rate-report achieved-rate line present"
assert_contains "${rate_stdout}" "target 8.000 Hz" "rate-report echoes its own target in the summary"
assert_contains "${rate_stdout}" "drift" "rate-report states drift rather than hiding it"

# --- scenario H: the DEFAULT cadence (no MIMIR_THERMAL_SLEEP_SECONDS override at all) really is 4x
# oversampling of the 1 Hz supervisor tick — not just that an explicit override is honored above.
default_dir="${TMP_BASE}/default-cadence"
mkdir -p "${default_dir}/bin" "${default_dir}/state"
cp "${FAKE_ADB}" "${default_dir}/bin/adb"
printf '3|305000000|1900800|1900800|72000|68000|380|1|128|1\n3|305000000|1900800|1900800|72000|68000|380|1|128|1\n' \
  >"${default_dir}/samples"
: >"${default_dir}/adb.log"
PATH="${default_dir}/bin:${PATH}" \
  FAKE_ADB_LOG="${default_dir}/adb.log" \
  FAKE_ADB_STATE="${default_dir}/state" \
  FAKE_ADB_SAMPLES="${default_dir}/samples" \
  bash "${SOURCE}" --serial test-serial --samples 2 --out "${default_dir}/capture.tsv" \
  >"${default_dir}/stdout.txt" 2>&1
default_out="$(cat "${default_dir}/stdout.txt")"
assert_contains "${default_out}" \
  "target cadence: 4.000 Hz (sleep=0.25s, 4.00x the 1 Hz supervisor tick)" \
  "default cadence really is 4x oversampling with zero env override (D3 fix)"

# --- refusals ------------------------------------------------------------------------------------
refusal_dir="${TMP_BASE}/refusals"
mkdir -p "${refusal_dir}/bin" "${refusal_dir}/state"
cp "${FAKE_ADB}" "${refusal_dir}/bin/adb"
: >"${refusal_dir}/adb.log"
: >"${refusal_dir}/samples"

refuse() {
  local label="$1"
  shift
  local out status
  out="$(PATH="${refusal_dir}/bin:${PATH}" \
    FAKE_ADB_LOG="${refusal_dir}/adb.log" \
    FAKE_ADB_STATE="${refusal_dir}/state" \
    FAKE_ADB_SAMPLES="${refusal_dir}/samples" \
    MIMIR_THERMAL_SLEEP_SECONDS=0 \
    bash "${SOURCE}" "$@" 2>&1)" && status=0 || status=$?
  printf '%s\n' "${status}|${out}"
  assert_eq "${status}" "2" "${label} exit"
}

for flag in --fan-mode --set-profile --apply --governor --max-pwrlevel --write --disable-thermal; do
  result="$(refuse "mutating ${flag}" --serial test-serial "${flag}" value)"
  assert_contains "${result}" "strictly read-only" "mutating ${flag} message"
done

result="$(refuse "unknown flag" --serial test-serial --bogus)"
assert_contains "${result}" "unknown argument: --bogus" "unknown flag message"

result="$(refuse "missing serial" --samples 2)"
assert_contains "${result}" "--serial is required" "missing serial message"

result="$(refuse "bad samples" --serial test-serial --samples zero)"
assert_contains "${result}" "--samples must be a positive integer" "bad samples message"

result="$(refuse "bad serial" --serial 'evil;serial' --samples 1)"
assert_contains "${result}" "serial contains unsupported characters" "bad serial message"

printf 'existing\n' >"${refusal_dir}/taken.tsv"
result="$(refuse "existing out" --serial test-serial --samples 1 --out "${refusal_dir}/taken.tsv")"
assert_contains "${result}" "output already exists" "existing out message"
assert_eq "$(cat "${refusal_dir}/taken.tsv")" "existing" "existing out untouched"

# No refusal may have touched the device.
assert_eq "$(wc -c <"${refusal_dir}/adb.log" | tr -d ' ')" "0" "refusals never invoke adb"

# --- ADB_BIN seam ---------------------------------------------------------------------------------
# PATH resolution is the default; ADB_BIN must override it. A PATH adb that would fail the run proves
# the override is the one that ran.
adbbin_dir="${TMP_BASE}/adbbin"
mkdir -p "${adbbin_dir}/bin" "${adbbin_dir}/state"
cat >"${adbbin_dir}/bin/adb" <<'POISON'
#!/usr/bin/env bash
printf 'PATH adb must not be used when ADB_BIN is set\n' >&2
exit 3
POISON
chmod +x "${adbbin_dir}/bin/adb"
printf '3|305000000|1900800|1900800|72000|68000|380|1|128|1\n' >"${adbbin_dir}/samples"
: >"${adbbin_dir}/adb.log"
PATH="${adbbin_dir}/bin:${PATH}" \
  ADB_BIN="${FAKE_ADB}" \
  FAKE_ADB_LOG="${adbbin_dir}/adb.log" \
  FAKE_ADB_STATE="${adbbin_dir}/state" \
  FAKE_ADB_SAMPLES="${adbbin_dir}/samples" \
  MIMIR_THERMAL_SLEEP_SECONDS=0 \
  bash "${SOURCE}" --serial test-serial --samples 1 --out "${adbbin_dir}/capture.tsv" >/dev/null 2>&1
assert_eq "$(sed -n '2p' "${adbbin_dir}/capture.tsv" | cut -f3)" "3" "ADB_BIN override sampled"
assert_eq "$(grep -c '^-s test-serial shell ' "${adbbin_dir}/adb.log")" "1" "ADB_BIN override logged"

printf 'test_mimir_thermal_capture.sh: %s passed, %s failed\n' "${PASS}" "${FAIL}"
if [[ "${FAIL}" -ne 0 ]]; then
  exit 1
fi
