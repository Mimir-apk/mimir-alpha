#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$REPO_ROOT"

node --test tools/platform-registry/platform-registry.test.mjs
node tools/platform-registry/generate-platform-console-art.mjs --check
node tools/platform-registry/verify-platform-registry.mjs --offline
