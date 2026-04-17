"""Mutators for `--update-hashes` and `--restamp` CLI modes.

Surgical regex-based rewrites keyed by `path:` — each screenshot's path
uniquely anchors its `content_sha256` / `last_verified_sha` lines within
the same block. PyYAML's safe_dump would rewrite the whole document and
drop the header comment + line-folded descriptions, which is why we
regex-substitute instead of load → mutate → dump.
"""

from __future__ import annotations

import re
import sys

from .features import iter_features
from .git_utils import file_sha256, head_short_sha, is_ancestor_of_head
from .paths import FEATURES_YAML, REPO_ROOT


def update_hashes(data: dict) -> int:
    """Update content_sha256 values in-place, preserving the file's comments.

    PyYAML's safe_dump would rewrite the whole document and drop the header
    comment + line-folded descriptions. Instead we do a surgical regex
    substitution keyed by `path:` — each screenshot's path uniquely anchors
    its content_sha256 line within the same block.
    """
    text = FEATURES_YAML.read_text(encoding="utf-8")
    updated = 0
    for feat in iter_features(data):
        shot = feat.get("screenshot") or {}
        path = (shot.get("path") or "").strip()
        if not path:
            continue
        screenshot_file = REPO_ROOT / path
        if not screenshot_file.exists():
            continue
        new_hash = file_sha256(screenshot_file)
        text, changed = _replace_content_hash(text, path, new_hash)
        if changed:
            updated += 1

    if updated:
        FEATURES_YAML.write_text(text, encoding="utf-8")
    return updated


def restamp_orphaned(data: dict) -> int:
    """Rewrite every orphaned `last_verified_sha` in features.yml to HEAD.

    "Orphaned" = `git merge-base --is-ancestor sha HEAD` returns non-zero.
    Keeps ancestor stamps untouched so an intentional pre-verified marker
    (the stamp was pinned to a specific commit for a reason) survives.

    Used two ways:
      - `scripts/verify-docs.py --restamp` after a squash-merge landed on
        main, to realign docs/features.yml with the merge commit.
      - `scripts/verify-docs.py --restamp` on a feature branch that
        inherited an orphaned stamp from main and needs to reclaim it
        before editing tracked sources.
    """
    head_sha = head_short_sha()
    if head_sha is None:
        print("Cannot resolve HEAD SHA; refusing to re-stamp.", file=sys.stderr)
        return 0
    text = FEATURES_YAML.read_text(encoding="utf-8")
    updated = 0
    for feat in iter_features(data):
        shot = feat.get("screenshot") or {}
        stored_sha = (shot.get("last_verified_sha") or "").strip()
        path = (shot.get("path") or "").strip()
        if not stored_sha or not path:
            continue
        if is_ancestor_of_head(stored_sha):
            continue  # stamp is still on the live history — leave alone
        text, changed = _replace_last_verified_sha(text, path, head_sha)
        if changed:
            updated += 1

    if updated:
        FEATURES_YAML.write_text(text, encoding="utf-8")
    return updated


def _replace_last_verified_sha(text: str, path: str, new_sha: str) -> tuple[str, bool]:
    """Replace `last_verified_sha:` immediately following a given `path:` line.

    Mirrors `_replace_content_hash`: regex anchor on the path to scope the
    rewrite to one screenshot block, preserve surrounding YAML comments
    and formatting. Returns (new_text, changed).
    """
    escaped = re.escape(path)
    pattern = re.compile(
        rf"(path:\s*{escaped}\s*\n(?:.*\n){{0,40}}?\s*last_verified_sha:\s*)"
        rf"\"?(?P<old>[0-9a-fA-F]*)\"?",
        re.MULTILINE,
    )
    match = pattern.search(text)
    if not match:
        return text, False
    if match["old"] == new_sha:
        return text, False
    # Preserve the quoted form: existing "abcdef1" stays quoted, bare abcdef1 stays bare.
    quoted = match[0].rstrip().endswith('"')
    replacement = f'"{new_sha}"' if quoted else new_sha
    new_text = text[: match.start()] + match[1] + replacement + text[match.end() :]
    return new_text, True


def _replace_content_hash(text: str, path: str, new_hash: str) -> tuple[str, bool]:
    """Replace the content_sha256 immediately following a given path: line.

    Returns (new_text, changed). `changed` is False when the hash already
    matches (no-op) or the pattern didn't match (block not found).
    """
    escaped = re.escape(path)
    # Match: `path: <escaped>` followed within ~40 lines by `content_sha256:`.
    # Capture the existing hash so we can detect no-op updates.
    pattern = re.compile(
        rf"(path:\s*{escaped}\s*\n(?:.*\n){{0,40}}?\s*content_sha256:\s*)"
        rf"\"?(?P<old>[0-9a-fA-F]*)\"?",
        re.MULTILINE,
    )
    match = pattern.search(text)
    if not match:
        return text, False
    if match["old"] == new_hash:
        return text, False
    new_text = text[: match.start()] + match[1] + new_hash + text[match.end() :]
    return new_text, True
