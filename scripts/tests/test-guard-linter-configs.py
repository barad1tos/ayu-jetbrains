#!/usr/bin/env python3
"""Exercise the coverage-config guard against real staged Git snapshots."""

from __future__ import annotations

import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, cast

import yaml

GUARD = Path(__file__).resolve().parents[1] / "guard-linter-configs.py"
CONFIG = """coverage:
  status:
    project:
      default:
        target: 80%
        threshold: 2%
    patch:
      default:
        target: 80%
        informational: true
ignore:
  - "licensing/**"
  - "settings/**"
comment:
  require_changes: true
"""
REMOVAL = CONFIG.replace('  - "licensing/**"\n', "")


class ConfigGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.git("init", "-q", "-b", "main")
        self.stage("codecov.yml", CONFIG)
        self.stage("detekt.yml", "complexity: {}\n")
        self.stage(".editorconfig", "root = true\n")
        self.git("commit", "-qm", "Fixture")

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", *arguments],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=True,
        ).stdout

    def stage(self, path: str, content: str) -> None:
        (self.root / path).write_text(content)
        self.git("add", "--", path)

    def guard(self, directory: Path | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(GUARD)],
            cwd=directory or self.root,
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_allowed(self) -> None:
        result = self.guard()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def assert_blocked(self) -> None:
        result = self.guard()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("--no-verify", result.stdout + result.stderr)

    def test_allows_removing_an_ignore(self) -> None:
        self.stage("codecov.yml", REMOVAL)
        self.assert_allowed()

    def test_allows_empty_ignore_list(self) -> None:
        self.stage("codecov.yml", CONFIG.replace('ignore:\n  - "licensing/**"\n  - "settings/**"', "ignore: []"))
        self.assert_allowed()

    def test_allows_removing_ignore_section(self) -> None:
        self.stage("codecov.yml", CONFIG.replace('ignore:\n  - "licensing/**"\n  - "settings/**"\n', ""))
        self.assert_allowed()

    def test_no_staged_protected_changes_are_a_noop(self) -> None:
        self.stage("source.kt", "class Panel\n")
        self.assert_allowed()

    def test_ignores_unstaged_weakening_when_index_is_safe(self) -> None:
        self.stage("codecov.yml", REMOVAL)
        (self.root / "codecov.yml").write_text(CONFIG.replace("80%", "1%"))
        self.assert_allowed()

    def test_rejects_staged_weakening_even_when_worktree_is_safe(self) -> None:
        self.stage("codecov.yml", CONFIG.replace("80%", "1%"))
        (self.root / "codecov.yml").write_text(REMOVAL)
        self.assert_blocked()

    def test_rejects_added_replaced_duplicated_or_reordered_ignores(self) -> None:
        for replacement in (
            '  - "licensing/**"\n  - "settings/**"\n  - "**"',
            '  - "**"',
            '  - "settings/**"\n  - "settings/**"',
            '  - "settings/**"\n  - "licensing/**"',
        ):
            with self.subTest(replacement=replacement):
                self.stage("codecov.yml", CONFIG.replace('  - "licensing/**"\n  - "settings/**"', replacement))
                self.assert_blocked()

    def test_rejects_other_settings_changes_alongside_removal(self) -> None:
        for altered in (
            REMOVAL.replace("80%", "79%"),
            REMOVAL.replace("2%", "3%"),
            REMOVAL.replace("informational: true", "informational: false"),
            REMOVAL.replace("require_changes: true", "require_changes: 1"),
            REMOVAL.replace("comment:\n  require_changes: true\n", ""),
            REMOVAL + "codecov:\n  require_ci_to_pass: false\n",
        ):
            with self.subTest(altered=altered):
                self.stage("codecov.yml", altered)
                self.assert_blocked()

    def test_rejects_malformed_ambiguous_or_non_string_ignores(self) -> None:
        for altered in (
            REMOVAL + "bad: [\n",
            REMOVAL + 'ignore: ["**"]\n',
            REMOVAL.replace("target: 80%", "target: 80%\n        target: 1%"),
            REMOVAL.replace('  - "settings/**"', "  - true"),
            REMOVAL.replace('  - "settings/**"', '  - !custom "settings/**"'),
            REMOVAL.replace('ignore:\n  - "settings/**"', "ignore: null"),
            REMOVAL.replace('ignore:\n  - "settings/**"', "ignore: &cycle [*cycle]"),
        ):
            with self.subTest(altered=altered):
                self.stage("codecov.yml", altered)
                self.assert_blocked()

    def test_rejects_other_protected_changes(self) -> None:
        self.stage("codecov.yml", REMOVAL)
        self.stage("detekt.yml", "complexity: {active: false}\n")
        self.assert_blocked()

    def test_rejects_editorconfig_change(self) -> None:
        self.stage(".editorconfig", "root = false\n")
        self.assert_blocked()

    def test_rejects_config_deletion(self) -> None:
        self.git("rm", "-q", "codecov.yml")
        self.assert_blocked()

    def run_hook(self) -> subprocess.CompletedProcess[str]:
        config_path = GUARD.parent.parent / ".pre-commit-config.yaml"
        config = cast(dict[str, Any], yaml.safe_load(config_path.read_text()))
        hooks = [
            hook
            for repository in config["repos"]
            if repository["repo"] == "local"
            for hook in repository["hooks"]
            if hook["id"] == "guard-linter-configs"
        ]
        self.assertEqual(1, len(hooks))
        hooks[0]["entry"] = shlex.join([sys.executable, str(GUARD)])
        (self.root / ".pre-commit-config.yaml").write_text(yaml.safe_dump({"repos": [{"repo": "local", "hooks": hooks}]}))
        self.git("add", ".pre-commit-config.yaml")
        return subprocess.run(
            ["pre-commit", "run", "guard-linter-configs"],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_hook_rejects_deleted_config(self) -> None:
        for path in ("codecov.yml", "detekt.yml", ".editorconfig"):
            with self.subTest(path=path):
                self.git("rm", "-q", path)
                result = self.run_hook()
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("Linter config modification blocked", result.stdout + result.stderr)
                self.git("restore", "--staged", "--worktree", "--", path)

    def test_hook_allows_removal(self) -> None:
        self.stage("codecov.yml", REMOVAL)
        result = self.run_hook()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Passed", result.stdout)

    def test_hook_rejects_symlink(self) -> None:
        (self.root / "codecov.yml").unlink()
        (self.root / "other.yml").write_text(REMOVAL)
        (self.root / "codecov.yml").symlink_to("other.yml")
        self.git("add", "codecov.yml")
        result = self.run_hook()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Linter config modification blocked", result.stdout + result.stderr)

    def test_rejects_new_config_without_a_baseline(self) -> None:
        self.git("rm", "-q", "codecov.yml")
        self.git("commit", "-qm", "Remove fixture config")
        self.stage("codecov.yml", REMOVAL)
        self.assert_blocked()

    def test_rejects_mode_change(self) -> None:
        self.stage("codecov.yml", REMOVAL)
        self.git("update-index", "--chmod=+x", "codecov.yml")
        self.assert_blocked()

    def test_rejects_symlink_replacement(self) -> None:
        (self.root / "codecov.yml").unlink()
        (self.root / "other.yml").write_text(REMOVAL)
        (self.root / "codecov.yml").symlink_to("other.yml")
        self.git("add", "codecov.yml")
        self.assert_blocked()

    def test_checks_the_repository_root_from_a_subdirectory(self) -> None:
        self.stage("codecov.yml", REMOVAL)
        nested = self.root / "nested"
        nested.mkdir()
        result = self.guard(nested)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_fails_outside_a_git_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = self.guard(Path(directory))
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
