#!/usr/bin/env bash
# CI emulator smoke: install the debug APK on the runner's booted emulator, cold-start Mimir and keep the evidence
# under smoke/. It is a file, called on one line, because android-emulator-runner runs every line of its `script:`
# in a shell of its own: a variable set on one line was gone by the next, and the install was handed an empty name.
#
#   bash tools/ci-emulator-smoke.sh            (from the workspace root, with the APK artifact under apk/)
set -euo pipefail

APK=$(ls apk/mimir-debug-*.apk 2>/dev/null | head -1 || true)
if [ -z "$APK" ]; then
  echo "::error::No apk/mimir-debug-*.apk in the workspace"; ls -la apk 2>/dev/null || true; exit 1
fi
echo "APK: $APK"

adb wait-for-device
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb install -r "$APK"
adb logcat -c
adb shell am start -W -n com.gruning.mimir.rewrite/.MainActivity
sleep 20

mkdir -p smoke
adb shell screencap -p /sdcard/mimir-main.png
adb pull /sdcard/mimir-main.png smoke/mimir-main.png
adb logcat -d > smoke/logcat.txt
adb shell dumpsys activity activities > smoke/activities.txt

if grep -q "FATAL EXCEPTION" smoke/logcat.txt; then
  echo "::error::Mimir crashed during cold start"; grep -A 30 "FATAL EXCEPTION" smoke/logcat.txt | head -80; exit 1
fi
grep -q "com.gruning.mimir.rewrite/.MainActivity" smoke/activities.txt || { echo "::error::MainActivity is not resumed"; exit 1; }
echo "cold start OK"
