package dev.ayuislands.integration

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import dev.ayuislands.settings.SyntaxCapabilityEvidence
import dev.ayuislands.settings.SyntaxCapabilityState
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.catalogTestWork
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.StudioSyntaxEdit
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.studio.ui.StudioSyntaxAction
import dev.ayuislands.studio.ui.StudioSyntaxSection
import dev.ayuislands.studio.ui.StudioSyntaxView
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxPreset
import org.junit.Rule
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSyntaxSectionTest {
    @get:Rule val application = ApplicationRule()

    @Test
    fun `named presets remain free and locked choices survive entitlement changes`() =
        inSection { fixture ->
            fixture.render(licensed = false)
            val lockedCustom = fixture.preset(SyntaxPreset.CUSTOM)
            assertFalse(lockedCustom.isEnabled)
            lockedCustom.doClick()
            fixture.checkBox("Dim comments").doClick()
            assertEquals(0, fixture.edits)

            val neon = fixture.preset(SyntaxPreset.NEON)
            neon.doClick()
            assertEquals(SyntaxPreset.NEON.name, fixture.settings.selectedPreset)
            fixture.render(licensed = false)
            assertSame(neon, fixture.preset(SyntaxPreset.NEON))
            fixture.render()
            val custom = fixture.preset(SyntaxPreset.CUSTOM)
            assertSame(lockedCustom, custom)
            assertTrue(custom.isEnabled)
            custom.doClick()
            fixture.render()
            fixture.checkBox("Dim comments").doClick()
            fixture.checkBox("Quiet operators").doClick()
            val saved = fixture.working
            fixture.render(licensed = false)
            fixture.link("Unlock Pro").doClick()
            assertEquals(listOf<StudioSyntaxAction>(StudioSyntaxAction.Unlock), fixture.actions)
            fixture.render()
            assertEquals(saved, fixture.working)
            assertTrue(fixture.checkBox("Dim comments").isSelected)
            assertTrue(fixture.checkBox("Quiet operators").isSelected)
        }

    @Test
    fun `successive keyboard and readability edits preserve hidden cells through a scoped reset`() =
        inSection { fixture ->
            fixture.selectCustom()
            fixture.edit(StudioSyntaxEdit.Intensity("Java", PrimitiveCategory.KEYWORD, 68))
            fixture.edit(StudioSyntaxEdit.Intensity("Java", PrimitiveCategory.COMMENT, 79))
            fixture.edit(StudioSyntaxEdit.Intensity("Kotlin", PrimitiveCategory.KEYWORD, 84))
            fixture.render(language = "Java", categories = setOf(PrimitiveCategory.KEYWORD, PrimitiveCategory.COMMENT))
            val controls = fixture.visibleSliders()
            assertEquals(2, controls.size)
            val keyword = controls.single { it.accessibleContext.accessibleName.startsWith("Keyword intensity") }
            assertEquals(68, keyword.value)
            fixture.checkBox("Soften documentation").doClick()
            keyword.pressRight()
            assertEquals("69", fixture.settings.customOverrides["Java|KEYWORD"])
            assertTrue(fixture.settings.readability.softenDocumentation)

            fixture.render(language = "Java", categories = setOf(PrimitiveCategory.KEYWORD))
            fixture.button("Reset Java customizations").doClick()
            assertEquals(mapOf("Java|COMMENT" to "79", "Kotlin|KEYWORD" to "84"), fixture.settings.customOverrides)
            fixture.render(language = "Java", editable = false, categories = setOf(PrimitiveCategory.KEYWORD))
            assertFalse(keyword.isEnabled)
            val saved = fixture.working
            keyword.value = 91
            assertEquals(saved, fixture.working)
        }

    @Test
    fun `language disclosure and support actions stay separate from draft edits`() =
        inSection { fixture ->
            fixture.selectCustom()
            fixture.render(language = "Java")
            val group =
                fixture.components.filterIsInstance<TitledSeparator>().single { it.text == "Language Customization" }
            assertTrue(fixture.visibleSliders().isNotEmpty())
            group.dispatchEvent(MouseEvent(group, MouseEvent.MOUSE_RELEASED, 0L, 0, 1, 1, 1, false, MouseEvent.BUTTON1))
            fixture.render(language = "Java")
            assertTrue(fixture.visibleSliders().isEmpty())
            group.dispatchEvent(MouseEvent(group, MouseEvent.MOUSE_RELEASED, 0L, 0, 1, 1, 1, false, MouseEvent.BUTTON1))

            val language = fixture.components.filterIsInstance<JComboBox<*>>().single()
            language.selectedItem = "Kotlin"
            fixture.render(language = "Kotlin", capability = SyntaxCapabilityState.SupportUnavailable("Kotlin"))
            assertTrue(fixture.visibleSliders().isEmpty())
            fixture.button("Open Marketplace").doClick()
            fixture.link("Per-key tuning in Color Scheme editor").doClick()
            assertEquals(
                listOf(
                    StudioSyntaxAction.DiscloseLanguage(false),
                    StudioSyntaxAction.DiscloseLanguage(true),
                    StudioSyntaxAction.SelectLanguage("Kotlin"),
                    StudioSyntaxAction.RecoverSupport,
                    StudioSyntaxAction.OpenColorScheme,
                ),
                fixture.actions,
            )
            assertEquals(0, fixture.edits)
        }

    private fun inSection(test: (Fixture) -> Unit) {
        SwingUtilities.invokeAndWait {
            val fixture = Fixture()
            try {
                test(fixture)
            } finally {
                Disposer.dispose(fixture.section)
            }
        }
    }

    private class Fixture {
        var working = catalogTestWork(catalogTestDocument()).drafts.single()
            private set
        var edits = 0
            private set
        val actions = mutableListOf<StudioSyntaxAction>()
        val section =
            StudioSyntaxSection(
                languageExpanded = true,
                stage = { update: (DraftRecord) -> DraftRecord ->
                    working = update(working)
                    edits++
                },
                onAction = { actions.add(it) },
            )
        val components: Sequence<Component> get() = descendants(section.content.component)
        val settings get() = StudioSyntaxProjection.settings(working.appearance)

        fun selectCustom() = edit(StudioSyntaxEdit.Preset(SyntaxPreset.CUSTOM))

        fun edit(edit: StudioSyntaxEdit) {
            working = StudioSyntaxEditing.edit(working, edit)
        }

        fun render(
            language: String = "Kotlin",
            licensed: Boolean = true,
            editable: Boolean = true,
            categories: Set<PrimitiveCategory> = PrimitiveCategory.entries.toSet(),
            capability: SyntaxCapabilityState =
                SyntaxCapabilityState.Confirmed(language, SyntaxCapabilityEvidence(language, categories)),
        ) {
            section.render(StudioSyntaxView(working.appearance, language, capability, categories, editable, licensed))
        }

        fun preset(preset: SyntaxPreset) =
            components.filterIsInstance<JToggleButton>().single { it.text == preset.displayName }

        fun checkBox(text: String) = components.filterIsInstance<JCheckBox>().single { it.text == text }

        fun button(text: String) = components.filterIsInstance<JButton>().single { it.text == text }

        fun link(text: String) = components.filterIsInstance<ActionLink>().single { it.text == text }

        fun visibleSliders() =
            components
                .filterIsInstance<JSlider>()
                .filter { slider -> generateSequence<Component>(slider) { it.parent }.all(Component::isVisible) }
                .toList()
    }
}

private fun descendants(component: Component): Sequence<Component> {
    val children = (component as? Container)?.components.orEmpty()
    return sequenceOf(component) + children.asSequence().flatMap(::descendants)
}

private fun JSlider.pressRight() {
    val key = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)
    val action = getInputMap(JComponent.WHEN_FOCUSED)[key]
    actionMap[action].actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, action.toString()))
}
