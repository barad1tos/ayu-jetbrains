#!/usr/bin/env bash
# Blocks commits that modify linter/coverage config files.
# Bypass with: git commit --no-verify
echo "Linter config modification blocked: $*"
echo "If intentional, use: git commit --no-verify"
exit 1
