# Ayu Islands maintenance scripts

Python scripts managed via [uv](https://docs.astral.sh/uv/). Dependencies
are declared in `pyproject.toml` and pinned in `uv.lock`.

## Setup

```bash
cd scripts
uv sync
```

## Scripts

### `verify-docs.py` — docs drift detector

Enforces three invariants between `docs/features.yml`, `README.md`,
`src/main/resources/META-INF/plugin.xml`, and `CHANGELOG.md`:

1. **Keyword coverage** — every feature's keyword must appear in both
   `README.md` and the `<description>` CDATA of `plugin.xml`.
2. **Changelog cross-ref** — every `[Paid]`/`[Free]` bullet in the latest
   `CHANGELOG.md` version section must correspond to a feature with a
   matching `introduced: X.Y.Z`.
3. **Screenshot freshness** — for every feature with a `screenshot` block,
   the declared Kotlin `sources` must not have changes after
   `last_verified_sha`, and the file's SHA-256 must match `content_sha256`.

Run:

```bash
cd scripts
uv run python verify-docs.py                 # lint (used by CI + pre-commit)
uv run python verify-docs.py --update-hashes # recompute content_sha256 after re-capture
```

When the lint fails on a screenshot: either re-capture the image (then
run `--update-hashes` + bump `last_verified_sha` in `docs/features.yml`)
or, if the UI didn't visually change, just bump `last_verified_sha` to
the current HEAD.

### Shell scripts

`verify-bytecode.sh` / `verify-proguard-keeps.sh` / `guard-linter-configs.sh`
don't need uv — plain bash, run from the repo root.
