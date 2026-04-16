#!/usr/bin/env python3
"""Verify every plugin.xml-referenced class has a ProGuard keep rule.

Without a keep rule, ProGuard obfuscates the class name in the shipped JAR
and IntelliJ's platform can no longer instantiate it by FQN from plugin.xml —
the user sees a silent "class not found" at plugin load. This runs BEFORE
shipping to catch the gap.

CLI:
  scripts/verify-proguard-keeps.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from _plugin_xml import REPO_ROOT, extract_plugin_classes

PROGUARD = REPO_ROOT / "proguard-rules.pro"

# Matches `-keep class X.Y.Z { ... }` and `-keep interface X.Y.Z { ... }`.
# The captured name may end in `.**` for wildcard package coverage.
_KEEP_RE = re.compile(
    r"^\s*-keep\s+(?:class|interface)\s+(\S+)",
    re.MULTILINE,
)


def extract_keep_rules(path: Path = PROGUARD) -> tuple[set[str], list[str]]:
    """Return (exact_class_names, wildcard_prefixes).

    Wildcard prefixes include the trailing dot — a class matches when its
    FQN starts with the prefix (e.g., prefix `dev.ayuislands.accent.elements.`
    covers `dev.ayuislands.accent.elements.InlayHintsElement`).
    """
    text = path.read_text(encoding="utf-8")
    matches = _KEEP_RE.findall(text)
    exact: set[str] = set()
    wildcards: list[str] = []
    for name in matches:
        if name.endswith(".**"):
            wildcards.append(name[:-2])  # Keep trailing dot for prefix match
        else:
            exact.add(name)
    return exact, wildcards


def is_covered(fqn: str, exact: set[str], wildcards: list[str]) -> bool:
    if fqn in exact:
        return True
    return any(fqn.startswith(prefix) for prefix in wildcards)


def main() -> int:
    classes = extract_plugin_classes()
    if not classes:
        print("ERROR: No dev.ayuislands.* classes found in plugin.xml", file=sys.stderr)
        return 1

    exact, wildcards = extract_keep_rules()

    missing: list[str] = []
    for cls in classes:
        if is_covered(cls, exact, wildcards):
            print(f"  PASS  {cls}")
        else:
            print(f"  FAIL  {cls} (no keep rule)")
            missing.append(cls)

    print()
    print(
        f"Results: {len(classes) - len(missing)}/{len(classes)} classes "
        f"covered by keep rules"
    )
    if missing:
        print()
        print("MISSING KEEP RULES:")
        for cls in missing:
            print(f"  - {cls}")
        return 1
    print("All plugin.xml class references have ProGuard keep rules.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
