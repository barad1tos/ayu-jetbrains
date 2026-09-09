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
from typing import Any

from .features import iter_features
from .git_utils import file_sha256, head_short_sha, is_ancestor_of_head, sources_sha256
from .paths import FEATURES_YAML, REPO_ROOT


def update_hashes(data: dict[str, Any]) -> int:
    """Update content_sha256 values in-place, preserving the file's comments.

    PyYAML's safe_dump would rewrite the whole document and drop the header
    comment + line-folded descriptions. Instead we do a surgical regex
    substitution keyed by `path:` — each screenshot's path uniquely anchors
    its content_sha256 line within the same block.
    """
    text = FEATURES_YAML.read_text(encoding="utf-8")
    updated = 0
    for feat in iter_features(data):
        shot: dict[str, Any] = feat.get("screenshot") or {}
        path: str = (shot.get("path") or "").strip()
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


def restamp_orphaned(data: dict[str, Any]) -> int:
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
        shot: dict[str, Any] = feat.get("screenshot") or {}
        stored_sha: str = (shot.get("last_verified_sha") or "").strip()
        path: str = (shot.get("path") or "").strip()
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


def restamp_reviewed(data: dict[str, Any], feature_ids: list[str]) -> int:
    """Attest explicitly reviewed screenshots against current source bytes.

    Keep image hashes and capture dates unchanged. Validate all selected entries
    before writing, so a typo or unreadable source cannot leave a partial stamp.
    Raises ValueError or OSError if provenance cannot be recorded safely.
    """
    selected = set(feature_ids)
    features = {feature["id"]: feature for feature in iter_features(data)}
    if unknown := selected - features.keys():
        raise ValueError(f"Unknown feature IDs: {', '.join(sorted(unknown))}")
    head_sha = head_short_sha()
    if head_sha is None:
        raise ValueError("Cannot resolve HEAD SHA; refusing to re-stamp")
    text = FEATURES_YAML.read_text(encoding="utf-8")
    updated = 0
    for feature_id in sorted(selected):
        shot: dict[str, Any] = features[feature_id].get("screenshot") or {}
        path: str = (shot.get("path") or "").strip()
        sources: list[str] = shot.get("sources") or []
        if not path or not sources or not shot.get("last_verified_sha"):
            raise ValueError(
                f"{feature_id}: screenshot requires path, sources, last_verified_sha"
            )
        if file_sha256(REPO_ROOT / path) != shot.get("content_sha256"):
            raise ValueError(
                f"{feature_id}: screenshot content_sha256 differs or is missing; "
                "review the image and run --update-hashes before re-stamping"
            )
        source_hash = sources_sha256(sources)
        previous = text
        text, _ = _replace_last_verified_sha(text, path, head_sha)
        text, _ = _replace_field_after_path(
            text,
            path,
            "sources_sha256",
            source_hash,
            preserve_quotes=True,
            insert_missing=True,
        )
        updated += text != previous
    if updated:
        FEATURES_YAML.write_text(text, encoding="utf-8")
    return updated


def _replace_field_after_path(
    text: str,
    path: str,
    field_name: str,
    new_value: str,
    *,
    preserve_quotes: bool,
    insert_missing: bool = False,
) -> tuple[str, bool]:
    """Replace a field within the screenshot block identified by its path.

    Regex anchors on the path so the rewrite stays scoped to one screenshot
    block, preserving surrounding YAML comments and formatting. Returns
    (new_text, changed). `changed` is False when the value already matches
    (no-op) or an optional field is absent. Missing blocks raise ValueError.

    When `preserve_quotes` is True the existing quote style is kept: an
    existing `"abcdef1"` stays quoted, a bare `abcdef1` stays bare.
    """
    block = re.search(
        rf"^(?P<indent>[ \t]*)path:[ \t]*{re.escape(path)}[ \t]*\n"
        rf"(?:(?P=indent)[^\n]*\n|[ \t]*\n)*",
        text,
        re.MULTILINE,
    )
    if block is None:
        raise ValueError(f"Cannot locate screenshot block for {path}")
    indent = block["indent"]
    pattern = re.compile(
        rf"^({indent}{field_name}:[ \t]*)\"?(?P<old>[0-9a-fA-F]*)\"?",
        re.MULTILINE,
    )
    block_text = block[0]
    match = pattern.search(block_text)
    if not match:
        if insert_missing:
            block_text += f'{indent}{field_name}: "{new_value}"\n'
            return text[: block.start()] + block_text + text[block.end() :], True
        return text, False
    if match["old"] == new_value:
        return text, False
    if preserve_quotes and match[0].rstrip().endswith('"'):
        replacement = f'"{new_value}"'
    else:
        replacement = new_value
    start = block.start() + match.start()
    end = block.start() + match.end()
    new_text = text[:start] + match[1] + replacement + text[end:]
    return new_text, True


def _replace_last_verified_sha(text: str, path: str, new_sha: str) -> tuple[str, bool]:
    """Replace `last_verified_sha:` for the given screenshot path; keep quote style."""
    return _replace_field_after_path(
        text, path, "last_verified_sha", new_sha, preserve_quotes=True
    )


def _replace_content_hash(text: str, path: str, new_hash: str) -> tuple[str, bool]:
    """Replace `content_sha256:` for the given screenshot path; emit bare value."""
    return _replace_field_after_path(
        text, path, "content_sha256", new_hash, preserve_quotes=False
    )
