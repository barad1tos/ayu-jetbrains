# Ayu Islands maintenance scripts

All Python scripts run from the repo root. Dependencies are declared in
`pyproject.toml` and pinned in `uv.lock` — run `uv sync` inside `scripts/`
once to set up the local environment.

## Scripts

All `verify-*` scripts exit `0` on success, `1` on failure, and print a
per-item PASS/FAIL summary. They're wired into CI (`.github/workflows/build.yml`)
and where appropriate into `.pre-commit-config.yaml`.

### `verify-docs.py` — docs drift detector

Enforces three invariants between `docs/features.yml`, `README.md`,
`src/main/resources/META-INF/plugin.xml`, and `CHANGELOG.md`:

1. **Keyword coverage** — every feature's keyword appears in both
   `README.md` and the `<description>` CDATA of `plugin.xml`.
2. **Changelog cross-ref** — every `[Paid]`/`[Free]` bullet in the latest
   `CHANGELOG.md` version section has a feature with matching `introduced`.
3. **Screenshot freshness** — for every feature with a `screenshot` block,
   the declared Kotlin `sources` are unchanged since `last_verified_sha`
   and the file's SHA-256 matches `content_sha256`.

```bash
scripts/verify-docs.py                 # lint (used by CI + pre-commit)
scripts/verify-docs.py --update-hashes # recompute content_sha256 after re-capture
```

Deps: `pyyaml`. Invoked via uv shebang.

### `verify-theme-xml.py` — XML scheme cross-variant consistency

Parses `AyuIslandsMirage.xml`, `AyuIslandsDark.xml`, `AyuIslandsLight.xml`
and asserts identical attribute name sets, `FONT_TYPE` values, and
`baseAttributes` values. Catches the #1 theme-pack regression class:
one variant edited, the others forgotten.

```bash
scripts/verify-theme-xml.py
```

Deps: `defusedxml` (hardened XML parser — safer than stdlib `xml.etree`).
Invoked via uv shebang.

### `verify-theme-json.py` — JSON base/islands pair consistency

Every color variant ships two `.theme.json` files (base + islands). Their
`colors` and `icons.ColorPalette` blocks must be identical — this script
diffs each pair and fails on divergence.

```bash
python3 scripts/verify-theme-json.py
```

Deps: stdlib only (`json`). Plain `python3` invocation.

### `verify-bytecode.py` — JAR integrity

After `./gradlew buildPlugin`, verifies every `dev.ayuislands.*` class
referenced in `plugin.xml` exists in the **composed** JAR (the one that
ships). Excludes `-base.jar` / `-instrumented.jar` — those still contain
un-processed classes, so a pass against them would mask a ProGuard strip
in the shipping artifact.

```bash
python3 scripts/verify-bytecode.py                     # auto-detect composed JAR
python3 scripts/verify-bytecode.py path/to/plugin.jar  # explicit path
```

Deps: stdlib only (`subprocess` shells out to `javap`). Plain `python3`.

### `verify-proguard-keeps.py` — ProGuard keep-rule coverage

Every class referenced in `plugin.xml` must have a `-keep class X { *; }`
(or matching `-keep class X.** { *; }` wildcard) in `proguard-rules.pro`,
otherwise ProGuard obfuscates the name and IntelliJ can't instantiate it.

```bash
python3 scripts/verify-proguard-keeps.py
```

Deps: stdlib only. Plain `python3`.

### `guard-linter-configs.sh` — pre-commit gate

5-line bash hook that blocks commits touching `detekt.yml`, `.editorconfig`,
or `codecov.yml` unless the user explicitly passes `--no-verify`. Wired
into `.pre-commit-config.yaml`.

## Shared helpers

### `_plugin_xml.py`

Single source for "extract dev.ayuislands.* classes referenced from
plugin.xml". Used by `verify-bytecode.py` and `verify-proguard-keeps.py`
— if a future plugin.xml attribute becomes an FQN holder, update this
module only.
