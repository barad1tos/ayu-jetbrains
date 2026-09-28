#!/usr/bin/env python3
"""Behavior tests for the signature-gated Gradle retry wrapper."""

from __future__ import annotations

import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "gradle-retry.sh"
REPOSITORY_ROOT = SCRIPT.parent.parent
RELEASE_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "release.yml"
CODEQL_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "codeql.yml"


class GradleRetryTest(unittest.TestCase):
    def test_release_verifier_uses_retry_wrapper(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            "run: scripts/gradle-retry.sh verifyPlugin -DverifyGroup=${{ matrix.group }}",
            workflow,
        )

    def test_codeql_uses_retry(self) -> None:
        workflow = CODEQL_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            "OUTPUT=$(scripts/gradle-retry.sh compileKotlin compileTestKotlin --no-daemon 2>&1)",
            workflow,
        )

    def test_retry_rebuilds_generated_caches_only(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            make_gradle_fixture(root)
            stale_paths = [
                root / "build" / "stale",
                root / ".gradle" / "stale",
                root / ".intellijPlatform" / "ides" / "layoutIndex" / "stale",
                root / ".intellijPlatform" / "localPlatformArtifacts" / "stale",
            ]
            for stale_path in stale_paths:
                stale_path.parent.mkdir(parents=True, exist_ok=True)
                stale_path.touch()
            sandbox_state = root / ".intellijPlatform" / "sandbox" / "preserved"
            sandbox_state.parent.mkdir(parents=True)
            sandbox_state.touch()

            result = subprocess.run(
                [str(SCRIPT), "compileKotlin"],
                cwd=root,
                check=False,
                text=True,
                capture_output=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("2", (root / ".attempt-count").read_text(encoding="utf-8"))
            for stale_path in stale_paths:
                self.assertFalse(stale_path.exists(), stale_path)
            self.assertTrue(sandbox_state.exists())

    def test_unrelated_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            gradle = root / "gradlew"
            gradle.write_text(
                "#!/usr/bin/env bash\nprintf 'called' >> .attempts\necho 'unrelated error'\nexit 23\n",
                encoding="utf-8",
            )
            gradle.chmod(0o755)
            cached_index = root / ".intellijPlatform" / "ides" / "layoutIndex" / "preserved"
            cached_index.parent.mkdir(parents=True)
            cached_index.touch()

            result = subprocess.run(
                [str(SCRIPT), "compileKotlin"],
                cwd=root,
                check=False,
                text=True,
                capture_output=True,
            )

            self.assertEqual(23, result.returncode)
            self.assertEqual("called", (root / ".attempts").read_text(encoding="utf-8"))
            self.assertTrue(cached_index.exists())


def make_gradle_fixture(root: Path) -> None:
    gradle = root / "gradlew"
    gradle.write_text(
        textwrap.dedent(
            """\
            #!/usr/bin/env bash
            set -eu

            if [ "${1:-}" = "--stop" ]; then
              exit 0
            fi

            attempt_file=.attempt-count
            attempt=0
            if [ -f "$attempt_file" ]; then
              attempt=$(<"$attempt_file")
            fi
            attempt=$((attempt + 1))
            printf '%s' "$attempt" > "$attempt_file"

            if [ "$attempt" -eq 1 ]; then
              echo "Cannot access 'com.intellij.psi.PsiAnnotationMemberValue'"
              exit 1
            fi
            """,
        ),
        encoding="utf-8",
    )
    gradle.chmod(0o755)


if __name__ == "__main__":
    unittest.main()
