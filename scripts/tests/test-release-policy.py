#!/usr/bin/env python3
"""Tests for verify-docs semantic release policy."""

from __future__ import annotations

import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_docs import changelog  # noqa: E402
from verify_docs.report import Report  # noqa: E402


class VerifyReleasePolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_changelog = changelog.CHANGELOG
        self.original_gradle_properties = changelog.GRADLE_PROPERTIES
        self.original_plugin_xml = changelog.PLUGIN_XML

    def tearDown(self) -> None:
        changelog.CHANGELOG = self.original_changelog
        changelog.GRADLE_PROPERTIES = self.original_gradle_properties
        changelog.PLUGIN_XML = self.original_plugin_xml

    def test_fix_only_patch_release_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = make_repository(
                Path(temporary_directory),
                plugin_version="2.7.6",
                plugin_xml_version="2.7.6",
                changelog_text="""
                    ## [2.7.6] - 2026-06-24

                    - [Fix] Chrome tinting no longer colors shared dividers.

                    ## [2.7.5] - 2026-06-18

                    - [Paid] Accent Source status-bar widget.
                """,
            )

            report = run_release_policy(root, features_data())

        self.assertFalse(report.has_errors, report.findings)

    def test_feature_introduced_requires_minor_bump(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = make_repository(
                Path(temporary_directory),
                plugin_version="2.7.6",
                plugin_xml_version="2.7.6",
                changelog_text="""
                    ## [2.7.6] - 2026-06-24

                    - [Paid] Accent Source status-bar widget.

                    ## [2.7.5] - 2026-06-18

                    - [Fix] Chrome tinting target preservation.
                """,
            )

            report = run_release_policy(
                root,
                features_data(feature_id="accent-source-status-widget", introduced="2.7.6"),
            )

        self.assertTrue(report.has_errors)
        self.assertIn("expects a minor bump", messages(report))

    def test_feature_introduced_allows_minor_bump(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = make_repository(
                Path(temporary_directory),
                plugin_version="2.8.0",
                plugin_xml_version="2.8.0",
                changelog_text="""
                    ## [2.8.0] - 2026-06-24

                    - [Free] Syntax color controls.

                    ## [2.7.6] - 2026-06-18

                    - [Fix] Chrome tinting target preservation.
                """,
            )

            report = run_release_policy(
                root,
                features_data(feature_id="syntax-color-controls", introduced="2.8.0"),
            )

        self.assertFalse(report.has_errors, report.findings)

    def test_gradle_version_must_match_latest_changelog(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = make_repository(
                Path(temporary_directory),
                plugin_version="2.7.5",
                plugin_xml_version="2.7.6",
                changelog_text="""
                    ## [2.7.6] - 2026-06-24

                    - [Fix] Chrome tinting target preservation.

                    ## [2.7.5] - 2026-06-18

                    - [Fix] Groovy and Jenkinsfile colors.
                """,
            )

            report = run_release_policy(root, features_data())

        self.assertTrue(report.has_errors)
        self.assertIn("pluginVersion is 2.7.5", messages(report))

    def test_plugin_xml_change_notes_must_match_latest_changelog(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = make_repository(
                Path(temporary_directory),
                plugin_version="2.7.6",
                plugin_xml_version="2.7.5",
                changelog_text="""
                    ## [2.7.6] - 2026-06-24

                    - [Fix] Chrome tinting target preservation.

                    ## [2.7.5] - 2026-06-18

                    - [Fix] Groovy and Jenkinsfile colors.
                """,
            )

            report = run_release_policy(root, features_data())

        self.assertTrue(report.has_errors)
        self.assertIn("plugin.xml change-notes start at 2.7.5", messages(report))

    def test_plugin_xml_change_notes_must_have_parseable_heading(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = make_repository(
                Path(temporary_directory),
                plugin_version="2.7.6",
                plugin_xml_version="2.7.6",
                changelog_text="""
                    ## [2.7.6] - 2026-06-24

                    - [Fix] Chrome tinting target preservation.

                    ## [2.7.5] - 2026-06-18

                    - [Fix] Groovy and Jenkinsfile colors.
                """,
            )
            (root / "plugin.xml").write_text(
                """
                <idea-plugin>
                  <change-notes><![CDATA[
                    <p>No release heading</p>
                  ]]></change-notes>
                </idea-plugin>
                """,
                encoding="utf-8",
            )

            report = run_release_policy(root, features_data())

        self.assertTrue(report.has_errors)
        self.assertIn("must start with an `<h3>X.Y.Z</h3>`", messages(report))

    def test_malformed_version_reports_clear_numeric_error(self) -> None:
        report = Report()

        bump = changelog.classify_version_bump("2.7.5", "2.7.x", report)

        self.assertIsNone(bump)
        self.assertTrue(report.has_errors)
        self.assertIn(
            "expected numeric X.Y.Z version, got '2.7.x'",
            messages(report),
        )
        self.assertNotIn("invalid literal", messages(report))


def make_repository(
    root: Path,
    *,
    plugin_version: str,
    plugin_xml_version: str,
    changelog_text: str,
) -> Path:
    plugin_xml = root / "plugin.xml"
    plugin_xml.write_text(
        f"""
        <idea-plugin>
          <change-notes><![CDATA[
            <h3>{plugin_xml_version}</h3>
          ]]></change-notes>
        </idea-plugin>
        """,
        encoding="utf-8",
    )
    (root / "gradle.properties").write_text(
        f"pluginVersion={plugin_version}\n",
        encoding="utf-8",
    )
    (root / "CHANGELOG.md").write_text(
        textwrap.dedent(changelog_text).strip() + "\n",
        encoding="utf-8",
    )
    return root


def run_release_policy(root: Path, data: dict[str, object]) -> Report:
    changelog.CHANGELOG = root / "CHANGELOG.md"
    changelog.GRADLE_PROPERTIES = root / "gradle.properties"
    changelog.PLUGIN_XML = root / "plugin.xml"
    report = Report()
    changelog.check_semantic_release_policy(data, report)
    return report


def features_data(
    *,
    feature_id: str = "previous-feature",
    introduced: str = "2.7.5",
) -> dict[str, object]:
    return {
        "categories": {
            "accent": {
                "features": [
                    {
                        "id": feature_id,
                        "introduced": introduced,
                    }
                ]
            }
        }
    }


def messages(report: Report) -> str:
    return "\n".join(finding.message for finding in report.findings)


if __name__ == "__main__":
    unittest.main()
