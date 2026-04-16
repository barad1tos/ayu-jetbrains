#!/usr/bin/env -S uv run --script --project scripts
"""Verify that docs/features.yml stays in sync with README + plugin.xml + CHANGELOG.

Three invariants (see docs/features.yml header for the rationale):

  1. README + plugin.xml <description> both mention every feature's keyword
  2. CHANGELOG.md [Paid]/[Free] bullets in the latest version section correspond
     to at least one feature with matching `introduced: X.Y.Z`
  3. Screenshots declared under a feature aren't stale:
     - sources' files exist
     - `git log <last_verified_sha>..HEAD -- <sources>` is empty, with a branch
       -aware fallback so a squash-merged stamp on `main` warns instead of
       erroring (see `_check_freshness_by_state`)
     - file content SHA-256 matches `content_sha256` (or content_sha256 is empty
       meaning "seed me"; script offers --update-hashes to populate)

CLI:
  scripts/verify-docs.py                  # fail on any drift (used by CI)
  scripts/verify-docs.py --update-hashes  # recompute content_sha256 for every
                                          # screenshot and rewrite features.yml
                                          # (use when intentionally re-capturing)
  scripts/verify-docs.py --restamp        # rewrite every orphaned
                                          # last_verified_sha to current HEAD;
                                          # use after a squash-merge lands, or
                                          # when a feature branch inherits an
                                          # orphaned stamp from main before
                                          # editing tracked sources
"""

from __future__ import annotations

import argparse
import functools
import hashlib
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

# PyYAML is a small, widely-available dependency; the CI workflow installs it
# via `pip install pyyaml` before running this script. Let ImportError bubble
# with its default message — no sentinel dance that would confuse type-checkers.
import yaml


REPO_ROOT = Path(__file__).resolve().parent.parent
FEATURES_YAML = REPO_ROOT / "docs" / "features.yml"
README = REPO_ROOT / "README.md"
PLUGIN_XML = REPO_ROOT / "src" / "main" / "resources" / "META-INF" / "plugin.xml"
CHANGELOG = REPO_ROOT / "CHANGELOG.md"


@dataclass
class Finding:
    severity: str  # "error" or "warn"
    feature_id: str
    message: str


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=list)

    def error(self, feature_id: str, message: str) -> None:
        self.findings.append(Finding("error", feature_id, message))

    def warn(self, feature_id: str, message: str) -> None:
        self.findings.append(Finding("warn", feature_id, message))

    @property
    def has_errors(self) -> bool:
        return any(f.severity == "error" for f in self.findings)

    def print(self) -> None:
        if not self.findings:
            print("docs/features.yml in sync with README + plugin.xml + CHANGELOG")
            return
        for f in self.findings:
            prefix = "ERROR" if f.severity == "error" else "warn "
            print(f"  [{prefix}] {f.feature_id}: {f.message}")


def load_features() -> dict:
    with FEATURES_YAML.open() as fh:
        return yaml.safe_load(fh)


def iter_features(data: dict):
    """Yield every feature dict, flattening across categories.

    Category metadata (id, title) isn't currently consumed by any check —
    if a future lint needs it, expose a companion iterator rather than
    reintroduce an unused tuple field here.
    """
    for cat in data.get("categories", {}).values():
        yield from cat.get("features", [])


def extract_plugin_xml_description() -> str:
    """Extract the <description> CDATA block from plugin.xml as plain text."""
    xml = PLUGIN_XML.read_text(encoding="utf-8")
    if m := re.search(
        r"<description>\s*<!\[CDATA\[(.*?)]]>\s*</description>",
        xml,
        re.DOTALL,
    ):
        return m[1]
    else:
        raise SystemExit("Could not find <description> CDATA in plugin.xml")


def check_keywords(data: dict, report: Report) -> None:
    """Invariant 1: every feature keyword is present in README + plugin.xml description."""
    readme_text = README.read_text(encoding="utf-8").lower()
    description_text = extract_plugin_xml_description().lower()

    for feat in iter_features(data):
        kw = feat.get("keyword", "").strip().lower()
        fid = feat.get("id", "<missing-id>")
        if not kw:
            report.error(fid, "missing `keyword` field (feature.yml schema)")
            continue
        if kw not in readme_text:
            report.error(
                fid,
                f"keyword '{feat['keyword']}' not found in README.md — "
                f"the feature is declared in features.yml but not mentioned in marketing copy",
            )
        if kw not in description_text:
            report.error(
                fid,
                f"keyword '{feat['keyword']}' not found in plugin.xml <description> — "
                f"Marketplace 'About' page will not mention this feature",
            )


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
    if not _git_commit_exists(sha):
        _handle_orphan_stamp(
            fid, path, sha, sources, report, reason="not in local git object db"
        )
        return
    if _is_ancestor_of_head(sha):
        _check_source_freshness_since(fid, path, sha, sources, report, strict_msg=True)
        return
    _handle_orphan_stamp(
        fid, path, sha, sources, report, reason="not an ancestor of HEAD"
    )


def _handle_orphan_stamp(
    fid: str, path: str, sha: str, sources: list[str], report: Report, *, reason: str
) -> None:
    if _current_branch() == "main":
        report.warn(
            fid,
            f"last_verified_sha '{sha[:8]}' is orphaned ({reason}) — expected on "
            f"main after a squash-merge + branch delete. content_sha256 remains "
            f"the pixel-drift guard; the next PR that touches sources must re-stamp.",
        )
        return
    base = _merge_base_with_origin_main()
    if base is None:
        report.warn(
            fid,
            f"last_verified_sha '{sha[:8]}' is orphaned ({reason}) and origin/main "
            f"is not fetched — cannot diff branch changes; re-fetch or re-stamp.",
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
        changed = _git_changed_since(since_ref, sources)
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


def _is_ancestor_of_head(sha: str) -> bool:
    """Return True when `sha` is reachable from HEAD via parent edges.

    False on the classic post-squash-merge state: the feature-branch SHAs
    that were stamped during PR iteration sit on a now-unreferenced branch
    whose tip isn't an ancestor of the squash commit on main.
    """
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", sha, "HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
    )
    return result.returncode == 0


def _current_branch() -> str:
    """Return the current branch name; empty string on detached HEAD."""
    result = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else ""


def _merge_base_with_origin_main() -> str | None:
    """Return merge-base SHA with `origin/main`, or None if not computable."""
    result = subprocess.run(
        ["git", "merge-base", "origin/main", "HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    base = result.stdout.strip()
    return base or None


def _check_content_hash(
    fid: str, screenshot_file: Path, stored_hash: str, report: Report
) -> None:
    actual_hash = _file_sha256(screenshot_file)
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


def _git_commit_exists(sha: str) -> bool:
    result = subprocess.run(
        ["git", "rev-parse", "--verify", f"{sha}^{{commit}}"],
        cwd=REPO_ROOT,
        capture_output=True,
    )
    return result.returncode == 0


def _git_changed_since(sha: str, paths: list[str]) -> set[str]:
    """Return the set of file paths under `paths` that changed after `sha`.

    Raises `RuntimeError` on `git log` failure instead of silently returning
    an empty set — an empty set is indistinguishable from "clean history"
    and would let a stale screenshot slip past CI if git itself broke
    (missing ref, shallow clone, disk error).
    """
    result = subprocess.run(
        ["git", "log", "--name-only", "--pretty=", f"{sha}..HEAD", "--", *paths],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"git log failed for sha={sha}: {result.stderr.strip() or 'no stderr'}"
        )
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def _file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


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
        new_hash = _file_sha256(screenshot_file)
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
    head_sha = _head_short_sha()
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
        if _is_ancestor_of_head(stored_sha):
            continue  # stamp is still on the live history — leave alone
        text, changed = _replace_last_verified_sha(text, path, head_sha)
        if changed:
            updated += 1

    if updated:
        FEATURES_YAML.write_text(text, encoding="utf-8")
    return updated


def _head_short_sha() -> str | None:
    """Return `git rev-parse --short HEAD` output, or None on failure."""
    result = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return None if result.returncode != 0 else result.stdout.strip() or None


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


def check_required_links(data: dict, report: Report) -> None:
    """Invariant 4: every required_links entry's substring appears in its `in` file.

    Catches "someone refactored the Community section and dropped the
    GitHub Discussions links", or "removed the Marketplace install badge".
    """
    for entry in data.get("required_links") or []:
        name = entry.get("name", "<unnamed>")
        needle = (entry.get("substring") or "").strip().lower()
        target_rel = (entry.get("in") or "").strip()
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


_ASSET_EXTENSIONS = (".png", ".gif", ".svg", ".jpg", ".jpeg", ".webp")
_ASSET_SEARCH_ROOTS = ("assets", "src/main/resources/whatsnew")

GRADLE_PROPERTIES = REPO_ROOT / "gradle.properties"


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


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
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
            f"Updated {count} content_sha256 entry/entries in {FEATURES_YAML.relative_to(REPO_ROOT)}"
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


if __name__ == "__main__":
    sys.exit(main())
