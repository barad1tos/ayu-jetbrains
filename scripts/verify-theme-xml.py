#!/usr/bin/env -S uv run --script --project scripts
"""Cross-variant consistency checker for Ayu Islands editor scheme XML files.

Validates that all three variants (Mirage, Dark, Light) have identical:
  1. Attribute name sets
  2. FONT_TYPE values
  3. baseAttributes values

Usage: python3 scripts/verify-theme-xml.py
Exit code 0 = all checks pass, 1 = mismatches found
"""

import sys
from pathlib import Path
from typing import TYPE_CHECKING, Dict, List, Set, Tuple

# The defusedxml package replaces the stdlib xml.etree parser with a hardened
# variant that rejects entity expansion and XML-bomb attacks. Our inputs are
# committed source files (trusted), but using the safe parser signals intent
# and keeps the Semgrep CWE-611 rule satisfied for contributors who might
# later feed external XML into this script.
from defusedxml.ElementTree import parse as parse_xml

if TYPE_CHECKING:
    # Element is a type annotation only. The defusedxml parser returns stdlib
    # Element instances, so importing the type here stays safe — no stdlib
    # parsing code runs at runtime.
    from xml.etree.ElementTree import Element

REPO_ROOT = Path(__file__).resolve().parent.parent
THEMES = REPO_ROOT / "src" / "main" / "resources" / "themes"

VARIANTS: Dict[str, Path] = {
    "Mirage": THEMES / "AyuIslandsMirage.xml",
    "Dark": THEMES / "AyuIslandsDark.xml",
    "Light": THEMES / "AyuIslandsLight.xml",
}


def parse_attributes(path: Path) -> Dict[str, Element]:
    """Return {name: <option> element} for top-level attributes."""
    tree = parse_xml(path)
    attrs_elem = tree.find(".//attributes")
    if attrs_elem is None:
        return {}
    return {
        opt.get("name", ""): opt
        for opt in attrs_elem.findall("option")
        if opt.get("name")
    }


def get_font_types(attrs: Dict[str, Element]) -> Dict[str, str]:
    """Extract FONT_TYPE value per attribute."""
    result: Dict[str, str] = {}
    for name, elem in attrs.items():
        value_elem = elem.find("value")
        if value_elem is None:
            continue
        for opt in value_elem.findall("option"):
            if opt.get("name") == "FONT_TYPE":
                result[name] = opt.get("value", "")
    return result


def get_base_attributes(attrs: Dict[str, Element]) -> Dict[str, str]:
    """Extract baseAttributes value per attribute."""
    return {
        name: elem.get("baseAttributes", "")
        for name, elem in attrs.items()
        if elem.get("baseAttributes")
    }


def format_set_diff(
    ref_name: str,
    other_name: str,
    only_ref: Set[str],
    only_other: Set[str],
) -> List[str]:
    """Format the diff between two attribute name sets as output lines."""
    lines: List[str] = []
    if only_ref:
        lines.append(f"\nOnly in {ref_name} (not {other_name}):")
        lines.extend(f"  {attr}" for attr in sorted(only_ref))
    if only_other:
        lines.append(f"\nOnly in {other_name} (not {ref_name}):")
        lines.extend(f"  {attr}" for attr in sorted(only_other))
    return lines


def compare_sets(data: Dict[str, Set[str]]) -> int:
    """Compare sets across variants, return error count."""
    names = list(data.keys())
    ref_name = names[0]
    ref_set = data[ref_name]
    errors = 0

    for name in names:
        # noinspection PyTypeChecker
        print(f"{name}: {len(data[name])} attributes")

    for name in names[1:]:
        only_ref = ref_set - data[name]
        only_other = data[name] - ref_set
        if not only_ref and not only_other:
            print(f"{ref_name} vs {name}: OK")
            continue
        for line in format_set_diff(ref_name, name, only_ref, only_other):
            print(line)
        errors += 1

    return errors


def compare_values(
    check_name: str,
    data: Dict[str, Dict[str, str]],
) -> Tuple[int, int]:
    """Compare key-value maps across variants, return (checked, mismatches)."""
    all_keys = sorted(set().union(*data.values()))
    names = list(data.keys())
    mismatches = 0

    for key in all_keys:
        values = {variant: data[variant].get(key) for variant in names}
        unique = set(values.values())
        if len(unique) > 1:
            parts = " ".join(
                f"{variant}={values[variant] or 'none'}" for variant in names
            )
            print(f"{check_name} mismatch: {key} ({parts})")
            mismatches += 1

    return len(all_keys), mismatches


def run_check(
    title: str,
    check_name: str,
    data: Dict[str, Dict[str, str]],
) -> int:
    """Run a value-comparison check and print results."""
    print(f"--- {title} ---")
    checked, mismatches = compare_values(check_name, data)
    print(f"Checked {checked} attributes for {check_name} consistency")
    if mismatches == 0:
        print(f"All {check_name} values consistent: OK")
    else:
        print(f"{check_name} mismatches found: {mismatches}")
    print()
    return mismatches


def main() -> int:
    for path in VARIANTS.values():
        if not path.exists():
            print(f"MISSING: {path!s}")
            return 1

    all_attrs = {name: parse_attributes(path) for name, path in VARIANTS.items()}
    errors = 0

    print("=== Ayu Islands Cross-Variant Consistency Check ===")
    print()
    print("--- Check 1: Attribute Name Sets ---")
    attr_names = {name: set(attrs.keys()) for name, attrs in all_attrs.items()}
    errors += compare_sets(attr_names)
    print()

    font_types = {name: get_font_types(attrs) for name, attrs in all_attrs.items()}
    errors += run_check("Check 2: FONT_TYPE Consistency", "FONT_TYPE", font_types)

    base_attrs = {name: get_base_attributes(attrs) for name, attrs in all_attrs.items()}
    errors += run_check(
        "Check 3: baseAttributes Consistency", "baseAttributes", base_attrs
    )

    print("=== Summary ===")
    counts = " ".join(f"{n}={len(a)}" for n, a in all_attrs.items())
    print(f"Attribute counts: {counts}")
    print()

    if errors == 0:
        print("RESULT: PASS -- all variants are consistent")
        return 0

    print(f"RESULT: FAIL -- {errors} issue(s) found")
    return 1


if __name__ == "__main__":
    sys.exit(main())
