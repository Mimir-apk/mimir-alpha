#!/usr/bin/env bash
# Presence-only scan of a built Mimir APK before it goes to a tester.
#
#   tools/check-apk-key-free.sh <path-to.apk>
#
# It reads the INITIAL VALUE of BuildConfig.STEAMGRIDDB_API_KEY out of the APK's dex with the newest Android
# build-tools `dexdump`, and prints only one of:
#
#   STEAMGRIDDB_API_KEY: empty
#   STEAMGRIDDB_API_KEY: NON-EMPTY (length N)
#
# The value itself is never printed or logged: dexdump's output goes through a pipe into an awk program that
# emits a length and nothing else (the unzipped dex files sit in a private temp dir until exit, as the key
# sits in the APK). Do not "debug" this script by echoing that pipe; the dump of a debug build contains the
# developer key.
#
# It also prints whether the APK is debuggable (aapt2 dump badging), its package and version, and the
# signer certificate SHA-256 (apksigner verify --print-certs).
#
# Exit status: 0 = key empty, not debuggable, signature verifies. 1 = a gate failed (key present,
# debuggable, or the signature does not verify). 2 = the scan itself could not run (missing tool, missing
# APK, or the key field could not be read); a 2 is never a pass.
#
# Host notes: Git Bash on Windows works (the .exe/.bat tools are found, and paths are converted with
# cygpath). apksigner needs `java` on PATH or JAVA_HOME set. unzip is required.

set -u
set -o pipefail

# The shared certificate the Thor builds and the closed-beta APK are signed with. A public digest,
# not a secret. Informational only: an APK signed with the release signing key is also acceptable.
SHARED_CERT_SHA256="964a64cf186537819583ce379dc425132e68812e0293108ffcb50dab15cf2df3"
BUILD_CONFIG_CLASS="Lcom/gruning/mimir/rewrite/BuildConfig;"
KEY_FIELD="STEAMGRIDDB_API_KEY"

die() { echo "check-apk-key-free: $*" >&2; exit 2; }

[ $# -eq 1 ] || die "usage: tools/check-apk-key-free.sh <path-to.apk>"
APK="$1"
[ -f "$APK" ] || die "no such file: $APK"
if command -v cygpath >/dev/null 2>&1; then
  APK_NATIVE="$(cygpath -m "$APK")"
else
  APK_NATIVE="$APK"
fi

# ---- find the newest build-tools that has all three tools -------------------------------------------------
sdk_roots=()
[ -n "${ANDROID_HOME:-}" ] && sdk_roots+=("$ANDROID_HOME")
[ -n "${ANDROID_SDK_ROOT:-}" ] && sdk_roots+=("$ANDROID_SDK_ROOT")
[ -n "${LOCALAPPDATA:-}" ] && sdk_roots+=("$LOCALAPPDATA/Android/Sdk")
[ -n "${HOME:-}" ] && sdk_roots+=("$HOME/Android/Sdk" "$HOME/Library/Android/sdk")

tool_in() { # <dir> <name> -> prints the path of name, name.exe or name.bat in dir
  local d="$1" n="$2" ext
  for ext in "" ".exe" ".bat"; do
    if [ -f "$d/$n$ext" ]; then echo "$d/$n$ext"; return 0; fi
  done
  return 1
}

BT=""
for root in "${sdk_roots[@]}"; do
  if command -v cygpath >/dev/null 2>&1; then root="$(cygpath -u "$root" 2>/dev/null || echo "$root")"; fi
  [ -d "$root/build-tools" ] || continue
  while IFS= read -r ver; do
    d="$root/build-tools/$ver"
    if tool_in "$d" dexdump >/dev/null && tool_in "$d" aapt2 >/dev/null && tool_in "$d" apksigner >/dev/null; then
      BT="$d"; break
    fi
  done < <(ls -1 "$root/build-tools" | sort -V -r)
  [ -n "$BT" ] && break
done
[ -n "$BT" ] || die "no Android build-tools with dexdump, aapt2 and apksigner under ANDROID_HOME, ANDROID_SDK_ROOT or the default SDK location"
DEXDUMP="$(tool_in "$BT" dexdump)"
AAPT2="$(tool_in "$BT" aapt2)"
APKSIGNER="$(tool_in "$BT" apksigner)"
echo "build-tools: $(basename "$BT")"

rc=0

# ---- 1. the key's initial value: length only --------------------------------------------------------------
# The Windows dexdump cannot read a dex inside an APK ("Failed anonymous mmap"), so the classes*.dex files are
# unzipped to a private temp dir first and removed on exit. A dex of a debug build holds the key, exactly as the
# APK does; it never leaves that directory and is deleted by the trap.
command -v unzip >/dev/null 2>&1 || die "unzip is required to read the dex files out of the APK"
tmp="$(mktemp -d 2>/dev/null)" || die "could not create a temp directory"
trap 'rm -rf "$tmp"' EXIT
unzip -q -o -j "$APK" 'classes*.dex' -d "$tmp" 2>/dev/null || die "could not unzip classes*.dex from $APK"

# dexdump prints  Class descriptor : 'L...;'  then, per static field,  name : 'X'  and  value : <literal>.
# The awk program prints one status word and, for a found value, its length. Nothing else leaves the pipe.
class_q="'${BUILD_CONFIG_CLASS}'"
field_q="'${KEY_FIELD}'"
scan="NOFIELD"
dex_count=0
for dex in "$tmp"/classes*.dex; do
  [ -f "$dex" ] || continue
  dex_count=$((dex_count + 1))
  if command -v cygpath >/dev/null 2>&1; then dex_native="$(cygpath -m "$dex")"; else dex_native="$dex"; fi
  one="$("$DEXDUMP" "$dex_native" 2>/dev/null | awk -v cq="$class_q" -v fq="$field_q" '
    /^[ \t]*Class descriptor[ \t]*:/ { incls = (index($0, cq) > 0); want = 0; next }
    incls && /^[ \t]*name[ \t]*:/    { want = (index($0, fq) > 0); if (want) seen = 1; next }
    incls && want && /^[ \t]*value[ \t]*:/ {
      v = $0; sub(/^[^:]*:[ \t]*/, "", v); sub(/\r$/, "", v)
      if (v ~ /^"/) { print "VALUE " (length(v) - 2); got = 1; exit }
      print "NOVALUE"; got = 1; exit
    }
    END { if (!got) print (seen ? "NOVALUE" : "NOFIELD") }
  ')"
  scan="$one"
  [ "$one" != "NOFIELD" ] && break
done
[ "$dex_count" -gt 0 ] || die "no classes*.dex in $APK"

case "$scan" in
  "VALUE 0")
    echo "STEAMGRIDDB_API_KEY: empty" ;;
  VALUE\ *)
    echo "STEAMGRIDDB_API_KEY: NON-EMPTY (length ${scan#VALUE })"
    rc=1 ;;
  NOVALUE)
    echo "STEAMGRIDDB_API_KEY: UNREADABLE (the field has no constant initial value in this APK)" >&2
    exit 2 ;;
  NOFIELD)
    echo "STEAMGRIDDB_API_KEY: UNREADABLE (no $KEY_FIELD in $BUILD_CONFIG_CLASS in $dex_count dex file(s); wrong APK, or dexdump failed)" >&2
    exit 2 ;;
  *)
    echo "STEAMGRIDDB_API_KEY: UNREADABLE (unexpected scan result)" >&2
    exit 2 ;;
esac

# ---- 2. debuggable? package and version -------------------------------------------------------------------
badging="$("$AAPT2" dump badging "$APK_NATIVE" 2>/dev/null)" || die "aapt2 dump badging failed on $APK"
pkg_line="$(printf '%s\n' "$badging" | grep -m1 '^package:' | sed -E "s/^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'.*/\1 versionCode \2 versionName \3/")"
[ -n "$pkg_line" ] || die "aapt2 gave no package line for $APK"
echo "package: $pkg_line"
if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
  echo "debuggable: YES"
  rc=1
else
  echo "debuggable: no"
fi

# ---- 3. signer --------------------------------------------------------------------------------------------
if certs="$("$APKSIGNER" verify --print-certs "$APK_NATIVE" 2>&1)"; then
  digests="$(printf '%s\n' "$certs" | grep -i 'certificate SHA-256 digest' | sed -E 's/.*: *//' | tr -d '\r ')"
  if [ -z "$digests" ]; then
    echo "signer: verifies, but apksigner printed no SHA-256 digest" >&2
    rc=1
  else
    while IFS= read -r digest; do
      if [ "$digest" = "$SHARED_CERT_SHA256" ]; then
        echo "signer SHA-256: $digest (the shared Thor-build certificate)"
      else
        echo "signer SHA-256: $digest (NOT the shared Thor-build certificate)"
      fi
    done <<< "$digests"
  fi
else
  echo "signer: DOES NOT VERIFY" >&2
  printf '%s\n' "$certs" | head -3 >&2
  rc=1
fi

exit "$rc"
