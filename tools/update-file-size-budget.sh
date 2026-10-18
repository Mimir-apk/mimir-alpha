#!/bin/bash
# Regenerates the source file-size budget: every main Kotlin file over 2000 lines, at its current line count.
# Run from the repo root. Commit the result with a reason whenever a budget is raised.
set -e
OUT=app/src/test/resources/source-file-size-budget.txt
{
  echo "# path<TAB>max-lines. Files not listed may have at most 2000 lines. Regenerate with tools/update-file-size-budget.sh."
  find . -path ./build -prune -o -type f -name '*.kt' -path '*/src/main/kotlin/*' -not -path '*/build/*' -print \
    | sed 's#^\./##' | LC_ALL=C sort | while IFS= read -r f; do
      n=$(awk 'END{print NR}' "$f")
      if [ "$n" -gt 2000 ]; then printf '%s\t%s\n' "$f" "$n"; fi
    done
} > "$OUT"
echo "wrote $OUT ($(grep -vc '^#' "$OUT") files)"
