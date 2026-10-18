#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$REPO_ROOT"

node --test tools/platform-registry/platform-registry-source.test.mjs
node tools/platform-registry/generate-platform-registry.mjs --check-source
