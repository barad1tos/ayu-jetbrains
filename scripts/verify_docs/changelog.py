"""Invariant 2: tier-tagged bullets in latest changelog map to features."""

from __future__ import annotations

import re

from .features import iter_features
from .paths import CHANGELOG
from .report import Report


def extract_latest_changelog_section() -> tuple[str, list[tuple[str, str]]]:
    """Return (version, [(tier, bullet_text), ...]) for the latest CHANGELOG section."""
    text = CHANGELOG.read_text(encoding="utf-8")
    m = re.search(r"^##\s*\[(\d+\.\d+\.\d+)]", text, re.MULTILINE)
    if not m:
        raise SystemExit("Could not find latest version header in CHANGELOG.md")
    version = m[1]
    start = m.end()
    next_m = re.search(r"^##\s*\[", text[start:], re.MULTILINE)
    end = start + (next_m.start() if next_m else len(text) - start)
    section = text[start:end]
    # Match bullets that start with [Paid] or [Free] (tier-tagged, user-facing).
    bullets = re.findall(r"^\s*-\s*\[(Paid|Free)]\s*(.+)$", section, re.MULTILINE)
    return version, bullets


def check_changelog_cross_ref(data: dict, report: Report) -> None:
    """Invariant 2: tier-tagged bullets in latest changelog map to features with matching `introduced`."""
    version, bullets = extract_latest_changelog_section()
    if not bullets:
        # No tier-tagged bullets — could be a fix-only patch release. OK.
        return
    matching_features = [
        f for f in iter_features(data) if f.get("introduced") == version
    ]
    if not matching_features:
        report.error(
            "_changelog_xref_",
            f"CHANGELOG [{version}] has {len(bullets)} tagged bullet(s) but "
            f"no features.yml entry with introduced: {version}. Add one feature "
            f"per new bullet or mark the bullets as bug-fixes (drop [Paid]/[Free]).",
        )
