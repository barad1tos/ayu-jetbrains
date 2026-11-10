package dev.ayuislands.integration

import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.AyuIslandsStartupActivity
import dev.ayuislands.StartupLicenseHandler
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageRootChangeListener
import dev.ayuislands.accent.ScanCompletionAccentRefresher
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.settings.mappings.ProjectIconAccentAssigner
import dev.ayuislands.studio.StudioApplication
import dev.ayuislands.studio.StudioApplicationGraph
import dev.ayuislands.studio.StudioApplicationState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.StudioProjects
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.profiles.StudioProjectState
import dev.ayuislands.studio.session.StudioSessions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Producer ordering against the actual application authority seam; native behavior has real Graph coverage. */
class StartupLifecycleTest {
    @get:Rule val applicationRule = ApplicationRule()
    private val calls = mutableListOf<String>()
    private val project = mockk<Project>()
    private val graph = mockk<StudioApplicationGraph>()
    private val application = mockk<StudioApplication>()
    private val sessions = mockk<StudioSessions>()
    private var result: StudioApplicationState = StudioApplicationState.Closed
    private var initialized: AcceptanceResult = AcceptanceResult.NoChanges

    @Before
    fun setup() {
        every { project.isDisposed } returns false
        every { project.isDefault } returns false
        every { project.name } returns "startup"
        val state =
            AyuIslandsState().apply {
                installedFontsSeeded = true
                lastSeenVersion = "2.8.9"
            }
        val global = (StudioLegacyMigration.plan(StudioFixtures.capture(state)) as MigrationResult.Ready).document
        val document =
            StudioProjectState.initialize(
                global,
                StudioProjectKey("/work/startup"),
                "startup",
                global.currents.global,
            )
        every { sessions.document } returns document
        every { graph.sessions } returns sessions
        every { graph.application } returns application
        every { graph.projects } returns StudioProjects({ listOf(project) }, { project }, { "/work/startup" })
        coEvery { application.start() } answers {
            calls.add("authority")
            result
        }
        coEvery { graph.initializeProject(project) } answers {
            calls.add("current")
            initialized
        }
        mockkObject(
            ScanCompletionAccentRefresher.Companion,
            ProjectLanguageRootChangeListener.Companion,
            ProjectAccentSwapService.Companion,
            EditorScrollbarManager.Companion,
            ProjectViewScrollbarManager.Companion,
            CommitPanelAutoFitManager.Companion,
            GitPanelAutoFitManager.Companion,
            ProjectIconAccentAssigner,
            StartupLicenseHandler,
            AyuVariant.Companion,
        )
        every { ScanCompletionAccentRefresher.getInstance(project) } answers {
            calls.add("scan")
            mockk()
        }
        every { ProjectLanguageRootChangeListener.getInstance(project) } answers {
            calls.add("roots")
            mockk()
        }
        val swap = mockk<ProjectAccentSwapService>()
        every { ProjectAccentSwapService.getInstance() } returns swap
        every { swap.install() } answers { calls.add("focus") }
        every { EditorScrollbarManager.getInstance(project) } answers {
            calls.add("editor")
            mockk()
        }
        every { ProjectViewScrollbarManager.getInstance(project) } answers {
            calls.add("project")
            mockk()
        }
        every { CommitPanelAutoFitManager.getInstance(project) } answers {
            calls.add("commit")
            mockk()
        }
        every { GitPanelAutoFitManager.getInstance(project) } answers {
            calls.add("git")
            mockk()
        }
        coEvery { ProjectIconAccentAssigner.assignIfAbsent(project) } answers {
            calls.add("icon")
            false
        }
        every { AyuVariant.currentThemeName() } returns "Other theme"
        every { StartupLicenseHandler.computeAdaptiveDelay() } returns 3_000
        every { StartupLicenseHandler.start(project, graph, true, 3_000) } answers { calls.add("license") }
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `project lifetime owners precede authority and Current precedes every startup appearance producer`() {
        result = StudioApplicationState.Ready(sessions)
        runBlocking { startup().execute(project) }
        assertEquals(
            listOf(
                "focused-editor",
                "scan",
                "roots",
                "focus",
                "editor",
                "project",
                "commit",
                "git",
                "open",
                "authority",
                "current",
                "icon",
                "license",
            ),
            calls,
        )
    }

    @Test
    fun `unavailable authority never reaches project initialization or appearance producers`() {
        runBlocking { startup().execute(project) }
        assertFalse("current" in calls)
        assertFalse("icon" in calls)
        assertFalse("license" in calls)
    }

    @Test
    fun `unavailable project Current stops producers even when application is Ready`() {
        result = StudioApplicationState.Ready(sessions)
        initialized = AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.TARGET_UNAVAILABLE)))
        runBlocking { startup().execute(project) }
        assertFalse("icon" in calls)
        assertFalse("license" in calls)
    }

    @Test
    fun `disposed project cannot register owners or open authority`() {
        every { project.isDisposed } returns true
        runBlocking { startup().execute(project) }
        assertEquals(listOf("focused-editor"), calls)
    }

    private fun startup() =
        AyuIslandsStartupActivity(
            initializeFocusedEditorContext = { calls.add("focused-editor") },
            openStudio = {
                calls.add("open")
                graph
            },
        )
}
