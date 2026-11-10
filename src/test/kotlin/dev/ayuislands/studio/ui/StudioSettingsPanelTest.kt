package dev.ayuislands.studio.ui

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontWeight
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.settings.FontPreviewComponent
import dev.ayuislands.settings.GlowGroupPanel
import dev.ayuislands.settings.GlowPreview
import dev.ayuislands.settings.SettingsBadgeState
import dev.ayuislands.settings.SettingsBadges
import dev.ayuislands.settings.VcsColorPreviewComponent
import dev.ayuislands.settings.VcsPreviewIntensities
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.StudioSettingsView
import dev.ayuislands.studio.session.StudioProfileProjection
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionState
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSettingsPanelTest {
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
            fixture.panel.render(current)
            fixture.panel.render(current.copy(section = StudioSettingsSection.GLOW))
            val components = fixture.sections.map { it.component }
            val different =
                current.profiles.rows
                    .first { it.choice != current.profiles.selected }
                    .choice
            fixture.panel.render(
                current.copy(profiles = current.profiles.copy(selected = different, preview = PreviewMode.DRAFT)),
            )
            fixture.panel.render(current)

            assertTrue(fixture.events.selections.isEmpty())
            assertTrue(fixture.events.previews.isEmpty())
            assertTrue(fixture.events.navigation.isEmpty())
            assertEquals(StudioSettingsSection.entries.toSet(), fixture.rendered.keys)
            assertTrue(fixture.rendered.values.all { it.last() == PreviewMode.CURRENT })
            assertTrue(fixture.rendered.values.all { PreviewMode.DRAFT in it })
            assertTrue(
                fixture.rendered.values.all {
                    it == listOf(PreviewMode.CURRENT, PreviewMode.DRAFT, PreviewMode.CURRENT)
                },
            )
            assertEquals(components, fixture.sections.map { it.component })
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
            assertEquals(childOrder, fixture.sections.map { (it.component as JPanel).components.toList() })
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
    fun `profile and preview badges retire through accepted metadata and remain visible until close`(): Unit =
        onEdt {
            val ids = listOf("work-profiles", "settings-draft-preview")
            val state = SettingsBadgeState(lastSeenVersion = "2.8.9")
            state.acknowledgedSettingsBadges.addAll(SettingsBadges.registry.map { it.id }.filterNot { it in ids })
            val requests = mutableListOf<List<String>>()
            val first = fixture().panel
            val reopened = fixture().panel
            try {
                first.render(view().copy(section = StudioSettingsSection.WORKSPACE))
                first.refreshBadges({ state }, requests::add)
                val controls = descendants(first).filterIsInstance<StudioProfileControls>().single()
                val badges = descendants(controls).filterIsInstance<JLabel>()

                assertEquals(2, badges.count { it.text == "New" && it.isVisible })
                assertEquals(ids, requests.flatten())
                assertTrue(ids.all { SettingsBadges.isPending(state, it) })

                SettingsBadges.markSeen(state, requests.flatten())
                first.refreshBadges({ state }, requests::add)
                assertEquals(2, badges.count { it.text == "New" && it.isVisible })
                first.dispose()

                reopened.render(view())
                reopened.refreshBadges({ state }, requests::add)
                assertFalse(labels(reopened).any { it.text == "New" && it.isVisible })
                assertEquals(ids, requests.flatten())
            } finally {
                first.dispose()
                reopened.dispose()
                SettingsBadges.clearSessionWiring()
            }
        }

    private fun view(): StudioSettingsView {
        val document = sessionDocument()
        return StudioSettingsView(
            StudioProfileProjection.project(document, sessionState(document)),
            StudioSettingsSection.ACCENT,
            AuthorityStatus.READY,
            "#FFCD66",
        )
    }

    private fun fixture(): Fixture {
        val selections = mutableListOf<StudioProfileChoice>()
        val previews = mutableListOf<PreviewMode>()
        val navigation = mutableListOf<StudioSettingsSection>()
        val rendered = mutableMapOf<StudioSettingsSection, MutableList<PreviewMode>>()
        val groups = mutableMapOf<StudioSettingsSection, DetailGroup>()
        val font = FontPreviewComponent()
        val vcs = VcsColorPreviewComponent(AyuVariant.MIRAGE)
        val glow = GlowGroupPanel()
        val sections =
            StudioSettingsSection.entries.map { section ->
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
                    rendered.getOrPut(section) { mutableListOf() }.add(mode)
                    updatePreview(sample, mode)
                }
            }
        val panel = StudioSettingsPanel(sections.reversed(), selections::add, previews::add, navigation::add)
        return Fixture(
            panel,
            sections,
            Events(selections, previews, navigation),
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
    )

    private data class DetailGroup(
        val disclosure: JToggleButton,
        val details: JPanel,
    )

    private fun tabs(panel: StudioSettingsPanel): JTabbedPane =
        descendants(panel).filterIsInstance<JTabbedPane>().single()

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
