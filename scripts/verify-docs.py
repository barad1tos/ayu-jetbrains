#!/usr/bin/env -S uv run --script --project scripts
"""Verify that docs/features.yml stays in sync with README + plugin.xml + CHANGELOG.

Three invariants (see docs/features.yml header for the rationale):

  1. README + plugin.xml <description> both mention every feature's keyword
  2. CHANGELOG.md [Paid]/[Free] bullets in the latest version section correspond
     to at least one feature with matching `introduced: X.Y.Z`
  3. Screenshots declared under a feature aren't stale:
     - sources' files exist
     - `git log <last_verified_sha>..HEAD -- <sources>` is empty
     - file content SHA-256 matches `content_sha256` (or content_sha256 is empty
       meaning "seed me"; script offers --update-hashes to populate)

CLI:
  scripts/verify-docs.py                  # fail on any drift (used by CI)
  scripts/verify-docs.py --update-hashes  # recompute content_sha256 for every
                                          # screenshot and rewrite features.yml
                                          # (use when intentionally re-capturing)
"""

from __future__ import annotations

import argparse
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
    if not _check_commit_exists(fid, sha, report):
        return
    _check_source_freshness(fid, path, sha, sources, report)
    _check_content_hash(fid, screenshot_file, stored_hash, report)


def _check_source_paths_exist(fid: str, sources: list[str], report: Report) -> None:
    for src in sources:
        if not (REPO_ROOT / src).exists():
            report.error(
                fid, f"screenshot source '{src}' does not exist (renamed or deleted?)"
            )


def _check_commit_exists(fid: str, sha: str, report: Report) -> bool:
    if _git_commit_exists(sha):
        return True
    report.error(fid, f"last_verified_sha '{sha}' is not a known git commit")
    return False


def _check_source_freshness(
    fid: str, path: str, sha: str, sources: list[str], report: Report
) -> None:
    changed = _git_changed_since(sha, sources)
    if not changed:
        return
    preview = ", ".join(sorted(changed)[:3])
    more = f" (+{len(changed) - 3} more)" if len(changed) > 3 else ""
    report.error(
        fid,
        f"sources changed since last_verified_sha {sha[:8]}: {preview}{more}. "
        f"Re-capture {path} and bump last_verified_sha + content_sha256, "
        f"OR if the UI didn't visually change, re-stamp last_verified_sha "
        f"to the current HEAD.",
    )


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
    """Return the set of file paths under `paths` that changed after `sha`."""
    result = subprocess.run(
        ["git", "log", "--name-only", "--pretty=", f"{sha}..HEAD", "--"] + paths,
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return set()
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


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "--update-hashes",
        action="store_true",
        help="Recompute content_sha256 for every screenshot and rewrite features.yml",
    )
    args = ap.parse_args()

    data = load_features()

    if args.update_hashes:
        count = update_hashes(data)
        print(
            f"Updated {count} content_sha256 entry/entries in {FEATURES_YAML.relative_to(REPO_ROOT)}"
        )
        return 0

    report = Report()
    check_keywords(data, report)
    check_changelog_cross_ref(data, report)
    check_screenshots(data, report)
    report.print()
    return 1 if report.has_errors else 0


if __name__ == "__main__":
    sys.exit(main())
