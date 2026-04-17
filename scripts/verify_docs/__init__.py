"""Docs drift lint — split into per-concern modules.

Entry point remains `scripts/verify-docs.py`. The public surface of this
package is [cli.main]; every other module is an implementation detail
that the CLI orchestrator imports.

Module map:
  paths          — REPO_ROOT + file path constants
  report         — Finding / Report types
  features       — features.yml load + flatten-across-categories iterator
  plugin_xml     — <description> CDATA extractor
  git_utils      — merge-base / rev-parse / changed-since / SHA-256 helpers
  keywords       — Invariant 1 (README + plugin.xml keyword cross-ref)
  changelog      — Invariant 2 (tier-tagged bullets ↔ introduced version)
  screenshots    — Invariant 3 (source freshness + byte-hash)
  stamps         — --update-hashes / --restamp mutators
  required_links — Invariant 4 (required substring appears in target file)
  marketplace    — Invariant 6 (<description> matches last-published SHA)
  inventory      — Invariant 5 (asset_inventory ↔ filesystem ↔ referenced_by)
  cli            — argparse + orchestration
"""
