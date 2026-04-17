"""Invariant 3: screenshot freshness — source git-state + byte-hash."""

from __future__ import annotations

from pathlib import Path

from .features import iter_features
from .git_utils import (
    changed_since,
    commit_exists,
    file_sha256,
    head_points_at_primary,
    is_ancestor_of_head,
    merge_base_with_primary,
)
from .paths import REPO_ROOT
from .report import Report


def check_screenshots(data: dict, report: Report) -> None:
    """Invariant 3: screenshot freshness via source stamp + byte hash.

    Split into per-check helpers so the orchestrator stays straight-line
    and individual guards are testable in isolation.
    """
    for feat in iter_features(data):
        if shot := feat.get("screenshot"):
            _check_one_screenshot(feat["id"], shot, report)


def _check_one_screenshot(fid: str, shot: dict, report: Report) -> None:
    path = shot.get("path", "").strip()
    sha = shot.get("last_verified_sha", "").strip()
    sources = shot.get("sources") or []
    stored_hash = (shot.get("content_sha256") or "").strip()

    if not path or not sha or not sources:
        report.error(
            fid,
            "screenshot block incomplete — required: path, last_verified_sha, sources",
        )
        return

    screenshot_file = REPO_ROOT / path
    if not screenshot_file.exists():
        report.error(fid, f"screenshot path '{path}' does not exist")
        return

    _check_source_paths_exist(fid, sources, report)
    _check_freshness_by_state(fid, path, sha, sources, report)
    _check_content_hash(fid, screenshot_file, stored_hash, report)


def _check_source_paths_exist(fid: str, sources: list[str], report: Report) -> None:
    for src in sources:
        if not (REPO_ROOT / src).exists():
            report.error(
                fid, f"screenshot source '{src}' does not exist (renamed or deleted?)"
            )


def _check_freshness_by_state(
    fid: str, path: str, sha: str, sources: list[str], report: Report
) -> None:
    """Dispatch source-freshness check based on how the stamp relates to HEAD.

    Three cases:
      (A) Stamp is ancestor of HEAD (happy path) → diff sources since stamp;
          any change is an ERROR (re-stamp or re-capture required).
      (B) Stamp is orphaned (not ancestor of HEAD) AND we are on `main` →
          post-squash-merge state; emit a warn and lean on content_sha256 to
          catch pixel drift. A future PR that actually touches sources will
          be caught by case (C) on its feature branch.
      (C) Stamp is orphaned AND we are on a non-main branch → use the merge
          base with `origin/main` as the "since" ref. Any source change
          introduced by the current branch is an ERROR that demands
          re-stamping to HEAD (use `scripts/verify-docs.py --restamp`).
    """
    if not commit_exists(sha):
        _handle_orphan_stamp(
            fid, path, sha, sources, report, reason="not in local git object db"
        )
        return
    if is_ancestor_of_head(sha):
        _check_source_freshness_since(fid, path, sha, sources, report, strict_msg=True)
        return
    _handle_orphan_stamp(
        fid, path, sha, sources, report, reason="not an ancestor of HEAD"
    )


def _handle_orphan_stamp(
    fid: str, path: str, sha: str, sources: list[str], report: Report, *, reason: str
) -> None:
    # SHA-identity check instead of branch-name equality so the main path also
    # covers CI runs that check out `origin/main` in detached-HEAD state —
    # `git rev-parse --abbrev-ref HEAD` returns the literal string "HEAD"
    # there and would misroute us into the feature-branch diff logic.
    if head_points_at_primary():
        report.warn(
            fid,
            f"last_verified_sha '{sha[:8]}' is orphaned ({reason}) — expected on "
            f"the primary branch after a squash-merge + branch delete. "
            f"content_sha256 remains the pixel-drift guard; the next PR that "
            f"touches sources must re-stamp.",
        )
        return
    base = merge_base_with_primary()
    if base is None:
        report.warn(
            fid,
            f"last_verified_sha '{sha[:8]}' is orphaned ({reason}) and the "
            f"primary branch ref is not fetched — cannot diff branch changes; "
            f"re-fetch or re-stamp.",
        )
        return
    _check_source_freshness_since(
        fid, path, base, sources, report, strict_msg=False, orphan_sha=sha
    )


def _check_source_freshness_since(
    fid: str,
    path: str,
    since_ref: str,
    sources: list[str],
    report: Report,
    *,
    strict_msg: bool,
    orphan_sha: str | None = None,
) -> None:
    try:
        changed = changed_since(since_ref, sources)
    except RuntimeError as exc:
        report.error(fid, f"git freshness check failed for {path}: {exc}")
        return
    if not changed:
        return
    preview = ", ".join(sorted(changed)[:3])
    more = f" (+{len(changed) - 3} more)" if len(changed) > 3 else ""
    if strict_msg:
        report.error(
            fid,
            f"sources changed since last_verified_sha {since_ref[:8]}: {preview}{more}. "
            f"Re-capture {path} and bump last_verified_sha + content_sha256, "
            f"OR if the UI didn't visually change, re-stamp last_verified_sha "
            f"to the current HEAD (or run `scripts/verify-docs.py --restamp`).",
        )
    else:
        report.error(
            fid,
            f"last_verified_sha '{(orphan_sha or since_ref)[:8]}' is orphaned AND "
            f"this branch touches tracked sources: {preview}{more}. Re-stamp to "
            f"current HEAD before pushing (`scripts/verify-docs.py --restamp`).",
        )


def _check_content_hash(
    fid: str, screenshot_file: Path, stored_hash: str, report: Report
) -> None:
    actual_hash = file_sha256(screenshot_file)
    if stored_hash and actual_hash != stored_hash:
        report.warn(
            fid,
            f"content_sha256 in features.yml is '{stored_hash[:12]}...' but the "
            f"file hashes to '{actual_hash[:12]}...'. Run "
            f"`scripts/verify-docs.py --update-hashes` to sync.",
        )
    if not stored_hash:
        report.warn(
            fid,
            "content_sha256 is empty (seed state). "
            "Run `scripts/verify-docs.py --update-hashes` after capturing.",
        )
