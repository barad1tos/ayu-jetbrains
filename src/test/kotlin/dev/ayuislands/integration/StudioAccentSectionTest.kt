package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.accent.toolbar.popup.PopupSwatch
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AccentPanelAction
import dev.ayuislands.settings.AccentPanelContext
import dev.ayuislands.settings.AccentPanelGroup
import dev.ayuislands.settings.AccentPanelView
import dev.ayuislands.settings.AccentPreviewInput
import dev.ayuislands.settings.AccentSwatchGrid
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.MappingsContext
import dev.ayuislands.settings.mappings.MappingsControls
import dev.ayuislands.settings.mappings.MappingsObservations
import dev.ayuislands.settings.mappings.MappingsPanelView
import dev.ayuislands.settings.mappings.ProjectLanguageResolutionPanel
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioMappingsEditing
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.ui.StudioAccentSection
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioAccentSectionTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Before
    fun forbidAmbientSettings() {
        mockkObject(AyuIslandsSettings.Companion, AccentMappingsSettings.Companion, LicenseChecker)
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Accent render requires explicit fields")
        every { AccentMappingsSettings.getInstance() } throws AssertionError("Mappings belong to the session")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement belongs to the host view")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("Unlock must remain a host action")
    }

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun `Accent contributes one section with the complete familiar groups palette and mappings`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture()
            val content = fixture.section.content
            val groups = descendants(content.component).filterIsInstance<TitledSeparator>().map { it.text }.toList()

            assertEquals(StudioSettingsSection.ACCENT, content.section)
            assertEquals(7, StudioSettingsSection.entries.size)
            assertTrue(
                groups.containsAll(
                    listOf(
                        "Accent Color",
                        "Theme Synchronization",
                        "System",
                        "Overrides",
                        "Chrome Tinting",
                        "Quick Switcher",
                        "Accent Rotation",
                        "Accent Elements",
                    ),
                ),
            )
            assertEquals(1, descendants(content.component).filterIsInstance<AccentSwatchGrid>().count())
            assertEquals(12, descendants(content.component).filterIsInstance<PopupSwatch>().count())
            assertEquals(1, descendants(content.component).filterIsInstance<MappingsControls>().count())
            for (element in AccentElementId.entries.filter { it.group != AccentGroup.CHROME }) {
                assertTrue(button(content.component, element.displayName).text.isNotEmpty())
            }
            for (label in listOf(
                "Sync editor color scheme with active Ayu theme",
                "Follow system appearance (Light / Dark)",
                "Follow system accent color",
                "Show quick-switcher widget in main toolbar",
                "Enable accent rotation",
                "Highlight bracket scope on gutter",
                "Status bar",
                "Main toolbar",
                "Tool window stripe",
                "Navigation bar",
                "Panel borders",
                "Automatically pin an accent from the project icon",
                "Custom…",
                "Shuffle",
                "Reset accent to default…",
            )) {
                assertTrue(button(content.component, label).text.isNotEmpty())
            }
            assertTrue(
                descendants(content.component).filterIsInstance<JLabel>().any {
                    it.text == "Paused: using system accent"
                },
            )
            assertTrue(fixture.staged.isEmpty())
            assertTrue(fixture.actions.isEmpty())
        }
    }

    @Test
    fun `Current and Draft repaint the existing sample while Follow and shared policy stage separate intents`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture()
            val root = fixture.section.content.component
            val sample =
                descendants(root).filterIsInstance<JComponent>().single {
                    it.javaClass.simpleName == "AccentPreviewComponent"
                }
            sample.setSize(360, 220)
            val accepted = pixels(sample)
            fixture.section.content.preview(PreviewMode.DRAFT)
            assertFalse(accepted.contentEquals(pixels(sample)))
            fixture.section.content.preview(PreviewMode.CURRENT)
            assertTrue(accepted.contentEquals(pixels(sample)))
            assertTrue(fixture.staged.isEmpty())

            val follow = button(root, "Follow system accent color")
            assertTrue(follow.isEnabled, "A forced shared rule must not lock the stored profile choice")
            follow.doClick()
            val local = assertIs<SessionIntent.EditField>(fixture.staged.single().single())
            assertEquals("followSystemAccent", local.address.field.name)
            fixture.staged.clear()

            val policy =
                descendants(root).filterIsInstance<JComboBox<*>>().single {
                    it.itemCount == SystemAccentPolicy.entries.size && it.getItemAt(0) == SystemAccentPolicy.BY_PROFILE
                }
            policy.selectedItem = SystemAccentPolicy.FORCE_OFF
            assertEquals(
                SharedIntent.SystemPolicy(SystemAccentPolicy.FORCE_ON, SystemAccentPolicy.FORCE_OFF),
                assertIs<SessionIntent.Shared>(fixture.staged.single().single()).intent,
            )
            assertTrue(fixture.actions.isEmpty())
        }
    }

    @Test
    fun `readonly refresh retains disclosures unavailable toolbar and compact geometry`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture()
            val root = fixture.section.content.component
            val chrome = descendants(root).filterIsInstance<TitledSeparator>().single { it.text == "Chrome Tinting" }
            val mainToolbar = button(root, "Main toolbar")
            assertFalse(mainToolbar.isEnabled)
            assertTrue(mainToolbar.toolTipText.orEmpty().contains("Native title bar"))
            toggle(chrome)
            fixture.actions.clear()
            fixture.render(isEditable = false)
            assertSame(
                chrome,
                descendants(root).filterIsInstance<TitledSeparator>().single {
                    it.text ==
                        "Chrome Tinting"
                },
            )
            assertFalse(button(root, "Follow system accent color").isEnabled)
            assertFalse(button(root, "Enable accent rotation").isEnabled)
            assertFalse(visibleWithin(mainToolbar, root))
            fixture.render(isEditable = false, isIslandsUi = false)
            assertTrue(visibleWithin(button(root, "Sync with glow width"), root))
            assertTrue(
                descendants(root).filterIsInstance<JLabel>().any {
                    it.text == "Underline thickness" && visibleWithin(it, root)
                },
            )
            for (width in listOf(360, 760, 1200)) {
                root.setSize(width, root.preferredSize.height)
                layoutTree(root)
                val palette = descendants(root).filterIsInstance<AccentSwatchGrid>().single()
                val location = SwingUtilities.convertPoint(palette, 0, 0, root)
                assertTrue(location.x >= 0 && location.x + palette.width <= width)
                assertTrue(root.minimumSize.width <= width)
            }
            assertTrue(fixture.staged.isEmpty())
            assertTrue(fixture.actions.isEmpty())
        }
    }

    private fun fixture(): Fixture = Fixture().also { it.render() }

    private class Fixture {
        val document = sessionDocument()
        private val original = sessionRecord(document).work.drafts.single()
        val draft =
            original.copy(
                appearance =
                    original.appearance.copy(
                        accent =
                            original.appearance.accent.copy(
                                records =
                                    StudioList(
                                        original.appearance.accent.records.map {
                                            when (it.key.name) {
                                                "accentRotationEnabled" -> it.copy(value = RawValue.BooleanValue(true))
                                                "followSystemAccent" -> it.copy(value = RawValue.BooleanValue(false))
                                                else -> it
                                            }
                                        },
                                    ),
                            ),
                    ),
            )
        val shared = document.shared.copy(systemAccentPolicy = SystemAccentPolicy.FORCE_ON)
        val staged = mutableListOf<List<SessionIntent>>()
        val actions = mutableListOf<AccentPanelAction>()
        val section = StudioAccentSection(AccentPanelGroup.entries.toSet(), staged::add, actions::add)

        fun render(
            isEditable: Boolean = true,
            isIslandsUi: Boolean = true,
        ) {
            val view =
                AccentPanelView(
                    settings = StudioAccentEditing.settings(draft.appearance, shared),
                    context =
                        AccentPanelContext(
                            variant = AyuVariant.MIRAGE,
                            isSystemAppearanceSupported = true,
                            isSystemAccentSupported = true,
                            isIslandsUi = isIslandsUi,
                            isCustomHeaderActive = false,
                            toolbarUnavailableReason = "Native title bar",
                        ),
                    mappings = mappings(isEditable),
                    isEditable = isEditable,
                    isLicensed = true,
                )
            val elements = AccentElementId.entries.filter { it.group != AccentGroup.CHROME }.associateWith { true }
            section.render(
                draft,
                shared,
                view,
                AccentPreviewInput("#FFCC66", elements),
                AccentPreviewInput("#5CCFE6", elements),
            )
        }

        private fun mappings(isEditable: Boolean): MappingsPanelView =
            MappingsPanelView(
                StudioMappingsEditing.settings(shared.mappings),
                MappingsContext(
                    projectKey = "/work/current",
                    currentAccentHex = "#FFCC66",
                    canPinCurrent = true,
                    resolution =
                        ProjectLanguageResolutionPanel.State(
                            ProjectLanguageVerdict.Unavailable,
                            null,
                            null,
                            AccentResolver.Source.GLOBAL,
                            canMutate = isEditable,
                            canRescan = false,
                        ),
                ),
                MappingsObservations(),
                isEditable,
                isLicensed = true,
            )
    }

    private fun button(
        root: Container,
        text: String,
    ): AbstractButton = descendants(root).filterIsInstance<AbstractButton>().single { it.text == text }

    private fun toggle(separator: TitledSeparator) {
        separator.dispatchEvent(
            MouseEvent(separator, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
        )
    }

    private fun visibleWithin(
        component: Component,
        root: Container,
    ): Boolean {
        var current: Component? = component
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return true
    }

    private fun pixels(component: JComponent): IntArray {
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
        }
    }

    private fun descendants(component: Component): Sequence<Component> =
        sequence {
            yield(component)
            if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
        }
}
