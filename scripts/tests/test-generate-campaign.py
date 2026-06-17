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
risky_phrases: []
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

            draft = self.assert_success_file_contains(
                repository_root, "launch.md", "Ayu Islands"
            )
            self.assertIn("12 USD individual / 30 USD commercial", draft)
            self.assertIn("- marketplace: released", draft)
            self.assertIn("[Free] Accent overrides", draft)
            self.assertNotIn("unresolved placeholder", draft)

    def test_generates_all_selected_templates_and_support_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md", "follow-up.md"],
                {
                    "launch.md": "Launch: {{ product.name }}\n",
                    "follow-up.md": "Follow-up: {{ facts.free_feature_summary_inline }}\n",
                },
            )

            self.assert_success_file_contains(repository_root, "launch.md", "Launch: Ayu Islands")

            generated_files = {
                file_path.name
                for file_path in generated_directory(repository_root).iterdir()
            }
            self.assertEqual(
                {
                    "CHECKLIST.md",
                    "FACT_CHECK.md",
                    "POSTING_PLAN.md",
                    "README.md",
                    "follow-up.md",
                    "launch.md",
                },
                generated_files,
            )
            self.assertIn(
                "Follow-up: Accent overrides",
                read_generated_file(repository_root, "follow-up.md"),
            )
            readme = read_generated_file(repository_root, "README.md")
            posting_plan = read_generated_file(repository_root, "POSTING_PLAN.md")
            self.assertIn("- `launch.md`", readme)
            self.assertIn("- `follow-up.md`", readme)
            self.assertIn("- `launch.md`", posting_plan)
            self.assertIn("- `follow-up.md`", posting_plan)
            self.assertIn("Draft files:", readme)

    def test_guardrails_evaluate_every_selected_template(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md", "follow-up.md"],
                {
                    "launch.md": "Launch: {{ product.name }}\n",
                    "follow-up.md": "guaranteed productivity\n",
                },
            )

            self.assert_hard_blocked_fact_check_contains(
                repository_root,
                "follow-up.md: blocked phrase `guaranteed productivity`",
            )

    def test_warning_only_guardrails_are_written_without_failing_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Ayu Islands has 10,000+ downloads.\n"},
            )

            self.assert_success_file_contains(
                repository_root,
                "FACT_CHECK.md",
                "metric claim `10,000+ downloads` needs a source",
            )
            self.assertIn("- Hard blocks: 0", read_generated_file(repository_root, "README.md"))

    def test_risky_phrases_are_written_as_warnings(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            fact_check = self.render_guardrail_fact_check(
                temporary_directory,
                "No telemetry and no Sentry by design.\n",
                """
                blocked_phrases: []
                risky_phrases:
                  - telemetry
                  - Sentry
                conditional_claims: []
                """,
                "risky phrase `telemetry` needs review",
            )
            self.assertIn("risky phrase `Sentry` needs review", fact_check)

    def test_bare_todo_verify_markers_are_written_as_warnings(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.render_pricing_fact_check(
                temporary_directory,
                """
                public_claim_status: ready
                commercial: 30 USD
                model: annual license
                """,
                "Pricing: TODO_VERIFY individual",
            )

    def test_unready_pricing_status_is_written_as_warning(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.render_pricing_fact_check(
                temporary_directory,
                """
                individual: 12 USD
                commercial: 30 USD
                model: annual license
                """,
                "pricing copy is not ready for public use",
            )

    def test_blank_ready_pricing_fields_are_written_as_warnings(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.render_pricing_fact_check(
                temporary_directory,
                """
                public_claim_status: ready
                individual: ""
                commercial: 30 USD
                model: annual license
                """,
                "Pricing: TODO_VERIFY individual",
            )

    def test_malformed_template_placeholders_fail_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Bad placeholder: {{ product name }}\n"},
            )

            self.assert_hard_blocked_fact_check_contains(
                repository_root,
                "raw template placeholder `{{ product name }}` was not rendered",
            )

    def test_unclosed_template_placeholders_fail_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Bad placeholder: {{ product.name\n"},
            )

            self.assert_hard_blocked_fact_check_contains(
                repository_root,
                "raw template placeholder `{{ product.name` was not rendered",
            )

    def test_unresolved_template_placeholders_fail_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "{{ product.name }} {{ missing.value }}\n"},
            )

            self.assert_hard_blocked_fact_check_contains(
                repository_root,
                "unresolved placeholder missing.value",
                expected_stderr="Hard-blocked claims were found",
            )

    def test_blank_public_placeholder_values_fail_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Product: {{ product.name }}\n"},
                product="""
                name: ""
                category: JetBrains theme
                """,
            )

            self.assert_generation_fails_with(
                repository_root,
                "Expected `product.name` to be a non-empty scalar",
            )

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
                risky_phrases: []
                conditional_claims:
                  - phrase: lifetime access
                    message: "TODO_VERIFY: lifetime access requires manual verification."
                    requires:
                      path: pricing.missing
                      value: ready
                """,
            )

            fact_check = self.assert_hard_blocked_fact_check_contains(
                repository_root,
                "blocked phrase `guaranteed productivity`",
            )
            self.assertIn("lifetime access requires manual verification", fact_check)

    def test_blank_guardrail_phrases_fail_before_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                blocked_phrases:
                  - ""
                risky_phrases: []
                conditional_claims: []
                """,
                "Expected `blocked_phrases[0]`",
            )

    def test_duplicate_guardrail_keys_fail_before_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                blocked_phrases:
                  - guaranteed productivity
                blocked_phrases: []
                risky_phrases: []
                conditional_claims: []
                """,
                "Duplicate YAML key `blocked_phrases`",
                draft_content="guaranteed productivity\n",
            )

    def test_guardrails_must_define_required_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                risky_phrases: []
                conditional_claims: []
                """,
                "Expected `blocked_phrases` to be a YAML list",
                draft_content="guaranteed productivity\n",
            )

    def render_pricing_fact_check(
        self,
        temporary_directory: str,
        pricing: str,
        expected_text: str,
    ) -> None:
        repository_root = Path(temporary_directory)
        make_repository(
            repository_root,
            ["launch.md"],
            {"launch.md": "Pricing: {{ pricing.summary }}\n"},
            pricing=pricing,
        )
        self.assert_success_file_contains(repository_root, "FACT_CHECK.md", expected_text)

    def test_conditional_claim_requires_feature_id_warns_when_feature_is_absent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.render_guardrail_fact_check(
                temporary_directory,
                "External accent sources are ready.\n",
                """
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: external accent sources
                    message: "TODO_VERIFY: external accent sources require released feature metadata."
                    requires_feature_id: external-accent-sources
                """,
                "external accent sources require released feature metadata",
            )

    def test_conditional_claim_requires_feature_id_allows_released_feature(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "External accent sources are ready.\n"},
                features_yaml="""
                categories:
                  accent:
                    features:
                      - id: external-accent-sources
                        title: External accent sources
                        tier: paid
                        introduced: 2.8.0
                """,
                guardrails="""
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: external accent sources
                    message: "TODO_VERIFY: external accent sources require released feature metadata."
                    requires_feature_id: external-accent-sources
                """,
            )
            self.assert_success_file_contains(
                repository_root, "FACT_CHECK.md", "No warnings."
            )

    def test_conditional_claim_requires_path_value_warns_when_unmatched(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.render_guardrail_fact_check(
                temporary_directory,
                "Public trial is open.\n",
                """
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: public trial
                    message: "TODO_VERIFY: public trial requires confirmed launch status."
                    requires:
                      path: launch_status.trial_status
                      value: confirmed
                """,
                "public trial requires confirmed launch status",
            )

    def assert_success_file_contains(
        self,
        repository_root: Path,
        file_name: str,
        expected_text: str,
    ) -> str:
        result = run_generator(repository_root)
        self.assertEqual(0, result.returncode, result.stderr)
        content = read_generated_file(repository_root, file_name)
        self.assertIn(expected_text, content)
        return content

    def assert_generation_fails_with(self, repository_root: Path, expected_stderr: str) -> None:
        result = run_generator(repository_root)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(expected_stderr, result.stderr)

    def assert_hard_blocked_fact_check_contains(
        self,
        repository_root: Path,
        expected_text: str,
        expected_stderr: str | None = None,
    ) -> str:
        result = run_generator(repository_root)
        self.assertEqual(2, result.returncode)
        if expected_stderr is not None:
            self.assertIn(expected_stderr, result.stderr)
        fact_check = read_generated_file(repository_root, "FACT_CHECK.md")
        self.assertIn(expected_text, fact_check)
        return fact_check

    def test_conditional_claim_requires_path_value_allows_matching_config(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Public trial is open.\n"},
                launch_status="""
                marketplace: released
                trial_status: confirmed
                """,
                guardrails="""
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: public trial
                    message: "TODO_VERIFY: public trial requires confirmed launch status."
                    requires:
                      path: launch_status.trial_status
                      value: confirmed
                """,
            )

            self.assert_success_file_contains(
                repository_root, "FACT_CHECK.md", "No warnings."
            )

    def render_guardrail_fact_check(
        self,
        temporary_directory: str,
        draft_content: str,
        guardrails: str,
        expected_text: str,
    ) -> str:
        repository_root = Path(temporary_directory)
        make_repository(
            repository_root,
            ["launch.md"],
            {"launch.md": draft_content},
            guardrails=guardrails,
        )
        return self.assert_success_file_contains(repository_root, "FACT_CHECK.md", expected_text)

    def assert_guardrail_config_fails(
        self,
        temporary_directory: str,
        guardrails: str,
        expected_stderr: str,
        draft_content: str = "Ayu Islands launch copy.\n",
    ) -> None:
        repository_root = Path(temporary_directory)
        make_repository(
            repository_root,
            ["launch.md"],
            {"launch.md": draft_content},
            guardrails=guardrails,
        )
        self.assert_generation_fails_with(repository_root, expected_stderr)

    def test_conditional_claims_must_have_a_phrase(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - message: "TODO_VERIFY: public trial requires confirmed launch status."
                    requires:
                      path: launch_status.trial_status
                      value: confirmed
                """,
                "Expected `conditional_claims[0].phrase`",
                draft_content="Public trial is open.\n",
            )

    def test_conditional_claims_must_not_have_blank_messages(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: public trial
                    message: ""
                    requires:
                      path: launch_status.trial_status
                      value: confirmed
                """,
                "Expected `conditional_claims[0].message`",
                draft_content="Public trial is open.\n",
            )

    def test_conditional_claim_requires_value_must_not_be_blank(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: public trial
                    requires:
                      path: launch_status.trial_status
                      value: ""
                """,
                "Expected `conditional_claims[0].requires.value`",
                draft_content="Public trial is open.\n",
            )

    def test_conditional_claim_requires_must_be_mapping_even_when_inactive(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: unavailable claim
                    requires: launch_status.trial_status
                """,
                "Expected `conditional_claims[0].requires` to be a YAML mapping",
            )

    def test_conditional_claim_feature_id_must_be_scalar_even_when_inactive(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: unavailable claim
                    requires_feature_id:
                      - accent-overrides
                """,
                "Expected scalar value at `conditional_claims[0].requires_feature_id`",
            )

    def test_conditional_claim_cannot_mix_requirement_types(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            self.assert_guardrail_config_fails(
                temporary_directory,
                """
                blocked_phrases: []
                risky_phrases: []
                conditional_claims:
                  - phrase: external accent sources
                    requires_feature_id: accent-overrides
                    requires:
                      path: launch_status.marketplace
                      value: unreleased
                """,
                "Expected `conditional_claims[0]` to define only one requirement type",
                draft_content="External accent sources are ready.\n",
            )

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

            self.assert_generation_fails_with(repository_root, "Campaign output already exists")
            self.assertEqual("keep this review note\n", sentinel.read_text(encoding="utf-8"))

    def test_missing_local_marketing_config_reports_required_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            scripts_dir = repository_root / "scripts"
            scripts_dir.mkdir(parents=True)
            shutil.copy2(SOURCE_SCRIPT, scripts_dir / "generate_campaign.py")

            result = run_generator(repository_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Required YAML file is missing", result.stderr)
            self.assertIn(".marketing/config.yaml", result.stderr)

    def test_invalid_yaml_encoding_reports_config_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Ready: {{ product.name }}\n"},
            )
            (repository_root / ".marketing" / "config.yaml").write_bytes(b"\xff")

            result = run_generator(repository_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Failed to read YAML file", result.stderr)
            self.assertIn(".marketing/config.yaml", result.stderr)
            self.assertNotIn("Traceback", result.stderr)

    def test_unreadable_changelog_becomes_fact_check_warning(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "{{ facts.latest_changelog_summary }}\n"},
            )
            (repository_root / "CHANGELOG.md").write_bytes(b"\xff")

            result = run_generator(repository_root)

            self.assertEqual(0, result.returncode, result.stderr)
            fact_check = read_generated_file(repository_root, "FACT_CHECK.md")
            self.assertIn("failed to read CHANGELOG.md", fact_check)
            self.assertNotIn("Traceback", result.stderr)

    def test_unreadable_selected_template_reports_template_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Ready: {{ product.name }}\n"},
            )
            template_path = repository_root / ".marketing" / "templates" / "launch.md"
            template_path.unlink()
            template_path.mkdir()

            self.assert_generation_fails_with(
                repository_root,
                "Failed to read selected template",
            )

    def test_output_directory_parent_file_reports_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "Ready: {{ product.name }}\n"},
            )
            (repository_root / ".marketing" / "generated").write_text(
                "not a directory\n",
                encoding="utf-8",
            )

            self.assert_generation_fails_with(
                repository_root,
                "Failed to create campaign output directory",
            )

    def test_template_paths_cannot_escape_templates_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["../README.md"],
                {},
            )

            self.assert_generation_fails_with(
                repository_root,
                "must be a file name in .marketing/templates",
            )

    def test_template_names_cannot_conflict_with_support_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["readme.md"],
                {"readme.md": "This draft would be overwritten on case-insensitive filesystems.\n"},
            )

            self.assert_generation_fails_with(
                repository_root,
                "conflicts with a generated support file",
            )

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

            self.assert_generation_fails_with(
                repository_root,
                "Expected `accent.features[0].tier`",
            )

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

            self.assert_generation_fails_with(
                repository_root,
                "Expected `social_proof[0].source`",
            )

    def test_social_proof_quote_must_not_be_empty(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            make_repository(
                repository_root,
                ["launch.md"],
                {"launch.md": "{{ social_proof.summary }}\n"},
                social_proof="""
                - source: https://example.com/review
                  label: Missing quote
                  status: verified
                """,
            )

            self.assert_generation_fails_with(
                repository_root,
                "Expected `social_proof[0].quote`",
            )


def make_repository(
    repository_root: Path,
    template_names: Sequence[str],
    templates: Mapping[str, str],
    guardrails: str = DEFAULT_GUARDRAILS,
    features_yaml: str = DEFAULT_FEATURES_YAML,
    social_proof: str = DEFAULT_SOCIAL_PROOF,
    product: str = """
    name: Ayu Islands
    category: JetBrains theme
    """,
    pricing: str = """
    public_claim_status: ready
    individual: 12 USD
    commercial: 30 USD
    model: annual license
    """,
    launch_status: str = """
    marketplace: released
    """,
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
{textwrap.indent(textwrap.dedent(product).strip(), "  ")}
pricing:
{textwrap.indent(textwrap.dedent(pricing).strip(), "  ")}
launch_status:
{textwrap.indent(textwrap.dedent(launch_status).strip(), "  ")}
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
