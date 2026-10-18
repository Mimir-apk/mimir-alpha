#!/usr/bin/env bash
# Verify a signed catalog: bash tools/family-catalog/verify.sh <catalog.json> <public-key-base64>
#
# Checks <catalog.json>.sig against the raw-32-byte, base64 public key (the value of the
# Tweaker's FAMILY_CATALOG_PUBLIC_KEY_BASE64). Exit 0 when it verifies, 1 otherwise.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_common.sh
. "$here/_common.sh"

[ "$#" -eq 2 ] || die "usage: bash tools/family-catalog/verify.sh <catalog.json> <public-key-base64>"
require_openssl3
work="$(mktemp -d)" || die "could not make a temp dir"
trap 'rm -rf "$work"' EXIT

if verify_catalog "$1" "$2" "$(native_path "$work")"; then
  echo "verify: OK ($1)"
  exit 0
fi
echo "verify: FAILED ($1)" >&2
exit 1
