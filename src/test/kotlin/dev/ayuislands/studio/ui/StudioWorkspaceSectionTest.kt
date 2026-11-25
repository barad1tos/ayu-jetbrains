package dev.ayuislands.studio.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.TitledSeparator
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspaceControls
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspacePanelView
import dev.ayuislands.settings.WorkspacePaths
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.settings.WorkspaceSurface
import dev.ayuislands.settings.WorkspaceWidth
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.session.StudioWorkspaceEditing
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioWorkspaceSectionTest {
    @Before
    fun forbidAmbientEffects() {
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns mockk(relaxed = true)
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
        unmockkObject(
            AyuIslandsSettings.Companion,
            LicenseChecker,
            ProjectViewScrollbarManager.Companion,
            CommitPanelAutoFitManager.Companion,
            GitPanelAutoFitManager.Companion,
            EditorScrollbarManager.Companion,
        )
        unmockkStatic(ActionManager::class)
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
            val fields = catalogTestDocument().shared.workspace
            val working = StudioWorkspaceEditing.settings(fields)
            val current = working.copy(hideEditorVertical = !working.hideEditorVertical)
            val staged = mutableListOf<List<SharedIntent.Field>>()
            var unlocks = 0
            val section = StudioWorkspaceSection(staged::add) { unlocks++ }
            section.render(fields, WorkspacePanelView(working, working, isEditable = true, isLicensed = true), current)
            val component = section.content.component
            val spinners = descendants(component).filterIsInstance<JSpinner>()
            val originalModels = spinners.map { it.model }
            val acceptedPixels = pixels(component)

            section.content.preview(PreviewMode.DRAFT)
            assertFalse(acceptedPixels.contentEquals(pixels(component)))
            spinners.zip(originalModels).forEach { (spinner, model) -> assertSame(model, spinner.model) }
            section.content.preview(PreviewMode.CURRENT)
            assertTrue(acceptedPixels.contentEquals(pixels(component)))
            assertTrue(staged.isEmpty())
            assertEquals(0, unlocks)

            button(component, "Hide vertical scrollbar").doClick()
            val change = staged.single().single()
            assertEquals("hideEditorVScrollbar", change.before.key.name)
            assertEquals(fields.records.single { it.key == change.before.key }, change.before)
            section.render(fields, WorkspacePanelView(working, working, isEditable = true, isLicensed = false), current)
            button(component, "Unlock").doClick()
            assertEquals(1, unlocks)
            assertEquals(1, staged.size)
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

            spinner(controls, "Project View minimum width").value = 350
            spinner(controls, "Project View maximum width").value = 550
            spinner(controls, "Commit Panel fixed width").value = 400
            spinner(controls, "Commit path minimum hidden levels").value = 2
            spinner(controls, "Commit path maximum hidden levels").value = 6
            val combos = descendants(controls).filterIsInstance<JComboBox<*>>()
            combos.single { it.accessibleContext.accessibleName == "Commit path display" }.selectedItem =
                CommitPathDisplayMode.TOOLTIP
            assertEquals(
                listOf(
                    WorkspacePanelEvent.Minimum(WorkspaceSurface.PROJECT, 350),
                    WorkspacePanelEvent.Maximum(WorkspaceSurface.PROJECT, 550),
                    WorkspacePanelEvent.Fixed(WorkspaceSurface.COMMIT, 400),
                    WorkspacePanelEvent.PathMinimum(2),
                    WorkspacePanelEvent.PathMaximum(6),
                    WorkspacePanelEvent.PathMode(CommitPathDisplayMode.TOOLTIP),
                ),
                events,
            )
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
