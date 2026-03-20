package dev.ayuislands

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupLicenseStateTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private val project = mockk<Project>(relaxed = true)

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.enableProDefaults() } just runs
        every { LicenseChecker.applyWorkspaceDefaults() } just runs
        every { LicenseChecker.revertToFreeDefaults(any()) } just runs
        every { LicenseChecker.notifyTrialWelcome(any()) } just runs
        every { LicenseChecker.notifyTrialExpired(any()) } just runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // applyLicensedDefaults

    @Test
    fun `licensed state calls enableProDefaults when not yet applied`() {
        state.proDefaultsApplied = false

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        verify(exactly = 1) { LicenseChecker.enableProDefaults() }
    }

    @Test
    fun `licensed state skips enableProDefaults when already applied`() {
        state.proDefaultsApplied = true

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        verify(exactly = 0) { LicenseChecker.enableProDefaults() }
    }

    @Test
    fun `re-license after expiry resets trialExpiredNotified flag`() {
        state.trialExpiredNotified = true
        state.proDefaultsApplied = true

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        assertFalse(state.trialExpiredNotified)
    }

    @Test
    fun `re-license after expiry resets proDefaultsApplied to re-apply defaults`() {
        state.trialExpiredNotified = true
        state.proDefaultsApplied = true

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        verify(exactly = 1) { LicenseChecker.enableProDefaults() }
    }

    @Test
    fun `re-license after expiry resets trialWelcomeShown`() {
        state.trialExpiredNotified = true
        state.proDefaultsApplied = true
        state.trialWelcomeShown = true

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        verify(exactly = 1) {
            LicenseChecker.notifyTrialWelcome(project)
        }
    }

    @Test
    fun `first activation shows trial welcome notification`() {
        state.proDefaultsApplied = false
        state.trialWelcomeShown = false

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        verify(exactly = 1) {
            LicenseChecker.notifyTrialWelcome(project)
        }
        assertTrue(state.trialWelcomeShown)
    }

    @Test
    fun `subsequent activation skips trial welcome notification`() {
        state.proDefaultsApplied = false
        state.trialWelcomeShown = true

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        verify(exactly = 0) { LicenseChecker.notifyTrialWelcome(any()) }
    }

    @Test
    fun `workspace migration runs when not yet applied`() {
        state.proDefaultsApplied = true
        state.workspaceDefaultsApplied = false

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        verify(exactly = 1) { LicenseChecker.applyWorkspaceDefaults() }
    }

    @Test
    fun `workspace migration skips when already applied`() {
        state.proDefaultsApplied = true
        state.workspaceDefaultsApplied = true

        StartupLicenseHandler.applyLicensedDefaults(project, settings)

        verify(exactly = 0) { LicenseChecker.applyWorkspaceDefaults() }
    }

    // applyUnlicensedDefaults

    @Test
    fun `unlicensed state calls revertToFreeDefaults`() {
        StartupLicenseHandler.applyUnlicensedDefaults(
            project,
            AyuVariant.MIRAGE,
            settings,
        )

        verify(exactly = 1) {
            LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        }
    }

    @Test
    fun `unlicensed state sets trialExpiredNotified flag`() {
        state.trialExpiredNotified = false

        StartupLicenseHandler.applyUnlicensedDefaults(
            project,
            AyuVariant.DARK,
            settings,
        )

        assertTrue(state.trialExpiredNotified)
        verify(exactly = 1) {
            LicenseChecker.notifyTrialExpired(project)
        }
    }

    @Test
    fun `unlicensed state skips notification when already notified`() {
        state.trialExpiredNotified = true

        StartupLicenseHandler.applyUnlicensedDefaults(
            project,
            AyuVariant.LIGHT,
            settings,
        )

        verify(exactly = 0) { LicenseChecker.notifyTrialExpired(any()) }
    }

    // initWorkspaceServices

    @Test
    fun `initWorkspaceServices skips when project is disposed`() {
        every { project.isDisposed } returns true

        StartupLicenseHandler.initWorkspaceServices(project, settings)
    }

    @Test
    fun `initWorkspaceServices skips all services when no customizations`() {
        every { project.isDisposed } returns false
        state.hideProjectViewHScrollbar = false
        state.hideProjectRootPath = false
        state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.gitPanelWidthMode = PanelWidthMode.DEFAULT.name

        StartupLicenseHandler.initWorkspaceServices(project, settings)
    }

    @Test
    fun `initWorkspaceServices inits ProjectView when hideRootPath enabled`() {
        every { project.isDisposed } returns false
        mockkObject(ProjectViewScrollbarManager.Companion)
        every {
            ProjectViewScrollbarManager.getInstance(project)
        } returns mockk(relaxed = true)

        state.hideProjectRootPath = true
        state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.gitPanelWidthMode = PanelWidthMode.DEFAULT.name

        StartupLicenseHandler.initWorkspaceServices(project, settings)

        verify(exactly = 1) {
            ProjectViewScrollbarManager.getInstance(project)
        }
    }

    @Test
    fun `initWorkspaceServices inits CommitPanel when mode is AUTO_FIT`() {
        every { project.isDisposed } returns false
        mockkObject(CommitPanelAutoFitManager.Companion)
        every {
            CommitPanelAutoFitManager.getInstance(project)
        } returns mockk(relaxed = true)

        state.hideProjectViewHScrollbar = false
        state.hideProjectRootPath = false
        state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.commitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.gitPanelWidthMode = PanelWidthMode.DEFAULT.name

        StartupLicenseHandler.initWorkspaceServices(project, settings)

        verify(exactly = 1) {
            CommitPanelAutoFitManager.getInstance(project)
        }
    }

    @Test
    fun `initWorkspaceServices inits GitPanel when mode is FIXED`() {
        every { project.isDisposed } returns false
        mockkObject(GitPanelAutoFitManager.Companion)
        every {
            GitPanelAutoFitManager.getInstance(project)
        } returns mockk(relaxed = true)

        state.hideProjectViewHScrollbar = false
        state.hideProjectRootPath = false
        state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.gitPanelWidthMode = PanelWidthMode.FIXED.name

        StartupLicenseHandler.initWorkspaceServices(project, settings)

        verify(exactly = 1) {
            GitPanelAutoFitManager.getInstance(project)
        }
    }
}
