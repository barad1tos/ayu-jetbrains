package dev.ayuislands.integration

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.SyntaxCapabilityEvidence
import dev.ayuislands.settings.SyntaxCapabilityState
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.catalogTestWork
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioSyntaxEdit
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.studio.ui.StudioSyntaxAction
import dev.ayuislands.studio.ui.StudioSyntaxComparison
import dev.ayuislands.studio.ui.StudioSyntaxPreview
import dev.ayuislands.studio.ui.StudioSyntaxSection
import dev.ayuislands.studio.ui.StudioSyntaxView
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.RgbBlend
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxOverlayLoader
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.OverrideWriteResult
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
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
import kotlin.test.assertNotSame
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
            fixture.link("Unlock").doClick()
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

    @Test
    fun `current and draft style the same native Java editor without changing its source scheme`() {
        val palette = mirageSyntaxPalette()
        lateinit var editor: EditorEx
        inSection { fixture ->
            val manager = EditorColorsManager.getInstance()
            val global = manager.globalScheme
            val keyword =
                TextAttributesKey.createTextAttributesKey("JAVA_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
            val original = global.getAttributes(keyword)?.clone()
            fixture.selectCustom()
            fixture.edit(StudioSyntaxEdit.Style("Java", PrimitiveCategory.KEYWORD, FontStyleOverride.PLAIN))
            val current = fixture.prepareSample(global, palette)
            fixture.edit(StudioSyntaxEdit.Style("Java", PrimitiveCategory.KEYWORD, FontStyleOverride.BOLD))
            val draft = fixture.prepareSample(global, palette)

            fixture.section.content.preview(PreviewMode.DRAFT)
            fixture.comparison = StudioSyntaxComparison(current, draft)
            fixture.render(language = "Java")
            val sample = fixture.components.filterIsInstance<EditorTextField>().single()
            editor = requireNotNull(sample.getEditor(true))
            assertEquals(Font.BOLD, editor.javaKeywordStyle())
            assertNotSame(draft.scheme, editor.colorsScheme)

            fixture.section.content.preview(PreviewMode.CURRENT)
            assertSame(editor, sample.getEditor(true))
            assertEquals(Font.PLAIN, editor.javaKeywordStyle())
            val shown = editor.highlighter
            fixture.render(language = "Java")
            assertSame(shown, editor.highlighter)
            fixture.section.content.preview(PreviewMode.DRAFT)
            assertEquals(Font.BOLD, editor.javaKeywordStyle())
            assertEquals(Font.PLAIN, current.scheme.getAttributes(keyword)?.fontType)
            assertEquals(Font.BOLD, draft.scheme.getAttributes(keyword)?.fontType)
            assertSame(global, manager.globalScheme)
            assertEquals(original, global.getAttributes(keyword))
            assertEquals(0, fixture.edits)
        }
        SwingUtilities.invokeAndWait {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(editor.isDisposed)
        }
    }

    @Test
    fun `named sample restores a previously owned inherited token and keeps a newer external value`() {
        val palette = mirageSyntaxPalette()
        inSection { fixture ->
            val global = EditorColorsManager.getInstance().globalScheme
            val source = global.clone() as EditorColorsScheme
            val key = TextAttributesKey.createTextAttributesKey("CSS.BRACES", DefaultLanguageHighlighterColors.BRACES)
            val baseline = fixture.prepareSample(source, palette).scheme
            val original = baseline.getAttributes(key)?.clone()
            val owned = TextAttributes(Color.MAGENTA, null, null, null, Font.BOLD)
            val external = TextAttributes(Color.GREEN, null, null, null, Font.ITALIC)
            try {
                assertEquals(
                    OverrideWriteResult.APPLIED,
                    EditorSchemeOverrides.writeAttributes(source, EditorSchemeOwner.Syntax, key, owned),
                )
                val metadata = source.metaProperties.toMap()

                val reset = fixture.prepareSample(source, palette)
                assertEquals(original, reset.scheme.getAttributes(key))
                assertEquals(owned, source.getAttributes(key))
                assertEquals(metadata, source.metaProperties.toMap())

                source.setAttributes(key, external)
                val changed = fixture.prepareSample(source, palette)
                assertEquals(external, changed.scheme.getAttributes(key))
                assertEquals(external, source.getAttributes(key))
                assertEquals(metadata, source.metaProperties.toMap())
            } finally {
                EditorSchemeOverrides.restore(source, EditorSchemeOwner.Syntax)
                EditorSchemeOverrides.rearm(EditorSchemeOwner.Syntax, listOf(source), mapOf(source to emptySet()))
            }
        }
    }

    private fun mirageSyntaxPalette(): SyntaxIntensityApplicator.Request {
        val loader = SyntaxOverlayLoader.getInstance()
        return SyntaxIntensityApplicator.Request(
            preset = SyntaxPreset.AMBIENT,
            variantName = "Mirage",
            editorBg = RgbBlend.fallbackEditorBgFor("Mirage"),
            baseline = loader.loadBaselineForVariant("Mirage"),
            overlay = loader.loadOverlayForVariant("Mirage"),
            fallbacks = loader.fallbacksFor("Mirage"),
        )
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
        var comparison: StudioSyntaxComparison? = null
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

        fun prepareSample(
            scheme: EditorColorsScheme,
            palette: SyntaxIntensityApplicator.Request,
        ) = StudioSyntaxPreview.prepare(AyuVariant.MIRAGE, scheme, palette, working.appearance, isLicensed = true)

        fun render(
            language: String = "Kotlin",
            licensed: Boolean = true,
            editable: Boolean = true,
            categories: Set<PrimitiveCategory> = PrimitiveCategory.entries.toSet(),
            capability: SyntaxCapabilityState =
                SyntaxCapabilityState.Confirmed(language, SyntaxCapabilityEvidence(language, categories)),
        ) {
            section.render(
                StudioSyntaxView(working.appearance, language, capability, categories, editable, licensed),
                comparison,
            )
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

private fun EditorEx.javaKeywordStyle(): Int {
    val offset = document.text.indexOf("public final class")
    assertTrue(offset >= 0, "The native Java preview must include the sample class")
    return highlighter.createIterator(offset).textAttributes.fontType
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
