package dev.ayuislands.studio

import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.resolveWaveformConfig
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioGlowPanePlanning
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioGlowPanePlanningTest {
    private val mounted =
        GlowPaneAppearance(
            color = Color(0xFFCC66),
            style = GlowStyle.SOFT,
            intensity = 73,
            width = 14,
            placement = GlowPlacement.ISLAND,
            shape = GlowShape.SOLID,
            waveform = WaveformConfig(),
            isVisible = true,
        )

    @Test
    fun `editing editor placement and waveform movement leaves the other pane and native values intact`() {
        val settings =
            GlowSettings(
                editorPlacement = GlowPlacement.SIDE_EDGES,
                toolWindowPlacement = GlowPlacement.SIDE_EDGES,
                waveformMovement = WaveformMovement.CHAOTIC,
                waveformBaseline = WaveformBaseline.OUTSIDE,
            )
        val selection = select("glowEditorPlacement", "waveformDirection")

        val editor = StudioGlowPanePlanning.edit(selection, settings, null, isEditor = true, mounted)
        val tool = StudioGlowPanePlanning.edit(selection, settings, Color.CYAN, isEditor = false, mounted)

        assertEquals(setOf(GlowPaneField.PLACEMENT, GlowPaneField.MOVEMENT), editor.fields)
        assertEquals(setOf(GlowPaneField.MOVEMENT), tool.fields)
        val expectedTool = mounted.copy(waveform = mounted.waveform.copy(movement = WaveformMovement.CHAOTIC))
        assertEquals(expectedTool, tool.appearance)
        assertEquals(tool.appearance.copy(placement = GlowPlacement.SIDE_EDGES), editor.appearance)

        val toolSelection = select("glowToolWindowPlacement")
        val onlyTool = StudioGlowPanePlanning.edit(toolSelection, settings, null, isEditor = false, mounted)
        assertEquals(mounted.copy(placement = GlowPlacement.SIDE_EDGES), onlyTool.appearance)
    }

    @Test
    fun `selecting a style applies its stored parameters without resetting waveform or visibility`() {
        val settings =
            GlowSettings(
                style = GlowStyle.GRADIENT,
                intensity = mapOf(GlowStyle.SOFT to 15, GlowStyle.GRADIENT to 47),
                width = mapOf(GlowStyle.SOFT to 5, GlowStyle.GRADIENT to 9),
                waveformTraceLength = 713,
            )

        val selection = select("glowStyle", "softWidth")
        val edit = StudioGlowPanePlanning.edit(selection, settings, null, isEditor = true, mounted)

        assertEquals(setOf(GlowPaneField.STYLE, GlowPaneField.INTENSITY, GlowPaneField.WIDTH), edit.fields)
        assertEquals(mounted.copy(style = GlowStyle.GRADIENT, intensity = 47, width = 9), edit.appearance)
    }

    @Test
    fun `a stored width cannot overwrite a different style currently on screen`() {
        val settings = GlowSettings(style = GlowStyle.GRADIENT, width = mapOf(GlowStyle.GRADIENT to 9))
        val selection = select("gradientWidth")

        assertFailsWith<IllegalStateException> {
            StudioGlowPanePlanning.edit(selection, settings, null, isEditor = true, mounted)
        }
        val sameWidth = mounted.copy(width = 9)
        val unchanged = StudioGlowPanePlanning.edit(selection, settings, null, isEditor = true, sameWidth)
        assertSame(sameWidth, unchanged.appearance)
    }

    @Test
    fun `selected waveform values use the same runtime limits as legacy Glow`() {
        val legacy =
            AyuIslandsState().apply {
                waveformAmplitude = Int.MIN_VALUE
                waveformIntensity = Int.MAX_VALUE
                waveformLoopSeconds = Float.NaN
                waveformTraceDensity = Int.MAX_VALUE
                waveformTraceLength = Int.MAX_VALUE
                waveformBaseline = WaveformBaseline.OUTSIDE.name
            }
        val settings =
            GlowSettings(
                shape = GlowShape.WAVEFORM,
                waveformAmplitude = legacy.waveformAmplitude,
                waveformIntensity = legacy.waveformIntensity,
                waveformLoopSeconds = legacy.waveformLoopSeconds,
                waveformTraceDensity = legacy.waveformTraceDensity,
                waveformTraceLength = legacy.waveformTraceLength,
                waveformBaseline = WaveformBaseline.OUTSIDE,
            )
        val selection =
            select(
                "glowShape",
                "waveformAmplitude",
                "waveformIntensity",
                "waveformLoopSeconds",
                "waveformTraceDensity",
                "waveformTraceLength",
                "waveformBaseline",
            )
        val waveform = WaveformConfig(movement = WaveformMovement.COUNTER_CLOCKWISE, loopSeconds = 7f)
        val before = mounted.copy(waveform = waveform)

        val edit = StudioGlowPanePlanning.edit(selection, settings, null, isEditor = true, before)

        assertEquals(7, edit.fields.size)
        val expected = resolveWaveformConfig(legacy).copy(movement = before.waveform.movement)
        assertEquals(before.copy(shape = GlowShape.WAVEFORM, waveform = expected), edit.appearance)
        assertTrue(settings.waveformLoopSeconds.isNaN())
    }

    @Test
    fun `membership and dormant or unrelated field addresses grant no appearance writes`() {
        val selected = select("glowEnabled", "glowFocusRing", "glowTerminal", "glowPreset", "sharpNeonWidth")
        val selection =
            selected.copy(
                fields =
                    StudioList(
                        buildList {
                            addAll(selected.fields)
                            add(FieldAddress(LegacyFieldKey(LegacyStore.SYNTAX, "glowShape")))
                            add(FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "softWidth"), FieldPart.MapOrder))
                        },
                    ),
                dependencies = StudioList(listOf(GlowDependency.AVAILABILITY)),
            )

        val edit = StudioGlowPanePlanning.edit(selection, GlowSettings(), Color.CYAN, isEditor = true, mounted)

        assertTrue(edit.fields.isEmpty())
        assertSame(mounted, edit.appearance)
    }

    @Test
    fun `accent refresh preserves an identical plain color but distinguishes a dynamic color instance`() {
        val selection = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.ACCENT)))
        val equalPlainColor = Color(mounted.color.rgb, true)
        val dynamic = object : Color(mounted.color.rgb, true) {}

        val missing = StudioGlowPanePlanning.edit(selection, GlowSettings(), null, isEditor = true, mounted)
        val equal = StudioGlowPanePlanning.edit(selection, GlowSettings(), equalPlainColor, isEditor = true, mounted)
        val changed = StudioGlowPanePlanning.edit(selection, GlowSettings(), dynamic, isEditor = true, mounted)

        assertTrue(missing.fields.isEmpty())
        assertTrue(equal.fields.isEmpty())
        assertSame(mounted.color, equal.appearance.color)
        assertEquals(setOf(GlowPaneField.COLOR), changed.fields)
        assertSame(dynamic, changed.appearance.color)
    }

    private fun select(vararg names: String): StudioGlowSelection =
        StudioGlowSelection(fields = StudioList(names.map { FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, it)) }))
}
