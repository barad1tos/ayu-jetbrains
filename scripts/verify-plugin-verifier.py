#!/usr/bin/env python3
"""Gate IntelliJ Plugin Verifier API findings.

Run after `./gradlew verifyPlugin`. The Gradle task can still exit 0 when
Plugin Verifier marks a target as "Compatible" but records warnings in
`build/reports/pluginVerifier`.

Policy:
- internal API findings are a hard CI/release failure;
- deprecated, scheduled-for-removal, and experimental API findings are surfaced
  as advisory debt so each merge or release can minimize them when a stable
  public replacement exists.
"""

from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_REPORTS_DIR = REPO_ROOT / "build" / "reports" / "pluginVerifier"


def relative(path: Path) -> str:
    """Return a repo-relative path when possible."""
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def nonblank_lines(path: Path) -> list[str]:
    """Return stripped, non-empty lines from a verifier finding file."""
    return [
        line.strip()
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
        if line.strip()
    ]


def verifier_targets_exist(reports_dir: Path) -> bool:
    """Return True if the directory looks like output from `verifyPlugin`."""
    return any(reports_dir.rglob("verification-verdict.txt"))


def collect_usages(reports_dir: Path, file_name: str) -> dict[str, list[Path]]:
    """Group verifier finding lines by their exact verifier message."""
    findings: dict[str, list[Path]] = defaultdict(list)
    for path in sorted(reports_dir.rglob(file_name)):
        for line in nonblank_lines(path):
            findings[line].append(path)
    return findings


def print_findings(
    findings: dict[str, list[Path]],
    *,
    heading: str,
    guidance: str,
) -> None:
    """Print a concise, de-duplicated verifier report."""
    print(heading, file=sys.stderr)
    print(guidance, file=sys.stderr)
    print(file=sys.stderr)

    for index, (message, paths) in enumerate(sorted(findings.items()), start=1):
        first_path = paths[0]
        print(f"{index}. {message}", file=sys.stderr)
        print(
            f"   seen in {len(paths)} verifier target(s); first: {relative(first_path)}",
            file=sys.stderr,
        )


def print_internal_failure(findings: dict[str, list[Path]]) -> None:
    """Print the hard-failure report for internal API findings."""
    print_findings(
        findings,
        heading="ERROR: IntelliJ Plugin Verifier reported internal API usage.",
        guidance=(
            "Replace @ApiStatus.Internal / @IntellijInternalApi usages before "
            "merge or release."
        ),
    )


def print_advisory(
    *,
    deprecated: dict[str, list[Path]],
    experimental: dict[str, list[Path]],
) -> None:
    """Print non-fatal deprecated and experimental API debt."""
    if deprecated:
        print_findings(
            deprecated,
            heading=(
                "WARNING: IntelliJ Plugin Verifier reported deprecated or "
                "scheduled-for-removal API usage."
            ),
            guidance=(
                "Minimize before merge or release when a stable public replacement "
                "is available."
            ),
        )
        print(file=sys.stderr)

    if experimental:
        print_findings(
            experimental,
            heading="WARNING: IntelliJ Plugin Verifier reported experimental API usage.",
            guidance=(
                "Keep experimental API usage narrowly isolated and justified; "
                "prefer stable public API when one exists."
            ),
        )
        print(file=sys.stderr)


def main() -> int:
    """CLI entry point."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--reports-dir",
        type=Path,
        default=DEFAULT_REPORTS_DIR,
        help="Plugin Verifier reports directory.",
    )
    args = parser.parse_args()

    reports_dir = args.reports_dir.resolve()
    if not reports_dir.is_dir():
        print(
            f"ERROR: Plugin Verifier reports directory not found: {reports_dir}",
            file=sys.stderr,
        )
        print("Run ./gradlew verifyPlugin before this gate.", file=sys.stderr)
        return 1

    if not verifier_targets_exist(reports_dir):
        print(
            f"ERROR: No verification-verdict.txt files found under {relative(reports_dir)}.",
            file=sys.stderr,
        )
        print("Run ./gradlew verifyPlugin before this gate.", file=sys.stderr)
        return 1

    if findings := collect_usages(reports_dir, "internal-api-usages.txt"):
        print_internal_failure(findings)
        return 1

    deprecated = collect_usages(reports_dir, "deprecated-usages.txt")
    experimental = collect_usages(reports_dir, "experimental-api-usages.txt")
    print_advisory(deprecated=deprecated, experimental=experimental)

    print(
        "Plugin Verifier API gate passed: "
        f"internal=0, deprecated={len(deprecated)}, experimental={len(experimental)}; "
        f"reports={relative(reports_dir)}",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
