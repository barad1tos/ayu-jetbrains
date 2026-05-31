#!/usr/bin/env python3
"""CLI-boundary tests for verify-plugin-verifier.py."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "verify-plugin-verifier.py"


class VerifyPluginVerifierTest(unittest.TestCase):
    def test_missing_reports_directory_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            reports_dir = Path(temporary_directory) / "missing"

            result = run_gate(reports_dir)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("reports directory not found", result.stderr)

    def test_reports_without_verdict_files_fail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            reports_dir = Path(temporary_directory)

            result = run_gate(reports_dir)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("No verification-verdict.txt files found", result.stderr)

    def test_internal_api_usage_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            target = make_target(Path(temporary_directory))
            (target / "internal-api-usages.txt").write_text(
                "Internal API method com.intellij.Secret.call() invocation\n",
                encoding="utf-8",
            )

            result = run_gate(Path(temporary_directory))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("reported internal API usage", result.stderr)
        self.assertIn("com.intellij.Secret.call()", result.stderr)

    def test_deprecated_and_experimental_usage_is_advisory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            target = make_target(Path(temporary_directory))
            (target / "deprecated-usages.txt").write_text(
                "Deprecated API method com.intellij.Old.call() invocation\n",
                encoding="utf-8",
            )
            (target / "experimental-api-usages.txt").write_text(
                "Experimental API method com.intellij.New.call() invocation\n",
                encoding="utf-8",
            )

            result = run_gate(Path(temporary_directory))

        self.assertEqual(0, result.returncode)
        self.assertIn("deprecated=1", result.stdout)
        self.assertIn("experimental=1", result.stdout)
        self.assertIn("Deprecated API method", result.stderr)
        self.assertIn("Experimental API method", result.stderr)


def make_target(reports_dir: Path) -> Path:
    target = reports_dir / "IC-251" / "plugins" / "com.ayuislands.theme" / "2.7.1"
    target.mkdir(parents=True)
    (target / "verification-verdict.txt").write_text("Compatible\n", encoding="utf-8")
    return target


def run_gate(reports_dir: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--reports-dir", str(reports_dir)],
        check=False,
        text=True,
        capture_output=True,
    )


if __name__ == "__main__":
    unittest.main()
