package dev.ayuislands.studio

import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioGlowPlanning
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioGlowFocusPlanningTest {
    private val installed = GlowFocusAppearance(Color.ORANGE, GlowStyle.SOFT, 73)
    private val defaults = GlowSettings(enabled = true, focusRing = true, intensity = mapOf(GlowStyle.SOFT to 73))

    @Test
    fun `style loads its saved intensity without enabling the ring or replacing an equal color`() {
        val settings = defaults.copy(style = GlowStyle.GRADIENT, intensity = mapOf(GlowStyle.GRADIENT to 46))
        val selection = select("glowStyle").copy(dependencies = StudioList(listOf(GlowDependency.ACCENT)))
        val disabled = installed.copy(enabled = false)

        val planned = edit(selection, settings, Color(installed.color.rgb, true), actual = disabled)

        assertEquals(disabled.copy(style = GlowStyle.GRADIENT, intensity = 46), planned)
        assertSame(installed.color, planned.color)
    }

    @Test
    fun `active intensity cannot change a foreign style and an equal intensity needs no write`() {
        val selection = select("gradientIntensity")
        val settings = defaults.copy(style = GlowStyle.GRADIENT, intensity = mapOf(GlowStyle.GRADIENT to 46))
        val changedElsewhere = installed.copy(intensity = 46)

        assertFailsWith<IllegalStateException> { edit(selection, settings) }
        assertSame(changedElsewhere, edit(selection, settings, actual = changedElsewhere))

        val matchingStyle = installed.copy(style = GlowStyle.GRADIENT)
        assertEquals(matchingStyle.copy(intensity = 46), edit(selection, settings, actual = matchingStyle))
    }

    @Test
    fun `availability and explicit toggles affect only whether the ring is visible`() {
        val availability = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.AVAILABILITY)))
        val disabled = edit(availability, available = false)

        assertEquals(installed.copy(enabled = false), disabled)
        assertEquals(installed, edit(availability, actual = disabled))
        assertEquals(disabled, edit(select("glowEnabled"), defaults.copy(enabled = false)))
        assertEquals(disabled, edit(select("glowFocusRing"), defaults.copy(focusRing = false)))
        assertEquals(installed, edit(select("glowFocusRing"), actual = disabled))
        assertTrue(defaults.enabled && defaults.focusRing)
    }

    @Test
    fun `accent needs confirmed intent and keeps native dynamic color identity`() {
        val accent = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.ACCENT)))
        val dynamic = object : Color(installed.color.rgb, true) {}

        assertNull(StudioGlowPlanning.focusEdit(accent, defaults, null, available = true))
        assertSame(installed, edit(accent, confirmedAccent = Color(installed.color.rgb, true)))
        val planned = edit(accent, confirmedAccent = dynamic)
        assertSame(dynamic, planned.color)
        assertEquals(installed.copy(color = dynamic), planned)
        assertSame(installed.color, edit(select("glowStyle"), confirmedAccent = Color.CYAN).color)
    }

    @Test
    fun `pane-only and inactive fields do not take a focus hold`() {
        val selected = select("glowTerminal", "glowPreset", "softWidth", "gradientIntensity")
        val selection =
            selected.copy(
                fields =
                    StudioList(
                        buildList {
                            addAll(selected.fields)
                            add(FieldAddress(LegacyFieldKey(LegacyStore.SYNTAX, "glowStyle")))
                            add(FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowFocusRing"), FieldPart.MapOrder))
                        },
                    ),
            )

        assertNull(StudioGlowPlanning.focusEdit(selection, defaults, Color.CYAN, available = false))
    }

    private fun edit(
        selection: StudioGlowSelection,
        settings: GlowSettings = defaults,
        confirmedAccent: Color? = null,
        available: Boolean = true,
        actual: GlowFocusAppearance = installed,
    ): GlowFocusAppearance =
        StudioGlowPlanning.focusEdit(selection, settings, confirmedAccent, available)?.invoke(actual) ?: actual

    private fun select(vararg names: String): StudioGlowSelection =
        StudioGlowSelection(fields = StudioList(names.map { FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, it)) }))
}
