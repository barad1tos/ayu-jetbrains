#!/usr/bin/env python3
"""Run the workflow's eligibility script against Dependabot update scenarios."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import TypedDict, cast

import yaml

WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/dependabot-automerge.yml"


class Step(TypedDict, total=False):
    id: str
    run: str


class Job(TypedDict):
    steps: list[Step]


class Workflow(TypedDict):
    jobs: dict[str, Job]


def eligibility_script() -> str:
    workflow = cast(Workflow, yaml.safe_load(WORKFLOW.read_text()))
    for step in workflow["jobs"]["automerge"]["steps"]:
        if step.get("id") == "eligibility" and "run" in step:
            return step["run"]
    raise ValueError("Workflow must contain an eligibility script")


class DependabotAutomergeTest(unittest.TestCase):
    def assert_eligible(self, update_type: str, names: str, expected: bool) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-euo", "pipefail", "-c", eligibility_script()],
                env={**os.environ, "UPDATE_TYPE": update_type, "DEPENDENCY_NAMES": names,
                     "GITHUB_OUTPUT": str(output)},
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(f"eligible={str(expected).lower()}\n", output.read_text())

    def test_allows_patch_and_minor(self) -> None:
        for update in ("patch", "minor"):
            for names in ("actions/checkout", "org.junit.jupiter:junit-jupiter",
                          "github/codeql-action/analyze,github/codeql-action/init"):
                with self.subTest(update=update, names=names):
                    self.assert_eligible(f"version-update:semver-{update}", names, True)

    def test_rejects_major_and_unknown(self) -> None:
        for update in ("version-update:semver-major", "", "version-update:unknown"):
            with self.subTest(update=update):
                self.assert_eligible(update, "actions/checkout", False)

    def test_protects_toolchain_groups(self) -> None:
        for dependency in ("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin:kotlin-stdlib",
                           "org.jetbrains.intellij.platform", "com.jetbrains.intellij.idea:ideaIC"):
            for names in (dependency, f"org.junit.jupiter:junit-jupiter,{dependency}"):
                with self.subTest(names=names):
                    self.assert_eligible("version-update:semver-patch", names, False)

    def test_rejects_empty_names(self) -> None:
        for names in ("", " ", "actions/checkout,", ",actions/checkout"):
            with self.subTest(names=names):
                self.assert_eligible("version-update:semver-patch", names, False)

    def test_names_are_not_executed(self) -> None:
        self.assert_eligible("version-update:semver-patch", "$(exit 97)", True)


if __name__ == "__main__":
    unittest.main()
