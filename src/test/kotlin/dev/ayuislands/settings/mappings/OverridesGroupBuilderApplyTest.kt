package dev.ayuislands.settings.mappings

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.CommonActionsPanel
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import java.awt.Component
import java.awt.Container
import java.io.File
import javax.swing.JTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverridesGroupBuilderApplyTest {
    @BeforeTest
    fun setUp() {
        mockkObject(AyuVariant.Companion)
        mockkObject(AccentResolver)
        mockkObject(AccentApplicator)
        mockkObject(ProjectAccentSwapService.Companion)
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply persists and clears modified state when live reapply throws`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFCC66"
        every { AccentApplicator.applyFromHexString(any()) } throws RuntimeException("LafManager boom")
        val state = AccentMappingsState()
        val draft =
            AccentMappingsDraft().apply {
                addProject(ProjectMapping("/tmp/project", "Project", "#AABBCC"))
            }
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })
        try {
            var changeCount = 0
            builder.addPendingChangeListener { changeCount += 1 }

            builder.apply()

            assertEquals(mapOf("/tmp/project" to "#AABBCC"), state.projectAccents)
            assertFalse(builder.isModified())
            assertEquals(1, changeCount)
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `apply persists complete pending state before resolver applicator and swap`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AccentApplicator.applyFromHexString("#AABBCC") } returns true
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService
        val project = mockk<Project>(relaxed = true)
        val state = AccentMappingsState()
        val draft = AccentMappingsDraft()
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })
        var stateWasWritten = false
        var draftWasModified = false
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } answers {
            stateWasWritten =
                state.projectAccents == mapOf("/tmp/project" to "#AABBCC") &&
                state.projectDisplayNames == mapOf("/tmp/project" to "Project") &&
                state.languageAccents == mapOf("kotlin" to "#112233") &&
                state.projectFallbackAccents == mapOf("/tmp/project" to "#5CCFE6") &&
                state.forcedProjectLanguages == mapOf("/tmp/project" to "kotlin") &&
                state.languageFallbackAccent == "#73D0FF"
            draftWasModified = builder.isModified()
            assertTrue(stateWasWritten)
            assertTrue(draftWasModified)
            "#AABBCC"
        }
        try {
            buildGroup(builder, project)
            var changeCount = 0
            builder.addPendingChangeListener { changeCount += 1 }
            draft.addProject(ProjectMapping("/tmp/project", "Project", "#AABBCC"))
            draft.addLanguage(LanguageMapping("kotlin", "Kotlin", "#112233"))
            draft.setProjectFallbackAccent("/tmp/project", "#5CCFE6")
            draft.setForcedLanguage("/tmp/project", "kotlin")
            draft.setLanguageFallbackAccent("#73D0FF")

            builder.apply()

            assertEquals(mapOf("/tmp/project" to "#AABBCC"), state.projectAccents)
            assertFalse(builder.isModified())
            assertEquals(1, changeCount)
            assertTrue(stateWasWritten)
            assertTrue(draftWasModified)
            verify(exactly = 1) {
                AccentResolver.resolve(project, AyuVariant.MIRAGE)
                AccentApplicator.applyFromHexString("#AABBCC")
                swapService.notifyExternalApply("#AABBCC")
            }
            verifyOrder {
                AccentResolver.resolve(project, AyuVariant.MIRAGE)
                AccentApplicator.applyFromHexString("#AABBCC")
                swapService.notifyExternalApply("#AABBCC")
            }
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `apply is a no-op when license is unavailable`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val state = AccentMappingsState()
        val draft =
            AccentMappingsDraft().apply {
                addProject(ProjectMapping("/tmp/locked", "Locked", "#AABBCC"))
            }
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })
        try {
            builder.apply()

            assertTrue(state.projectAccents.isEmpty())
            assertTrue(builder.isModified())
            verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `license loss disables and guards project and language table actions`() {
        val projectMapping = ProjectMapping("/tmp/locked-preview", "Locked preview", "#AABBCC")
        val languageMapping = LanguageMapping("kotlin", "Kotlin", "#BBCCDD")
        val draft =
            AccentMappingsDraft().apply {
                addProject(projectMapping)
                addLanguage(languageMapping)
            }
        val state = AccentMappingsState().also(draft::writeTo)
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })
        try {
            val root = buildGroup(builder, mockk(relaxed = true))
            val tables = descendants(root, JTable::class.java)
            tables.single { it.columnCount == 3 }.selectionModel.setSelectionInterval(0, 0)
            tables.single { it.columnCount == 2 }.selectionModel.setSelectionInterval(0, 0)
            val actionsPanels = descendants(root, CommonActionsPanel::class.java)
            every { LicenseChecker.isLicensedOrGrace() } returns false

            listOf(
                CommonActionsPanel.Buttons.ADD,
                CommonActionsPanel.Buttons.EDIT,
                CommonActionsPanel.Buttons.REMOVE,
            ).forEach { button ->
                val actions = actionsPanels.mapNotNull { it.getAnAction(button) }
                assertEquals(2, actions.size)
                actions.forEach { action ->
                    val presentation = Presentation()
                    val event = mockk<AnActionEvent>(relaxed = true)
                    every { event.presentation } returns presentation
                    action.update(event)
                    assertFalse(presentation.isEnabled, "$button must disable after license loss")
                    action.actionPerformed(event)
                }
            }
            assertEquals(listOf(projectMapping), draft.projectMappings)
            assertEquals(1, draft.languageMappings.size)
            assertEquals(languageMapping.languageId, draft.languageMappings.single().languageId)
            assertEquals(languageMapping.hex, draft.languageMappings.single().hex)
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `licensed project remove leaves language mapping unchanged`() {
        val projectMapping = ProjectMapping("/tmp/removable", "Removable", "#AABBCC")
        val languageMapping = LanguageMapping("kotlin", "Kotlin", "#BBCCDD")
        val draft =
            AccentMappingsDraft().apply {
                addProject(projectMapping)
                addLanguage(languageMapping)
            }
        val state = AccentMappingsState().also(draft::writeTo)
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })
        val actionGroups = mutableListOf<ActionGroup>()
        try {
            val root = buildGroup(builder, mockk(relaxed = true), actionGroups)
            descendants(root, JTable::class.java)
                .single { it.columnCount == 3 }
                .selectionModel
                .setSelectionInterval(0, 0)
            var changeCount = 0
            builder.addPendingChangeListener { changeCount += 1 }

            actionGroups.removeAction(isProject = true).actionPerformed(mockk(relaxed = true))

            assertTrue(draft.projectMappings.isEmpty())
            assertEquals(1, draft.languageMappings.size)
            assertEquals(languageMapping.languageId, draft.languageMappings.single().languageId)
            assertEquals(languageMapping.hex, draft.languageMappings.single().hex)
            assertEquals(1, changeCount)
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `licensed language remove leaves project mapping unchanged`() {
        val projectMapping = ProjectMapping("/tmp/removable", "Removable", "#AABBCC")
        val languageMapping = LanguageMapping("kotlin", "Kotlin", "#BBCCDD")
        val draft =
            AccentMappingsDraft().apply {
                addProject(projectMapping)
                addLanguage(languageMapping)
            }
        val state = AccentMappingsState().also(draft::writeTo)
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })
        val actionGroups = mutableListOf<ActionGroup>()
        try {
            val root = buildGroup(builder, mockk(relaxed = true), actionGroups)
            descendants(root, JTable::class.java)
                .single { it.columnCount == 2 }
                .selectionModel
                .setSelectionInterval(0, 0)
            var changeCount = 0
            builder.addPendingChangeListener { changeCount += 1 }

            actionGroups.removeAction(isProject = false).actionPerformed(mockk(relaxed = true))

            assertEquals(1, draft.projectMappings.size)
            assertEquals(projectMapping.canonicalPath, draft.projectMappings.single().canonicalPath)
            assertEquals(projectMapping.hex, draft.projectMappings.single().hex)
            assertTrue(draft.languageMappings.isEmpty())
            assertEquals(1, changeCount)
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `license loss disables and guards pin current project action`() {
        val projectDirectory = File(System.getProperty("java.io.tmpdir"), "pin-after-license-loss")
        val project =
            mockk<Project>(relaxed = true) {
                every { isDefault } returns false
                every { isDisposed } returns false
                every { basePath } returns projectDirectory.path
                every { name } returns "Pinned project"
            }
        val draft = AccentMappingsDraft()
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { AccentMappingsState() })
        val actionGroups = mutableListOf<ActionGroup>()
        try {
            buildGroup(builder, project, actionGroups)

            assertEquals(2, actionGroups.size)
            val pinAction =
                actionGroups
                    .map { it as DefaultActionGroup }
                    .flatMap { it.childActionsOrStubs.toList() }
                    .single { it.templatePresentation.text == "Pin Current Project" }
            val presentation = Presentation()
            val event = mockk<AnActionEvent>(relaxed = true)
            every { event.presentation } returns presentation

            pinAction.update(event)
            assertTrue(presentation.isEnabled)

            every { LicenseChecker.isLicensedOrGrace() } returns false
            pinAction.update(event)
            assertFalse(presentation.isEnabled)

            pinAction.actionPerformed(event)
            assertTrue(draft.projectMappings.isEmpty())
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `pin current project stores global accent once and disables after pin`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val projectDirectory = File(System.getProperty("java.io.tmpdir"), "licensed-pin-project")
        val project =
            mockk<Project>(relaxed = true) {
                every { isDefault } returns false
                every { isDisposed } returns false
                every { basePath } returns projectDirectory.path
                every { name } returns "Licensed project"
            }
        val draft = AccentMappingsDraft()
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { AccentMappingsState() })
        val actionGroups = mutableListOf<ActionGroup>()
        try {
            buildGroup(builder, project, actionGroups)

            assertEquals(2, actionGroups.size)
            val pinAction =
                actionGroups
                    .map { it as DefaultActionGroup }
                    .flatMap { it.childActionsOrStubs.toList() }
                    .single { it.templatePresentation.text == "Pin Current Project" }
            val presentation = Presentation()
            val event = mockk<AnActionEvent>(relaxed = true)
            every { event.presentation } returns presentation
            val expectedMapping =
                ProjectMapping(
                    canonicalPath = projectDirectory.canonicalPath,
                    displayName = "Licensed project",
                    hex = "#5CCFE6",
                )

            pinAction.actionPerformed(event)

            assertEquals(listOf(expectedMapping), draft.projectMappings)
            pinAction.update(event)
            assertFalse(presentation.isEnabled)

            pinAction.actionPerformed(event)
            assertEquals(listOf(expectedMapping), draft.projectMappings)
        } finally {
            builder.dispose()
        }
    }

    @Test
    fun `apply uses focused project fallback when no parent is bound`() {
        val focusedProject =
            mockk<Project>(relaxed = true) {
                every { isDisposed } returns false
                every { isDefault } returns false
            }
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AccentApplicator.resolveFocusedProject() } returns focusedProject
        every { AccentResolver.resolve(focusedProject, AyuVariant.MIRAGE) } returns "#5CCFE6"
        every { AccentApplicator.applyFromHexString("#5CCFE6") } returns true
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService
        val state = AccentMappingsState()
        val draft =
            AccentMappingsDraft().apply {
                addProject(ProjectMapping("/tmp/fallback", "Fallback", "#5CCFE6"))
            }
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })
        try {
            builder.apply()

            verify(exactly = 1) { AccentApplicator.resolveFocusedProject() }
            verify(exactly = 1) { AccentResolver.resolve(focusedProject, AyuVariant.MIRAGE) }
            verify(exactly = 1) { AccentApplicator.applyFromHexString("#5CCFE6") }
        } finally {
            builder.dispose()
        }
    }

    private fun buildGroup(
        builder: OverridesGroupBuilder,
        project: Project,
        actionGroups: MutableList<ActionGroup> = mutableListOf(),
    ): Container {
        installUiServices(actionGroups)
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns AyuIslandsState()
        every { settings.getAccentForVariant(AyuVariant.MIRAGE) } returns "#5CCFE6"
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        return panel {
            builder.buildGroup(this, project)
        }
    }

    private fun installUiServices(actionGroups: MutableList<ActionGroup>) {
        mockkStatic(ApplicationManager::class)
        val application = mockk<Application>(relaxed = true)
        val actionManager = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns application
        every { application.getService(ActionManager::class.java) } returns actionManager
        every { actionManager.getAction(any()) } returns null
        every {
            actionManager.createActionToolbar(any(), capture(actionGroups), any())
        } returns mockk<ActionToolbar>(relaxed = true)

        every { application.getService(any<Class<*>>()) } answers {
            val serviceClass = firstArg<Class<*>>()
            if (serviceClass == ActionManager::class.java) {
                actionManager
            } else {
                mockkClass(serviceClass.kotlin, relaxed = true)
            }
        }
    }

    private fun List<ActionGroup>.removeAction(isProject: Boolean): AnAction {
        val groups = map { it as DefaultActionGroup }
        val group =
            groups.single { candidate ->
                candidate.childActionsOrStubs.any {
                    it.templatePresentation.text == "Pin Current Project"
                } == isProject
            }
        return group.childActionsOrStubs.single { it.templatePresentation.text == "Remove" }
    }

    private fun <T : Component> descendants(
        container: Container,
        type: Class<T>,
    ): List<T> =
        buildList {
            fun visit(component: Component) {
                if (type.isInstance(component)) add(type.cast(component))
                if (component is Container) {
                    component.components.forEach(::visit)
                }
            }
            visit(container)
        }
}
