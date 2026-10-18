#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source_builder="$script_dir/build-mimir-release-kit.sh"
fixture_root=$(mktemp -d "${TMPDIR:-/tmp}/mimir-release-kit-test.XXXXXX")

cleanup() {
    rm -rf "$fixture_root"
}
trap cleanup EXIT INT TERM

repo="$fixture_root/repo"
mkdir -p \
    "$repo/tools" \
    "$repo/core-tweaker-client/src/main/kotlin/com/gruning/mimir/rewrite/core/tweaker" \
    "$repo/mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"
cp "$source_builder" "$repo/tools/build-mimir-release-kit.sh"
chmod +x "$repo/tools/build-mimir-release-kit.sh"

cat > "$repo/core-tweaker-client/src/main/kotlin/com/gruning/mimir/rewrite/core/tweaker/TweakerV4Session.kt" <<'EOF'
const val TWEAKER_V4_MIN_API_VERSION = 4
EOF
cat > "$repo/core-tweaker-client/src/main/kotlin/com/gruning/mimir/rewrite/core/tweaker/TweakerDiscoveredSession.kt" <<'EOF'
const val TWEAKER_V9_DISCOVERED_RECORD_MIN_API_VERSION = 9
EOF
cat > "$repo/mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt" <<'EOF'
internal class MimirTweakerService {
    companion object {
        const val API_VERSION = 9
    }
}
EOF
cat > "$repo/.gitignore" <<'EOF'
**/build/
local.properties
EOF
cat > "$repo/README.md" <<'EOF'
release-kit fixture
EOF
# The kit copies these legal files into legal/.
mkdir -p "$repo/core-metadata/src/main/cpp"
for legal in NOTICE core-metadata/src/main/cpp/THIRD_PARTY_NOTICES.md \
    mimir-tweaker/LICENSE mimir-tweaker/NOTICE.md; do
    printf 'fixture %s\n' "$legal" > "$repo/$legal"
done

cat > "$repo/gradlew" <<'EOF'
#!/bin/sh
set -eu
: "${MIMIR_TEST_GRADLE_LOG:?}"
printf '%s\n' "$*" >> "$MIMIR_TEST_GRADLE_LOG"
case "$*" in
    ":app:assembleDebug --no-build-cache --rerun-tasks")
        mkdir -p app/build/outputs/apk/debug
        printf 'mimir fixture\n' > app/build/outputs/apk/debug/app-debug.apk
        # The real :app:assembleDebug transitively builds core-metadata, whose BuildConfig bakes the
        # ScreenScraper credentials read out of local.properties (ssProp()). This fixture never emitted
        # one, so build-mimir-release-kit.sh's compiled-credential oracle -- added 2026-08-13 precisely to
        # catch a build that shipped those fields blank -- refused every CONFIGURED run with
        # "core-metadata BuildConfig.java not found" and the whole self-test exited 1. Worse than a red
        # gate: the oracle's own EMPTY branch had no coverage at all, so the regression it guards could
        # have come back without any test noticing. Mirroring the real generation here fixes both.
        bc_dir=core-metadata/build/generated/source/buildConfig/debug/com/gruning/mimir/rewrite/core/metadata
        mkdir -p "$bc_dir"
        if [ "${MIMIR_TEST_BLANK_BUILDCONFIG:-0}" = 1 ]; then
            # Reproduces the 2026-08-13 regression exactly: local.properties is present and configured,
            # the build "succeeds", and the credentials compile empty anyway.
            bc_id=""
            bc_password=""
            bc_softname=""
        else
            bc_id=$(sed -n 's/^screenscraper\.devid=//p' local.properties | head -n 1)
            bc_password=$(sed -n 's/^screenscraper\.devpassword=//p' local.properties | head -n 1)
            bc_softname=Mimir
        fi
        cat > "$bc_dir/BuildConfig.java" <<JAVA
package com.gruning.mimir.rewrite.core.metadata;
public final class BuildConfig {
  public static final String SCREENSCRAPER_DEV_ID = "$bc_id";
  public static final String SCREENSCRAPER_DEV_PASSWORD = "$bc_password";
  public static final String SCREENSCRAPER_SOFTNAME = "$bc_softname";
}
JAVA
        ;;
    "-p mimir-tweaker :app:assembleProductionDebug --no-build-cache --rerun-tasks")
        mkdir -p mimir-tweaker/app/build/outputs/apk/production/debug
        printf 'tweaker fixture\n' > mimir-tweaker/app/build/outputs/apk/production/debug/app-production-debug.apk
        ;;
    *)
        printf 'unexpected Gradle invocation: %s\n' "$*" >&2
        exit 2
        ;;
esac
if [ "${MIMIR_TEST_ADVANCE_HEAD:-0}" = 1 ] && [ ! -e .git/mimir-test-advanced ]; then
    printf 'advanced\n' >> README.md
    git add README.md
    git -c commit.gpgsign=false -c core.hooksPath=/dev/null commit -q -m advance
    : > .git/mimir-test-advanced
fi
EOF
chmod +x "$repo/gradlew"

apkanalyzer="$fixture_root/apkanalyzer"
cat > "$apkanalyzer" <<'EOF'
#!/bin/sh
set -eu
field=$2
apk=$3
if [ "$field" = print ]; then
    case "$apk" in
        *mimir-tweaker.apk)
            posture=${MIMIR_TEST_TWEAKER_POSTURE:-PRODUCTION}
            writes=${MIMIR_TEST_TWEAKER_WRITES:-true}
            signed=${MIMIR_TEST_TWEAKER_SIGNED:-true}
            printf '%s\n' '<manifest>' '<application>'
            printf '  <meta-data android:name="com.gruning.mimir.tweaker.API_VERSION" android:value="9" />\n'
            printf '  <meta-data android:name="com.gruning.mimir.tweaker.POSTURE" android:value="%s" />\n' "$posture"
            printf '  <meta-data android:name="com.gruning.mimir.tweaker.HARDWARE_WRITES_COMPILED" android:value="%s" />\n' "$writes"
            printf '  <meta-data android:name="com.gruning.mimir.tweaker.SIGNED_MANIFEST_REQUIRED" android:value="%s" />\n' "$signed"
            printf '  <service android:name="com.gruning.mimir.tweaker.MimirTweakerService" />\n'
            printf '  <service android:name="com.gruning.mimir.tweaker.RestoreGuardianProcessService" />\n'
            printf '  <receiver android:name="com.gruning.mimir.tweaker.TweakerBootReceiver" />\n'
            printf '%s\n' '</application>' '</manifest>'
            ;;
        *mimir.apk)
            printf '%s\n' '<manifest>' '<application>'
            printf '  <meta-data android:name="com.gruning.mimir.rewrite.TWEAKER_REQUIRED_API_VERSION" android:value="4" />\n'
            printf '%s\n' '</application>' '</manifest>'
            ;;
        *) exit 2 ;;
    esac
    exit 0
fi
case "$apk:$field" in
    *mimir-tweaker.apk:application-id) printf 'com.gruning.mimir.tweaker\n' ;;
    *mimir-tweaker.apk:version-name) printf '0.4.0-test\n' ;;
    *mimir-tweaker.apk:version-code) printf '5\n' ;;
    *mimir-tweaker.apk:debuggable) printf 'true\n' ;;
    *mimir.apk:application-id) printf 'com.gruning.mimir.rewrite\n' ;;
    *mimir.apk:version-name) printf '0.1.0-test\n' ;;
    *mimir.apk:version-code) printf '4\n' ;;
    *mimir.apk:debuggable) printf 'true\n' ;;
    *) exit 2 ;;
esac
EOF
chmod +x "$apkanalyzer"

apksigner="$fixture_root/apksigner"
cat > "$apksigner" <<'EOF'
#!/bin/sh
set -eu
digest=${MIMIR_TEST_CERT_DIGEST:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}
case "${MIMIR_TEST_CERT_FORMAT:-legacy}" in
v2)
    printf 'V2 Signer: certificate SHA-256 digest: %s\n' "$digest"
    ;;
v37)
    # build-tools 37: one line per scheme, same key.
    printf 'V2 Signer: certificate SHA-256 digest: %s\n' "$digest"
    printf 'V3 Signer: certificate SHA-256 digest: %s\n' "$digest"
    printf 'V3.1 Signer: certificate SHA-256 digest: %s\n' "$digest"
    ;;
v37-v3only)
    printf 'V3 Signer: certificate SHA-256 digest: %s\n' "$digest"
    ;;
v37-two-keys)
    printf 'V2 Signer: certificate SHA-256 digest: %s\n' "$digest"
    printf 'V3 Signer: certificate SHA-256 digest: %s\n' bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    ;;
*)
    printf 'Signer #1 certificate SHA-256 digest: %s\n' "$digest"
    ;;
esac
EOF
chmod +x "$apksigner"

git -C "$repo" init -q
git -C "$repo" config user.name "Mimir Test"
git -C "$repo" config user.email "mimir-test@example.invalid"
git -C "$repo" add .
git -C "$repo" -c commit.gpgsign=false -c core.hooksPath=/dev/null commit -q -m fixture

builder="$repo/tools/build-mimir-release-kit.sh"
gradle_log="$fixture_root/gradle.log"

# The certification path requires a ScreenScraper software registration. These values are fake, live
# only inside the temp fixture, and every case below asserts they never reach an artifact or a message.
fixture_dev_id=fixture-devid-not-a-real-registration
fixture_dev_password=fixture-devpassword-not-a-real-registration

write_configured_local_properties() {
    cat > "$repo/local.properties" <<EOF
# fixture registration — fake values, git-ignored, never packaged
screenscraper.devid=$fixture_dev_id
screenscraper.devpassword=$fixture_dev_password
EOF
}

write_configured_local_properties

positive_out="$fixture_root/positive"
positive_stdout="$fixture_root/positive.stdout"
MIMIR_APKANALYZER="$apkanalyzer" \
MIMIR_APKSIGNER="$apksigner" \
MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$positive_out" > "$positive_stdout"

kit="$positive_out/Mimir-debug-validation-kit"
archive="$positive_out/Mimir-debug-validation-kit.zip"
test -f "$kit/mimir.apk"
test -f "$kit/mimir-tweaker.apk"
test -f "$kit/release-kit.json"
test -f "$kit/INSTALL.txt"
test -f "$archive"

python3 - "$repo" "$kit" "$archive" <<'PY'
import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

repo, kit, archive = map(Path, sys.argv[1:])
manifest = json.loads((kit / "release-kit.json").read_text(encoding="utf-8"))
revision = subprocess.check_output(
    ["git", "-C", str(repo), "rev-parse", "HEAD"], text=True
).strip()
assert manifest["schema"] == 3
assert manifest["channel"] == "debug"
assert manifest["distributable"] is False
assert manifest["built_by_script"] is True
assert manifest["source_dirty"] is False
# Presence attestation only — the manifest records the boolean, never the registration itself.
assert manifest["screenscraper_registration_present"] is True
assert manifest["artifact_source_revision"] == revision
assert manifest["tweaker_api_discovered_session_minimum"] == 9
assert manifest["tweaker_posture"] == "production"
assert manifest["hardware_writes_compiled"] is True
assert manifest["signed_manifest_required"] is True

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

artifacts = {item["file"]: item for item in manifest["artifacts"]}
assert artifacts["mimir.apk"]["sha256"] == digest(kit / "mimir.apk")
assert artifacts["mimir-tweaker.apk"]["sha256"] == digest(kit / "mimir-tweaker.apk")
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
with zipfile.ZipFile(archive) as bundle:
    assert set(bundle.namelist()) == expected
    assert bundle.read("Mimir-debug-validation-kit/mimir.apk") == (kit / "mimir.apk").read_bytes()
    assert bundle.read("Mimir-debug-validation-kit/mimir-tweaker.apk") == (kit / "mimir-tweaker.apk").read_bytes()
PY

# Standing posture invariant: presence is reported, values never are. Nothing the configured pass
# produced — kit files, archive bytes, or stdout — may contain either credential.
if grep -r -q -e "$fixture_dev_id" -e "$fixture_dev_password" \
    "$positive_out" "$positive_stdout"; then
    printf 'kit output leaked a ScreenScraper credential value\n' >&2
    exit 1
fi

reported_archive_sha=$(sed -n 's/^release-kit-sha256: //p' "$positive_stdout")
actual_archive_sha=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$archive")
test "$reported_archive_sha" = "$actual_archive_sha"
test "$(wc -l < "$gradle_log" | tr -d ' ')" -eq 2
grep -q ':app:assembleDebug --no-build-cache --rerun-tasks' "$gradle_log"
grep -q -- '-p mimir-tweaker :app:assembleProductionDebug --no-build-cache --rerun-tasks' "$gradle_log"

if MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
    MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$positive_out" >/dev/null 2>&1; then
    printf 'pre-existing output was accepted\n' >&2
    exit 1
fi

if MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --mimir-apk one.apk --tweaker-apk two.apk \
    --output "$fixture_root/external" >/dev/null 2>&1; then
    printf 'external APK packaging was accepted\n' >&2
    exit 1
fi

for args in \
    "--channel release-alpha --build-debug" \
    "--build-debug --channel release-alpha"; do
    # Intentional word splitting over a fixed test vector.
    # shellcheck disable=SC2086
    if MIMIR_TEST_GRADLE_LOG="$gradle_log" \
        "$builder" $args --output "$fixture_root/release-order-$(printf '%s' "$args" | cksum | awk '{print $1}')" \
        >/dev/null 2>&1; then
        printf 'release-alpha argument order was accepted: %s\n' "$args" >&2
        exit 1
    fi
done

MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
MIMIR_TEST_CERT_FORMAT=v2 MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --tweaker-posture production \
    --output "$fixture_root/explicit-production" >/dev/null

for format in v37 v37-v3only; do
    MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
    MIMIR_TEST_CERT_FORMAT=$format MIMIR_TEST_GRADLE_LOG="$gradle_log" \
        "$builder" --build-debug --output "$fixture_root/apksigner-$format" >/dev/null
done

if MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
    MIMIR_TEST_CERT_FORMAT=v37-two-keys MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/apksigner-two-keys" >/dev/null 2>&1; then
    printf 'two signing keys were accepted\n' >&2
    exit 1
fi
test -f "$fixture_root/explicit-production/Mimir-debug-validation-kit/mimir-tweaker.apk"

if MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
    MIMIR_TEST_TWEAKER_POSTURE=SAFE MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/wrong-posture" >/dev/null 2>&1; then
    printf 'wrong Production posture metadata was accepted\n' >&2
    exit 1
fi

if MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
    MIMIR_TEST_TWEAKER_WRITES=false MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/wrong-writes" >/dev/null 2>&1; then
    printf 'Production APK with hardware writes disabled was accepted\n' >&2
    exit 1
fi

if MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --tweaker-posture invalid \
    --output "$fixture_root/invalid-posture" >/dev/null 2>&1; then
    printf 'invalid Tweaker posture was accepted\n' >&2
    exit 1
fi

if MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$repo/inside-checkout" >/dev/null 2>&1; then
    printf 'output inside the checkout was accepted\n' >&2
    exit 1
fi

ln -s "$repo" "$fixture_root/repo-link"
mkdir -p "$repo/build"
if MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$fixture_root/repo-link/tools/build-mimir-release-kit.sh" --build-debug \
    --output "$repo/build/symlink-escape" >/dev/null 2>&1; then
    printf 'symlink invocation escaped checkout containment\n' >&2
    exit 1
fi

write_configured_local_properties
printf 'STEAMGRIDDB_API_KEY=   release-test-sentinel   \n' >> "$repo/local.properties"
if MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/secret" >/dev/null 2>&1; then
    printf 'nonblank developer key was accepted\n' >&2
    exit 1
fi
write_configured_local_properties

# Build-provenance assertion. The device-certified kit was built from a checkout with no
# local.properties, so BuildConfig SCREENSCRAPER_DEV_ID/SCREENSCRAPER_DEV_PASSWORD baked empty,
# ScreenScraperDevRegistration.isConfigured was false, and the connected-art gate shipped permanently
# closed. Empty creds stay a SUPPORTED state for ordinary Gradle builds and for
# tools/mimir-quality-gate.sh; only this certification path refuses them.
expect_registration_refusal() {
    case_name=$1
    case_out="$fixture_root/$case_name"
    case_err="$fixture_root/$case_name.stderr"
    if MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
        MIMIR_TEST_GRADLE_LOG="$gradle_log" \
        "$builder" --build-debug --output "$case_out" >/dev/null 2>"$case_err"; then
        printf 'unconfigured ScreenScraper registration was accepted: %s\n' "$case_name" >&2
        exit 1
    fi
    if [ -e "$case_out" ]; then
        printf 'refused kit still reserved its output directory: %s\n' "$case_name" >&2
        exit 1
    fi
    for needle in \
        'ScreenScraper software registration is absent or empty' \
        'local.properties' \
        'screenscraper.devid' \
        'screenscraper.devpassword'; do
        if ! grep -q -- "$needle" "$case_err"; then
            printf 'refusal message missing [%s]: %s\n' "$needle" "$case_name" >&2
            exit 1
        fi
    done
    if grep -q -e "$fixture_dev_id" -e "$fixture_dev_password" "$case_err"; then
        printf 'refusal message leaked a credential value: %s\n' "$case_name" >&2
        exit 1
    fi
}

rm -f "$repo/local.properties"
expect_registration_refusal registration-absent-file

printf 'screenscraper.devid=\nscreenscraper.devpassword=%s\n' "$fixture_dev_password" \
    > "$repo/local.properties"
expect_registration_refusal registration-empty-devid

printf 'screenscraper.devid=%s\n' "$fixture_dev_id" > "$repo/local.properties"
expect_registration_refusal registration-missing-devpassword

printf 'screenscraper.devid=   \nscreenscraper.devpassword=\t\n' > "$repo/local.properties"
expect_registration_refusal registration-blank-values

# Configured again: the same builder that refused four times must now proceed past the assertion.
write_configured_local_properties
MIMIR_APKANALYZER="$apkanalyzer" \
MIMIR_APKSIGNER="$apksigner" \
MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/registration-configured" >/dev/null
test -f "$fixture_root/registration-configured/Mimir-debug-validation-kit/mimir.apk"
if grep -r -q -e "$fixture_dev_id" -e "$fixture_dev_password" \
    "$fixture_root/registration-configured"; then
    printf 'configured pass leaked a ScreenScraper credential value\n' >&2
    exit 1
fi

# The compiled-credential oracle's own reason for existing (2026-08-13): local.properties is present AND
# configured, the build reports success, and BuildConfig's credentials are baked EMPTY anyway -- which is
# how a kit shipped with MimirConnectedArtGate permanently closed and nothing caught it. The four refusal
# cases above only cover an unconfigured local.properties, which the builder rejects long before it ever
# reads BuildConfig, so until this case existed the EMPTY branch had no coverage at all.
if MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
    MIMIR_TEST_BLANK_BUILDCONFIG=1 MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/blank-buildconfig" \
    >/dev/null 2>"$fixture_root/blank-buildconfig.stderr"; then
    printf 'a build with EMPTY compiled ScreenScraper credentials was accepted\n' >&2
    exit 1
fi
if ! grep -q 'compiled EMPTY' "$fixture_root/blank-buildconfig.stderr"; then
    printf 'blank-BuildConfig refusal did not name the compiled-empty cause\n' >&2
    exit 1
fi
if [ -e "$fixture_root/blank-buildconfig" ]; then
    printf 'refused blank-BuildConfig kit still reserved its output directory\n' >&2
    exit 1
fi
if grep -q -e "$fixture_dev_id" -e "$fixture_dev_password" \
    "$fixture_root/blank-buildconfig.stderr"; then
    printf 'blank-BuildConfig refusal leaked a credential value\n' >&2
    exit 1
fi

if MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
    MIMIR_TEST_CERT_DIGEST=short MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/bad-cert" >/dev/null 2>&1; then
    printf 'invalid certificate digest was accepted\n' >&2
    exit 1
fi

if MIMIR_APKANALYZER="$apkanalyzer" MIMIR_APKSIGNER="$apksigner" \
    MIMIR_TEST_ADVANCE_HEAD=1 MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/revision-change" >/dev/null 2>&1; then
    printf 'source revision change during build was accepted\n' >&2
    exit 1
fi

printf 'dirty\n' >> "$repo/README.md"
if MIMIR_TEST_GRADLE_LOG="$gradle_log" \
    "$builder" --build-debug --output "$fixture_root/dirty" >/dev/null 2>&1; then
    printf 'dirty source checkout was accepted\n' >&2
    exit 1
fi

printf 'release-kit tests: PASS\n'
