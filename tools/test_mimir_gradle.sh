#!/usr/bin/env sh
# Hermetic self-tests for tools/mimir-gradle.sh (POSIX sh).

set -eu

TOOLS="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH= cd -- "$TOOLS/.." && pwd)"
GRADLE_SH="$TOOLS/mimir-gradle.sh"
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
    *"$needle"*)
      PASS=$((PASS + 1))
      ;;
    *)
      printf 'FAIL %s: missing [%s] in [%s]\n' "$label" "$needle" "$haystack" >&2
      FAIL=$((FAIL + 1))
      ;;
  esac
}

assert_file_missing() {
  path="$1"
  label="$2"
  if [ ! -e "$path" ]; then
    PASS=$((PASS + 1))
  else
    printf 'FAIL %s: expected missing %s\n' "$label" "$path" >&2
    FAIL=$((FAIL + 1))
  fi
}

make_fake_gradle() {
  dir="$1"
  version="$2"
  mkdir -p "$dir"
  cat >"$dir/gradle" <<EOF
#!/usr/bin/env sh
printf 'Gradle %s\n' '$version'
EOF
  chmod +x "$dir/gradle"
  printf '%s' "$dir/gradle"
}

make_sentinel_gradlew() {
  dir="$1"
  sentinel="$2"
  cat >"$dir/gradlew" <<EOF
#!/usr/bin/env sh
printf 'EXECUTED\n' >"$sentinel"
exit 99
EOF
  chmod +x "$dir/gradlew"
}

make_wrapper_props() {
  root="$1"
  dist_url="$2"
  mkdir -p "$root/gradle/wrapper"
  cat >"$root/gradle/wrapper/gradle-wrapper.properties" <<EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=$dist_url
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF
}

make_unpacked_dist() {
  gradle_home="$1"
  flavor="$2"
  hash="$3"
  dist_dir="$gradle_home/wrapper/dists/$flavor/$hash"
  mkdir -p "$dist_dir/gradle-8.9/bin"
  cat >"$dist_dir/gradle-8.9/bin/gradle" <<'EOF'
#!/usr/bin/env sh
printf 'Gradle 8.9\n'
EOF
  chmod +x "$dist_dir/gradle-8.9/bin/gradle"
  : >"$dist_dir/${flavor}.zip.ok"
}

run_gradle_sh() {
  env -i \
    HOME="$HOME" \
    PATH="/usr/bin:/bin" \
    SHELL="/bin/sh" \
    TMPDIR="${TMPDIR:-/tmp}" \
    "$@" \
    sh "$GRADLE_SH" --version 2>/dev/null
}

run_gradle_sh_capture() {
  env -i \
    HOME="$HOME" \
    PATH="/usr/bin:/bin" \
    SHELL="/bin/sh" \
    TMPDIR="${TMPDIR:-/tmp}" \
    "$@" \
    sh "$GRADLE_SH" :noop 2>&1
}

TMP_BASE="$(mktemp -d "${TMPDIR:-/tmp}/mimir-gradle-test.XXXXXX")"
trap 'rm -rf "$TMP_BASE"' EXIT

# Fake-wrapper sentinel: cold unpacked dist must not execute ./gradlew
CASE_DIR="$TMP_BASE/cold"
SENTINEL="$CASE_DIR/sentinel.txt"
mkdir -p "$CASE_DIR/tools"
cp "$GRADLE_SH" "$CASE_DIR/tools/mimir-gradle.sh"
make_sentinel_gradlew "$CASE_DIR" "$SENTINEL"
make_wrapper_props "$CASE_DIR" 'https\://services.gradle.org/distributions/gradle-8.9-bin.zip'
FAKE_HOME="$CASE_DIR/home"
make_unpacked_dist "$FAKE_HOME" "gradle-8.9-bin" "abc123"
out="$(env -i HOME="$HOME" PATH="/usr/bin:/bin" SHELL="/bin/sh" TMPDIR="${TMPDIR:-/tmp}" GRADLE_USER_HOME="$FAKE_HOME" sh "$CASE_DIR/tools/mimir-gradle.sh" --version 2>&1)"
assert_file_missing "$SENTINEL" "cold: gradlew sentinel"
assert_contains "$out" "source=unpacked-wrapper-dist" "cold: source"

# Warm path reuses unpacked dist
out="$(env -i HOME="$HOME" PATH="/usr/bin:/bin" SHELL="/bin/sh" TMPDIR="${TMPDIR:-/tmp}" GRADLE_USER_HOME="$FAKE_HOME" sh "$CASE_DIR/tools/mimir-gradle.sh" --version 2>&1)"
assert_contains "$out" "version=8.9" "warm: version"

# Reject Gradle 8.7 via MIMIR_GRADLE
BIN87="$(make_fake_gradle "$TMP_BASE/g87" "8.7")"
if err="$(MIMIR_GRADLE="$BIN87" sh "$GRADLE_SH" --version 2>&1)"; then
  printf 'FAIL 8.7 rejection: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "must be Gradle 8.9" "8.7 rejection"
fi

# Non-executable MIMIR_GRADLE
BIN_NE="$TMP_BASE/nonexec/gradle"
mkdir -p "$TMP_BASE/nonexec"
printf '#!/bin/sh\n' >"$BIN_NE"
if err="$(MIMIR_GRADLE="$BIN_NE" sh "$GRADLE_SH" --version 2>&1)"; then
  printf 'FAIL nonexec: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  if [ -x "$BIN_NE" ]; then
    # Git Bash can expose every file as executable; the empty script must still be rejected.
    assert_contains "$err" "must be Gradle 8.9" "invalid runner on executable-bit-free filesystem"
  else
    assert_contains "$err" "not executable" "nonexec"
  fi
fi

# Reject repository ./gradlew via MIMIR_GRADLE
REPO_CASE="$TMP_BASE/repo"
mkdir -p "$REPO_CASE/tools"
cp "$GRADLE_SH" "$REPO_CASE/tools/mimir-gradle.sh"
make_sentinel_gradlew "$REPO_CASE" "$REPO_CASE/sentinel-mimir.txt"
make_wrapper_props "$REPO_CASE" 'https\://services.gradle.org/distributions/gradle-8.9-bin.zip'
if err="$(MIMIR_GRADLE="$REPO_CASE/gradlew" sh "$REPO_CASE/tools/mimir-gradle.sh" --version 2>&1)"; then
  printf 'FAIL repo wrapper rejection: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "must not point at repository ./gradlew" "repo wrapper rejection"
fi
assert_file_missing "$REPO_CASE/sentinel-mimir.txt" "repo wrapper nonexecution"

# MIMIR_GRADLE symlink alias to repository ./gradlew
SYMLINK_CASE="$TMP_BASE/symlink-mimir"
mkdir -p "$SYMLINK_CASE/tools"
cp "$GRADLE_SH" "$SYMLINK_CASE/tools/mimir-gradle.sh"
make_sentinel_gradlew "$SYMLINK_CASE" "$SYMLINK_CASE/sentinel-symlink.txt"
make_wrapper_props "$SYMLINK_CASE" 'https\://services.gradle.org/distributions/gradle-8.9-bin.zip'
ln -s "$SYMLINK_CASE/gradlew" "$SYMLINK_CASE/gradle-alias"
if err="$(MIMIR_GRADLE="$SYMLINK_CASE/gradle-alias" sh "$SYMLINK_CASE/tools/mimir-gradle.sh" --version 2>&1)"; then
  printf 'FAIL symlink alias rejection: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "must not point at repository ./gradlew" "symlink alias rejection"
fi
assert_file_missing "$SYMLINK_CASE/sentinel-symlink.txt" "symlink alias nonexecution"

# GRADLE hardlink alias to repository ./gradlew
HARDLINK_CASE="$TMP_BASE/hardlink-gradle"
mkdir -p "$HARDLINK_CASE/tools"
cp "$GRADLE_SH" "$HARDLINK_CASE/tools/mimir-gradle.sh"
make_sentinel_gradlew "$HARDLINK_CASE" "$HARDLINK_CASE/sentinel-hardlink.txt"
make_wrapper_props "$HARDLINK_CASE" 'https\://services.gradle.org/distributions/gradle-8.9-bin.zip'
ln "$HARDLINK_CASE/gradlew" "$HARDLINK_CASE/gradle-hardlink"
if err="$(GRADLE="$HARDLINK_CASE/gradle-hardlink" sh "$HARDLINK_CASE/tools/mimir-gradle.sh" --version 2>&1)"; then
  printf 'FAIL hardlink alias rejection: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "must not point at repository ./gradlew" "hardlink alias rejection"
fi
assert_file_missing "$HARDLINK_CASE/sentinel-hardlink.txt" "hardlink alias nonexecution"

# PATH alias (symlink) to repository ./gradlew
PATH_ALIAS_CASE="$TMP_BASE/path-alias"
mkdir -p "$PATH_ALIAS_CASE/tools" "$PATH_ALIAS_CASE/bin"
cp "$GRADLE_SH" "$PATH_ALIAS_CASE/tools/mimir-gradle.sh"
make_sentinel_gradlew "$PATH_ALIAS_CASE" "$PATH_ALIAS_CASE/sentinel-path.txt"
make_wrapper_props "$PATH_ALIAS_CASE" 'https\://services.gradle.org/distributions/gradle-8.9-bin.zip'
ln -s "$PATH_ALIAS_CASE/gradlew" "$PATH_ALIAS_CASE/bin/gradle"
if err="$(PATH="$PATH_ALIAS_CASE/bin:/usr/bin:/bin" sh "$PATH_ALIAS_CASE/tools/mimir-gradle.sh" --version 2>&1)"; then
  printf 'FAIL PATH alias rejection: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "must not point at repository ./gradlew" "PATH alias rejection"
fi
assert_file_missing "$PATH_ALIAS_CASE/sentinel-path.txt" "PATH alias nonexecution"

# Warmed wrapper-distribution bin/gradle symlink alias to repository ./gradlew
DIST_ALIAS_CASE="$TMP_BASE/dist-alias"
mkdir -p "$DIST_ALIAS_CASE/tools"
cp "$GRADLE_SH" "$DIST_ALIAS_CASE/tools/mimir-gradle.sh"
make_sentinel_gradlew "$DIST_ALIAS_CASE" "$DIST_ALIAS_CASE/sentinel-dist.txt"
make_wrapper_props "$DIST_ALIAS_CASE" 'https\://services.gradle.org/distributions/gradle-8.9-bin.zip'
DIST_HOME="$DIST_ALIAS_CASE/home"
dist_dir="$DIST_HOME/wrapper/dists/gradle-8.9-bin/aliashash"
mkdir -p "$dist_dir/gradle-8.9/bin"
ln -s "$DIST_ALIAS_CASE/gradlew" "$dist_dir/gradle-8.9/bin/gradle"
: >"$dist_dir/gradle-8.9-bin.zip.ok"
if err="$(env -i HOME="$HOME" PATH="/usr/bin:/bin" SHELL="/bin/sh" TMPDIR="${TMPDIR:-/tmp}" GRADLE_USER_HOME="$DIST_HOME" sh "$DIST_ALIAS_CASE/tools/mimir-gradle.sh" --version 2>&1)"; then
  printf 'FAIL dist alias rejection: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "no Gradle 8.9 runner found" "dist alias rejection"
fi
assert_file_missing "$DIST_ALIAS_CASE/sentinel-dist.txt" "dist alias nonexecution"

# Cold valid wrapper properties with empty Gradle cache (no runner, no gradlew execution)
COLD_EMPTY_CASE="$TMP_BASE/cold-empty"
mkdir -p "$COLD_EMPTY_CASE/tools"
cp "$GRADLE_SH" "$COLD_EMPTY_CASE/tools/mimir-gradle.sh"
make_sentinel_gradlew "$COLD_EMPTY_CASE" "$COLD_EMPTY_CASE/sentinel-cold.txt"
make_wrapper_props "$COLD_EMPTY_CASE" 'https\://services.gradle.org/distributions/gradle-8.9-bin.zip'
COLD_EMPTY_HOME="$COLD_EMPTY_CASE/empty-home"
mkdir -p "$COLD_EMPTY_HOME"
if err="$(env -i HOME="$HOME" PATH="/usr/bin:/bin" SHELL="/bin/sh" TMPDIR="${TMPDIR:-/tmp}" GRADLE_USER_HOME="$COLD_EMPTY_HOME" sh "$COLD_EMPTY_CASE/tools/mimir-gradle.sh" --version 2>&1)"; then
  printf 'FAIL cold empty cache: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "no Gradle 8.9 runner found" "cold empty cache"
fi
assert_file_missing "$COLD_EMPTY_CASE/sentinel-cold.txt" "cold empty cache nonexecution"

BIN89="$(make_fake_gradle "$TMP_BASE/g89" "8.9")"

# GRADLE env fallback
out="$(GRADLE="$BIN89" sh "$GRADLE_SH" --version 2>&1)"
assert_contains "$out" "source=GRADLE" "GRADLE fallback"

# PATH fallback
PATH_CASE="$TMP_BASE/pathbin"
BIN_PATH="$(make_fake_gradle "$PATH_CASE" "8.9")"
out="$(PATH="$PATH_CASE:/usr/bin:/bin" sh "$GRADLE_SH" --version 2>&1)"
assert_contains "$out" "source=PATH" "PATH fallback"

# argv exact order: runner receives args unchanged after launcher bookkeeping
ARG_CASE="$TMP_BASE/argv"
mkdir -p "$ARG_CASE"
cat >"$ARG_CASE/gradle" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$@" >"$MIMIR_ARGV_OUT"
printf 'Gradle 8.9\n'
EOF
chmod +x "$ARG_CASE/gradle"
ARGV_OUT="$ARG_CASE/argv.out"
rm -f "$ARGV_OUT"
export MIMIR_ARGV_OUT="$ARGV_OUT"
MIMIR_GRADLE="$ARG_CASE/gradle" sh "$GRADLE_SH" :app:assembleDebug --offline --console=plain >/dev/null 2>&1
if [ -f "$ARGV_OUT" ]; then
  assert_eq "$(tr '\n' ' ' <"$ARGV_OUT" | sed 's/ $//')" ":app:assembleDebug --offline --console=plain" "argv order"
else
  printf 'FAIL argv order: missing capture file\n' >&2
  FAIL=$((FAIL + 1))
fi

# Wrong distribution URL → no unpacked runner
BAD_URL_CASE="$TMP_BASE/badurl"
mkdir -p "$BAD_URL_CASE/tools"
cp "$GRADLE_SH" "$BAD_URL_CASE/tools/mimir-gradle.sh"
make_wrapper_props "$BAD_URL_CASE" 'https\://services.gradle.org/distributions/gradle-8.7-bin.zip'
BAD_HOME="$BAD_URL_CASE/home"
mkdir -p "$BAD_HOME"
if err="$(env -i HOME="$HOME" PATH="/usr/bin:/bin" SHELL="/bin/sh" TMPDIR="${TMPDIR:-/tmp}" GRADLE_USER_HOME="$BAD_HOME" sh "$BAD_URL_CASE/tools/mimir-gradle.sh" --version 2>&1)"; then
  printf 'FAIL bad dist url: expected failure\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "no Gradle 8.9 runner found" "bad dist url"
fi

# No-runner actionable failure (isolated copy without wrapper/dist)
NORUNNER_ROOT="$TMP_BASE/norunner"
mkdir -p "$NORUNNER_ROOT/tools"
cp "$GRADLE_SH" "$NORUNNER_ROOT/tools/mimir-gradle.sh"
EMPTY_HOME="$NORUNNER_ROOT/home"
mkdir -p "$EMPTY_HOME"
if err="$(env -i HOME="$HOME" PATH="/usr/bin:/bin" SHELL="/bin/sh" TMPDIR="${TMPDIR:-/tmp}" GRADLE_USER_HOME="$EMPTY_HOME" sh "$NORUNNER_ROOT/tools/mimir-gradle.sh" --version 2>&1)"; then
  printf 'FAIL no-runner: expected failure when no candidates\n' >&2
  FAIL=$((FAIL + 1))
else
  assert_contains "$err" "set MIMIR_GRADLE" "no-runner message"
fi

printf 'test_mimir_gradle.sh: %s passed, %s failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
