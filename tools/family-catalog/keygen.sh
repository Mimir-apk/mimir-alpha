#!/usr/bin/env bash
# One-time: make the catalog signing key. Run it yourself, in a terminal.
#
#   bash tools/family-catalog/keygen.sh
#
# It asks where to put the PRIVATE key, creates an Ed25519 key there, and prints only the
# PUBLIC key (base64 of the raw 32 bytes) on stdout. That value is the Tweaker's BuildConfig
# FAMILY_CATALOG_PUBLIC_KEY_BASE64. The private key is never printed and is never read from an
# env var or a flag. It must NOT live in the repo or in a cloud-synced folder.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_common.sh
. "$here/_common.sh"
require_openssl3

cat >&2 <<'MSG'
Catalog signing key (Ed25519). Choose a folder that is
  - outside every git work tree (never this repo), and
  - not synced to a cloud (no OneDrive, Dropbox, Google Drive, iCloud).
Back the file up offline; losing it means shipping a new public key in an APK.
MSG
prompt_path "Where should the PRIVATE key be written? (a new file; the folder must exist) " KEYPATH

key="$(posix_path "$KEYPATH")"
dir="${key%/*}"
[ -d "${dir:-/}" ] || die "the folder $dir does not exist"
[ ! -e "$key" ] || die "refusing to overwrite $key"
if inside_git_tree "$key"; then
  die "refusing: $key is inside a git work tree. A private key must never sit in a repo."
fi
if looks_cloud_synced "$key"; then
  die "refusing: $key looks like a cloud-synced folder. Pick a local-only folder."
fi

work="$(mktemp -d)" || die "could not make a temp dir"
trap 'rm -rf "$work"' EXIT
nwork="$(native_path "$work")"

nkey="$(native_path "$key")"
umask 077
if ! openssl genpkey -algorithm ed25519 -out "$nkey" 2>/dev/null; then
  [ -s "$key" ] || rm -f "$key"
  die "openssl genpkey failed"
fi
chmod 600 "$key" 2>/dev/null
if command -v icacls >/dev/null 2>&1 && [ -n "${USERNAME:-}" ]; then
  # Windows: drop inherited access, leave only the current user.
  icacls "$nkey" /inheritance:r /grant:r "${USERNAME}:F" >/dev/null 2>&1 \
    || echo "keygen: could not tighten the Windows ACL on $key; do it by hand" >&2
fi

pub="$(pubkey_b64_from_private "$nkey" "$nwork")" \
  || { rm -f "$key"; die "could not derive the public key (removed the new key file)"; }

echo "keygen: wrote the private key to $key (not printed)." >&2
echo "keygen: the PUBLIC key, for FAMILY_CATALOG_PUBLIC_KEY_BASE64, is the line below." >&2
printf '%s\n' "$pub"
