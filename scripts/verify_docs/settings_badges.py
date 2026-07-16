"""Settings-badge coverage: features from the current release onward need anchors.

The wayfinding badges (SettingsBadges.kt registry) are how users discover new
settings. A feature shipped in the current minor release — or staged in
features.yml for any later release — without an anchor is invisible in the
Settings dialog; this check makes that a hard error instead of a UX
regression discovered by hand. Features older than the current minor are
exempt: their anchors retire from the registry by curation.

Opt-out: a feature with no settings surface (pure fix, engine-only change)
declares `settings_badge: none` in its features.yml entry.
"""

from __future__ import annotations

import re
from typing import Any

from .features import iter_features
from .paths import GRADLE_PROPERTIES, REPO_ROOT
from .report import Report

SETTINGS_BADGES_KT = (
    REPO_ROOT / "src/main/kotlin/dev/ayuislands/settings/SettingsBadges.kt"
)

_ANCHOR_ID_PATTERN = re.compile(r'id\s*=\s*"([a-z0-9-]+)"')
_PLUGIN_VERSION_PATTERN = re.compile(r"^pluginVersion\s*=\s*(\d+)\.(\d+)", re.MULTILINE)
_INTRODUCED_PATTERN = re.compile(r"^(\d+)\.(\d+)")


def _current_minor_version() -> tuple[int, int] | None:
    match = _PLUGIN_VERSION_PATTERN.search(GRADLE_PROPERTIES.read_text())
    return (int(match.group(1)), int(match.group(2))) if match else None


def _introduced_minor(introduced: str) -> tuple[int, int] | None:
    match = _INTRODUCED_PATTERN.match(introduced)
    return (int(match.group(1)), int(match.group(2))) if match else None


def _registry_anchor_ids() -> set[str]:
    return set(_ANCHOR_ID_PATTERN.findall(SETTINGS_BADGES_KT.read_text()))


def check_settings_badges(data: dict[str, Any], report: Report) -> None:
    """Features introduced at or after the current minor release need badge anchors."""
    current = _current_minor_version()
    if current is None:
        report.error(
            "settings-badges", "cannot parse pluginVersion from gradle.properties"
        )
        return

    anchors = _registry_anchor_ids()
    for feature in iter_features(data):
        introduced = str(feature.get("introduced", ""))
        introduced_minor = _introduced_minor(introduced)
        if introduced_minor is None or introduced_minor < current:
            continue
        if feature.get("settings_badge") == "none":
            continue
        feature_id = str(feature.get("id", "?"))
        if feature_id not in anchors:
            report.error(
                feature_id,
                f"introduced in {introduced} but missing from the SettingsBadges "
                "registry — add an anchor (and a bindNewSettingBadge/newFeatureBadge "
                "call), or declare `settings_badge: none` if the feature has no "
                "settings surface",
            )
