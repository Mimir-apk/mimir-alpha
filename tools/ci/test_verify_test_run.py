"""Stdlib regression tests. Run: python -B -m unittest -v test_verify_test_run"""
from pathlib import Path
import tempfile
import unittest

from verify_test_run import InvalidRun, main, verify


TASK = ":app:testDebugUnitTest"
CRITICAL_TASKS = (
    ":core-model:test", ":core-storage:testDebugUnitTest",
    ":core-launch:testDebugUnitTest", ":core-resolver:testDebugUnitTest",
)


class VerifyTestRunTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.log = self.root / "gradle.log"
        self.completion = self.root / "gradle.exit-code"
        self.expected = {TASK: self.root / "app-results"}
        self.success()
        self.xml(TASK)

    def success(self):
        self.completion.write_text("0 0\n", encoding="utf-8")
        self.log.write_text(f"> Task {TASK}\nBUILD SUCCESSFUL in 3s\n", encoding="utf-8")

    def failure(self, reason="There were failing tests. See the report at: file:///report.html", task=TASK):
        self.completion.write_text("1 0\n", encoding="utf-8")
        self.log.write_text(
            f"> Task {task} FAILED\n\nFAILURE: Build failed with an exception.\n\n"
            f"* What went wrong:\nExecution failed for task '{task}'.\n> {reason}\n\n"
            "* Try:\n> Run with --info\n\n* Exception is:\nstacktrace\n\nBUILD FAILED in 3s\n",
            encoding="utf-8",
        )

    def xml(self, task, failure="", error="", name="example", failure_type="java.lang.AssertionError"):
        directory = self.expected[task]
        directory.mkdir(parents=True, exist_ok=True)
        node = f'<failure type="{failure_type}">{failure}</failure>' if failure else ""
        node += f"<error>{error}</error>" if error else ""
        path = directory / "TEST-example.xml"
        path.write_text(
            f'<testsuite name="ExampleTest" tests="1" failures="{int(bool(failure))}" errors="{int(bool(error))}" skipped="0">'
            f'<testcase name="{name}" classname="ExampleTest">{node}</testcase></testsuite>', encoding="utf-8",
        )
        return path

    def check(self):
        return verify(self.log, self.completion, self.expected)

    def test_successful_complete_run(self):
        self.assertIn("complete:", self.check())

    def test_complete_assertion_failure_allowed(self):
        self.failure()
        self.xml(TASK, failure="expected 1 but was 2")
        self.assertIn("1 task(s) with report-only", self.check())

    def test_each_critical_assertion_failure_is_blocking(self):
        for task in CRITICAL_TASKS:
            with self.subTest(task=task):
                self.expected[task] = self.root / task.split(":")[1]
                self.xml(task, failure="deliberate critical assertion")
                self.failure(task=task)
                with self.assertRaisesRegex(InvalidRun, "critical test assertions failed"):
                    verify(self.log, self.completion, self.expected, {task})
                del self.expected[task]

    def test_all_critical_suites_pass(self):
        for task in CRITICAL_TASKS:
            self.expected[task] = self.root / task.split(":")[1]
            self.xml(task)
        self.assertIn("complete:", verify(self.log, self.completion, self.expected, set(CRITICAL_TASKS)))

    def test_noncritical_assertion_remains_report_only_with_critical_gate(self):
        task = CRITICAL_TASKS[0]
        self.expected[task] = self.root / "critical"
        self.xml(task)
        self.failure()
        self.xml(TASK, failure="ordinary report-only assertion")
        self.assertIn("report-only", verify(self.log, self.completion, self.expected, {task}))

    def test_unknown_critical_task_is_rejected(self):
        with self.assertRaisesRegex(InvalidRun, "gated tasks must be expected"):
            verify(self.log, self.completion, self.expected, {CRITICAL_TASKS[0]})

    def test_completely_skipped_critical_suite_is_rejected(self):
        path = self.xml(TASK)
        path.write_text('<testsuite tests="1" failures="0" errors="0" skipped="1">'
                        '<testcase name="ignored"><skipped/></testcase></testsuite>', encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "all test cases skipped"):
            verify(self.log, self.completion, self.expected, {TASK})

    def test_cli_critical_failure_is_nonzero(self):
        self.failure()
        self.xml(TASK, failure="deliberate assertion")
        self.assertEqual(1, main(["--log", str(self.log), "--completion", str(self.completion),
                                 "--expect", f"{TASK}={self.expected[TASK]}", "--gate", TASK]))

    def test_cli_unknown_gate_is_nonzero(self):
        self.assertEqual(1, main(["--log", str(self.log), "--completion", str(self.completion),
                                 "--expect", f"{TASK}={self.expected[TASK]}", "--gate", CRITICAL_TASKS[0]]))

    def test_partial_xml_across_expected_modules_rejected(self):
        self.expected[":core-model:test"] = self.root / "model-results"
        with self.assertRaisesRegex(InvalidRun, "missing JUnit"):
            self.check()

    def test_complete_multiple_modules(self):
        self.expected[":core-model:test"] = self.root / "model-results"
        self.xml(":core-model:test")
        self.assertIn("2 task(s)", self.check())

    def test_complete_mixed_passing_and_failing_modules(self):
        self.expected[":core-model:test"] = self.root / "model-results"
        self.xml(":core-model:test")
        self.failure()
        self.xml(TASK, failure="assertion")
        self.assertIn("2 task(s)", self.check())

    def test_multiple_complete_assertion_failures(self):
        other = ":core-model:test"
        self.expected[other] = self.root / "model-results"
        self.xml(TASK, failure="assertion")
        self.xml(other, failure="assertion")
        self.failure()
        first = self.log.read_text(encoding="utf-8")
        self.failure(task=other)
        second = self.log.read_text(encoding="utf-8")
        self.log.write_text(first.replace("BUILD FAILED in 3s\n", "================\n") + second, encoding="utf-8")
        self.assertIn("2 task(s) with report-only", self.check())
        with self.assertRaisesRegex(InvalidRun, "critical test assertions failed"):
            verify(self.log, self.completion, self.expected, {other})

    def test_additional_infrastructure_diagnostic_rejected(self):
        self.failure()
        self.xml(TASK, failure="assertion")
        output = self.log.read_text(encoding="utf-8")
        self.log.write_text(output.replace("BUILD FAILED", "* What went wrong:\nGradle daemon disappeared unexpectedly.\n\n* Try:\nRetry\nBUILD FAILED"), encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "infrastructure"):
            self.check()

    def test_zero_cases_rejected(self):
        self.xml(TASK).write_text('<testsuite tests="0" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "no test cases"):
            self.check()

    def test_timeout_without_completion_record_rejected_even_with_xml_and_footer(self):
        self.completion.unlink()
        with self.assertRaisesRegex(InvalidRun, "completion"):
            self.check()

    def test_missing_footer_rejected(self):
        self.log.write_text(f"> Task {TASK}\n", encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "footer"):
            self.check()

    def test_non_test_failed_task_rejected(self):
        self.failure(task=":app:compileDebugKotlin")
        with self.assertRaisesRegex(InvalidRun, "non-test"):
            self.check()

    def test_test_task_infrastructure_failure_rejected_despite_assertion_xml(self):
        self.failure(reason="Process 'Gradle Test Executor 1' finished with non-zero exit value 1")
        self.xml(TASK, failure="an earlier assertion")
        with self.assertRaisesRegex(InvalidRun, "infrastructure"):
            self.check()

    def test_synthetic_executor_xml_rejected(self):
        self.failure()
        self.xml(TASK, failure="could not complete execution", name="Gradle Test Executor 1")
        with self.assertRaisesRegex(InvalidRun, "executor"):
            self.check()

    def test_gradle_exception_in_xml_rejected(self):
        self.failure()
        self.xml(TASK, failure="worker died", failure_type="org.gradle.api.internal.tasks.testing.TestSuiteExecutionException")
        with self.assertRaisesRegex(InvalidRun, "infrastructure"):
            self.check()

    def test_error_node_rejected(self):
        self.failure()
        self.xml(TASK, error="worker crashed")
        with self.assertRaisesRegex(InvalidRun, "execution error"):
            self.check()

    def test_malformed_xml_rejected(self):
        self.xml(TASK).write_text("<testsuite>", encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "malformed"):
            self.check()

    def test_missing_xml_rejected(self):
        self.xml(TASK).unlink()
        with self.assertRaisesRegex(InvalidRun, "missing JUnit"):
            self.check()

    def test_inconsistent_xml_counts_rejected(self):
        path = self.xml(TASK)
        path.write_text(path.read_text().replace('tests="1"', 'tests="2"'), encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "inconsistent"):
            self.check()

    def test_failure_exit_without_test_failure_rejected(self):
        self.completion.write_text("1 0\n", encoding="utf-8")
        self.log.write_text("BUILD FAILED in 3s\n", encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "fully explained"):
            self.check()

    def test_failed_task_requires_corresponding_failing_xml(self):
        self.failure()
        with self.assertRaisesRegex(InvalidRun, "disagree"):
            self.check()

    def test_exit_code_and_footer_disagreement_rejected(self):
        self.completion.write_text("1 0\n", encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "footer"):
            self.check()

    def test_tee_failure_rejected(self):
        self.completion.write_text("0 1\n", encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "log capture"):
            self.check()

    def test_signal_exit_rejected(self):
        self.completion.write_text("143 0\n", encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "normally"):
            self.check()

    def test_malformed_completion_rejected(self):
        self.completion.write_text("0\n", encoding="utf-8")
        with self.assertRaisesRegex(InvalidRun, "malformed completion"):
            self.check()

    def test_cli_success(self):
        self.assertEqual(0, main(["--log", str(self.log), "--completion", str(self.completion), "--expect", f"{TASK}={self.expected[TASK]}"]))


if __name__ == "__main__":
    unittest.main()
