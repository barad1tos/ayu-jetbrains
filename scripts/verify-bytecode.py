#!/usr/bin/env python3
"""Verify that every plugin.xml-referenced class is present in the shipped JAR.

Runs AFTER `./gradlew buildPlugin` in the CI JAR integrity job. Checks the
COMPOSED JAR (not `-base.jar` or `-instrumented.jar`) because that's what
actually ships to the Marketplace — if ProGuard strips a class from the
composed JAR while leaving it in `-base.jar`, the dist ZIP ships broken
while the raw-source jar still reports pass.

CLI:
  scripts/verify-bytecode.py                     # auto-detect composed JAR
  scripts/verify-bytecode.py path/to/plugin.jar  # explicit JAR path
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

from _plugin_xml import REPO_ROOT, extract_plugin_classes

BUILD_LIBS = REPO_ROOT / "build" / "libs"
_EXCLUDED_SUFFIXES = ("-base", "-instrumented", "-sources")


def find_composed_jar() -> Path | None:
    """Return the composed JAR path, excluding -base / -instrumented / -sources.

    Picks the largest matching candidate as a tiebreaker in case Gradle's
    dist-output layout changes — the composed JAR is always the biggest one
    (it bundles obfuscated classes + resources that the -base jar lacks).
    """
    if candidates := [
        p
        for p in BUILD_LIBS.glob("ayu-jetbrains-*.jar")
        if not any(p.stem.endswith(sfx) for sfx in _EXCLUDED_SUFFIXES)
    ]:
        return max(candidates, key=lambda p: p.stat().st_size)
    else:
        return None


def class_in_jar(jar: Path, fqn: str) -> bool:
    result = subprocess.run(
        ["javap", "-public", "-cp", str(jar), fqn],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.returncode == 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("jar", nargs="?", type=Path, help="Optional explicit JAR path")
    args = ap.parse_args()

    jar: Path | None = args.jar
    if jar is None:
        jar = find_composed_jar()
    if jar is None:
        print(
            "ERROR: No composed JAR in build/libs/. "
            "Run ./gradlew buildPlugin first.",
            file=sys.stderr,
        )
        return 1
    if not jar.is_file():
        print(f"ERROR: JAR not found: {jar}", file=sys.stderr)
        return 1
    print(f"JAR: {jar.relative_to(REPO_ROOT)}")

    classes = extract_plugin_classes()
    if not classes:
        print("ERROR: No dev.ayuislands.* classes found in plugin.xml", file=sys.stderr)
        return 1

    missing: list[str] = []
    for cls in classes:
        if class_in_jar(jar, cls):
            print(f"  PASS  {cls}")
        else:
            print(f"  FAIL  {cls}")
            missing.append(cls)

    print()
    print(f"Results: {len(classes) - len(missing)}/{len(classes)} classes found in JAR")
    if missing:
        print()
        print("MISSING CLASSES:")
        for cls in missing:
            print(f"  - {cls}")
        return 1
    print("All plugin.xml class references verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
