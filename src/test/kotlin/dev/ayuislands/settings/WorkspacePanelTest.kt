package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
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
import javax.swing.JSpinner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
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
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
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

        assertTrue(spinners.isNotEmpty(), "locked Workspace preview must still render width spinners")
        assertTrue(spinners.all { !it.isEnabled }, "locked Workspace preview must disable width spinners")
        spinners.first().value = (spinners.first().value as Int) + 10

        assertFalse(
            workspacePanel.isModified(),
            "locked Workspace preview spinner changes must not dirty Settings",
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
