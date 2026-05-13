"""Invariant 1: every feature keyword appears in README + plugin.xml description."""

from __future__ import annotations

from typing import Any

from .features import iter_features
from .paths import README
from .plugin_xml import extract_plugin_xml_description
from .report import Report


def check_keywords(data: dict[str, Any], report: Report) -> None:
    """Invariant 1: every feature keyword is present in README + plugin.xml description."""
    readme_text = README.read_text(encoding="utf-8").lower()
    description_text = extract_plugin_xml_description().lower()

    for feat in iter_features(data):
        keyword = feat.get("keyword", "").strip().lower()
        feature_id = feat.get("id", "<missing-id>")
        if not keyword:
            report.error(feature_id, "missing `keyword` field (feature.yml schema)")
            continue
        if keyword not in readme_text:
            report.error(
                feature_id,
                f"keyword '{feat['keyword']}' not found in README.md — "
                f"the feature is declared in features.yml but not mentioned in marketing copy",
            )
        if keyword not in description_text:
            report.error(
                feature_id,
                f"keyword '{feat['keyword']}' not found in plugin.xml <description> — "
                f"Marketplace 'About' page will not mention this feature",
            )
