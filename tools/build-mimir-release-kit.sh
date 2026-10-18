#!/bin/sh
set -eu

usage() {
    cat <<'EOF'
Usage: tools/build-mimir-release-kit.sh --build-debug --output DIR

Builds one non-distributable paired validation kit from a clean checkout:
  - Mimir Debug
  - Mimir Tweaker PRODUCTION Debug

The default pair is built together from this committed revision. External APKs and
release-alpha packaging are refused; the kit always validates both freshly built APKs.
The output directory must not exist and must be outside the checkout.

A ScreenScraper software registration is REQUIRED on this certification path:
local.properties must supply non-empty screenscraper.devid and screenscraper.devpassword.
A kit built without them bakes an empty BuildConfig registration and ships a permanently
closed connected-art gate. Only presence is ever reported; values are never printed.
EOF
}

fail() {
    printf 'release-kit: %s\n' "$*" >&2
    exit 1
}

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)

build_debug=0
output_dir=
requested_channel=
requested_posture=production

while [ "$#" -gt 0 ]; do
    case "$1" in
        --build-debug)
            build_debug=1
            shift
            ;;
        --output)
            [ "$#" -ge 2 ] || fail "--output requires a directory"
            output_dir=$2
            shift 2
            ;;
        --channel)
            [ "$#" -ge 2 ] || fail "--channel requires a value"
            [ "$2" = debug ] ||
                fail "only the non-distributable debug channel is available"
            requested_channel=$2
            shift 2
            ;;
        --tweaker-posture)
            [ "$#" -ge 2 ] || fail "--tweaker-posture requires a value"
            case "$2" in
                production|safe) ;;
                *) fail "Tweaker posture must be production or safe" ;;
            esac
            requested_posture=$2
            shift 2
            ;;
        --mimir-apk)
            [ "$#" -ge 2 ] || fail "--mimir-apk requires a path"
            fail "external APK packaging is disabled pending embedded artifact provenance"
            ;;
        --tweaker-apk)
            [ "$#" -ge 2 ] || fail "--tweaker-apk requires a path"
            fail "external APK packaging is disabled pending embedded artifact provenance"
            ;;
        --release-certificate-sha256)
            [ "$#" -ge 2 ] || fail "--release-certificate-sha256 requires a digest"
            fail "release-alpha packaging is not supported by the paired builder"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

[ "$build_debug" -eq 1 ] ||
    fail "external APK packaging is disabled pending embedded artifact provenance"
case "${requested_channel:-debug}" in
    debug) ;;
    *) fail "only the non-distributable debug channel is available" ;;
esac
[ "$requested_posture" = production ] || [ "$requested_posture" = safe ] ||
    fail "Tweaker posture must be production or safe"
[ -n "$output_dir" ] || fail "--output is required"

case "$output_dir" in
    /*) output_candidate=$output_dir ;;
    *) output_candidate="$(pwd)/$output_dir" ;;
esac
output_parent=$(dirname "$output_candidate")
output_name=$(basename "$output_candidate")
[ -d "$output_parent" ] || fail "output parent does not exist: $output_parent"
output_parent=$(CDPATH= cd -- "$output_parent" && pwd -P)
output_abs="$output_parent/$output_name"
case "$output_name" in
    ""|.|..) fail "invalid output directory name" ;;
esac
case "$output_abs" in
    "$repo_root"|"$repo_root"/*) fail "output must be outside the source checkout" ;;
esac
[ ! -e "$output_abs" ] || fail "output path already exists: $output_abs"

local_properties="$repo_root/local.properties"
local_key=$(python3 - "$local_properties" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
if path.is_file():
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("STEAMGRIDDB_API_KEY="):
            print(line.split("=", 1)[1].strip(), end="")
            break
PY
)
[ -z "$local_key" ] ||
    fail "refusing to package a debug APK with a developer SteamGridDB key"
unset local_key

# Build-provenance assertion: this kit requires configured ScreenScraper developer registration.
# With empty generated values, ScreenScraperDevRegistration.isConfigured is false and
# MimirConnectedArtGate stays closed even when the player's account is connected.
#
# Scope is deliberate: empty creds remain a SUPPORTED state for ordinary builds (see
# core-metadata/build.gradle.kts) — a fresh clone simply has the fetch feature unconfigured and stays
# offline. Neither Gradle nor tools/mimir-quality-gate.sh may fail on it. Only this kit builder refuses.
#
# POSTURE: presence/absence only. Raw local.properties values are never printed, logged, or written
# into release metadata; the app's existing BuildConfig intentionally carries compiled values.
screenscraper_registration_state() {
    python3 - "$1" <<'PY'
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
}

registration_state=$(screenscraper_registration_state "$local_properties") ||
    fail "could not determine the ScreenScraper registration state"
if [ "$registration_state" != configured ]; then
    printf 'release-kit: set screenscraper.devid and screenscraper.devpassword in %s\n' \
        "$local_properties" >&2
    printf 'release-kit: (untracked and git-ignored; raw values are never printed or archived), then re-run.\n' >&2
    fail "ScreenScraper software registration is absent or empty: a kit built from this checkout would bake BuildConfig SCREENSCRAPER_DEV_ID/SCREENSCRAPER_DEV_PASSWORD empty, leaving ScreenScraperDevRegistration.isConfigured=false and MimirConnectedArtGate permanently closed"
fi

require_clean_source() {
    git -C "$repo_root" diff --quiet || return 1
    git -C "$repo_root" diff --cached --quiet || return 1
    [ -z "$(git -C "$repo_root" ls-files --others --exclude-standard | head -n 1)" ] || return 1
}

require_clean_source || fail "validation kits require a clean source checkout"
source_revision=$(git -C "$repo_root" rev-parse HEAD)
case "$source_revision" in
    *[!0-9a-f]*|"") fail "source revision is not a hexadecimal Git object id" ;;
esac

umask 077
# Every refusal BEFORE this line leaves nothing behind, because there is nothing to leave -- which is why
# the four registration-refusal self-tests could assert "refused kit still reserved its output directory"
# and pass without any cleanup logic existing. Every refusal AFTER it used to leave a half-built kit on
# disk: real APKs already copied in and chmod 600'd, but no release-kit.json, no INSTALL.txt, no archive.
# That object is worse than nothing here -- it carries installable APKs with no signed manifest to identify
# them, and it also poisons the retry, because line ~108 refuses to run again while the path exists, so the
# operator has to rm a directory full of APKs by hand before trying anything. Found 2026-08-20 by the
# blank-BuildConfig self-test, which is the first case that ever refused this late.
created_output=0
cleanup_output() {
    [ "$created_output" = 1 ] || return 0
    rm -rf "$output_abs"
}
trap cleanup_output EXIT INT TERM

mkdir "$output_abs" || fail "could not reserve output directory"
created_output=1
kit_dir="$output_abs/Mimir-debug-validation-kit"
mkdir "$kit_dir"

case "$requested_posture" in
    production) tweaker_task=assembleProductionDebug ;;
    safe) tweaker_task=assembleSafeDebug ;;
    *) fail "unsupported Tweaker posture: $requested_posture" ;;
esac

(
    cd "$repo_root"
    ./gradlew :app:assembleDebug --no-build-cache --rerun-tasks
    ./gradlew -p mimir-tweaker ":app:$tweaker_task" --no-build-cache --rerun-tasks
)

[ "$(git -C "$repo_root" rev-parse HEAD)" = "$source_revision" ] ||
    fail "source revision changed during the build"
require_clean_source || fail "source checkout changed during the build"

mimir_source="$repo_root/app/build/outputs/apk/debug/app-debug.apk"
tweaker_source="$repo_root/mimir-tweaker/app/build/outputs/apk/$(printf '%s' "$requested_posture" | tr '[:upper:]' '[:lower:]')/debug/app-$(printf '%s' "$requested_posture" | tr '[:upper:]' '[:lower:]')-debug.apk"
[ -f "$mimir_source" ] || fail "Mimir Debug APK was not produced"
[ -f "$tweaker_source" ] || fail "Tweaker $requested_posture Debug APK was not produced"
cp "$mimir_source" "$kit_dir/mimir.apk"
cp "$tweaker_source" "$kit_dir/mimir-tweaker.apk"
chmod 600 "$kit_dir/mimir.apk" "$kit_dir/mimir-tweaker.apk"
mkdir -p "$kit_dir/legal/mimir-tweaker"
cp "$repo_root/NOTICE" "$kit_dir/legal/NOTICE"
cp "$repo_root/core-metadata/src/main/cpp/THIRD_PARTY_NOTICES.md" \
    "$kit_dir/legal/THIRD_PARTY_NOTICES.md"
cp "$repo_root/mimir-tweaker/LICENSE" "$kit_dir/legal/mimir-tweaker/LICENSE"
cp "$repo_root/mimir-tweaker/NOTICE.md" "$kit_dir/legal/mimir-tweaker/NOTICE.md"
chmod 600 "$kit_dir/legal/NOTICE" "$kit_dir/legal/THIRD_PARTY_NOTICES.md" \
    "$kit_dir/legal/mimir-tweaker/LICENSE" "$kit_dir/legal/mimir-tweaker/NOTICE.md"
mimir_apk="$kit_dir/mimir.apk"
tweaker_apk="$kit_dir/mimir-tweaker.apk"

sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$sdk_dir" ] && [ -f "$local_properties" ]; then
    sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$local_properties" | head -n 1)
fi

apkanalyzer=${MIMIR_APKANALYZER:-}
if [ -z "$apkanalyzer" ] && [ -n "$sdk_dir" ] &&
    [ -x "$sdk_dir/cmdline-tools/latest/bin/apkanalyzer" ]; then
    apkanalyzer="$sdk_dir/cmdline-tools/latest/bin/apkanalyzer"
fi
if [ -z "$apkanalyzer" ]; then
    apkanalyzer=$(command -v apkanalyzer 2>/dev/null || true)
fi
[ -n "$apkanalyzer" ] && [ -x "$apkanalyzer" ] || fail "apkanalyzer was not found"

apksigner=${MIMIR_APKSIGNER:-}
if [ -z "$apksigner" ] && [ -n "$sdk_dir" ] && [ -d "$sdk_dir/build-tools" ]; then
    apksigner=$(find "$sdk_dir/build-tools" -type f -name apksigner -perm -111 2>/dev/null |
        sort | tail -n 1)
fi
if [ -z "$apksigner" ]; then
    apksigner=$(command -v apksigner 2>/dev/null || true)
fi
[ -n "$apksigner" ] && [ -x "$apksigner" ] || fail "apksigner was not found"

manifest_value() {
    "$apkanalyzer" manifest "$1" "$2" | tr -d '\r' | tail -n 1
}

manifest_metadata_value() {
    metadata_name=$1
    apk=$2
    "$apkanalyzer" manifest print "$apk" | tr -d '\r' | awk -v wanted="$metadata_name" '
        /<meta-data/ { active = 1; key = ""; value = "" }
        active && match($0, /android:name="[^"]*"/) {
            key = substr($0, RSTART + 14, RLENGTH - 15)
        }
        active && match($0, /android:value="[^"]*"/) {
            value = substr($0, RSTART + 15, RLENGTH - 16)
        }
        active && /\/>/ {
            if (key == wanted) { print value; found = 1; exit }
            active = 0
        }
        END { if (!found) exit 1 }
    '
}

certificate_digest() {
    output=$("$apksigner" verify --print-certs "$1") ||
        fail "signature verification failed: $1"
    # build-tools <= 36 print "Signer #1 certificate SHA-256 digest: <hex>"; 37 prints one
    # "V2 Signer: ...", "V3 Signer: ..." or "V3.1 Signer: ..." line per scheme. Count distinct
    # digests, so one key signing several schemes is one signer and a second key is two.
    digests=$(printf '%s\n' "$output" |
        sed -n -E 's/^(Signer #[0-9]+|V[0-9.]+ Signer( #[0-9]+)?):? certificate SHA-256 digest: //p' |
        tr '[:upper:]' '[:lower:]' | tr -d ' :\r' | sort -u)
    [ "$(printf '%s\n' "$digests" | sed '/^$/d' | wc -l | tr -d ' ')" -eq 1 ] ||
        fail "exactly one signing certificate is required: $1"
    case "$digests" in
        *[!0-9a-f]*|"") fail "certificate digest is not hexadecimal: $1" ;;
    esac
    [ "${#digests}" -eq 64 ] || fail "certificate digest is not SHA-256: $1"
    printf '%s\n' "$digests"
}

mimir_package=$(manifest_value application-id "$mimir_apk")
tweaker_package=$(manifest_value application-id "$tweaker_apk")
[ "$mimir_package" = com.gruning.mimir.rewrite ] ||
    fail "unexpected Mimir application id: $mimir_package"
[ "$tweaker_package" = com.gruning.mimir.tweaker ] ||
    fail "unexpected Tweaker application id: $tweaker_package"

mimir_version_name=$(manifest_value version-name "$mimir_apk")
mimir_version_code=$(manifest_value version-code "$mimir_apk")
tweaker_version_name=$(manifest_value version-name "$tweaker_apk")
tweaker_version_code=$(manifest_value version-code "$tweaker_apk")
mimir_debuggable=$(manifest_value debuggable "$mimir_apk")
tweaker_debuggable=$(manifest_value debuggable "$tweaker_apk")
case "$mimir_version_name$tweaker_version_name" in
    *[!A-Za-z0-9._+-]*) fail "version names contain unsupported manifest characters" ;;
esac
case "$mimir_version_code:$tweaker_version_code" in
    *[!0-9:]*) fail "version codes must be decimal integers" ;;
esac
[ "$mimir_debuggable:$tweaker_debuggable" = true:true ] ||
    fail "the paired validation kit must contain two debug APKs"

client_api=$(sed -n 's/^const val TWEAKER_V4_MIN_API_VERSION = \([0-9][0-9]*\)$/\1/p' \
    "$repo_root/core-tweaker-client/src/main/kotlin/com/gruning/mimir/rewrite/core/tweaker/TweakerV4Session.kt")
client_discovered_api=$(sed -n 's/^const val TWEAKER_V9_DISCOVERED_RECORD_MIN_API_VERSION = \([0-9][0-9]*\)$/\1/p' \
    "$repo_root/core-tweaker-client/src/main/kotlin/com/gruning/mimir/rewrite/core/tweaker/TweakerDiscoveredSession.kt")
service_api=$(sed -n 's/^[[:space:]]*const val API_VERSION = \([0-9][0-9]*\)$/\1/p' \
    "$repo_root/mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt")
[ -n "$client_api" ] || fail "could not read Mimir's required Tweaker API"
[ -n "$client_discovered_api" ] || fail "could not read Mimir's discovered-session API"
[ -n "$service_api" ] || fail "could not read Tweaker's declared API"

mimir_required=$(manifest_metadata_value \
    com.gruning.mimir.rewrite.TWEAKER_REQUIRED_API_VERSION "$mimir_apk" || true)
tweaker_api=$(manifest_metadata_value com.gruning.mimir.tweaker.API_VERSION "$tweaker_apk" || true)
tweaker_posture=$(manifest_metadata_value com.gruning.mimir.tweaker.POSTURE "$tweaker_apk" || true)
tweaker_writes=$(manifest_metadata_value \
    com.gruning.mimir.tweaker.HARDWARE_WRITES_COMPILED "$tweaker_apk" || true)
tweaker_signed=$(manifest_metadata_value \
    com.gruning.mimir.tweaker.SIGNED_MANIFEST_REQUIRED "$tweaker_apk" || true)
[ "$mimir_required" = "$client_api" ] || fail "Mimir APK/source API mismatch"
[ "$tweaker_api" = "$service_api" ] || fail "Tweaker APK/source API mismatch"
[ "$tweaker_api" -ge "$mimir_required" ] || fail "Tweaker API is older than Mimir's requirement"
[ "$client_discovered_api" -ge 9 ] || fail "Mimir discovered-session API is older than V9"
[ "$tweaker_api" -ge "$client_discovered_api" ] || fail "Tweaker API is older than Mimir's discovered-session requirement"
expected_posture=$(printf '%s' "$requested_posture" | tr '[:lower:]' '[:upper:]')
[ "$(printf '%s' "$tweaker_posture" | tr '[:lower:]' '[:upper:]')" = "$expected_posture" ] ||
    fail "Tweaker APK posture does not match requested posture: $expected_posture"
case "$requested_posture" in
    production)
        [ "$tweaker_writes" = true ] || fail "Tweaker PRODUCTION APK did not compile hardware writes"
        [ "$tweaker_signed" = true ] || fail "Tweaker PRODUCTION APK must retain signed-manifest metadata"
        ;;
    safe)
        [ "$tweaker_writes" = false ] || fail "Tweaker SAFE APK compiled hardware writes"
        [ "$tweaker_signed" = false ] || fail "Tweaker SAFE APK unexpectedly requires signed manifests"
        ;;
esac

tweaker_manifest=$("$apkanalyzer" manifest print "$tweaker_apk" | tr -d '\r')
if [ "$requested_posture" = production ]; then
    case "$tweaker_manifest" in
        *'com.gruning.mimir.tweaker.MainActivity'*|*'android:name=".MainActivity"'*)
            fail "Tweaker Production APK exposes a launcher MainActivity" ;;
    esac
fi
case "$tweaker_manifest" in
    *'MimirTweakerService'*) ;;
    *) fail "Tweaker APK is missing MimirTweakerService" ;;
esac
case "$tweaker_manifest" in
    *'RestoreGuardianProcessService'*) ;;
    *) fail "Tweaker APK is missing RestoreGuardianProcessService" ;;
esac
case "$tweaker_manifest" in
    *'TweakerBootReceiver'*) ;;
    *) fail "Tweaker APK is missing TweakerBootReceiver" ;;
esac

# --- Build-credential gate -----------------------------------------------------------------
# Developer registration comes from gitignored local.properties. A separate worktree without
# that file falls through to empty defaults, so inspect the generated values before packaging.
#
# Read generated BuildConfig rather than APK dex.
# Two dex approaches were tried and both are unreliable here — `grep -F` matches a short
# credential as a substring of unrelated dex bytes (false PASS on a genuinely empty build), and
# `strings | grep -xF` fails to find it at all (false FAIL on a correct build). BuildConfig.java
# is exact and was verified to discriminate: length 0 on the broken build, non-zero on the fixed
# one. It asserts NON-EMPTINESS only — no credential value is printed, logged, or written.
metadata_buildconfig() {
    find "$repo_root/core-metadata/build/generated/source/buildConfig" \
        -name BuildConfig.java 2>/dev/null | head -n 1
}

buildconfig_field_length() {
    awk -F'"' -v f="$1" '$0 ~ f" = " {print length($2); exit}' "$2"
}

local_props="$repo_root/local.properties"
[ -f "$local_props" ] ||
    fail "local.properties is missing — this build would ship blank credentials (ScreenScraper regression, 2026-08-13)"

metadata_bc=$(metadata_buildconfig)
[ -n "$metadata_bc" ] ||
    fail "core-metadata BuildConfig.java not found — cannot verify compiled credentials"

for field in SCREENSCRAPER_DEV_ID SCREENSCRAPER_DEV_PASSWORD SCREENSCRAPER_SOFTNAME; do
    len=$(buildconfig_field_length "$field" "$metadata_bc")
    [ -n "$len" ] || fail "$field is absent from the generated BuildConfig"
    [ "$len" -gt 0 ] ||
        fail "$field compiled EMPTY — this build would report 'ScreenScraper isn't available in this build'"
done

printf 'screenscraper credentials: compiled in (non-empty verified, values not shown)\n'

mimir_cert=$(certificate_digest "$mimir_apk")
tweaker_cert=$(certificate_digest "$tweaker_apk")
[ "$mimir_cert" = "$tweaker_cert" ] ||
    fail "Mimir and Tweaker are not signed by the same certificate"

mimir_sha=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$mimir_apk")
tweaker_sha=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$tweaker_apk")

cat > "$kit_dir/release-kit.json" <<EOF
{
  "schema": 3,
  "channel": "debug",
  "distributable": false,
  "built_by_script": true,
  "artifact_source_revision": "$source_revision",
  "source_dirty": false,
  "screenscraper_registration_present": true,
  "signing_certificate_sha256": "$mimir_cert",
  "tweaker_api_required": $mimir_required,
  "tweaker_api_declared": $tweaker_api,
  "tweaker_api_discovered_session_minimum": $client_discovered_api,
  "tweaker_posture": "$requested_posture",
  "hardware_writes_compiled": $tweaker_writes,
  "signed_manifest_required": $tweaker_signed,
  "artifacts": [
    {"file":"mimir.apk","application_id":"$mimir_package","version_name":"$mimir_version_name","version_code":$mimir_version_code,"debuggable":true,"sha256":"$mimir_sha"},
    {"file":"mimir-tweaker.apk","application_id":"$tweaker_package","version_name":"$tweaker_version_name","version_code":$tweaker_version_code,"debuggable":true,"sha256":"$tweaker_sha"}
  ]
}
EOF

cat > "$kit_dir/INSTALL.txt" <<'EOF'
MIMIR + MIMIR TWEAKER — PAIRED DEBUG VALIDATION KIT

This pair is non-distributable and must be treated as one revision-matched bundle.
Compare both APK hashes and the signing certificate with release-kit.json before use.
Install the daemon first, then the frontend, on the same explicit serial:

  adb -s <serial> install -r mimir-tweaker.apk
  adb -s <serial> install -r mimir.apk

Launch and pair through com.gruning.mimir.rewrite/.MainActivity. Android permissions and
special access remain explicit user choices. Do not uninstall packages, clear package data,
or reset the Guardian journal as part of this validation.
EOF

chmod 600 "$kit_dir/release-kit.json" "$kit_dir/INSTALL.txt"
touch -t 198001010000 "$mimir_apk" "$tweaker_apk" \
    "$kit_dir/release-kit.json" "$kit_dir/INSTALL.txt" \
    "$kit_dir/legal/NOTICE" "$kit_dir/legal/THIRD_PARTY_NOTICES.md" \
    "$kit_dir/legal/mimir-tweaker/LICENSE" "$kit_dir/legal/mimir-tweaker/NOTICE.md"

archive="$output_abs/Mimir-debug-validation-kit.zip"
python3 - "$output_abs" "$archive" <<'PYZIP'
from pathlib import Path
import sys
import zipfile
root = Path(sys.argv[1])
files = (
    "mimir.apk", "mimir-tweaker.apk", "release-kit.json", "INSTALL.txt",
    "legal/NOTICE", "legal/THIRD_PARTY_NOTICES.md",
    "legal/mimir-tweaker/LICENSE", "legal/mimir-tweaker/NOTICE.md",
)
with zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED) as bundle:
    for name in files:
        path = "Mimir-debug-validation-kit/" + name
        bundle.write(root / path, path)
PYZIP
chmod 600 "$archive"

python3 - "$kit_dir/release-kit.json" "$mimir_apk" "$tweaker_apk" "$archive" <<'PY'
import hashlib
import json
import sys
import zipfile
from pathlib import Path

manifest_path, mimir_path, tweaker_path, archive_path = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
expected = {
    "Mimir-debug-validation-kit/mimir.apk",
    "Mimir-debug-validation-kit/mimir-tweaker.apk",
    "Mimir-debug-validation-kit/release-kit.json",
    "Mimir-debug-validation-kit/INSTALL.txt",
    "Mimir-debug-validation-kit/legal/NOTICE",
    "Mimir-debug-validation-kit/legal/THIRD_PARTY_NOTICES.md",
    "Mimir-debug-validation-kit/legal/mimir-tweaker/LICENSE",
    "Mimir-debug-validation-kit/legal/mimir-tweaker/NOTICE.md",
}
with zipfile.ZipFile(archive_path) as bundle:
    if set(bundle.namelist()) != expected:
        raise SystemExit("release-kit: archive membership mismatch")
    staged = {
        "Mimir-debug-validation-kit/mimir.apk": mimir_path,
        "Mimir-debug-validation-kit/mimir-tweaker.apk": tweaker_path,
        "Mimir-debug-validation-kit/release-kit.json": manifest_path,
        "Mimir-debug-validation-kit/INSTALL.txt": manifest_path.parent / "INSTALL.txt",
        "Mimir-debug-validation-kit/legal/NOTICE": manifest_path.parent / "legal" / "NOTICE",
        "Mimir-debug-validation-kit/legal/THIRD_PARTY_NOTICES.md": manifest_path.parent / "legal" / "THIRD_PARTY_NOTICES.md",
        "Mimir-debug-validation-kit/legal/mimir-tweaker/LICENSE": manifest_path.parent / "legal" / "mimir-tweaker" / "LICENSE",
        "Mimir-debug-validation-kit/legal/mimir-tweaker/NOTICE.md": manifest_path.parent / "legal" / "mimir-tweaker" / "NOTICE.md",
    }
    for name, path in staged.items():
        if bundle.read(name) != path.read_bytes():
            raise SystemExit(f"release-kit: archived bytes mismatch: {name}")

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

artifacts = {item["file"]: item for item in manifest["artifacts"]}
if artifacts["mimir.apk"]["sha256"] != digest(mimir_path):
    raise SystemExit("release-kit: Mimir checksum mismatch")
if artifacts["mimir-tweaker.apk"]["sha256"] != digest(tweaker_path):
    raise SystemExit("release-kit: Tweaker checksum mismatch")
PY

[ "$(git -C "$repo_root" rev-parse HEAD)" = "$source_revision" ] ||
    fail "source revision changed while packaging"
require_clean_source || fail "source checkout changed while packaging"

archive_sha=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$archive")

# The kit is complete and self-verified past this point, so hand it over rather than clean it up.
created_output=0

printf 'release-kit: %s\n' "$archive"
printf 'release-kit-sha256: %s\n' "$archive_sha"
printf 'artifact-source-revision: %s\n' "$source_revision"
