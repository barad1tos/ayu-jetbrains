"""Repo-rooted path constants shared across the docs-lint modules."""

from __future__ import annotations

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
FEATURES_YAML = REPO_ROOT / "docs" / "features.yml"
README = REPO_ROOT / "README.md"
PLUGIN_XML = REPO_ROOT / "src" / "main" / "resources" / "META-INF" / "plugin.xml"
CHANGELOG = REPO_ROOT / "CHANGELOG.md"
GRADLE_PROPERTIES = REPO_ROOT / "gradle.properties"
