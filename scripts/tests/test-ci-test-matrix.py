#!/usr/bin/env python3
"""Behavioral checks for benchmark selection and report parity."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import cast

import yaml

CLI = Path(__file__).resolve().parents[1] / "ci-test-matrix.py"
SCOPE = {"sha": "a" * 40, "run": "42", "attempt": "1"}
WORKERS = ("unit-0", "unit-1", "integration")


def write_json(path: Path, data: object) -> None:
    path.write_text(json.dumps(data), encoding="utf-8")


class TestMatrixBenchmark(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.work = Path(directory.name)

    @staticmethod
    def run_cli(*arguments: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CLI), *(str(arg) for arg in arguments)],
            env={
                **os.environ,
                "GITHUB_SHA": SCOPE["sha"],
                "GITHUB_RUN_ID": SCOPE["run"],
                "GITHUB_RUN_ATTEMPT": SCOPE["attempt"],
            },
            capture_output=True,
            text=True,
            check=False,
        )

    def bundle(self, role: str, case: str, *, scope: object = SCOPE) -> Path:
        directory = self.work / "workers" / f"benchmark-worker-1-{role}"
        directory.mkdir(parents=True, exist_ok=True)
        write_json(
            directory / "inventory.json",
            {"scope": scope, "role": role, "tests": {case: 1}},
        )
        (directory / "coverage.ic").write_bytes(b"coverage-data")
        return directory

    def make_workers(self) -> dict[str, int]:
        write_json(
            self.work / "plan.json",
            {
                "scope": SCOPE,
                "units": {"unit-0": ["dev.ayu.Left"], "unit-1": ["dev.ayu.Right"]},
            },
        )
        cases = {f"{index:064x}": 1 for index in range(len(WORKERS))}
        for role, case in zip(WORKERS, cases, strict=True):
            self.bundle(role, case)
        return cases

    def test_workflow_is_isolated_and_times_candidate_before_baseline_comparison(
        self,
    ) -> None:
        path = CLI.parents[1] / ".github/workflows/test-matrix-benchmark.yml"
        workflow = cast(
            dict[object, object], yaml.safe_load(path.read_text(encoding="utf-8"))
        )
        self.assertEqual(
            workflow[True], {"push": {"branches": ["ci/test-matrix-benchmark"]}}
        )
        jobs = cast(dict[str, dict[str, object]], workflow["jobs"])
        self.assertEqual(jobs["candidate"]["needs"], ["plan", "workers", "integration"])
        self.assertNotIn("needs", jobs["integration"])
        self.assertEqual(jobs["workers"]["needs"], "plan")
        self.assertEqual(jobs["parity"]["needs"], ["baseline", "candidate"])
        self.assertNotIn("gate", jobs)

    def test_plan_grows_with_current_tests_and_changes_shard_count(self) -> None:
        discovery = self.work / "discovered"
        discovery.mkdir()
        timings = self.work / "timing.json"
        plan = self.work / "plan.json"
        classes = [f"dev.ayu.Case{index}" for index in range(7)]
        for klass in classes:
            (discovery / f"TEST-{klass}.xml").write_text(
                f'<testsuite name="{klass}"/>', encoding="utf-8"
            )
        rows = [
            {"task": "test", "suite": klass, "seconds": 7 - index}
            for index, klass in enumerate(classes[:-1])
        ]
        rows.append({"task": "test", "suite": "dev.ayu.Deleted", "seconds": 99})
        write_json(timings, rows)
        for count in (2, 4):
            expected_roles = [f"unit-{index}" for index in range(count)]
            result = self.run_cli("plan", discovery, count, plan, "--timings", timings)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), expected_roles)
            assignments: list[str] = []
            for role in expected_roles:
                selected = self.run_cli("select", plan, role)
                self.assertEqual(selected.returncode, 0, selected.stderr)
                self.assertTrue(selected.stdout)
                assignments.extend(selected.stdout.splitlines())
            self.assertCountEqual(assignments, classes)
        original_plan = plan.read_bytes()
        write_json(timings, list(reversed(rows)))
        self.assertEqual(
            self.run_cli("plan", discovery, 4, plan, "--timings", timings).returncode, 0
        )
        self.assertEqual(plan.read_bytes(), original_plan)
        self.assertEqual(self.run_cli("plan", discovery, 4, plan).returncode, 0)

    def test_collect_requires_all_workers_same_attempt_and_no_duplicate_tests(
        self,
    ) -> None:
        expected = self.make_workers()
        collect = ("collect", self.work / "plan.json", self.work / "workers")
        output = self.work / "candidate"
        result = self.run_cli(*collect, output)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads((output / "inventory.json").read_text(encoding="utf-8"))
        self.assertEqual(report["tests"], expected)
        self.assertEqual(len(list((output / "coverage").glob("*.ic"))), len(WORKERS))

        integration = self.bundle("integration", next(iter(expected)))
        duplicate = self.run_cli(*collect, self.work / "duplicate")
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertIn("duplicate", duplicate.stderr)

        self.bundle("integration", "4".zfill(64), scope={**SCOPE, "attempt": "2"})
        foreign = self.run_cli(*collect, self.work / "foreign")
        self.assertNotEqual(foreign.returncode, 0)
        self.assertIn("scope", foreign.stderr)

        integration.rename(self.work / "missing-integration")
        missing = self.run_cli(*collect, self.work / "missing")
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("workers", missing.stderr)

    def test_capture_exports_only_hashed_outcomes_and_rejects_failed_case(self) -> None:
        build = self.work / "build"
        path = build / "test-results" / "test" / "TEST-case.xml"
        path.parent.mkdir(parents=True)
        binary = build / "kover" / "bin-reports" / "test.ic"
        binary.parent.mkdir(parents=True)
        binary.write_bytes(b"coverage")
        path.write_text(
            '<testsuite tests="1"><testcase classname="dev.ayu.Sample" name="private-case">'
            "<skipped/></testcase><system-out>private-output</system-out></testsuite>",
            encoding="utf-8",
        )
        output = self.work / "report"
        result = self.run_cli("capture", "unit-0", build, output)
        self.assertEqual(result.returncode, 0, result.stderr)
        inventory = (output / "inventory.json").read_text(encoding="utf-8")
        self.assertNotIn("private-", inventory)
        self.assertNotIn("dev.ayu.Sample", inventory)
        self.assertEqual(len(json.loads(inventory)["tests"]), 1)
        self.assertEqual((output / "coverage.ic").read_bytes(), b"coverage")

        path.write_text(
            '<testsuite tests="1"><testcase classname="dev.ayu.Sample" name="bad">'
            "<failure/></testcase></testsuite>",
            encoding="utf-8",
        )
        failure = self.run_cli("capture", "unit-0", build, self.work / "failure")
        self.assertNotEqual(failure.returncode, 0)
        self.assertIn("failed", failure.stderr)
        self.assertFalse((self.work / "failure" / "inventory.json").exists())

    def test_compare_detects_line_and_case_changes_even_when_totals_match(self) -> None:
        report = self.work / "report.xml"
        baseline = self.work / "baseline"
        candidate = self.work / "candidate"
        for role, directory in (("baseline", baseline), ("candidate", candidate)):
            directory.mkdir()
            write_json(
                directory / "inventory.json",
                {"scope": SCOPE, "role": role, "tests": {"0" * 64: 1}},
            )
        for covered_line, output in ((3, baseline), (4, candidate)):
            report.write_text(
                f'<report><package name="example"><sourcefile name="Source.kt">'
                f'<line nr="{covered_line}" ci="1"/></sourcefile></package>'
                '<counter type="LINE" covered="1" missed="0"/></report>',
                encoding="utf-8",
            )
            result = self.run_cli("coverage", report, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((output / "report.xml").read_bytes(), report.read_bytes())
        mismatch = self.run_cli("compare", baseline, candidate)
        self.assertNotEqual(mismatch.returncode, 0)
        self.assertIn("coverage", mismatch.stderr)

        (candidate / "coverage.json").write_bytes(
            (baseline / "coverage.json").read_bytes()
        )
        self.assertEqual(self.run_cli("compare", baseline, candidate).returncode, 0)

        write_json(
            candidate / "inventory.json",
            {"scope": SCOPE, "role": "candidate", "tests": {"1" * 64: 1}},
        )
        mismatch = self.run_cli("compare", baseline, candidate)
        self.assertNotEqual(mismatch.returncode, 0)
        self.assertIn("JUnit", mismatch.stderr)


if __name__ == "__main__":
    unittest.main()
