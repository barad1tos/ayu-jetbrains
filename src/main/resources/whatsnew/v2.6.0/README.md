# What's New v2.6.0 — Peacock Parity

Image-first whatsnew (the renderer ignores `body` prose; only `title` + image
ship to the user). Each PNG must carry its own caption text, attribution
footer, and feature labels — the title is the only text overlay the panel adds.

## Required files

- `slide-chrome-collage.png` — 3 IntelliJ frames in a row, each with a
  different Ayu accent. Tells the "per-project color" story in one frame.
- `slide-chrome-depth.png` — triptych showing what's native to JetBrains
  (WCAG / per-language pins / live refresh) plus the thank-you footer.

Both at PNG, ≥1600px wide, ~16:9 aspect, ≤500 KB after optimization.

## Slide 1 — `slide-chrome-collage.png`

**Story told visually:** "Same IDE, three contexts, three colors, zero
effort." This is the slide that needs to land in 2 seconds of looking.

| Element                  | Spec                                                                                                                                                                                                                               |
|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Background               | Subtle blurred Ayu Mirage editor bg (`#1F2430`) — full bleed                                                                                                                                                                       |
| Composition              | 3 IntelliJ frames in a horizontal row, very slight 3D perspective tilt (~5°), small overlap so they read as a connected family                                                                                                     |
| Frame 1 (left, gold)     | Project: `backend-api` (rename a real project for the screenshot). Accent: `#FFCC66` (Mirage gold). NavBar / StatusBar / Tab underline / ToolWindow stripe all clearly tinted. Sample editor content visible (Kotlin code is good) |
| Frame 2 (middle, cyan)   | Project: `web-app`. Accent: `#73D0FF` (Mirage cyan). Same chrome surfaces tinted. Sample editor: TypeScript or HTML                                                                                                                |
| Frame 3 (right, coral)   | Project: `terraform-core`. Accent: `#F28779` (Mirage coral). Sample editor: HCL / Terraform                                                                                                                                        |
| Bottom-right footer text | `Inspired by Peacock for VS Code · johnpapa.net` — micro-text, 11–12pt, dim grey (`#828E9F` or similar) so it doesn't compete with chrome but is clearly readable                                                                  |

**Capture flow:**

1. Set Ayu Islands theme = Mirage (Islands UI), tint intensity = default.
2. Open `backend-api` project → Settings → Ayu Islands → Accent → set to
   `#FFCC66`. Take screenshot of a full IDE window.
3. Repeat for `web-app` (`#73D0FF`) and `terraform-core` (`#F28779`).
4. In Figma / Sketch / Preview: arrange the 3 PNGs side-by-side, scale ~33%
   each, slight perspective transform.
5. Add the attribution footer text in the bottom-right.

## Slide 2 — `slide-chrome-depth.png`

**Story told visually:** "Here's what JetBrains let us do that Peacock
couldn't." Three horizontal bands, each showing one differentiator + a
caption. Footer = thank-you to John Papa.

| Band            | Content                                                                                                                                                                                                       | In-band caption                                   |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|
| Top (33%)       | Zoomed NavBar (~3x) with text rendered on saturated lime accent (`#BAE67E`). Bonus: split into "Without WCAG / With WCAG" if room — left half white text barely readable, right half auto-darkened text crisp | `WCAG-aware foreground — readable on any accent`  |
| Middle (33%)    | Settings panel cropped to the per-language pin list. Show 5 rows with distinct colors: Kotlin gold, Python cyan, Swift coral, Rust orange, Terraform lavender. Live preview swatch on the right               | `Per-language pins — the IDE follows your file`   |
| Bottom (33%)    | 4-frame horizontal strip: same project, chrome cycling through 4 different accents. Add subtle motion arrows between frames to imply rotation                                                                 | `Live refresh — rotation + theme sync, no reload` |
| Centered footer | `Thank you, John Papa.   ·   Peacock for VS Code   ·   github.com/johnpapa/vscode-peacock` — bigger than slide 1's footer (~13–14pt), centered                                                                |

**Capture flow:**

1. Top band — pick one project, set accent to `#BAE67E`, screenshot the
   NavBar at high zoom. Optionally render the WCAG comparison in Figma by
   duplicating the NavBar and overlaying white text vs auto-picked text.
2. Middle band — Settings → Ayu Islands → Accent → Overrides → Languages
   tab. Crop to the language pin section.
3. Bottom band — record a screen-grab of accent rotation tick, export 4
   evenly-spaced frames, lay them out in a strip with light arrows.
4. Stack the 3 bands in a single PNG, add the centered footer.

## Image guidelines (both slides)

- **Format:** PNG, no transparency needed.
- **Width:** ≥1600 px (Retina-friendly). Panel scales proportionally.
- **Height:** ≤900 px so the slide doesn't push the next off-screen on 13".
- **Aspect:** 16:9 to 16:10 reads cleanest.
- **Optimize** before commit: `pngcrush` or ImageOptim to ≤500 KB each.

## Footer attribution — non-negotiable

Both slides MUST carry the Peacock attribution footer text, baked into the
PNG. This is what protects the release from plagiarism allegations and keeps
the gratitude impossible to misread.

## Outreach (optional but high-leverage)

Before the v2.6.0 release ships to Marketplace, consider a courtesy DM to
John Papa (Twitter/X: `@john_papa`) — informing, not asking permission.
The release IS our independent work; the heads-up is a politeness gesture
that often turns into endorsement. Sample message:

> "Hey John, releasing the Ayu Islands plugin for JetBrains v2.6.0 next
> week with a feature we call 'Peacock parity' — per-project chrome tinting
> inspired by your VS Code extension (which I've used for years). Built on
> JetBrains primitives, no Peacock code, full attribution in our What's New
> screen. Wanted to give you a heads-up out of respect. Cheers."

If he replies positively, screenshot the reply and consider adding it to
the marketplace listing as a third-party endorsement (with permission).
