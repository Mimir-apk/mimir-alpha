#!/usr/bin/env bash
# Sign the catalog: bash tools/family-catalog/sign.sh <catalog.json>
#
# Writes <catalog.json>.sig: the base64 (one line, no trailing newline) of the raw 64-byte
# Ed25519 signature over the file's exact bytes. It asks for the private key's path at a
# prompt. No key, password or path is taken from an env var or a flag, and a key inside a git
# work tree is refused. Do not edit the JSON after signing; any byte change breaks the signature.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_common.sh
. "$here/_common.sh"

[ "$#" -eq 1 ] || die "usage: bash tools/family-catalog/sign.sh <catalog.json>"
catalog="$1"
[ -f "$catalog" ] || die "no such file: $catalog"
require_openssl3

prompt_path "Path to the PRIVATE key file: " KEYPATH
key="$(posix_path "$KEYPATH")"
[ -f "$key" ] || die "no such key file: $key"
if inside_git_tree "$key"; then
  die "refusing: $key is inside a git work tree. Keep the private key outside every repo."
fi
if looks_cloud_synced "$key"; then
  die "refusing: $key looks like a cloud-synced folder. Keep the private key on a local-only folder."
fi

work="$(mktemp -d)" || die "could not make a temp dir"
trap 'rm -rf "$work"' EXIT
nwork="$(native_path "$work")"

nkey="$(native_path "$key")"
pub="$(pubkey_b64_from_private "$nkey" "$nwork")" \
  || die "$key is not a readable Ed25519 private key"

openssl pkeyutl -sign -inkey "$nkey" -rawin \
  -in "$(native_path "$catalog")" -out "$nwork/sig.bin" 2>/dev/null \
  || die "openssl pkeyutl -sign failed"
[ "$(( $(wc -c < "$work/sig.bin") ))" -eq 64 ] || die "signature is not 64 bytes"

sig="$catalog.sig"
printf '%s' "$(openssl base64 -A -in "$nwork/sig.bin")" > "$sig"

# Do not trust the writer: verify what is on disk against the public half of this key.
verify_catalog "$catalog" "$pub" "$nwork" || { rm -f "$sig"; die "the new signature does not verify (removed $sig)"; }

echo "sign: wrote $sig and verified it." >&2
echo "sign: signed with the key whose PUBLIC half is: $pub" >&2
echo "sign: this must equal the Tweaker's FAMILY_CATALOG_PUBLIC_KEY_BASE64." >&2
