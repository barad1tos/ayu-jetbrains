#!/bin/bash
# Cross-variant consistency checker for Ayu Islands editor scheme XML files.
# Validates that all three variants (Mirage, Dark, Light) have identical
# attribute name sets, identical FONT_TYPE values, and identical baseAttributes.
#
# Usage: bash scripts/check-consistency.sh
# Exit code 0 = all checks pass, 1 = mismatches found

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIRAGE="$REPO_ROOT/src/main/resources/themes/AyuIslandsMirage.xml"
DARK="$REPO_ROOT/src/main/resources/themes/AyuIslandsDark.xml"
LIGHT="$REPO_ROOT/src/main/resources/themes/AyuIslandsLight.xml"

ERRORS=0

# Create temp files and clean up on exit
MIRAGE_NAMES_FILE=$(mktemp)
DARK_NAMES_FILE=$(mktemp)
LIGHT_NAMES_FILE=$(mktemp)
MIRAGE_FONT_FILE=$(mktemp)
DARK_FONT_FILE=$(mktemp)
LIGHT_FONT_FILE=$(mktemp)
MIRAGE_BASE_FILE=$(mktemp)
DARK_BASE_FILE=$(mktemp)
LIGHT_BASE_FILE=$(mktemp)
trap 'rm -f "$MIRAGE_NAMES_FILE" "$DARK_NAMES_FILE" "$LIGHT_NAMES_FILE" "$MIRAGE_FONT_FILE" "$DARK_FONT_FILE" "$LIGHT_FONT_FILE" "$MIRAGE_BASE_FILE" "$DARK_BASE_FILE" "$LIGHT_BASE_FILE"' EXIT

# Section: Helper Functions

# Extract top-level attribute names from the <attributes> section only.
# Top-level entries are indented exactly 8 spaces; nested value options are indented more.
extract_attr_names() {
    sed -n '/<attributes>/,/<\/attributes>/p' "$1" \
        | grep -E '^        <option name="' \
        | sed -E 's/.*name="([^"]+)".*/\1/' \
        | sort -u
}

# Bulk-extract FONT_TYPE values for all attributes in one pass.
# Uses awk to track which top-level attribute we're inside, then captures FONT_TYPE.
# Output: "ATTR_NAME<tab>FONT_TYPE_VALUE" per line
extract_all_font_types() {
    awk '
    /<attributes>/ { in_attrs=1; next }
    /<\/attributes>/ { in_attrs=0; next }
    in_attrs && /^        <option name=/ {
        # Extract attribute name with sed-like approach
        gsub(/.*name="/, "", $0)
        split($0, parts, "\"")
        current_attr = parts[1]
        # Reset for next attribute
        if ($0 ~ /\/>/) { current_attr = ""; }
        next
    }
    in_attrs && current_attr != "" && /FONT_TYPE/ {
        gsub(/.*value="/, "", $0)
        split($0, parts, "\"")
        print current_attr "\t" parts[1]
        current_attr = ""
        next
    }
    in_attrs && /^        <\/option>/ {
        current_attr = ""
    }
    ' "$1" | sort -t'	' -k1,1
}

# Bulk-extract baseAttributes values for all attributes in one pass.
# Output: "ATTR_NAME<tab>BASE_ATTR_VALUE" per line
extract_all_base_attrs() {
    sed -n '/<attributes>/,/<\/attributes>/p' "$1" \
        | grep -E '^        <option name="[^"]*" baseAttributes="' \
        | sed -E 's/.*name="([^"]+)" baseAttributes="([^"]+)".*/\1\t\2/' \
        | sort -t'	' -k1,1
}

echo "=== Ayu Islands Cross-Variant Consistency Check ==="
echo ""

# Section: Check 1 - Attribute Name Sets

echo "--- Check 1: Attribute Name Sets ---"

extract_attr_names "$MIRAGE" > "$MIRAGE_NAMES_FILE"
extract_attr_names "$DARK" > "$DARK_NAMES_FILE"
extract_attr_names "$LIGHT" > "$LIGHT_NAMES_FILE"

MIRAGE_COUNT=$(wc -l < "$MIRAGE_NAMES_FILE" | tr -d ' ')
DARK_COUNT=$(wc -l < "$DARK_NAMES_FILE" | tr -d ' ')
LIGHT_COUNT=$(wc -l < "$LIGHT_NAMES_FILE" | tr -d ' ')

echo "Mirage: $MIRAGE_COUNT attributes"
echo "Dark:   $DARK_COUNT attributes"
echo "Light:  $LIGHT_COUNT attributes"

MIRAGE_VS_DARK=$(diff "$MIRAGE_NAMES_FILE" "$DARK_NAMES_FILE" || true)
MIRAGE_VS_LIGHT=$(diff "$MIRAGE_NAMES_FILE" "$LIGHT_NAMES_FILE" || true)

if [[ -n "$MIRAGE_VS_DARK" ]]; then
    echo ""
    echo "MISMATCH: Mirage vs Dark"
    echo "$MIRAGE_VS_DARK"
    ERRORS=$((ERRORS + 1))
else
    echo "Mirage vs Dark: OK"
fi

if [[ -n "$MIRAGE_VS_LIGHT" ]]; then
    echo ""
    echo "MISMATCH: Mirage vs Light"
    echo "$MIRAGE_VS_LIGHT"
    ERRORS=$((ERRORS + 1))
else
    echo "Mirage vs Light: OK"
fi
echo ""

# Section: Check 2 - FONT_TYPE Consistency

echo "--- Check 2: FONT_TYPE Consistency ---"

extract_all_font_types "$MIRAGE" > "$MIRAGE_FONT_FILE"
extract_all_font_types "$DARK" > "$DARK_FONT_FILE"
extract_all_font_types "$LIGHT" > "$LIGHT_FONT_FILE"

FONT_DIFF_MD=$(diff "$MIRAGE_FONT_FILE" "$DARK_FONT_FILE" || true)
FONT_DIFF_ML=$(diff "$MIRAGE_FONT_FILE" "$LIGHT_FONT_FILE" || true)

FONT_CHECKED=$(wc -l < "$MIRAGE_FONT_FILE" | tr -d ' ')

if [[ -n "$FONT_DIFF_MD" || -n "$FONT_DIFF_ML" ]]; then
    # Detail the mismatches
    FONT_MISMATCHES=0
    ALL_FONT_ATTRS=$(cut -f1 "$MIRAGE_FONT_FILE" "$DARK_FONT_FILE" "$LIGHT_FONT_FILE" | sort -u)

    while IFS= read -r attr; do
        [[ -z "$attr" ]] && continue
        m_font=$(grep -F "${attr}	" "$MIRAGE_FONT_FILE" | cut -f2 || true)
        d_font=$(grep -F "${attr}	" "$DARK_FONT_FILE" | cut -f2 || true)
        l_font=$(grep -F "${attr}	" "$LIGHT_FONT_FILE" | cut -f2 || true)

        if ! [[ "$m_font" == "$d_font" && "$m_font" == "$l_font" ]]; then
            echo "FONT_TYPE mismatch: $attr (Mirage=${m_font:-none} Dark=${d_font:-none} Light=${l_font:-none})"
            FONT_MISMATCHES=$((FONT_MISMATCHES + 1))
            ERRORS=$((ERRORS + 1))
        fi
    done <<< "$ALL_FONT_ATTRS"

    echo "Checked $FONT_CHECKED attributes for FONT_TYPE consistency"
    echo "FONT_TYPE mismatches found: $FONT_MISMATCHES"
else
    FONT_MISMATCHES=0
    echo "Checked $FONT_CHECKED attributes for FONT_TYPE consistency"
    echo "All FONT_TYPE values consistent: OK"
fi
echo ""

# Section: Check 3 - baseAttributes Consistency

echo "--- Check 3: baseAttributes Consistency ---"

extract_all_base_attrs "$MIRAGE" > "$MIRAGE_BASE_FILE"
extract_all_base_attrs "$DARK" > "$DARK_BASE_FILE"
extract_all_base_attrs "$LIGHT" > "$LIGHT_BASE_FILE"

BASE_DIFF_MD=$(diff "$MIRAGE_BASE_FILE" "$DARK_BASE_FILE" || true)
BASE_DIFF_ML=$(diff "$MIRAGE_BASE_FILE" "$LIGHT_BASE_FILE" || true)

BASE_CHECKED=$(wc -l < "$MIRAGE_BASE_FILE" | tr -d ' ')

if [[ -n "$BASE_DIFF_MD" || -n "$BASE_DIFF_ML" ]]; then
    BASE_MISMATCHES=0
    ALL_BASE_ATTRS=$(cut -f1 "$MIRAGE_BASE_FILE" "$DARK_BASE_FILE" "$LIGHT_BASE_FILE" | sort -u)

    while IFS= read -r attr; do
        [[ -z "$attr" ]] && continue
        m_base=$(grep -F "${attr}	" "$MIRAGE_BASE_FILE" | cut -f2 || true)
        d_base=$(grep -F "${attr}	" "$DARK_BASE_FILE" | cut -f2 || true)
        l_base=$(grep -F "${attr}	" "$LIGHT_BASE_FILE" | cut -f2 || true)

        if ! [[ "$m_base" == "$d_base" && "$m_base" == "$l_base" ]]; then
            echo "baseAttributes mismatch: $attr (Mirage=${m_base:-none} Dark=${d_base:-none} Light=${l_base:-none})"
            BASE_MISMATCHES=$((BASE_MISMATCHES + 1))
            ERRORS=$((ERRORS + 1))
        fi
    done <<< "$ALL_BASE_ATTRS"

    echo "Checked $BASE_CHECKED attributes for baseAttributes consistency"
    echo "baseAttributes mismatches found: $BASE_MISMATCHES"
else
    BASE_MISMATCHES=0
    echo "Checked $BASE_CHECKED attributes for baseAttributes consistency"
    echo "All baseAttributes values consistent: OK"
fi
echo ""

# Section: Summary

echo "=== Summary ==="
echo "Attribute counts: Mirage=$MIRAGE_COUNT Dark=$DARK_COUNT Light=$LIGHT_COUNT"
echo "FONT_TYPE mismatches: ${FONT_MISMATCHES:-0}"
echo "baseAttributes mismatches: ${BASE_MISMATCHES:-0}"

if [[ $ERRORS -eq 0 ]]; then
    echo ""
    echo "RESULT: PASS -- all variants are consistent"
    exit 0
else
    echo ""
    echo "RESULT: FAIL -- $ERRORS issue(s) found"
    exit 1
fi
