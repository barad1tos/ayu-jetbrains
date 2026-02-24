# Ayu Islands

Ayu Mirage colors with modern Islands UI for JetBrains IDEs.

## Features

- **Islands Dark UI** — rounded panels, island gaps, compact mode support
- **Ayu Mirage syntax palette** — 15+ language-specific highlights
- **Languages** — HCL/Terraform, Go, Python, JavaScript/TypeScript, Markdown, YAML, JSON
- **VCS integration** — tuned diff gutters, file status colors, terminal palette
- **Project gradients** — 9 color groups in Ayu Mirage hues

## Install

**From JetBrains Marketplace:**

Settings → Plugins → Marketplace → search "Ayu Islands" → Install

**Manual:**

Download `.zip` from [Releases](https://github.com/barad1tos/ayu-jetbrains/releases) → Settings → Plugins → ⚙ → Install from Disk

## Build

```bash
./gradlew buildPlugin
```

Output: `build/distributions/ayu-jetbrains-<version>.zip`

Test in a sandboxed IDE:
```bash
./gradlew runIde
```

## Roadmap

- [ ] Ayu Dark Islands variant
- [ ] Ayu Light Islands variant
- [ ] Accent color customization *(paid)*
- [ ] Contrast/brightness tuning *(paid)*
- [ ] Font customization *(paid)*
- [ ] Theme presets *(paid)*

## License

[BSL-1.1](LICENSE) — source-available, converts to Apache 2.0 on 2030-02-24.
