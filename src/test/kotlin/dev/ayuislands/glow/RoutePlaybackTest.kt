package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformMovement
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RoutePlaybackTest {
    private val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)

    @Test
    fun `separately decoded equal static accents share rendering without replacing native colors`() {
        val first = appearance(Color(0x5CCFE6))
        val second = first.copy(color = Color(0x5CCFE6), placement = GlowPlacement.SIDE_EDGES)
        val secondColor = second.color

        assertSame(first, qualifyRouteAppearance(config, listOf(first, second)))
        assertSame(secondColor, second.color)
    }

    @Test
    fun `opaque colors require identical references without evaluating native color callbacks`() {
        val firstColor = OpaqueColor()
        val first = appearance(firstColor)
        assertSame(first, qualifyRouteAppearance(config, listOf(first, first.copy())))

        for (other in listOf(OpaqueColor(), Color.BLACK)) {
            assertFailsWith<IllegalStateException> {
                qualifyRouteAppearance(config, listOf(first, first.copy(color = other)))
            }
            assertFailsWith<IllegalStateException> {
                qualifyRouteAppearance(config, listOf(first.copy(color = other), first))
            }
        }
    }

    @Test
    fun `shared playback rejects different native parameters without normalizing peers`() {
        val first = appearance(Color.CYAN)
        val incompatible =
            listOf(
                first.copy(color = Color.MAGENTA),
                first.copy(style = GlowStyle.GRADIENT),
                first.copy(width = 11),
                first.copy(intensity = 71),
                first.copy(shape = GlowShape.SOLID),
                first.copy(isVisible = false),
                first.copy(waveform = config.copy(amplitude = 17)),
                first.copy(waveform = config.copy(movement = WaveformMovement.CLOCKWISE)),
            )
        for (second in incompatible) {
            assertFailsWith<IllegalStateException> { qualifyRouteAppearance(config, listOf(first, second)) }
        }
    }

    private fun appearance(color: Color): GlowPaneAppearance =
        GlowPaneAppearance(color, GlowStyle.SOFT, 50, 8, GlowPlacement.ISLAND, GlowShape.WAVEFORM, config, true)

    private class OpaqueColor : Color(0) {
        override fun getRGB(): Int = error("Opaque color must not be evaluated during qualification")
    }
}
