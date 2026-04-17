"""Invariant 6: <description> matches the last-published Marketplace SHA."""

from __future__ import annotations

import functools
import hashlib

from .paths import GRADLE_PROPERTIES
from .plugin_xml import extract_plugin_xml_description
from .report import Report


@functools.cache
def _read_plugin_version() -> str | None:
    """Extract pluginVersion=X.Y.Z from gradle.properties.

    Tolerates `pluginVersion = X`, `pluginVersion\t=\tX`, etc. — any whitespace
    around the `=`. Returns None when the key is absent or has no value so
    callers fail loudly instead of silently no-op'ing the sync guard.
    """
    for line in GRADLE_PROPERTIES.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        key, sep, value = stripped.partition("=")
        if sep and key.strip() == "pluginVersion":
            version = value.strip()
            return version or None
    return None


def check_marketplace_sync(data: dict, report: Report) -> None:
    """Invariant 6: when gradle pluginVersion matches the last-published version,
    the current plugin.xml <description> hash must match what shipped.

    Guards the trap where someone edits <description> without bumping the
    version — changes land on main but the Marketplace "About" page keeps
    showing whatever the last published JAR carried. After `/release-plugin`
    bumps the version and re-publishes, it also refreshes
    `release_sync.last_published_description_sha256` so this check stays
    silent during the window the published version == the on-disk version.
    """
    sync = data.get("release_sync") or {}
    expected_version = (sync.get("last_published_version") or "").strip()
    expected_sha = (sync.get("last_published_description_sha256") or "").strip()
    if not expected_version or not expected_sha:
        return  # Bootstrap mode — no last-published state recorded yet.

    current_version = _read_plugin_version()
    if current_version is None:
        report.error(
            "_release_sync_",
            "gradle.properties is missing a `pluginVersion=X.Y.Z` line — "
            "the Marketplace-sync guard cannot verify anything without it. "
            "Restore the key or fix the format.",
        )
        return
    if current_version != expected_version:
        # Mid-development; main is ahead of the last-published version.
        # The next /release-plugin run will refresh the hash.
        return

    current_sha = hashlib.sha256(
        extract_plugin_xml_description().encode("utf-8")
    ).hexdigest()
    if current_sha == expected_sha:
        return

    report.error(
        "_release_sync_",
        f"plugin.xml <description> has changed since v{expected_version} was "
        f"published to Marketplace (expected SHA {expected_sha[:12]}..., got "
        f"{current_sha[:12]}...). The Marketplace 'About' page still serves "
        f"the old copy. Either (a) bump pluginVersion + re-run /release-plugin "
        f"so the new description ships, OR (b) revert the <description> edit "
        f"to match the currently-published state.",
    )
