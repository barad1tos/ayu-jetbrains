package dev.ayuislands.accent.toolbar

import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.color.AccentHsl
import org.jetbrains.annotations.TestOnly
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * Quick-Switcher chip's layered icon: three nested squircle (superellipse-
 * like) layers mirroring the SVG reference at
 * `~/Downloads/rounded-square-layered-icon.svg`. Renders as
 *
 *  - **Outer ring**: filled with the focused project's accent (the colour
 *    every other chip-level test asserts).
 *  - **Empty band** between the outer ring and the inner square (transparent;
 *    no paint).
 *  - **Inner island**: either filled with `AccentHsl.darken(accent)` (when a
 *    per-project pin is active — `pinned = true`) or stroked as an outline in
 *    the outer-ring colour (when no pin — `pinned = false`). The filled-vs-
 *    hollow distinction is the chip's affordance for "this project's accent
 *    is pinned" (locked-in weight) vs "chip shows the global accent" (lighter
 *    outline).
 *
 * Geometry mirrors the SVG `~/Downloads/rounded-square-layered-icon.svg` —
 * three nested squircles, NOT round-rectangles. Each layer is a 4-cubic-
 * Bézier path whose control points sit on the bounding edges (Apple-style
 * iOS-app-icon corner). At the SVG scale (300×300 viewBox, 250×250 outer
 * square), the first control offset from each midpoint toward the corner is
 * 76 px (= 0.304 × side length). The icon scales that ratio to any [sizePx].
 *
 * Paint is split into [paintIcon] (Swing entry point) and a `@TestOnly`
 * [paintForTest] render seam — tests rasterize into a `BufferedImage` and
 * assert on the outer-ring and inner-square pixels. Mirrors the seam in
 * `PopupSwatch`.
 */
internal class LayeredAccentIcon(
    private val sizePx: Int,
    accent: AccentHex,
    private val pinned: Boolean,
) : Icon {
    init {
        require(sizePx > 0) { "sizePx must be positive, got $sizePx" }
    }

    // Both colours are resolved once at construction so the paint path is
    // branch-free on colour derivation. `AccentHsl.darken` returns the input
    // unchanged at the lightness clamp edge; fall back to `lighten` so very
    // dark accents still produce a distinguishable inner island.
    private val outerColor: Color = ColorUtil.fromHex(accent.value)
    private val innerColor: Color =
        ColorUtil.fromHex(
            AccentHsl
                .darken(accent)
                .takeIf { it.value != accent.value }
                ?.value
                ?: AccentHsl.lighten(accent).value,
        )

    /** Exposed so chip-level tests can assert the active accent without re-deriving from the hex string. */
    internal val accentColor: Color get() = outerColor

    /** Exposed so chip-level tests can assert the pinned-vs-unpinned visual without rendering. */
    internal val isPinned: Boolean get() = pinned

    override fun getIconWidth(): Int = sizePx

    override fun getIconHeight(): Int = sizePx

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(x, y)
            paintForTest(g2)
        } finally {
            g2.dispose()
        }
    }

    @TestOnly
    internal fun paintForTest(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val side = sizePx.toFloat()
        val outerInset = side * OUTER_INSET_RATIO
        val middleInset = side * MIDDLE_INSET_RATIO
        val innerInset = side * INNER_INSET_RATIO

        val outer = squirclePath(outerInset, side - 2 * outerInset)
        val middle = squirclePath(middleInset, side - 2 * middleInset)
        val inner = squirclePath(innerInset, side - 2 * innerInset)

        // Outer ring = outer area minus the middle "hole" — yields the
        // filled outer band with a transparent gap separating it from the
        // inner square below.
        val ring = Area(outer).apply { subtract(Area(middle)) }
        g2.color = outerColor
        g2.fill(ring)

        // Inner island: filled (pinned) or outlined (no pin). Outline width
        // scales with chip size so the stroke stays visually present at the
        // ~3px inner-squircle footprint of the 13×13 cell while staying
        // crisp at larger JBUI scales.
        if (pinned) {
            g2.color = innerColor
            g2.fill(inner)
        } else {
            g2.color = outerColor
            g2.stroke = BasicStroke((side * STROKE_RATIO).coerceAtLeast(MIN_STROKE_PX))
            g2.draw(inner)
        }
    }

    /** One squircle layer (4 cubic Béziers) — see class header for geometry. */
    private fun squirclePath(
        inset: Float,
        side: Float,
    ): Path2D.Float {
        val k = CONTROL_OFFSET_RATIO * side
        val half = side / 2f
        val far = inset + side
        val path = Path2D.Float()
        // Top midpoint → right midpoint (top-right corner).
        path.moveTo(inset + half, inset)
        path.curveTo(inset + half + k, inset, far, inset, far, inset + half)
        // Right midpoint → bottom midpoint (bottom-right corner).
        path.curveTo(far, inset + half + k, far, far, inset + half, far)
        // Bottom midpoint → left midpoint (bottom-left corner).
        path.curveTo(inset + half - k, far, inset, far, inset, inset + half)
        // Left midpoint → top midpoint (top-left corner).
        path.curveTo(inset, inset + half - k, inset, inset, inset + half, inset)
        path.closePath()
        return path
    }

    companion object {
        // SVG reference geometry: 25/300, 50/300, 75/300 insets — kept as
        // ratios so the icon renders correctly at any size (Retina scaling,
        // future chip-size tweaks).
        internal const val OUTER_INSET_RATIO = 0.0833f
        internal const val MIDDLE_INSET_RATIO = 0.1667f
        internal const val INNER_INSET_RATIO = 0.25f

        // First control-point offset from each side's midpoint toward the
        // corner, expressed as a fraction of the full side length. Derived
        // directly from the SVG: 76 px / 250 px = 0.304.
        private const val CONTROL_OFFSET_RATIO = 0.304f

        // Outline stroke ratio (no-pin state). 6% of side length yields
        // ~0.78px at 13px → coerceAtLeast(1f) keeps the line visible.
        private const val STROKE_RATIO = 0.06f
        private const val MIN_STROKE_PX = 1f

        /**
         * Chip's hit-test contract: `true` when the local mouse point
         * `(x, y)` falls inside the inner-island bounding box of a chip
         * rendered at [size]. Bounding box rather than the squircle path
         * itself — the near-corner overshoot is sub-pixel at 13px and the
         * cost of a point-in-Bezier check on every `mousePressed` is not
         * worth the precision. Owned by the icon because the rectangle's
         * inset is dictated by the icon's geometry; the chip is the only
         * caller.
         */
        internal fun isInsideInnerIslandHitBox(
            x: Int,
            y: Int,
            size: Int,
        ): Boolean {
            val inset = size * INNER_INSET_RATIO
            val limit = size - inset
            return x >= inset && x <= limit && y >= inset && y <= limit
        }
    }
}
