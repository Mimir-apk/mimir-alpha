#!/usr/bin/env python3
"""Build and inspect Mimir's immutable, per-source SHA-1 DAT index.

The on-disk ``MIMIDX1`` format is deliberately small and boring.  Every integer
is little-endian and the fixed header is exactly 192 bytes::

    0x00  char[8]  magic (``MIMIDX1\\0``)
    0x08  u16      version (1)
    0x0a  u16      header bytes (192)
    0x0c  u32      flags (SHA1_ONLY | RAW_DEFLATE_UTF8)
    0x10  u32[4]   source/platform/origin/expected-header UTF-8 byte lengths
    0x20  u32[5]   parsed/unique/ambiguous/payload/string counts
    0x34  u32      SHA record bytes (24)
    0x38  u32      payload record bytes (8)
    0x3c  u32      reserved (0)
    0x40  u64[8]   metadata/SHA/payload/string-offset/deflate offsets,
                    deflate/raw/file byte sizes
    0x80  byte[32] SHA-256 of the exact decompressed input XML
    0xa0  byte[32] SHA-256 of the canonical resolved semantic map

Metadata is the four header strings concatenated in the order above.  Every
section begins on an eight-byte boundary; inter-section padding is zero.  SHA
records are sorted and consist of the raw 20-byte SHA-1 followed by a u32
result word.  Bit 31 is the *only* ambiguity sentinel; its low 31 bits must be
zero.  Otherwise the word is a zero-based payload index.  Payloads are two u32
string IDs (canonical title, exact clone-collapse key).  The sorted, unique
UTF-8 strings are concatenated, raw-DEFLATE compressed, and addressed by a
``stringCount + 1`` u32 offset table (also zero-padded before DEFLATE).

The semantic digest is intentionally independent of storage IDs and DEFLATE
bytes.  For each sorted SHA-1 it hashes the raw 20 bytes, then ``0x01`` for an
ambiguous result, or ``0x00`` followed by u32le length + UTF-8 bytes for title
and collapse key.  This exact contract is also checked by ``inspect``.

The Logiqx reader mirrors ``LogiqxDatParser``: merged ROM/disk members and
BIOS/device/non-runnable machines are skipped, and every otherwise-usable
entry is counted.  Packing is SHA-1-only and therefore fails closed if any
such parsed entry lacks one valid 40-hex SHA-1.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zlib


MAGIC = b"MIMIDX1\0"
VERSION = 1
HEADER_BYTES = 192
FLAG_SHA1_ONLY = 1 << 0
FLAG_RAW_DEFLATE_UTF8 = 1 << 1
FLAGS = FLAG_SHA1_ONLY | FLAG_RAW_DEFLATE_UTF8
SHA_RECORD_BYTES = 24
PAYLOAD_RECORD_BYTES = 8
AMBIGUOUS_WORD = 1 << 31
U32_MAX = (1 << 32) - 1
PAYLOAD_INDEX_MAX = AMBIGUOUS_WORD - 1
SHA1_RE = re.compile(r"[0-9a-fA-F]{40}\Z")

# 8 + 2 + 2 + 4 + (4 * 4) + (5 * 4) + (3 * 4) + (8 * 8) + 32 + 32 = 192.
HEADER = struct.Struct("<8sHHI4I5I3I8Q32s32s")
U32 = struct.Struct("<I")
PAYLOAD = struct.Struct("<II")


class PackError(ValueError):
    """A source or packed index violated an immutable format invariant."""


def _blank(value: str | None) -> bool:
    return value is None or not value.strip()


def _tag(raw: str) -> str:
    # Real Logiqx DATs are unnamespaced.  Accepting a namespace here retains
    # the element's qname-like local portion without changing attribute rules.
    return raw.rsplit("}", 1)[-1]


def _read_xml(path: Path) -> bytes:
    try:
        encoded = path.read_bytes()
    except OSError as exc:
        raise PackError(f"cannot read input {path}: {exc}") from exc
    if encoded.startswith(b"\x1f\x8b"):
        try:
            return gzip.decompress(encoded)
        except (OSError, EOFError) as exc:
            raise PackError(f"invalid gzip input {path}: {exc}") from exc
    return encoded


def _parse_logiqx(xml: bytes) -> tuple[str | None, list[tuple[bytes, str, str]]]:
    """Return (header name, SHA/title/collapse entries) with Kotlin parser parity."""
    entries: list[tuple[bytes, str, str]] = []
    header_name: str | None = None
    in_header = False
    reading_header_name = False
    game_name: str | None = None
    clone_of: str | None = None
    skip_machine = False
    parsed_count = 0

    try:
        events = ET.iterparse(io.BytesIO(xml), events=("start", "end"))
        for event, elem in events:
            tag = _tag(elem.tag)
            if event == "start":
                if tag == "header":
                    in_header = True
                elif tag == "name" and in_header and header_name is None:
                    reading_header_name = True
                elif tag in ("game", "machine"):
                    game_name = elem.attrib.get("name")
                    clone_of = elem.attrib.get("cloneof")
                    skip_machine = (
                        elem.attrib.get("isbios") == "yes"
                        or elem.attrib.get("isdevice") == "yes"
                        or elem.attrib.get("runnable") == "no"
                    )
                elif tag == "rom" and not skip_machine:
                    merge = elem.attrib.get("merge")
                    crc = elem.attrib.get("crc")
                    sha1 = elem.attrib.get("sha1")
                    # LogiqxDatParser admits a ROM carrying either usable hash.
                    if game_name is not None and _blank(merge) and (not _blank(crc) or not _blank(sha1)):
                        if not game_name or clone_of == "":
                            raise PackError("usable <rom> has an empty game name or clone-collapse key")
                        parsed_count += 1
                        if sha1 is None or SHA1_RE.fullmatch(sha1) is None:
                            raise PackError(
                                f"parsed entry {parsed_count} ({game_name!r}, <rom>) lacks a valid SHA-1"
                            )
                        collapse = clone_of if clone_of is not None else game_name
                        entries.append((bytes.fromhex(sha1), game_name, collapse))
                elif tag == "disk" and not skip_machine:
                    merge = elem.attrib.get("merge")
                    sha1 = elem.attrib.get("sha1")
                    # LogiqxDatParser admits disks only when sha1 is non-blank.
                    if game_name is not None and _blank(merge) and not _blank(sha1):
                        if not game_name or clone_of == "":
                            raise PackError("usable <disk> has an empty game name or clone-collapse key")
                        parsed_count += 1
                        if sha1 is None or SHA1_RE.fullmatch(sha1) is None:
                            raise PackError(
                                f"parsed entry {parsed_count} ({game_name!r}, <disk>) lacks a valid SHA-1"
                            )
                        collapse = clone_of if clone_of is not None else game_name
                        entries.append((bytes.fromhex(sha1), game_name, collapse))
            else:
                if tag == "name" and reading_header_name:
                    candidate = "".join(elem.itertext()).strip()
                    header_name = candidate if candidate else None
                    reading_header_name = False
                elif tag == "header":
                    in_header = False
                elif tag in ("game", "machine"):
                    game_name = None
                    clone_of = None
                    skip_machine = False
                elem.clear()
    except ET.ParseError as exc:
        raise PackError(f"invalid Logiqx XML: {exc}") from exc

    # An assertion rather than a derived count keeps future parser edits from
    # silently admitting an entry without putting it in the SHA-only result.
    if parsed_count != len(entries):
        raise PackError("internal error: parsed-entry/SHA-entry counts diverged")
    return header_name, entries


def _resolved_map(
    entries: list[tuple[bytes, str, str]],
) -> list[tuple[bytes, tuple[str, str] | None]]:
    """Apply DatStore's exact SHA collision and clone-collapse rule."""
    grouped: dict[bytes, list[tuple[str, str]]] = {}
    for sha1, title, collapse in entries:
        grouped.setdefault(sha1, []).append((title, collapse))

    resolved: list[tuple[bytes, tuple[str, str] | None]] = []
    for sha1 in sorted(grouped):
        candidates = grouped[sha1]
        collapse_keys = {collapse for _title, collapse in candidates}
        # DatStore returns candidates.first() only when every candidate has the
        # same exact collapse key.  Any other collision fails closed.
        result = candidates[0] if len(collapse_keys) == 1 else None
        resolved.append((sha1, result))
    return resolved


def _semantic_digest(
    resolved: list[tuple[bytes, tuple[str, str] | None]],
) -> bytes:
    digest = hashlib.sha256()
    previous: bytes | None = None
    for sha1, result in resolved:
        if len(sha1) != 20 or (previous is not None and sha1 <= previous):
            raise PackError("semantic map SHA-1 records are not strictly sorted")
        previous = sha1
        digest.update(sha1)
        if result is None:
            digest.update(b"\x01")
            continue
        digest.update(b"\x00")
        for value in result:
            encoded = value.encode("utf-8")
            if len(encoded) > U32_MAX:
                raise PackError("semantic string exceeds u32 length")
            digest.update(U32.pack(len(encoded)))
            digest.update(encoded)
    return digest.digest()


def _raw_deflate(data: bytes) -> bytes:
    compressor = zlib.compressobj(
        level=9,
        method=zlib.DEFLATED,
        wbits=-zlib.MAX_WBITS,
        memLevel=9,
        strategy=zlib.Z_DEFAULT_STRATEGY,
    )
    return compressor.compress(data) + compressor.flush(zlib.Z_FINISH)


def _checked_u32(label: str, value: int) -> int:
    if not 0 <= value <= U32_MAX:
        raise PackError(f"{label} does not fit u32: {value}")
    return value


def _align8(value: int) -> int:
    return (value + 7) & ~7


def _build_index(
    xml: bytes,
    *,
    source_id: str,
    platform_id: str,
    origin: str,
    expected_header: str,
) -> bytes:
    for label, value in (
        ("source-id", source_id),
        ("platform-id", platform_id),
        ("origin", origin),
        ("expected-header", expected_header),
    ):
        if _blank(value) or "\0" in value:
            raise PackError(f"{label} is blank or contains a NUL")

    actual_header, entries = _parse_logiqx(xml)
    if actual_header != expected_header:
        raise PackError(
            f"header mismatch: expected {expected_header!r}, source contains {actual_header!r}"
        )
    if not entries:
        raise PackError("source produced no usable SHA-1 entries")

    resolved = _resolved_map(entries)
    ambiguous_count = sum(result is None for _sha1, result in resolved)

    payload_values = sorted(
        {result for _sha1, result in resolved if result is not None},
        key=lambda pair: (pair[0].encode("utf-8"), pair[1].encode("utf-8")),
    )
    if len(payload_values) > PAYLOAD_INDEX_MAX + 1:
        raise PackError("payload count exceeds the 31-bit result-word space")
    payload_ids = {value: index for index, value in enumerate(payload_values)}

    string_values = sorted(
        {value for pair in payload_values for value in pair},
        key=lambda value: value.encode("utf-8"),
    )
    string_ids = {value: index for index, value in enumerate(string_values)}
    string_pool = bytearray()
    string_offsets = [0]
    for value in string_values:
        string_pool.extend(value.encode("utf-8"))
        string_offsets.append(len(string_pool))
    _checked_u32("raw string-pool bytes", len(string_pool))
    compressed_strings = _raw_deflate(bytes(string_pool))

    metadata_values = (source_id, platform_id, origin, expected_header)
    metadata_parts = tuple(value.encode("utf-8") for value in metadata_values)
    metadata_lengths = tuple(_checked_u32("metadata string bytes", len(part)) for part in metadata_parts)
    metadata = b"".join(metadata_parts)

    sha_records = bytearray()
    for sha1, result in resolved:
        word = AMBIGUOUS_WORD if result is None else payload_ids[result]
        sha_records.extend(sha1)
        sha_records.extend(U32.pack(word))

    payload_records = b"".join(
        PAYLOAD.pack(string_ids[title], string_ids[collapse])
        for title, collapse in payload_values
    )
    offset_records = b"".join(U32.pack(offset) for offset in string_offsets)

    parsed_count = _checked_u32("parsed-entry count", len(entries))
    unique_count = _checked_u32("unique-SHA count", len(resolved))
    ambiguous_count = _checked_u32("ambiguous-SHA count", ambiguous_count)
    payload_count = _checked_u32("payload count", len(payload_values))
    string_count = _checked_u32("string count", len(string_values))

    metadata_offset = HEADER_BYTES
    metadata_end = metadata_offset + len(metadata)
    sha_offset = _align8(metadata_end)
    payload_offset = sha_offset + len(sha_records)
    string_offsets_offset = payload_offset + len(payload_records)
    string_offsets_end = string_offsets_offset + len(offset_records)
    deflate_offset = _align8(string_offsets_end)
    file_size = deflate_offset + len(compressed_strings)

    header = HEADER.pack(
        MAGIC,
        VERSION,
        HEADER_BYTES,
        FLAGS,
        *metadata_lengths,
        parsed_count,
        unique_count,
        ambiguous_count,
        payload_count,
        string_count,
        SHA_RECORD_BYTES,
        PAYLOAD_RECORD_BYTES,
        0,
        metadata_offset,
        sha_offset,
        payload_offset,
        string_offsets_offset,
        deflate_offset,
        len(compressed_strings),
        len(string_pool),
        file_size,
        hashlib.sha256(xml).digest(),
        _semantic_digest(resolved),
    )
    packed = b"".join(
        (
            header,
            metadata,
            bytes(sha_offset - metadata_end),
            bytes(sha_records),
            payload_records,
            offset_records,
            bytes(deflate_offset - string_offsets_end),
            compressed_strings,
        )
    )
    if len(packed) != file_size:
        raise PackError("internal error: packed file size diverged")
    return packed


def _inflate_raw(encoded: bytes) -> bytes:
    inflater = zlib.decompressobj(wbits=-zlib.MAX_WBITS)
    try:
        decoded = inflater.decompress(encoded) + inflater.flush()
    except zlib.error as exc:
        raise PackError(f"invalid raw-DEFLATE string pool: {exc}") from exc
    if not inflater.eof or inflater.unused_data or inflater.unconsumed_tail:
        raise PackError("raw-DEFLATE string pool is truncated or has trailing data")
    return decoded


def _decode_metadata(data: bytes, offset: int, lengths: tuple[int, ...]) -> tuple[str, ...]:
    values: list[str] = []
    cursor = offset
    try:
        for length in lengths:
            values.append(data[cursor : cursor + length].decode("utf-8"))
            cursor += length
    except UnicodeDecodeError as exc:
        raise PackError(f"metadata is not valid UTF-8: {exc}") from exc
    return tuple(values)


def _inspect_bytes(data: bytes) -> dict[str, object]:
    if len(data) < HEADER_BYTES:
        raise PackError(f"index is truncated: {len(data)} bytes")
    fields = HEADER.unpack_from(data)
    (
        magic,
        version,
        header_bytes,
        flags,
        source_len,
        platform_len,
        origin_len,
        expected_header_len,
        parsed_count,
        unique_count,
        ambiguous_count,
        payload_count,
        string_count,
        sha_record_bytes,
        payload_record_bytes,
        reserved,
        metadata_offset,
        sha_offset,
        payload_offset,
        string_offsets_offset,
        deflate_offset,
        deflate_size,
        string_raw_size,
        file_size,
        input_digest,
        stored_semantic_digest,
    ) = fields

    if magic != MAGIC:
        raise PackError(f"bad magic: {magic!r}")
    if version != VERSION or header_bytes != HEADER_BYTES:
        raise PackError(f"unsupported version/header: {version}/{header_bytes}")
    if flags != FLAGS:
        raise PackError(f"unsupported flags: 0x{flags:08x}")
    if sha_record_bytes != SHA_RECORD_BYTES or payload_record_bytes != PAYLOAD_RECORD_BYTES:
        raise PackError("record-size contract mismatch")
    if reserved != 0:
        raise PackError("reserved header field is nonzero")
    if file_size != len(data):
        raise PackError(f"file-size contract mismatch: header={file_size}, actual={len(data)}")
    if (
        parsed_count < unique_count
        or ambiguous_count > unique_count
        or payload_count > unique_count - ambiguous_count
    ):
        raise PackError("impossible count relationship")

    metadata_size = source_len + platform_len + origin_len + expected_header_len
    canonical_metadata_offset = HEADER_BYTES
    metadata_end = canonical_metadata_offset + metadata_size
    canonical_sha_offset = _align8(metadata_end)
    canonical_payload_offset = canonical_sha_offset + unique_count * SHA_RECORD_BYTES
    canonical_string_offsets_offset = canonical_payload_offset + payload_count * PAYLOAD_RECORD_BYTES
    string_offsets_end = canonical_string_offsets_offset + (string_count + 1) * U32.size
    canonical_deflate_offset = _align8(string_offsets_end)
    expected_offsets = (
        canonical_metadata_offset,
        canonical_sha_offset,
        canonical_payload_offset,
        canonical_string_offsets_offset,
        canonical_deflate_offset,
    )
    actual_offsets = (
        metadata_offset,
        sha_offset,
        payload_offset,
        string_offsets_offset,
        deflate_offset,
    )
    if actual_offsets != expected_offsets:
        raise PackError(f"non-canonical section offsets: {actual_offsets!r}")
    if any(offset % 8 for offset in actual_offsets):
        raise PackError("a section does not begin on an eight-byte boundary")
    if deflate_offset + deflate_size != file_size:
        raise PackError("deflate section does not end at file-size boundary")
    if any(data[metadata_end:sha_offset]) or any(data[string_offsets_end:deflate_offset]):
        raise PackError("inter-section padding is nonzero")

    source_id, platform_id, origin, expected_header = _decode_metadata(
        data,
        metadata_offset,
        (source_len, platform_len, origin_len, expected_header_len),
    )

    encoded_pool = data[deflate_offset:file_size]
    string_pool = _inflate_raw(encoded_pool)
    if len(string_pool) != string_raw_size:
        raise PackError("inflated string-pool size mismatch")

    offsets = [
        U32.unpack_from(data, string_offsets_offset + index * U32.size)[0]
        for index in range(string_count + 1)
    ]
    if not offsets or offsets[0] != 0 or offsets[-1] != string_raw_size:
        raise PackError("string-offset endpoints are invalid")
    if any(left >= right for left, right in zip(offsets, offsets[1:])):
        raise PackError("strings must be non-empty with strictly increasing offsets")
    strings: list[tuple[str, bytes]] = []
    try:
        for left, right in zip(offsets, offsets[1:]):
            raw = string_pool[left:right]
            strings.append((raw.decode("utf-8"), raw))
    except UnicodeDecodeError as exc:
        raise PackError(f"string pool is not valid UTF-8: {exc}") from exc
    string_bytes = [raw for _value, raw in strings]
    if any(left >= right for left, right in zip(string_bytes, string_bytes[1:])):
        raise PackError("string table is not strictly UTF-8-byte sorted")

    payloads: list[tuple[str, str]] = []
    payload_sort_keys: list[tuple[bytes, bytes]] = []
    for index in range(payload_count):
        title_id, collapse_id = PAYLOAD.unpack_from(
            data, payload_offset + index * PAYLOAD_RECORD_BYTES
        )
        if title_id >= string_count or collapse_id >= string_count:
            raise PackError(f"payload {index} references an invalid string ID")
        payloads.append((strings[title_id][0], strings[collapse_id][0]))
        payload_sort_keys.append((strings[title_id][1], strings[collapse_id][1]))
    if any(left >= right for left, right in zip(payload_sort_keys, payload_sort_keys[1:])):
        raise PackError("payload table is not strictly canonical-sorted")

    resolved: list[tuple[bytes, tuple[str, str] | None]] = []
    referenced_payloads: set[int] = set()
    actual_ambiguous = 0
    previous_sha: bytes | None = None
    for index in range(unique_count):
        record_offset = sha_offset + index * SHA_RECORD_BYTES
        sha1 = data[record_offset : record_offset + 20]
        word = U32.unpack_from(data, record_offset + 20)[0]
        if previous_sha is not None and sha1 <= previous_sha:
            raise PackError("SHA records are not strictly sorted")
        previous_sha = sha1
        if word & AMBIGUOUS_WORD:
            if word != AMBIGUOUS_WORD:
                raise PackError("ambiguity result uses bits other than the one-bit sentinel")
            result = None
            actual_ambiguous += 1
        else:
            if word >= payload_count:
                raise PackError(f"SHA record {index} references an invalid payload")
            referenced_payloads.add(word)
            result = payloads[word]
        resolved.append((sha1, result))
    if actual_ambiguous != ambiguous_count:
        raise PackError("ambiguous count does not match SHA records")
    if referenced_payloads != set(range(payload_count)):
        raise PackError("payload table contains an unreferenced record")
    semantic_digest = _semantic_digest(resolved)
    if semantic_digest != stored_semantic_digest:
        raise PackError("semantic-map digest mismatch")

    return {
        "format": "MIMIDX1",
        "version": version,
        "source_id": source_id,
        "platform_id": platform_id,
        "origin": origin,
        "expected_header": expected_header,
        "parsed_entry_count": parsed_count,
        "unique_sha1_count": unique_count,
        "ambiguous_sha1_count": ambiguous_count,
        "resolved_sha1_count": unique_count - ambiguous_count,
        "distinct_collapse_key_count": len({collapse for _title, collapse in payloads}),
        "payload_count": payload_count,
        "string_count": string_count,
        "string_raw_bytes": string_raw_size,
        "string_deflate_bytes": deflate_size,
        "file_bytes": file_size,
        "input_xml_sha256": input_digest.hex(),
        "semantic_map_sha256": semantic_digest.hex(),
    }


def _verify_against_input(
    stats: dict[str, object],
    packed: bytes,
    input_path: Path,
) -> None:
    xml = _read_xml(input_path)
    if hashlib.sha256(xml).hexdigest() != stats["input_xml_sha256"]:
        raise PackError("verification input XML digest does not match the index")
    rebuilt = _build_index(
        xml,
        source_id=str(stats["source_id"]),
        platform_id=str(stats["platform_id"]),
        origin=str(stats["origin"]),
        expected_header=str(stats["expected_header"]),
    )
    if rebuilt != packed:
        raise PackError("verification input rebuild is not byte-identical")


def _write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    try:
        with temporary.open("xb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except OSError as exc:
        raise PackError(f"cannot write output {path}: {exc}") from exc
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _pack_command(args: argparse.Namespace) -> dict[str, object]:
    input_path = Path(args.input)
    output_path = Path(args.output)
    xml = _read_xml(input_path)
    packed = _build_index(
        xml,
        source_id=args.source_id,
        platform_id=args.platform_id,
        origin=args.origin,
        expected_header=args.expected_header,
    )
    # Parse every byte and recompute the semantic digest before allowing it to
    # become an output.  This is the emitter's mandatory self-verification.
    stats = _inspect_bytes(packed)
    _write_atomic(output_path, packed)
    return {**stats, "input": str(input_path), "output": str(output_path)}


def _inspect_command(args: argparse.Namespace) -> dict[str, object]:
    path = Path(args.index)
    try:
        packed = path.read_bytes()
    except OSError as exc:
        raise PackError(f"cannot read index {path}: {exc}") from exc
    stats = _inspect_bytes(packed)
    if args.input is not None:
        _verify_against_input(stats, packed, Path(args.input))
        stats["input_verified"] = True
    return {**stats, "index": str(path)}


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)

    pack = subcommands.add_parser("pack", help="pack one .datgz/.gz or XML source")
    pack.add_argument("input", help="input Logiqx XML, optionally gzip-compressed")
    pack.add_argument("output", help="output .midx path")
    pack.add_argument("--source-id", required=True, help="stable logical source ID, e.g. snes.datgz")
    pack.add_argument("--platform-id", required=True, help="resolver platform ID")
    pack.add_argument("--origin", required=True, help="metadata origin enum/name")
    pack.add_argument(
        "--expected-header",
        required=True,
        help="exact <header><name> value required from the source",
    )
    pack.set_defaults(run=_pack_command)

    inspect = subcommands.add_parser("inspect", help="strictly inspect and verify one .midx")
    inspect.add_argument("index", help="packed .midx path")
    inspect.add_argument(
        "--input",
        help="optionally rebuild from this XML/.gz and require byte identity",
    )
    inspect.set_defaults(run=_inspect_command)
    return parser


def main(argv: list[str] | None = None) -> int:
    try:
        args = _parser().parse_args(argv)
        stats = args.run(args)
        print(json.dumps(stats, indent=2, sort_keys=True))
        return 0
    except (PackError, IndexError, OverflowError, struct.error, UnicodeError) as exc:
        print(f"pack-dats.py: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
