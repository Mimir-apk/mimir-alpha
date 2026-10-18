#!/usr/bin/env bash
# Shared helpers for keygen.sh, sign.sh and verify.sh. Sourced, not run.
#
# Hard limit for every script that sources this: a private key never enters the repo, CI, an
# APK, an env var, a shell argument or a password flag. It is only ever named by a path the
# operator types at a prompt, and that path may not be inside a git work tree.
#
# Process starts are slow on Windows, so these helpers prefer bash builtins to forks.

die() {
  echo "${0##*/}: $*" >&2
  exit 1
}

require_openssl3() {
  command -v openssl >/dev/null 2>&1 \
    || die "openssl was not found on PATH (Git Bash ships one; macOS: brew install openssl@3)"
  local v
  v="$(openssl version 2>/dev/null)"
  case "$v" in
    OpenSSL\ [3-9].* | OpenSSL\ [1-9][0-9].*) ;;
    *) die "OpenSSL 3.0 or newer is required for Ed25519 -rawin signing; found: ${v:-unknown}" ;;
  esac
}

# Git Bash on Windows: accept C:\x\y or /c/x/y from the operator and hand openssl a mixed path.
posix_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -u -- "$1" 2>/dev/null || printf '%s' "$1"
  else
    printf '%s' "$1"
  fi
}
native_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m -- "$1" 2>/dev/null || printf '%s' "$1"
  else
    printf '%s' "$1"
  fi
}

# prompt_path <prompt text> <varname>: read a path from stdin (the terminal when interactive).
# The prompt goes to stderr so stdout stays clean for the one value a script prints.
prompt_path() {
  local reply
  printf '%s' "$1" >&2
  IFS= read -r reply || die "no path was given"
  reply="${reply%$'\r'}"
  case "$reply" in
    \"*\") reply="${reply#\"}"; reply="${reply%\"}" ;;
    \'*\') reply="${reply#\'}"; reply="${reply%\'}" ;;
  esac
  [ -n "$reply" ] || die "no path was given"
  printf -v "$2" '%s' "$reply"
}

# True when the path (or any ancestor directory) holds a .git entry, i.e. is in a work tree.
# Walks up by hand so it works without git and for linked worktrees (.git is a file there).
inside_git_tree() {
  local d="${1%/*}"
  [ "$d" = "$1" ] && d=.
  [ -n "$d" ] || d=/
  d="$(cd "$d" 2>/dev/null && pwd -P)" || return 2
  while :; do
    [ -e "$d/.git" ] && return 0
    { [ -z "$d" ] || [ "$d" = "/" ]; } && return 1
    d="${d%/*}"
    [ -n "$d" ] || d=/
  done
}

looks_cloud_synced() {
  local rc=1
  shopt -s nocasematch
  case "$1" in
    *onedrive* | *dropbox* | *"google drive"* | *googledrive* | *icloud* | *"mobile documents"* | *nextcloud* | *"box sync"* | *pcloud*)
      rc=0 ;;
  esac
  shopt -u nocasematch
  return $rc
}

# An Ed25519 SubjectPublicKeyInfo is a fixed 12-byte prefix then the 32-byte key. 12 bytes is a
# whole number of base64 groups, so base64(SPKI) = this prefix + base64(raw key).
SPKI_B64_PREFIX="MCowBQYDK2VwAyEA"

# pubkey_b64_from_private <native key path> <native work dir>: the raw 32-byte Ed25519 public
# key as one line of base64. Prints only that.
pubkey_b64_from_private() {
  local spki
  openssl pkey -in "$1" -pubout -outform DER -out "$2/pub.der" 2>/dev/null || return 1
  spki="$(openssl base64 -A -in "$2/pub.der" 2>/dev/null)" || return 1
  [[ "$spki" =~ ^${SPKI_B64_PREFIX}[A-Za-z0-9+/]{43}=$ ]] || return 1
  printf '%s' "${spki#"$SPKI_B64_PREFIX"}"
}

# verify_catalog <posix catalog path> <public key base64> <native work dir>
# Checks <catalog>.sig (exactly 88 base64 chars: the raw 64-byte signature, no newline) over the
# file's exact bytes. Return 0 only when the signature verifies; says why on stderr otherwise.
verify_catalog() {
  local file="$1" pub="$2" work="$3" sig="$1.sig" sigtext=""
  [ -f "$file" ] || { echo "no such file: $file" >&2; return 1; }
  [ -f "$sig" ] || { echo "no signature file: $sig" >&2; return 1; }
  # read to EOF without stripping, so a trailing newline or CR fails the strict match below
  IFS= read -r -d '' sigtext < "$sig"
  [[ "$sigtext" =~ ^[A-Za-z0-9+/]{86}==$ ]] \
    || { echo "$sig must be exactly 88 base64 characters (a raw 64-byte signature) with no newline" >&2; return 1; }
  [[ "$pub" =~ ^[A-Za-z0-9+/]{43}=$ ]] \
    || { echo "public key must be the base64 of exactly 32 raw bytes (44 characters)" >&2; return 1; }
  # DER SubjectPublicKeyInfo for the raw key, built by prepending the fixed prefix in base64.
  printf '%s' "${SPKI_B64_PREFIX}${pub}" | openssl base64 -d -A -out "$work/vpub.der" 2>/dev/null \
    || { echo "public key is not valid base64" >&2; return 1; }
  printf '%s' "$sigtext" | openssl base64 -d -A -out "$work/vsig.bin" 2>/dev/null \
    || { echo "$sig is not valid base64" >&2; return 1; }
  openssl pkeyutl -verify -pubin -keyform DER -inkey "$work/vpub.der" -rawin \
    -in "$(native_path "$file")" -sigfile "$work/vsig.bin" >/dev/null 2>&1
}
