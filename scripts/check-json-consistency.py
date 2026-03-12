#!/usr/bin/env python3
"""Per-variant consistency checker for Ayu Islands .theme.json pairs.

Each color variant has a base file (ExperimentalDark/IntelliJ parent) and an
islands file (Islands Dark/ExperimentalLight parent). The "colors" and
"icons.ColorPalette" blocks must be identical within each pair — divergence
means one file was edited and the other forgotten (the project's #1 gotcha).

Usage: python3 scripts/check-json-consistency.py
Exit code 0 = all checks pass, 1 = mismatches found
"""

import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

REPO_ROOT = Path(__file__).resolve().parent.parent
THEMES = REPO_ROOT / "src" / "main" / "resources" / "themes"


@dataclass(frozen=True)
class ThemePair:
    variant: str
    base_file: str
    islands_file: str


@dataclass(frozen=True)
class BlockPath:
    label: str
    keys: List[str]


PAIRS = [
    ThemePair(
        "mirage",
        "ayu-islands-mirage.theme.json",
        "ayu-islands-mirage-islands.theme.json",
    ),
    ThemePair(
        "dark",
        "ayu-islands-dark.theme.json",
        "ayu-islands-dark-islands.theme.json",
    ),
    ThemePair(
        "light",
        "ayu-islands-light.theme.json",
        "ayu-islands-light-islands.theme.json",
    ),
]

BLOCKS_TO_CHECK = [
    BlockPath("colors", ["colors"]),
    BlockPath("icons.ColorPalette", ["icons", "ColorPalette"]),
]


def extract_block(data: Dict[str, Any], keys: List[str]) -> Optional[Dict[str, Any]]:
    """Walk nested keys, return the sub-dict or None."""
    node = data  # type: Any
    for key in keys:
        if isinstance(node, dict) and key in node:
            node = node[key]
        else:
            return None
    return node if isinstance(node, dict) else None


def diff_dicts(base: Dict[str, Any], islands: Dict[str, Any]) -> List[str]:
    """Return formatted lines for each differing key between the two dicts."""
    all_keys = sorted(set(base) | set(islands))
    return [
        f"    {k}: base={base.get(k)!r}  islands={islands.get(k)!r}"
        for k in all_keys
        if base.get(k) != islands.get(k)
    ]


def check_pair(pair: ThemePair) -> int:
    """Check a single base/islands pair, return the number of block mismatches."""
    base_path = THEMES / pair.base_file
    islands_path = THEMES / pair.islands_file

    for path in (base_path, islands_path):
        if not path.exists():
            print(f"MISSING: {path!s}")
            return 1

    with open(base_path) as f:
        base_data = json.load(f)  # type: Dict[str, Any]
    with open(islands_path) as f:
        islands_data = json.load(f)  # type: Dict[str, Any]

    errors = 0

    for block in BLOCKS_TO_CHECK:
        base_block = extract_block(base_data, block.keys)
        islands_block = extract_block(islands_data, block.keys)

        if base_block is None or islands_block is None:
            print(f"MISMATCH in {pair.variant} pair:")
            print(f"  {block.label}: missing in one or both files")
            errors += 1
            continue

        if diffs := diff_dicts(base_block, islands_block):
            print(f"MISMATCH in {pair.variant} pair:")
            print(f"  {block.label}: {len(diffs)} difference(s)")
            for line in diffs:
                print(line)
            errors += 1

    if errors == 0:
        print(f"{pair.variant}: OK")

    return errors


def main() -> int:
    print("=== Ayu Islands JSON Pair Consistency Check ===")
    print()

    errors = sum(check_pair(pair) for pair in PAIRS)

    print()
    if errors == 0:
        print("RESULT: PASS -- all JSON pairs are consistent")
        return 0

    print(f"RESULT: FAIL -- {errors} pair(s) with mismatches")
    return 1


if __name__ == "__main__":
    sys.exit(main())
