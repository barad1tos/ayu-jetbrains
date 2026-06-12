package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspacePanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings

    @BeforeTest
    fun setUp() {
        state =
            AyuIslandsState().apply {
                projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
                commitPanelWidthMode = PanelWidthMode.FIXED.name
                gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
            }
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkStatic(ApplicationManager::class)
        mockkStatic(ProjectManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val projectManagerMock = mockk<ProjectManager>(relaxed = true)
        val actionManagerMock = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { ProjectManager.getInstance() } returns projectManagerMock
        every { projectManagerMock.openProjects } returns emptyArray()
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(experimentalUiClass) } returns experimentalUiMock
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unlicensed width controls stay visible but cannot dirty settings`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val workspacePanel = WorkspacePanel()
        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val spinners = components(dialogPanel, JSpinner::class.java)
        val pathDisplayCombo = pathDisplayCombo(dialogPanel)

        assertTrue(spinners.isNotEmpty(), "locked Workspace preview must still render width spinners")
        assertTrue(spinners.all { !it.isEnabled }, "locked Workspace preview must disable width spinners")
        assertFalse(pathDisplayCombo.isEnabled, "locked Workspace preview must disable path display combo")
        spinners.first().value = (spinners.first().value as Int) + 10
        pathDisplayCombo.selectedItem = CommitPathDisplayMode.TOOLTIP

        assertFalse(
            workspacePanel.isModified(),
            "locked Workspace preview spinner changes must not dirty Settings",
        )

        workspacePanel.apply()

        assertEquals(
            CommitPathDisplayMode.INLINE.name,
            state.commitPanelPathDisplayMode,
            "locked Workspace preview must not apply path display changes",
        )
    }

    @Test
    fun `unlicensed default workspace keeps every width mode preview visible`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.gitPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.workspaceProjectViewExpanded = true
        state.workspaceCommitPanelExpanded = true
        state.workspaceGitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val spinners = components(dialogPanel, JSpinner::class.java)

        assertTrue(spinners.isNotEmpty(), "locked Workspace preview must render width controls")
        assertTrue(
            spinners.all { it.isEffectivelyVisibleWithin(dialogPanel) && !it.isEnabled },
            "Locked Workspace preview must show every width mode control even when stored mode is Default",
        )
        spinners.first().value = (spinners.first().value as Int) + 10

        assertFalse(
            workspacePanel.isModified(),
            "Locked Workspace default-mode preview controls must not dirty Settings",
        )
    }

    @Test
    fun `commit path controls are visible but disabled in default mode`() {
        state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.workspaceCommitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val labels = components(dialogPanel, JLabel::class.java).map { it.text.orEmpty() }
        val spinners = components(dialogPanel, JSpinner::class.java)

        assertTrue(labels.any { it.contains("Path Display") })
        assertTrue(
            spinners.any {
                (it.value as? Int) == AyuIslandsState.DEFAULT_COMMIT_PATH_MIN_HIDDEN_LEVELS &&
                    !it.isEnabled
            },
            "Default mode must show disabled path min spinner",
        )
        assertTrue(
            spinners.any {
                (it.value as? Int) == AyuIslandsState.DEFAULT_COMMIT_PATH_MAX_HIDDEN_LEVELS &&
                    !it.isEnabled
            },
            "Default mode must show disabled path max spinner",
        )
    }

    @Test
    fun `commit path display explains tooltip and level controls`() {
        state.workspaceCommitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val comments = components(dialogPanel, JEditorPane::class.java).map { it.text.orEmpty() }

        assertTrue(
            comments.any { it.contains("Tooltip only hides inline paths") },
            "Path Display must explain tooltip-only mode",
        )
        assertTrue(
            comments.any { it.contains("Min/max levels control how many leading directories") },
            "Path Display must explain min/max hidden levels",
        )
    }

    @Test
    fun `commit path controls are enabled in fixed mode and dirty settings`() {
        state.commitPanelWidthMode = PanelWidthMode.FIXED.name
        state.workspaceCommitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val spinners = components(dialogPanel, JSpinner::class.java)
        val pathMinSpinner =
            spinners.first {
                (it.value as? Int) == AyuIslandsState.DEFAULT_COMMIT_PATH_MIN_HIDDEN_LEVELS &&
                    it.isEnabled
            }

        pathMinSpinner.value = 1

        assertTrue(workspacePanel.isModified())
    }

    @Test
    fun `commit path display combo switches to tooltip mode and disables level controls`() {
        state.commitPanelWidthMode = PanelWidthMode.FIXED.name
        state.workspaceCommitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val pathDisplayCombo = pathDisplayCombo(dialogPanel)
        val pathSpinners =
            components(dialogPanel, JSpinner::class.java).filter { spinner ->
                (spinner.value as? Int) in 0..10
            }

        pathDisplayCombo.selectedItem = CommitPathDisplayMode.TOOLTIP

        assertTrue(workspacePanel.isModified())
        assertTrue(pathSpinners.all { spinner -> !spinner.isEnabled })
    }

    @Test
    fun `commit path tooltip mode is applied to settings state`() {
        state.commitPanelWidthMode = PanelWidthMode.FIXED.name
        state.workspaceCommitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        pathDisplayCombo(dialogPanel).selectedItem = CommitPathDisplayMode.TOOLTIP

        workspacePanel.apply()

        assertEquals(CommitPathDisplayMode.TOOLTIP.name, state.commitPanelPathDisplayMode)
    }

    @Test
    fun `commit path hidden levels apply and reset with settings state`() {
        state.commitPanelWidthMode = PanelWidthMode.FIXED.name
        state.commitPanelPathMinHiddenLevels = 1
        state.commitPanelPathMaxHiddenLevels = 3
        state.workspaceCommitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val (pathMinSpinner, pathMaxSpinner) = pathLevelSpinners(dialogPanel)

        pathMinSpinner.value = 2
        pathMaxSpinner.value = 4

        assertTrue(workspacePanel.isModified())
        workspacePanel.apply()

        assertEquals(2, state.commitPanelPathMinHiddenLevels)
        assertEquals(4, state.commitPanelPathMaxHiddenLevels)
        assertFalse(workspacePanel.isModified())

        pathMinSpinner.value = 5
        pathMaxSpinner.value = 6
        assertTrue(workspacePanel.isModified())

        workspacePanel.reset()

        assertEquals(2, pathMinSpinner.value)
        assertEquals(4, pathMaxSpinner.value)
        assertEquals(2, state.commitPanelPathMinHiddenLevels)
        assertEquals(4, state.commitPanelPathMaxHiddenLevels)
        assertFalse(workspacePanel.isModified())
    }

    @Test
    fun `commit path min spinner lifts max spinner when min exceeds max`() {
        state.commitPanelWidthMode = PanelWidthMode.FIXED.name
        state.commitPanelPathMinHiddenLevels = 0
        state.commitPanelPathMaxHiddenLevels = 1
        state.workspaceCommitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val spinners = components(dialogPanel, JSpinner::class.java)
        val pathMinSpinner =
            spinners.first {
                (it.value as? Int) == 0 &&
                    it.isEnabled
            }
        val pathMaxSpinner =
            spinners.first {
                (it.value as? Int) == 1 &&
                    it.isEnabled
            }

        pathMinSpinner.value = 3

        assertTrue(workspacePanel.isModified())
        assertTrue((pathMaxSpinner.value as Int) >= 3)
    }

    @Test
    fun `workspace settings are grouped by capability`() {
        state.workspaceProjectViewExpanded = true
        state.workspaceCommitPanelExpanded = true
        state.workspaceGitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val labels = components(dialogPanel, JLabel::class.java).map { it.text.orEmpty() }

        assertSectionOrder(
            labels,
            listOf(
                "Editor",
                "Project View Display",
                "Tool Window Width",
                "Path Display",
            ),
        )
    }

    @Test
    fun `workspace mode combos use the same preferred width`() {
        state.workspaceProjectViewExpanded = true
        state.workspaceCommitPanelExpanded = true
        state.workspaceGitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        val modeComboWidths =
            components(dialogPanel, JComboBox::class.java)
                .filter { comboBox -> comboBox.isPanelWidthModeCombo() || comboBox.isPathDisplayCombo() }
                .map { comboBox -> comboBox.preferredSize.width }
                .toSet()

        assertEquals(1, modeComboWidths.size, "Workspace mode combos must keep min/max columns aligned")
    }

    @Test
    fun `workspace width mode controls start in the same column`() {
        state.workspaceProjectViewExpanded = true
        state.workspaceCommitPanelExpanded = true
        state.workspaceGitPanelExpanded = true
        val workspacePanel = WorkspacePanel()

        val dialogPanel =
            com.intellij.ui.dsl.builder
                .panel {
                    workspacePanel.buildPanel(this@panel, AyuVariant.MIRAGE)
                }
        dialogPanel.size = dialogPanel.preferredSize
        dialogPanel.doLayoutRecursively()
        val widthModeComboColumns =
            components(dialogPanel, JComboBox::class.java)
                .filter { comboBox -> comboBox.isPanelWidthModeCombo() }
                .map { comboBox -> comboBox.xWithin(dialogPanel) }
                .toSet()

        assertEquals(1, widthModeComboColumns.size, "Workspace width mode controls must start in one column")
    }

    private fun assertSectionOrder(
        labels: List<String>,
        expectedSections: List<String>,
    ) {
        var previousIndex = -1
        for (section in expectedSections) {
            val index = labels.indexOfFirst { label -> label.contains(section) }
            assertTrue(index > previousIndex, "$section section must appear after the previous Workspace section")
            previousIndex = index
        }
    }

    private fun pathDisplayCombo(container: Container): JComboBox<*> =
        components(container, JComboBox::class.java).first { comboBox ->
            comboBox.isPathDisplayCombo()
        }

    private fun pathLevelSpinners(container: Container): Pair<JSpinner, JSpinner> {
        val spinners =
            components(container, JSpinner::class.java)
                .filter { spinner -> (spinner.value as? Int) in 0..10 }
        val minSpinner =
            spinners.first { spinner ->
                (spinner.value as? Int) == state.commitPanelPathMinHiddenLevels
            }
        val maxSpinner =
            spinners.first { spinner ->
                (spinner.value as? Int) == state.commitPanelPathMaxHiddenLevels
            }
        return minSpinner to maxSpinner
    }

    private fun JComboBox<*>.isPanelWidthModeCombo(): Boolean =
        (0 until itemCount).any { index -> getItemAt(index) == PanelWidthMode.AUTO_FIT }

    private fun JComboBox<*>.isPathDisplayCombo(): Boolean =
        (0 until itemCount).any { index -> getItemAt(index) == CommitPathDisplayMode.TOOLTIP }

    private fun Component.xWithin(root: Component): Int = SwingUtilities.convertPoint(parent, location, root).x

    private fun Container.doLayoutRecursively() {
        doLayout()
        components.forEach { child ->
            if (child is Container) child.doLayoutRecursively()
        }
    }

    private fun <T : Component> components(
        container: Container,
        componentClass: Class<T>,
    ): List<T> =
        container.components.flatMap { child ->
            val current = if (componentClass.isInstance(child)) listOf(componentClass.cast(child)) else emptyList()
            val descendants =
                if (child is Container) {
                    components(child, componentClass)
                } else {
                    emptyList()
                }
            current + descendants
        }

    private fun Component.isEffectivelyVisibleWithin(root: Component): Boolean {
        var current: Component? = this
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }
}
