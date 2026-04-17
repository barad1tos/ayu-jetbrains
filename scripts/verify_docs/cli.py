"""argparse + orchestration — the `main` entry invoked by scripts/verify-docs.py."""

from __future__ import annotations

import argparse

from .changelog import check_changelog_cross_ref
from .features import load_features
from .inventory import check_asset_inventory
from .keywords import check_keywords
from .marketplace import check_marketplace_sync
from .paths import FEATURES_YAML, REPO_ROOT
from .report import Report
from .required_links import check_required_links
from .screenshots import check_screenshots
from .stamps import restamp_orphaned, update_hashes


def main() -> int:
    ap = argparse.ArgumentParser(
        description=(
            "Verify that docs/features.yml stays in sync with README + plugin.xml + CHANGELOG."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument(
        "--update-hashes",
        action="store_true",
        help="Recompute content_sha256 for every screenshot and rewrite features.yml",
    )
    ap.add_argument(
        "--restamp",
        action="store_true",
        help="Rewrite every screenshot's last_verified_sha to the current HEAD "
        "when the existing stamp is orphaned (not an ancestor of HEAD). "
        "Use after a squash-merge rebase or when adopting the inherited "
        "stamp from main onto a fresh feature branch.",
    )
    args = ap.parse_args()

    data = load_features()

    if args.update_hashes:
        count = update_hashes(data)
        print(
            f"Updated {count} content_sha256 entry/entries in "
            f"{FEATURES_YAML.relative_to(REPO_ROOT)}"
        )
        return 0

    if args.restamp:
        count = restamp_orphaned(data)
        print(
            f"Re-stamped {count} orphaned last_verified_sha entry/entries in "
            f"{FEATURES_YAML.relative_to(REPO_ROOT)} to current HEAD"
        )
        return 0

    report = Report()
    check_keywords(data, report)
    check_changelog_cross_ref(data, report)
    check_screenshots(data, report)
    check_required_links(data, report)
    check_asset_inventory(data, report)
    check_marketplace_sync(data, report)
    report.print()
    return 1 if report.has_errors else 0
