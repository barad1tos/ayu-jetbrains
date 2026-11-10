package dev.ayuislands.integration

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.CommonActionsPanel
import com.intellij.ui.TitledSeparator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.MappingRow
import dev.ayuislands.settings.mappings.MappingsContext
import dev.ayuislands.settings.mappings.MappingsControls
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsObservations
import dev.ayuislands.settings.mappings.MappingsPanelAction
import dev.ayuislands.settings.mappings.MappingsPanelSettings
import dev.ayuislands.settings.mappings.MappingsPanelView
import dev.ayuislands.settings.mappings.MappingsTable
import dev.ayuislands.settings.mappings.ProjectLanguageResolutionPanel
import dev.ayuislands.settings.mappings.ProjectMapping
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioMappingsEditing
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.ui.StudioMappingsSection
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioMappingsSectionTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Before
    fun forbidAmbientEffects() {
        mockkObject(
            AyuIslandsSettings.Companion,
            AccentMappingsSettings.Companion,
            LicenseChecker,
            ProjectLanguageDetector,
        )
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Controls require explicit settings")
        every { AccentMappingsSettings.getInstance() } throws AssertionError("Mapping state belongs to the host")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement must be supplied explicitly")
        every { ProjectLanguageDetector.verdict(any(), warmCache = any()) } throws
            AssertionError("Source and diagnostics must share the host observation")
        every { ProjectLanguageDetector.dominant(any()) } throws AssertionError("Rendering must not warm detection")
        every { ProjectLanguageDetector.rescan(any()) } throws AssertionError("Rescan must be dispatched to the host")
    }

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun `rerender retains table identity selected keys column order card scroll and disclosure`() {
        SwingUtilities.invokeAndWait {
            val actions = mutableListOf<MappingsPanelAction>()
            val edits = mutableListOf<MappingsEdit>()
            val controls = MappingsControls(initialExpanded = true, actions::add, edits::add)
            val initial = view()
            controls.render(initial)
            controls.setSize(760, 900)
            layoutTree(controls)
            val projects = table(controls, MappingsTable.PROJECTS)
            val languages = table(controls, MappingsTable.LANGUAGES)
            val columns = projects.columnModel
            val pathColumn = columns.getColumn(2)
            columns.moveColumn(2, 0)
            pathColumn.preferredWidth = 173
            projects.setRowSelectionInterval(7, 7)
            languages.setRowSelectionInterval(4, 4)
            val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, languages) as JScrollPane
            languageButton(controls).doClick()
            scroll.viewport.viewPosition = Point(0, languages.rowHeight * 2)
            val position = scroll.viewport.viewPosition
            val disclosure = descendants(controls).filterIsInstance<TitledSeparator>().single { it.text == "Overrides" }
            toggle(disclosure)
            actions.clear()
            edits.clear()

            controls.render(
                initial.copy(settings = initial.settings.copy(projects = initial.settings.projects.reversed())),
            )
            controls.render(initial)
            layoutTree(controls)

            assertSame(projects, table(controls, MappingsTable.PROJECTS))
            assertSame(languages, table(controls, MappingsTable.LANGUAGES))
            assertSame(columns, projects.columnModel)
            assertSame(pathColumn, projects.columnModel.getColumn(0))
            assertEquals(173, pathColumn.preferredWidth)
            assertEquals("/work/project-7", projects.getValueAt(projects.selectedRow, 0))
            assertEquals("Language 4", languages.getValueAt(languages.selectedRow, 1))
            assertTrue(languageButton(controls).isSelected)
            assertFalse(visibleWithin(languages, controls), "Rendering must not reopen the disclosure")
            toggle(disclosure)
            layoutTree(controls)
            assertEquals(position, scroll.viewport.viewPosition)
            assertEquals(listOf<MappingsPanelAction>(MappingsPanelAction.Expanded(true)), actions)
            assertTrue(edits.isEmpty())
        }
    }

    @Test
    fun `installed native edit shortcut addresses the selected raw key and late license loss blocks it`() {
        SwingUtilities.invokeAndWait {
            val actions = mutableListOf<MappingsPanelAction>()
            val edits = mutableListOf<MappingsEdit>()
            val controls = MappingsControls(initialExpanded = true, actions::add, edits::add)
            val initial = view()
            controls.render(initial)
            withVisible(controls) {
                val projects = table(controls, MappingsTable.PROJECTS)
                projects.setRowSelectionInterval(3, 3)
                val enter = KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null)
                val edit = ActionUtil.getActions(projects).single { enter in it.shortcutSet.shortcuts }
                perform(edit, projects, KeyEvent.VK_ENTER)
                assertEquals(
                    listOf<MappingsPanelAction>(
                        MappingsPanelAction.EditColor(MappingsTable.PROJECTS, "/work/project-3"),
                    ),
                    actions,
                )
                assertTrue(edits.isEmpty())
                actions.clear()
                controls.render(initial.copy(isLicensed = false))
                val stale = event(edit, projects, KeyEvent.VK_ENTER)
                ActionUtil.performDumbAwareUpdate(edit, stale, false)
                assertFalse(stale.presentation.isEnabled)
                ActionUtil.performActionDumbAwareWithCallbacks(edit, stale)
                assertTrue(actions.isEmpty())
                assertTrue(edits.isEmpty())
            }
        }
    }

    @Test
    fun `Studio routes completed mapping edits through shared intents and leaves dialogs to the host`() {
        SwingUtilities.invokeAndWait {
            val original = fields()
            val staged = mutableListOf<List<SessionIntent>>()
            val actions = mutableListOf<MappingsPanelAction>()
            val section = StudioMappingsSection(initialExpanded = true, staged::add, actions::add)
            val initial = view().copy(settings = StudioMappingsEditing.settings(original))
            section.render(original, initial)
            withVisible(section.component) {
                val projects = table(section.component, MappingsTable.PROJECTS)
                val toolbar = toolbar(projects)
                val add = assertNotNull(toolbar.getAnAction(CommonActionsPanel.Buttons.ADD))
                perform(add, projects)
                assertEquals(listOf<MappingsPanelAction>(MappingsPanelAction.Add(MappingsTable.PROJECTS)), actions)
                assertTrue(staged.isEmpty())

                section.accept(MappingsEdit.AddProject(ProjectMapping("/work/added", "Added", "#73D0FF")))
                val changes =
                    staged.single().map {
                        assertIs<SharedIntent.Field>(
                            assertIs<SessionIntent.Shared>(it).intent,
                        )
                    }
                assertEquals(listOf("projectAccents", "projectDisplayNames"), changes.map { it.after.key.name })
                changes.forEach {
                    assertEquals(
                        original.records.single { field ->
                            field.key == it.before.key
                        },
                        it.before,
                    )
                }
                assertEquals(2, projects.rowCount, "Stage requests do not make controls a second settings authority")

                staged.clear()
                actions.clear()
                section.render(original, initial.copy(isEditable = false))
                section.accept(MappingsEdit.Remove(MappingsTable.PROJECTS, "/work/alpha"))
                ActionUtil.performActionDumbAwareWithCallbacks(add, event(add, projects))
                assertTrue(staged.isEmpty())
                assertTrue(actions.isEmpty())
            }
        }
    }

    @Test
    fun `raw rows remain literal and compact while diagnostics use supplied source and language labels`() {
        SwingUtilities.invokeAndWait {
            val controls =
                MappingsControls(
                    initialExpanded = true,
                    { error("Render emitted an action") },
                    { error("Render emitted an edit") },
                )
            val rawName = "<html>Проєкт & збережена назва з довгим продовженням</html>"
            val initial =
                view().copy(
                    settings =
                        MappingsPanelSettings(
                            projects = listOf(MappingRow("/work/raw", rawName, "future-color")),
                            languages = listOf(MappingRow("TypeScript", null, " #112233 ")),
                        ),
                    observations = MappingsObservations(languageNames = mapOf("kotlin" to "Observed Kotlin")),
                )
            controls.render(initial)
            val projects = table(controls, MappingsTable.PROJECTS)
            assertEquals(rawName, projects.getValueAt(0, 1))
            assertEquals("future-color", projects.getValueAt(0, 0))
            val renderer = assertIs<JComponent>(projects.prepareRenderer(projects.getCellRenderer(0, 1), 0, 1))
            assertNull(renderer.getClientProperty("html"))
            assertTrue(
                renderer.accessibleContext.accessibleName
                    .orEmpty()
                    .contains(rawName),
            )
            assertTrue(renderer.toolTipText.orEmpty().contains(rawName))
            val resolution = descendants(controls).filterIsInstance<ProjectLanguageResolutionPanel>().single()
            assertTrue(resolution.currentSummaryForTest().contains("Project override"))
            assertTrue(resolution.currentSummaryForTest().contains("Observed Kotlin"))
            for (contentWidth in listOf(360, 760, 1200)) {
                controls.setSize(contentWidth, 1000)
                layoutTree(controls)
                assertTrue(controls.minimumSize.width <= contentWidth)
                val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, projects) as JScrollPane
                val location = SwingUtilities.convertPoint(scroll, 0, 0, controls)
                assertTrue(location.x >= 0 && location.x + scroll.width <= contentWidth)
                assertFalse(scroll.horizontalScrollBar.isVisible)
                assertTrue(projects.width <= scroll.viewport.width)
            }
        }
    }

    private fun withVisible(
        component: JComponent,
        block: () -> Unit,
    ) {
        val window = JFrame("Ayu Islands mapping interaction test")
        try {
            window.isAutoRequestFocus = false
            window.contentPane.add(component)
            window.setSize(800, 900)
            window.setLocation(-10000, -10000)
            window.isVisible = true
            block()
        } finally {
            window.dispose()
        }
    }

    private fun view(): MappingsPanelView =
        MappingsPanelView(
            settings =
                MappingsPanelSettings(
                    projects = (0 until 20).map { MappingRow("/work/project-$it", "Project $it", "#FFCC66") },
                    languages = (0 until 12).map { MappingRow("language-$it", "Language $it", "#5CCFE6") },
                ),
            context =
                MappingsContext(
                    projectKey = "/work/current",
                    currentAccentHex = "#FFCC66",
                    canPinCurrent = true,
                    resolution =
                        ProjectLanguageResolutionPanel.State(
                            verdict = ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 100L)),
                            forcedLanguageId = null,
                            fallbackHex = null,
                            activeSource = AccentResolver.Source.PROJECT_OVERRIDE,
                            canMutate = true,
                            canRescan = true,
                        ),
                ),
            observations = MappingsObservations(),
            isEditable = true,
            isLicensed = true,
        )

    private fun fields(): StudioFields {
        val replacements =
            mapOf(
                "projectAccents" to ordered("/work/alpha" to "#FFCC66", "/work/beta" to "#5CCFE6"),
                "projectDisplayNames" to ordered("/work/alpha" to "Alpha", "/work/beta" to "Beta"),
            )
        return sessionDocument().shared.mappings.let { original ->
            original.copy(
                records = StudioList(original.records.map { it.copy(value = replacements[it.key.name] ?: it.value) }),
            )
        }
    }

    private fun ordered(vararg entries: Pair<String, String>): RawValue.OrderedMap =
        RawValue.OrderedMap(StudioList(entries.map { (key, value) -> RawValue.Entry(key, RawValue.Text(value)) }))

    private fun table(
        root: Container,
        target: MappingsTable,
    ): JTable =
        descendants(root).filterIsInstance<JTable>().single {
            it.columnCount ==
                if (target == MappingsTable.PROJECTS) 3 else 2
        }

    private fun toolbar(table: JTable): CommonActionsPanel {
        var parent = table.parent
        while (parent != null) {
            descendants(parent).filterIsInstance<CommonActionsPanel>().singleOrNull()?.let { return it }
            parent = parent.parent
        }
        error("Table has no native toolbar")
    }

    private fun perform(
        action: AnAction,
        component: JComponent,
        keyCode: Int? = null,
    ) {
        val invocation = event(action, component, keyCode)
        ActionUtil.performDumbAwareUpdate(action, invocation, false)
        assertTrue(invocation.presentation.isEnabled)
        ActionUtil.performActionDumbAwareWithCallbacks(action, invocation)
    }

    private fun event(
        action: AnAction,
        component: JComponent,
        keyCode: Int? = null,
    ): AnActionEvent =
        AnActionEvent.createEvent(
            action,
            DataManager.getInstance().getDataContext(component),
            null,
            ActionPlaces.UNKNOWN,
            ActionUiKind.NONE,
            keyCode?.let { KeyEvent(component, KeyEvent.KEY_PRESSED, 0, 0, it, KeyEvent.CHAR_UNDEFINED) },
        )

    private fun languageButton(root: Container): AbstractButton =
        descendants(root).filterIsInstance<AbstractButton>().single { it.text == "Languages" }

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
        return current === root && root.isVisible
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { child -> listOf(child) + if (child is Container) descendants(child) else emptyList() }

    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
}
