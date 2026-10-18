#!/usr/bin/env sh
# POSIX Gradle launcher for Mimir-rewrite quality gates.
# Resolution: MIMIR_GRADLE → GRADLE → PATH gradle (exact 8.9) → warmed unpacked wrapper dist.
# Never executes ./gradlew.

set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
WRAPPER="$ROOT/gradlew"
WRAPPER_PROPS="$ROOT/gradle/wrapper/gradle-wrapper.properties"

GRADLE_BIN=""
GRADLE_VERSION=""
GRADLE_SOURCE=""

die() {
  printf 'mimir-gradle: %s\n' "$1" >&2
  exit 1
}

gradle_version() {
  candidate="$1"
  if [ ! -x "$candidate" ]; then
    return 1
  fi
  version_file="$(mktemp "${TMPDIR:-/tmp}/mimir-gradle-ver.XXXXXX")"
  if ! "$candidate" --version >"$version_file" 2>/dev/null; then
    rm -f "$version_file"
    return 1
  fi
  awk '/^Gradle / { print $2; exit }' "$version_file"
  rm -f "$version_file"
}

validate_gradle_89() {
  candidate="$1"
  label="$2"
  if [ ! -x "$candidate" ]; then
    die "$label is set but not executable: $candidate"
  fi
  reject_repo_wrapper "$candidate" "$label"
  version="$(gradle_version "$candidate")" || die "$label does not report a Gradle version: $candidate"
  if [ "$version" != "8.9" ]; then
    die "$label must be Gradle 8.9 (found $version): $candidate"
  fi
  GRADLE_BIN="$candidate"
  GRADLE_VERSION="$version"
  GRADLE_SOURCE="$label"
}

reject_repo_wrapper() {
  candidate="$1"
  label="$2"
  if [ ! -e "$candidate" ]; then
    return 0
  fi
  if [ -e "$WRAPPER" ] && { [ "$candidate" -ef "$WRAPPER" ] || cmp -s "$candidate" "$WRAPPER"; }; then
    die "$label must not point at repository ./gradlew; use an unpacked Gradle 8.9 binary"
  fi
}

parse_wrapper_dist() {
  if [ ! -f "$WRAPPER_PROPS" ]; then
    return 1
  fi
  dist_url="$(awk -F= '/^distributionUrl=/ { sub(/^distributionUrl=/, ""); gsub(/\\:/, ":"); print; exit }' "$WRAPPER_PROPS")"
  dist_path="$(awk -F= '/^distributionPath=/ { sub(/^distributionPath=/, ""); print; exit }' "$WRAPPER_PROPS")"
  dist_base="$(awk -F= '/^distributionBase=/ { sub(/^distributionBase=/, ""); print; exit }' "$WRAPPER_PROPS")"
  if [ -z "$dist_url" ] || [ -z "$dist_path" ]; then
    return 1
  fi
  case "$dist_url" in
    *gradle-8.9-bin.zip*)
      dist_flavor="gradle-8.9-bin"
      ;;
    *gradle-8.9-all.zip*)
      dist_flavor="gradle-8.9-all"
      ;;
    *)
      return 1
      ;;
  esac
  case "$dist_base" in
    GRADLE_USER_HOME|"")
      dist_root="${GRADLE_USER_HOME:-$HOME/.gradle}/$dist_path/$dist_flavor"
      ;;
    *)
      return 1
      ;;
  esac
  if [ ! -d "$dist_root" ]; then
    return 1
  fi
  for hash_dir in "$dist_root"/*; do
    [ -d "$hash_dir" ] || continue
    ok_file="$hash_dir/${dist_flavor}.zip.ok"
    gradle_bin="$hash_dir/gradle-8.9/bin/gradle"
    if [ ! -f "$ok_file" ] || [ ! -x "$gradle_bin" ]; then
      continue
    fi
    if [ -e "$WRAPPER" ] && { [ "$gradle_bin" -ef "$WRAPPER" ] || cmp -s "$gradle_bin" "$WRAPPER"; }; then
      continue
    fi
    version="$(gradle_version "$gradle_bin")" || continue
    if [ "$version" = "8.9" ]; then
      GRADLE_BIN="$gradle_bin"
      GRADLE_VERSION="$version"
      GRADLE_SOURCE="unpacked-wrapper-dist"
      return 0
    fi
  done
  return 1
}

resolve_gradle() {
  if [ -n "${MIMIR_GRADLE:-}" ]; then
    validate_gradle_89 "$MIMIR_GRADLE" "MIMIR_GRADLE"
    return 0
  fi

  if [ -n "${GRADLE:-}" ]; then
    validate_gradle_89 "$GRADLE" "GRADLE"
    return 0
  fi

  path_gradle="$(command -v gradle 2>/dev/null || true)"
  if [ -n "$path_gradle" ]; then
    validate_gradle_89 "$path_gradle" "PATH"
    return 0
  fi

  if parse_wrapper_dist; then
    return 0
  fi

  die "no Gradle 8.9 runner found; set MIMIR_GRADLE, set GRADLE, install Gradle 8.9 on PATH, or warm the Gradle 8.9 wrapper distribution"
}

resolve_gradle
cd "$ROOT"
printf 'mimir-gradle: runner=%s version=%s source=%s\n' "$GRADLE_BIN" "$GRADLE_VERSION" "$GRADLE_SOURCE" >&2
exec "$GRADLE_BIN" "$@"
