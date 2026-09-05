#!/usr/bin/env -S uv run --script --project scripts
"""Verify that docs/features.yml stays in sync with README + plugin.xml + CHANGELOG.

Thin entry point. All logic lives in the `verify_docs` package:

  paths          — REPO_ROOT + file path constants
  report         — Finding / Report
  features       — features.yml load + flatten-across-categories iterator
  plugin_xml     — <description> CDATA extractor
  git_utils      — merge-base / rev-parse / changed-since / SHA-256
  keywords       — Invariant 1 (README + plugin.xml keyword cross-ref)
  changelog      — Invariants 2 + 7 (changelog ↔ features ↔ release bump)
  screenshots    — Invariant 3 (source freshness + byte-hash)
  stamps         — --update-hashes / --restamp mutators
  required_links — Invariant 4 (required substring appears in target file)
  marketplace    — Invariant 6 (<description> matches last-published SHA)
  inventory      — Invariant 5 (asset_inventory ↔ filesystem ↔ referenced_by)
  cli            — argparse + orchestration

CLI:
  scripts/verify-docs.py                  # fail on any drift (used by CI)
  scripts/verify-docs.py --update-hashes  # recompute content_sha256 for every
                                          # screenshot and rewrite features.yml
                                          # (use when intentionally re-capturing)
  scripts/verify-docs.py --restamp        # rewrite every orphaned
                                          # last_verified_sha to current HEAD;
                                          # use after a squash-merge lands, or
                                          # when a feature branch inherits an
                                          # orphaned stamp from main before
                                          # editing tracked sources
  scripts/verify-docs.py --restamp FEATURE_ID [FEATURE_ID ...]
                                          # after visual review, attest current
                                          # source bytes for selected screenshots;
                                          # commit sources + metadata together

Selected restamping records sources_sha256 plus last_verified_sha provenance.
The source digest survives commit/rebase identity changes but rejects later file
or source-list changes. Existing entries without it retain Git-history checks.
Image hashes and capture dates are never changed by restamping. If an image was
re-captured, review it and run --update-hashes separately before restamping.
"""

from __future__ import annotations

import sys

from verify_docs.cli import main

if __name__ == "__main__":
    sys.exit(main())
