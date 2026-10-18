#!/usr/bin/env python3
"""Seal the emulator definitions: recompute the checksums and write the files in the pinned layout.

Edit the rows in definitions.json (and bump "ordinal"), then run:

    python seal.py            # rewrite definitions.json and pointer.json
    python seal.py --check    # change nothing; exit 1 if either file is not already sealed

What it does, and nothing else:
  1. reads definitions.json (a closed shape: schema, ordinal, sha256, sig, body.rows);
  2. recomputes the inner "sha256": SHA-256 of the CANONICAL body (sorted keys, no spaces, ASCII);
  3. writes definitions.json back, one row per line, keys sorted, LF line ends, one final newline;
  4. writes pointer.json: this file's ordinal, the tag "defs-<ordinal>" and the SHA-256 of the EXACT bytes of
     definitions.json, which is what `sha256sum definitions.json` prints and what the app pins.

Row order is kept as written. Python 3.8+, standard library only. The app validates everything again.
"""

import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFINITIONS = HERE / "definitions.json"
POINTER = HERE / "pointer.json"


def canonical(value) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def render_definitions(doc: dict) -> str:
    rows = doc["body"]["rows"]
    body_sha = sha256_hex(canonical(doc["body"]).encode("ascii"))
    lines = ["{", '  "body": {', '    "rows": [']
    for i, row in enumerate(rows):
        lines.append("      " + canonical(row) + ("," if i < len(rows) - 1 else ""))
    lines += [
        "    ]",
        "  },",
        f'  "ordinal": {int(doc["ordinal"])},',
        f'  "schema": {int(doc["schema"])},',
        f'  "sha256": "{body_sha}",',
        '  "sig": null',
        "}",
    ]
    return "\n".join(lines) + "\n"


def render_pointer(doc: dict, definitions_bytes: bytes) -> str:
    ordinal = int(doc["ordinal"])
    return "\n".join(
        [
            "{",
            f'  "ordinal": {ordinal},',
            f'  "schema": {int(doc["schema"])},',
            f'  "sha256": "{sha256_hex(definitions_bytes)}",',
            '  "sig": null,',
            f'  "tag": "defs-{ordinal}"',
            "}",
        ]
    ) + "\n"


def main(argv: list[str]) -> int:
    check = "--check" in argv
    doc = json.loads(DEFINITIONS.read_text(encoding="utf-8"))
    if doc.get("sig") is not None:
        print("definitions.json carries a signature this script does not produce; refusing to touch it", file=sys.stderr)
        return 2
    definitions = render_definitions(doc).encode("ascii")
    pointer = render_pointer(doc, definitions).encode("ascii")

    current_definitions = DEFINITIONS.read_bytes()
    current_pointer = POINTER.read_bytes() if POINTER.exists() else b""
    if check:
        ok = current_definitions == definitions and current_pointer == pointer
        print("sealed" if ok else "NOT sealed: run python seal.py")
        return 0 if ok else 1

    # newline="" style writes: bytes go to disk exactly as built, so LF stays LF on Windows.
    DEFINITIONS.write_bytes(definitions)
    POINTER.write_bytes(pointer)
    print(f"definitions.json  sha256 {sha256_hex(definitions)}")
    print(f"pointer.json      names defs-{int(doc['ordinal'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
