#!/usr/bin/env bash
# Read-only hardware capture for the Tweaker family catalog.
#
#   bash tools/family-catalog/capture.sh <adb-serial> > capture-<model>.txt
#
# What it does: one `adb shell` call that only READS a short, fixed list of properties and
# sysfs nodes (design D3, 2026-10-13), then prints them as `### section` blocks that
# draft.py parses. Nothing is written to the device: no `settings put`, no node writes, no
# installs, no `am`, no reboot. Output goes to stdout; progress and errors go to stderr.
#
# Personal data: none is read. The settings and logcat lines are filtered ON the device
# (settings to performance_mode|fan, logcat to the word cpugpumode, last 3 lines), so
# nothing else crosses the wire. No serial, account, wifi or file listing is captured.
#
# Works from Git Bash on Windows and from Linux/macOS.
set -u
export MSYS_NO_PATHCONV=1   # Git Bash would otherwise rewrite /sys/... arguments

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
  echo "usage: bash tools/family-catalog/capture.sh <adb-serial> > capture-<model>.txt" >&2
  echo "       (serial from 'adb devices', e.g. emulator-5554 or 192.0.2.10:5555)" >&2
  exit 2
fi
SERIAL="$1"

if ! command -v adb >/dev/null 2>&1; then
  echo "capture: adb is not on PATH" >&2
  exit 1
fi
STATE="$(adb -s "$SERIAL" get-state 2>&1 | tr -d '\r')"
if [ "$STATE" != "device" ]; then
  echo "capture: $SERIAL is not a connected device (adb said: $STATE)" >&2
  exit 1
fi

# The whole on-device script. READ-ONLY by construction: it uses only cat, ls, getprop,
# settings list, logcat -d, grep, tail, tr and echo. test_draft.py scans this block for
# write verbs, so adding one fails the test.
read -r -d '' ON_DEVICE <<'DEVICE_SH'
rd() {
  v=$(cat "$1" 2>&1 | tr '\n' ' ' | tr -s ' ')
  v=${v% }
  case "$v" in
    "cat: "*"Permission denied"*|"cat: "*"Operation not permitted"*)
      echo "$2=<denied>"; echo "# daemon read owed: $2" ;;
    "cat: "*"No such file"*|"cat: "*"Not a directory"*)
      echo "$2=<absent>" ;;
    "cat: "*)
      echo "$2=<unreadable>"; echo "# daemon read owed: $2" ;;
    "")
      echo "$2=<empty>" ;;
    *)
      echo "$2=$v" ;;
  esac
}
gp() {
  v=$(getprop "$1")
  if [ -n "$v" ]; then echo "$1=$v"; else echo "$1=<absent>"; fi
}
echo "### getprop"
for k in ro.soc.model ro.vendor.qti.soc_model ro.board.platform ro.hardware \
         ro.product.manufacturer ro.product.model ro.product.device \
         ro.build.fingerprint init.svc.pservice; do
  gp "$k"
done
echo "### cpufreq"
for p in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do
  [ -d "$p" ] || continue
  n=${p##*/}
  for f in related_cpus cpuinfo_min_freq cpuinfo_max_freq scaling_available_frequencies \
           scaling_min_freq scaling_max_freq; do
    rd "$p/$f" "$n.$f"
  done
done
echo "### msm_performance"
rd /sys/kernel/msm_performance/parameters/cpu_max_freq cpu_max_freq
echo "### kgsl"
K=/sys/class/kgsl/kgsl-3d0
for f in gpu_available_frequencies num_pwrlevels min_pwrlevel max_pwrlevel default_pwrlevel \
         thermal_pwrlevel; do
  rd "$K/$f" "$f"
done
rd "$K/devfreq/available_frequencies" devfreq_available_frequencies
echo "### settings"
s=$(settings list system 2>&1)
echo "$s" | grep -E 'performance_mode|fan'
echo "### gpio5_pwm2"
g=$(ls /sys/class/gpio5_pwm2 2>&1 | tr '\n' ' ' | tr -s ' ')
g=${g% }
case "$g" in
  *"No such file"*|*"Not a directory"*) echo "ls=<absent>" ;;
  *"Permission denied"*) echo "ls=<denied>"; echo "# daemon read owed: gpio5_pwm2.ls" ;;
  "") echo "ls=<empty>" ;;
  *) echo "ls=$g" ;;
esac
echo "### logcat_cpugpumode"
logcat -d 2>&1 | grep cpugpumode | tail -n 3
echo "### end"
DEVICE_SH

echo "### meta"
echo "capture_format=1"
echo "captured_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "capture: reading $SERIAL (read-only)..." >&2
OUT="$(adb -s "$SERIAL" shell "$ON_DEVICE" 2>/dev/null | tr -d '\r')"
RC=$?
printf '%s\n' "$OUT"

# Exit 0 hides failures on this setup: judge the artifact, not the status.
case "$OUT" in
  *"### end") ;;
  *)
    echo "capture: INCOMPLETE - the device script did not reach '### end' (rc=$RC)" >&2
    exit 1
    ;;
esac
echo "capture: done" >&2
