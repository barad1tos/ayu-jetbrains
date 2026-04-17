"""Thin git subprocess wrappers + SHA-256 file hash used across the checks.

Every helper returns a narrow, typed value (bool / str / set[str] / None)
so call sites don't need to know about subprocess.CompletedProcess. The
only exception is `git_changed_since`, which RAISES on git failure —
silently returning an empty set would let a stale screenshot slip past
CI if git itself broke (missing ref, shallow clone, disk error).
"""

from __future__ import annotations

import functools
import hashlib
import subprocess
from pathlib import Path

from .paths import REPO_ROOT


@functools.cache
def primary_ref() -> str:
    """Return the remote primary-branch ref (e.g. `origin/main`).

    Derives from `git symbolic-ref refs/remotes/origin/HEAD` when that ref
    exists (set by `git clone` and recoverable via `git remote set-head
    origin -a`); falls back to `origin/main` for repos that never had the
    symbolic ref initialized. Caching keeps the lookup cheap when every
    feature in features.yml runs through the freshness pipeline.
    """
    result = subprocess.run(
        ["git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    ref = result.stdout.strip() if result.returncode == 0 else ""
    return ref or "origin/main"


def rev_parse(ref: str) -> str:
    """Resolve `ref` to a full commit SHA; empty string on failure."""
    result = subprocess.run(
        ["git", "rev-parse", ref],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else ""


def is_ancestor_of_head(sha: str) -> bool:
    """Return True when `sha` is reachable from HEAD via parent edges.

    False on the classic post-squash-merge state: the feature-branch SHAs
    that were stamped during PR iteration sit on a now-unreferenced branch
    whose tip isn't an ancestor of the squash commit on main.

    Git's `merge-base --is-ancestor` exits 0 on "is ancestor", 1 on
    "not ancestor", and 128 on a genuine repo error (bad object, missing
    ref, corrupt pack). Exit 128 gets the same False treatment as exit 1
    here — a corrupted repo is already in a state where the freshness
    story is moot, and the orphan-stamp handler downstream surfaces the
    SHA as orphaned with enough context for a human to investigate.
    """
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", sha, "HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
    )
    return result.returncode == 0


def head_points_at_primary() -> bool:
    """True when HEAD's commit SHA equals the primary branch's commit SHA.

    SHA-identity check — correct under both named-branch checkouts (local
    `main` tracking `origin/main`) and detached-HEAD checkouts (CI runs
    that check out `origin/main` as a disconnected ref). An
    `abbrev-ref HEAD == 'main'` alternative misses the detached case
    because `git rev-parse --abbrev-ref HEAD` returns the literal string
    "HEAD" when not on a named branch.
    """
    head_sha = rev_parse("HEAD")
    primary_sha = rev_parse(primary_ref())
    # Guard both sides: `rev_parse` returns "" on failure, and "" == ""
    # would spuriously match if a future refactor widened the failure
    # surface. Keeping the `bool(primary_sha)` check makes the invariant
    # explicit instead of relying on the short-circuit.
    return bool(head_sha) and bool(primary_sha) and head_sha == primary_sha


def merge_base_with_primary() -> str | None:
    """Return merge-base SHA with the remote primary branch, or None if the
    primary ref is not fetched (shallow clone, new repo without `origin/HEAD`)."""
    result = subprocess.run(
        ["git", "merge-base", primary_ref(), "HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    base = result.stdout.strip()
    return base or None


def commit_exists(sha: str) -> bool:
    result = subprocess.run(
        ["git", "rev-parse", "--verify", f"{sha}^{{commit}}"],
        cwd=REPO_ROOT,
        capture_output=True,
    )
    return result.returncode == 0


def changed_since(sha: str, paths: list[str]) -> set[str]:
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


def head_short_sha() -> str | None:
    """Return `git rev-parse --short HEAD` output, or None on failure."""
    result = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return None if result.returncode != 0 else result.stdout.strip() or None


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()
