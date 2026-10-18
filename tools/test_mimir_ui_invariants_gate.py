#!/usr/bin/env python3
"""Adversarial tests for tools/mimir_ui_invariants_gate.py (stdlib only)."""

from __future__ import annotations

import importlib.util
import io
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("mimir_ui_invariants_gate", TOOLS / "mimir_ui_invariants_gate.py")
gate = importlib.util.module_from_spec(SPEC)
sys.modules["mimir_ui_invariants_gate"] = gate
assert SPEC.loader is not None
SPEC.loader.exec_module(gate)


class MimirUiInvariantsGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmpdir.cleanup)
        self.root = Path(self._tmpdir.name)
        self._orig_root = gate.ROOT
        self._orig_waiver = gate.WAIVER_PATH
        self._orig_producer = gate.PRODUCER_PATH
        gate.ROOT = self.root
        gate.WAIVER_PATH = self.root / "tools" / "mimir-ui-invariants-waivers.tsv"
        gate.WAIVER_PATH.parent.mkdir(parents=True, exist_ok=True)
        gate.PRODUCER_PATH = self.root / "tools" / "mimir-must-consume-producers.tsv"

    def tearDown(self) -> None:
        gate.ROOT = self._orig_root
        gate.WAIVER_PATH = self._orig_waiver
        gate.PRODUCER_PATH = self._orig_producer

    def write(self, rel: str, content: str) -> Path:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def write_waivers(self, body: str) -> None:
        header = "\t".join(gate.WAIVER_HEADER)
        gate.WAIVER_PATH.write_text(f"{header}\n{body}".rstrip() + "\n", encoding="utf-8")

    def write_producers(self, body: str) -> None:
        header = "\t".join(gate.PRODUCER_HEADER)
        gate.PRODUCER_PATH.parent.mkdir(parents=True, exist_ok=True)
        gate.PRODUCER_PATH.write_text(f"{header}\n{body}".rstrip() + "\n", encoding="utf-8")

    def test_real_mimir_focusable_file_ignores_kdoc_prose(self) -> None:
        path = self.write(
            "core-input/src/main/kotlin/MimirFocusable.kt",
            (
                "/**\n"
                " * It intentionally does NOT use Compose's own `Modifier.focusable()` /\n"
                " * `focusTarget()` — forbidden pattern in prose only.\n"
                " */\n"
                "fun ok() {}\n"
            ),
        )
        self.assertEqual([], gate.scan_file(path, ("F1",)))

    def test_nested_block_and_line_comments(self) -> None:
        source = "/* outer /* inner */ still comment */\nval x = 1 // end\n"
        code, p1 = gate.KotlinLexer(source).build_views()
        self.assertIn("val x = 1", code)
        self.assertNotIn("outer", code)

    def test_raw_template_and_eof_without_trailing_newline(self) -> None:
        source = "val x = \"${\"\"\"raw\"\"\"}\"\nval y = `splash's worst`"
        code, _ = gate.KotlinLexer(source).build_views()
        self.assertEqual(source.count("\n"), code.count("\n"))
        self.assertIn("splash's worst", code)

    def test_f1_all_forms_and_source_sets(self) -> None:
        main = self.write(
            "app/src/main/kotlin/Demo.kt",
            "import androidx.compose.foundation.focusable\nfun a() { Modifier.focusable() }\n",
        )
        test = self.write(
            "app/src/test/kotlin/DemoTest.kt",
            "import androidx.compose.ui.focus.focusTarget\nfun b() { focusTarget() }\n",
        )
        android_test = self.write(
            "app/src/androidTest/kotlin/DemoAndroidTest.kt",
            ".focusable(enabled = true)\n",
        )
        findings = (
            gate.scan_file(main, ("F1",))
            + gate.scan_file(test, ("F1",))
            + gate.scan_file(android_test, ("F1",))
        )
        details = {f.detail for f in findings}
        self.assertIn("import androidx.compose.foundation.focusable", details)
        self.assertIn("import androidx.compose.ui.focus.focusTarget", details)
        self.assertIn(".focusable(", details)
        self.assertIn("focusTarget(", details)

    def test_f1_ignores_string_literals(self) -> None:
        path = self.write(
            "core-ui/src/main/kotlin/Demo.kt",
            'val msg = "import androidx.compose.foundation.focusable and .focusable()"\n',
        )
        self.assertEqual([], gate.scan_file(path, ("F1",)))

    def test_p1_all_forms_scoped_to_feature_main(self) -> None:
        feature = self.write(
            "feature-demo/src/main/kotlin/Demo.kt",
            (
                "val a = Color(0xFF_01_02_03)\n"
                'val b = "#1234"\n'
                "val c = 0xFF00FF00u\n"
            ),
        )
        core = self.write("core-ui/src/main/kotlin/Demo.kt", "val hidden = Color(0xFFFFFFFF)\n")
        feature_test = self.write(
            "feature-demo/src/test/kotlin/DemoTest.kt",
            "val ignored = Color(0xFFFFFFFF)\n",
        )
        p1_feature = gate.scan_file(feature, ("P1",))
        self.assertGreaterEqual(len(p1_feature), 3)
        self.assertNotIn(core, gate.feature_main_files())
        self.assertNotIn(feature_test, gate.feature_main_files())
        rels = {p.relative_to(gate.ROOT).as_posix() for p in gate.feature_main_files()}
        self.assertTrue(all(r.startswith("feature-") and "/src/main/" in r for r in rels))
        gate.WAIVER_PATH.write_text("\t".join(gate.WAIVER_HEADER) + "\n", encoding="utf-8")
        buf = io.StringIO()
        with redirect_stdout(buf):
            self.assertEqual(1, gate.run_gate())
        self.assertNotIn("core-ui/src/main/kotlin/Demo.kt", buf.getvalue())

    def test_p1_two_literals_are_two_findings(self) -> None:
        path = self.write(
            "feature-demo/src/main/kotlin/Demo.kt",
            "val a = Color(0xFF010203); val b = Color(0xFF040506)\n",
        )
        findings = gate.scan_file(path, ("P1",))
        matches = [f.exact_match for f in findings if f.detail == "Color(0x"]
        self.assertEqual(["Color(0xFF010203)", "Color(0xFF040506)"], matches)

    def test_overlap_dedupe_keeps_most_specific(self) -> None:
        findings = [
            gate.Finding("P1", "f", 1, 1, "0xFF", "x", "short"),
            gate.Finding("P1", "f", 1, 1, "0xFF00FF00", "x", "long"),
        ]
        kept = gate.dedupe_overlaps(findings)
        self.assertEqual(1, len(kept))
        self.assertEqual("0xFF00FF00", kept[0].exact_match)

    def test_multiline_color_dedupes_nested_hex_literal(self) -> None:
        path = self.write(
            "feature-demo/src/main/kotlin/Demo.kt",
            "val bg = Color(\n  0xFF010203\n)\n",
        )
        findings = gate.scan_file(path, ("P1",))
        color_findings = [f for f in findings if f.detail == "Color(0x"]
        hex_findings = [f for f in findings if f.detail == "0x-literal"]
        self.assertEqual(1, len(color_findings))
        self.assertEqual("Color(\n  0xFF010203\n)", color_findings[0].exact_match)
        self.assertEqual([], hex_findings)

    def test_multiline_two_distinct_literals_stay_two_findings(self) -> None:
        path = self.write(
            "feature-demo/src/main/kotlin/Demo.kt",
            (
                "val a = Color(\n  0xFF010203\n)\n"
                "val b = Color(\n  0xFF040506\n)\n"
            ),
        )
        findings = gate.scan_file(path, ("P1",))
        color_findings = [f for f in findings if f.detail == "Color(0x"]
        self.assertEqual(2, len(color_findings))
        self.assertEqual(
            ["Color(\n  0xFF010203\n)", "Color(\n  0xFF040506\n)"],
            [f.exact_match for f in color_findings],
        )
        self.assertEqual([], [f for f in findings if f.detail == "0x-literal"])

    def test_waiver_validates_header_and_reason(self) -> None:
        gate.WAIVER_PATH.write_text("bad\theader\n", encoding="utf-8")
        with self.assertRaises(SystemExit):
            gate.load_waivers()
        self.write_waivers(
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tPREVIEW_BG\t0xFF\tshort\towner\t2026-07-24"
        )
        with self.assertRaises(SystemExit):
            gate.load_waivers()

    def test_waiver_suppresses_exact_live_finding(self) -> None:
        rel = "feature-demo/src/main/kotlin/Demo.kt"
        self.write(rel, "private const val PREVIEW_BG = 0xFF080D12\n")
        self.write_waivers(
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tPREVIEW_BG\t0xFF080D12\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = gate.run_gate()
        self.assertEqual(0, code)
        self.assertIn("PASS", buf.getvalue())

    def test_same_line_comment_prefix_focusable_exact_column(self) -> None:
        rel = "app/src/main/kotlin/Demo.kt"
        self.write(rel, "/* c */ Modifier.focusable()\n")
        findings = gate.scan_file(self.root / rel, ("F1",))
        self.assertEqual(1, len(findings))
        finding = findings[0]
        self.assertEqual(".focusable(", finding.detail)
        self.assertEqual(".focusable(", finding.exact_match)
        self.assertEqual(17, finding.column)

    def test_nested_block_comment_masks_focusable_false_positive(self) -> None:
        rel = "app/src/main/kotlin/Demo.kt"
        self.write(rel, "/* outer /* inner .focusable( */ still */ val ok = 1\n")
        self.assertEqual([], gate.scan_file(self.root / rel, ("F1",)))

    def test_raw_template_focusable_is_live_f1(self) -> None:
        rel = "app/src/main/kotlin/Demo.kt"
        self.write(rel, 'val x = """${ Modifier.focusable() }"""\n')
        findings = gate.scan_file(self.root / rel, ("F1",))
        self.assertTrue(any(f.exact_match == ".focusable(" for f in findings))

    def test_nested_templates_normal_and_raw(self) -> None:
        rel = "app/src/main/kotlin/Demo.kt"
        self.write(
            rel,
            (
                'val a = "pre ${ "inner ${ focusTarget() }" } post"\n'
                'val b = """raw ${ Color(0xFF010203) }"""\n'
            ),
        )
        f1 = gate.scan_file(self.root / rel, ("F1",))
        self.assertTrue(any("focusTarget" in f.exact_match for f in f1))
        feature = self.write(
            "feature-demo/src/main/kotlin/Hex.kt",
            'val b = """raw ${ Color(0xFF010203) }"""\n',
        )
        p1 = gate.scan_file(feature, ("P1",))
        self.assertTrue(any("Color(0xFF010203)" in f.exact_match for f in p1))

    def test_comment_span_preserves_exact_view_length(self) -> None:
        source = "/* abc */\nval x = 1"
        code, p1 = gate.KotlinLexer(source).build_views()
        self.assertEqual(len(source), len(code))
        self.assertEqual(len(source), len(p1))
        self.assertEqual(" " * 9, code[:9])
        self.assertEqual("\nval x = 1", code[9:])

    def test_multiline_f1_and_p1_patterns(self) -> None:
        rel = "app/src/main/kotlin/Demo.kt"
        self.write(rel, "Modifier\n.focusable()\n")
        f1 = gate.scan_file(self.root / rel, ("F1",))
        self.assertTrue(any(".focusable(" in f.exact_match for f in f1))
        feature = self.write(
            "feature-demo/src/main/kotlin/Hex.kt",
            "Color\n.rgb(1,2,3)\nColor\n.parseColor(\"#fff\")\n",
        )
        p1 = gate.scan_file(feature, ("P1",))
        details = {f.detail for f in p1}
        self.assertIn("Color.rgb/argb", details)
        self.assertIn("parseColor", details)

    def test_ambiguous_duplicate_same_line_waiver_fails(self) -> None:
        rel = "feature-demo/src/main/kotlin/Demo.kt"
        self.write(rel, "val a = 0xFF010203; val b = 0xFF010203\n")
        self.write_waivers(
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\t1\t0xFF010203\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        findings = gate.scan_file(self.root / rel, ("P1",))
        errors = gate.validate_waivers(gate.load_waivers(), findings)
        self.assertTrue(any("ambiguous waiver" in e for e in errors))

    def test_unknown_waiver_rule_fails_load(self) -> None:
        self.write_waivers(
            "Z9\tfeature-demo/src/main/kotlin/Demo.kt\t1\t0xFF\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        with self.assertRaises(SystemExit):
            gate.load_waivers()

    def test_waiver_survives_the_line_moving_underneath_it(self) -> None:
        """
        The reason this format exists. On 2026-08-30 this waiver went stale three times in one evening
        while CompanionSurface.kt lost 400 lines to a retirement and gained a job line -- the waived
        thing never changed once. A line number tracks where an exception sits, and nothing anyone
        cares about is *where*.
        """
        rel = "feature-demo/src/main/kotlin/Demo.kt"
        waiver = (
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tPREVIEW_BG\t0xFF080D12\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        self.write_waivers(waiver)

        # line 1
        self.write(rel, "private const val PREVIEW_BG = 0xFF080D12\n")
        first = gate.validate_waivers(gate.load_waivers(), gate.scan_file(self.root / rel, ("P1",)))
        self.assertEqual([], first)

        # same declaration, now line 4
        self.write(rel, "// a\n// b\n// c\nprivate const val PREVIEW_BG = 0xFF080D12\n")
        moved = gate.validate_waivers(gate.load_waivers(), gate.scan_file(self.root / rel, ("P1",)))
        self.assertEqual([], moved)

    def test_waiver_still_fails_when_the_waived_literal_changes(self) -> None:
        """The strictness that must survive the swap: content moving is fine, content changing is not."""
        rel = "feature-demo/src/main/kotlin/Demo.kt"
        self.write_waivers(
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tPREVIEW_BG\t0xFF080D12\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        self.write(rel, "private const val PREVIEW_BG = 0xFF111111\n")

        errors = gate.validate_waivers(gate.load_waivers(), gate.scan_file(self.root / rel, ("P1",)))

        self.assertTrue(any("no line carrying both anchor" in e for e in errors), errors)

    def test_waiver_fails_when_its_anchor_matches_two_lines(self) -> None:
        """An anchor that describes two places waives neither -- it must be narrowed, not guessed."""
        rel = "feature-demo/src/main/kotlin/Demo.kt"
        self.write_waivers(
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tPREVIEW_BG\t0xFF080D12\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        self.write(
            rel,
            "private const val PREVIEW_BG = 0xFF080D12\nprivate const val PREVIEW_BG2 = 0xFF080D12\n",
        )

        errors = gate.validate_waivers(gate.load_waivers(), gate.scan_file(self.root / rel, ("P1",)))

        self.assertTrue(any("ambiguous waiver" in e for e in errors), errors)

    def test_stale_waiver_path_and_exact_match_fail(self) -> None:
        rel = "feature-demo/src/main/kotlin/Demo.kt"
        self.write(rel, "val a = 0xFF010203\n")
        self.write_waivers(
            "P1\tfeature-demo/src/main/kotlin/Missing.kt\tval a\t0xFF010203\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24\n"
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tval a\t0xDEADBEEF\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        findings = gate.scan_file(self.root / rel, ("P1",))
        errors = gate.validate_waivers(gate.load_waivers(), findings)
        self.assertTrue(any("stale waiver path missing" in e for e in errors))
        self.assertTrue(any("no line carrying both anchor" in e for e in errors))

    def test_stale_zero_finding_and_duplicate_waivers_fail(self) -> None:
        rel = "feature-demo/src/main/kotlin/Demo.kt"
        self.write(rel, "private const val PREVIEW_BG = 0xFF080D12 // 0xDEADBEEF\n")
        self.write_waivers(
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tPREVIEW_BG\t0xDEADBEEF\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24\n"
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tPREVIEW_BG\t0xDEADBEEF\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        with self.assertRaises(SystemExit):
            gate.load_waivers()
        self.write_waivers(
            "P1\tfeature-demo/src/main/kotlin/Demo.kt\tPREVIEW_BG\t0xDEADBEEF\t"
            "@Preview-only background never runtime palette source\tproduct-owner\t2026-07-24"
        )
        findings = gate.scan_file(self.root / rel, ("P1",))
        errors = gate.validate_waivers(gate.load_waivers(), findings)
        self.assertTrue(any("zero-finding" in e for e in errors))

    def test_kotlin_source_enumeration_excludes_nested_worktrees(self) -> None:
        canonical = self.write(
            "app/src/main/kotlin/Canonical.kt",
            "fun canonical() {}\n",
        )
        untracked = self.write(
            "app/src/main/kotlin/UntrackedCanonical.kt",
            "fun untracked() {}\n",
        )
        self.write(
            ".worktrees/agent-a/Mimir-rewrite/app/src/main/kotlin/Duplicate.kt",
            "fun duplicateClaude() {}\n",
        )
        self.write(
            ".cursor/worktrees/agent-b/Mimir-rewrite/app/src/main/kotlin/Duplicate.kt",
            "fun duplicateCursor() {}\n",
        )
        self.write(
            "app/build/generated/source/Duplicate.kt",
            "fun buildOnly() {}\n",
        )

        files = gate.kotlin_source_files()
        rels = {p.relative_to(gate.ROOT).as_posix() for p in files}

        self.assertEqual(
            {
                "app/src/main/kotlin/Canonical.kt",
                "app/src/main/kotlin/UntrackedCanonical.kt",
            },
            rels,
        )
        self.assertIn(canonical, files)
        self.assertIn(untracked, files)
        self.assertTrue(all(not gate.is_excluded_worktree_path(rel) for rel in rels))
        self.assertEqual(len(files), len(list(gate.iter_kotlin_source_paths(gate.ROOT))))

    def test_symlink_loop_to_root_terminates_without_duplicate_traversal(self) -> None:
        canonical = self.write(
            "app/src/main/kotlin/Canonical.kt",
            "fun canonical() {}\n",
        )
        (self.root / "loop").symlink_to(self.root, target_is_directory=True)

        rels = [p.relative_to(gate.ROOT).as_posix() for p in gate.iter_repo_paths(gate.ROOT)]
        kotlin_rels = [p.relative_to(gate.ROOT).as_posix() for p in gate.kotlin_source_files()]

        self.assertEqual(rels.count("app/src/main/kotlin/Canonical.kt"), 1)
        self.assertEqual(kotlin_rels.count("app/src/main/kotlin/Canonical.kt"), 1)
        self.assertIn(canonical, gate.kotlin_source_files())

    def test_symlink_alias_to_worktrees_not_traversed(self) -> None:
        canonical = self.write(
            "app/src/main/kotlin/Canonical.kt",
            "fun canonical() {}\n",
        )
        self.write(
            ".worktrees/agent-a/Mimir-rewrite/app/src/main/kotlin/Evil.kt",
            "fun evil() { Modifier.focusable() }\n",
        )
        (self.root / "alias").symlink_to(
            self.root / ".claude" / "worktrees", target_is_directory=True
        )

        kotlin_rels = {p.relative_to(gate.ROOT).as_posix() for p in gate.kotlin_source_files()}
        all_rels = {p.relative_to(gate.ROOT).as_posix() for p in gate.iter_repo_paths(gate.ROOT)}

        self.assertEqual({"app/src/main/kotlin/Canonical.kt"}, kotlin_rels)
        self.assertIn(canonical, gate.kotlin_source_files())
        self.assertFalse(any("worktrees" in rel for rel in kotlin_rels))
        self.assertFalse(any(rel.startswith("alias/") for rel in all_rels))

    def test_symlink_file_included_at_repo_relative_path(self) -> None:
        hidden = self.write(
            "app/build/hidden/Evil.kt",
            "fun evil() { Modifier.focusable() }\n",
        )
        link = self.root / "app" / "src" / "main" / "kotlin" / "Alias.kt"
        link.parent.mkdir(parents=True, exist_ok=True)
        link.symlink_to(hidden)

        rels = {p.relative_to(gate.ROOT).as_posix() for p in gate.kotlin_source_files()}
        self.assertIn("app/src/main/kotlin/Alias.kt", rels)
        self.assertNotIn("app/build/hidden/Evil.kt", rels)
        self.assertGreater(len(gate.scan_file(link, ("F1",))), 0)

    def test_broken_symlink_skipped_without_crash(self) -> None:
        self.write("app/src/main/kotlin/Canonical.kt", "fun canonical() {}\n")
        (self.root / "app" / "src" / "main" / "kotlin" / "Missing.kt").symlink_to(
            self.root / "no-such-target.kt"
        )

        rels = {p.relative_to(gate.ROOT).as_posix() for p in gate.kotlin_source_files()}
        self.assertEqual({"app/src/main/kotlin/Canonical.kt"}, rels)

    # --- R1: regex bracket-escape scan -------------------------------------
    #
    # Target bug class: a Kotlin source compiles a Regex(...)/Pattern.compile(...)/
    # .toRegex() pattern containing a bare, unescaped ']' or '}' outside a character
    # class. OpenJDK's java.util.regex silently accepts these as literal characters;
    # Android's ICU-backed engine raises PatternSyntaxException in the static
    # initializer (GameModeFpsLimiter.kt's original defect).

    def test_find_unescaped_bracket_violations_unit(self) -> None:
        def chars(pattern: str) -> list[str]:
            return [ch for _, ch in gate.find_unescaped_bracket_violations(pattern)]

        self.assertEqual(["]"], chars("abc]def"))
        self.assertEqual(["}"], chars("abc}def"))
        self.assertEqual([], chars("abc\\]def"))
        self.assertEqual([], chars("abc\\}def"))
        self.assertEqual([], chars("[abc]def"))
        # Java/ICU bracket-expression convention: a ']' immediately after '[' or
        # '[^' is a literal class member, not the close (OpenJDK-verified:
        # Pattern.compile("[^]]*").matches("x") is true, .matches("]") is false).
        self.assertEqual([], chars("[^]]*"))
        self.assertEqual([], chars("[]abc]"))
        # Bounded quantifiers and Unicode property escapes must never be flagged.
        self.assertEqual([], chars("[0-9a-f]{64}"))
        self.assertEqual([], chars("a{0,2}"))
        self.assertEqual([], chars("\\p{M}+"))
        self.assertEqual([], chars("\\P{L}+"))
        self.assertEqual([], chars("[^\\p{L}\\p{N}]+"))
        # The exact trailing fragment of the original GameModeFpsLimiter bug.
        pattern = "Fps:([0-9]+)]}]"
        violations = gate.find_unescaped_bracket_violations(pattern)
        self.assertEqual(["]", "}", "]"], [ch for _, ch in violations])
        for idx, ch in violations:
            self.assertEqual(ch, pattern[idx])

    def test_r1_flags_bare_bracket_outside_class(self) -> None:
        rel = "core-resolver/src/main/kotlin/Demo.kt"
        self.write(rel, 'private val P = Regex("abc]def")\n')
        findings = gate.scan_file(self.root / rel, ("R1",))
        self.assertEqual(1, len(findings))
        self.assertEqual("R1", findings[0].rule)
        self.assertEqual("]", findings[0].exact_match)
        self.assertEqual("unescaped ] outside character class", findings[0].detail)

    def test_r1_flags_bare_brace_outside_quantifier(self) -> None:
        rel = "core-resolver/src/main/kotlin/Demo.kt"
        self.write(rel, 'private val P = Regex("abc}def")\n')
        findings = gate.scan_file(self.root / rel, ("R1",))
        self.assertEqual(1, len(findings))
        self.assertEqual("}", findings[0].exact_match)
        self.assertEqual("unescaped } outside character class/quantifier", findings[0].detail)

    def test_r1_ignores_escaped_bracket_and_real_class_member(self) -> None:
        rel = "core-resolver/src/main/kotlin/Demo.kt"
        self.write(
            rel,
            (
                'private val A = Regex("abc\\\\]def")\n'
                'private val B = Regex("[abc]def")\n'
            ),
        )
        self.assertEqual([], gate.scan_file(self.root / rel, ("R1",)))

    def test_r1_ignores_quantifier_and_property_escape_and_leading_class_bracket(self) -> None:
        rel = "core-metadata/src/main/kotlin/Demo.kt"
        self.write(
            rel,
            (
                'private val HEX = Regex("[0-9a-f]{64}")\n'
                'private val MARK = Regex("""\\p{M}+""")\n'
                'private val NOT_BRACKET = Regex("""[^]]*""")\n'
            ),
        )
        self.assertEqual([], gate.scan_file(self.root / rel, ("R1",)))

    def test_r1_flags_concatenated_literal_chain_at_correct_source_offset(self) -> None:
        rel = "mimir-tweaker/app/src/main/kotlin/Demo.kt"
        self.write(
            rel,
            (
                "private val P = Regex(\n"
                '    "abc" +\n'
                '        "def]ghi"\n'
                ")\n"
            ),
        )
        findings = gate.scan_file(self.root / rel, ("R1",))
        self.assertEqual(1, len(findings))
        finding = findings[0]
        self.assertEqual(3, finding.line_no)
        self.assertEqual("]", finding.exact_match)
        self.assertEqual("]", finding.line_text[finding.column - 1])

    def test_r1_matches_original_game_mode_fps_limiter_bug_verbatim(self) -> None:
        # Byte-identical to the pre-fix mimir-tweaker/.../GameModeFpsLimiter.kt
        # FPS_CONFIG_OUTPUT pattern -- the guard's reason for existing.
        rel = "mimir-tweaker/app/src/main/kotlin/Demo.kt"
        self.write(
            rel,
            r'''private val FPS_CONFIG_OUTPUT = Regex(
    "^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+) interventions:\\n" +
        "\\[Name:([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+) Modes: " +
        "\\{4=\\[Game Mode:4,Scaling:([-0-9.]+),Use Angle:(true|false)," +
        "Fps:([0-9]+),Loading Boost Duration:(-?[0-9]+)]}]$",
)
''',
        )
        findings = gate.scan_file(self.root / rel, ("R1",))
        self.assertEqual(["]", "}", "]"], [f.exact_match for f in sorted(findings, key=lambda f: f.column)])
        self.assertTrue(all(f.line_no == 5 for f in findings))

    def test_r1_bails_on_non_literal_argument(self) -> None:
        rel = "core-resolver/src/main/kotlin/Demo.kt"
        self.write(rel, "private fun f(x: String) = Regex(x)\n")
        self.assertEqual([], gate.scan_file(self.root / rel, ("R1",)))

    def test_r1_bails_on_partially_dynamic_concatenation(self) -> None:
        rel = "core-resolver/src/main/kotlin/Demo.kt"
        self.write(rel, 'private fun f(x: String) = Regex("abc" + x + "def]ghi")\n')
        self.assertEqual([], gate.scan_file(self.root / rel, ("R1",)))

    def test_r1_covers_pattern_compile_and_toregex_call_forms(self) -> None:
        rel = "core-resolver/src/main/kotlin/Demo.kt"
        self.write(
            rel,
            (
                'private val A = java.util.regex.Pattern.compile("abc]def")\n'
                'private val B = "abc}def".toRegex()\n'
            ),
        )
        findings = gate.scan_file(self.root / rel, ("R1",))
        self.assertEqual(["]", "}"], sorted(f.exact_match for f in findings))

    def test_r1_second_regex_argument_does_not_hide_a_violation(self) -> None:
        rel = "core-resolver/src/main/kotlin/Demo.kt"
        self.write(rel, 'private val P = Regex("abc]def", RegexOption.IGNORE_CASE)\n')
        findings = gate.scan_file(self.root / rel, ("R1",))
        self.assertEqual(1, len(findings))
        self.assertEqual("]", findings[0].exact_match)

    def test_r1_waiver_suppresses_finding_end_to_end(self) -> None:
        rel = "core-resolver/src/main/kotlin/Demo.kt"
        self.write(rel, 'private val P = Regex("abc]def")\n')
        self.write_waivers(
            "R1\tcore-resolver/src/main/kotlin/Demo.kt\tRegex\t]\t"
            "pending upstream ICU fix, tracked separately\towner\t2026-08-06"
        )
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = gate.run_gate()
        self.assertEqual(0, code)
        self.assertIn("PASS", buf.getvalue())

    # --- D1: statement-position discard ------------------------------------

    def test_d1_flags_bare_statement_discard_of_registered_producer(self) -> None:
        self.write_producers(
            "D1\tFetcher.clearPick\t\tinterrupted-transaction recovery refusal, load-bearing"
        )
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun onReset() {\n"
            "    scope.launch {\n"
            "        Fetcher.clearPick(\n"
            "            context,\n"
            "            key,\n"
            "        )\n"
            "        shelf.refreshTiles()\n"
            "    }\n"
            "}\n",
        )
        findings = gate.scan_file(path, ("D1",))
        self.assertEqual(1, len(findings))
        self.assertEqual("D1", findings[0].rule)
        self.assertEqual(3, findings[0].line_no)
        self.assertEqual("Fetcher.clearPick(", findings[0].exact_match)

    def test_d1_does_not_fire_when_result_is_bound(self) -> None:
        self.write_producers("D1\tFetcher.clearPick\t\tload-bearing recovery refusal")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun onReset() {\n"
            "    scope.launch {\n"
            "        val cleared = Fetcher.clearPick(context, key)\n"
            "        if (cleared) shelf.refreshTiles()\n"
            "    }\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D1",)))

    def test_d1_does_not_fire_when_bound_via_wrapped_binary_expression(self) -> None:
        # Regression fixture for a real false positive: this is N1's actual
        # merged fix (MimirAppCoordinator.kt, the earlier change) with the producer name
        # changed to the test fixture's. The `&&` wraps, leaving the producer
        # call alone on its own line -- the discriminator the old rule used
        # (nothing but whitespace precedes the call on ITS line) could not
        # tell that apart from a genuine bare statement. The result IS bound
        # and branched two lines later; the newline is a formatting accident.
        self.write_producers("D1\tFetcher.clearAsAutomatic\t\tload-bearing recovery refusal")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun onResetHero() {\n"
            "    scope.launch {\n"
            "        val emulated = current.row.itemId as? Emulated\n"
            "        val cleared = emulated != null &&\n"
            "            Fetcher.clearAsAutomatic(context, emulated.gameId)\n"
            "        if (cleared) {\n"
            "            shelf.refreshTiles()\n"
            "        } else {\n"
            "            holdMenuOpenWithNote()\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D1",)))

    def test_d1_flags_the_equivalent_bare_call_the_wrapped_fixture_is_not(self) -> None:
        # The other half of the same regression: a bare, unbound call of the
        # identical producer, on its own line for a genuinely different
        # reason (statement position, not a wrapped assignment), must still
        # fire -- the fix above must not have widened into blindness.
        self.write_producers("D1\tFetcher.clearAsAutomatic\t\tload-bearing recovery refusal")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun onResetHero() {\n"
            "    scope.launch {\n"
            "        Fetcher.clearAsAutomatic(context, emulated.gameId)\n"
            "        shelf.refreshTiles()\n"
            "    }\n"
            "}\n",
        )
        findings = gate.scan_file(path, ("D1",))
        self.assertEqual(1, len(findings))
        self.assertEqual(3, findings[0].line_no)

    def test_d1_does_not_fire_when_bound_via_same_line_binary_expression(self) -> None:
        # The sibling shape from the same real file that never had the bug:
        # assignment and call on one line, `&&` and all -- already correctly
        # inert before this fixture existed; pinned so it stays that way.
        self.write_producers("D1\tFetcher.clearPreviewClipAsAutomatic\t\tload-bearing recovery refusal")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun onResetClip() {\n"
            "    scope.launch {\n"
            "        val emulated = current.row.itemId as? Emulated\n"
            "        val cleared = emulated != null && Fetcher.clearPreviewClipAsAutomatic(\n"
            "            context,\n"
            "            key,\n"
            "        )\n"
            "        if (cleared) shelf.refreshTiles()\n"
            "    }\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D1",)))

    def test_d1_does_not_fire_on_tail_position_consumed_by_block(self) -> None:
        # The call is the sole/last expression of its enclosing block, so its
        # value becomes that block's implicit value (the honest form this
        # codebase already uses for its consumed siblings) -- not a discard.
        self.write_producers("D1\tFetcher.clearPick\t\tload-bearing recovery refusal")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun onReset(): Boolean = runSourceOperation {\n"
            "    Fetcher.clearPick(context, key)\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D1",)))

    def test_d1_does_not_fire_on_argument_position_in_multiline_call(self) -> None:
        # First token of its own line, but it is an ARGUMENT to the enclosing
        # call (next real token after its close paren is ',') -- consumed,
        # not discarded.
        self.write_producers("D1\tFetcher.clearPick\t\tload-bearing recovery refusal")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun onReset() {\n"
            "    logOutcome(\n"
            "        tag,\n"
            "        Fetcher.clearPick(context, key),\n"
            "        extra,\n"
            "    )\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D1",)))

    def test_d1_does_not_fire_on_producer_name_inside_comment_or_string(self) -> None:
        # Required negative fixture: a producer name appearing only inside a
        # `//` comment or a string literal must never fire -- the code view
        # (same lexer F1 already uses) masks both to spaces before D1 runs.
        self.write_producers("D1\tFetcher.clearPick\t\tload-bearing recovery refusal")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "// Fetcher.clearPick(context, key) used to be called here unconditionally.\n"
            "fun onReset() {\n"
            "    val msg = \"Fetcher.clearPick(context, key)\"\n"
            "    log(msg)\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D1",)))

    def test_d1_does_not_fire_on_its_own_function_declaration(self) -> None:
        # Bare (unqualified) producer name: the declaration `fun persist(...)`
        # textually matches "persist(" too -- must be excluded, or the rule
        # would flag every producer's own definition.
        self.write_producers(
            "D1\tpersist\tapp/src/main/kotlin/Demo.kt\tprivate persist(): Boolean, load-bearing"
        )
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "class Store {\n"
            "    private suspend fun persist(context: Context, next: Doc): Boolean {\n"
            "        return write(context, next)\n"
            "    }\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D1",)))

    def test_d1_scope_path_restricts_match_to_named_file(self) -> None:
        # "persist" collides across unrelated classes repo-wide; scope_path is
        # how a bare name stays precise without inventing a fake qualifier.
        self.write_producers(
            "D1\tpersist\tapp/src/main/kotlin/HomeCollectionStore.kt\tHomeCollectionStore.persist, load-bearing"
        )
        scoped = self.write(
            "app/src/main/kotlin/HomeCollectionStore.kt",
            "suspend fun toggleMember() {\n"
            "    mutationMutex.withLock {\n"
            "        persist(\n"
            "            context,\n"
            "            next,\n"
            "        )\n"
            "        Unit\n"
            "    }\n"
            "}\n",
        )
        unrelated = self.write(
            "app/src/main/kotlin/OnboardingStore.kt",
            "private fun persist(context: Context, checkpoint: Checkpoint): Boolean {\n"
            "    ensureLoaded()\n"
            "    persist(context, checkpoint)\n"
            "    unrelatedCleanup()\n"
            "    return true\n"
            "}\n",
        )
        self.assertEqual(1, len(gate.scan_file(scoped, ("D1",))))
        self.assertEqual([], gate.scan_file(unrelated, ("D1",)))

    def test_d1_waiver_suppresses_live_finding_end_to_end(self) -> None:
        self.write_producers("D1\tFetcher.clearPick\t\tload-bearing recovery refusal")
        self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun onReset() {\n"
            "    scope.launch {\n"
            "        Fetcher.clearPick(context, key)\n"
            "        shelf.refreshTiles()\n"
            "    }\n"
            "}\n",
        )
        self.write_waivers(
            "D1\tapp/src/main/kotlin/Demo.kt\tFetcher.clearPick\tFetcher.clearPick(\t"
            "proven unreachable, tracked in OWED-42\towner\t2026-08-12"
        )
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = gate.run_gate()
        self.assertEqual(0, code)
        self.assertIn("PASS", buf.getvalue())

    # --- D2: sealed result collapsed by == outside a decision ---------------

    def test_d2_flags_sealed_comparison_used_as_the_produced_value(self) -> None:
        self.write_producers("D2\tScanOutcome\t\tHealthy vs Failed(reason, kind); reason/kind lost on ==")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun rescan(): Boolean = runSourceOperation {\n"
            "    repository.rescan(rootUri).outcome == ScanOutcome.Healthy\n"
            "}\n",
        )
        findings = gate.scan_file(path, ("D2",))
        self.assertEqual(1, len(findings))
        self.assertEqual("D2", findings[0].rule)
        self.assertEqual("== ScanOutcome.Healthy", findings[0].exact_match)

    def test_d2_does_not_fire_when_bound_value_is_carried_forward(self) -> None:
        # Regression fixture for a real false positive: this is the actual
        # merged fix (MimirLibraryCoordinator.kt, the earlier change) verbatim (types
        # renamed to the test fixture's producer). `outcome` is bound once
        # and then used TWICE -- once in the == comparison, once carried
        # forward whole as `scanOutcome = outcome` -- so the sealed value's
        # Failed(reason, kind) payload is not actually lost: it is still
        # reachable through `scanOutcome` by whatever consumes this block's
        # result. The old rule fired anyway because it only asked "is this
        # == the block's produced value", never "is the compared value ALSO
        # still reachable another way".
        self.write_producers("D2\tScanOutcome\t\tHealthy vs Failed(reason, kind); reason/kind lost on ==")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "rescan = { rootUri ->\n"
            "    runSourceOperation {\n"
            "        val outcome = repository.rescan(rootUri).outcome\n"
            "        SourceOperationOutcome(libraryChanged = outcome == ScanOutcome.Healthy, scanOutcome = outcome)\n"
            "    }\n"
            "},\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D2",)))

    def test_d2_flags_bound_value_used_only_in_the_comparison(self) -> None:
        # The inverse of the fixture above, same binding style: `outcome` is
        # bound to a local but used NOWHERE else -- no `scanOutcome =
        # outcome`, no return, nothing carries it forward. Binding to a
        # local first does not save this from being the same collapse as
        # the bare chain form; the fix must not have over-widened into
        # exempting every `val`-bound comparison regardless of whether the
        # value actually goes anywhere else.
        self.write_producers("D2\tScanOutcome\t\tHealthy vs Failed(reason, kind); reason/kind lost on ==")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "rescan = { rootUri ->\n"
            "    runSourceOperation {\n"
            "        val outcome = repository.rescan(rootUri).outcome\n"
            "        outcome == ScanOutcome.Healthy\n"
            "    }\n"
            "},\n",
        )
        findings = gate.scan_file(path, ("D2",))
        self.assertEqual(1, len(findings))
        self.assertEqual("== ScanOutcome.Healthy", findings[0].exact_match)

    def test_d2_does_not_fire_inside_an_if_condition(self) -> None:
        # The full ScanResult (including Failed.reason) is still returned by
        # this function via `.also { }`; the == here only gates a local side
        # effect, so nothing is actually discarded -- this is the exact
        # false-positive risk found in core-resolver/LibraryRepositoryImpl.kt.
        self.write_producers("D2\tScanOutcome\t\tHealthy vs Failed(reason, kind)")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "override suspend fun rescan(rootUri: String): ScanResult = serializedScan {\n"
            "    scanSourceUntilStable(rootUri, role).also { result ->\n"
            "        if (result.outcome == ScanOutcome.Healthy) refreshAllMedia()\n"
            "    }\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D2",)))

    def test_d2_does_not_fire_on_type_name_inside_comment_or_string(self) -> None:
        self.write_producers("D2\tScanOutcome\t\tHealthy vs Failed(reason, kind)")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "// old code used outcome == ScanOutcome.Healthy directly\n"
            "fun ok(): Boolean {\n"
            "    val msg = \"outcome == ScanOutcome.Healthy\"\n"
            "    log(msg)\n"
            "    return true\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D2",)))

    # --- D3: producer gating an early return with no reporting call --------

    def test_d3_flags_elvis_discard_with_no_reporting_call(self) -> None:
        self.write_producers("D3\tresolveIntent\t\tnullable launch Intent")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun launch() {\n"
            "    scope.launch {\n"
            "        val target = resolveIntent(context, pkg) ?: return@launch\n"
            "        start(target)\n"
            "    }\n"
            "}\n",
        )
        findings = gate.scan_file(path, ("D3",))
        self.assertEqual(1, len(findings))
        self.assertEqual("D3", findings[0].rule)
        self.assertEqual(3, findings[0].line_no)

    def test_d3_hops_through_tail_wrapped_producer_call(self) -> None:
        # The real B1 shape: the producer is the tail expression of a
        # one-line withContext{} wrapper, and the Elvis operator guards the
        # WRAPPER's result, not the producer call's own close paren directly.
        self.write_producers("D3\tresolveIntent\t\tnullable launch Intent")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun launch() {\n"
            "    scope.launch {\n"
            "        val target = withContext(Dispatchers.IO) {\n"
            "            resolveIntent(context, pkg)\n"
            "        } ?: return@launch\n"
            "        start(target)\n"
            "    }\n"
            "}\n",
        )
        findings = gate.scan_file(path, ("D3",))
        self.assertEqual(1, len(findings))
        self.assertEqual(4, findings[0].line_no)

    def test_d3_does_not_fire_when_elvis_block_reports(self) -> None:
        self.write_producers("D3\tresolveIntent\t\tnullable launch Intent")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun launch() {\n"
            "    scope.launch {\n"
            "        val target = resolveIntent(context, pkg) ?: run {\n"
            "            Toast.makeText(context, \"App could not be launched\", LENGTH_SHORT).show()\n"
            "            return@launch\n"
            "        }\n"
            "        start(target)\n"
            "    }\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D3",)))

    def test_d3_recognizes_mimir_feedback_channels(self) -> None:
        self.write_producers("D3\tresolveIntent\t\tnullable launch Intent")
        for name in ("showMimirMessage", "showCompanionFeedbackToast", "showMimirMessageDebug"):
            with self.subTest(channel=name):
                path = self.write(
                    "app/src/main/kotlin/Demo.kt",
                    "fun launch() { val target = resolveIntent(context, pkg) ?: run { "
                    + name + "(context, message); return }; start(target) }",
                )
                findings = gate.scan_file(path, ("D3",))
                self.assertEqual(1 if name.endswith("Debug") else 0, len(findings))

    def test_d3_does_not_fire_on_default_value_elvis_without_return(self) -> None:
        # `?: true` (or any non-return fallback) is an ordinary default value,
        # not an early-exit discard -- D3 only targets the "abandon the
        # function/lambda" shape, per the spec's own naming ("early return").
        self.write_producers("D3\treturnArmed\t\tarm-durability Boolean")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun arm(): Boolean {\n"
            "    val armed = returnArmed(request) ?: true\n"
            "    return armed\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D3",)))

    def test_d3_flags_negated_if_discard(self) -> None:
        self.write_producers("D3\tstageTransfer\t\ttwo distinct refusal reasons collapsed to Boolean")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun moveToPrimary() {\n"
            "    scope.launch {\n"
            "        if (!stageTransfer(token, app)) return@launch\n"
            "        swap()\n"
            "    }\n"
            "}\n",
        )
        findings = gate.scan_file(path, ("D3",))
        self.assertEqual(1, len(findings))
        self.assertEqual(3, findings[0].line_no)

    def test_d3_does_not_fire_when_negated_if_block_reports(self) -> None:
        self.write_producers("D3\tstageTransfer\t\ttwo distinct refusal reasons collapsed to Boolean")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "fun moveToPrimary() {\n"
            "    scope.launch {\n"
            "        if (!stageTransfer(token, app)) {\n"
            "            Toast.makeText(context, \"Could not move to the main screen\", LENGTH_SHORT).show()\n"
            "            return@launch\n"
            "        }\n"
            "        swap()\n"
            "    }\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D3",)))

    def test_d3_does_not_fire_on_producer_name_inside_comment_or_string(self) -> None:
        self.write_producers("D3\tresolveIntent\t\tnullable launch Intent")
        path = self.write(
            "app/src/main/kotlin/Demo.kt",
            "// resolveIntent(context, pkg) ?: return@launch used to be here\n"
            "fun launch() {\n"
            "    val msg = \"resolveIntent(context, pkg) ?: return@launch\"\n"
            "    log(msg)\n"
            "}\n",
        )
        self.assertEqual([], gate.scan_file(path, ("D3",)))

    # --- producer registry loader validation --------------------------------

    def test_producer_registry_validates_header_and_rule_and_note_length(self) -> None:
        gate.PRODUCER_PATH.write_text("bad\theader\n", encoding="utf-8")
        with self.assertRaises(SystemExit):
            gate.load_producers()
        self.write_producers("Q1\tFoo.bar\t\tsome note here")
        with self.assertRaises(SystemExit):
            gate.load_producers()
        self.write_producers("D1\tFoo.bar\t\tshort")
        with self.assertRaises(SystemExit):
            gate.load_producers()

    def test_producer_registry_rejects_duplicate_rows(self) -> None:
        self.write_producers(
            "D1\tFoo.bar\t\tload-bearing result, first row\n"
            "D1\tFoo.bar\t\tload-bearing result, duplicate row"
        )
        with self.assertRaises(SystemExit):
            gate.load_producers()

    def test_producer_registry_missing_file_is_empty(self) -> None:
        self.assertEqual([], gate.load_producers())

    # --- pin the real 688e3855 finding count --------

    def test_must_consume_real_repo_finding_count_pinned(self) -> None:
        # Pins the actual count reported on 688e3855: 11 findings
        # (10 of the spec's predicted 12 -- B2/B3 need one-hop local-variable
        # dataflow this lexical gate does not attempt -- plus one genuine
        # extra the mechanism found beyond the manual 19-site inventory:
        # NativeShelfPackages.kt:181, the same resolveNativeShelfLaunchIntent
        # producer in launchNativeShelfPackage()). This is EXPECTED TO GO TO
        # ZERO once N1/N2/N3 land (the forced-ordering condition in the spec's
        # §6) -- if it does, update this pin, do not delete it blind.
        #
        # 2026-08-15: IT DID. All 11 sites now consume their producer -- e.g.
        # HomeCollectionStore.kt:186 is `if (persist(context, next))`, branched,
        # where it used to be a bare statement. Updated to the empty list rather
        # than deleted, preserving the regression guard.
        #
        # A zero is only evidence if the instrument could still have shown a
        # defect, so that was checked rather than assumed: a synthetic probe
        # file containing a bare `CustomHeroStore.clearAsAutomatic()` in
        # statement position is flagged D1 by this same scan (verified against
        # the live producer registry, 15 producers, 618 main-source files). A
        # regression that reintroduced any of the 11 sites would fail here.
        gate.ROOT = TOOLS.parent
        gate.PRODUCER_PATH = TOOLS / "mimir-must-consume-producers.tsv"
        producers = gate.load_producers()
        self.assertTrue(producers, "an empty registry would make this pin vacuous")
        findings: list[gate.Finding] = []
        for path in gate.main_kotlin_source_files():
            findings.extend(gate.scan_file(path, ("D1", "D2", "D3"), producers=producers))
        sites = sorted((f.rule, f.rel_path, f.line_no) for f in findings)
        self.assertEqual([], sites)

    def test_repo_end_to_end_kotlin_enumeration_semantic(self) -> None:
        gate.ROOT = TOOLS.parent
        gate.WAIVER_PATH = TOOLS / "mimir-ui-invariants-waivers.tsv"
        # gate.PRODUCER_PATH stays pointed at setUp's empty tempdir path (never
        # written by this test): this test is about F1/P1/R1 + enumeration
        # plumbing, not the live must-consume finding count, which is real,
        # currently non-zero by design; see test_must_consume_* below.
        files = gate.kotlin_source_files()
        rels = [p.relative_to(gate.ROOT).as_posix() for p in files]

        self.assertGreater(len(files), 0)
        self.assertTrue(all(gate.is_kotlin_source_rel(rel) for rel in rels))
        self.assertTrue(all(not gate.is_excluded_worktree_path(rel) for rel in rels))
        self.assertEqual(len(files), len(list(gate.iter_kotlin_source_paths(gate.ROOT))))

        buf = io.StringIO()
        with redirect_stdout(buf):
            code = gate.run_gate()
        self.assertEqual(0, code)
        output = buf.getvalue()
        self.assertIn(f"({len(files)} Kotlin files", output)
        reported = int(re.search(r"\((\d+) Kotlin files", output).group(1))
        self.assertEqual(reported, len(files))


if __name__ == "__main__":
    unittest.main()
