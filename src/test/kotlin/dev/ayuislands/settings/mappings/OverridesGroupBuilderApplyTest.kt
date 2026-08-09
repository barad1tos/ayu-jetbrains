package dev.ayuislands.settings.mappings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
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
        var changeCount = 0
        builder.addPendingChangeListener { changeCount += 1 }

        builder.apply()

        assertEquals(mapOf("/tmp/project" to "#AABBCC"), state.projectAccents)
        assertFalse(builder.isModified())
        assertEquals(1, changeCount)
    }

    @Test
    fun `apply invokes resolver applicator and swap once`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AccentResolver.resolve(any(), AyuVariant.MIRAGE) } returns "#AABBCC"
        every { AccentApplicator.applyFromHexString("#AABBCC") } returns true
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService
        val project = mockk<Project>(relaxed = true)
        val state = AccentMappingsState()
        val draft =
            AccentMappingsDraft().apply {
                addProject(ProjectMapping("/tmp/project", "Project", "#AABBCC"))
            }
        draft.writeTo(state)
        val builder = OverridesGroupBuilder(draft = draft, stateProvider = { state })
        buildGroup(builder, project)

        builder.apply()

        assertEquals(mapOf("/tmp/project" to "#AABBCC"), state.projectAccents)
        assertFalse(builder.isModified())
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

        builder.apply()

        assertTrue(state.projectAccents.isEmpty())
        assertTrue(builder.isModified())
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
    }

    @Test
    fun `unlicensed override remove actions cannot delete visible rows`() {
        val projectMapping = ProjectMapping("/tmp/locked-preview", "Locked preview", "#AABBCC")
        val languageMapping = LanguageMapping("kotlin", "Kotlin", "#BBCCDD")
        val draft =
            AccentMappingsDraft().apply {
                addProject(projectMapping)
                addLanguage(languageMapping)
            }
        val builder = OverridesGroupBuilder(draft = draft)
        table(builder, "projectTable").selectionModel.setSelectionInterval(0, 0)
        table(builder, "languageTable").selectionModel.setSelectionInterval(0, 0)

        val projectActions = unlicensedActions(builder, "projectActions")
        val languageActions = unlicensedActions(builder, "languageActions")

        assertFalse(projectActions.removeEnabled())
        assertFalse(languageActions.removeEnabled())
        projectActions.remove()
        languageActions.remove()
        assertEquals(listOf(projectMapping), draft.projectMappings)
        assertEquals(listOf(languageMapping), draft.languageMappings)
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

        builder.apply()

        verify(exactly = 1) { AccentApplicator.resolveFocusedProject() }
        verify(exactly = 1) { AccentResolver.resolve(focusedProject, AyuVariant.MIRAGE) }
        verify(exactly = 1) { AccentApplicator.applyFromHexString("#5CCFE6") }
    }

    private fun table(
        builder: OverridesGroupBuilder,
        fieldName: String,
    ): JTable {
        val field = OverridesGroupBuilder::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(builder) as JTable
    }

    private fun buildGroup(
        builder: OverridesGroupBuilder,
        project: Project,
    ) {
        installUiServices()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns AyuIslandsState()
        every { settings.getAccentForVariant(AyuVariant.MIRAGE) } returns "#5CCFE6"
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        panel {
            builder.buildGroup(this, project)
        }
    }

    private fun installUiServices() {
        mockkStatic(ApplicationManager::class)
        val application = mockk<Application>(relaxed = true)
        val actionManager = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns application
        every { application.getService(ActionManager::class.java) } returns actionManager
        every { actionManager.getAction(any()) } returns null

        every { application.getService(any<Class<*>>()) } answers {
            val serviceClass = firstArg<Class<*>>()
            if (serviceClass == ActionManager::class.java) {
                actionManager
            } else {
                mockkClass(serviceClass.kotlin, relaxed = true)
            }
        }
    }

    private fun unlicensedActions(
        builder: OverridesGroupBuilder,
        methodName: String,
    ): TableActionHandle {
        val tableActionsField = OverridesGroupBuilder::class.java.getDeclaredField("tableActions")
        tableActionsField.isAccessible = true
        val tableActions = tableActionsField.get(builder)
        val method = tableActions.javaClass.getDeclaredMethod(methodName, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return TableActionHandle(method.invoke(tableActions, false))
    }

    private class TableActionHandle(
        delegate: Any,
    ) {
        private val removeAction = delegate.action("getRemove")
        private val removeEnabledAction = delegate.action("getRemoveEnabled")

        fun remove() {
            removeAction()
        }

        fun removeEnabled(): Boolean = removeEnabledAction() as Boolean

        @Suppress("UNCHECKED_CAST")
        private fun Any.action(name: String): () -> Any? {
            val method = javaClass.getDeclaredMethod(name)
            method.isAccessible = true
            return method.invoke(this) as () -> Any?
        }
    }
}
