"""Invariant 2: changelog and semantic release policy."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from .features import iter_features
from .paths import CHANGELOG, GRADLE_PROPERTIES, PLUGIN_XML
from .report import Report

_VERSION_HEADER = re.compile(r"^##\s*\[(\d+\.\d+\.\d+)]", re.MULTILINE)
_TIER_TAGS = frozenset({"Paid", "Free"})


@dataclass(frozen=True)
class ChangelogSection:
    version: str
    tier_bullets: list[tuple[str, str]]


def extract_latest_changelog_section() -> tuple[str, list[tuple[str, str]]]:
    """Return (version, [(tier, bullet_text), ...]) for the latest CHANGELOG section."""
    latest, _previous = extract_latest_and_previous_changelog_sections()
    return latest.version, latest.tier_bullets


def extract_latest_and_previous_changelog_sections() -> tuple[ChangelogSection, ChangelogSection]:
    """Return the two newest CHANGELOG sections."""
    text = CHANGELOG.read_text(encoding="utf-8")
    matches = list(_VERSION_HEADER.finditer(text))
    if len(matches) < 2:
        raise SystemExit("CHANGELOG.md needs at least two version sections")
    return (
        extract_changelog_section(text, matches, 0),
        extract_changelog_section(text, matches, 1),
    )


def extract_changelog_section(
    text: str,
    matches: list[re.Match[str]],
    index: int,
) -> ChangelogSection:
    """Extract one CHANGELOG section from a version-header match list."""
    match = matches[index]
    start = match.end()
    end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
    section = text[start:end]
    bullets = extract_tier_bullets(section)
    return ChangelogSection(version=match[1], tier_bullets=bullets)


def extract_tier_bullets(section: str) -> list[tuple[str, str]]:
    """Return [(tier, bullet_text), ...] for [Paid]/[Free] bullets."""
    bullets: list[tuple[str, str]] = []
    for raw_line in section.splitlines():
        line = raw_line.strip()
        if not line.startswith("- ["):
            continue
        closing_bracket = line.find("]")
        if closing_bracket < 0:
            continue
        tier = line[3:closing_bracket]
        if tier in _TIER_TAGS:
            bullets.append((tier, line[closing_bracket + 1 :].strip()))
    return bullets


def check_changelog_cross_ref(data: dict[str, Any], report: Report) -> None:
    """Invariant 2: tier-tagged bullets in latest changelog map to features with matching `introduced`."""
    version, bullets = extract_latest_changelog_section()
    if not bullets:
        # No tier-tagged bullets — could be a fix-only patch release. OK.
        return
    matching_features = [
        feature
        for feature in iter_features(data)
        if feature.get("introduced") == version
    ]
    if not matching_features:
        report.error(
            "_changelog_xref_",
            f"CHANGELOG [{version}] has {len(bullets)} tagged bullet(s) but "
            f"no features.yml entry with introduced: {version}. Add one feature "
            f"per new bullet or mark the bullets as bug-fixes (drop [Paid]/[Free]).",
        )


def check_semantic_release_policy(data: dict[str, Any], report: Report) -> None:
    """Invariant 7: features.yml drives semantic patch/minor release bumps."""
    latest, previous = extract_latest_and_previous_changelog_sections()
    plugin_version = read_plugin_version()
    if plugin_version is None:
        report.error(
            "_release_semantics_",
            "gradle.properties is missing a `pluginVersion=X.Y.Z` line — "
            "semantic release policy cannot compare the release surfaces.",
        )
    elif plugin_version != latest.version:
        report.error(
            "_release_semantics_",
            f"gradle.properties pluginVersion is {plugin_version}, but the "
            f"latest CHANGELOG entry is {latest.version}. Keep the release "
            "version and update index aligned.",
        )

    plugin_xml_version = read_plugin_xml_change_notes_version()
    if plugin_xml_version is None:
        report.error(
            "_release_semantics_",
            "plugin.xml change-notes must start with an `<h3>X.Y.Z</h3>` "
            "heading so Marketplace release notes point at the latest "
            "CHANGELOG entry.",
        )
    elif plugin_xml_version != latest.version:
        report.error(
            "_release_semantics_",
            f"plugin.xml change-notes start at {plugin_xml_version}, but the "
            f"latest CHANGELOG entry is {latest.version}. Keep Marketplace "
            "release notes aligned with the update index.",
        )

    actual_bump = classify_version_bump(previous.version, latest.version, report)
    if actual_bump is None:
        return

    latest_features = [
        feature
        for feature in iter_features(data)
        if feature.get("introduced") == latest.version
    ]
    expected_bump = "minor" if latest_features else "patch"
    if actual_bump == expected_bump:
        return

    if latest_features:
        feature_ids = ", ".join(
            sorted(str(feature.get("id", "<missing-id>")) for feature in latest_features)
        )
        report.error(
            "_release_semantics_",
            f"features.yml has feature(s) introduced in {latest.version} "
            f"({feature_ids}), so semantic policy expects a minor bump from "
            f"{previous.version}; actual bump is {actual_bump}.",
        )
        return

    report.error(
        "_release_semantics_",
        f"features.yml has no feature introduced in {latest.version}; "
        f"fix-only releases should be patch bumps from {previous.version}, "
        f"but actual bump is {actual_bump}.",
    )


def read_plugin_version() -> str | None:
    """Extract pluginVersion=X.Y.Z from gradle.properties."""
    for line in GRADLE_PROPERTIES.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.strip().partition("=")
        if separator and key.strip() == "pluginVersion":
            version = value.strip()
            return version or None
    return None


def read_plugin_xml_change_notes_version() -> str | None:
    """Return the first <h3>X.Y.Z</h3> version in plugin.xml change-notes."""
    xml = PLUGIN_XML.read_text(encoding="utf-8")
    change_notes_start = xml.find("<change-notes")
    if change_notes_start < 0:
        return None
    open_tag_end = xml.find(">", change_notes_start)
    if open_tag_end < 0:
        return None
    change_notes_end = xml.find("</change-notes>", open_tag_end)
    if change_notes_end < 0:
        return None
    return extract_h3_version(xml[open_tag_end + 1 : change_notes_end])


def extract_h3_version(text: str) -> str | None:
    """Return the first exact X.Y.Z heading inside a change-notes block."""
    heading_start = text.find("<h3>")
    if heading_start < 0:
        return None
    version_start = heading_start + len("<h3>")
    heading_end = text.find("</h3>", version_start)
    if heading_end < 0:
        return None
    candidate = text[version_start:heading_end].strip()
    try:
        parse_version(candidate)
    except ValueError:
        return None
    return candidate


def classify_version_bump(previous: str, current: str, report: Report) -> str | None:
    """Return the single-step semantic bump between two X.Y.Z versions."""
    try:
        previous_parts = parse_version(previous)
        current_parts = parse_version(current)
    except ValueError as error:
        report.error("_release_semantics_", str(error))
        return None

    if current_parts <= previous_parts:
        report.error(
            "_release_semantics_",
            f"latest CHANGELOG version {current} must be newer than {previous}.",
        )
        return None

    previous_major, previous_minor, previous_patch = previous_parts
    current_major, current_minor, current_patch = current_parts
    if (
        current_major == previous_major
        and current_minor == previous_minor
        and current_patch == previous_patch + 1
    ):
        return "patch"
    if (
        current_major == previous_major
        and current_minor == previous_minor + 1
        and current_patch == 0
    ):
        return "minor"
    if current_major == previous_major + 1 and current_minor == 0 and current_patch == 0:
        return "major"

    report.error(
        "_release_semantics_",
        f"version moved from {previous} to {current}; expected exactly one "
        "major, minor, or patch step.",
    )
    return None


def parse_version(version: str) -> tuple[int, int, int]:
    """Parse an X.Y.Z version into comparable integers."""
    parts = version.split(".")
    if len(parts) != 3:
        raise ValueError(f"expected X.Y.Z version, got {version!r}")
    return int(parts[0]), int(parts[1]), int(parts[2])
