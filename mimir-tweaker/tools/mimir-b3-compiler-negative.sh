#!/bin/sh
set -eu

# Checked-in B3 compiler-negative gate. It deliberately uses Gradle's normal test compilation
# (including its friend path), so `internal` cannot mask a private/capability regression.
tweaker_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture="$tweaker_root/app/src/test/fixtures/B3ForgeAttempts.kt.fixture"
target="$tweaker_root/app/src/test/kotlin/com/gruning/mimir/tweaker/B3ForgeAttempts.kt"
output=$(mktemp -t mimir-b3-forge.XXXXXX)
trap 'rm -f "$target" "$output"' EXIT HUP INT TERM

test -f "$fixture"
cp "$fixture" "$target"
if "$tweaker_root/../gradlew" -p "$tweaker_root" :app:compileProductionDebugUnitTestKotlin --offline >"$output" 2>&1; then
  cat "$output"
  echo "B3 forge fixture unexpectedly compiled" >&2
  exit 1
fi
cat "$output"
for marker in AVAILABLE_CTOR AVAILABLE_COPY_COMPONENT AVAILABLE_SUBCLASS CAPABILITY_CTOR_AND_SOURCE INJECTABLE_AUTHORITY_CTOR PRIVATE_MINTER OLD_ENVELOPE_FROM OLD_JOURNAL_FROM RAW_AVAILABLE_FIELDS REMOVED_SIGN_BOUND FABRICATED_ENVELOPE_VERIFIER FABRICATED_JOURNAL_VERIFIER SEALED_TRUTH_SUBCLASS FORGED_MGDE_RECORD FORGED_MGDE_SOURCE FORGED_MGDE_SOURCE_SUBCLASS; do
  # A marker may precede an explanatory comment. Find the first actual source line after
  # it, rather than relying on a brittle fixed +1 offset.
  line=$(awk -v marker="$marker" '
    index($0, "B3_NEGATIVE:" marker) { marked = 1; next }
    marked && $0 !~ /^[[:space:]]*$/ && $0 !~ /^[[:space:]]*\/\// { print NR; exit }
  ' "$fixture")
  test -n "$line"
  grep -q "B3ForgeAttempts.kt:$line" "$output"
done
