package dev.ayuislands.glow

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-math locks for [GlowPlacementGeometry.clipRegions] — the strips the
 * glass pane clips the cached glow frame to. The user-visible contract:
 * ISLAND paints the full frame, TAB_BAR only a band under the editor tab
 * strip, SIDE_EDGES only the left/right tool-window edges.
 */
class GlowPlacementGeometryTest {
    @Test
    fun `island placement paints unclipped`() {
        assertEquals(
            emptyList(),
            GlowPlacementGeometry.clipRegions(GlowPlacement.ISLAND, 800, 600, 10, 8),
        )
    }

    @Test
    fun `tab bar placement clips to a single top strip spanning the full width`() {
        val regions = GlowPlacementGeometry.clipRegions(GlowPlacement.TAB_BAR, 800, 600, 10, 8)

        assertEquals(listOf(Rectangle(0, 0, 800, 14)), regions, "strip = glowWidth + arc/2 = 10 + 4")
    }

    @Test
    fun `side edges placement clips to left and right strips spanning the full height`() {
        val regions = GlowPlacementGeometry.clipRegions(GlowPlacement.SIDE_EDGES, 800, 600, 10, 8)

        assertEquals(
            listOf(
                Rectangle(0, 0, 14, 600),
                Rectangle(786, 0, 14, 600),
            ),
            regions,
            "left strip flush to x=0, right strip flush to width - strip",
        )
    }

    @Test
    fun `strips never exceed the overlay bounds`() {
        val tabBar = GlowPlacementGeometry.clipRegions(GlowPlacement.TAB_BAR, 800, 6, 10, 8)
        assertEquals(listOf(Rectangle(0, 0, 800, 6)), tabBar, "tab strip clamps to a short overlay")

        val edges = GlowPlacementGeometry.clipRegions(GlowPlacement.SIDE_EDGES, 10, 600, 10, 8)
        assertTrue(
            edges.all { it.x >= 0 && it.x + it.width <= 10 },
            "edge strips clamp to a narrow overlay: $edges",
        )
    }

    @Test
    fun `degenerate sizes return no clip regions for every placement`() {
        for (placement in GlowPlacement.entries) {
            assertEquals(
                emptyList(),
                GlowPlacementGeometry.clipRegions(placement, 0, 600, 10, 8),
                "zero width must not produce clip regions for $placement",
            )
            assertEquals(
                emptyList(),
                GlowPlacementGeometry.clipRegions(placement, 800, -1, 10, 8),
                "negative height must not produce clip regions for $placement",
            )
        }
    }

    @Test
    fun `strip thickness never collapses below one pixel`() {
        val regions = GlowPlacementGeometry.clipRegions(GlowPlacement.TAB_BAR, 800, 600, 0, 0)

        assertEquals(listOf(Rectangle(0, 0, 800, 1)), regions)
    }
}
