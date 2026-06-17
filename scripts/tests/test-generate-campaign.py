#!/usr/bin/env python3
"""CLI-boundary tests for generate_campaign.py."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from collections.abc import Mapping, Sequence
from datetime import date
from pathlib import Path

SOURCE_SCRIPT = Path(__file__).resolve().parent.parent / "generate_campaign.py"
REPO_ROOT = SOURCE_SCRIPT.parent.parent

DEFAULT_GUARDRAILS = """
blocked_phrases:
  - guaranteed productivity
conditional_claims: []
"""
DEFAULT_FEATURES_YAML = """
categories:
  accent:
    features:
      - id: accent-overrides
        title: Accent overrides
        tier: free
        introduced: 2.7.4
"""
DEFAULT_SOCIAL_PROOF = """
- source: https://example.com/review
  label: Maintainer quote
  quote: Looks sharp
  status: verified
"""


class GenerateCampaignTest(unittest.TestCase):
    def test_console_entrypoint_matches_documented_command(self) -> None:
        result = subprocess.run(
            ["uv", "run", "--project", "scripts", "generate-campaign", "--help"],
            check=False,
            cwd=REPO_ROOT,
            text=True,
            capture_output=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Generate private Ayu Islands marketing campaign drafts", result.stdout)

    def test_generates_drafts_with_summary_alias_placeholders(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {
                    "launch.md": """
                    # {{ product.name }}

                    Pricing: {{ pricing.summary }}
                    Status:
                    {{ launch_status.summary }}
                    Proof:
                    {{ social_proof.summary }}
                    Version: {{ facts.latest_version }}
                    Changes:
                    {{ facts.latest_changelog_summary }}
                    """
                },
            )

            result = run_generator(repository_root)

            self.assertEqual(0, result.returncode, result.stderr)
            draft = read_generated_file(repository_root, "launch.md")
            self.assertIn("Ayu Islands", draft)
            self.assertIn("12 USD individual / 30 USD commercial", draft)
            self.assertIn("- marketplace: released", draft)
            self.assertIn("[Free] Accent overrides", draft)
            self.assertNotIn("unresolved placeholder", draft)

    def test_warning_only_guardrails_are_written_without_failing_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Ayu Islands has 10,000 downloads.\n"},
            )

            result = run_generator(repository_root)

            self.assertEqual(0, result.returncode, result.stderr)
            fact_check = read_generated_file(repository_root, "FACT_CHECK.md")
            self.assertIn("metric claim `10,000 downloads` needs a source", fact_check)
            self.assertIn("- Hard blocks: 0", read_generated_file(repository_root, "README.md"))

    def test_unresolved_template_placeholders_fail_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "{{ product.name }} {{ missing.value }}\n"},
            )

            result = run_generator(repository_root)

            self.assertEqual(2, result.returncode)
            self.assertIn("Hard-blocked claims were found", result.stderr)
            fact_check = read_generated_file(repository_root, "FACT_CHECK.md")
            self.assertIn("unresolved placeholder missing.value", fact_check)

    def test_guardrails_fail_blocked_phrase_and_report_malformed_requires(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {
                    "launch.md": """
                    guaranteed productivity
                    lifetime access
                    """
                },
                guardrails="""
                blocked_phrases:
                  - guaranteed productivity
                conditional_claims:
                  - phrase: lifetime access
                    message: "TODO_VERIFY: lifetime access requires manual verification."
                    requires:
                      path: pricing.missing
                """,
            )

            result = run_generator(repository_root)

            self.assertEqual(2, result.returncode)
            fact_check = read_generated_file(repository_root, "FACT_CHECK.md")
            self.assertIn("blocked phrase `guaranteed productivity`", fact_check)
            self.assertIn("lifetime access requires manual verification", fact_check)

    def test_existing_campaign_directory_is_not_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Ready: {{ product.name }}\n"},
            )
            output_directory = generated_directory(repository_root)
            output_directory.mkdir(parents=True)
            sentinel = output_directory / "README.md"
            sentinel.write_text("keep this review note\n", encoding="utf-8")

            result = run_generator(repository_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Campaign output already exists", result.stderr)
            self.assertEqual("keep this review note\n", sentinel.read_text(encoding="utf-8"))

    def test_template_paths_cannot_escape_templates_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["../README.md"],
                {},
            )

            result = run_generator(repository_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("must be a file name in .marketing/templates", result.stderr)

    def test_template_names_cannot_conflict_with_support_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["readme.md"],
                {"readme.md": "This draft would be overwritten on case-insensitive filesystems.\n"},
            )

            result = run_generator(repository_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("conflicts with a generated support file", result.stderr)

    def test_feature_metadata_must_be_complete(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "{{ facts.free_feature_summary }}\n"},
                features_yaml="""
                categories:
                  accent:
                    features:
                      - id: accent-overrides
                        title: Accent overrides
                        introduced: 2.7.4
                """,
            )

            result = run_generator(repository_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Expected `accent.features[0].tier`", result.stderr)

    def test_social_proof_entries_must_not_be_silently_dropped(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "{{ social_proof.summary }}\n"},
                social_proof="""
                - label: Missing source
                  quote: Looks sharp
                  status: verified
                """,
            )

            result = run_generator(repository_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Expected `social_proof[0].source`", result.stderr)


def make_repository(
    repository_root: Path,
    template_names: Sequence[str],
    templates: Mapping[str, str],
    guardrails: str = DEFAULT_GUARDRAILS,
    features_yaml: str = DEFAULT_FEATURES_YAML,
    social_proof: str = DEFAULT_SOCIAL_PROOF,
) -> None:
    scripts_dir = repository_root / "scripts"
    scripts_dir.mkdir(parents=True)
    shutil.copy2(SOURCE_SCRIPT, scripts_dir / "generate_campaign.py")

    docs_dir = repository_root / "docs"
    docs_dir.mkdir()
    (docs_dir / "features.yml").write_text(
        textwrap.dedent(features_yaml).lstrip(),
        encoding="utf-8",
    )
    (repository_root / "CHANGELOG.md").write_text(
        textwrap.dedent(
            """
            # Changelog

            ## [2.7.4]
            - [Free] Accent overrides

            ## [2.7.3]
            - Previous release
            """
        ).lstrip(),
        encoding="utf-8",
    )

    marketing_root = repository_root / ".marketing"
    templates_dir = marketing_root / "templates"
    templates_dir.mkdir(parents=True)
    for template_name, template_content in templates.items():
        (templates_dir / template_name).write_text(
            textwrap.dedent(template_content).lstrip(),
            encoding="utf-8",
        )

    template_list = "\n".join(f"      - {json.dumps(name)}" for name in template_names)
    config = f"""
product:
  name: Ayu Islands
  category: JetBrains theme
pricing:
  public_claim_status: ready
  individual: 12 USD
  commercial: 30 USD
  model: annual license
launch_status:
  marketplace: released
manual_verification:
  - Confirm generated copy against Marketplace listing.
social_proof:
{textwrap.indent(textwrap.dedent(social_proof).strip(), "  ")}
campaign_modes:
  launch:
    description: Launch copy
    templates:
{template_list}
guardrails:
{textwrap.indent(textwrap.dedent(guardrails).strip(), "  ")}
"""
    (marketing_root / "config.yaml").write_text(
        textwrap.dedent(config).lstrip(),
        encoding="utf-8",
    )


def run_generator(repository_root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(repository_root / "scripts" / "generate_campaign.py"),
            "--mode",
            "launch",
            "--name",
            "Launch Plan",
        ],
        check=False,
        cwd=repository_root,
        text=True,
        capture_output=True,
    )


def generated_directory(repository_root: Path) -> Path:
    return repository_root / ".marketing" / "generated" / f"{date.today().isoformat()}-launch-plan"


def read_generated_file(repository_root: Path, file_name: str) -> str:
    return (generated_directory(repository_root) / file_name).read_text(encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
