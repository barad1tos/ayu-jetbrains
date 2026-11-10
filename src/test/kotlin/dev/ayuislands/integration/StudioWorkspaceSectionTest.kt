package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspaceControls
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspacePanelView
import dev.ayuislands.settings.WorkspacePaths
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.settings.WorkspaceWidth
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.session.StudioWorkspaceEditing
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.ui.StudioWorkspaceSection
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioWorkspaceSectionTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Before
    fun forbidAmbientEffects() {
        mockkObject(
            AyuIslandsSettings.Companion,
            LicenseChecker,
            ProjectViewScrollbarManager.Companion,
            CommitPanelAutoFitManager.Companion,
            GitPanelAutoFitManager.Companion,
            EditorScrollbarManager.Companion,
        )
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Workspace controls must use explicit facts")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement must be supplied explicitly")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("Unlock belongs to the host")
        every { ProjectViewScrollbarManager.getInstance(any()) } throws
            AssertionError("Project effects belong to Apply")
        every { CommitPanelAutoFitManager.getInstance(any()) } throws AssertionError("Commit effects belong to Apply")
        every { GitPanelAutoFitManager.getInstance(any()) } throws AssertionError("Git effects belong to Apply")
        every { EditorScrollbarManager.getInstance(any()) } throws AssertionError("Editor effects belong to Apply")
    }

    @After
    fun cleanup() {
        SwingUtilities.invokeAndWait {}
        unmockkAll()
    }

    @Test
    fun `render retains every existing control and familiar groups without ambient reads or events`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<WorkspacePanelEvent>()
            val controls = WorkspaceControls(events::add)
            val initial = view()
            controls.render(initial)
            controls.render(initial.copy(settings = initial.settings.copy(hideEditorVertical = true)))
            controls.render(initial)

            assertEquals(4, descendants(controls).filterIsInstance<JCheckBox>().size)
            assertEquals(4, descendants(controls).filterIsInstance<JComboBox<*>>().size)
            assertEquals(11, descendants(controls).filterIsInstance<JSpinner>().size)
            assertEquals(
                listOf("Editor", "Project View Display", "Tool Window Width", "Path Display"),
                descendants(controls).filterIsInstance<TitledSeparator>().map { it.text },
            )
            assertEquals(1199, spinner(controls, "Project View fixed width").value)
            assertEquals(-40, spinner(controls, "Commit Panel fixed width").value)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `current draft selection changes only the structural sample and checkbox dispatch remains shared`() {
        SwingUtilities.invokeAndWait {
            val fields = sessionDocument().shared.workspace
            val working = StudioWorkspaceEditing.settings(fields)
            val current = working.copy(hideEditorVertical = !working.hideEditorVertical)
            val staged = mutableListOf<List<SessionIntent>>()
            val actions = mutableListOf<WorkspacePanelEvent>()
            val section = StudioWorkspaceSection(staged::add, actions::add)
            section.render(fields, WorkspacePanelView(working, working, isEditable = true, isLicensed = true), current)
            val component = section.content.component
            val initialValues = descendants(component).filterIsInstance<JSpinner>().map { it.value }
            val acceptedPixels = pixels(component)

            section.content.preview(PreviewMode.DRAFT)
            assertFalse(acceptedPixels.contentEquals(pixels(component)))
            assertEquals(initialValues, descendants(component).filterIsInstance<JSpinner>().map { it.value })
            section.content.preview(PreviewMode.CURRENT)
            assertTrue(acceptedPixels.contentEquals(pixels(component)))
            assertTrue(staged.isEmpty())
            assertTrue(actions.isEmpty())

            button(component, "Hide vertical scrollbar").doClick()
            val change = assertIs<SharedIntent.Field>(assertIs<SessionIntent.Shared>(staged.single().single()).intent)
            assertEquals("hideEditorVScrollbar", change.before.key.name)
            assertEquals(fields.records.single { it.key == change.before.key }, change.before)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun `locked controls preserve full preview while readonly controls cannot stage edits`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<WorkspacePanelEvent>()
            val controls = WorkspaceControls(events::add)
            val initial = view()
            controls.render(initial.copy(isLicensed = false))
            val spinners = descendants(controls).filterIsInstance<JSpinner>()

            assertTrue(spinners.all { !it.isEnabled && visibleWithin(it, controls) })
            assertTrue(descendants(controls).filterIsInstance<JCheckBox>().all { !it.isEnabled })
            spinners.first().value = 420
            button(controls, "Hide vertical scrollbar").doClick()
            assertTrue(events.isEmpty())
            button(controls, "Unlock").doClick()
            assertEquals(listOf<WorkspacePanelEvent>(WorkspacePanelEvent.Unlock), events.toList())

            events.clear()
            controls.render(initial.copy(isEditable = false))
            assertTrue(descendants(controls).filterIsInstance<JCheckBox>().all { !it.isEnabled })
            spinner(controls, "Project View fixed width").value = 477
            assertTrue(events.isEmpty())
            controls.render(initial)
            assertEquals(1199, spinner(controls, "Project View fixed width").value)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `mode gates preserve hidden values and path levels while enabling only relevant controls`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<WorkspacePanelEvent>()
            val controls = WorkspaceControls(events::add)
            val initial = view()
            val defaults = initial.settings.copy(commit = initial.settings.commit.copy(mode = "DEFAULT"))
            controls.render(initial.copy(settings = defaults))

            assertFalse(spinner(controls, "Commit path minimum hidden levels").isEnabled)
            assertFalse(spinner(controls, "Commit path maximum hidden levels").isEnabled)
            val tooltip = initial.settings.copy(paths = initial.settings.paths.copy(mode = "TOOLTIP"))
            controls.render(initial.copy(settings = tooltip))
            assertFalse(spinner(controls, "Commit path minimum hidden levels").isEnabled)
            assertEquals(3, spinner(controls, "Commit path minimum hidden levels").value)
            controls.render(initial)
            assertTrue(spinner(controls, "Commit path minimum hidden levels").isEnabled)
            assertEquals(3, spinner(controls, "Commit path minimum hidden levels").value)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `workspace controls fit supported narrow and wide content without label clipping`() {
        SwingUtilities.invokeAndWait {
            val controls = WorkspaceControls { error("Layout must not dispatch") }
            controls.render(view())
            for (width in listOf(480, 720, 1080)) {
                controls.setSize(width, controls.preferredSize.height)
                layout(controls)
                for (label in descendants(controls).filterIsInstance<JLabel>().filter { visibleWithin(it, controls) }) {
                    assertTrue(label.width >= label.minimumSize.width, "Clipped Workspace label: ${label.text}")
                    val position = SwingUtilities.convertPoint(label.parent, label.location, controls)
                    assertTrue(
                        position.x >= 0 && position.x + label.width <= width,
                        "Workspace label outside content: ${label.text}",
                    )
                }
            }
        }
    }

    @Test
    fun `long unknown width names stay inside their own preview pane`() {
        SwingUtilities.invokeAndWait {
            val controls = WorkspaceControls { error("Preview rendering must not dispatch") }
            val initial = view()
            val baseline = initial.settings.copy(project = initial.settings.project.copy(mode = "FUTURE"))
            controls.render(initial.copy(settings = baseline, preview = baseline))
            val sample =
                descendants(controls).filterIsInstance<JComponent>().single {
                    it.toolTipText == "Workspace layout sample"
                }
            val before = rightPanePixels(sample)

            val changed = baseline.copy(project = baseline.project.copy(mode = "FUTURE_WIDTH_".repeat(20)))
            controls.render(initial.copy(settings = changed, preview = changed))

            assertTrue(before.contentEquals(rightPanePixels(sample)), "Project width text painted over the Editor pane")
        }
    }

    private fun view(): WorkspacePanelView {
        val settings =
            WorkspaceSettings(
                hideEditorVertical = false,
                hideEditorHorizontal = false,
                hideProjectRootPath = false,
                hideProjectHorizontal = false,
                project = WorkspaceWidth(PanelWidthMode.AUTO_FIT.name, 300, 500, 1199),
                commit = WorkspaceWidth(PanelWidthMode.FIXED.name, 300, 500, -40),
                git = WorkspaceWidth(PanelWidthMode.AUTO_FIT.name, 300, 500, 450),
                paths = WorkspacePaths("INLINE", 3, 5),
            )
        return WorkspacePanelView(settings, settings, isEditable = true, isLicensed = true)
    }

    private fun spinner(
        container: Container,
        name: String,
    ): JSpinner =
        descendants(container).filterIsInstance<JSpinner>().single { it.accessibleContext.accessibleName == name }

    private fun button(
        container: Container,
        title: String,
    ): AbstractButton = descendants(container).filterIsInstance<AbstractButton>().single { it.text == title }

    private fun pixels(component: JComponent): IntArray {
        component.setSize(720, component.preferredSize.height)
        layout(component)
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun rightPanePixels(component: JComponent): IntArray {
        component.setSize(320, 200)
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        val start = image.width / 2
        return image.getRGB(start, 0, image.width - start, image.height, null, 0, image.width - start)
    }

    private fun layout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layout)
    }

    private fun visibleWithin(
        component: Component,
        root: Component,
    ): Boolean {
        var current: Component? = component
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }

    private fun descendants(root: Container): List<Component> =
        buildList {
            for (child in root.components) {
                add(child)
                if (child is Container) addAll(descendants(child))
            }
        }
}
