#!/usr/bin/env python3
"""Write a JUnit XML digest to the GitHub step summary (or stdout).

Gradle writes one TEST-<class>.xml per test class under
<module>/build/test-results/<task>/. This script walks the roots it is given,
and emits Markdown with

  * a per-module (and per-task) totals table,
  * EVERY failing test as Class.method plus the first line of its message
    (cut at 200 characters, Markdown pipes escaped),
  * a per-class table (tests, failures, errors, skipped), collapsed.

It only reports. It exits 0 whatever it finds, even when it cannot read a file or
fails on its own, because the job's own status (the Gradle exit code) decides pass
or fail. Only a malformed command line (argparse) exits 2.

  --annotate  also prints GitHub workflow commands to stdout: one
              "::warning title=Test failure::Class.method - first message line" per
              failing test (the first 50, then one line counting the rest), and a
              "::warning title=No test results::" when no XML was found at all. A job
              whose test step is continue-on-error (report-only unit tests) ends green,
              so these warnings are what keeps a failure, a timeout or a crash visible.
              GitHub lists only the first ten warnings of a step on the run page; the
              step log has them all.

Standard library only, Python 3.8 or newer.

  CI:     python3 tools/ci/junit_summary.py --title "Unit tests" .
          (writes to $GITHUB_STEP_SUMMARY, appending)
  Local:  python3 tools/ci/junit_summary.py path/to/build/test-results
          (no $GITHUB_STEP_SUMMARY: Markdown goes to stdout)

A root may be a build/test-results folder, a module folder, or the whole
checkout: every TEST-*.xml with a build/test-results segment in its path beneath it is
read (.git, .agents, evidence, docs, marketing and node_modules are never entered, so the
old XML the repository tracks as evidence is not mistaken for a run's results). A root
with no such XML but TEST-*.xml of its own, directly or one folder down, is read as a
bare folder. The module is the path in front of "build/test-results" (relative to
--base, default the current directory) and the task is the folder after it.
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MESSAGE_LIMIT = 200
ANNOTATION_CAP = 50
# GitHub rejects a step summary over 1 MiB; stay well under so the header and totals always land.
SUMMARY_BUDGET_BYTES = 900_000
# Never descended into. The repository tracks old test XML as historical evidence; reading
# it reported a hundred failures that no run had produced.
SKIP_DIRS = {".git", ".agents", "evidence", "docs", "marketing", "node_modules", ".gradle", "binary"}


def first_line(text, limit=MESSAGE_LIMIT):
    """First non-empty line of text, cut at limit characters."""
    for line in (text or "").splitlines():
        line = line.strip()
        if line:
            return line if len(line) <= limit else line[: limit - 3] + "..."
    return ""


def cell(text):
    """Make text safe inside a Markdown table cell (a pipe ends the cell, angle brackets become tags)."""
    return (
        text.replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\r", " ")
        .replace("\n", " ")
    )


def code(text):
    """A Markdown code span that survives pipes and backticks inside a table cell."""
    return "`" + text.replace("`", "'").replace("|", "\\|").replace("\n", " ") + "`"


def locate(xml_path, root, base):
    """Return (module, task) for one result file."""
    parts = xml_path.resolve().parts
    for i in range(len(parts) - 2, 0, -1):
        if parts[i] == "test-results" and parts[i - 1] == "build":
            module_dir = Path(*parts[: i - 1])
            task = parts[i + 1] if i + 1 < len(parts) - 1 else ""
            try:
                module = module_dir.relative_to(base).as_posix()
            except ValueError:
                module = "/".join(module_dir.parts[-2:])
            return (module if module not in ("", ".") else "(root)"), task
    # Not under build/test-results: a bare folder of XML. Name it after the root and the subfolder.
    top = root.resolve()
    relative = xml_path.resolve().parent.relative_to(top).as_posix() if top in xml_path.resolve().parents else "."
    return (top.name or "(root)"), ("" if relative == "." else relative)


def in_results_folder(path):
    """True when the path has a build/test-results segment: where Gradle writes a run's XML."""
    parts = path.resolve().parts
    return any(parts[i] == "build" and parts[i + 1] == "test-results" for i in range(len(parts) - 1))


def find_xml(root):
    """The TEST-*.xml files under root that a Gradle run wrote.

    Only a path with a build/test-results segment counts. A root that has none (a bare folder of XML given on
    the command line) is read as it stands: its own TEST-*.xml files and those one folder down, never deeper,
    so a checkout with old XML tracked somewhere can never be mistaken for results.
    """
    root = Path(root)
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name.startswith("TEST-") and name.endswith(".xml"):
                found.append(Path(dirpath) / name)
    gradle = [path for path in found if in_results_folder(path)]
    if gradle:
        return gradle
    top = root.resolve()
    return [path for path in found if path.resolve().parent == top or path.resolve().parent.parent == top]


def read_suites(xml_path):
    """Yield (suite_name, tests, failures, errors, skipped, failing) for each testsuite in a file."""
    tree = ET.parse(str(xml_path))
    top = tree.getroot()
    suites = [top] if top.tag == "testsuite" else list(top.iter("testsuite"))
    for suite in suites:
        suite_name = suite.get("name") or xml_path.stem[len("TEST-"):]
        tests = failures = errors = skipped = 0
        failing = []
        for case in suite.iter("testcase"):
            tests += 1
            if case.find("skipped") is not None:
                skipped += 1
            hit = None
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is not None:
                    hit = (kind, node)
                    break
            if hit is None:
                continue
            kind, node = hit
            if kind == "failure":
                failures += 1
            else:
                errors += 1
            message = first_line(node.get("message")) or first_line(node.text) or node.get("type") or ""
            failing.append((case.get("classname") or suite_name, case.get("name") or "(unnamed)", kind, message))
        # A suite can report a failure with no testcase element (a class that failed to load).
        # Count it so the totals never disagree with the XML's own header.
        declared = int(suite.get("failures") or 0) + int(suite.get("errors") or 0)
        if declared > failures + errors:
            extra = declared - failures - errors
            errors += extra
            note = first_line(suite.findtext("system-err")) or "suite reported a failure with no testcase element"
            failing.extend((suite_name, "(suite-level)", "error", note) for _ in range(extra))
        yield suite_name, tests, failures, errors, skipped, failing


def collect(roots, base):
    classes = {}  # (module, task, class) -> [tests, failures, errors, skipped]
    failures = []  # (module, task, class, method, kind, message)
    unreadable = []
    seen = set()
    for root in roots:
        root = Path(root)
        if not root.exists():
            unreadable.append((str(root), "path does not exist"))
            continue
        for xml_path in find_xml(root):
            real = xml_path.resolve()
            if real in seen:
                continue
            seen.add(real)
            module, task = locate(xml_path, root, base)
            try:
                for suite_name, tests, fails, errs, skips, failing in read_suites(xml_path):
                    row = classes.setdefault((module, task, suite_name), [0, 0, 0, 0])
                    for index, value in enumerate((tests, fails, errs, skips)):
                        row[index] += value
                    for classname, method, kind, message in failing:
                        failures.append((module, task, classname, method, kind, message))
            except (ET.ParseError, OSError, ValueError) as error:
                unreadable.append((str(xml_path), type(error).__name__ + ": " + str(error)))
    return classes, failures, unreadable


def render(title, classes, failures, unreadable, roots):
    totals = {}  # (module, task) -> [classes, tests, failures, errors, skipped]
    for (module, task, _), row in classes.items():
        entry = totals.setdefault((module, task), [0, 0, 0, 0, 0])
        entry[0] += 1
        for index in range(4):
            entry[index + 1] += row[index]
    grand = [sum(entry[i] for entry in totals.values()) for i in range(5)]

    out = [f"## {title}", ""]
    if not classes:
        out.append("**No test results.** No JUnit XML found under " + ", ".join(code(str(r)) for r in roots) + ".")
        out.append("")
        out.append(
            "The run did not finish (a timeout or a crash), or the build failed before any test ran. "
            "Gradle writes a test task's XML only when the task ends, so a killed run leaves nothing."
        )
        out.append("")
    else:
        out.append(
            f"**{grand[1]:,} tests, {grand[2]:,} failures, {grand[3]:,} errors, {grand[4]:,} skipped** "
            f"in {grand[0]:,} classes across {len(totals)} module/task groups."
        )
        out.append("")
        out += ["| Module | Task | Classes | Tests | Failures | Errors | Skipped |", "| --- | --- | ---: | ---: | ---: | ---: | ---: |"]
        for (module, task), entry in sorted(totals.items()):
            bad = " **!**" if entry[2] or entry[3] else ""
            out.append(f"| {cell(module)} | {cell(task) or '-'} | {entry[0]} | {entry[1]} | {entry[2]} | {entry[3]} | {entry[4]}{bad} |")
        out.append("")

        if failures:
            out.append(f"### Failing tests ({len(failures)})")
            out.append("")
            out += ["| Module | Task | Test | Kind | Message |", "| --- | --- | --- | --- | --- |"]
            used = sum(len(line) + 1 for line in out)
            listed = 0
            for module, task, classname, method, kind, message in sorted(failures):
                line = f"| {cell(module)} | {cell(task) or '-'} | {code(classname + '.' + method)} | {kind} | {cell(message)} |"
                if used + len(line) + 1 > SUMMARY_BUDGET_BYTES // 2:
                    break
                out.append(line)
                used += len(line) + 1
                listed += 1
            if listed < len(failures):
                out.append("")
                out.append(f"{len(failures) - listed} more failing tests are not listed: the summary size limit was reached. Read the JUnit XML.")
            out.append("")
        else:
            out += ["No failing tests.", ""]

        out.append(f"<details><summary>Per-class results ({len(classes)} classes)</summary>")
        out.append("")
        out += ["| Module | Task | Class | Tests | Failures | Errors | Skipped |", "| --- | --- | --- | ---: | ---: | ---: | ---: |"]
        ordered = sorted(classes.items(), key=lambda item: (0 if item[1][1] or item[1][2] else 1, item[0]))
        used = sum(len(line) + 1 for line in out)
        listed = 0
        for (module, task, name), row in ordered:
            line = f"| {cell(module)} | {cell(task) or '-'} | {cell(name)} | {row[0]} | {row[1]} | {row[2]} | {row[3]} |"
            if used + len(line) + 1 > SUMMARY_BUDGET_BYTES:
                break
            out.append(line)
            used += len(line) + 1
            listed += 1
        if listed < len(ordered):
            out.append("")
            out.append(f"{len(ordered) - listed} more classes are not listed: the summary size limit was reached.")
        out += ["", "</details>", ""]

    if unreadable:
        out.append(f"### Unreadable results ({len(unreadable)})")
        out.append("")
        for path, why in unreadable[:20]:
            out.append(f"- {code(path)}: {cell(why)}")
        if len(unreadable) > 20:
            out.append(f"- ... and {len(unreadable) - 20} more")
        out.append("")
    return "\n".join(out) + "\n", grand


def command_data(text):
    """Escape the data of a GitHub workflow command (a literal %, CR or LF would end or corrupt it)."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotations(classes, failures, unreadable, roots):
    """The ::warning lines for --annotate, as (is_no_results_warning, line) pairs."""
    lines = []
    if not classes:
        where = ", ".join(str(r) for r in roots)
        lines.append((True, "::warning title=No test results::" + command_data(
            f"No JUnit XML found under {where}: the run did not finish (timeout or crash)")))
    for module, task, classname, method, kind, message in sorted(failures)[:ANNOTATION_CAP]:
        text = f"{classname}.{method}" + (f" \u2014 {message}" if message else "")
        lines.append((False, "::warning title=Test failure::" + command_data(text)))
    if len(failures) > ANNOTATION_CAP:
        lines.append((False, "::warning title=Test failure::" + command_data(
            f"{len(failures) - ANNOTATION_CAP} more failing tests are not annotated (the cap is {ANNOTATION_CAP}); see the run summary")))
    if unreadable:
        lines.append((False, "::warning title=Unreadable test results::" + command_data(
            f"{len(unreadable)} JUnit XML files could not be read; see the run summary")))
    return lines


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8")  # a Windows console would choke on the dash in an annotation
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description="Summarise Gradle JUnit XML into Markdown.")
    parser.add_argument("roots", nargs="+", help="build/test-results folder, module folder or checkout to scan")
    parser.add_argument("--title", default="Test results", help="heading for the summary section")
    parser.add_argument("--base", default=".", help="module paths are shown relative to this folder (default: current directory)")
    parser.add_argument("--summary", default=os.environ.get("GITHUB_STEP_SUMMARY"), help="file to append to (default: $GITHUB_STEP_SUMMARY, else stdout)")
    parser.add_argument("--annotate", action="store_true", help="also print a ::warning per failing test (first 50) and when no results were found")
    args = parser.parse_args(argv)

    base = Path(args.base).resolve()
    classes, failures, unreadable = collect(args.roots, base)
    markdown, grand = render(args.title, classes, failures, unreadable, args.roots)
    status = (
        f"junit_summary: {args.title}: {grand[1]} tests, {grand[2]} failures, {grand[3]} errors, "
        f"{grand[4]} skipped in {grand[0]} classes; {len(unreadable)} unreadable files"
    )
    if args.summary:
        with open(args.summary, "a", encoding="utf-8", newline="") as handle:
            handle.write(markdown)
        print(status)
    else:
        sys.stdout.write(markdown)
        print(status, file=sys.stderr)
    # A missing result must never pass silently, so that warning also goes out in CI (summary file) mode.
    for no_results, line in annotations(classes, failures, unreadable, args.roots):
        if args.annotate or (no_results and args.summary):
            print(line)
        elif no_results:
            print(line, file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # report, never fail the job on the reporter itself
        print(f"junit_summary: could not summarise: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(0)
