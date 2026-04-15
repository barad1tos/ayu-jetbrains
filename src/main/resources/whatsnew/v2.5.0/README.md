# What's New v2.5.0 — Asset Drop

Drop release-showcase screenshots into this directory. The What's New tab loads
PNGs by name from `manifest.json` (sibling file).

## Required files

The current manifest references three screenshots. Add them with these exact
filenames so the slides render images instead of placeholder rectangles:

- `slide-project-overrides.png` — Settings → Ayu Islands → Accent → Overrides
  panel showing one or two project rows, each with a different color swatch.
- `slide-language-overrides.png` — same panel scrolled to the language
  overrides table; show 2–3 language rows (e.g., Kotlin, Python, TypeScript)
  with distinct colors.
- `slide-project-collage.png` — collage of 3 IntelliJ windows side by side,
  each with a clearly different accent color, each captioned with its role:
  **Work** (one color), **Pet project** (another), **Outsource** (a third).
  The point is to show "same IDE, three contexts, three colors, zero effort".
  Tile the windows horizontally in a screenshot, add small text labels under
  each via your image editor (Figma / Preview / whatever). Crop to roughly
  16:9 so it matches the other slide dimensions.

## Image guidelines

- **Format:** PNG. Transparent or matte background both work.
- **Resolution:** ≥ 1600 px wide for crispness on Retina; the panel scales
  proportionally. Avoid taller than ~900 px or the slide pushes the next one
  off-screen on a 13" laptop.
- **Aspect:** roughly 16:9 to 16:10 reads cleanest.

## Adding more slides

Edit `manifest.json` — append a new slide object with `title`, `body`, and
`image`. Drop the matching PNG into this directory. No code changes are needed.

## Iterating in the dev sandbox

Run `./gradlew runIde` after dropping new files; the resource is picked up
fresh on each sandbox launch. To re-trigger the auto-open in sandbox after
already seeing the tab once:

1. Stop the sandbox.
2. Edit `~/Library/Caches/JetBrains/IntelliJIdea<sandbox-version>/sandbox/config/options/ayuIslands.xml`,
   remove the `lastWhatsNewShownVersion` entry (or set it to `2.4.x`).
3. Re-run `./gradlew runIde`.

Or use `Tools → Show What's New…` to reopen on demand without touching state.
