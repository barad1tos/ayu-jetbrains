"""Invariant 2: tier-tagged bullets in latest changelog map to features."""

from __future__ import annotations

import re
from typing import Any, cast

from .features import iter_features
from .paths import CHANGELOG
from .report import Report

_VERSION_HEADER = re.compile(r"^##\s*\[(\d+\.\d+\.\d+)]", re.MULTILINE)
_NEXT_HEADER = re.compile(r"^##\s*\[", re.MULTILINE)
# `[^\n]+` instead of `.+` — `.+` plus surrounding `\s*` is what static
# analysers flag as polynomial backtracking. `[^\n]+` is a single-char class
# with no overlap, so the matcher is linear.
_TIER_BULLET = re.compile(r"^\s*-\s*\[(Paid|Free)]\s*([^\n]+)$", re.MULTILINE)


def extract_latest_changelog_section() -> tuple[str, list[tuple[str, str]]]:
    """Return (version, [(tier, bullet_text), ...]) for the latest CHANGELOG section."""
    text = CHANGELOG.read_text(encoding="utf-8")
    match = _VERSION_HEADER.search(text)
    if not match:
        raise SystemExit("Could not find latest version header in CHANGELOG.md")
    version: str = match[1]
    start = match.end()
    next_match = _NEXT_HEADER.search(text, pos=start)
    end = next_match.start() if next_match else len(text)
    section = text[start:end]
    bullets = cast(list[tuple[str, str]], _TIER_BULLET.findall(section))
    return version, bullets


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
