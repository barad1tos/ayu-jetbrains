#!/usr/bin/env bash
# Gradle wrapper with a single signature-gated retry for the transient
# IntelliJ Platform layoutIndex flake: the resolved platform layout
# nondeterministically drops com.intellij.java and compileKotlin fails with
# "Cannot access 'com.intellij.psi...'" on the Groovy annotator.
# Only that signature triggers a retry (after wiping project-local Gradle
# state); every other failure keeps its original exit code untouched.
set -uo pipefail

FLAKE_SIGNATURE="Cannot access 'com.intellij.psi"

LOG_FILE=$(mktemp)
trap 'rm -f "$LOG_FILE"' EXIT

./gradlew "$@" 2>&1 | tee "$LOG_FILE"
EXIT_CODE="${PIPESTATUS[0]}"

if [ "$EXIT_CODE" -eq 0 ]; then
  exit 0
fi

if ! grep -qF "$FLAKE_SIGNATURE" "$LOG_FILE"; then
  exit "$EXIT_CODE"
fi

echo "::warning::layoutIndex flake detected (com.intellij.java dropped from platform layout) — wiping project Gradle state and retrying once"
./gradlew --stop >/dev/null 2>&1 || true
rm -rf build .gradle
# No exec: let the EXIT trap clean up the temp log; the retry output still
# streams straight to stdout and nothing needs to inspect it.
./gradlew "$@"
