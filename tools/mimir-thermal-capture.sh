#!/usr/bin/env bash
#
# mimir-thermal-capture.sh — read-only ceiling capture that discriminates which Guardian trip fired.
#
# PURPOSE
#   Mimir Tweaker's HardwareSessionSupervisor releases the performance ceilings it applied as soon as
#   any one of four trips fires. Production compiles the restore-reason log out, so on a production
#   build there is no way to read WHICH trip fired. This tool answers that from the outside: it samples
#   every trip INPUT, catches the sample where a ceiling releases, and prints each input against its
#   own threshold at that moment. No DIAGNOSTICS build required.
#
#   Cadence: the supervisor itself ticks at a fixed 1.000 Hz (HardwareSessionSupervisor.kt:
#   sampleIntervalMs = 1_000L). Sampling at an arbitrary rate near 1 Hz aliases against that tick and
#   makes its 3-consecutive-sample streak unrecoverable after the fact, so this tool defaults to
#   oversampling the tick by an explicit multiple (OVERSAMPLE_MULTIPLE below; 4x by default, i.e. a
#   ~4 Hz target) instead of trying to match it 1:1. The achieved rate is never assumed — every run
#   measures its own actual elapsed time and reports what it got, including drift from the target; see
#   "target cadence" / "achieved rate" in the runtime output.
#
#   The four trips — mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/
#   HardwareSessionSupervisor.kt:14-17, evaluated in presentThermalTrip() at :849 —
#
#       androidThermalTrip        = THERMAL_STATUS_SEVERE (3)   status >= 3    -> THERMAL_ANDROID
#       cpuTripCExclusive         = 85                          whole C >  85  -> THERMAL_CPU
#       gpuTripCExclusive         = 85                          whole C >  85  -> THERMAL_GPU
#       batteryTripDeciCExclusive = 450 (45.0 C)                deci-C  > 450  -> THERMAL_BATTERY
#
#   presentThermalTrip() returns the FIRST match in that order, so this tool reports the same
#   precedence rather than naming whatever happens to be hottest. Two details decide close calls and
#   are reproduced exactly here:
#     - CPU/GPU compare as WHOLE degrees. LivePerformanceTelemetry.normalizedThermalCelsius divides a
#       raw reading above 1000 by 1000.0 and .toInt() TRUNCATES, so 85.9 C is 85 and does NOT trip.
#     - A reading outside the supervisor's validity window cannot trip at all; it is labelled INVALID
#       here. TelemetryRanges: cpu/gpu 0..120 C, battery 0..800 deci-C, android status 0..6, and a
#       thermal zone whose normalized value falls outside 1.0..150.0 C is dropped before any of that.
#   Hottest-zone families follow classifiedThermalZoneWinners(): "cpu" for CPU; "gpu", "kgsl" or "gfx"
#   for GPU.
#
# LIMITS — read these before quoting this tool as proof
#   The trip reads the INSTANTANEOUS hottest zone and has NO debounce, at any cadence. A capture can
#   still miss a spike that fired and receded between samples. A transition readout therefore names the
#   first input that was over trip AT THE SAMPLED INSTANT — that is evidence, not a confession. A
#   transition with nothing over trip is a real and informative result: the ceiling moved for some
#   other reason.
#
#   The requested cadence is a target, not a guarantee: each sample is one adb round trip plus a host
#   read of the wall clock, and both vary with host load. Never trust the header or the --samples count
#   for timing — trust the epoch_ms column and the "achieved rate" line this tool prints from its own
#   measured elapsed time. If the achieved rate drifts from the target, that drift is reported, not
#   hidden.
#
# POSTURE — strictly read-only
#   Every device interaction is a read: cat of sysfs (including the usb/battery power_supply nodes),
#   dumpsys, settings get. Nothing is written, toggled, installed, launched or stopped; no profile and
#   no property is changed. The epoch_ms timestamp and the achieved-rate report are read from the HOST
#   clock, not the device, and touch no device state at all. --self-test audits this script's own
#   source for mutating verbs, the host suite asserts the same over the recorded adb command log, and
#   flags that would change device state are refused by name.
#
# USAGE
#   tools/mimir-thermal-capture.sh --serial SERIAL [--samples N] [--out FILE]
#   tools/mimir-thermal-capture.sh --self-test
#
#   --samples N   samples to take (default 600). Default cadence targets OVERSAMPLE_MULTIPLE (4x) the
#                 supervisor's 1 Hz tick, i.e. ~4 Hz => ~150s target duration at the default count —
#                 adb round-trip latency usually pulls the achieved rate below that, which this tool
#                 measures and reports at the end rather than assuming. Override the target interval
#                 with MIMIR_THERMAL_SLEEP_SECONDS in seconds (0 = no wait, sample as fast as possible).
#   ADB_BIN       adb to invoke (default: adb from PATH).
#   python3 is required on the HOST for millisecond epoch timestamps — BSD/macOS date(1) has no
#   sub-second resolution and this tool must never silently downgrade epoch_ms to whole seconds.

set -euo pipefail

TOOL=mimir-thermal-capture
ADB="${ADB_BIN:-adb}"

# The Guardian's HardwareSessionSupervisor ticks at a fixed, documented rate (sampleIntervalMs =
# 1_000L in HardwareSessionSupervisor.kt). Sampling at an arbitrary rate near that tick aliases against
# it and makes a 3-consecutive-sample streak unrecoverable after the fact, so the default cadence here
# oversamples the tick by an explicit multiple instead of trying to match it 1:1. Both constants are
# named so the relationship stays auditable rather than a magic 0.25 buried in an env-var default.
SUPERVISOR_TICK_SECONDS=1
OVERSAMPLE_MULTIPLE=4
DEFAULT_SLEEP_SECONDS=$(awk -v tick="$SUPERVISOR_TICK_SECONDS" -v mult="$OVERSAMPLE_MULTIPLE" \
  'BEGIN { printf "%.6g", tick / mult }')
SLEEP_SECONDS="${MIMIR_THERMAL_SLEEP_SECONDS:-$DEFAULT_SLEEP_SECONDS}"

# Trip constants mirrored from SupervisorPolicy and TelemetryRanges, kept as raw integers so every
# comparison below happens in the same units the supervisor uses.
ANDROID_TRIP=3
ANDROID_MIN=0
ANDROID_MAX=6
CPU_TRIP_C=85
CPU_MIN_C=0
CPU_MAX_C=120
GPU_TRIP_C=85
GPU_MIN_C=0
GPU_MAX_C=120
BATTERY_TRIP_DECI_C=450
BATTERY_MIN_DECI_C=0
BATTERY_MAX_DECI_C=800

usage() {
  cat <<'EOF'
Usage: tools/mimir-thermal-capture.sh --serial SERIAL [--samples N] [--out FILE]
       tools/mimir-thermal-capture.sh --self-test

Read-only capture of every Guardian trip input plus USB/battery power delivery. Flags the
sample where a ceiling releases and names the first trip that was over threshold at that
instant. Default cadence oversamples the supervisor's 1 Hz tick 4x (~4 Hz target); the
achieved rate is measured from actual elapsed time and reported at the end, never assumed.

  --serial SERIAL  device to read (required; naming it prevents capturing the wrong handheld)
  --samples N      samples to take, default 600 (~150s at the default ~4 Hz target cadence)
  --out FILE       TSV destination, default ./mimir-thermal-<serial>-<utc>.tsv
  --self-test      audit this script for device-mutating verbs and exit

MIMIR_THERMAL_SLEEP_SECONDS overrides the target interval in seconds (0 = no wait).
This tool never changes device state. Flags that would are refused by name.
EOF
}

fail() {
  printf '%s: %s\n' "$TOOL" "$*" >&2
  exit 2
}

# --- read-only source audit --------------------------------------------------------------------
# Mirrors the fake-ADB probe harnesses: the runner proves its own posture
# from its own text, so a mutating verb cannot be added without a gate noticing. Comment lines and
# this definition are excluded so the documentation above may name what the tool refuses to do.
self_test() {
  forbidden='settings[[:space:]]+(put|delete)|setprop|appops[[:space:]]+set|(^|[[:space:]])(install|uninstall|push|remount|reboot|disable-verity)([[:space:]]|$)|force-stop|(^|[[:space:]])pm[[:space:]]+(clear|grant|revoke|compile)|(^|[[:space:]])am[[:space:]]+(start|broadcast|startservice)|(^|[[:space:]])svc[[:space:]]|(>>?|tee)[[:space:]]*/sys/|(^|[[:space:]])(su|monkey)([[:space:]]|$)'
  if grep -Ev '^[[:space:]]*#|forbidden=' "$0" | grep -Eqi -- "$forbidden"; then
    printf '%s: SELF-TEST FAIL — a device-mutating verb is present in this script\n' "$TOOL" >&2
    exit 2
  fi
  printf '%s: self-test PASS (read-only source audit)\n' "$TOOL"
}

# Refused by name. This capture is diagnostic: it must never be the thing that changed the
# measurement. A flag that would write a profile, governor, fan curve or property is not a missing
# feature here — it is out of scope by design.
is_mutating_flag() {
  case "$1" in
    --fan-mode|--fan-duty|--set-fan|--profile|--set-profile|--apply|--restore|--reset|--governor|--max-pwrlevel|--set|--write|--clear|--stop-guardian|--disable-thermal)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

SERIAL=
SAMPLES=600
OUT=

while [ "$#" -gt 0 ]; do
  case "$1" in
    --self-test)
      [ "$#" -eq 1 ] || fail "--self-test takes no other arguments"
      self_test
      exit 0
      ;;
    --serial)
      [ "$#" -ge 2 ] || fail "--serial requires a device serial"
      SERIAL=$2
      shift 2
      ;;
    --samples)
      [ "$#" -ge 2 ] || fail "--samples requires a count"
      SAMPLES=$2
      shift 2
      ;;
    --out)
      [ "$#" -ge 2 ] || fail "--out requires a path"
      OUT=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if is_mutating_flag "$1"; then
        fail "refusing $1: this tool is strictly read-only and never changes device state"
      fi
      fail "unknown argument: $1"
      ;;
  esac
done

[ -n "$SERIAL" ] || fail "--serial is required"
case "$SERIAL" in
  *[!A-Za-z0-9._:-]*) fail "serial contains unsupported characters" ;;
esac
case "$SAMPLES" in
  ""|*[!0-9]*) fail "--samples must be a positive integer" ;;
esac
[ "$SAMPLES" -gt 0 ] || fail "--samples must be a positive integer"
case "$SLEEP_SECONDS" in
  ""|*[!0-9.]*) fail "MIMIR_THERMAL_SLEEP_SECONDS must be a non-negative number" ;;
esac
if [ -z "$OUT" ]; then
  OUT="./mimir-thermal-$SERIAL-$(date -u +%Y%m%dT%H%M%SZ).tsv"
fi
[ ! -e "$OUT" ] || fail "output already exists: $OUT"

# One adb round trip per sample: the whole probe runs inside a single device shell, so the readings
# belong to the same instant as closely as the transport allows. Every line of it is a read.
#
# Read into the variable rather than captured through $( ): macOS ships bash 3.2, whose parser cannot
# handle a here-document inside a command substitution. `read -d ''` stops at EOF and returns 1, which
# is expected here, so its status is discarded.
PROBE=
read -r -d '' PROBE <<'PROBE_BODY' || true
gm=$(cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null)
gz=$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null)
p0=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null)
p6=$(cat /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null)
ch=""
gh=""
for z in /sys/class/thermal/thermal_zone*; do
  t=$(cat "$z/type" 2>/dev/null)
  v=$(cat "$z/temp" 2>/dev/null)
  case "$v" in ''|*[!0-9]*) continue ;; esac
  # normalizedThermalCelsius(): a raw reading above 1000 is milli-C, otherwise it is already C.
  if [ "$v" -gt 1000 ]; then m=$v; else m=$((v * 1000)); fi
  # Zones outside 1.0..150.0 C are dropped before the supervisor ever sees them.
  [ "$m" -ge 1000 ] && [ "$m" -le 150000 ] || continue
  case "$t" in
    *[Cc][Pp][Uu]*) [ -z "$ch" ] || [ "$m" -gt "$ch" ] && ch=$m ;;
  esac
  case "$t" in
    *[Gg][Pp][Uu]*|*[Kk][Gg][Ss][Ll]*|*[Gg][Ff][Xx]*) [ -z "$gh" ] || [ "$m" -gt "$gh" ] && gh=$m ;;
  esac
done
bt=$(dumpsys battery 2>/dev/null | sed -n 's/.*temperature: //p' | head -1)
ts=$(dumpsys thermalservice 2>/dev/null | sed -n 's/.*Thermal Status: //p' | head -1)
fd=$(cat /sys/class/gpio5_pwm2/duty 2>/dev/null)
fm=$(settings get system fan_mode 2>/dev/null)
# Power delivery: the adapter is current-limited and saturates under sustained load, at which point
# the battery supplements it (discharges) rather than the adapter exceeding its own ceiling. Reading
# both is what makes true device consumption (see compute_true_watts below) recoverable at all.
ui=$(cat /sys/class/power_supply/usb/current_now 2>/dev/null)
uv=$(cat /sys/class/power_supply/usb/voltage_now 2>/dev/null)
bi=$(cat /sys/class/power_supply/battery/current_now 2>/dev/null)
bv=$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null)
echo "$gm|$gz|$p0|$p6|$ch|$gh|$bt|$ts|$fd|$fm|$ui|$uv|$bi|$bv"
PROBE_BODY

blank_to_dash() {
  if [ -z "${1:-}" ]; then printf '%s' -; else printf '%s' "$1"; fi
}

# A negative reading is a real integer that simply cannot trip; it must reach the range check and be
# labelled INVALID rather than be dismissed as UNREADABLE.
is_integer() {
  [[ ${1:-} =~ ^-?[0-9]+$ ]]
}

# Kotlin .toInt() truncates toward zero and awk int() does the same. Never rounds.
milli_to_whole_c() {
  awk -v raw="$1" 'BEGIN { printf "%d", int(raw / 1000) }'
}

scaled_display() {
  awk -v raw="$1" -v div="$2" 'BEGIN { printf "%.1f", raw / div }'
}

# usb/current_now and usb/voltage_now (likewise battery/*) are microamps and microvolts on Android's
# power_supply sysfs, so watts = (uA / 1e6) * (uV / 1e6). A non-integer or absent input (unreadable
# node, permission denied) renders as "-", never a silent 0.000 that would look like a real zero-power
# reading. -v passes each value as awk's own argument rather than interpolating it into the program
# text, for the same reason report_transition's bug comment below gives: no quoting context is ever
# crossed.
compute_watts() {
  local current=$1 voltage=$2
  if ! is_integer "$current" || ! is_integer "$voltage"; then
    printf -- '-'
    return 0
  fi
  awk -v i="$current" -v v="$voltage" 'BEGIN { printf "%.3f", (i / 1000000.0) * (v / 1000000.0) }'
}

# true_w = adapter_w - batt_w, NOT adapter_w alone. The adapter is current-limited and saturates under
# load; past that point the battery supplements it and batt_w goes negative (discharging), so
# subtracting a negative adds the battery's contribution back in. Logging adapter_w as if it were
# device power UNDERSTATES consumption exactly when the device is working hardest. Undefined (as "-")
# unless both inputs are themselves defined — a partial reading must never produce a confident number.
compute_true_watts() {
  local adapter=$1 batt=$2
  if [ "$adapter" = - ] || [ "$batt" = - ]; then
    printf -- '-'
    return 0
  fi
  awk -v a="$adapter" -v b="$batt" 'BEGIN { printf "%.3f", a - b }'
}

# One trip input's verdict: OVER / below / INVALID / UNREADABLE. The caller both renders this and
# resolves supervisor precedence from it, so a reading can never be rendered one way and counted
# another.
verdict() {
  local value=$1 low=$2 high=$3 trip=$4 mode=$5
  if ! is_integer "$value"; then
    printf 'UNREADABLE'
    return 0
  fi
  if [ "$value" -lt "$low" ] || [ "$value" -gt "$high" ]; then
    printf 'INVALID'
    return 0
  fi
  if [ "$mode" = inclusive ]; then
    if [ "$value" -ge "$trip" ]; then printf 'OVER'; else printf 'below'; fi
  else
    if [ "$value" -gt "$trip" ]; then printf 'OVER'; else printf 'below'; fi
  fi
}

render_input() {
  printf '      %-24s %-12s %-26s %s\n' "$1" "$2" "$3" "$4"
}

# The bug this replaces: the ad-hoc version nested escaped double quotes inside a command
# substitution inside a double-quoted string. The inner \" closed the outer quote, which exposed
# {printf "%.1f", VALUE/1000} to brace expansion, so awk received the mangled program
# BEGINprintf "%.1f" — an expression pattern with no BEGIN block. awk then fell through to reading
# stdin and printed nothing, which is exactly why the header appeared and the values never did.
# Every awk program here is single-quoted and every value arrives through -v, so no quoting context
# is ever crossed.
report_transition() {
  local at=$1 was_level=$2 now_level=$3 was_p6=$4 now_p6=$5
  local cpu_milli=$6 gpu_milli=$7 battery_deci=$8 android_status=$9
  local cpu_whole=- gpu_whole=-
  local cpu_text=unreadable gpu_text=unreadable battery_text=unreadable status_text=unreadable
  local android_verdict cpu_verdict gpu_verdict battery_verdict reason=

  if is_integer "$cpu_milli"; then
    cpu_whole=$(milli_to_whole_c "$cpu_milli")
    cpu_text="$(scaled_display "$cpu_milli" 1000) C"
  fi
  if is_integer "$gpu_milli"; then
    gpu_whole=$(milli_to_whole_c "$gpu_milli")
    gpu_text="$(scaled_display "$gpu_milli" 1000) C"
  fi
  if is_integer "$battery_deci"; then
    battery_text="$(scaled_display "$battery_deci" 10) C"
  fi
  if is_integer "$android_status"; then
    status_text="$android_status"
  fi

  android_verdict=$(verdict "$android_status" "$ANDROID_MIN" "$ANDROID_MAX" "$ANDROID_TRIP" inclusive)
  cpu_verdict=$(verdict "$cpu_whole" "$CPU_MIN_C" "$CPU_MAX_C" "$CPU_TRIP_C" exclusive)
  gpu_verdict=$(verdict "$gpu_whole" "$GPU_MIN_C" "$GPU_MAX_C" "$GPU_TRIP_C" exclusive)
  battery_verdict=$(verdict "$battery_deci" "$BATTERY_MIN_DECI_C" "$BATTERY_MAX_DECI_C" \
    "$BATTERY_TRIP_DECI_C" exclusive)

  printf '*** sample %s  CEILING CHANGE  gpu_maxlvl %s->%s  pol6 %s->%s\n' \
    "$at" "$was_level" "$now_level" "$was_p6" "$now_p6"
  printf '    trip inputs at this sample:\n'
  render_input android_thermal_status "$status_text" "trip >= 3 (SEVERE)" "$android_verdict"
  render_input cpu_hottest_zone "$cpu_text" "trip > 85 C (whole, trunc)" "$cpu_verdict"
  render_input gpu_hottest_zone "$gpu_text" "trip > 85 C (whole, trunc)" "$gpu_verdict"
  render_input battery "$battery_text" "trip > 45.0 C" "$battery_verdict"

  # presentThermalTrip() returns the first match in exactly this order.
  if [ "$android_verdict" = OVER ]; then
    reason=THERMAL_ANDROID
  elif [ "$cpu_verdict" = OVER ]; then
    reason=THERMAL_CPU
  elif [ "$gpu_verdict" = OVER ]; then
    reason=THERMAL_GPU
  elif [ "$battery_verdict" = OVER ]; then
    reason=THERMAL_BATTERY
  fi
  if [ -n "$reason" ]; then
    printf '    first matching trip in supervisor order: %s\n' "$reason"
  else
    printf '    no trip input was over threshold at this sample\n'
  fi
  printf '    (no debounce: the state at the sampled instant, not a confession)\n'
}

printf 'epoch_ms\tsample\tgpu_maxlvl\tgpu_hz\tpol0_khz\tpol6_khz\tcpu_hot_mC\tgpu_hot_mC\tbatt_dC\tthermal_status\tfan_duty\tfan_mode\tusb_uA\tusb_uV\tadapter_w\tbatt_uA\tbatt_uV\tbatt_w\ttrue_w\tevent\n' \
  >"$OUT"

printf '%s: capturing %s samples from %s -> %s\n' "$TOOL" "$SAMPLES" "$SERIAL" "$OUT"
printf '%s: watching for a ceiling release (gpu_maxlvl or policy6 scaling_max_freq change)\n' "$TOOL"

# The target cadence is derived and printed from whatever SLEEP_SECONDS actually is (default or
# override) rather than repeating OVERSAMPLE_MULTIPLE verbatim, so this line can never drift out of
# sync with the interval the loop below actually uses.
banner_target_hz=$(awk -v s="$SLEEP_SECONDS" 'BEGIN { if (s > 0) printf "%.3f", 1 / s; else print "" }')
if [ -z "$banner_target_hz" ]; then
  printf '%s: target cadence: no fixed wait (sleep=%ss); supervisor tick is %s Hz\n' \
    "$TOOL" "$SLEEP_SECONDS" "$SUPERVISOR_TICK_SECONDS"
else
  banner_tick_multiple=$(awk -v tick="$SUPERVISOR_TICK_SECONDS" -v s="$SLEEP_SECONDS" \
    'BEGIN { printf "%.2f", tick / s }')
  printf '%s: target cadence: %s Hz (sleep=%ss, %sx the %s Hz supervisor tick)\n' \
    "$TOOL" "$banner_target_hz" "$SLEEP_SECONDS" "$banner_tick_multiple" "$SUPERVISOR_TICK_SECONDS"
fi

prev_level=
prev_p6=
changes=0
gaps=0
sample=0
first_ms=
last_ms=
while [ "$sample" -lt "$SAMPLES" ]; do
  # Host wall clock, not device state: BSD/macOS date(1) has no sub-second resolution, so millisecond
  # epoch needs an interpreter. -S skips site-module init since only the stdlib time module is used.
  now_ms=$(python3 -S -c 'import time; print(int(time.time() * 1000))')
  if [ -z "$first_ms" ]; then first_ms=$now_ms; fi
  last_ms=$now_ms

  raw=$("$ADB" -s "$SERIAL" shell "$PROBE" 2>/dev/null | tr -d '\r' | tail -n 1 || true)
  gpu_level=
  gpu_hz=
  pol0=
  pol6=
  cpu_mc=
  gpu_mc=
  batt_dc=
  status=
  fan_duty=
  fan_mode=
  usb_ua=
  usb_uv=
  batt_ua=
  batt_uv=
  IFS='|' read -r gpu_level gpu_hz pol0 pol6 cpu_mc gpu_mc batt_dc status fan_duty fan_mode \
    usb_ua usb_uv batt_ua batt_uv <<<"$raw" || true

  event=-
  if [ -z "$raw" ]; then
    # A dropped round trip is a gap, not a transition. Reporting it as a ceiling change would invent
    # a trip that never happened, so the last readable values are held for the next comparison.
    event=NO_SAMPLE
    gaps=$((gaps + 1))
  elif [ -n "$prev_level" ] && [ -n "$gpu_level" ] &&
    { [ "$gpu_level" != "$prev_level" ] || [ "$pol6" != "$prev_p6" ]; }; then
    event=CEILING_CHANGE
    changes=$((changes + 1))
  fi

  adapter_w=$(compute_watts "$usb_ua" "$usb_uv")
  batt_w=$(compute_watts "$batt_ua" "$batt_uv")
  true_w=$(compute_true_watts "$adapter_w" "$batt_w")

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$now_ms" "$sample" \
    "$(blank_to_dash "$gpu_level")" "$(blank_to_dash "$gpu_hz")" \
    "$(blank_to_dash "$pol0")" "$(blank_to_dash "$pol6")" \
    "$(blank_to_dash "$cpu_mc")" "$(blank_to_dash "$gpu_mc")" \
    "$(blank_to_dash "$batt_dc")" "$(blank_to_dash "$status")" \
    "$(blank_to_dash "$fan_duty")" "$(blank_to_dash "$fan_mode")" \
    "$(blank_to_dash "$usb_ua")" "$(blank_to_dash "$usb_uv")" "$adapter_w" \
    "$(blank_to_dash "$batt_ua")" "$(blank_to_dash "$batt_uv")" "$batt_w" "$true_w" \
    "$event" >>"$OUT"

  if [ "$event" = CEILING_CHANGE ]; then
    report_transition "$sample" "$prev_level" "$gpu_level" "$prev_p6" "$pol6" \
      "$cpu_mc" "$gpu_mc" "$batt_dc" "$status"
  fi

  if [ -n "$gpu_level" ]; then
    prev_level=$gpu_level
    prev_p6=$pol6
  fi
  sample=$((sample + 1))
  if [ "$SLEEP_SECONDS" != 0 ]; then
    sleep "$SLEEP_SECONDS"
  fi
done

printf '%s: done — %s samples, %s ceiling changes, %s dropped reads -> %s\n' \
  "$TOOL" "$SAMPLES" "$changes" "$gaps" "$OUT"

# The achieved rate is measured from the epoch_ms this run actually recorded, never assumed from the
# requested cadence. NO_SAMPLE iterations still spent real wall-clock time (often more, on an adb
# timeout) and count toward it; excluding them would understate elapsed time and inflate the rate.
if [ "$sample" -le 1 ]; then
  printf '%s: achieved rate: not enough samples to measure (need more than 1, got %s)\n' \
    "$TOOL" "$sample"
elif [ -z "${first_ms:-}" ] || [ -z "${last_ms:-}" ] || [ "$last_ms" -le "$first_ms" ]; then
  printf '%s: achieved rate: elapsed time too small to measure (%s samples in under 1 ms)\n' \
    "$TOOL" "$sample"
else
  elapsed_ms=$((last_ms - first_ms))
  elapsed_s=$(awk -v ms="$elapsed_ms" 'BEGIN { printf "%.3f", ms / 1000.0 }')
  achieved_hz=$(awk -v n="$sample" -v ms="$elapsed_ms" 'BEGIN { printf "%.3f", (n - 1) / (ms / 1000.0) }')
  summary_target_hz=$(awk -v s="$SLEEP_SECONDS" 'BEGIN { if (s > 0) printf "%.3f", 1 / s; else print "" }')
  if [ -z "$summary_target_hz" ]; then
    printf '%s: achieved rate %s Hz over %ss elapsed (%s samples; sleep=%ss, no fixed target)\n' \
      "$TOOL" "$achieved_hz" "$elapsed_s" "$sample" "$SLEEP_SECONDS"
  else
    drift_pct=$(awk -v a="$achieved_hz" -v t="$summary_target_hz" \
      'BEGIN { printf "%.1f", (a - t) / t * 100.0 }')
    printf '%s: achieved rate %s Hz over %ss elapsed (target %s Hz, drift %s%%)\n' \
      "$TOOL" "$achieved_hz" "$elapsed_s" "$summary_target_hz" "$drift_pct"
  fi
fi
