#!/usr/bin/env python3
"""Exercise screenshot provenance against real files and Git history."""

from __future__ import annotations

import hashlib
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from verify_docs import cli, features, git_utils, screenshots, stamps
from verify_docs.report import Report


class ScreenshotRestampTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.manifest = self.root / "features.yml"
        self.source = self.root / "Panel.kt"
        self.picture = self.root / "panel.png"
        self.source.write_text("class Panel\n")
        self.picture.write_bytes(b"original screenshot")
        self.git("init", "-q", "-b", "main")
        self.commit()
        self.base = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/main", self.base)
        self.git("switch", "-q", "-c", "fix/panel")
        self.manifest.write_text(
            "# Preserve this metadata comment.\n"
            "categories:\n"
            "  appearance:\n"
            "    features:\n"
            "      - id: panel\n"
            "        screenshot:\n"
            "          path: panel.png\n"
            "          sources:\n"
            "            - Panel.kt\n"
            f'          last_verified_sha: "{self.base}"\n'
            f'          content_sha256: "{hashlib.sha256(self.picture.read_bytes()).hexdigest()}"\n'
            '          captured_at: "2026-09-01"\n'
        )
        self.commit()
        for module in (cli, git_utils, screenshots, stamps):
            self.enterContext(patch.object(module, "REPO_ROOT", self.root))
        for module in (cli, features, stamps):
            self.enterContext(patch.object(module, "FEATURES_YAML", self.manifest))
        git_utils.primary_ref.cache_clear()
        self.addCleanup(git_utils.primary_ref.cache_clear)

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            [
                "git",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.com",
                *arguments,
            ],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()

    def commit(self) -> None:
        self.git("add", ".")
        self.git("commit", "-qm", "Fixture")

    def restamp(self, *identifiers: str) -> tuple[int, str]:
        output = io.StringIO()
        with (
            patch.object(sys, "argv", ["verify-docs.py", "--restamp", *identifiers]),
            redirect_stdout(output),
            redirect_stderr(output),
        ):
            try:
                code = cli.main()
            except SystemExit as error:
                code = int(str(error.code))
        return code, output.getvalue()

    def findings(self) -> Report:
        report = Report()
        screenshots.check_screenshots(features.load_features(), report)
        return report

    def test_committed_source_drift_can_be_restamped(self) -> None:
        self.source.write_text("// Reviewed: no visual change.\nclass Panel\n")
        self.commit()
        self.assertTrue(self.findings().has_errors)
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        self.assertEqual([], self.findings().findings)
        self.assertIn("# Preserve this metadata comment.", self.manifest.read_text())
        self.assertIn('captured_at: "2026-09-01"', self.manifest.read_text())
        self.assertEqual(b"original screenshot", self.picture.read_bytes())

    def test_stamp_survives_combined_commit_but_rejects_next_edit(self) -> None:
        self.source.write_text("// Reviewed comment.\nclass Panel\n")
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        self.assertEqual([], self.findings().findings)
        self.commit()
        self.assertEqual([], self.findings().findings)
        self.source.write_text("class DifferentPanel\n")
        self.assertTrue(self.findings().has_errors)
        self.commit()
        self.assertTrue(self.findings().has_errors)

    def test_plain_restamp_does_not_accept_ancestor_source_drift(self) -> None:
        self.source.write_text("class DifferentPanel\n")
        self.commit()
        original = self.manifest.read_bytes()
        code, output = self.restamp()
        self.assertEqual(0, code, output)
        self.assertEqual(original, self.manifest.read_bytes())
        self.assertTrue(self.findings().has_errors)

    def test_unknown_feature_does_not_write_partial_updates(self) -> None:
        original = self.manifest.read_bytes()
        code, _ = self.restamp("panel", "missing")
        self.assertNotEqual(0, code)
        self.assertEqual(original, self.manifest.read_bytes())

    def test_pixel_drift_blocks_source_attestation(self) -> None:
        self.picture.write_bytes(b"different screenshot")
        original = self.manifest.read_bytes()
        code, output = self.restamp("panel")
        self.assertNotEqual(0, code)
        self.assertIn("content_sha256", output)
        self.assertEqual(original, self.manifest.read_bytes())
        self.assertTrue(self.findings().findings)

    def test_missing_source_blocks_attestation(self) -> None:
        self.source.unlink()
        original = self.manifest.read_bytes()
        code, _ = self.restamp("panel")
        self.assertNotEqual(0, code)
        self.assertEqual(original, self.manifest.read_bytes())

    def test_later_source_deletion_is_detected(self) -> None:
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        self.source.unlink()
        self.assertTrue(self.findings().has_errors)

    def test_source_list_changes_invalidate_attestation(self) -> None:
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        (self.root / "Other.kt").write_text("class Other\n")
        self.manifest.write_text(
            self.manifest.read_text().replace(
                "- Panel.kt", "- Panel.kt\n            - Other.kt"
            )
        )
        self.assertTrue(self.findings().has_errors)

    def test_repeated_attestation_is_idempotent(self) -> None:
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        original = self.manifest.read_bytes()
        self.assertEqual(0, self.restamp("panel")[0])
        self.assertEqual(original, self.manifest.read_bytes())

    def test_unselected_screenshot_metadata_is_unchanged(self) -> None:
        (self.root / "other.png").write_bytes(b"another screenshot")
        untouched = (
            "      - id: other\n"
            "        screenshot:\n"
            "          path: other.png\n"
            "          sources:\n"
            "            - Panel.kt\n"
            f'          last_verified_sha: "{self.base}"\n'
            '          sources_sha256: "abcd"\n'
            '          content_sha256: "1234"\n'
        )
        self.manifest.write_text(self.manifest.read_text() + untouched)
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        self.assertTrue(self.manifest.read_text().endswith(untouched))
        findings = self.findings().findings
        self.assertTrue(findings)
        self.assertTrue(all(finding.feature_id == "other" for finding in findings))

    def test_plain_restamp_rebinds_orphan_without_attesting_sources(self) -> None:
        self.manifest.write_text(
            self.manifest.read_text().replace(self.base, "deadbeef")
        )
        code, output = self.restamp()
        self.assertEqual(0, code, output)
        self.assertNotIn("deadbeef", self.manifest.read_text())
        self.assertNotIn("sources_sha256", self.manifest.read_text())
        self.assertEqual([], self.findings().findings)

    def test_source_attestation_survives_orphaned_provenance(self) -> None:
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        provenance = self.git("rev-parse", "--short", "HEAD")
        self.manifest.write_text(
            self.manifest.read_text().replace(provenance, "deadbeef")
        )
        self.assertEqual([], self.findings().findings)
        self.source.write_text("class ChangedPanel\n")
        self.assertTrue(self.findings().has_errors)

    def test_later_pixel_change_is_still_reported(self) -> None:
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        self.picture.write_bytes(b"new pixels")
        self.assertTrue(self.findings().findings)

    def test_missing_pixel_hash_cannot_be_seeded_by_restamp(self) -> None:
        original_hash = hashlib.sha256(self.picture.read_bytes()).hexdigest()
        self.manifest.write_text(self.manifest.read_text().replace(original_hash, ""))
        original = self.manifest.read_bytes()
        code, output = self.restamp("panel")
        self.assertNotEqual(0, code)
        self.assertIn("content_sha256", output)
        self.assertEqual(original, self.manifest.read_bytes())

    def test_failure_after_valid_feature_does_not_write(self) -> None:
        self.manifest.write_text(
            self.manifest.read_text() + "      - id: unavailable\n"
        )
        original = self.manifest.read_bytes()
        code, output = self.restamp("panel", "unavailable")
        self.assertNotEqual(0, code)
        self.assertIn("unavailable", output)
        self.assertEqual(original, self.manifest.read_bytes())

    def test_directory_stamp_tracks_nested_changes(self) -> None:
        source_dir = self.root / "panels"
        source_dir.mkdir()
        nested = source_dir / "nested"
        nested.mkdir()
        source_file = nested / "Panel.kt"
        source_file.write_text("class Panel\n")
        self.manifest.write_text(
            self.manifest.read_text().replace("- Panel.kt", "- panels")
        )
        self.commit()
        code, output = self.restamp("panel")
        self.assertEqual(0, code, output)
        self.commit()
        self.assertEqual([], self.findings().findings)
        original = source_file.read_bytes()
        source_file.write_text("class UpdatedPanel\n")
        self.assertTrue(self.findings().has_errors)
        source_file.write_bytes(original)
        self.assertEqual([], self.findings().findings)
        extra = nested / "Added.kt"
        extra.write_text("class Added\n")
        self.assertTrue(self.findings().has_errors)
        extra.unlink()
        self.assertEqual([], self.findings().findings)
        source_file.rename(nested / "Renamed.kt")
        self.assertTrue(self.findings().has_errors)
        (nested / "Renamed.kt").rename(source_file)
        source_file.unlink()
        self.assertTrue(self.findings().has_errors)


if __name__ == "__main__":
    unittest.main()
