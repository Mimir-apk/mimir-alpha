#!/usr/bin/env python3
"""Hermetic adversarial gates for the immutable DAT compiler."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


PACKER_PATH = Path(__file__).with_name("pack-dats.py")
SPEC = importlib.util.spec_from_file_location("mimir_pack_dats", PACKER_PATH)
assert SPEC is not None and SPEC.loader is not None
PACKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKER)


def xml(body: str, header: str = "Fixture Parent-Clone") -> bytes:
    return f"<datafile><header><name>{header}</name></header>{body}</datafile>".encode()


class PackDatsTest(unittest.TestCase):
    def pack(self, source: bytes) -> bytes:
        return PACKER._build_index(
            source,
            source_id="fixture.datgz",
            platform_id="fixture",
            origin="NO_INTRO",
            expected_header="Fixture Parent-Clone",
        )

    def test_deterministic_clone_collapse_and_ambiguity_contract(self) -> None:
        source = xml(
            """
            <game name="Parent"><rom sha1="0000000000000000000000000000000000000000"/></game>
            <game name="Clone A" cloneof="Parent"><rom sha1="1111111111111111111111111111111111111111"/></game>
            <game name="Clone B" cloneof="Parent"><rom sha1="1111111111111111111111111111111111111111"/></game>
            <game name="Other"><rom sha1="2222222222222222222222222222222222222222"/></game>
            <game name="Conflict" cloneof="Different"><rom sha1="2222222222222222222222222222222222222222"/></game>
            """,
        )

        first = self.pack(source)
        second = self.pack(source)
        self.assertEqual(first, second)
        stats = PACKER._inspect_bytes(first)
        self.assertEqual(5, stats["parsed_entry_count"])
        self.assertEqual(3, stats["unique_sha1_count"])
        self.assertEqual(1, stats["ambiguous_sha1_count"])
        self.assertEqual(1, stats["distinct_collapse_key_count"])
        self.assertEqual("Fixture Parent-Clone", stats["expected_header"])

    def test_crc_only_or_wrong_header_source_fails_closed(self) -> None:
        crc_only = xml('<game name="CRC only"><rom crc="12345678"/></game>')
        with self.assertRaises(PACKER.PackError):
            self.pack(crc_only)

        sha = xml('<game name="Game"><rom sha1="11' + '11' * 19 + '"/></game>', header="Wrong")
        with self.assertRaises(PACKER.PackError):
            self.pack(sha)

    def test_corrupt_padding_and_truncation_are_rejected(self) -> None:
        source = xml('<game name="Game"><rom sha1="' + 'ab' * 20 + '"/></game>')
        packed = bytearray(self.pack(source))
        metadata_lengths = [int.from_bytes(packed[at : at + 4], "little") for at in range(0x10, 0x20, 4)]
        metadata_end = 192 + sum(metadata_lengths)
        sha_offset = int.from_bytes(packed[0x48:0x50], "little")
        if metadata_end < sha_offset:
            packed[metadata_end] = 1
            with self.assertRaises(PACKER.PackError):
                PACKER._inspect_bytes(bytes(packed))

        with self.assertRaises(PACKER.PackError):
            PACKER._inspect_bytes(self.pack(source)[:-1])


if __name__ == "__main__":
    unittest.main()
