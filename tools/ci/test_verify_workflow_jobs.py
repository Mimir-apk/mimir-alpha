"""Aggregate policy tests; no GitHub credentials or network needed."""
import unittest

from verify_workflow_jobs import InvalidVerdict, is_full_run, verify


def needs(main="true", app="true", slow="false", tweaker="true", label="false"):
    outputs = dict(main=main, app=app, slow=slow, tweaker=tweaker, label_run=label)
    result = {"changes": {"result": "success", "outputs": outputs},
              "tweaker-unit-tests": {"result": "skipped"}}
    for job, key in (("build", "main"), ("unit", "main"), ("app-unit", "app"),
                     ("app-unit-slow", "slow"), ("tweaker", "tweaker")):
        result[job] = {"result": "success" if outputs[key] == "true" else "skipped"}
    return result


def pr(action="synchronize", draft=False, labels=(), label=""):
    return {"action": action, "pull_request": {"draft": draft, "labels": [{"name": x} for x in labels]},
            "label": {"name": label}}


class WorkflowVerdictTest(unittest.TestCase):
    def test_all_requested_jobs_succeed(self):
        self.assertIn("## CI verdict", verify(needs(), "pull_request", pr()))

    def test_requested_job_cannot_be_skipped_failed_or_cancelled(self):
        for job in ("build", "unit", "app-unit", "tweaker"):
            for status in ("skipped", "failure", "cancelled", ""):
                with self.subTest(job=job, status=status):
                    data = needs()
                    data[job]["result"] = status
                    with self.assertRaisesRegex(InvalidVerdict, job):
                        verify(data, "pull_request", pr())

    def test_slow_job_is_required_when_requested(self):
        data = needs(slow="true")
        data["app-unit-slow"]["result"] = "skipped"
        with self.assertRaisesRegex(InvalidVerdict, "app-unit-slow"):
            verify(data, "push", {})

    def test_docs_only_or_verified_duplicate_may_skip_builds(self):
        self.assertIn("## CI verdict", verify(needs("false", "false", "false", "false"), "push", {}))

    def test_failed_cancelled_or_skipped_changes_fail_full_run(self):
        for status in ("failure", "cancelled", "skipped"):
            with self.subTest(status=status):
                data = needs()
                data["changes"]["result"] = status
                with self.assertRaisesRegex(InvalidVerdict, "changes must succeed"):
                    verify(data, "push", {})

    def test_missing_invalid_and_boolean_outputs_are_rejected(self):
        for key in ("main", "app", "slow", "tweaker", "label_run"):
            for value in (None, "", "TRUE", True):
                with self.subTest(key=key, value=value):
                    data = needs()
                    data["changes"]["outputs"][key] = value
                    with self.assertRaisesRegex(InvalidVerdict, "invalid changes output"):
                        verify(data, "push", {})

    def test_unexpected_dependency_is_rejected(self):
        data = needs()
        data["forgotten-job"] = {"result": "success"}
        with self.assertRaisesRegex(InvalidVerdict, "dependency list"):
            verify(data, "push", {})

    def test_unrequested_job_cannot_run(self):
        data = needs()
        data["app-unit-slow"]["result"] = "success"
        with self.assertRaisesRegex(InvalidVerdict, "expected skipped"):
            verify(data, "push", {})

    def test_slow_label_run_has_only_supplemental_verdict(self):
        event = pr("labeled", label="ci:app-tests")
        self.assertFalse(is_full_run("pull_request", event))
        self.assertIn("Supplemental CI verdict", verify(needs("false", "false", "true", "false", "true"), "pull_request", event))

    def test_pinned_dispatch_has_only_supplemental_verdict(self):
        data = needs("false", "false", "false", "false")
        data["tweaker-unit-tests"]["result"] = "success"
        event = {"inputs": {"tweaker_sha": "a" * 40}}
        self.assertFalse(is_full_run("workflow_dispatch", event))
        self.assertIn("Supplemental CI verdict", verify(data, "workflow_dispatch", event))

    def test_ignored_label_and_unlabelled_draft_are_supplemental(self):
        data = {key: {"result": "skipped"} for key in needs()}
        for event in (pr("labeled", label="unrelated"), pr(draft=True), pr("labeled", label="ci:run-draft")):
            with self.subTest(event=event):
                self.assertFalse(is_full_run("pull_request", event))
                self.assertIn("Supplemental", verify(data, "pull_request", event))

    def test_draft_opt_in_and_normal_dispatch_are_full_runs(self):
        for event in (pr(draft=True, labels=("ci:run-draft",)), pr("labeled", draft=True, label="ci:run-draft")):
            self.assertTrue(is_full_run("pull_request", event))
        self.assertTrue(is_full_run("workflow_dispatch", {"inputs": {}}))

    def test_full_run_cannot_use_supplemental_filter(self):
        with self.assertRaisesRegex(InvalidVerdict, "supplemental run"):
            verify(needs(label="true"), "push", {})

    def test_unknown_event_is_rejected(self):
        with self.assertRaisesRegex(InvalidVerdict, "unsupported"):
            verify(needs(), "unexpected", {})


if __name__ == "__main__":
    unittest.main()
