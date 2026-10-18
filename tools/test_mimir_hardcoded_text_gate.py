#!/usr/bin/env python3
"""Tests for tools/mimir_hardcoded_text_gate.py (stdlib only)."""

from __future__ import annotations

import importlib.util
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("mimir_hardcoded_text_gate", TOOLS / "mimir_hardcoded_text_gate.py")
gate = importlib.util.module_from_spec(SPEC)
sys.modules["mimir_hardcoded_text_gate"] = gate
assert SPEC.loader is not None
SPEC.loader.exec_module(gate)

HEADER = "module\tcount\n"


def sites(source: str, module: str = "feature-files") -> list:
    return gate.count_sites(source, module, f"{module}/src/main/kotlin/X.kt")


class CountSitesTest(unittest.TestCase):
    def test_counts_plain_text_literal(self) -> None:
        self.assertEqual(1, len(sites('fun A() { Text("Open") }')))

    def test_counts_named_text_argument_and_multiline_call(self) -> None:
        source = 'fun A() {\n    Text(\n        text = "Open",\n        color = c,\n    )\n}\n'
        found = sites(source)
        self.assertEqual(["Open"], [s.text for s in found])
        self.assertEqual(3, found[0].line_no)

    def test_counts_the_mimir_wrappers(self) -> None:
        source = (
            'MimirChromeText("Back")\n'
            'MimirAnimatedChromeText(text = "Next")\n'
            'MimirControlHintText("OPEN")\n'
            'MimirWordEllipsisText("A long sentence", style = s, color = c)\n'
        )
        self.assertEqual(4, len(sites(source)))

    def test_counts_a_template_that_carries_words(self) -> None:
        self.assertEqual(1, len(sites('Text("$count items")')))
        self.assertEqual(1, len(sites('Text("${list.size} games")')))

    def test_counts_a_raw_string(self) -> None:
        self.assertEqual(1, len(sites('Text("""Line one\nline two""")')))

    def test_ignores_text_with_nothing_to_translate(self) -> None:
        for literal in ('""', '"•"', '"$count"', '"${count}"', '"12"', '" "', '"→"'):
            with self.subTest(literal=literal):
                self.assertEqual([], sites(f"Text({literal})"))

    def test_ignores_resources_variables_and_expressions(self) -> None:
        for call in (
            "Text(stringResource(R.string.files_footer_open))",
            "Text(label)",
            "Text(modifier = m, text = label)",
            'Text(if (on) "On" else "Off")',
            'Text(title.ifBlank { "Untitled" })',
            'Text(text = items.joinToString(", "))',
        ):
            with self.subTest(call=call):
                self.assertEqual([], sites(call))

    def test_ignores_comments_and_strings_that_only_look_like_calls(self) -> None:
        source = (
            '// Text("commented out")\n'
            '/* Text("block") */\n'
            '/** Renders Text("in kdoc") */\n'
            'val doc = "Text(\\"inside a string\\")"\n'
            'log("Text(\\"x\\")")\n'
        )
        self.assertEqual([], sites(source))

    def test_ignores_other_composables_and_lookalike_names(self) -> None:
        for call in ('BasicText("x")', 'Button("Open")', 'drawText("Open")', 'setText("Open")', 'ContentText("Open")', 'Label("Open")'):
            with self.subTest(call=call):
                self.assertEqual([], sites(call))

    def test_only_the_first_argument_is_text(self) -> None:
        self.assertEqual([], sites('Text(label, style = s, fontFamily = "Mono")'))

    def test_counts_each_site_once(self) -> None:
        self.assertEqual(2, len(sites('Text("One")\nText("Two")')))


class ScannedModuleTest(unittest.TestCase):
    def test_modules_that_draw_ui_are_scanned(self) -> None:
        for module in ("app", "core-ui", "feature-settings", "feature-library", "feature-files", "feature-browse", "feature-companion"):
            with self.subTest(module=module):
                self.assertEqual(module, gate.scanned_module(f"{module}/src/main/kotlin/p/X.kt"))

    def test_other_modules_and_source_sets_are_not(self) -> None:
        for rel in (
            "core-model/src/main/kotlin/X.kt",
            "core-launch/src/main/kotlin/X.kt",
            "mimir-tweaker/app/src/main/kotlin/X.kt",
            "feature-files/src/test/kotlin/X.kt",
            "app/src/lab/kotlin/X.kt",
            "app/src/androidTest/kotlin/X.kt",
            "tools/X.kt",
        ):
            with self.subTest(rel=rel):
                self.assertIsNone(gate.scanned_module(rel))


class GateRunTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.baseline = self.root / "baseline.tsv"

    def write(self, rel: str, content: str) -> None:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def write_baseline(self, body: str) -> None:
        self.baseline.write_text(HEADER + body, encoding="utf-8")

    def run_gate(self, update: bool = False) -> tuple[int, str]:
        out = io.StringIO()
        with redirect_stdout(out):
            code = gate.run_gate(self.root, self.baseline, update=update)
        return code, out.getvalue()

    def feature(self, module: str, *literals: str) -> None:
        body = "".join(f'    Text("{lit}")\n' for lit in literals)
        self.write(f"{module}/src/main/kotlin/Screen.kt", f"fun Screen() {{\n{body}}}\n")

    def test_passes_at_the_baseline(self) -> None:
        self.feature("feature-files", "Open", "Back")
        self.write_baseline("feature-files\t2\n")
        code, out = self.run_gate()
        self.assertEqual(0, code, out)
        self.assertIn("HARDCODED TEXT GATE: PASS", out)

    def test_fails_when_a_module_count_rises_and_names_the_sites(self) -> None:
        self.feature("feature-files", "Open", "Back", "Delete")
        self.write_baseline("feature-files\t2\n")
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("HARDCODED TEXT GATE: FAIL", out)
        self.assertIn("feature-files: 3 site(s), baseline 2", out)
        self.assertIn("feature-files/src/main/kotlin/Screen.kt:4 'Delete'", out)

    def test_a_module_missing_from_the_baseline_has_a_baseline_of_zero(self) -> None:
        self.feature("feature-files", "Open")
        self.feature("feature-newthing", "Hello")
        self.write_baseline("feature-files\t1\n")
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("feature-newthing: 1 site(s), baseline 0", out)
        self.assertNotIn("feature-files:", out)

    def test_passes_with_a_note_when_a_count_falls(self) -> None:
        self.feature("feature-files", "Open")
        self.write_baseline("feature-files\t4\n")
        code, out = self.run_gate()
        self.assertEqual(0, code)
        self.assertIn("note: feature-files is at 1, baseline 4", out)

    def test_a_rise_in_one_module_is_not_paid_for_by_a_fall_in_another(self) -> None:
        self.feature("feature-files", "Open", "Back")
        self.feature("feature-library", "Play")
        self.write_baseline("feature-files\t0\nfeature-library\t5\n")
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("feature-files: 2 site(s), baseline 0", out)

    def test_ignores_modules_and_source_sets_outside_the_scan(self) -> None:
        self.feature("feature-files", "Open")
        self.write("core-model/src/main/kotlin/X.kt", 'fun A() { Text("Not scanned") }\n')
        self.write("feature-files/src/test/kotlin/XTest.kt", 'fun A() { Text("Test only") }\n')
        self.write("feature-files/build/generated/src/main/kotlin/Gen.kt", 'fun A() { Text("Generated") }\n')
        self.write(".worktrees/other/feature-files/src/main/kotlin/X.kt", 'fun A() { Text("Nested worktree") }\n')
        self.write_baseline("feature-files\t1\n")
        code, out = self.run_gate()
        self.assertEqual(0, code, out)

    def test_a_missing_baseline_fails_closed(self) -> None:
        self.feature("feature-files", "Open")
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("baseline missing", out)

    def test_a_malformed_baseline_fails_closed(self) -> None:
        self.feature("feature-files", "Open")
        for body in ("feature-files\tmany\n", "feature-files\t1\nfeature-files\t2\n", "feature-files\n"):
            with self.subTest(body=body):
                self.write_baseline(body)
                code, out = self.run_gate()
                self.assertEqual(1, code)
                self.assertIn("HARDCODED TEXT GATE: FAIL", out)
        self.baseline.write_text("feature-files\t1\n", encoding="utf-8")
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("header", out)

    def test_update_baseline_round_trips_and_records_zero_modules(self) -> None:
        self.feature("feature-files", "Open", "Back")
        self.write("feature-library/src/main/kotlin/Clean.kt", "fun A() {}\n")
        code, _ = self.run_gate(update=True)
        self.assertEqual(0, code)
        self.assertEqual({"feature-files": 2, "feature-library": 0}, gate.load_baseline(self.baseline))
        code, out = self.run_gate()
        self.assertEqual(0, code, out)


class RealRepoBaselineTest(unittest.TestCase):
    def test_the_committed_baseline_parses_and_matches_the_tree(self) -> None:
        baseline = gate.load_baseline(gate.BASELINE_PATH)
        counts = gate.module_counts(gate.scan_repo(gate.ROOT))
        rising, _ = gate.evaluate(counts, baseline)
        self.assertEqual([], rising, "a module added hardcoded UI text since the baseline")
        for module in gate.scanned_modules(gate.ROOT):
            self.assertIn(module, baseline, f"{module} has no baseline row; run --update-baseline")


if __name__ == "__main__":
    unittest.main()
