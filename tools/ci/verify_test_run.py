#!/usr/bin/env python3
"""Verify completed Gradle test runs and optionally gate critical assertions.

--expect accepts TASK=RESULT_DIRECTORY once per requested execution task.
--gate TASK makes assertions in that expected task blocking.
--completion contains two integers: Gradle's exit code and tee's exit code.
The caller removes old completion/log/XML outputs before invoking Gradle.
No dependencies beyond Python's standard library.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


class InvalidRun(ValueError):
    pass


def verify(log: Path, completion: Path, expected: dict[str, Path], gated: set[str] | None = None) -> str:
    if not expected:
        raise InvalidRun("no expected test result directories supplied")
    gated = set(gated or ())
    if gated - expected.keys():
        raise InvalidRun("gated tasks must be expected: " + ", ".join(sorted(gated - expected.keys())))
    try:
        record = completion.read_text(encoding="utf-8").strip()
        if not re.fullmatch(r"\d+ \d+", record):
            raise InvalidRun("malformed completion record (expected Gradle and tee exit codes)")
        gradle_rc, tee_rc = map(int, record.split())
        output = log.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        raise InvalidRun(f"missing/unreadable completion record or Gradle log: {exc}") from exc
    if tee_rc != 0:
        raise InvalidRun(f"log capture failed (tee exit {tee_rc})")
    if gradle_rc not in (0, 1):
        raise InvalidRun(f"Gradle did not exit normally (exit {gradle_rc})")
    # CI uses --console=plain, so footer/task diagnostics have no terminal escapes.
    footers = re.findall(r"^BUILD (SUCCESSFUL|FAILED) in [^\r\n]+\r?$", output, re.M)
    if footers != ["SUCCESSFUL" if gradle_rc == 0 else "FAILED"]:
        raise InvalidRun("missing, ambiguous, or exit-code-inconsistent Gradle completion footer")

    failed_tasks = set(re.findall(r"^> Task (\S+) FAILED\r?$", output, re.M))
    if failed_tasks - expected.keys():
        raise InvalidRun("non-requested/non-test task failed: " + ", ".join(sorted(failed_tasks - expected.keys())))
    # Every top-level failure must be the standard test-assertion diagnostic.
    # Do not whitelist all *UnitTest FAILED lines: executor crashes use those too.
    sections = re.findall(r"^\* What went wrong:\r?\n(.*?)(?=^\* |^={3,}|\Z)", output, re.M | re.S)
    assertion_tasks: set[str] = set()
    for section in sections:
        match = re.fullmatch(
            r"Execution failed for task '([^']+)'\.\r?\n"
            r"> There were failing tests\. See the report at: [^\r\n]+\r?\n\s*",
            section,
        )
        if match is None or match[1] not in expected:
            raise InvalidRun("Gradle reports an infrastructure/non-assertion failure")
        assertion_tasks.add(match[1])
    if gradle_rc == 1 and (not failed_tasks or failed_tasks != assertion_tasks):
        raise InvalidRun("failed Gradle run is not fully explained by ordinary test failures")
    if gradle_rc == 0 and (failed_tasks or sections):
        raise InvalidRun("successful exit contradicts failure diagnostics")

    failing_xml_tasks: set[str] = set()
    tests_total = 0
    for task, directory in expected.items():
        files = sorted(directory.glob("TEST-*.xml"))
        if not files:
            raise InvalidRun(f"missing JUnit XML for {task}: {directory}")
        task_tests = 0
        task_skipped = 0
        for path in files:
            try:
                suite = ET.parse(path).getroot()
                if suite.tag != "testsuite":
                    raise InvalidRun(f"unexpected JUnit root in {path}")
                counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            except (OSError, ET.ParseError, KeyError, ValueError) as exc:
                raise InvalidRun(f"malformed/unreadable JUnit XML: {path}: {exc}") from exc
            cases = suite.findall("testcase")
            failures = sum(case.find("failure") is not None for case in cases)
            skipped = sum(case.find("skipped") is not None for case in cases)
            errors = sum(case.find("error") is not None for case in cases)
            if any(n < 0 for n in counts.values()) or counts != dict(tests=len(cases), failures=failures, errors=errors, skipped=skipped):
                raise InvalidRun(f"inconsistent JUnit counts: {path}")
            if errors or suite.findall("error") or suite.findall("failure"):
                raise InvalidRun(f"JUnit execution error: {path}")
            for case in cases:
                identity = " ".join((suite.get("name", ""), case.get("name", ""), case.get("classname", "")))
                if "Gradle Test Executor" in identity:
                    raise InvalidRun(f"Gradle test executor failure: {path}")
                for failure in case.findall("failure"):
                    if failure.get("type", "").startswith("org.gradle."):
                        raise InvalidRun(f"Gradle infrastructure exception in JUnit: {path}")
            task_tests += counts["tests"]
            task_skipped += counts["skipped"]
            if failures:
                failing_xml_tasks.add(task)
        if task_tests == 0:
            raise InvalidRun(f"no test cases recorded for {task}")
        if task in gated and task_tests == task_skipped:
            raise InvalidRun(f"all test cases skipped for critical task {task}")
        tests_total += task_tests
    if failing_xml_tasks != failed_tasks:
        raise InvalidRun("JUnit failures and Gradle failed tasks disagree")
    if failed_tasks & gated:
        raise InvalidRun("critical test assertions failed: " + ", ".join(sorted(failed_tasks & gated)))
    return f"complete: {len(expected)} task(s), {tests_total} tests; {len(failed_tasks)} task(s) with report-only test failures"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--completion", required=True, type=Path)
    parser.add_argument("--expect", action="append", required=True, metavar="TASK=DIRECTORY")
    parser.add_argument("--gate", action="append", default=[], metavar="TASK")
    args = parser.parse_args(argv)
    expected: dict[str, Path] = {}
    for item in args.expect:
        task, separator, directory = item.partition("=")
        if not separator or not directory or not re.fullmatch(r"(?::[\w.-]+)+:(?:test|testDebugUnitTest)", task) or task in expected:
            parser.error(f"invalid or duplicate --expect: {item}")
        expected[task] = Path(directory)
    try:
        print(verify(args.log, args.completion, expected, set(args.gate)))
    except InvalidRun as exc:
        print(f"test run rejected: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
