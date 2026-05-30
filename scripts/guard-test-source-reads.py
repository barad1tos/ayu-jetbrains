#!/usr/bin/env python3
"""Guard tests against production source-grep assertions.

The guard tracks production Kotlin/Java source path literals in tests. Existing
documented compromises are grandfathered with exact counts, so adding another
source-read in an already-grandfathered test still fails until it is reviewed.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = REPO_ROOT / "src/test/kotlin"

STRING_LITERAL_RE = re.compile(r'"(?:\\.|[^"\\\n])*"')
PATH_FACTORY_RE = re.compile(
    r"(?<![\w$])(?:Path\.of|Paths\.get|Path|(?:java\.io\.)?File)\s*\(",
)


@dataclass(frozen=True)
class Allowance:
    count: int
    reason: str


ALLOWLIST: dict[tuple[str, str], Allowance] = {
    (
        "src/test/kotlin/dev/ayuislands/settings/mappings/ProjectAccentSwapServiceHexGateShapeTest.kt",
        "src/main/kotlin/dev/ayuislands/settings/mappings/ProjectAccentSwapService.kt",
    ): Allowance(1, "documented hex-gate shape compromise"),
    (
        "src/test/kotlin/dev/ayuislands/accent/AccentApplicatorRevertAllSymmetryTest.kt",
        "src/main/kotlin/dev/ayuislands/accent/AccentApplicator.kt",
    ): Allowance(1, "documented apply-revert symmetry compromise"),
    (
        "src/test/kotlin/dev/ayuislands/settings/AyuIslandsChromePanelTest.kt",
        "src/main/kotlin/dev/ayuislands/settings/AyuIslandsChromePanel.kt",
    ): Allowance(2, "documented chrome panel static regression compromise"),
    (
        "src/test/kotlin/dev/ayuislands/settings/AyuIslandsChromePanelTest.kt",
        "dev/ayuislands/settings/AyuIslandsChromePanel.kt",
    ): Allowance(1, "documented chrome panel classpath-source fallback"),
    (
        "src/test/kotlin/dev/ayuislands/accent/LanguageDetectionRulesTest.kt",
        "/repo/src/main/kotlin/Foo.kt",
    ): Allowance(1, "path-classification fixture, not a file read"),
    (
        "src/test/kotlin/dev/ayuislands/accent/AccentApplicatorTest.kt",
        "src/main/kotlin/dev/ayuislands/accent/AccentApplicator.kt",
    ): Allowance(1, "documented RequiresEdt annotation compromise"),
    (
        "src/test/kotlin/dev/ayuislands/AyuIslandsStartupActivityTest.kt",
        "src/main/kotlin/dev/ayuislands/AyuIslandsStartupActivity.kt",
    ): Allowance(1, "documented startup wiring compromise"),
    (
        "src/test/kotlin/dev/ayuislands/accent/AccentApplicatorBannedApiGuardTest.kt",
        "src/main/kotlin/dev/ayuislands/accent/AccentApplicator.kt",
    ): Allowance(1, "banned platform API guard"),
    (
        "src/test/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanelTest.kt",
        "src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt",
    ): Allowance(1, "documented syntax panel static compromise"),
    (
        "src/test/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherPremiumBlockGateTest.kt",
        "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherPopup.kt",
    ): Allowance(1, "documented premium copy guard"),
    (
        "src/test/kotlin/dev/ayuislands/accent/elements/ChromeLiveRefreshSymmetryTest.kt",
        "src/main/kotlin/dev/ayuislands/accent/elements/$elementName.kt",
    ): Allowance(1, "documented chrome live-refresh symmetry compromise"),
    (
        "src/test/kotlin/dev/ayuislands/accent/AccentApplicatorFocusedProjectTest.kt",
        "src/main/kotlin/dev/ayuislands/accent/AccentApplicator.kt",
    ): Allowance(1, "documented focused-project publish-gate compromise"),
    (
        "src/test/kotlin/dev/ayuislands/accent/Gap4BannedApiGuardTest.kt",
        "src/main/kotlin/dev/ayuislands/accent",
    ): Allowance(1, "banned platform API guard directory"),
    (
        "src/test/kotlin/dev/ayuislands/accent/LiveChromeRefresherTest.kt",
        "src/main/kotlin/dev/ayuislands/accent/LiveChromeRefresher.kt",
    ): Allowance(1, "documented chrome refresh compromise"),
    (
        "src/test/kotlin/dev/ayuislands/glow/GlowFallbackBannedApiGuardTest.kt",
        "src/main/kotlin/dev/ayuislands/glow/GlowOverlayManager.kt",
    ): Allowance(1, "banned platform API guard"),
    (
        "src/test/kotlin/dev/ayuislands/glow/GlowLifecycleGuardTest.kt",
        "src/main/kotlin/dev/ayuislands/glow/GlowOverlayManager.kt",
    ): Allowance(1, "banned platform API guard"),
    (
        "src/test/kotlin/dev/ayuislands/licensing/LicenseCheckerQuickSwitcherRevertTest.kt",
        "src/main/kotlin/dev/ayuislands/licensing/LicenseChecker.kt",
    ): Allowance(1, "documented quick-switcher revert-order compromise"),
}


def main() -> int:
    occurrences = find_source_path_literals()
    errors = []

    for key, count in sorted(occurrences.items()):
        allowance = ALLOWLIST.get(key)
        file_path, literal = key
        if allowance is None:
            errors.append(
                f"{file_path}: unexpected production source path literal {literal!r}",
            )
        elif count != allowance.count:
            errors.append(
                f"{file_path}: expected {allowance.count} occurrence(s) of {literal!r} "
                f"({allowance.reason}), found {count}",
            )

    for key, allowance in sorted(ALLOWLIST.items()):
        count = occurrences.get(key, 0)
        if count == 0:
            file_path, literal = key
            errors.append(
                f"{file_path}: allowlisted source path {literal!r} is gone; "
                f"remove its allowlist entry ({allowance.reason})",
            )

    if errors:
        print("Production source reads in tests must be reviewed explicitly.", file=sys.stderr)
        print("Tests assert on observable behavior, not source-file text.", file=sys.stderr)
        for error in errors:
            print(f"  [ERROR] {error}", file=sys.stderr)
        return 1

    print("test source-read guard passed")
    return 0


def find_source_path_literals() -> Counter[tuple[str, str]]:
    occurrences: Counter[tuple[str, str]] = Counter()
    for file_path in sorted(TEST_ROOT.rglob("*.kt")):
        relative_file = file_path.relative_to(REPO_ROOT).as_posix()
        text = file_path.read_text()
        for literal in string_literals(text):
            if is_production_source_path(literal):
                occurrences[(relative_file, literal)] += 1
        for literal in split_path_literals(text):
            if is_production_source_path(literal):
                occurrences[(relative_file, literal)] += 1
    return occurrences


def string_literals(text: str) -> list[str]:
    return [
        match.group()[1:-1]
        for match in STRING_LITERAL_RE.finditer(text)
    ]


def split_path_literals(text: str) -> list[str]:
    paths = []
    for match in PATH_FACTORY_RE.finditer(text):
        closing_paren = find_closing_paren(text, match.end() - 1)
        if closing_paren is None:
            continue
        segments = string_literals(text[match.end():closing_paren])
        if len(segments) < 2:
            continue
        if any(is_production_source_path(segment) for segment in segments):
            continue
        path = joined_production_source_path(segments)
        if path is not None:
            paths.append(path)
    return paths


def joined_production_source_path(segments: list[str]) -> str | None:
    joined = "/".join(segment.strip("/") for segment in segments if segment)
    for source_root in ("src/main/kotlin", "src/main/java"):
        index = joined.find(source_root)
        if index >= 0:
            return joined[index:]
    return joined if re.fullmatch(r"(?:dev|com)/[\w/$.-]+\.kt", joined) else None


def find_closing_paren(
    text: str,
    open_paren: int,
) -> int | None:
    depth = 0
    in_string = False
    escaped = False
    for index in range(open_paren, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index
    return None


def is_production_source_path(literal: str) -> bool:
    return (
        "src/main/kotlin" in literal
        or "src/main/java" in literal
        or bool(re.fullmatch(r"(?:dev|com)/[\w/$.-]+\.kt", literal))
    )


if __name__ == "__main__":
    sys.exit(main())
