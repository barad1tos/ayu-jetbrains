#!/usr/bin/env bash
# Verify that all plugin.xml-referenced classes exist in the built JAR.
# Usage: bash scripts/verify-bytecode.sh [path/to/plugin.jar]
set -euo pipefail

PLUGIN_XML="src/main/resources/META-INF/plugin.xml"

# Resolve JAR path: argument or auto-detect from build output
if [[ -n "${1:-}" ]]; then
    JAR="$1"
else
    JAR=$(find build/libs -name 'ayu-jetbrains-*.jar' -not -name '*-sources*' 2>/dev/null | head -1)
    if [[ -z "$JAR" ]]; then
        echo "ERROR: No JAR found in build/libs/. Run ./gradlew buildPlugin first."
        exit 1
    fi
fi

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: JAR not found: $JAR"
    exit 1
fi

echo "JAR: $JAR"
echo "Parsing class references from $PLUGIN_XML ..."

# Extract all dev.ayuislands.* class FQNs from plugin.xml attributes.
# Matches: implementation="...", instance="...", serviceImplementation="...", class="..."
CLASSES=$(grep -oE '(implementation|instance|serviceImplementation|class)="[^"]+"' "$PLUGIN_XML" \
    | sed 's/.*="//' | sed 's/"$//' \
    | grep '^dev\.ayuislands\.' \
    | sort -u)

if [[ -z "$CLASSES" ]]; then
    echo "ERROR: No dev.ayuislands.* classes found in plugin.xml"
    exit 1
fi

TOTAL=0
MISSING=0
MISSING_LIST=""

while IFS= read -r CLASS; do
    TOTAL=$((TOTAL + 1))
    if javap -public -cp "$JAR" "$CLASS" > /dev/null 2>&1; then
        echo "  PASS  $CLASS"
    else
        echo "  FAIL  $CLASS"
        MISSING=$((MISSING + 1))
        MISSING_LIST="$MISSING_LIST  - $CLASS"$'\n'
    fi
done <<< "$CLASSES"

echo ""
echo "Results: $((TOTAL - MISSING))/$TOTAL classes found in JAR"

if [[ "$MISSING" -gt 0 ]]; then
    echo ""
    echo "MISSING CLASSES:"
    echo "$MISSING_LIST"
    exit 1
fi

echo "All plugin.xml class references verified."
