package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPanelPreview
import dev.ayuislands.font.FontPanelResources
import dev.ayuislands.font.FontPanelSelection
import dev.ayuislands.font.FontPanelView
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontTerminalAction
import dev.ayuislands.font.FontUninstaller
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.FontControls
import dev.ayuislands.settings.FontPreviewComponent
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioFontEditing
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.ui.StudioFontSection
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioFontSectionTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Before
    fun forbidAmbientEffects() {
        mockkObject(AyuIslandsSettings.Companion, FontDetector, FontInstaller, FontUninstaller)
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Font controls must use explicit view data")
        every { FontDetector.resolveFamily(any()) } throws AssertionError("Preview must use the observed family")
        every { FontDetector.detectAll() } throws AssertionError("Font controls must not perform a native census")
        every { FontDetector.status(any()) } throws AssertionError("Availability must be supplied explicitly")
        every { FontDetector.isFamilyInstalled(any()) } throws
            AssertionError("Availability must be supplied explicitly")
        every { FontDetector.listMonospaceFonts() } throws AssertionError("Font choices must be supplied explicitly")
        every { FontInstaller.installResource(any(), any(), any(), any()) } throws
            AssertionError("Install belongs to the host")
        every { FontUninstaller.uninstallResource(any(), any(), any(), any(), any()) } throws
            AssertionError("Delete belongs to the host")
        every { FontDetector.invalidateCache() } throws AssertionError("Rendering must not mutate font discovery")
    }

    @After
    fun cleanup() {
        SwingUtilities.invokeAndWait {}
        unmockkAll()
    }

    @Test
    fun `shared font controls retain familiar controls and native preview without dispatching during render`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<FontPanelEvent>()
            val controls = FontControls(events::add)
            controls.render(view())
            val sample = descendants(controls).filterIsInstance<FontPreviewComponent>().single()

            assertTrue(button(controls, "Apply font preset").isSelected)
            assertTrue(labels(controls).containsAll(listOf("Preset", "Size:", "Spacing:", "Weight:")))
            assertTrue(descendants(controls).any { it.accessibleContext?.accessibleName == "Customize" })
            assertTrue(button(controls, "Ligatures").isSelected)
            assertTrue(button(controls, "Also apply to console").isSelected)
            assertTrue(button(controls, "Reapply preset").isEnabled)
            val expectedPresets = FontPreset.entries.map { it.displayName }
            assertEquals(
                expectedPresets,
                descendants(
                    controls,
                ).mapNotNull { it.accessibleContext?.accessibleName }.filter { it in expectedPresets },
            )
            controls.render(view().copy(isEnabled = false))
            controls.render(view())
            assertSame(sample, descendants(controls).filterIsInstance<FontPreviewComponent>().single())
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `explicit current and draft sample inputs change native typography without accepting a setting`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<FontPanelEvent>()
            val controls = FontControls(events::add)
            val initial = view()
            controls.render(initial)
            val sample = descendants(controls).filterIsInstance<FontPreviewComponent>().single()
            val before = sample.preferredSize.height
            val draft = requireNotNull(initial.preview)
            controls.render(initial.copy(preview = draft.copy(settings = draft.settings.copy(fontSize = 27f))))

            assertTrue(sample.preferredSize.height > before)
            controls.render(initial)
            assertEquals(before, sample.preferredSize.height)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `native editing and install buttons emit explicit events while unavailable family and raw size stay visible`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<FontPanelEvent>()
            val controls = FontControls(events::add)
            val custom =
                FontSettings
                    .fromPreset(
                        FontPreset.CUSTOM,
                    ).copy(fontFamily = "Unavailable Mono", fontSize = 42.5f)
            val initial =
                view().copy(
                    selection = FontPanelSelection("CUSTOM", custom),
                    preview = FontPanelPreview(custom, custom.fontFamily, false),
                )
            controls.render(initial)
            assertTrue(
                descendants(controls).filterIsInstance<JComboBox<*>>().any {
                    it.selectedItem ==
                        "Unavailable Mono"
                },
            )
            val size =
                descendants(controls).filterIsInstance<JSpinner>().single {
                    (it.value as Number).toDouble() ==
                        42.5
                }
            assertTrue(events.isEmpty())
            size.value = 20
            assertEquals(listOf<FontPanelEvent>(FontPanelEvent.Size(20f)), events.toList())

            val curated = view()
            controls.render(
                curated.copy(resources = curated.resources.copy(availability = FontAvailability.MISSING_CURATED)),
            )
            button(controls, "Install automatically").doClick()
            assertEquals(FontPanelEvent.Resource(FontPreset.AMBIENT, FontAction.INSTALL), events.last())
        }
    }

    @Test
    fun `Studio binding stages font and shared intents while resource actions remain explicit host callbacks`() {
        SwingUtilities.invokeAndWait {
            val document = sessionDocument()
            var draft = sessionRecord(document).work.drafts.single()
            for (event in listOf(FontPanelEvent.Enable(true), FontPanelEvent.Size(26f))) {
                for (intent in StudioFontEditing.edits(draft, event)) {
                    val edit = assertIs<SessionIntent.EditField>(intent)
                    draft = StudioDraftPolicy.edit(draft, edit.address, edit.value)
                }
            }
            val shared =
                document.shared.system.records
                    .single { it.key.name == "fontInstallTerminal" }
            val selection = StudioFontProjection.selection(draft.appearance)
            val settings = requireNotNull(selection.settings)
            val current = FontPanelPreview(settings.copy(fontSize = 13f), "Monospaced", true)
            val draftView =
                view().copy(
                    selection = selection,
                    preview = current.copy(settings = settings),
                    resources = view().resources.copy(terminal = (shared.value as? RawValue.Text)?.value),
                )
            val staged = mutableListOf<List<SessionIntent>>()
            val resources = mutableListOf<FontPanelEvent.Resource>()
            val manual = mutableListOf<FontTerminalAction>()
            val section = StudioFontSection(staged::add, resources::add, manual::add)
            section.render(draft, draftView, current, shared)
            val sample = descendants(section.content.component).filterIsInstance<FontPreviewComponent>().single()
            val currentHeight = sample.preferredSize.height
            section.content.preview(PreviewMode.DRAFT)
            assertTrue(sample.preferredSize.height > currentHeight)
            assertTrue(staged.isEmpty())
            val size =
                descendants(section.content.component)
                    .filterIsInstance<JSpinner>()
                    .single { (it.value as Number).toDouble() == 26.0 }
            size.value = 22
            val fontEdit = assertIs<SessionIntent.EditField>(staged.single().single())
            assertEquals(draft.id, fontEdit.draft)
            assertEquals("fontPresetCustomizations", fontEdit.address.field.name)

            section.render(
                draft,
                draftView.copy(
                    resources = draftView.resources.copy(isMac = true, availability = FontAvailability.MISSING_CURATED),
                ),
                current,
                shared,
            )
            val terminal =
                descendants(section.content.component)
                    .filterIsInstance<JComboBox<*>>()
                    .single { it.itemCount > 0 && it.getItemAt(0) == "Built-in Terminal" }
            terminal.selectedIndex = 1
            val sharedEdit = assertIs<SharedIntent.Field>(assertIs<SessionIntent.Shared>(staged.last().single()).intent)
            assertEquals(shared, sharedEdit.before)
            assertEquals(RawValue.Text("SYSTEM"), sharedEdit.after.value)
            button(section.content.component, "Install automatically").doClick()
            assertEquals(listOf(FontPanelEvent.Resource(FontPreset.AMBIENT, FontAction.INSTALL)), resources.toList())
            assertTrue(manual.isEmpty())
        }
    }

    private fun view(): FontPanelView {
        val settings = FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true)
        return FontPanelView(
            selection = FontPanelSelection("AMBIENT", settings),
            isEnabled = true,
            preview = FontPanelPreview(settings, "Monospaced", true),
            resources =
                FontPanelResources(
                    availability = FontAvailability.AVAILABLE,
                    installedFamilies = listOf("Monospaced", "Personal Mono"),
                    isMac = false,
                    terminal = "BUILTIN",
                    canManage = true,
                ),
            isEditable = true,
        )
    }

    private fun button(
        container: Container,
        title: String,
    ): AbstractButton = descendants(container).filterIsInstance<AbstractButton>().single { it.text == title }

    private fun labels(container: Container): List<String> =
        descendants(container).filterIsInstance<JLabel>().map {
            it.text
        }

    private fun descendants(container: Container): List<Component> =
        container.components.flatMap { child ->
            listOf(child) +
                if (child is Container) descendants(child) else emptyList()
        }
}
