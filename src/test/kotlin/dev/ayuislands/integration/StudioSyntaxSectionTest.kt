package dev.ayuislands.integration

import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ui.popup.ComponentPopupBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.EditorTextField
import com.intellij.ui.InplaceButton
import com.intellij.ui.TitledSeparator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.SyntaxCapabilityEvidence
import dev.ayuislands.settings.SyntaxCapabilityState
import dev.ayuislands.settings.SyntaxControls
import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.settings.SyntaxPanelView
import dev.ayuislands.settings.SyntaxPreviewComponent
import dev.ayuislands.settings.SyntaxPreviewInput
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.ui.StudioSyntaxSection
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.PrimitiveGroup
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPreset
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSyntaxSectionTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Before
    fun forbidAmbientEffects() {
        mockkObject(
            AyuIslandsSettings.Companion,
            SyntaxIntensityState.Companion,
            SyntaxIntensityService.Companion,
            LicenseChecker,
        )
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Settings must come from the explicit view")
        every { SyntaxIntensityState.getInstance() } throws AssertionError("Syntax choices belong to the draft")
        every { SyntaxIntensityService.getInstance() } throws AssertionError("Native Syntax effects belong to Apply")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement must be explicit")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("License actions belong to the host")
    }

    @After
    fun cleanup() {
        SwingUtilities.invokeAndWait {}
        unmockkAll()
    }

    @Test
    fun `unlicensed preset row disables Custom pill affordance`() {
        SwingUtilities.invokeAndWait {
            val input = preview(Color(0x5CCFE6), Font.PLAIN)
            val events = mutableListOf<SyntaxPanelEvent>()
            val controls = SyntaxControls(input, events::add)
            try {
                controls.render(view(draft()).copy(isLicensed = false), input)
                val custom =
                    descendants(controls).filterIsInstance<ActionButton>().single {
                        it.presentation.text == "Custom"
                    }
                custom.update()
                assertFalse(custom.isEnabled, "Free users must see Custom as disabled")
                assertEquals("Pro Feature", custom.presentation.description)
                assertTrue(events.isEmpty())
            } finally {
                Disposer.dispose(controls)
            }
        }
    }

    @Test
    fun `native style edits keep their alternate sample across synchronous rerender and comparison modes`() {
        SwingUtilities.invokeAndWait {
            val popupContent = slot<JComponent>()
            stubPopup(popupContent)
            val input = preview(Color(0x5CCFE6), Font.PLAIN)
            var requested = draft()
            lateinit var section: StudioSyntaxSection

            fun show() {
                section.render(
                    requested,
                    view(requested).copy(
                        language = "TIL",
                        capability = confirmed("TIL"),
                        actuatedByLanguage = mapOf("TIL" to PrimitiveCategory.entries.toSet()),
                    ),
                    input,
                    input,
                )
            }
            section =
                StudioSyntaxSection(input, stage = { edits ->
                    for (intent in edits) {
                        val edit = assertIs<SessionIntent.EditField>(intent)
                        requested = StudioDraftPolicy.edit(requested, edit.address, edit.value)
                    }
                    show()
                }, action = {})
            try {
                show()
                val content = section.content.component
                expand(customizationGroup(content))
                val sample = descendants(content).filterIsInstance<SyntaxPreviewComponent>().single()
                assertEquals("preview.hil", sample.sampleFileNameForTest())
                val style =
                    descendants(content).filterIsInstance<InplaceButton>().single {
                        it.accessibleContext.accessibleName == "Font style — Type reference, TIL"
                    }
                assertTrue(style.isEnabled, "Confirmed and actuated Type reference styles must be available")
                style.doClick()
                button(popupContent.captured, "Bold").doClick()

                assertEquals("preview.tf", sample.sampleFileNameForTest())
                assertEquals(
                    "BOLD",
                    StudioSyntaxProjection.settings(requested.appearance).customEmphasis["TIL|TYPE_REF"],
                )
                section.content.preview(PreviewMode.DRAFT)
                assertEquals("preview.tf", sample.sampleFileNameForTest())
                section.content.preview(PreviewMode.CURRENT)
                assertEquals("preview.tf", sample.sampleFileNameForTest())
            } finally {
                Disposer.dispose(section)
            }
        }
    }

    @Test
    fun `readability and primitive groups reflow without clipping or replacing controls`() {
        SwingUtilities.invokeAndWait {
            val input = preview(Color(0x5CCFE6), Font.PLAIN)
            val events = mutableListOf<SyntaxPanelEvent>()
            val controls = SyntaxControls(input, events::add)
            try {
                controls.render(view(draft()), input)
                expand(customizationGroup(controls))
                val fields = descendants(controls).filterIsInstance<JSlider>()
                val labels =
                    descendants(controls).filterIsInstance<JLabel>().filter { label ->
                        PrimitiveCategory.entries.any { it.displayName == label.text }
                    }
                val toggles = descendants(controls).filterIsInstance<JCheckBox>()
                for (width in listOf(360, 760, 1200)) {
                    controls.setSize(width, 2200)
                    repeat(3) { layoutTree(controls) }
                    assertGeometry(controls, width, labels, fields, toggles)
                    assertEquals(fields, descendants(controls).filterIsInstance<JSlider>())
                }
                assertTrue(events.isEmpty())
            } finally {
                Disposer.dispose(controls)
            }
        }
    }

    @Test
    fun `shared controls retain complete familiar groups and expanded context without render commands`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<SyntaxPanelEvent>()
            val input = preview(Color(0x5CCFE6), Font.PLAIN)
            val controls = SyntaxControls(input, events::add)
            try {
                val initial = view(draft())
                controls.render(initial, input)
                val sample = descendants(controls).filterIsInstance<SyntaxPreviewComponent>().single()
                val details = customizationGroup(controls)
                expand(details)

                val labels = descendants(controls).filterIsInstance<JLabel>().map { it.text }
                assertTrue(labels.containsAll(PrimitiveCategory.entries.map { it.displayName }))
                val groups = descendants(controls).filterIsInstance<TitledSeparator>().map { it.text }
                val expectedGroups =
                    PrimitiveGroup.entries
                        .sortedWith(compareBy({ it.columnIndex }, { it.columnOrder }))
                        .map { it.displayName }
                assertEquals(expectedGroups, groups.filter { it in expectedGroups })
                assertEquals(
                    listOf("Dim comments", "Soften documentation", "Quiet operators", "Emphasize declarations"),
                    descendants(controls).filterIsInstance<AbstractButton>().map { it.text }.filter {
                        it in setOf("Dim comments", "Soften documentation", "Quiet operators", "Emphasize declarations")
                    },
                )
                assertEquals(PrimitiveCategory.entries.size, descendants(controls).filterIsInstance<JSlider>().size)
                val keywordReset =
                    descendants(controls).filterIsInstance<InplaceButton>().single {
                        it.accessibleContext.accessibleName == "Reset Keyword to default"
                    }
                assertTrue(keywordReset.isVisible, "Opaque style at a known cell must retain its existing reset action")
                assertTrue(button(controls, "Reset Java customizations").isVisible)
                assertEquals("FUTURE_STYLE", initial.settings.customStyles["Java|KEYWORD"])
                controls.render(initial.copy(language = "Kotlin", capability = confirmed("Kotlin")), input)
                controls.render(initial, input)

                assertSame(sample, descendants(controls).filterIsInstance<SyntaxPreviewComponent>().single())
                assertSame(details, customizationGroup(controls))
                assertTrue(descendants(controls).filterIsInstance<JSlider>().all { it.isVisible })
                assertTrue(events.isEmpty())
            } finally {
                Disposer.dispose(controls)
            }
        }
    }

    @Test
    fun `Current and Draft change a real native highlighter without touching either source or global scheme`() {
        lateinit var editor: EditorEx
        SwingUtilities.invokeAndWait {
            val manager = EditorColorsManager.getInstance()
            val global = manager.globalScheme
            val originalGlobal = requireNotNull(global.getAttributes(keyword())).clone()
            val current = preview(Color(0x5CCFE6), Font.PLAIN)
            val candidate = preview(Color(0xFFD580), Font.BOLD)
            val currentAttributes = requireNotNull(current.scheme.getAttributes(keyword())).clone()
            val candidateAttributes = requireNotNull(candidate.scheme.getAttributes(keyword())).clone()
            val staged = mutableListOf<List<SessionIntent>>()
            val actions = mutableListOf<SyntaxPanelEvent>()
            val section = StudioSyntaxSection(current, staged::add, actions::add)
            val initial = draft()
            try {
                section.render(initial, view(initial), current, candidate)
                val field = descendants(section.content.component).filterIsInstance<EditorTextField>().single()
                editor = requireNotNull(field.getEditor(true))
                val offset = editor.document.text.indexOf("public final class")
                assertTrue(offset >= 0, "The real Java sample must be loaded")
                assertEquals(
                    currentAttributes.foregroundColor,
                    editor.highlighter
                        .createIterator(offset)
                        .textAttributes.foregroundColor,
                )

                section.content.preview(PreviewMode.DRAFT)
                val shown = editor.highlighter.createIterator(offset).textAttributes
                assertEquals(candidateAttributes.foregroundColor, shown.foregroundColor)
                assertEquals(Font.BOLD, shown.fontType)
                assertNotSame(candidate.scheme, editor.colorsScheme)
                section.content.preview(PreviewMode.CURRENT)
                assertEquals(
                    currentAttributes.foregroundColor,
                    editor.highlighter
                        .createIterator(offset)
                        .textAttributes.foregroundColor,
                )
                assertNotSame(current.scheme, editor.colorsScheme)
                assertEquals(currentAttributes, current.scheme.getAttributes(keyword()))
                assertEquals(candidateAttributes, candidate.scheme.getAttributes(keyword()))
                assertSame(global, manager.globalScheme)
                assertEquals(originalGlobal, global.getAttributes(keyword()))
                assertTrue(staged.isEmpty())
                assertTrue(actions.isEmpty())
            } finally {
                Disposer.dispose(section)
            }
        }
        SwingUtilities.invokeAndWait { assertTrue(editor.isDisposed) }
    }

    @Test
    fun `unchanged samples retain native editor state while actions use the latest draft`() {
        SwingUtilities.invokeAndWait {
            val input = preview(Color(0x5CCFE6), Font.PLAIN)
            val initial = draft()
            val staged = mutableListOf<List<SessionIntent>>()
            val section = StudioSyntaxSection(input, staged::add) {}
            try {
                section.render(initial, view(initial), input, input)
                val field = descendants(section.content.component).filterIsInstance<EditorTextField>().single()
                val editor = requireNotNull(field.getEditor(true))
                val scheme = editor.colorsScheme
                val highlighter = editor.highlighter
                val latest = initial.copy(id = DraftId("latest-draft"))

                section.render(latest, view(latest), input, input)
                assertSame(scheme, editor.colorsScheme)
                assertSame(highlighter, editor.highlighter)
                button(section.content.component, "Dim comments").doClick()
                assertEquals(latest.id, assertIs<SessionIntent.EditField>(staged.single().single()).draft)

                val refreshed = preview(Color.MAGENTA, Font.PLAIN)
                section.render(latest, view(latest), refreshed, input)
                assertNotSame(highlighter, editor.highlighter)
                val offset = editor.document.text.indexOf("public final class")
                assertTrue(offset >= 0)
                assertEquals(
                    Color.MAGENTA,
                    editor.highlighter
                        .createIterator(offset)
                        .textAttributes.foregroundColor,
                )
            } finally {
                Disposer.dispose(section)
            }
        }
    }

    @Test
    fun `native edits stage one field while support recovery and readonly rendering preserve choices`() {
        SwingUtilities.invokeAndWait {
            val input = preview(Color(0x5CCFE6), Font.PLAIN)
            val initial = draft()
            val staged = mutableListOf<List<SessionIntent>>()
            val actions = mutableListOf<SyntaxPanelEvent>()
            val section = StudioSyntaxSection(input, staged::add, actions::add)
            try {
                val requested = view(initial)
                section.render(initial, requested, input, input)
                val readability = button(section.content.component, "Dim comments")
                readability.doClick()
                val edit = assertIs<SessionIntent.EditField>(staged.single().single())
                assertEquals(initial.id, edit.draft)
                assertEquals("dimComments", edit.address.field.name)

                section.render(initial, requested.copy(isEditable = false), input, input)
                assertFalse(readability.isEnabled)
                readability.doClick()
                assertEquals(1, staged.size)
                val unavailable = requested.copy(capability = SyntaxCapabilityState.SupportUnavailable("Java"))
                section.render(initial, unavailable, input, input)
                expand(customizationGroup(section.content.component))
                button(section.content.component, "Open Marketplace").doClick()

                assertEquals(listOf<SyntaxPanelEvent>(SyntaxPanelEvent.RecoverSupport), actions.toList())
                assertEquals(1, staged.size)
                assertEquals(requested.settings, StudioSyntaxProjection.settings(initial.appearance))
                assertTrue(descendants(section.content.component).filterIsInstance<JSlider>().none { it.isEnabled })
            } finally {
                Disposer.dispose(section)
            }
        }
    }

    private fun draft(): DraftRecord {
        val initial = sessionRecord(sessionDocument()).work.drafts.single()
        val selected =
            StudioSyntaxEditing
                .edits(
                    initial,
                    SyntaxPanelEvent.Preset(SyntaxPreset.CUSTOM),
                ).fold(initial) { current, intent ->
                    val edit = assertIs<SessionIntent.EditField>(intent)
                    StudioDraftPolicy.edit(current, edit.address, edit.value)
                }
        return StudioDraftPolicy.edit(
            selected,
            FieldAddress(LegacyFieldKey(LegacyStore.SYNTAX, "customStyles"), FieldPart.MapEntry("Java|KEYWORD")),
            FieldValue.Present(RawValue.Text("FUTURE_STYLE")),
        )
    }

    private fun view(draft: DraftRecord): SyntaxPanelView =
        SyntaxPanelView(
            settings = StudioSyntaxProjection.settings(draft.appearance),
            language = "Java",
            actuatedByLanguage =
                mapOf(
                    "Java" to PrimitiveCategory.entries.toSet(),
                    "Kotlin" to PrimitiveCategory.entries.toSet(),
                ),
            capability = confirmed("Java"),
            isEditable = true,
            isLicensed = true,
        )

    private fun confirmed(language: String): SyntaxCapabilityState =
        SyntaxCapabilityState.Confirmed(language, SyntaxCapabilityEvidence(language, PrimitiveCategory.entries.toSet()))

    private fun preview(
        color: Color,
        fontType: Int,
    ): SyntaxPreviewInput {
        val scheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        scheme.setAttributes(keyword(), TextAttributes(color, null, null, null, fontType))
        return SyntaxPreviewInput(AyuVariant.MIRAGE, scheme)
    }

    private fun keyword(): TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("JAVA_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

    private fun customizationGroup(container: Container): TitledSeparator =
        descendants(container).filterIsInstance<TitledSeparator>().single { it.text == "Language Customization" }

    private fun stubPopup(content: CapturingSlot<JComponent>) {
        mockkStatic(JBPopupFactory::class)
        val factory = mockk<JBPopupFactory>()
        val builder = mockk<ComponentPopupBuilder>()
        every { JBPopupFactory.getInstance() } returns factory
        every { factory.createComponentPopupBuilder(capture(content), any()) } returns builder
        every { builder.setTitle(any()) } returns builder
        every { builder.setRequestFocus(any()) } returns builder
        every { builder.setCancelOnClickOutside(any()) } returns builder
        every { builder.setCancelOnWindowDeactivation(any()) } returns builder
        every { builder.setMovable(any()) } returns builder
        every { builder.setResizable(any()) } returns builder
        every { builder.setCancelKeyEnabled(any()) } returns builder
        every { builder.createPopup() } returns mockk<JBPopup>(relaxed = true)
    }

    private fun expand(separator: TitledSeparator) {
        separator.dispatchEvent(
            MouseEvent(separator, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
        )
    }

    private fun assertGeometry(
        controls: SyntaxControls,
        width: Int,
        labels: List<JLabel>,
        fields: List<JSlider>,
        toggles: List<JCheckBox>,
    ) {
        val components =
            buildList {
                addAll(labels)
                addAll(fields)
                addAll(toggles)
            }
        components.forEach { component ->
            val location = SwingUtilities.convertPoint(component, 0, 0, controls)
            assertTrue(
                location.x >= 0 && location.x + component.width <= width,
                "${component.javaClass.simpleName} must fit at width $width",
            )
            assertTrue(
                component.width >= component.minimumSize.width,
                "Each control must retain its intrinsic minimum at width $width",
            )
        }
        labels.forEach { label ->
            assertTrue(label.width >= label.getFontMetrics(label.font).stringWidth(label.text))
        }
        val toggleRows = toggles.map { SwingUtilities.convertPoint(it, 0, 0, controls).y }.toSet()
        if (width == 1200) assertEquals(1, toggleRows.size)
        if (width == 360) assertTrue(toggleRows.size > 1)
    }

    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

    private fun button(
        container: Container,
        title: String,
    ): AbstractButton = descendants(container).filterIsInstance<AbstractButton>().single { it.text == title }

    private fun descendants(root: Container): List<Component> =
        buildList {
            for (child in root.components) {
                add(child)
                if (child is Container) addAll(descendants(child))
            }
        }
}
