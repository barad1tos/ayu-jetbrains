package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class GlowEnumsTest {
    // GlowShape

    @Test
    fun `GlowShape decodes persisted names and falls back to SOLID`() {
        assertEquals(GlowShape.SOLID, GlowShape.fromName("SOLID"))
        assertEquals(GlowShape.WAVEFORM, GlowShape.fromName("WAVEFORM"))
        assertEquals(GlowShape.SOLID, GlowShape.fromName("UNKNOWN"))
        assertEquals(GlowShape.SOLID, GlowShape.fromName(null))
    }

    @Test
    fun `WaveformDirection decodes persisted names and falls back to CLOCKWISE`() {
        assertEquals(WaveformDirection.CLOCKWISE, WaveformDirection.fromName("CLOCKWISE"))
        assertEquals(
            WaveformDirection.COUNTER_CLOCKWISE,
            WaveformDirection.fromName("COUNTER_CLOCKWISE"),
        )
        assertEquals(WaveformDirection.CLOCKWISE, WaveformDirection.fromName("UNKNOWN"))
        assertEquals(WaveformDirection.CLOCKWISE, WaveformDirection.fromName(null))
    }

    // GlowStyle

    @Test
    fun `GlowStyle fromName returns correct entry for valid names`() {
        assertEquals(GlowStyle.SOFT, GlowStyle.fromName("SOFT"))
        assertEquals(GlowStyle.SHARP_NEON, GlowStyle.fromName("SHARP_NEON"))
        assertEquals(GlowStyle.GRADIENT, GlowStyle.fromName("GRADIENT"))
    }

    @Test
    fun `GlowStyle fromName falls back to SOFT for invalid name`() {
        assertEquals(GlowStyle.SOFT, GlowStyle.fromName("NONEXISTENT"))
        assertEquals(GlowStyle.SOFT, GlowStyle.fromName(""))
        assertEquals(GlowStyle.SOFT, GlowStyle.fromName("soft"))
    }

    @Test
    fun `GlowStyle entries have correct display names`() {
        assertEquals("Soft", GlowStyle.SOFT.displayName)
        assertEquals("Sharp Neon", GlowStyle.SHARP_NEON.displayName)
        assertEquals("Gradient", GlowStyle.GRADIENT.displayName)
    }

    @Test
    fun `GlowStyle entries have expected defaults`() {
        assertEquals(35, GlowStyle.SOFT.defaultIntensity)
        assertEquals(8, GlowStyle.SOFT.defaultWidth)
        assertEquals(65, GlowStyle.SHARP_NEON.defaultIntensity)
        assertEquals(8, GlowStyle.SHARP_NEON.defaultWidth)
        assertEquals(45, GlowStyle.GRADIENT.defaultIntensity)
        assertEquals(10, GlowStyle.GRADIENT.defaultWidth)
    }

    // GlowTabMode

    @Test
    fun `GlowTabMode fromName returns correct entry for valid names`() {
        assertEquals(GlowTabMode.MINIMAL, GlowTabMode.fromName("MINIMAL"))
        assertEquals(GlowTabMode.FULL, GlowTabMode.fromName("FULL"))
        assertEquals(GlowTabMode.OFF, GlowTabMode.fromName("OFF"))
    }

    @Test
    fun `GlowTabMode fromName resolves legacy names`() {
        assertEquals(GlowTabMode.MINIMAL, GlowTabMode.fromName("UNDERLINE"))
        assertEquals(GlowTabMode.FULL, GlowTabMode.fromName("FULL_BORDER"))
    }

    @Test
    fun `GlowTabMode fromName falls back to MINIMAL for invalid name`() {
        assertEquals(GlowTabMode.MINIMAL, GlowTabMode.fromName("NONEXISTENT"))
        assertEquals(GlowTabMode.MINIMAL, GlowTabMode.fromName(""))
    }

    @Test
    fun `GlowTabMode entries have correct display names`() {
        assertEquals("Minimal", GlowTabMode.MINIMAL.displayName)
        assertEquals("Full", GlowTabMode.FULL.displayName)
        assertEquals("Off", GlowTabMode.OFF.displayName)
    }

    // GlowAnimation

    @Test
    fun `GlowAnimation fromName returns correct entry for valid names`() {
        assertEquals(GlowAnimation.NONE, GlowAnimation.fromName("NONE"))
        assertEquals(GlowAnimation.PULSE, GlowAnimation.fromName("PULSE"))
        assertEquals(GlowAnimation.BREATHE, GlowAnimation.fromName("BREATHE"))
        assertEquals(GlowAnimation.REACTIVE, GlowAnimation.fromName("REACTIVE"))
    }

    @Test
    fun `GlowAnimation fromName falls back to NONE for invalid name`() {
        assertEquals(GlowAnimation.NONE, GlowAnimation.fromName("NONEXISTENT"))
        assertEquals(GlowAnimation.NONE, GlowAnimation.fromName(""))
    }

    @Test
    fun `GlowAnimation entries have correct display names`() {
        assertEquals("None", GlowAnimation.NONE.displayName)
        assertEquals("Pulse", GlowAnimation.PULSE.displayName)
        assertEquals("Breathe", GlowAnimation.BREATHE.displayName)
        assertEquals("Reactive", GlowAnimation.REACTIVE.displayName)
    }

    @Test
    fun `all three enums have exhaustive entries`() {
        assertEquals(3, GlowStyle.entries.size)
        assertEquals(3, GlowTabMode.entries.size)
        assertEquals(4, GlowAnimation.entries.size)
        assertEquals(2, GlowPlacement.entries.size)
    }

    // GlowPlacement

    @Test
    fun `GlowPlacement fromName returns correct entry for valid names`() {
        assertEquals(GlowPlacement.ISLAND, GlowPlacement.fromName("ISLAND"))
        assertEquals(GlowPlacement.SIDE_EDGES, GlowPlacement.fromName("SIDE_EDGES"))
    }

    @Test
    fun `GlowPlacement fromName falls back to ISLAND for invalid names`() {
        assertEquals(GlowPlacement.ISLAND, GlowPlacement.fromName("NONEXISTENT"))
        assertEquals(GlowPlacement.ISLAND, GlowPlacement.fromName(""))
        assertEquals(GlowPlacement.ISLAND, GlowPlacement.fromName(null))
    }

    @Test
    fun `GlowPlacement fromName migrates retired TAB_BAR to its successor`() {
        // 2.8.0 pre-release value: side edges replaced under-tabs, so a saved
        // partial placement must stay partial instead of silently going
        // full-frame ISLAND.
        assertEquals(GlowPlacement.SIDE_EDGES, GlowPlacement.fromName("TAB_BAR"))
    }

    @Test
    fun `GlowPlacement entries have correct display names`() {
        assertEquals("Island", GlowPlacement.ISLAND.displayName)
        assertEquals("Side edges", GlowPlacement.SIDE_EDGES.displayName)
    }
}
