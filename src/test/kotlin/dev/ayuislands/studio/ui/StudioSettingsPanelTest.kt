package dev.ayuislands.studio.ui

import com.intellij.ui.components.ActionLink
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontWeight
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.settings.FontPreviewComponent
import dev.ayuislands.settings.GlowGroupPanel
import dev.ayuislands.settings.GlowPreview
import dev.ayuislands.settings.VcsColorPreviewComponent
import dev.ayuislands.settings.VcsPreviewIntensities
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.catalogTestWork
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileKind
import dev.ayuislands.studio.model.StudioProfileLabel
import dev.ayuislands.studio.model.StudioProfileRow
import dev.ayuislands.studio.model.StudioProfileView
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.StudioSettingsView
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioAccentEditing
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSettingsPanelTest {
    @Test
    fun `the native Accent tab follows Current and Draft from the shell`(): Unit =
        onEdt {
            val document = catalogTestDocument()
            val draft = catalogTestWork(document).drafts.single()
            val accent =
                StudioAccentSection(
                    onDraftChanged = { error("Render must not dispatch edits") },
                    chooseCustom = { null },
                    shuffleColor = { error("Render must not shuffle") },
                )
            accent.render(
                StudioAccentView(
                    AyuVariant.MIRAGE,
                    StudioAccentEditing.choose(draft, AyuVariant.MIRAGE, AccentHex.require("#5CCFE6")),
                    document.currents.global.appearance,
                    SystemAccentPolicy.BY_PROFILE,
                    isEditable = true,
                ),
            )
            val fixture = fixture(accent = accent.content)
            val current = view()
            fixture.panel.render(current)
            assertTrue(descendants(fixture.panel).contains(accent.content.component))
            assertTrue(labels(fixture.panel).any { it.text == "Global accent sample · #FFCC66" })

            fixture.panel.render(current.copy(profiles = current.profiles.copy(preview = PreviewMode.DRAFT)))
            assertTrue(labels(fixture.panel).any { it.text == "Global accent sample · #5CCFE6" })
            assertTrue(fixture.events.previews.isEmpty())
        }

    @Test
    fun `shell keeps the brand one picker seven familiar tabs and one pending status`(): Unit =
        onEdt {
            val fixture = fixture()
            fixture.panel.render(view())

            assertEquals(
                listOf("Accent", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
                tabTitles(fixture.panel),
            )
            assertEquals(1, descendants(fixture.panel).filterIsInstance<JComboBox<*>>().size)
            assertEquals(1, labels(fixture.panel).count { it.text == "Ayu Islands" })
            assertTrue(labels(fixture.panel).single { it.text == "Ayu Islands" }.icon.iconWidth > 0)
            assertEquals(1, labels(fixture.panel).count { it.text == "v3.0.0" })
            assertEquals(1, labels(fixture.panel).count { it.text == "No pending changes" })
            assertEquals(1, descendants(fixture.panel).filterIsInstance<StudioProfileControls>().size)
            assertFalse(labels(fixture.panel).any { it.text == "Quick Switcher" })
        }

    @Test
    fun `render updates existing native previews without issuing selection or navigation commands`(): Unit =
        onEdt {
            val fixture = fixture()
            val current = view()
            fixture.panel.render(current)
            val acceptedSamples = fixture.samples.mapValues { (_, component) -> previewPixels(component) }
            fixture.panel.render(current)
            fixture.panel.render(current.copy(section = StudioSettingsSection.GLOW))
            val different =
                current.profiles.rows
                    .first { it.choice != current.profiles.selected }
                    .choice
            fixture.panel.render(
                current.copy(profiles = current.profiles.copy(selected = different, preview = PreviewMode.DRAFT)),
            )
            val draftSamples = fixture.samples.mapValues { (_, component) -> previewPixels(component) }
            fixture.panel.render(current)
            for ((section, component) in fixture.samples) {
                val accepted = acceptedSamples.getValue(section)
                assertFalse(accepted.contentEquals(draftSamples.getValue(section)), "$section did not show its draft")
                assertTrue(accepted.contentEquals(previewPixels(component)), "$section did not return to Current")
            }

            assertTrue(fixture.events.selections.isEmpty())
            assertTrue(fixture.events.previews.isEmpty())
            assertTrue(fixture.events.navigation.isEmpty())
            assertEquals(StudioSettingsSection.entries.toSet(), fixture.rendered.keys)
            assertTrue(
                fixture.rendered.values.all {
                    it == listOf(PreviewMode.CURRENT, PreviewMode.DRAFT, PreviewMode.CURRENT)
                },
            )
            assertSame(
                fixture.samples.getValue(StudioSettingsSection.FONT),
                descendants(fixture.panel).filterIsInstance<FontPreviewComponent>().single(),
            )
            assertSame(
                fixture.samples.getValue(StudioSettingsSection.VCS),
                descendants(fixture.panel).filterIsInstance<VcsColorPreviewComponent>().single(),
            )
            assertSame(
                fixture.samples.getValue(StudioSettingsSection.GLOW),
                descendants(fixture.panel).filterIsInstance<GlowGroupPanel>().single(),
            )
        }

    @Test
    fun `retry after a failed preview refresh updates every section`(): Unit =
        onEdt {
            var shouldFail = true
            val fixture =
                fixture { section, mode ->
                    if (shouldFail && section == StudioSettingsSection.GLOW && mode == PreviewMode.DRAFT) {
                        shouldFail = false
                        error("Glow preview failed")
                    }
                }
            val current = view()
            val draft = current.copy(profiles = current.profiles.copy(preview = PreviewMode.DRAFT))
            fixture.panel.render(current)
            assertFailsWith<IllegalStateException> { fixture.panel.render(draft) }
            fixture.panel.render(draft)
            assertTrue(fixture.rendered.values.all { it.last() == PreviewMode.DRAFT })
        }

    @Test
    fun `tab switching and profile refresh preserve native content identity order and disclosure state`(): Unit =
        onEdt {
            val fixture = fixture()
            val initial = view()
            fixture.panel.render(initial)
            val tabs = tabs(fixture.panel)
            val original = (0 until tabs.tabCount).map(tabs::getComponentAt)
            val childOrder = fixture.sections.map { (it.component as JPanel).components.toList() }
            val disclosure = fixture.groups.getValue(StudioSettingsSection.ACCENT).disclosure
            disclosure.doClick()

            tabs.selectedIndex = StudioSettingsSection.GLOW.ordinal
            fixture.panel.render(
                initial.copy(
                    section = StudioSettingsSection.GLOW,
                    profiles = initial.profiles.copy(preview = PreviewMode.DRAFT),
                ),
            )
            tabs.selectedIndex = StudioSettingsSection.ACCENT.ordinal

            assertEquals(listOf(StudioSettingsSection.GLOW, StudioSettingsSection.ACCENT), fixture.events.navigation)
            assertEquals(original, (0 until tabs.tabCount).map(tabs::getComponentAt))
            for ((index, section) in fixture.sections.withIndex()) {
                val shown = descendants(tabs.getComponentAt(index) as Container)
                assertTrue(shown.any { it === section.component }, "Missing original ${section.section} body")
                assertEquals(childOrder[index], (section.component as JPanel).components.toList())
            }
            assertTrue(disclosure.isSelected)
            assertTrue(
                fixture.groups
                    .getValue(StudioSettingsSection.ACCENT)
                    .details.isVisible,
            )
            assertTrue(fixture.events.selections.isEmpty())
        }

    @Test
    fun `restored selected section and shared caption are presentation only`(): Unit =
        onEdt {
            val fixture = fixture()
            fixture.panel.render(view().copy(section = StudioSettingsSection.WORKSPACE))

            assertEquals(StudioSettingsSection.WORKSPACE.ordinal, tabs(fixture.panel).selectedIndex)
            assertTrue(labels(fixture.panel).any { it.text == "Shared across profiles" && it.isVisible })
            assertTrue(fixture.events.navigation.isEmpty())
            fixture.panel.render(view().copy(section = StudioSettingsSection.FONT))
            assertEquals(StudioSettingsSection.FONT.ordinal, tabs(fixture.panel).selectedIndex)
            assertFalse(labels(fixture.panel).any { it.text == "Shared across profiles" && it.isVisible })
            assertTrue(fixture.events.navigation.isEmpty())
        }

    @Test
    fun `read only and recovery status reuse explicit control permissions without semantic commands`(): Unit =
        onEdt {
            val fixture = fixture()
            val initial = view()
            val profiles =
                initial.profiles.copy(
                    rows = StudioList(initial.profiles.rows.map { it.copy(isEnabled = false) }),
                )
            fixture.panel.render(initial.copy(profiles = profiles, authority = AuthorityStatus.READ_ONLY))
            assertTrue(labels(fixture.panel).any { it.text == "Read-only settings" })
            assertFalse(descendants(fixture.panel).filterIsInstance<JComboBox<*>>().single().isEnabled)
            fixture.panel.render(initial.copy(profiles = profiles, authority = AuthorityStatus.RECOVERY))
            assertTrue(labels(fixture.panel).any { it.text == "Recovery required" })
            assertTrue(
                descendants(fixture.panel).filterIsInstance<JToggleButton>().single { it.text == "Draft" }.isEnabled,
            )
            assertTrue(fixture.events.selections.isEmpty())
            assertTrue(fixture.events.previews.isEmpty())
            assertTrue(fixture.events.navigation.isEmpty())
        }

    @Test
    fun `accepted badge refresh keeps visible row hints until the dialog reopens`(): Unit =
        onEdt {
            val first = fixture().panel
            first.render(view().copy(pendingBadgeIds = StudioList(listOf("work-profiles", "settings-draft-preview"))))
            assertEquals(2, labels(first).count { it.text == "New" && it.isVisible })

            first.render(view())
            assertEquals(2, labels(first).count { it.text == "New" && it.isVisible })

            val reopened = fixture().panel
            reopened.render(view())
            assertFalse(labels(reopened).any { it.text == "New" && it.isVisible })
        }

    @Test
    fun `community actions are sent to the host only when clicked`(): Unit =
        onEdt {
            val fixture = fixture()
            fixture.panel.render(view())
            assertTrue(fixture.events.community.isEmpty())

            val links = descendants(fixture.panel).filterIsInstance<ActionLink>()
            links.single { it.text == "Share Your Setup" }.doClick()
            links.single { it.text == "Request a Feature" }.doClick()

            assertEquals(
                listOf(StudioCommunityLink.SHARE_SETUP, StudioCommunityLink.REQUEST_FEATURE),
                fixture.events.community,
            )
        }

    private fun view(): StudioSettingsView {
        val whisper = StudioProfileChoice.Profile(ProfileId("whisper"))
        val ambient = StudioProfileChoice.Profile(ProfileId("ambient"))
        val rows =
            listOf(whisper to "Whisper", ambient to "Ambient").map { (choice, name) ->
                StudioProfileRow(
                    choice,
                    StudioProfileLabel(name),
                    StudioProfileKind.FACTORY,
                    StudioList(emptyList()),
                    isCurrent = choice == whisper,
                    isEnabled = true,
                )
            }
        return StudioSettingsView(
            StudioProfileView(
                StudioList(rows),
                whisper,
                "Whisper",
                PreviewMode.CURRENT,
                isPreviewEnabled = true,
                hasPendingChanges = false,
            ),
            StudioSettingsSection.ACCENT,
            AuthorityStatus.READY,
            AccentHex.require("#FFCD66"),
            StudioList(emptyList()),
        )
    }

    private fun fixture(
        accent: StudioSectionContent? = null,
        beforePreview: (StudioSettingsSection, PreviewMode) -> Unit = { _, _ -> },
    ): Fixture {
        val selections = mutableListOf<StudioProfileChoice>()
        val previews = mutableListOf<PreviewMode>()
        val navigation = mutableListOf<StudioSettingsSection>()
        val community = mutableListOf<StudioCommunityLink>()
        val rendered = mutableMapOf<StudioSettingsSection, MutableList<PreviewMode>>()
        val groups = mutableMapOf<StudioSettingsSection, DetailGroup>()
        val font = FontPreviewComponent()
        val vcs = VcsColorPreviewComponent(AyuVariant.MIRAGE)
        val glow = GlowGroupPanel()
        val sections =
            StudioSettingsSection.entries.map { section ->
                if (section == StudioSettingsSection.ACCENT && accent != null) return@map accent
                val sample =
                    when (section) {
                        StudioSettingsSection.FONT -> font
                        StudioSettingsSection.VCS -> vcs
                        StudioSettingsSection.GLOW -> glow
                        else -> JPanel()
                    }
                val detail =
                    JPanel().apply {
                        add(JCheckBox("Retained option", true))
                        isVisible = false
                    }
                val disclosure =
                    JToggleButton("More settings").apply {
                        addActionListener { detail.isVisible = isSelected }
                    }
                groups[section] = DetailGroup(disclosure, detail)
                val body =
                    JPanel(BorderLayout()).apply {
                        add(sample, BorderLayout.NORTH)
                        add(disclosure, BorderLayout.CENTER)
                        add(detail, BorderLayout.SOUTH)
                    }
                StudioSectionContent(section, body) { mode ->
                    beforePreview(section, mode)
                    rendered.getOrPut(section) { mutableListOf() }.add(mode)
                    updatePreview(sample, mode)
                }
            }
        val panel =
            StudioSettingsPanel(
                sections.reversed(),
                "3.0.0",
                selections::add,
                previews::add,
                navigation::add,
                community::add,
            )
        return Fixture(
            panel,
            sections,
            Events(selections, previews, navigation, community),
            rendered,
            groups,
            mapOf(
                StudioSettingsSection.FONT to font,
                StudioSettingsSection.VCS to vcs,
                StudioSettingsSection.GLOW to glow,
            ),
        )
    }

    private fun updatePreview(
        component: JComponent,
        mode: PreviewMode,
    ) {
        val isDraft = mode == PreviewMode.DRAFT
        when (component) {
            is FontPreviewComponent -> {
                component.updateSettings(
                    if (isDraft) 18f else 13f,
                    1.3f,
                    true,
                    FontWeight.REGULAR,
                )
            }

            is VcsColorPreviewComponent -> {
                component.updatePreview(
                    AyuVariant.MIRAGE,
                    VcsPreviewIntensities(diffViewer = if (isDraft) 80 else 20),
                )
            }

            is GlowGroupPanel -> {
                component.updatePreview(
                    GlowPreview(
                        GlowShape.SOLID,
                        GlowStyle.SOFT,
                        if (isDraft) 80 else 20,
                        2,
                        Color.ORANGE,
                        true,
                        WaveformConfig(),
                    ),
                )
            }
        }
    }

    private data class Fixture(
        val panel: StudioSettingsPanel,
        val sections: List<StudioSectionContent>,
        val events: Events,
        val rendered: Map<StudioSettingsSection, List<PreviewMode>>,
        val groups: Map<StudioSettingsSection, DetailGroup>,
        val samples: Map<StudioSettingsSection, JComponent>,
    )

    private data class Events(
        val selections: List<StudioProfileChoice>,
        val previews: List<PreviewMode>,
        val navigation: List<StudioSettingsSection>,
        val community: List<StudioCommunityLink>,
    )

    private data class DetailGroup(
        val disclosure: JToggleButton,
        val details: JPanel,
    )

    private fun tabs(panel: StudioSettingsPanel): JTabbedPane =
        descendants(panel).filterIsInstance<JTabbedPane>().single()

    private fun previewPixels(component: JComponent): IntArray {
        val size = component.preferredSize
        component.size = size
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, size.width, size.height, null, 0, size.width)
    }

    private fun tabTitles(panel: StudioSettingsPanel): List<String> =
        tabs(panel).let { pane -> (0 until pane.tabCount).map(pane::getTitleAt) }

    private fun labels(panel: StudioSettingsPanel): List<JLabel> = descendants(panel).filterIsInstance<JLabel>()

    private fun descendants(container: Container): List<Component> =
        container.components.flatMap { child ->
            listOf(child) +
                if (child is Container) descendants(child) else emptyList()
        }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
}
