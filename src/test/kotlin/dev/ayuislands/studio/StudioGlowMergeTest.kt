package dev.ayuislands.studio

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.resolveWaveformConfig
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioGlowMerge
import dev.ayuislands.studio.session.StudioGlowProjection
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioGlowMergeTest {
    private val settings = StudioGlowProjection.settings(sessionDocument().currents.global.appearance)
    private val actual =
        GlowPaneAppearance(
            Color(0x5CCFE6),
            GlowStyle.GRADIENT,
            93,
            19,
            GlowPlacement.SIDE_EDGES,
            GlowShape.WAVEFORM,
            WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 17),
            isVisible = false,
        )
    private val accent = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.ACCENT)))

    @Test
    fun `membership permission follows explicit target intent and leaves focus independent`() {
        assertEquals(setOf("Git"), StudioGlowMerge.membershipScope(selected("glowGit")))
        assertEquals(emptySet(), StudioGlowMerge.membershipScope(accent))
        assertFalse(StudioGlowMerge.selectsFocus(selected("glowGit")))
        assertEquals(emptySet(), StudioGlowMerge.membershipScope(selected("glowFocusRing")))
        assertTrue(StudioGlowMerge.selectsFocus(selected("glowFocusRing")))
        val broad = StudioGlowMerge.membershipScope(selected("glowEnabled"))
        assertEquals(setOf("Editor", "Project", "Terminal", "Run", "Debug", "Git", "Services"), broad)
        val availability = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.AVAILABILITY)))
        assertEquals(broad, StudioGlowMerge.membershipScope(availability))
        assertTrue(StudioGlowMerge.selectsFocus(availability))
    }

    @Test
    fun `playback keeps actual animation until explicitly selected or starting cold`() {
        val candidate = settings.copy(animation = GlowAnimation.PULSE)
        assertEquals(
            GlowAnimation.BREATHE,
            StudioGlowMerge.mergeAnimation(accent, candidate, GlowAnimation.BREATHE, false),
        )
        assertEquals(
            GlowAnimation.PULSE,
            StudioGlowMerge.mergeAnimation(selected("glowAnimation"), candidate, GlowAnimation.BREATHE, false),
        )
        assertEquals(
            GlowAnimation.PULSE,
            StudioGlowMerge.mergeAnimation(selected("glowEnabled"), candidate, GlowAnimation.NONE, true),
        )
    }

    @Test
    fun `active empty route keeps exact unselected parameters and applies only explicit waveform field`() {
        val before = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 29, traceLength = 73)
        assertEquals(
            before,
            StudioGlowMerge.mergeRoute(selected("glowTerminal"), settings, before, emptyList(), false),
        )
        val changed = StudioGlowMerge.mergeRoute(selected("waveformAmplitude"), settings, before, emptyList(), false)
        assertEquals(before.copy(amplitude = resolveWaveformConfig(settings).amplitude), changed)
        assertNull(
            StudioGlowMerge.mergeRoute(
                selected("glowEnabled"),
                settings.copy(enabled = false),
                before,
                emptyList(),
                false,
            ),
        )
        assertNull(StudioGlowMerge.mergeRoute(accent, settings, null, listOf(actual), false))
    }

    @Test
    fun `accent dependency changes only color and preserves native hidden state`() {
        val result = StudioGlowMerge.mergePane(actual, accent, settings, Color.ORANGE, isEditor = true)
        assertEquals(actual.copy(color = Color.ORANGE), result)
        val focus = GlowFocusAppearance(actual.color, actual.style, actual.intensity, enabled = true)
        assertEquals(
            focus.copy(color = Color.ORANGE),
            StudioGlowMerge.mergeFocus(focus, accent, settings, Color.ORANGE, isAvailable = false),
        )
    }

    @Test
    fun `equal plain accent preserves exact pane focus and color identities`() {
        val equal = Color(0x5CCFE6)
        assertSame(actual, StudioGlowMerge.mergePane(actual, accent, settings, equal, isEditor = true))
        val focus = GlowFocusAppearance(actual.color, actual.style, actual.intensity, enabled = true)
        assertSame(focus, StudioGlowMerge.mergeFocus(focus, accent, settings, equal, isAvailable = true))
    }

    @Test
    fun `opaque colors are never evaluated or compared by callbacks`() {
        val color = OpaqueColor()
        val pane = actual.copy(color = color)
        val focus = GlowFocusAppearance(color, actual.style, actual.intensity, enabled = true)
        assertSame(pane, StudioGlowMerge.mergePane(pane, accent, settings, color, isEditor = false))
        assertSame(focus, StudioGlowMerge.mergeFocus(focus, accent, settings, color, isAvailable = true))
        val replacement = OpaqueColor()
        val result = StudioGlowMerge.mergePane(pane, accent, settings, replacement, isEditor = false)
        assertSame(replacement, result.color)
    }

    @Test
    fun `dormant style edits metadata playback and membership do not repaint an existing pane`() {
        val names =
            listOf("sharpNeonIntensity", "gradientWidth", "glowPreset", "glowAnimation", "glowEnabled", "glowEditor")
        for (name in names) {
            val result = StudioGlowMerge.mergePane(actual, selected(name), settings, Color.ORANGE, isEditor = true)
            assertSame(actual, result, name)
        }
        val available = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.AVAILABILITY)))
        assertSame(actual, StudioGlowMerge.mergePane(actual, available, settings, Color.ORANGE, isEditor = true))
    }

    @Test
    fun `selected style uses its stored intensity and width without changing waveform or visibility`() {
        val candidate = settings.copy(style = GlowStyle.SHARP_NEON)
        val result = StudioGlowMerge.mergePane(actual, selected("glowStyle"), candidate, Color.ORANGE, isEditor = true)
        assertEquals(
            actual.copy(
                style = candidate.style,
                intensity = candidate.intensity.getValue(candidate.style),
                width = candidate.width.getValue(candidate.style),
            ),
            result,
        )
    }

    @Test
    fun `active style parameters cannot silently mix with a foreign native selector`() {
        val selection = selected("softIntensity")
        assertFailsWith<IllegalStateException> {
            StudioGlowMerge.mergePane(actual, selection, settings, Color.ORANGE, isEditor = true)
        }
        val focus = GlowFocusAppearance(actual.color, actual.style, actual.intensity, enabled = true)
        assertFailsWith<IllegalStateException> {
            StudioGlowMerge.mergeFocus(focus, selection, settings, Color.ORANGE, isAvailable = true)
        }
        val compatible = actual.copy(style = settings.style)
        assertEquals(
            compatible.copy(intensity = settings.intensity.getValue(settings.style)),
            StudioGlowMerge.mergePane(compatible, selection, settings, Color.ORANGE, isEditor = true),
        )
    }

    @Test
    fun `equal parameters need no selector review and retain native identities`() {
        val pane =
            actual.copy(
                intensity = settings.intensity.getValue(settings.style),
                width = settings.width.getValue(settings.style),
            )
        for (name in listOf("softIntensity", "softWidth")) {
            assertSame(pane, StudioGlowMerge.mergePane(pane, selected(name), settings, Color.ORANGE, isEditor = true))
        }
        val focus = GlowFocusAppearance(pane.color, pane.style, pane.intensity, enabled = false)
        assertSame(
            focus,
            StudioGlowMerge.mergeFocus(focus, selected("softIntensity"), settings, Color.ORANGE, isAvailable = true),
        )
    }

    @Test
    fun `equal parameters do not block an independent accent change`() {
        val pane = actual.copy(intensity = settings.intensity.getValue(settings.style))
        val selection = selected("softIntensity").copy(dependencies = accent.dependencies)
        assertEquals(
            pane.copy(color = Color.ORANGE),
            StudioGlowMerge.mergePane(pane, selection, settings, Color.ORANGE, isEditor = true),
        )
        val focus = GlowFocusAppearance(pane.color, pane.style, pane.intensity, enabled = false)
        assertEquals(
            focus.copy(color = Color.ORANGE),
            StudioGlowMerge.mergeFocus(focus, selection, settings, Color.ORANGE, isAvailable = true),
        )
    }

    @Test
    fun `shape and individual waveform fields preserve the other native parameters`() {
        val candidate = settings.copy(shape = GlowShape.SOLID, waveformAmplitude = Int.MAX_VALUE)
        assertEquals(
            actual.copy(shape = GlowShape.SOLID),
            StudioGlowMerge.mergePane(actual, selected("glowShape"), candidate, Color.ORANGE, isEditor = true),
        )
        val configured = resolveWaveformConfig(candidate)
        assertEquals(
            actual.copy(waveform = actual.waveform.copy(amplitude = configured.amplitude)),
            StudioGlowMerge.mergePane(actual, selected("waveformAmplitude"), candidate, Color.ORANGE, isEditor = true),
        )
    }

    @Test
    fun `placement affects only the corresponding kind of pane`() {
        val selection = selected("glowEditorPlacement")
        assertSame(actual, StudioGlowMerge.mergePane(actual, selection, settings, Color.ORANGE, isEditor = false))
        assertEquals(
            actual.copy(placement = settings.editorPlacement),
            StudioGlowMerge.mergePane(actual, selection, settings, Color.ORANGE, isEditor = true),
        )
    }

    @Test
    fun `focus eligibility changes retain the actual style intensity and color`() {
        val focus = GlowFocusAppearance(actual.color, actual.style, actual.intensity, enabled = true)
        val candidate = settings.copy(enabled = true, focusRing = true)
        val selection = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.AVAILABILITY)))
        assertEquals(
            focus.copy(enabled = false),
            StudioGlowMerge.mergeFocus(focus, selection, candidate, Color.ORANGE, isAvailable = false),
        )
        assertSame(
            focus,
            StudioGlowMerge.mergeFocus(focus, selected("glowEnabled"), candidate, Color.ORANGE, isAvailable = true),
        )
    }

    @Test
    fun `reopening focus gate restores stored eligibility without replacing native parameters`() {
        val focus = GlowFocusAppearance(actual.color, actual.style, actual.intensity, enabled = false)
        val candidate = settings.copy(enabled = true, focusRing = true)
        assertEquals(
            focus.copy(enabled = true),
            StudioGlowMerge.mergeFocus(focus, selected("glowEnabled"), candidate, Color.ORANGE, isAvailable = true),
        )
        assertSame(
            focus,
            StudioGlowMerge.mergeFocus(
                focus,
                selected("glowEnabled"),
                candidate.copy(focusRing = false),
                Color.ORANGE,
                isAvailable = true,
            ),
        )
    }

    private fun selected(name: String): StudioGlowSelection =
        StudioGlowSelection(StudioList(listOf(FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name)))))

    private class OpaqueColor : Color(0) {
        override fun getRGB(): Int = error("Opaque RGB callback must not run")

        override fun equals(other: Any?): Boolean = error("Opaque equality callback must not run")

        override fun hashCode(): Int = error("Opaque hashing callback must not run")
    }
}
