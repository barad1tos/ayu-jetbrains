package dev.ayuislands.glow

import java.awt.Rectangle

/**
 * Pure clip-region math for [GlowPlacement] variants. The glass pane paints a
 * cached full-perimeter glow frame; partial placements clip that frame to
 * strips instead of re-rendering, so style, intensity, width, animation, and
 * fade semantics carry over unchanged.
 */
object GlowPlacementGeometry {
    /**
     * Rectangles the paint pass clips to for [placement] over a
     * `width x height` overlay. An empty list means paint unclipped —
     * returned for [GlowPlacement.ISLAND] and for degenerate sizes, where
     * nothing paints anyway.
     *
     * The strip extends past [glowWidth] by half of [arcWidth] so the island
     * corner falloff is not chopped mid-curve; tune the reach in
     * [stripThickness] only.
     */
    fun clipRegions(
        placement: GlowPlacement,
        width: Int,
        height: Int,
        glowWidth: Int,
        arcWidth: Int,
    ): List<Rectangle> {
        if (width <= 0 || height <= 0) return emptyList()
        return when (placement) {
            GlowPlacement.ISLAND -> {
                emptyList()
            }

            GlowPlacement.TAB_BAR -> {
                val strip = stripThickness(glowWidth, arcWidth).coerceAtMost(height)
                listOf(Rectangle(0, 0, width, strip))
            }

            GlowPlacement.SIDE_EDGES -> {
                val strip = stripThickness(glowWidth, arcWidth).coerceAtMost(width)
                listOf(
                    Rectangle(0, 0, strip, height),
                    Rectangle(width - strip, 0, strip, height),
                )
            }
        }
    }

    private fun stripThickness(
        glowWidth: Int,
        arcWidth: Int,
    ): Int = (glowWidth + arcWidth / 2).coerceAtLeast(1)
}
