#!/usr/bin/env bash
# Verify that all plugin.xml-referenced classes have ProGuard keep rules.
# Usage: bash scripts/verify-proguard-keeps.sh
set -euo pipefail

PLUGIN_XML="src/main/resources/META-INF/plugin.xml"
PROGUARD="proguard-rules.pro"

echo "Parsing class references from $PLUGIN_XML ..."

# Extract all dev.ayuislands.* class FQNs from plugin.xml attributes.
CLASSES=$(grep -oE '(implementation|instance|serviceImplementation|class)="[^"]+"' "$PLUGIN_XML" \
    | sed 's/.*="//' | sed 's/"$//' \
    | grep '^dev\.ayuislands\.' \
    | sort -u)

if [[ -z "$CLASSES" ]]; then
    echo "ERROR: No dev.ayuislands.* classes found in plugin.xml"
    exit 1
fi

echo "Parsing keep rules from $PROGUARD ..."

# Extract class names from -keep class/interface directives
KEEP_RULES=$(grep -oE '^\s*-keep\s+(class|interface)\s+[^ {]+' "$PROGUARD" \
    | sed -E 's/.*-keep[[:space:]]+(class|interface)[[:space:]]+//' \
    | sort -u)

# Separate exact matches from wildcard patterns
KEEP_WILDCARDS=$(echo "$KEEP_RULES" | grep '\.\*\*$' || true)
KEEP_EXACT=$(echo "$KEEP_RULES" | grep -v '\.\*\*$' || true)

TOTAL=0
MISSING=0
MISSING_LIST=""

while IFS= read -r CLASS; do
    TOTAL=$((TOTAL + 1))
    FOUND=false

    # Check exact match
    if echo "$KEEP_EXACT" | grep -qxF "$CLASS"; then
        FOUND=true
    fi

    # Check wildcard match (package.** covers package.sub.ClassName)
    if [[ "$FOUND" == "false" ]]; then
        while IFS= read -r PATTERN; do
            [[ -z "$PATTERN" ]] && continue
            # Convert "dev.ayuislands.accent.elements.**" to prefix "dev.ayuislands.accent.elements."
            PREFIX="${PATTERN%%\*\*}"
            if [[ "$CLASS" == "$PREFIX"* ]]; then
                FOUND=true
                break
            fi
        done <<< "$KEEP_WILDCARDS"
    fi

    if [[ "$FOUND" == "true" ]]; then
        echo "  PASS  $CLASS"
    else
        echo "  FAIL  $CLASS (no keep rule)"
        MISSING=$((MISSING + 1))
        MISSING_LIST="$MISSING_LIST  - $CLASS"$'\n'
    fi
done <<< "$CLASSES"

echo ""
echo "Results: $((TOTAL - MISSING))/$TOTAL classes covered by keep rules"

if [[ "$MISSING" -gt 0 ]]; then
    echo ""
    echo "MISSING KEEP RULES:"
    echo "$MISSING_LIST"
    exit 1
fi

echo "All plugin.xml class references have ProGuard keep rules."
