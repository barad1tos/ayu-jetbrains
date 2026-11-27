#!/usr/bin/env python3
"""Verify independent CI test scheduling and the timing artifact contract."""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import NotRequired, TypedDict, cast

import yaml

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
WORKFLOW = SCRIPTS_DIR.parent / ".github/workflows/build.yml"
SUMMARIZER = SCRIPTS_DIR / "summarize-test-timings.py"
TIMINGS_PATH = "build/reports/ci-test-timings.json"
GATE_RESULT = re.compile(r"\$\{\{ needs\.([a-z-]+)\.result \}\}")


class Job(TypedDict):
    needs: NotRequired[str | list[str]]
    steps: list[dict[str, object]]


def named_steps(job: Job) -> dict[str, dict[str, object]]:
    return {str(step["name"]): step for step in job["steps"] if "name" in step}


def load_jobs() -> dict[str, Job]:
    workflow = cast(
        dict[str, dict[str, Job]], yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    )
    return workflow["jobs"]


class CiTestWorkflowTest(unittest.TestCase):
    def test_test_starts_independently_and_gate_requires_both(self) -> None:
        jobs = load_jobs()
        gate = jobs["gate"]
        dependencies = gate.get("needs", [])
        self.assertIsInstance(dependencies, list)
        self.assertIn("build", dependencies)
        self.assertIn("test", dependencies)
        command = cast(str, named_steps(gate)["Check results"]["run"])

        for failed_job, expected_exit in (("build", 1), ("test", 1), ("", 0)):
            with self.subTest(failed_job=failed_job):
                rendered = GATE_RESULT.sub(
                    lambda match, failed=failed_job: (
                        "failure" if match[1] == failed else "success"
                    ),
                    command,
                )
                result = subprocess.run(
                    ["bash", "-e", "-c", rendered],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(
                    expected_exit, result.returncode, result.stdout + result.stderr
                )

        self.assertFalse(jobs["test"].get("needs"))

    def test_timing_steps_require_success(self) -> None:
        steps = named_steps(load_jobs()["test"])
        self.assertEqual(
            "xvfb-run -a scripts/gradle-retry.sh test integrationTest koverXmlReport koverVerify",
            steps["Run tests with coverage"]["run"],
        )
        self.assertEqual(
            "github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository",
            steps["Upload coverage to Codecov"]["if"],
        )
        timing_steps = (
            "Set up uv for test timing export",
            "Summarize test timings",
            "Upload test timings",
        )
        self.assertEqual(
            ["Upload coverage to Codecov", *timing_steps],
            [
                name
                for name in steps
                if name in ("Upload coverage to Codecov", *timing_steps)
            ],
        )
        self.assertEqual(
            "uv run --project scripts --locked --no-dev python scripts/summarize-test-timings.py "
            "--results-dir build/test-results --output " + TIMINGS_PATH,
            steps["Summarize test timings"]["run"],
        )
        for name in timing_steps:
            self.assertIn(
                steps[name].get("if"), (None, "${{ success() }}", "success()")
            )
        self.assertEqual(
            {
                "name": "ci-test-timings-${{ github.run_attempt }}",
                "path": TIMINGS_PATH,
                "if-no-files-found": "error",
            },
            steps["Upload test timings"]["with"],
        )


def junit_report(suite: str, seconds: str = "0.125", tests: str = "2") -> str:
    return (
        f'<testsuite name="{suite}" time="{seconds}" tests="{tests}" failures="0" errors="0" skipped="1">'
        '<testcase name="private-case-name"/>'
        "<system-out>private-stdout-marker</system-out>"
        "<system-err>private-stderr-marker</system-err>"
        "</testsuite>"
    )


def write_report(results: Path, task: str, content: str) -> None:
    directory = results / task
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "TEST-fixture.xml").write_text(content, encoding="utf-8")


def summarize(results: Path, output: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(SUMMARIZER),
            "--results-dir",
            str(results),
            "--output",
            str(output),
        ],
        capture_output=True,
        text=True,
        check=False,
    )


class TestTimingReportTest(unittest.TestCase):
    def timing_paths(self) -> tuple[Path, Path]:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        return Path(directory.name) / "test-results", Path(
            directory.name
        ) / "reports" / "timings.json"

    def test_emits_only_suite_metadata(self) -> None:
        results, output = self.timing_paths()
        write_report(results, "test", junit_report("dev.ayuislands.UnitTest"))
        write_report(
            results,
            "integrationTest",
            junit_report("dev.ayuislands.SampleTest$Nested", "3.5"),
        )

        result = summarize(results, output)

        self.assertEqual(0, result.returncode, result.stderr)
        report = output.read_text(encoding="utf-8")
        self.assertEqual(
            [
                {
                    "task": "integrationTest",
                    "suite": "dev.ayuislands.SampleTest$Nested",
                    "seconds": 3.5,
                    "tests": 2,
                    "failures": 0,
                    "errors": 0,
                    "skipped": 1,
                },
                {
                    "task": "test",
                    "suite": "dev.ayuislands.UnitTest",
                    "seconds": 0.125,
                    "tests": 2,
                    "failures": 0,
                    "errors": 0,
                    "skipped": 1,
                },
            ],
            json.loads(report),
        )
        for marker in (
            "private-case-name",
            "private-stdout-marker",
            "private-stderr-marker",
        ):
            self.assertNotIn(marker, report + result.stdout + result.stderr)

    def test_rejects_doctype_declarations(self) -> None:
        results, output = self.timing_paths()
        write_report(results, "test", junit_report("dev.ayuislands.UnitTest"))
        write_report(
            results,
            "integrationTest",
            "<!DOCTYPE testsuite>" + junit_report("dev.ayuislands.SampleTest"),
        )

        result = summarize(results, output)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("integrationTest JUnit reports", result.stderr)
        self.assertFalse(output.exists())

    def test_rejects_missing_or_invalid_phase(self) -> None:
        results, output = self.timing_paths()
        write_report(results, "test", junit_report("dev.ayuislands.UnitTest"))
        invalid_reports = (
            None,
            "<private-malformed-xml",
            junit_report("../private-suite"),
            junit_report("dev.ayuislands.SampleTest", "NaN"),
            junit_report("dev.ayuislands.SampleTest", tests="-1"),
        )
        for report in invalid_reports:
            with self.subTest(report=report):
                if report is not None:
                    write_report(results, "integrationTest", report)
                result = summarize(results, output)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("integrationTest JUnit reports", result.stderr)
                self.assertNotIn("private-", result.stdout + result.stderr)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
