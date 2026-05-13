"""Invariant 4: every required_links entry's substring appears in its `in` file."""

from __future__ import annotations

from typing import Any

from .paths import REPO_ROOT
from .report import Report


def check_required_links(data: dict[str, Any], report: Report) -> None:
    """Invariant 4: every required_links entry's substring appears in its `in` file.

    Catches "someone refactored the Community section and dropped the
    GitHub Discussions links", or "removed the Marketplace install badge".
    """
    entries: list[dict[str, Any]] = data.get("required_links") or []
    for entry in entries:
        name: str = entry.get("name", "<unnamed>") or "<unnamed>"
        needle: str = (entry.get("substring") or "").strip().lower()
        target_rel: str = (entry.get("in") or "").strip()
        if not needle or not target_rel:
            report.error(name, "required_links entry missing `substring` or `in`")
            continue
        target_path = REPO_ROOT / target_rel
        if not target_path.exists():
            report.error(name, f"required_links target '{target_rel}' does not exist")
            continue
        haystack = target_path.read_text(encoding="utf-8").lower()
        if needle not in haystack:
            report.error(
                name,
                f"required link '{entry['substring']}' not found in {target_rel} — "
                f"add it or remove this entry from features.yml",
            )
