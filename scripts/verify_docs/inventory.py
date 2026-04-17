"""Invariant 5: asset_inventory ↔ filesystem ↔ referenced_by consistency."""

from __future__ import annotations

from pathlib import Path

from .paths import REPO_ROOT
from .report import Report

_ASSET_EXTENSIONS = (".png", ".gif", ".svg", ".jpg", ".jpeg", ".webp")
_ASSET_SEARCH_ROOTS = ("assets", "src/main/resources/whatsnew")


def check_asset_inventory(data: dict, report: Report) -> None:
    """Invariant 5: every on-disk image under the tracked roots is inventoried,
    and every inventory entry's `referenced_by` files actually mention the path.

    Three failure modes caught:
      - Orphan asset:       PNG on disk but not in asset_inventory
      - Broken reference:   inventory says "README.md" but README doesn't
                            actually contain the path
      - Missing justification: inventory entry has referenced_by=[] AND no
                               `justification` explaining why it's unused
    """
    inventory = data.get("asset_inventory") or []
    _check_inventory_entries(inventory, report)
    _check_filesystem_orphans(inventory, report)


def _check_inventory_entries(inventory: list[dict], report: Report) -> None:
    for entry in inventory:
        _check_one_inventory_entry(entry, report)


def _check_one_inventory_entry(entry: dict, report: Report) -> None:
    path = (entry.get("path") or "").strip()
    if not path:
        report.error("_asset_inventory_", "inventory entry missing `path`")
        return
    if not (REPO_ROOT / path).exists():
        report.error(path, "inventory path does not exist on disk")
        return
    # Normalize: drop non-strings, strip whitespace, drop empties. A bare
    # `referenced_by: [""]` would otherwise resolve to REPO_ROOT itself
    # (`Path / ""` == `Path`) and produce nonsense errors about the repo
    # root not containing the asset path.
    raw_referenced_by = entry.get("referenced_by") or []
    referenced_by = [
        ref.strip() for ref in raw_referenced_by if isinstance(ref, str) and ref.strip()
    ]
    justification = (entry.get("justification") or "").strip()
    if not referenced_by and not justification:
        report.error(
            path,
            "asset has no `referenced_by` and no `justification` — either "
            "add a README/plugin.xml reference or explain why it's unreferenced",
        )
        return
    basename = Path(path).name
    for ref_rel in referenced_by:
        _check_reference_in_file(path, basename, ref_rel, report)


def _check_reference_in_file(
    path: str, basename: str, ref_rel: str, report: Report
) -> None:
    ref_path = REPO_ROOT / ref_rel
    if not ref_path.exists():
        report.error(path, f"`referenced_by` file '{ref_rel}' does not exist")
        return
    content = ref_path.read_text(encoding="utf-8")
    # Accept either full repo-relative path (README, plugin.xml) or bare
    # basename (manifest.json, which joins a directory prefix at runtime).
    if path not in content and basename not in content:
        report.error(
            path,
            f"inventory claims '{ref_rel}' references this asset, but the "
            f"file contains neither the full path nor the basename — add "
            f"the reference or remove '{ref_rel}' from `referenced_by`",
        )


def _check_filesystem_orphans(inventory: list[dict], report: Report) -> None:
    inventoried = {(entry.get("path") or "").strip() for entry in inventory}
    for root_rel in _ASSET_SEARCH_ROOTS:
        root = REPO_ROOT / root_rel
        if not root.is_dir():
            continue
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in _ASSET_EXTENSIONS:
                continue
            rel = path.relative_to(REPO_ROOT).as_posix()
            if rel not in inventoried:
                report.error(
                    rel,
                    "orphan asset — not listed in features.yml `asset_inventory`. "
                    "Add an entry with `referenced_by:` or `justification:`.",
                )
