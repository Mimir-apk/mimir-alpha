#!/usr/bin/env python3
"""Fail the aggregate check when a requested job did not succeed."""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys


class InvalidVerdict(ValueError):
    pass


def is_full_run(event_name: str, event: dict) -> bool:
    if event_name == "workflow_dispatch":
        return not event.get("inputs", {}).get("tweaker_sha")
    if event_name == "pull_request":
        pr = event["pull_request"]
        if event.get("action") == "labeled":
            return bool(pr["draft"] and event.get("label", {}).get("name") == "ci:run-draft")
        return not pr["draft"] or any(label["name"] == "ci:run-draft" for label in pr["labels"])
    if event_name == "push":
        return True
    raise InvalidVerdict(f"unsupported workflow event: {event_name}")


def verify(needs: dict, event_name: str, event: dict) -> str:
    full = is_full_run(event_name, event)
    jobs = {"build": "main", "unit": "main", "app-unit": "app",
            "app-unit-slow": "slow", "tweaker": "tweaker"}
    required_ids = {"changes", "tweaker-unit-tests", *jobs}
    if set(needs) != required_ids:
        raise InvalidVerdict("aggregate dependency list does not match its contract")
    changes = needs["changes"]
    if changes["result"] == "skipped" and not full:
        if any(job["result"] != "skipped" for job in needs.values()):
            raise InvalidVerdict("an ignored event unexpectedly ran a job")
        return "Supplemental CI verdict: no builds requested by this event"
    if changes["result"] != "success":
        raise InvalidVerdict("changes must succeed before job skips can be trusted")
    outputs = changes["outputs"]
    for key in {"main", "app", "slow", "tweaker", "label_run"}:
        if outputs.get(key) not in ("true", "false"):
            raise InvalidVerdict(f"missing or invalid changes output: {key}")
    if full and outputs["label_run"] != "false":
        raise InvalidVerdict("a supplemental run cannot establish the full verdict")
    pinned = event_name == "workflow_dispatch" and bool(event.get("inputs", {}).get("tweaker_sha"))
    rows = []
    for job_id, output in [*jobs.items(), ("tweaker-unit-tests", None)]:
        wanted = pinned if output is None else outputs[output] == "true"
        expected = "success" if wanted else "skipped"
        actual = needs[job_id]["result"]
        if actual != expected:
            raise InvalidVerdict(f"{job_id}: expected {expected}, got {actual}")
        rows.append(f"| {job_id} | {actual} | {'yes' if wanted else 'no'} |")
    title = "CI verdict" if full else "Supplemental CI verdict"
    return f"## {title}\n\n| Job | Result | Requested |\n| --- | --- | --- |\n" + "\n".join(rows)


def main() -> int:
    try:
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        result = verify(json.loads(os.environ["NEEDS_JSON"]), os.environ["GITHUB_EVENT_NAME"], event)
    except (InvalidVerdict, OSError, ValueError, KeyError, TypeError) as exc:
        print(f"::error title=CI verdict rejected::{exc}", file=sys.stderr)
        return 1
    print(result)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(result + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
