#!/usr/bin/env python3
"""Build Mimir's exact MAME payload index from an official release XML.

The input is metadata only: MAME's release-pinned full driver information (`-listxml`) archive.
No ROM path, filename or corpus observation influences the output.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import gzip
import hashlib
import io
from pathlib import Path
import re
import struct
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


MAME_RELEASE = "0.287"
MAME_TAG = "mame0287"
SOURCE_NAME = "mame0287lx.zip"
SOURCE_URL = (
    "https://github.com/mamedev/mame/releases/download/"
    f"{MAME_TAG}/{SOURCE_NAME}"
)
SOURCE_SHA256 = "60444261c6f2378ddf026237b91db4f01a56e83f0597d076c5a6ccc8f734eecd"
OUTPUT_NAME = "mame0287.mpaygz"
MEMBER_OUTPUT_NAME = "mame0287.mmemgz"
DEPENDENCY_OUTPUT_NAME = "mame0287.mdepgz"
MAX_SET_MEMBERS = 512
MAX_MEMBER_BYTES = 32 * 1024 * 1024
MAX_SET_BYTES = 128 * 1024 * 1024
MAX_SOURCE_ARCHIVE_BYTES = 32 * 1024 * 1024

MACHINE_RUNNABLE = 1 << 0
MACHINE_BIOS = 1 << 1
MACHINE_DEVICE = 1 << 2

BLOCK_UNHASHABLE_ROM = 1 << 0
BLOCK_UNHASHABLE_DISK = 1 << 1
BLOCK_UNRESOLVED_MERGE = 1 << 2
BLOCK_UNKNOWN_BIOS = 1 << 3
BLOCK_MULTIPLE_DEFAULT_BIOS = 1 << 4
BLOCK_UNSUPPORTED_ROM_SIZE = 1 << 5
BLOCK_UNKNOWN_DEVICE = 1 << 6
BLOCK_ANCESTRY_CYCLE = 1 << 7

REQ_MERGED = 1 << 0
REQ_BADDUMP = 1 << 1
REQ_UNSUPPORTED_SIZE = 1 << 2
REQ_WRITABLE = 1 << 3

DEPENDENCY_MAGIC = b"MAMEDEP1"
DEPENDENCY_FORMAT_VERSION = 1
MACHINE_RECORD_INTS = 14
BIOS_RECORD_INTS = 3
ROM_REQUIREMENT_INTS = 4
DISK_REQUIREMENT_INTS = 4
MACHINE_NAME = re.compile(r"^[a-z0-9_+.-]{1,64}$")


@dataclass(frozen=True)
class RawRom:
    name: str
    atom: tuple[str, int] | None
    bios: str | None
    merge: str | None
    baddump: bool
    unsupported_size: bool


@dataclass(frozen=True)
class RawDisk:
    name: str
    digest: str | None
    merge: str | None
    baddump: bool
    writable: bool


@dataclass
class MachineNode:
    name: str
    flags: int
    cloneof: str | None
    romof: str | None
    bios_profiles: list[tuple[str, bool]]
    roms: list[RawRom]
    disks: list[RawDisk]
    device_refs: set[str]
    blockers: int = 0


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def clean_field(value: str | None) -> str:
    return " ".join((value or "").replace("\t", " ").split())


def valid_sha1(value: str | None) -> str | None:
    normalized = (value or "").lower()
    if len(normalized) != 40 or any(c not in "0123456789abcdef" for c in normalized):
        return None
    return normalized


def required_roms(machine: ET.Element) -> tuple[list[tuple[str, int]], bool, int]:
    rows: list[tuple[str, int]] = []
    required_count = 0
    all_hashable = True
    for rom in machine.findall("rom"):
        if rom.get("optional") == "yes" or rom.get("status", "good") == "nodump":
            continue
        required_count += 1
        digest = valid_sha1(rom.get("sha1"))
        size = rom.get("size") or ""
        if digest is None or not size.isdigit():
            all_hashable = False
        else:
            rows.append((digest, int(size)))
    return rows, all_hashable, required_count


def required_disks(machine: ET.Element) -> list[str | None]:
    rows: list[str | None] = []
    for disk in machine.findall("disk"):
        if disk.get("optional") == "yes" or disk.get("status", "good") == "nodump":
            continue
        rows.append(valid_sha1(disk.get("sha1")))
    return rows


def set_signature(members: list[tuple[str, int]]) -> str:
    canonical = "".join(f"{digest}:{size}\n" for digest, size in sorted(members))
    return hashlib.sha256(canonical.encode("ascii")).hexdigest()


def build_payload(xml_path: Path) -> tuple[bytes, int, int, bytes, int]:
    unique_sets: dict[str, tuple[int, int, str, str, str] | None] = {}
    unique_disks: dict[str, tuple[str, str, int, int, str] | None] = {}
    required_members: set[tuple[str, int]] = set()

    for _, machine in ET.iterparse(xml_path, events=("end",)):
        if machine.tag != "machine":
            continue
        attributes = machine.attrib
        if (
            attributes.get("isdevice") == "yes"
            or attributes.get("isbios") == "yes"
            or attributes.get("runnable", "yes") == "no"
        ):
            machine.clear()
            continue

        machine_name = clean_field(attributes.get("name"))
        title = clean_field(machine.findtext("description"))
        clone_root = clean_field(attributes.get("cloneof")) or machine_name
        if not machine_name or not title:
            machine.clear()
            continue

        roms, all_roms_hashable, required_rom_count = required_roms(machine)
        disks = required_disks(machine)
        required_members.update(
            (digest, size)
            for digest, size in roms
            if 0 < size <= MAX_MEMBER_BYTES
        )

        # This is the machine's exact direct ROM-payload signature, not a launchability claim. `romof`,
        # BIOS and device dependencies can still be external; a split/partial direct payload cannot hit.
        total_rom_bytes = sum(size for _, size in roms)
        if (
            roms
            and all_roms_hashable
            and not disks
            and len(roms) <= MAX_SET_MEMBERS
            and all(0 <= size <= MAX_MEMBER_BYTES for _, size in roms)
            and 0 < total_rom_bytes <= MAX_SET_BYTES
        ):
            signature = set_signature(roms)
            record = (
                len(roms),
                total_rom_bytes,
                machine_name,
                clone_root,
                title,
            )
            if signature not in unique_sets:
                unique_sets[signature] = record
            elif unique_sets[signature] != record:
                # Once ambiguous, always ambiguous; a later equal row must never resurrect it.
                unique_sets[signature] = None

        for disk_digest in disks:
            if disk_digest is None:
                continue
            record = (machine_name, clone_root, required_rom_count, len(disks), title)
            if disk_digest not in unique_disks:
                unique_disks[disk_digest] = record
            elif unique_disks[disk_digest] != record:
                unique_disks[disk_digest] = None
        machine.clear()

    lines = [
        "\t".join(
            (
                "#MIMIR_MAME_PAYLOAD_V1",
                f"mame={MAME_RELEASE}",
                f"source_sha256={SOURCE_SHA256}",
            )
        )
    ]
    set_count = 0
    for signature, record in sorted(unique_sets.items()):
        if record is None:
            continue
        member_count, total_bytes, machine_name, clone_root, title = record
        lines.append(
            f"S\t{signature}\t{member_count}\t{total_bytes}\t"
            f"{machine_name}\t{clone_root}\t{title}"
        )
        set_count += 1

    disk_count = 0
    for digest, record in sorted(unique_disks.items()):
        if record is None:
            continue
        machine_name, clone_root, rom_count, disk_total, title = record
        lines.append(
            f"D\t{digest}\t{machine_name}\t{clone_root}\t"
            f"{rom_count}\t{disk_total}\t{title}"
        )
        disk_count += 1

    member_records = bytearray()
    for digest, size in sorted(required_members):
        member_records.extend(bytes.fromhex(digest))
        member_records.extend(struct.pack(">I", size))
    member_payload = (
        b"MMEMPAY1"
        + struct.pack(">II", len(required_members), 24)
        + bytes(member_records)
    )

    return (
        ("\n".join(lines) + "\n").encode("utf-8"),
        set_count,
        disk_count,
        member_payload,
        len(required_members),
    )


def _required_node(machine: ET.Element) -> MachineNode:
    attributes = machine.attrib
    name = clean_field(attributes.get("name"))
    if not MACHINE_NAME.fullmatch(name):
        raise SystemExit(f"invalid MAME machine name: {name!r}")
    flags = 0
    if attributes.get("runnable", "yes") != "no":
        flags |= MACHINE_RUNNABLE
    if attributes.get("isbios") == "yes":
        flags |= MACHINE_BIOS
    if attributes.get("isdevice") == "yes":
        flags |= MACHINE_DEVICE

    bios_profiles: list[tuple[str, bool]] = []
    seen_bios: set[str] = set()
    for bios in machine.findall("biosset"):
        profile = clean_field(bios.get("name"))
        if not MACHINE_NAME.fullmatch(profile) or profile in seen_bios:
            raise SystemExit(f"invalid or duplicate BIOS profile on {name}: {profile!r}")
        seen_bios.add(profile)
        bios_profiles.append((profile, bios.get("default") == "yes"))

    roms: list[RawRom] = []
    blockers = 0
    for rom in machine.findall("rom"):
        if rom.get("optional") == "yes" or rom.get("status", "good") == "nodump":
            continue
        digest = valid_sha1(rom.get("sha1"))
        raw_size = rom.get("size") or ""
        size = int(raw_size) if raw_size.isdigit() else -1
        atom = (digest, size) if digest is not None and 0 <= size <= 0xFFFF_FFFF else None
        if atom is None:
            blockers |= BLOCK_UNHASHABLE_ROM
        unsupported_size = size > MAX_MEMBER_BYTES
        if unsupported_size:
            blockers |= BLOCK_UNSUPPORTED_ROM_SIZE
        bios = clean_field(rom.get("bios")) or None
        roms.append(
            RawRom(
                name=clean_field(rom.get("name")),
                atom=atom,
                bios=bios,
                merge=clean_field(rom.get("merge")) or None,
                baddump=rom.get("status", "good") == "baddump",
                unsupported_size=unsupported_size,
            )
        )

    disks: list[RawDisk] = []
    for disk in machine.findall("disk"):
        if disk.get("optional") == "yes" or disk.get("status", "good") == "nodump":
            continue
        digest = valid_sha1(disk.get("sha1"))
        if digest is None:
            blockers |= BLOCK_UNHASHABLE_DISK
        disks.append(
            RawDisk(
                name=clean_field(disk.get("name")),
                digest=digest,
                merge=clean_field(disk.get("merge")) or None,
                baddump=disk.get("status", "good") == "baddump",
                writable=disk.get("writable") == "yes",
            )
        )

    explicit_defaults = sum(1 for _, declared in bios_profiles if declared)
    if explicit_defaults > 1:
        blockers |= BLOCK_MULTIPLE_DEFAULT_BIOS
    return MachineNode(
        name=name,
        flags=flags,
        cloneof=clean_field(attributes.get("cloneof")) or None,
        romof=clean_field(attributes.get("romof")) or None,
        bios_profiles=bios_profiles,
        roms=roms,
        disks=disks,
        device_refs={
            clean_field(ref.get("name"))
            for ref in machine.findall("device_ref")
            if clean_field(ref.get("name"))
        },
        blockers=blockers,
    )


def _machine_nodes(xml_path: Path) -> dict[str, MachineNode]:
    nodes: dict[str, MachineNode] = {}
    for _, machine in ET.iterparse(xml_path, events=("end",)):
        if machine.tag != "machine":
            continue
        node = _required_node(machine)
        if node.name in nodes:
            raise SystemExit(f"duplicate MAME machine: {node.name}")
        nodes[node.name] = node
        machine.clear()
    return nodes


def _ancestry(node: MachineNode, nodes: dict[str, MachineNode]) -> list[MachineNode] | None:
    result: list[MachineNode] = []
    seen = {node.name}
    parent_name = node.romof
    while parent_name is not None:
        if parent_name in seen:
            node.blockers |= BLOCK_ANCESTRY_CYCLE
            return None
        parent = nodes.get(parent_name)
        if parent is None:
            node.blockers |= BLOCK_UNRESOLVED_MERGE
            return None
        seen.add(parent_name)
        result.append(parent)
        parent_name = parent.romof
    return result


def _merge_owner_rom(
    node: MachineNode,
    row: RawRom,
    nodes: dict[str, MachineNode],
) -> str | None:
    if row.merge is None:
        return node.name
    ancestry = _ancestry(node, nodes)
    if ancestry is None or row.atom is None:
        return None
    for ancestor in ancestry:
        matches = {
            candidate.atom
            for candidate in ancestor.roms
            if candidate.name == row.merge and candidate.atom == row.atom
        }
        if len(matches) == 1:
            return ancestor.name
    return None


def _merge_owner_disk(
    node: MachineNode,
    row: RawDisk,
    nodes: dict[str, MachineNode],
) -> str | None:
    if row.merge is None:
        return node.name
    ancestry = _ancestry(node, nodes)
    if ancestry is None or row.digest is None:
        return None
    for ancestor in ancestry:
        matches = {
            candidate.digest
            for candidate in ancestor.disks
            if candidate.name == row.merge and candidate.digest == row.digest
        }
        if len(matches) == 1:
            return ancestor.name
    return None


def _bios_profile_owner(
    node: MachineNode,
    profile: str,
    nodes: dict[str, MachineNode],
) -> str | None:
    candidates = [node] + (_ancestry(node, nodes) or [])
    return next(
        (
            candidate.name
            for candidate in candidates
            if any(name == profile for name, _ in candidate.bios_profiles)
        ),
        None,
    )


def _string_table(values: set[str]) -> tuple[bytes, dict[str, int]]:
    output = bytearray()
    offsets: dict[str, int] = {}
    for value in sorted(values):
        encoded = value.encode("utf-8")
        if not encoded or b"\0" in encoded:
            raise SystemExit(f"invalid dependency string: {value!r}")
        offsets[value] = len(output)
        output.extend(encoded)
        output.append(0)
    return bytes(output), offsets


def build_dependency_payload(xml_path: Path) -> tuple[bytes, dict[str, int]]:
    nodes = _machine_nodes(xml_path)
    ordered_names = sorted(nodes)
    machine_ids = {name: index for index, name in enumerate(ordered_names)}

    for node in nodes.values():
        if node.cloneof is not None and node.cloneof not in nodes:
            node.blockers |= BLOCK_UNRESOLVED_MERGE
        if node.romof is not None and node.romof not in nodes:
            node.blockers |= BLOCK_UNRESOLVED_MERGE
        for target in node.device_refs:
            if target not in nodes:
                node.blockers |= BLOCK_UNKNOWN_DEVICE
        _ancestry(node, nodes)

    rom_atoms = sorted(
        {
            row.atom
            for node in nodes.values()
            for row in node.roms
            if row.atom is not None
        }
    )
    disk_atoms = sorted(
        {
            row.digest
            for node in nodes.values()
            for row in node.disks
            if row.digest is not None
        }
    )
    rom_atom_ids = {atom: index for index, atom in enumerate(rom_atoms)}
    disk_atom_ids = {atom: index for index, atom in enumerate(disk_atoms)}

    bios_rows: list[tuple[int, str, int]] = []
    bios_ids: dict[tuple[str, str], int] = {}
    default_bios: dict[str, int] = {}
    for name in ordered_names:
        node = nodes[name]
        explicit = [profile for profile, declared in node.bios_profiles if declared]
        effective = explicit[0] if len(explicit) == 1 else (
            node.bios_profiles[0][0] if node.bios_profiles else None
        )
        for profile, declared in node.bios_profiles:
            flags = (1 if declared else 0) | (2 if profile == effective else 0)
            bios_ids[(name, profile)] = len(bios_rows)
            bios_rows.append((machine_ids[name], profile, flags))
        if effective is not None:
            default_bios[name] = bios_ids[(name, effective)]

    rom_requirements: list[tuple[int, int, int, int]] = []
    disk_requirements: list[tuple[int, int, int, int]] = []
    device_edges: list[int] = []
    machine_rows: list[tuple[int, ...]] = []
    string_values = set(ordered_names)
    string_values.update(profile for _, profile, _ in bios_rows)
    strings, string_offsets = _string_table(string_values)

    media_bearing = {
        name
        for name, node in nodes.items()
        if any(row.atom is not None for row in node.roms)
        or any(row.digest is not None for row in node.disks)
    }
    bios_cursor = 0
    for name in ordered_names:
        node = nodes[name]
        local_bios_count = len(node.bios_profiles)
        rom_start = len(rom_requirements)
        for row in node.roms:
            if row.atom is None:
                continue
            owner = _merge_owner_rom(node, row, nodes)
            if owner is None:
                node.blockers |= BLOCK_UNRESOLVED_MERGE
                owner = node.name
            flags = 0
            if row.merge is not None:
                flags |= REQ_MERGED
            if row.baddump:
                flags |= REQ_BADDUMP
            if row.unsupported_size:
                flags |= REQ_UNSUPPORTED_SIZE
            profile_id = 0xFFFF_FFFF
            if row.bios is not None:
                profile_owner = _bios_profile_owner(node, row.bios, nodes)
                profile_id = bios_ids.get((profile_owner, row.bios), 0xFFFF_FFFF)
                if profile_id == 0xFFFF_FFFF:
                    node.blockers |= BLOCK_UNKNOWN_BIOS
            rom_requirements.append(
                (
                    rom_atom_ids[row.atom],
                    profile_id,
                    machine_ids[owner],
                    flags,
                )
            )
        # Multiplicity is part of closure: two sockets carrying the same payload atom are still two
        # declared requirements. Sorting gives deterministic order without collapsing duplicates.
        rom_requirements[rom_start:] = sorted(rom_requirements[rom_start:])

        disk_start = len(disk_requirements)
        for row in node.disks:
            if row.digest is None:
                continue
            owner = _merge_owner_disk(node, row, nodes)
            if owner is None:
                node.blockers |= BLOCK_UNRESOLVED_MERGE
                owner = node.name
            flags = 0
            if row.merge is not None:
                flags |= REQ_MERGED
            if row.baddump:
                flags |= REQ_BADDUMP
            if row.writable:
                flags |= REQ_WRITABLE
            disk_requirements.append(
                (disk_atom_ids[row.digest], machine_ids[owner], flags, 0)
            )
        disk_requirements[disk_start:] = sorted(disk_requirements[disk_start:])

        device_start = len(device_edges)
        local_devices = sorted(
            {
                machine_ids[target]
                for target in node.device_refs
                if target in media_bearing
            }
        )
        device_edges.extend(local_devices)
        machine_rows.append(
            (
                string_offsets[name],
                node.flags,
                machine_ids.get(node.cloneof, 0xFFFF_FFFF),
                machine_ids.get(node.romof, 0xFFFF_FFFF),
                default_bios.get(name, 0xFFFF_FFFF),
                bios_cursor,
                local_bios_count,
                rom_start,
                len(rom_requirements) - rom_start,
                disk_start,
                len(disk_requirements) - disk_start,
                device_start,
                len(local_devices),
                node.blockers,
            )
        )
        bios_cursor += local_bios_count

    header = struct.pack(
        ">8s9I",
        DEPENDENCY_MAGIC,
        DEPENDENCY_FORMAT_VERSION,
        len(strings),
        len(machine_rows),
        len(bios_rows),
        len(rom_atoms),
        len(disk_atoms),
        len(rom_requirements),
        len(disk_requirements),
        len(device_edges),
    )
    output = bytearray(header)
    output.extend(strings)
    for row in machine_rows:
        output.extend(struct.pack(f">{MACHINE_RECORD_INTS}I", *row))
    for machine_id, profile, flags in bios_rows:
        output.extend(
            struct.pack(
                f">{BIOS_RECORD_INTS}I",
                machine_id,
                string_offsets[profile],
                flags,
            )
        )
    for digest, size in rom_atoms:
        output.extend(bytes.fromhex(digest))
        output.extend(struct.pack(">I", size))
    for digest in disk_atoms:
        output.extend(bytes.fromhex(digest))
    for row in rom_requirements:
        output.extend(struct.pack(f">{ROM_REQUIREMENT_INTS}I", *row))
    for row in disk_requirements:
        output.extend(struct.pack(f">{DISK_REQUIREMENT_INTS}I", *row))
    for target in device_edges:
        output.extend(struct.pack(">I", target))

    stats = {
        "machines": len(machine_rows),
        "bios_profiles": len(bios_rows),
        "declared_rom_requirements": sum(len(node.roms) for node in nodes.values()),
        "unhashable_rom_requirements": sum(
            1 for node in nodes.values() for row in node.roms if row.atom is None
        ),
        "rom_atoms": len(rom_atoms),
        "declared_disk_requirements": sum(len(node.disks) for node in nodes.values()),
        "unhashable_disk_requirements": sum(
            1 for node in nodes.values() for row in node.disks if row.digest is None
        ),
        "disk_atoms": len(disk_atoms),
        "rom_requirements": len(rom_requirements),
        "disk_requirements": len(disk_requirements),
        "device_edges": len(device_edges),
        "blocked_machines": sum(1 for node in nodes.values() if node.blockers),
        "payload_bytes": len(output),
    }
    return bytes(output), stats


def deterministic_gzip(payload: bytes) -> bytes:
    target = io.BytesIO()
    with gzip.GzipFile(
        filename="",
        mode="wb",
        fileobj=target,
        compresslevel=9,
        mtime=0,
    ) as compressed:
        compressed.write(payload)
    return target.getvalue()


def obtain_source(explicit: Path | None, stage: Path) -> Path:
    if explicit is not None:
        source = explicit.resolve()
        if not source.is_file():
            raise SystemExit(f"source archive does not exist: {source}")
    else:
        source = stage / SOURCE_NAME
        with urllib.request.urlopen(SOURCE_URL, timeout=180) as response, source.open("wb") as output:
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) > MAX_SOURCE_ARCHIVE_BYTES:
                raise SystemExit("official source archive exceeds the download budget")
            remaining = MAX_SOURCE_ARCHIVE_BYTES + 1
            while remaining > 0:
                chunk = response.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                output.write(chunk)
                remaining -= len(chunk)
            if remaining == 0:
                raise SystemExit("official source archive exceeds the download budget")
    actual = sha256_file(source)
    if actual != SOURCE_SHA256:
        raise SystemExit(f"source SHA-256 mismatch: expected {SOURCE_SHA256}, got {actual}")
    return source


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-zip", type=Path)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "app/src/main/assets/dat" / OUTPUT_NAME,
    )
    parser.add_argument(
        "--member-output",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "app/src/main/assets/dat" / MEMBER_OUTPUT_NAME,
    )
    parser.add_argument(
        "--dependency-output",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "app/src/main/assets/dat" / DEPENDENCY_OUTPUT_NAME,
    )
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="mimir-mame-payload-") as temp:
        stage = Path(temp)
        source = obtain_source(args.source_zip, stage)
        with zipfile.ZipFile(source) as archive:
            members = [entry for entry in archive.infolist() if not entry.is_dir()]
            if len(members) != 1 or members[0].filename != "mame0287.xml":
                raise SystemExit("official archive shape drifted: expected exactly mame0287.xml")
            if members[0].file_size > 400 * 1024 * 1024:
                raise SystemExit("official XML exceeds the 400 MiB generation budget")
            archive.extract(members[0], stage)

        payload, set_count, disk_count, member_payload, member_count = build_payload(
            stage / "mame0287.xml"
        )
        dependency_payload, dependency_stats = build_dependency_payload(
            stage / "mame0287.xml"
        )
        compressed = deterministic_gzip(payload)
        member_compressed = deterministic_gzip(member_payload)
        dependency_compressed = deterministic_gzip(dependency_payload)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(compressed)
        args.member_output.parent.mkdir(parents=True, exist_ok=True)
        args.member_output.write_bytes(member_compressed)
        args.dependency_output.parent.mkdir(parents=True, exist_ok=True)
        args.dependency_output.write_bytes(dependency_compressed)

    print(f"output={args.output}")
    print(f"set_rows={set_count}")
    print(f"disk_rows={disk_count}")
    print(f"payload_bytes={len(payload)}")
    print(f"payload_sha256={hashlib.sha256(payload).hexdigest()}")
    print(f"compressed_bytes={len(compressed)}")
    print(f"compressed_sha256={hashlib.sha256(compressed).hexdigest()}")
    print(f"member_output={args.member_output}")
    print(f"member_rows={member_count}")
    print(f"member_payload_bytes={len(member_payload)}")
    print(f"member_payload_sha256={hashlib.sha256(member_payload).hexdigest()}")
    print(f"member_compressed_bytes={len(member_compressed)}")
    print(f"member_compressed_sha256={hashlib.sha256(member_compressed).hexdigest()}")
    print(f"dependency_output={args.dependency_output}")
    for key, value in dependency_stats.items():
        print(f"dependency_{key}={value}")
    print(
        "dependency_payload_sha256="
        f"{hashlib.sha256(dependency_payload).hexdigest()}"
    )
    print(f"dependency_compressed_bytes={len(dependency_compressed)}")
    print(
        "dependency_compressed_sha256="
        f"{hashlib.sha256(dependency_compressed).hexdigest()}"
    )


if __name__ == "__main__":
    main()
